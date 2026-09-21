package fr.clubcitrouille.lanterne.lab;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.lwjgl.util.shaderc.Shaderc;

/**
 * Outil AUTONOME : compile UN fichier GLSL en SPIR-V avec shaderc, dans le processus courant, et
 * affiche le resultat. Sert aux experiences sur le blocage natif (voir RECHERCHE-VULKAN-SANS-SODIUM.md).
 *
 * <p>Usage : {@code java [-Xss..] -cp ... ShadercCompileTool <fichier.glsl> <vert|frag> [opt=0|1|2] [out.spv]}.
 * Code de sortie : 0 succes, 1 echec de compilation, 3 exception, autre = crash natif.
 */
public final class ShadercCompileTool {
    private ShadercCompileTool() {}

    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(args[0]));
        int kind = "vert".equals(args[1]) ? Shaderc.shaderc_glsl_vertex_shader : Shaderc.shaderc_glsl_fragment_shader;
        int opt = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
        Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
        Shaderc.shaderc_compile_options_set_optimization_level(options, opt);
        long t0 = System.nanoTime();
        try {
            long result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, args[0], "main", options);
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (status != 0) {
                System.out.println("ECHEC statut=" + status + " en " + ms + " ms : "
                        + Shaderc.shaderc_result_get_error_message(result));
                System.exit(1);
            }
            ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
            System.out.println("SUCCES " + spirv.remaining() + " octets SPIR-V en " + ms + " ms");
            if (args.length > 3) {
                byte[] bytes = new byte[spirv.remaining()];
                spirv.get(bytes);
                Files.write(Path.of(args[3]), bytes);
            }
            System.exit(0);
        } catch (Throwable t) {
            System.out.println("EXCEPTION " + t);
            System.exit(3);
        }
    }
}
