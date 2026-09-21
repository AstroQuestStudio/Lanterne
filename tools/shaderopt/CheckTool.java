import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Valide (hors jeu) chaque programme aplati d'un pack avec glslang.
 * Usage : java CheckTool <dossier shaders> <profil|-> <glslang.exe> [world]
 * Un programme "OK" est accepte par le front-end GLSL (profil de compatibilite implicite des packs OptiFine).
 */
public class CheckTool {
    public static void main(String[] a) throws Exception {
        Path root = Path.of(a[0]);
        String profile = a[1].equals("-") ? null : a[1];
        String glslang = a[2];
        String world = a.length > 3 ? a[3] : "world0";
        Map<String, String> ov = new LinkedHashMap<>();
        if (profile != null) ov = ShaderTool.readProfiles(root).getOrDefault(profile, new LinkedHashMap<>());
        int ok = 0, fail = 0;
        Map<String, Integer> errs = new TreeMap<>();
        List<String> failed = new ArrayList<>();
        Path tmp = Files.createTempDirectory("glslcheck");
        for (Path e : ShaderTool.entries(root)) {
            String rel = root.relativize(e).toString().replace(File.separatorChar, '/');
            String dir = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : "";
            if (!(dir.isEmpty() || dir.equals(world))) continue;
            String n = e.getFileName().toString();
            String stage = n.endsWith(".fsh") ? "frag" : n.endsWith(".vsh") ? "vert" : n.endsWith(".csh") ? "comp" : "geom";
            String flat = ShaderTool.flatten(root, e, ov, null);
            Path f = tmp.resolve(n);
            Files.writeString(f, flat, StandardCharsets.ISO_8859_1);
            Process p = new ProcessBuilder(glslang, "-S", stage, f.toString()).redirectErrorStream(true).start();
            String outp = new String(p.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            int code = p.waitFor();
            if (code == 0) ok++;
            else {
                fail++;
                failed.add(rel);
                Matcher m = Pattern.compile("ERROR: [^:]*:[0-9]+: (.*)").matcher(outp);
                if (m.find()) {
                    String msg = m.group(1).replaceAll("'[^']*'", "'X'").replaceAll("[0-9]+", "N");
                    if (msg.length() > 90) msg = msg.substring(0, 90);
                    errs.merge(msg, 1, Integer::sum);
                }
            }
        }
        System.out.printf(Locale.ROOT, "programmes=%d OK=%d ECHEC=%d (%.0f%%)%n", ok + fail, ok, fail,
                100.0 * ok / Math.max(1, ok + fail));
        errs.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(6)
                .forEach(en -> System.out.println("   " + en.getValue() + " x " + en.getKey()));
        if (!failed.isEmpty()) System.out.println("   ex: " + failed.subList(0, Math.min(4, failed.size())));
    }
}
