package fr.clubcitrouille.lanterne.client.shaderopt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spécialisation SANS PERTE d'un programme GLSL sur un état de jeu « neutre ».
 *
 * <h2>L'idée</h2>
 * Un shaderpack contient des branches qui ne servent que dans des états rares : sous l'eau
 * ({@code isEyeInWater}), sous la pluie ({@code rainStrength}, {@code wetness}), pendant un orage, aveuglé,
 * sous l'effet d'obscurité… Le pilote graphique ne connaît pas la valeur d'un {@code uniform} au moment de
 * compiler : il garde donc tout le code, ses registres, et un occupation plus faible des unités de calcul,
 * même quand la branche n'est jamais prise. Si, pendant que le joueur est au sec sous un ciel dégagé, on
 * recompile le shader avec ces {@code uniform} devenus des constantes, le pilote supprime lui-même les
 * branches mortes : le rendu est IDENTIQUE (les valeurs sont exactement celles de l'état courant), et le
 * shader est plus court et plus léger. Dès que l'état change, on revient à la version générale.
 *
 * <h2>Ce que fait cette classe</h2>
 * <ol>
 *   <li>{@link #specialize} remplace {@code uniform T nom;} par {@code const T nom = valeur;} ;</li>
 *   <li>propage ces constantes vers des variables globales dérivées jamais réassignées ;</li>
 *   <li>replie les {@code if} dont la condition devient connue (logique à trois valeurs : {@code faux && ?}
 *       vaut faux, {@code vrai || ?} vaut vrai) — utile pour mesurer, réduire la taille du source et
 *       prouver la spécialisation, même si le pilote le ferait aussi.</li>
 * </ol>
 * Toute transformation qui échoue à la validation est abandonnée par l'appelant (voir
 * {@link ShaderOptimizer}) : on ne dégrade jamais un shader qu'on ne comprend pas.
 *
 * <p>Java pur, sans dépendance à Minecraft.
 */
public final class ConstFolder {
    private ConstFolder() {}

    /** Résultat d'une spécialisation. */
    public record Result(String text, int constUniforms, int propagated, int foldedIfs, int removedChars,
                         List<String> notes) {}

    /** Valeurs standard d'OptiFine/Iris pour « au sec, ciel dégagé, vue normale ». */
    public static final Map<String, String> NEUTRAL_STATE = new LinkedHashMap<>();
    static {
        NEUTRAL_STATE.put("isEyeInWater", "0");
        NEUTRAL_STATE.put("rainStrength", "0.0");
        NEUTRAL_STATE.put("wetness", "0.0");
        NEUTRAL_STATE.put("thunderStrength", "0.0");
        NEUTRAL_STATE.put("blindness", "0.0");
        NEUTRAL_STATE.put("darknessFactor", "0.0");
        NEUTRAL_STATE.put("darknessLightFactor", "0.0");
        NEUTRAL_STATE.put("nightVision", "0.0");
    }

    // ------------------------------------------------------------ valeurs à trois états
    /** Valeur GLSL scalaire connue ou inconnue. */
    private record Val(boolean known, double v) {
        static final Val UNKNOWN = new Val(false, 0);

        static Val of(double d) {
            return new Val(true, d);
        }

        static Val bool(boolean b) {
            return new Val(true, b ? 1 : 0);
        }

        boolean truthy() {
            return v != 0;
        }
    }

    private static final Pattern UNIFORM_DECL = Pattern.compile(
            "(?m)^([ \\t]*)(?:layout\\s*\\([^)]*\\)\\s*)?uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?"
                    + "(float|int|uint|bool)\\s+(\\w+)\\s*;");

    /**
     * Spécialise le texte aplati sur les valeurs données.
     *
     * @param glsl   programme préprocessé (un seul étage)
     * @param values nom d'uniform → littéral GLSL (ex. {@code "rainStrength" → "0.0"})
     */
    public static Result specialize(String glsl, Map<String, String> values) {
        List<String> notes = new ArrayList<>();
        int before = glsl.length();
        Map<String, Val> known = new HashMap<>();

        // 1. uniform → const
        Matcher m = UNIFORM_DECL.matcher(glsl);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        int constUniforms = 0;
        while (m.find()) {
            String name = m.group(3);
            String lit = values.get(name);
            if (lit == null) continue;
            String type = m.group(2);
            Val v = parseLiteral(lit);
            if (!v.known()) continue;
            sb.append(glsl, last, m.start());
            sb.append(m.group(1)).append("const ").append(type).append(' ').append(name).append(" = ")
                    .append(literal(type, v)).append(';');
            last = m.end();
            known.put(name, v);
            constUniforms++;
        }
        sb.append(glsl.substring(last));
        String text = sb.toString();
        if (constUniforms == 0) return new Result(glsl, 0, 0, 0, 0, notes);

        // 2. propagation vers des globales dérivées jamais réassignées (au plus 4 passes)
        int propagated = 0;
        for (int pass = 0; pass < 4; pass++) {
            int found = propagateGlobals(text, known);
            propagated += found;
            if (found == 0) break;
        }

        // 3. repli des if
        int[] folded = {0};
        for (int pass = 0; pass < 8; pass++) {
            int before2 = folded[0];
            text = foldIfs(text, known, folded);
            if (folded[0] == before2) break;
        }
        // 4. fonctions devenues inutilisées : un code mort peut devenir INVALIDE une fois les uniforms figés
        //    (ex. tableau[isEyeInWater - 2] avec isEyeInWater = 0 : indice constant négatif refusé)
        for (int pass = 0; pass < 6; pass++) {
            String next = removeUnusedFunctions(text);
            if (next.length() == text.length()) break;
            text = next;
        }
        return new Result(text, constUniforms, propagated, folded[0], before - text.length(), notes);
    }

    // ------------------------------------------------ suppression des fonctions inutilisées
    private static String removeUnusedFunctions(String text) {
        int depth = 0;
        int n = text.length();
        int i = 0;
        StringBuilder out = new StringBuilder(n);
        int copied = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    int j = i - 1;
                    while (j >= 0 && Character.isWhitespace(text.charAt(j))) j--;
                    int end = matching(text, i, '{', '}');
                    if (end < 0) return text;
                    if (j >= 0 && text.charAt(j) == ')') {
                        int k = j, d = 0;
                        while (k >= 0) {
                            char ch = text.charAt(k);
                            if (ch == ')') d++;
                            else if (ch == '(') {
                                d--;
                                if (d == 0) break;
                            }
                            k--;
                        }
                        int e = k - 1;
                        while (e >= 0 && Character.isWhitespace(text.charAt(e))) e--;
                        int b = e;
                        while (b >= 0 && isIdent(text.charAt(b))) b--;
                        String name = text.substring(b + 1, e + 1);
                        if (!name.isEmpty() && !name.equals("main") && !isReferenced(text, name, b + 1)) {
                            // début de la déclaration : après le dernier ';' ou '}' ou ligne '#'
                            int s = b;
                            while (s > 0) {
                                char sc = text.charAt(s - 1);
                                if (sc == ';' || sc == '}') break;
                                s--;
                            }
                            // ne pas manger une ligne de directive (#version, #extension) ni un attribut layout
                            int lineStart = s;
                            for (int p = s; p <= b; p++) {
                                if (text.charAt(p) == '\n') {
                                    int q = skipWs(text, p + 1);
                                    if (q < text.length() && text.charAt(q) == '#') {
                                        int nl = text.indexOf('\n', q);
                                        lineStart = nl < 0 ? q : nl + 1;
                                    }
                                }
                            }
                            if (lineStart >= copied) {
                                out.append(text, copied, lineStart);
                                copied = end + 1;
                            }
                        }
                    }
                    i = end + 1;
                    continue;
                }
                depth++;
            } else if (c == '}') depth = Math.max(0, depth - 1);
            i++;
        }
        out.append(text.substring(Math.min(copied, n)));
        return out.toString();
    }

    /** {@code name(} apparaît-il ailleurs que dans sa propre définition (à partir de {@code defStart}) ? */
    private static boolean isReferenced(String text, String name, int defStart) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(text);
        int count = 0;
        while (m.find()) {
            if (m.start() == defStart) continue;
            count++;
        }
        return count > 0;
    }

    private static Val parseLiteral(String lit) {
        lit = lit.strip();
        if (lit.equals("true")) return Val.of(1);
        if (lit.equals("false")) return Val.of(0);
        try {
            return Val.of(Double.parseDouble(lit.replaceAll("[fFuU]$", "")));
        } catch (NumberFormatException e) {
            return Val.UNKNOWN;
        }
    }

    private static String literal(String type, Val v) {
        return switch (type) {
            case "bool" -> v.truthy() ? "true" : "false";
            case "int" -> Long.toString((long) v.v());
            case "uint" -> (long) v.v() + "u";
            default -> {
                String s = Double.toString(v.v());
                yield s.contains("E") ? String.format(Locale.ROOT, "%.9f", v.v()) : s;
            }
        };
    }

    // ------------------------------------------------- propagation de globales dérivées
    private static final Pattern GLOBAL_DECL = Pattern.compile(
            "(?m)^(?:const\\s+)?(?:highp\\s+|mediump\\s+|lowp\\s+)?(float|int|uint|bool)\\s+(\\w+)\\s*=\\s*([^;]+);");

    private static int propagateGlobals(String text, Map<String, Val> known) {
        int count = 0;
        Matcher m = GLOBAL_DECL.matcher(text);
        while (m.find()) {
            String name = m.group(2);
            if (known.containsKey(name)) continue;
            // profondeur 0 ? (la déclaration commence en colonne 0 : les corps de fonction sont indentés
            // dans la plupart des packs, et on vérifie aussi l'absence de réassignation)
            if (depthAt(text, m.start()) != 0) continue;
            Val v = new Ev(m.group(3), known).eval();
            if (!v.known()) continue;
            if (reassigned(text, name)) continue;
            known.put(name, v);
            count++;
        }
        return count;
    }

    private static int depthAt(String s, int pos) {
        int d = 0;
        for (int i = 0; i < pos; i++) {
            char c = s.charAt(i);
            if (c == '{') d++;
            else if (c == '}') d--;
        }
        return d;
    }

    private static boolean reassigned(String text, String name) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*(?:[-+*/]=|=(?!=)|\\+\\+|--)|(?:\\+\\+|--)\\s*"
                + Pattern.quote(name) + "\\b");
        Matcher m = p.matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count > 1;
    }

    // ------------------------------------------------------------- repli des if
    private static String foldIfs(String text, Map<String, Val> known, int[] counter) {
        Pattern ifp = Pattern.compile("\\bif\\s*\\(");
        int from = 0;
        while (true) {
            Matcher m = ifp.matcher(text);
            if (!m.find(from)) return text;
            int ifStart = m.start();
            int condOpen = m.end() - 1;
            int condClose = matching(text, condOpen, '(', ')');
            if (condClose < 0) {
                from = m.end();
                continue;
            }
            String cond = text.substring(condOpen + 1, condClose);
            Val v = new Ev(cond, known).eval();
            if (!v.known()) {
                from = m.end();
                continue;
            }
            int thenStart = skipWs(text, condClose + 1);
            int thenEnd = statementEnd(text, thenStart);
            if (thenEnd < 0) {
                from = m.end();
                continue;
            }
            int p = skipWs(text, thenEnd);
            int elseStart = -1, elseEnd = -1;
            if (text.startsWith("else", p) && (p + 4 >= text.length() || !isIdent(text.charAt(p + 4)))) {
                elseStart = skipWs(text, p + 4);
                elseEnd = statementEnd(text, elseStart);
                if (elseEnd < 0) {
                    from = m.end();
                    continue;
                }
            }
            String replacement;
            int regionEnd;
            if (v.truthy()) {
                replacement = text.substring(thenStart, thenEnd);
                regionEnd = elseEnd >= 0 ? elseEnd : thenEnd;
            } else {
                replacement = elseEnd >= 0 ? text.substring(elseStart, elseEnd) : "";
                regionEnd = elseEnd >= 0 ? elseEnd : thenEnd;
            }
            text = text.substring(0, ifStart) + replacement + text.substring(regionEnd);
            counter[0]++;
            from = ifStart;
        }
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int skipWs(String s, int p) {
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        return p;
    }

    private static int matching(String s, int open, char o, char c) {
        int d = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == o) d++;
            else if (ch == c) {
                d--;
                if (d == 0) return i;
            }
        }
        return -1;
    }

    /** Fin (exclue) de l'instruction qui commence en {@code p}, ou -1 si elle n'est pas comprise. */
    private static int statementEnd(String s, int p) {
        if (p >= s.length()) return -1;
        if (s.charAt(p) == '{') {
            int e = matching(s, p, '{', '}');
            return e < 0 ? -1 : e + 1;
        }
        if (startsWithWord(s, p, "if")) {
            int po = s.indexOf('(', p);
            int pc = po < 0 ? -1 : matching(s, po, '(', ')');
            if (pc < 0) return -1;
            int t = statementEnd(s, skipWs(s, pc + 1));
            if (t < 0) return -1;
            int q = skipWs(s, t);
            if (startsWithWord(s, q, "else")) {
                int e = statementEnd(s, skipWs(s, q + 4));
                return e;
            }
            return t;
        }
        if (startsWithWord(s, p, "for") || startsWithWord(s, p, "while")) {
            int po = s.indexOf('(', p);
            int pc = po < 0 ? -1 : matching(s, po, '(', ')');
            if (pc < 0) return -1;
            return statementEnd(s, skipWs(s, pc + 1));
        }
        if (startsWithWord(s, p, "do")) return -1;
        // instruction simple : jusqu'au premier ';' hors parenthèses/accolades
        int dp = 0;
        for (int i = p; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') dp++;
            else if (c == ')' || c == ']' || c == '}') dp--;
            else if (c == ';' && dp == 0) return i + 1;
        }
        return -1;
    }

    private static boolean startsWithWord(String s, int p, String w) {
        return s.startsWith(w, p) && (p + w.length() >= s.length() || !isIdent(s.charAt(p + w.length())));
    }

    // ------------------------------------------------------- évaluateur de conditions
    private static final class Ev {
        final String s;
        final Map<String, Val> known;
        int p = 0;

        Ev(String s, Map<String, Val> known) {
            this.s = s;
            this.known = known;
        }

        Val eval() {
            try {
                Val v = ternary();
                ws();
                return p >= s.length() ? v : Val.UNKNOWN;
            } catch (RuntimeException e) {
                return Val.UNKNOWN;
            }
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

        Val ternary() {
            Val c = lor();
            if (eat("?")) {
                Val a = ternary();
                if (!eat(":")) throw new RuntimeException();
                Val b = ternary();
                if (!c.known()) return Val.UNKNOWN;
                return c.truthy() ? a : b;
            }
            return c;
        }

        Val lor() {
            Val v = land();
            while (true) {
                ws();
                if (s.startsWith("||", p)) {
                    p += 2;
                    Val r = land();
                    if ((v.known() && v.truthy()) || (r.known() && r.truthy())) v = Val.bool(true);
                    else if (v.known() && r.known()) v = Val.bool(false);
                    else v = Val.UNKNOWN;
                } else return v;
            }
        }

        Val land() {
            Val v = cmp();
            while (true) {
                ws();
                if (s.startsWith("&&", p)) {
                    p += 2;
                    Val r = cmp();
                    if ((v.known() && !v.truthy()) || (r.known() && !r.truthy())) v = Val.bool(false);
                    else if (v.known() && r.known()) v = Val.bool(true);
                    else v = Val.UNKNOWN;
                } else return v;
            }
        }

        Val cmp() {
            Val v = add();
            while (true) {
                ws();
                String op = null;
                for (String o : new String[]{"==", "!=", "<=", ">=", "<", ">"}) {
                    if (s.startsWith(o, p) && !s.startsWith("<<", p) && !s.startsWith(">>", p)) {
                        op = o;
                        break;
                    }
                }
                if (op == null) return v;
                p += op.length();
                Val r = add();
                if (!v.known() || !r.known()) {
                    v = Val.UNKNOWN;
                    continue;
                }
                boolean b = switch (op) {
                    case "==" -> v.v() == r.v();
                    case "!=" -> v.v() != r.v();
                    case "<=" -> v.v() <= r.v();
                    case ">=" -> v.v() >= r.v();
                    case "<" -> v.v() < r.v();
                    default -> v.v() > r.v();
                };
                v = Val.bool(b);
            }
        }

        Val add() {
            Val v = mul();
            while (true) {
                ws();
                if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')
                        && !s.startsWith("++", p) && !s.startsWith("--", p) && !s.startsWith("+=", p)
                        && !s.startsWith("-=", p)) {
                    char op = s.charAt(p++);
                    Val r = mul();
                    v = (v.known() && r.known()) ? Val.of(op == '+' ? v.v() + r.v() : v.v() - r.v()) : Val.UNKNOWN;
                } else return v;
            }
        }

        Val mul() {
            Val v = unary();
            while (true) {
                ws();
                if (p < s.length() && (s.charAt(p) == '*' || s.charAt(p) == '/') && !s.startsWith("*=", p)
                        && !s.startsWith("/=", p)) {
                    char op = s.charAt(p++);
                    Val r = unary();
                    if (!v.known() || !r.known()) v = Val.UNKNOWN;
                    else v = Val.of(op == '*' ? v.v() * r.v() : (r.v() == 0 ? 0 : v.v() / r.v()));
                } else return v;
            }
        }

        Val unary() {
            ws();
            if (p < s.length() && s.charAt(p) == '!' && !s.startsWith("!=", p)) {
                p++;
                Val v = unary();
                return v.known() ? Val.bool(!v.truthy()) : Val.UNKNOWN;
            }
            if (p < s.length() && s.charAt(p) == '-') {
                p++;
                Val v = unary();
                return v.known() ? Val.of(-v.v()) : Val.UNKNOWN;
            }
            if (p < s.length() && s.charAt(p) == '+') {
                p++;
                return unary();
            }
            return primary();
        }

        Val primary() {
            ws();
            if (p >= s.length()) throw new RuntimeException();
            char c = s.charAt(p);
            if (c == '(') {
                p++;
                Val v = ternary();
                if (!eat(")")) throw new RuntimeException();
                return postfix(v);
            }
            if (Character.isDigit(c) || (c == '.' && p + 1 < s.length() && Character.isDigit(s.charAt(p + 1)))) {
                int st = p;
                while (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) p++;
                if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
                    p++;
                    if (p < s.length() && (s.charAt(p) == '-' || s.charAt(p) == '+')) p++;
                    while (p < s.length() && Character.isDigit(s.charAt(p))) p++;
                }
                double d = Double.parseDouble(s.substring(st, p));
                while (p < s.length() && "uUfF".indexOf(s.charAt(p)) >= 0) p++;
                return Val.of(d);
            }
            if (isIdent(c)) {
                int st = p;
                while (p < s.length() && isIdent(s.charAt(p))) p++;
                String id = s.substring(st, p);
                if (id.equals("true")) return Val.bool(true);
                if (id.equals("false")) return Val.bool(false);
                ws();
                if (p < s.length() && s.charAt(p) == '(') {
                    int close = matching(s, p, '(', ')');
                    if (close < 0) throw new RuntimeException();
                    String inner = s.substring(p + 1, close);
                    p = close + 1;
                    Val arg = new Ev(inner, known).eval();
                    Val out = switch (id) {
                        case "float", "bool" -> arg;
                        case "int", "uint" -> arg.known() ? Val.of(Math.floor(arg.v())) : arg;
                        default -> Val.UNKNOWN;
                    };
                    return postfix(out);
                }
                Val kv = known.get(id);
                return postfix(kv == null ? Val.UNKNOWN : kv);
            }
            throw new RuntimeException();
        }

        /** Un accès {@code .x} ou {@code [i]} sur un scalaire rend la valeur inconnue. */
        Val postfix(Val v) {
            ws();
            if (p < s.length() && (s.charAt(p) == '.' || s.charAt(p) == '[')) {
                while (p < s.length() && (s.charAt(p) == '.' || isIdent(s.charAt(p)) || s.charAt(p) == '['
                        || s.charAt(p) == ']')) p++;
                return Val.UNKNOWN;
            }
            return v;
        }
    }
}
