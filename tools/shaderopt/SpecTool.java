import fr.clubcitrouille.lanterne.client.shaderopt.AttachmentGraph;
import fr.clubcitrouille.lanterne.client.shaderopt.ConstFolder;
import fr.clubcitrouille.lanterne.client.shaderopt.CostModel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mesures hors jeu de l'optimiseur SANS PERTE de Lanterne, sur n'importe quel shaderpack.
 *
 * <pre>
 *   java SpecTool spec   &lt;dossier shaders&gt; &lt;profil|-&gt; &lt;glslang.exe&gt; [world]   spécialisation d'état neutre
 *   java SpecTool attach &lt;dossier shaders&gt; &lt;profil|-&gt; [world]                    écritures jamais relues
 *   java SpecTool check  &lt;dossier shaders&gt; &lt;profil|-&gt; &lt;glslang.exe&gt; [world]   validité de base (glslang)
 * </pre>
 */
public class SpecTool {
    /** Ce qu'Iris corrige avant de compiler : varying/attribute (GLSL 1.20) -> in/out. Idem avant et après. */
    static String normalize(String text, String stage) {
        // Iris compile en 330/460 compatibilité : on monte seulement le #version (varying/attribute restent
        // valides en profil de compatibilité). Identique pour l'original et le transformé.
        return text.replaceFirst("(?m)^#version\\s+\\d+(\\s+\\w+)?", "#version 330 compatibility");
    }

    /** Mode de compilation qui accepte l'original : 0 = #version d'origine, 1 = 330 compatibilité, -1 = aucun. */
    static int bestMode(String glslang, Path tmp, String name, String stage, String text) throws Exception {
        if (compilesWith(0, glslang, tmp, name, stage, text)) return 0;
        if (compilesWith(1, glslang, tmp, name, stage, text)) return 1;
        return -1;
    }

    static boolean compiles(String glslang, Path tmp, String name, String stage, String text) throws Exception {
        return bestMode(glslang, tmp, name, stage, text) >= 0;
    }

