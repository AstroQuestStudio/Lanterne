package fr.clubcitrouille.lanterne.client.shaderopt;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estimation STATIQUE du coût par invocation d'un programme GLSL déjà préprocessé et aplati
 * (options appliquées, #include résolus, macros développées).
 *
 * <p>Ce n'est pas un chronomètre : ce sont des ordres de grandeur RELATIFS pour comparer deux versions d'un
 * même shader (avant/après une transformation) ou deux passes entre elles. Le pire cas est retenu : les
 * deux branches d'un {@code if} comptent, une boucle à borne inconnue compte huit tours.
 *
 * <p>Java pur, sans dépendance à Minecraft : le même code sert au mod et à l'outil hors jeu.
 */
public final class CostModel {
    private CostModel() {}

    private static final Set<String> FETCH = Set.of("texture", "texture2D", "texture3D", "textureLod", "texture2DLod",
            "textureGrad", "textureProj", "textureOffset", "textureGather", "texelFetch", "shadow2D", "shadow2DLod",
            "textureLodOffset", "imageLoad", "textureQueryLod", "shadow2DProj", "texture2DProj");
    private static final Set<String> TRANSC = Set.of("pow", "exp", "exp2", "log", "log2", "sin", "cos", "tan", "asin",
            "acos", "atan", "sinh", "cosh", "tanh");
    private static final Set<String> SQRT = Set.of("sqrt", "inversesqrt", "normalize", "length", "distance",
            "reflect", "refract");
    private static final Set<String> STORE = Set.of("imageStore", "imageAtomicAdd", "imageAtomicMax", "imageAtomicOr",
            "imageAtomicExchange", "imageAtomicMin", "imageAtomicAnd", "imageAtomicXor", "imageAtomicCompSwap");

    /** Vecteur de coût (par invocation, pire cas). */
    public static final class Cost {
        public double fetch, trans, sqrt, alu, store, branch;
        public int unknownLoops;

        void add(Cost o, double k) {
            fetch += o.fetch * k;
            trans += o.trans * k;
            sqrt += o.sqrt * k;
            alu += o.alu * k;
            store += o.store * k;
            branch += o.branch * k;
            unknownLoops += o.unknownLoops;
        }

        /** Score relatif : les échantillons de texture et les écritures pèsent le plus (bande passante). */
        public double score() {
            return 10 * fetch + 4 * trans + 2 * sqrt + 0.25 * alu + 10 * store;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "fetch=%.0f trans=%.0f sqrt=%.0f alu=%.0f store=%.0f branch=%.0f boucles?=%d score=%.0f",
                    fetch, trans, sqrt, alu, store, branch, unknownLoops, score());
        }
    }

    private static final Pattern CALL = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern FOR = Pattern.compile("\\bfor\\s*\\(([^;]*);([^;]*);([^)]*)\\)");
    private static final Pattern WHILE = Pattern.compile("\\bwhile\\s*\\(");
    private static final Pattern IFP = Pattern.compile("\\bif\\s*\\(");

    /** Coût de {@code main()} du programme aplati, ou un coût nul si la fonction est absente. */
    public static Cost estimate(String flat) {
        return new Analyzer(flat).fn("main");
    }

    /** Nombre de lignes non vides d'un texte aplati. */
    public static long lines(String flat) {
        return flat.lines().filter(l -> !l.isBlank()).count();
    }

    static String blockAfter(String s, int open) {
        int d = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') d++;
            else if (c == '}') {
                d--;
                if (d == 0) return s.substring(open + 1, i);
            }
        }
        return s.substring(open + 1);
    }

    private static final class Analyzer {
        final Map<String, String> fns = new HashMap<>();
        final Map<String, Cost> memo = new HashMap<>();
        final Set<String> busy = new HashSet<>();

        Analyzer(String flat) {
            int depth = 0;
            int n = flat.length();
            int i = 0;
            while (i < n) {
                char c = flat.charAt(i);
                if (c == '{') {
                    if (depth == 0) {
                        int j = i - 1;
                        while (j >= 0 && Character.isWhitespace(flat.charAt(j))) j--;
                        if (j >= 0 && flat.charAt(j) == ')') {
                            int k = j, d = 0;
                            while (k >= 0) {
                                char ch = flat.charAt(k);
                                if (ch == ')') d++;
                                else if (ch == '(') {
                                    d--;
                                    if (d == 0) break;
                                }
                                k--;
                            }
                            int e = k - 1;
                            while (e >= 0 && Character.isWhitespace(flat.charAt(e))) e--;
                            int b = e;
                            while (b >= 0 && (Character.isLetterOrDigit(flat.charAt(b)) || flat.charAt(b) == '_')) b--;
                            String name = flat.substring(b + 1, e + 1);
                            String body = blockAfter(flat, i);
                            if (!name.isEmpty()) fns.put(name, body);
                            i += body.length() + 2;
                            continue;
                        }
                        String body = blockAfter(flat, i);
                        i += body.length() + 2;
                        continue;
                    }
                    depth++;
                } else if (c == '}') depth = Math.max(0, depth - 1);
                i++;
            }
        }

        Cost fn(String name) {
            Cost known = memo.get(name);
            if (known != null) return known;
            if (!fns.containsKey(name) || busy.contains(name)) return new Cost();
            busy.add(name);
            Cost c = body(fns.get(name));
            busy.remove(name);
            memo.put(name, c);
            return c;
        }

        Cost body(String text) {
            Cost total = new Cost();
            StringBuilder rest = new StringBuilder();
            Matcher fm = FOR.matcher(text);
            int i = 0;
            int last = 0;
            while (i < text.length() && fm.find(i)) {
                int p = fm.end();
                while (p < text.length() && Character.isWhitespace(text.charAt(p))) p++;
                String loopBody;
                int endPos;
                if (p < text.length() && text.charAt(p) == '{') {
                    loopBody = blockAfter(text, p);
                    endPos = p + loopBody.length() + 2;
                } else {
                    int semi = text.indexOf(';', p);
                    if (semi < 0) semi = text.length() - 1;
                    loopBody = text.substring(p, semi + 1);
                    endPos = semi + 1;
                }
                double iters = iterations(fm.group(1), fm.group(2), fm.group(3));
                Cost inner = body(loopBody);
                inner.add(leaf(fm.group(2) + ";" + fm.group(3)), 1);
                boolean unknown = iters < 0;
                Cost scaled = new Cost();
                scaled.add(inner, unknown ? 8 : iters);
                if (unknown) scaled.unknownLoops++;
                rest.append(text, last, fm.start());
                last = endPos;
                total.add(scaled, 1);
                i = endPos;
            }
            rest.append(text.substring(Math.min(last, text.length())));
            String r = rest.toString();
            Matcher wm = WHILE.matcher(r);
            while (wm.find()) total.unknownLoops++;
            total.add(leaf(r), 1);
            return total;
        }

        Cost leaf(String t) {
            Cost c = new Cost();
            Matcher m = CALL.matcher(t);
            while (m.find()) {
                String n = m.group(1);
                if (FETCH.contains(n)) c.fetch++;
                else if (TRANSC.contains(n)) c.trans++;
                else if (SQRT.contains(n)) c.sqrt++;
                else if (STORE.contains(n)) c.store++;
                else if (fns.containsKey(n)) c.add(fn(n), 1);
            }
            Matcher im = IFP.matcher(t);
            while (im.find()) c.branch++;
            for (int k = 0; k < t.length(); k++) {
                char ch = t.charAt(k);
                if (ch == '*' || ch == '/' || ch == '+' || ch == '-') c.alu++;
                else if (ch == '?') c.branch++;
            }
            return c;
        }

        double iterations(String init, String cond, String step) {
            Matcher a = Pattern.compile("(\\w+)\\s*=\\s*(-?\\d+(?:\\.\\d+)?)").matcher(init);
            if (!a.find()) return -1;
            double start = Double.parseDouble(a.group(2));
            String var = a.group(1);
            Matcher b = Pattern.compile(Pattern.quote(var) + "\\s*(<=|<|!=|>=|>)\\s*(-?\\d+(?:\\.\\d+)?)").matcher(cond);
            if (!b.find()) return -1;
            double bound = Double.parseDouble(b.group(2));
            double stp = 1;
            Matcher c = Pattern.compile(Pattern.quote(var) + "\\s*(\\+=|-=)\\s*(\\d+(?:\\.\\d+)?)").matcher(step);
            if (c.find()) {
                stp = Double.parseDouble(c.group(2));
                if (c.group(1).equals("-=")) stp = -stp;
            } else if (step.contains("--")) stp = -1;
            String op = b.group(1);
            double n;
            if (stp > 0) n = Math.ceil(((op.equals("<=") ? bound + 1 : bound) - start) / stp);
            else n = Math.ceil((start - (op.equals(">=") ? bound - 1 : bound)) / -stp);
            return Math.max(0, n);
        }
    }
}
