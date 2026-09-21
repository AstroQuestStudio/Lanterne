import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Outil d'analyse de shaderpacks (hors jeu) pour l'optimiseur de shaders de Lanterne.
 *
 * Usage :
 *   java ShaderTool.java scan  <dossier shaders> [profil]
 *   java ShaderTool.java cost  <dossier shaders> [profil] [--world world0]
 *   java ShaderTool.java dump  <dossier shaders> <programme rel.> <sortie> [profil]
 *
 * Il reproduit ce que fait Iris avant le compilateur : options (#define / const) surchargeables par un
 * profil de shaders.properties, macros standard d'Iris, #include aplatis, macros developpees. Sur ce texte
 * il estime un COUT STATIQUE par pixel (echantillons de texture, transcendantes, ALU, boucles) en suivant
 * les appels de fonctions. Les chiffres sont des ordres de grandeur RELATIFS, pas des millisecondes.
 */
public class ShaderTool {

    // ---------------------------------------------------------------- macros
    record Macro(String name, List<String> params, String body) {}

    static final Map<String, String> STD = new LinkedHashMap<>();
    static {
        STD.put("MC_VERSION", "12111");
        STD.put("MC_MIPMAP_LEVEL", "4");
        STD.put("IRIS_VERSION", "10711");
        STD.put("MC_GL_VERSION", "330");
        STD.put("MC_GLSL_VERSION", "330");
        STD.put("MC_OS_WINDOWS", "");
        STD.put("MC_GL_VENDOR_NVIDIA", "");
        STD.put("MC_GL_RENDERER_GEFORCE", "");
        STD.put("IS_IRIS", "");
        STD.put("IRIS_REQUIRES_SEPARATE_ENTITY_DRAWS", "");
        STD.put("MAX_COLOR_BUFFERS", "16");
        STD.put("IRIS_HAS_TRANSLUCENCY_SORTING", "");
        STD.put("IRIS_INLINE_GLINT", "");
        STD.put("IRIS_TAG_SUPPORT", "2");
        STD.put("MC_NORMAL_MAP", "");
        STD.put("MC_SPECULAR_MAP", "");
        STD.put("MC_RENDER_QUALITY", "1.0");
        STD.put("MC_SHADOW_QUALITY", "1.0");
        STD.put("MC_HAND_DEPTH", "0.125");
        STD.put("IRIS_FEATURE_FADE_VARIABLE", "");
        STD.put("IRIS_FEATURE_SSBO", "");
        STD.put("IRIS_FEATURE_CUSTOM_IMAGES", "");
        STD.put("IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE", "");
        STD.put("IRIS_FEATURE_ENTITY_TRANSLUCENT", "");
    }

    static class Pre {
        final Path root;
        final Map<String, Macro> macros = new HashMap<>();
        final Map<String, String> overrides;
        final StringBuilder out = new StringBuilder();
        int includes = 0;
        final Set<String> missing = new TreeSet<>();

        Pre(Path root, Map<String, String> overrides) {
            this.root = root;
            this.overrides = overrides;
            for (var e : STD.entrySet()) macros.put(e.getKey(), new Macro(e.getKey(), null, e.getValue()));
        }

        static String stripComments(String s) {
            StringBuilder b = new StringBuilder(s.length());
            int n = s.length();
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                    while (i < n && s.charAt(i) != '\n') i++;
                    b.append('\n');
                } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                    i += 2;
                    int st = i;
                    while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                        if (s.charAt(i) == '\n') b.append('\n');
                        i++;
                    }
                    // /* DRAWBUFFERS:0367 */ et /* RENDERTARGETS: 0,3 */ : on garde un marqueur lisible
                    String body = s.substring(st, Math.min(i, n)).strip();
                    if (body.startsWith("DRAWBUFFERS") || body.startsWith("RENDERTARGETS")) {
                        b.append("\n//@@").append(body.replaceAll("\\s+", "")).append('\n');
                    }
                    i++;
                } else b.append(c);
            }
            return b.toString();
        }

        void run(Path file) throws IOException {
            process(file, 0);
        }

        void process(Path file, int depth) throws IOException {
            if (depth > 48) return;
            String text;
            try {
                text = Files.readString(file, StandardCharsets.ISO_8859_1);
            } catch (NoSuchFileException e) {
                missing.add(root.relativize(file).toString());
                return;
            }
            text = text.replace("\r", "");
            text = stripComments(text);
            text = text.replace("\\\n", " ");
            String[] lines = text.split("\n", -1);
            // pile de conditions : [actif parent, deja pris, actif courant]
            Deque<boolean[]> stack = new ArrayDeque<>();
            boolean active = true;
            for (String raw : lines) {
                String line = raw.strip();
                if (line.startsWith("#")) {
                    String d = line.substring(1).strip();
                    String kw = d.split("\\s+", 2)[0];
                    String rest = d.length() > kw.length() ? d.substring(kw.length()).strip() : "";
                    // "#ifdef" collé à un nom sans espace : cas "#if(" ou "#elif("
                    if (kw.startsWith("if(") || kw.startsWith("elif(")) {
                        int p = kw.indexOf('(');
                        rest = d.substring(p).strip();
                        kw = kw.substring(0, p);
                    }
                    switch (kw) {
                        case "ifdef" -> {
                            boolean c = macros.containsKey(rest.split("\\s+")[0]);
                            stack.push(new boolean[]{active, c, c});
                            active = active && c;
                        }
                        case "ifndef" -> {
                            boolean c = !macros.containsKey(rest.split("\\s+")[0]);
                            stack.push(new boolean[]{active, c, c});
                            active = active && c;
                        }
                        case "if" -> {
                            boolean c = active && evalIf(rest);
                            stack.push(new boolean[]{active, c, c});
                            active = active && c;
                        }
                        case "elif" -> {
                            if (stack.isEmpty()) break;
                            boolean[] f = stack.peek();
                            boolean c = f[0] && !f[1] && evalIf(rest);
                            if (c) f[1] = true;
                            f[2] = c;
                            active = f[0] && c;
                        }
                        case "else" -> {
                            if (stack.isEmpty()) break;
                            boolean[] f = stack.peek();
                            boolean c = f[0] && !f[1];
                            f[1] = true;
                            f[2] = c;
                            active = c;
                        }
                        case "endif" -> {
                            if (stack.isEmpty()) break;
                            boolean[] f = stack.pop();
                            active = f[0];
                        }
                        case "define" -> {
                            if (active) define(rest);
                        }
                        case "undef" -> {
                            if (active) macros.remove(rest.split("\\s+")[0]);
                        }
                        case "include" -> {
                            if (active) {
                                Matcher m = Pattern.compile("[\"<]([^\">]+)[\">]").matcher(rest);
                                if (m.find()) {
                                    String p = m.group(1);
                                    Path target = p.startsWith("/") ? root.resolve(p.substring(1))
                                            : file.getParent().resolve(p);
                                    includes++;
                                    process(target.normalize(), depth + 1);
                                }
                            }
                        }
                        default -> {
                            // #version, #extension, #pragma, #line, #error : conservés pour information
                            if (active && (kw.equals("version") || kw.equals("extension"))) {
                                out.append('#').append(d).append('\n');
                            }
                        }
                    }
                } else if (active) {
                    String l = raw;
                    // const float X = v; surchargée par un profil ?
                    if (!overrides.isEmpty()) {
                        Matcher cm = CONST_DECL.matcher(l);
                        if (cm.find() && overrides.containsKey(cm.group(2))) {
                            l = cm.group(1) + overrides.get(cm.group(2)) + cm.group(4);
                        }
                    }
                    out.append(expand(l, new HashSet<>(), 0)).append('\n');
                }
            }
        }

        static final Pattern CONST_DECL = Pattern.compile("^(\\s*const\\s+\\w+\\s+(\\w+)\\s*=\\s*)([^;]+)(;.*)$");

        void define(String rest) {
            Matcher m = Pattern.compile("^(\\w+)(\\(([^)]*)\\))?\\s*(.*)$").matcher(rest);
            if (!m.find()) return;
            String name = m.group(1);
            List<String> params = null;
            if (m.group(2) != null) {
                params = Arrays.stream(m.group(3).split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
            }
            String body = m.group(4).strip();
            if (params == null && overrides.containsKey(name)) body = overrides.get(name);
            macros.put(name, new Macro(name, params, body));
        }

        static final Pattern IDENT = Pattern.compile("[A-Za-z_]\\w*");

        String expand(String s, Set<String> active, int depth) {
            if (depth > 12 || s.isEmpty()) return s;
            Matcher m = IDENT.matcher(s);
            StringBuilder b = new StringBuilder();
            int last = 0;
            boolean changed = false;
            while (m.find()) {
                String id = m.group();
                Macro mac = macros.get(id);
                if (mac == null || active.contains(id)) continue;
                // ne pas toucher aux nombres flottants du type 1e5 (ident précédé d'un chiffre)
                if (m.start() > 0 && Character.isDigit(s.charAt(m.start() - 1))) continue;
                if (mac.params == null) {
                    b.append(s, last, m.start());
                    Set<String> a2 = new HashSet<>(active);
                    a2.add(id);
                    b.append(expand(mac.body, a2, depth + 1));
                    last = m.end();
                    changed = true;
                } else {
                    int p = m.end();
                    while (p < s.length() && s.charAt(p) == ' ') p++;
                    if (p >= s.length() || s.charAt(p) != '(') continue;
                    int q = p + 1, dpt = 1;
                    List<String> args = new ArrayList<>();
                    int st = q;
                    while (q < s.length() && dpt > 0) {
                        char c = s.charAt(q);
                        if (c == '(') dpt++;
                        else if (c == ')') {
                            dpt--;
                            if (dpt == 0) {
                                args.add(s.substring(st, q).strip());
                                break;
                            }
                        } else if (c == ',' && dpt == 1) {
                            args.add(s.substring(st, q).strip());
                            st = q + 1;
                        }
                        q++;
                    }
                    if (dpt != 0) continue;
                    String body = mac.body;
                    for (int i = 0; i < mac.params.size() && i < args.size(); i++) {
                        body = body.replaceAll("\\b" + Pattern.quote(mac.params.get(i)) + "\\b",
                                Matcher.quoteReplacement(args.get(i)));
                    }
                    body = body.replace("##", "");
                    b.append(s, last, m.start());
                    Set<String> a2 = new HashSet<>(active);
                    a2.add(id);
                    b.append(expand(body, a2, depth + 1));
                    last = q + 1;
                    m.region(last, s.length());
                    changed = true;
                }
            }
            if (!changed) return s;
            b.append(s.substring(last));
            return b.toString();
        }

        // ---- évaluation d'un #if
        boolean evalIf(String expr) {
            try {
                expr = expr.replaceAll("defined\\s*\\(\\s*(\\w+)\\s*\\)", "defined_$1")
                        .replaceAll("defined\\s+(\\w+)", "defined_$1");
                Matcher m = Pattern.compile("defined_(\\w+)").matcher(expr);
                StringBuilder b = new StringBuilder();
                while (m.find()) m.appendReplacement(b, macros.containsKey(m.group(1)) ? "1" : "0");
                m.appendTail(b);
                String e = expand(b.toString(), new HashSet<>(), 0);
                return new Ev(e).parse() != 0;
            } catch (RuntimeException ex) {
                return false;
            }
        }
    }

    /** Petit évaluateur d'entiers pour #if. Les identifiants inconnus valent 0, les flottants sont tronqués. */
    static class Ev {
        final String s;
        int p = 0;

        Ev(String s) {
            this.s = s;
        }

        long parse() {
            long v = ternary();
            return v;
        }

        void ws() {
            while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        }

        boolean eat(String t) {
            ws();
            if (s.startsWith(t, p)) {
                p += t.length();
                return true;
            }
            return false;
        }

        long ternary() {
            long c = lor();
            if (eat("?")) {
                long a = ternary();
                eat(":");
                long b = ternary();
                return c != 0 ? a : b;
            }
            return c;
        }

        long lor() {
            long v = land();
            while (true) {
                ws();
                if (s.startsWith("||", p)) {
                    p += 2;
                    long r = land();
                    v = (v != 0 || r != 0) ? 1 : 0;
                } else return v;
            }
        }

        long land() {
            long v = bor();
            while (true) {
                ws();
                if (s.startsWith("&&", p)) {
                    p += 2;
                    long r = bor();
                    v = (v != 0 && r != 0) ? 1 : 0;
                } else return v;
            }
        }

        long bor() {
            long v = band();
            while (true) {
                ws();
                if (p < s.length() && s.charAt(p) == '|' && !s.startsWith("||", p)) {
                    p++;
                    v |= band();
                } else return v;
            }
        }

        long band() {
            long v = eq();
            while (true) {
                ws();
                if (p < s.length() && s.charAt(p) == '&' && !s.startsWith("&&", p)) {
                    p++;
                    v &= eq();
                } else return v;
            }
        }

        long eq() {
            long v = rel();
            while (true) {
                if (eat("==")) v = v == rel() ? 1 : 0;
                else if (eat("!=")) v = v != rel() ? 1 : 0;
                else return v;
            }
        }

        long rel() {
            long v = shift();
            while (true) {
                if (eat("<=")) v = v <= shift() ? 1 : 0;
                else if (eat(">=")) v = v >= shift() ? 1 : 0;
                else if (eat("<") ) v = v < shift() ? 1 : 0;
                else if (eat(">")) v = v > shift() ? 1 : 0;
                else return v;
            }
        }

        long shift() {
            long v = add();
            while (true) {
                if (eat("<<")) v <<= add();
                else if (eat(">>")) v >>= add();
                else return v;
            }
        }

        long add() {
            long v = mul();
            while (true) {
                ws();
                if (eat("+")) v += mul();
                else if (eat("-")) v -= mul();
                else return v;
            }
        }

        long mul() {
            long v = unary();
            while (true) {
                if (eat("*")) v *= unary();
                else if (eat("/")) {
                    long d = unary();
                    v = d == 0 ? 0 : v / d;
                } else if (eat("%")) {
                    long d = unary();
                    v = d == 0 ? 0 : v % d;
                } else return v;
            }
        }

        long unary() {
            ws();
            if (eat("!")) return unary() == 0 ? 1 : 0;
            if (eat("-")) return -unary();
            if (eat("+")) return unary();
            if (eat("~")) return ~unary();
            if (eat("(")) {
                long v = ternary();
                eat(")");
                return v;
            }
            ws();
            int st = p;
            if (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) {
                if (s.startsWith("0x", p) || s.startsWith("0X", p)) {
                    p += 2;
                    int h = p;
                    while (p < s.length() && Character.digit(s.charAt(p), 16) >= 0) p++;
                    return Long.parseLong(s.substring(h, p), 16);
                }
                while (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) p++;
                if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
                    p++;
                    if (p < s.length() && (s.charAt(p) == '-' || s.charAt(p) == '+')) p++;
                    while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
                }
                String num = s.substring(st, p);
                while (p < s.length() && "uUlLfF".indexOf(s.charAt(p)) >= 0) p++;
                return (long) Double.parseDouble(num);
            }
            while (p < s.length() && (Character.isLetterOrDigit(s.charAt(p)) || s.charAt(p) == '_')) p++;
            if (p == st) throw new RuntimeException("syntaxe #if : " + s);
            return 0; // identifiant inconnu
        }
    }

    // ------------------------------------------------------------- coûts
    static final Set<String> FETCH = Set.of("texture", "texture2D", "texture3D", "textureLod", "texture2DLod",
            "textureGrad", "textureProj", "textureOffset", "textureGather", "texelFetch", "shadow2D", "shadow2DLod",
            "textureLodOffset", "imageLoad", "textureSize", "textureQueryLod", "shadow2DProj", "texture2DProj");
    static final Set<String> TRANSC = Set.of("pow", "exp", "exp2", "log", "log2", "sin", "cos", "tan", "asin", "acos",
            "atan", "sinh", "cosh", "tanh");
    static final Set<String> SQRT = Set.of("sqrt", "inversesqrt", "normalize", "length", "distance", "reflect", "refract");
    static final Set<String> STORE = Set.of("imageStore", "imageAtomicAdd", "imageAtomicMax", "imageAtomicOr",
            "imageAtomicExchange", "imageAtomicMin", "imageAtomicAnd", "imageAtomicXor", "imageAtomicCompSwap");
    static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "return", "else", "do", "layout",
            "uniform", "in", "out", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4",
            "mat2", "mat3", "mat4", "float", "int", "uint", "bool", "mix", "clamp", "min", "max", "smoothstep",
            "step", "abs", "floor", "ceil", "fract", "sign", "mod", "dot", "cross", "dFdx", "dFdy", "fwidth");

    /** Vecteur de coût (par invocation, pire cas : les deux branches d'un if comptent). */
    static class Cost {
        double fetch, trans, sqrt, alu, store, branch, loops;
        int unknownLoops;

        void add(Cost o, double k) {
            fetch += o.fetch * k;
            trans += o.trans * k;
            sqrt += o.sqrt * k;
            alu += o.alu * k;
            store += o.store * k;
            branch += o.branch * k;
            unknownLoops += o.unknownLoops;
            loops += o.loops * k;
        }

        double score() {
            return 10 * fetch + 4 * trans + 2 * sqrt + 0.25 * alu + 10 * store;
        }
    }

    static String blockAfter(String s, int open) { // s.charAt(open) == '{'
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

    static final Pattern CALL = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\(");
    static final Pattern FOR = Pattern.compile("\\bfor\\s*\\(([^;]*);([^;]*);([^)]*)\\)");
    static final Pattern WHILE = Pattern.compile("\\bwhile\\s*\\(");
    static final Pattern IFP = Pattern.compile("\\bif\\s*\\(");

    static class Analyzer {
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
                        // fonction ? cherche ')' juste avant
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
                        // struct / interface block : sauter
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
            if (memo.containsKey(name)) return memo.get(name);
            if (!fns.containsKey(name) || busy.contains(name)) return new Cost();
            busy.add(name);
            Cost c = body(fns.get(name));
            busy.remove(name);
            memo.put(name, c);
            return c;
        }

        /** Cout d'un bloc de texte : boucles = corps x iterations, appels = cout de la fonction appelee. */
        Cost body(String text) {
            Cost total = new Cost();
            StringBuilder rest = new StringBuilder();
            int i = 0;
            Matcher fm = FOR.matcher(text);
            List<int[]> loopSpans = new ArrayList<>();
            List<Cost> loopCosts = new ArrayList<>();
            while (fm.find(i)) {
                // corps
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
                Cost header = leaf(fm.group(2) + ";" + fm.group(3));
                inner.add(header, 1);
                Cost scaled = new Cost();
                boolean unknown = iters < 0;
                double k = unknown ? 8 : iters;
                scaled.add(inner, k);
                if (unknown) scaled.unknownLoops++;
                scaled.loops = k * Math.max(1, inner.loops == 0 ? 1 : 1);
                loopSpans.add(new int[]{fm.start(), endPos});
                loopCosts.add(scaled);
                i = endPos;
                if (i >= text.length()) break;
            }
            int last = 0;
            for (int idx = 0; idx < loopSpans.size(); idx++) {
                rest.append(text, last, loopSpans.get(idx)[0]);
                last = loopSpans.get(idx)[1];
                total.add(loopCosts.get(idx), 1);
            }
            rest.append(text.substring(Math.min(last, text.length())));
            String r = rest.toString();
            // while : borne inconnue (8)
            Matcher wm = WHILE.matcher(r);
            while (wm.find()) {
                total.unknownLoops++;
                total.loops += 8;
            }
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

    // -------------------------------------------------------------- commandes
    static Map<String, Map<String, String>> readProfiles(Path root) throws IOException {
        Map<String, Map<String, String>> res = new LinkedHashMap<>();
        Path sp = root.resolve("shaders.properties");
        if (!Files.exists(sp)) return res;
        for (String l : Files.readAllLines(sp, StandardCharsets.ISO_8859_1)) {
            l = l.strip();
            Matcher m = Pattern.compile("^profile\\.(\\w+)\\s*=\\s*(.*)$").matcher(l);
            if (!m.find()) continue;
            Map<String, String> opts = new LinkedHashMap<>();
            for (String tok : m.group(2).split("\\s+")) {
                int e = tok.indexOf('=');
                if (e > 0) opts.put(tok.substring(0, e), tok.substring(e + 1));
                else if (tok.startsWith("profile.")) {
                    // héritage : profile.X
                    Map<String, String> parent = res.get(tok.substring(8));
                    if (parent != null) opts.putAll(parent);
                }
            }
            res.put(m.group(1), opts);
        }
        return res;
    }

    static List<Path> entries(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString();
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("lib/") || rel.startsWith("program/") || rel.startsWith("include/") || n.startsWith("clrwl_")) return false;
                return n.endsWith(".fsh") || n.endsWith(".vsh") || n.endsWith(".gsh") || n.endsWith(".csh");
            }).sorted().collect(Collectors.toList());
        }
    }

    static String flatten(Path root, Path entry, Map<String, String> ov, Pre[] outPre) throws IOException {
        Pre pre = new Pre(root, ov);
        pre.run(entry);
        if (outPre != null) outPre[0] = pre;
        return pre.out.toString();
    }

    public static void main(String[] a) throws Exception {
        if (a.length < 2) {
            System.err.println("usage: scan|cost|dump <dossier shaders> ...");
            return;
        }
        Path root = Path.of(a[1]);
        switch (a[0]) {
            case "scan" -> scan(root);
            case "cost" -> {
                String profile = a.length > 2 && !a[2].startsWith("--") ? a[2] : null;
                String world = "world0";
                for (int i = 0; i < a.length - 1; i++) if (a[i].equals("--world")) world = a[i + 1];
                cost(root, profile, world);
            }
            case "dump" -> {
                Map<String, String> ov = new LinkedHashMap<>();
                if (a.length > 4) ov = readProfiles(root).getOrDefault(a[4], new LinkedHashMap<>());
                Files.writeString(Path.of(a[3]), flatten(root, root.resolve(a[2]), ov, null));
            }
            default -> System.err.println("commande inconnue");
        }
    }

    static void scan(Path root) throws IOException {
        var en = entries(root);
        long bytes = 0;
        int files = 0;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                bytes += Files.size(p);
                files++;
            }
        }
        int fsh = 0, vsh = 0, gsh = 0, csh = 0;
        Set<String> names = new TreeSet<>();
        for (Path p : en) {
            String n = p.getFileName().toString();
            if (n.endsWith(".fsh")) fsh++;
            else if (n.endsWith(".vsh")) vsh++;
            else if (n.endsWith(".gsh")) gsh++;
            else csh++;
            names.add(n.substring(0, n.lastIndexOf('.')));
        }
        int options = 0, images = 0, ssbo = 0, ifdefs = 0;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String n = p.getFileName().toString();
                if (!(n.endsWith(".glsl") || n.endsWith(".fsh") || n.endsWith(".vsh") || n.endsWith(".csh")
                        || n.endsWith(".gsh") || n.endsWith(".inc") || n.endsWith(".h"))) continue;
                String t = Files.readString(p, StandardCharsets.ISO_8859_1);
                options += (int) Pattern.compile("//\\s*\\[").matcher(t).results().count();
                images += (int) Pattern.compile("\\b(u?image[123]D\\w*)").matcher(t).results().count();
                ssbo += (int) Pattern.compile("\\bbuffer\\s+\\w+\\s*\\{|layout\\s*\\(\\s*std430").matcher(t).results().count();
            }
        }
        String props = Files.exists(root.resolve("shaders.properties"))
                ? Files.readString(root.resolve("shaders.properties"), StandardCharsets.ISO_8859_1) : "";
        int custom = (int) Pattern.compile("(?m)^\\s*(uniform|variable)\\.").matcher(props).results().count();
        int profiles = readProfiles(root).size();
        int composites = 0, deferred = 0;
        for (String n : names) {
            if (n.startsWith("composite")) composites++;
            if (n.startsWith("deferred")) deferred++;
        }
        boolean shadowDh = names.stream().anyMatch(n -> n.startsWith("dh_"));
        System.out.printf(Locale.ROOT,
                "fichiers=%d taille=%.1fKo programmes(fsh/vsh/gsh/csh)=%d/%d/%d/%d passes_distinctes=%d composites=%d deferred=%d "
                        + "options(//[])=%d profils=%d uniformes_custom=%d images=%d ssbo=%d dh=%b%n",
                files, bytes / 1024.0, fsh, vsh, gsh, csh, names.size(), composites, deferred, options, profiles,
                custom, images, ssbo, shadowDh);
    }

    static void cost(Path root, String profile, String world) throws IOException {
        Map<String, String> ov = new LinkedHashMap<>();
        if (profile != null) ov = readProfiles(root).getOrDefault(profile, new LinkedHashMap<>());
        System.out.println("# profil=" + (profile == null ? "(defauts du pack)" : profile) + " world=" + world
                + " surcharges=" + ov.size());
        System.out.println("passe\tlignes\tfetch\ttrans\tsqrt\talu\tstore\tbranch\tboucles_inconnues\tscore");
        for (Path e : entries(root)) {
            String rel = root.relativize(e).toString().replace('\\', '/');
            String dir = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : "";
            if (!(dir.isEmpty() || dir.equals(world))) continue;
            String n = e.getFileName().toString();
            if (!n.endsWith(".fsh") && !n.endsWith(".csh")) continue;
            Pre[] holder = new Pre[1];
            String flat = flatten(root, e, ov, holder);
            long lines = flat.lines().filter(l -> !l.isBlank()).count();
            fr.clubcitrouille.lanterne.client.shaderopt.CostModel.Cost c =
                    fr.clubcitrouille.lanterne.client.shaderopt.CostModel.estimate(flat);
            System.out.printf(Locale.ROOT, "%s\t%d\t%.0f\t%.0f\t%.0f\t%.0f\t%.0f\t%.0f\t%d\t%.0f%n",
                    n.substring(0, n.lastIndexOf('.')), lines, c.fetch, c.trans, c.sqrt, c.alu, c.store, c.branch,
                    c.unknownLoops, c.score());
        }
    }
}
