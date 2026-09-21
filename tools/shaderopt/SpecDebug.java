import fr.clubcitrouille.lanterne.client.shaderopt.ConstFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Diagnostic d'une régression : java SpecDebug <shaders> <profil|-> <programme rel.> <glslang.exe> <dossier sortie> */
public class SpecDebug {
    public static void main(String[] a) throws Exception {
        Path root = Path.of(a[0]);
        Map<String, String> ov = new LinkedHashMap<>();
        if (!a[1].equals("-")) ov = ShaderTool.readProfiles(root).getOrDefault(a[1], new LinkedHashMap<>());
        String flat = ShaderTool.flatten(root, root.resolve(a[2]), ov, null);
        ConstFolder.Result r = ConstFolder.specialize(flat, ConstFolder.NEUTRAL_STATE);
        Path out = Path.of(a[4]);
        Files.createDirectories(out);
        Files.writeString(out.resolve("before.fsh"), flat, StandardCharsets.ISO_8859_1);
        Files.writeString(out.resolve("after.fsh"), r.text(), StandardCharsets.ISO_8859_1);
        for (String mode : new String[]{"before", "after"}) {
            Process p = new ProcessBuilder(a[3], "-S", "frag", out.resolve(mode + ".fsh").toString())
                    .redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            System.out.println("== " + mode + " (exit " + p.waitFor() + ")");
            o.lines().limit(6).forEach(System.out::println);
        }
        System.out.println("uniforms=" + r.constUniforms() + " propag=" + r.propagated() + " ifs=" + r.foldedIfs());
    }
}