    static boolean compilesWith(int mode, String glslang, Path tmp, String name, String stage, String text)
            throws Exception {
        if (mode == 1) text = normalize(text, stage);
        Path f = tmp.resolve(name);
        Files.writeString(f, text, StandardCharsets.ISO_8859_1);
        Process p = new ProcessBuilder(glslang, "-S", stage, f.toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor() == 0;
    }

    static String stage(String n) {
        return n.endsWith(".fsh") ? "frag" : n.endsWith(".vsh") ? "vert" : n.endsWith(".csh") ? "comp" : "geom";
    }

    public static void main(String[] a) throws Exception {
        String cmd = a[0];
        Path root = Path.of(a[1]);
        String profile = a[2].equals("-") ? null : a[2];
        Map<String, String> ov = new LinkedHashMap<>();
        if (profile != null) ov = ShaderTool.readProfiles(root).getOrDefault(profile, new LinkedHashMap<>());
        switch (cmd) {
            case "spec" -> spec(root, ov, a[3], a.length > 4 ? a[4] : "world0");
            case "attach" -> attach(root, ov, a.length > 3 ? a[3] : "world0");
            case "check" -> check(root, ov, a[3], a.length > 4 ? a[4] : "world0");
            default -> System.err.println("commande inconnue");
        }
    }

    static boolean inWorld(Path root, Path e, String world) {
        String rel = root.relativize(e).toString().replace(File.separatorChar, '/');
        String dir = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : "";
        return dir.isEmpty() || dir.equals(world);
    }

    static void check(Path root, Map<String, String> ov, String glslang, String world) throws Exception {
        Path tmp = Files.createTempDirectory("spec");
        int ok = 0, fail = 0;
        for (Path e : ShaderTool.entries(root)) {
            if (!inWorld(root, e, world)) continue;
            String n = e.getFileName().toString();
            String flat = ShaderTool.flatten(root, e, ov, null);
            if (compiles(glslang, tmp, n, stage(n), flat)) ok++;
            else fail++;
        }
        System.out.printf(Locale.ROOT, "base : %d programmes, %d valides (%.0f%%)%n", ok + fail, ok,
                100.0 * ok / Math.max(1, ok + fail));
    }

    static void spec(Path root, Map<String, String> ov, String glslang, String world) throws Exception {
        Path tmp = Files.createTempDirectory("spec");
        int total = 0, withState = 0, validBefore = 0, validAfter = 0, regress = 0;
        double fullBefore = 0, fullAfter = 0, allBefore = 0, allAfter = 0;
        long removed = 0, folded = 0, linesBefore = 0, linesAfter = 0;
        List<String> rows = new ArrayList<>();
        for (Path e : ShaderTool.entries(root)) {
            if (!inWorld(root, e, world)) continue;
            String n = e.getFileName().toString();
            if (!n.endsWith(".fsh")) continue;
            String name = n.substring(0, n.lastIndexOf('.'));
            String flat = ShaderTool.flatten(root, e, ov, null);
            total++;
            ConstFolder.Result r = ConstFolder.specialize(flat, ConstFolder.NEUTRAL_STATE);
            if (r.constUniforms() == 0) continue;
            withState++;
            CostModel.Cost c0 = CostModel.estimate(flat);
            CostModel.Cost c1 = CostModel.estimate(r.text());
            int mode = bestMode(glslang, tmp, name + "_0.fsh", "frag", flat);
            boolean v0 = mode >= 0;
            boolean v1 = v0 && compilesWith(mode, glslang, tmp, name + "_1.fsh", "frag", r.text());
            if (v0) validBefore++;
            if (v1) validAfter++;
            if (v0 && !v1) regress++;
            boolean full = name.startsWith("composite") || name.startsWith("deferred") || name.equals("final");
            allBefore += c0.score();
            allAfter += c1.score();
            if (full) {
                fullBefore += c0.score();
                fullAfter += c1.score();
            }
            removed += r.removedChars();
            folded += r.foldedIfs();
            linesBefore += CostModel.lines(flat);
            linesAfter += CostModel.lines(r.text());
            rows.add(String.format(Locale.ROOT, "  %-26s uniforms=%d propag=%d ifs=%-3d score %6.0f -> %6.0f (%+.1f%%) glslang %s->%s",
                    name, r.constUniforms(), r.propagated(), r.foldedIfs(), c0.score(), c1.score(),
                    c0.score() == 0 ? 0 : 100.0 * (c1.score() - c0.score()) / c0.score(), v0 ? "ok" : "KO",
                    v1 ? "ok" : "KO"));
        }
        rows.forEach(System.out::println);
        System.out.printf(Locale.ROOT,
                "RESUME programmes=%d dont_avec_uniforms_d_etat=%d | valides avant=%d apres=%d regressions=%d | "
                        + "ifs_replies=%d caracteres_en_moins=%d lignes %d->%d | score total %.0f -> %.0f (%+.1f%%) | "
                        + "plein-ecran %.0f -> %.0f (%+.1f%%)%n",
                total, withState, validBefore, validAfter, regress, folded, removed, linesBefore, linesAfter, allBefore,
                allAfter, allBefore == 0 ? 0 : 100.0 * (allAfter - allBefore) / allBefore, fullBefore, fullAfter,
                fullBefore == 0 ? 0 : 100.0 * (fullAfter - fullBefore) / fullBefore);
    }

    /**
     * Lit les lignes {@code program.<nom>.enabled = <expression>} de shaders.properties après préprocessing
     * (les #if du fichier de propriétés sont évalués avec les options du pack, comme le fait Iris).
     */
    static Map<String, String> readEnabled(Path root, Map<String, String> ov, ShaderTool.Pre env) throws Exception {
        Map<String, String> res = new LinkedHashMap<>();
        Path props = root.resolve("shaders.properties");
        if (!Files.exists(props)) return res;
        ShaderTool.Pre p2 = new ShaderTool.Pre(root, ov);
        p2.macros.putAll(env.macros);
        p2.process(props, 0);
        Pattern pat = Pattern.compile("(?m)^\\s*program\\.([^\\s=]+)\\.enabled\\s*=\\s*(.+?)\\s*$");
        Matcher m = pat.matcher(p2.out.toString());
        while (m.find()) {
            String expr = m.group(2).replaceAll("\\btrue\\b", "1").replaceAll("\\bfalse\\b", "0");
            res.put(m.group(1), expr);
        }
        return res;
    }

    static void attach(Path root, Map<String, String> ov, String world) throws Exception {
        AttachmentGraph g = new AttachmentGraph();
        int passes = 0, disabled = 0;
        Map<String, String> enabledExpr = null;
        List<String> skipped = new ArrayList<>();
        for (Path e : ShaderTool.entries(root)) {
            if (!inWorld(root, e, world)) continue;
            String n = e.getFileName().toString();
            if (!n.endsWith(".fsh")) continue;
            String name = n.substring(0, n.lastIndexOf('.'));
            ShaderTool.Pre[] holder = new ShaderTool.Pre[1];
            String flat = ShaderTool.flatten(root, e, ov, holder);
            if (enabledExpr == null) enabledExpr = readEnabled(root, ov, holder[0]);
            String expr = enabledExpr.get(world + "/" + name);
            if (expr == null) expr = enabledExpr.get(name);
            if (expr != null && !holder[0].evalIf(expr)) {
                disabled++;
                skipped.add(name);
                continue;
            }
            g.addPass(name, flat);
            passes++;
        }
        System.out.println("passes analysées : " + passes + " (désactivées par shaders.properties : " + disabled
                + " " + skipped + ")");
        System.out.print(g.summary());
        Matcher unused = Pattern.compile("JAMAIS").matcher(g.summary());
        int dead = 0;
        while (unused.find()) dead++;
        System.out.println("écritures jamais relues : " + dead);
    }
}
