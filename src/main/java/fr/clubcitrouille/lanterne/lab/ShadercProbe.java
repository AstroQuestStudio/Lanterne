package fr.clubcitrouille.lanterne.lab;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.lwjgl.util.shaderc.Shaderc;

import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Preuve isolee, pas branchee au pipeline de rendu : la chaine GLSL -> SPIR-V fonctionne-t-elle
 * reellement dans cet environnement ? Voir {@code RECHERCHE-VULKAN-SANS-SODIUM.md} pour le
 * contexte complet — ce fichier ne repond qu'a UNE question precise parmi les points durs
 * identifies cette nuit : shaderc est deja dans le cache Gradle local, mais rien ne garantissait
 * avant ce test qu'il soit reellement utilisable (natives presentes, API accessible) sans avoir
 * deja essaye.
 *
 * <h2>Ce que ce fichier ne fait PAS</h2>
 *
 * <p>Aucun appel automatique au demarrage du mod, aucun branchement a Iris ni au rendu reel — un
 * shader GLSL minimal ("hello world") est compile en SPIR-V a la demande, le resultat est jete.
 * Rien ici ne doit jamais s'executer sans etre appele explicitement (voir {@link #test()}), pour
 * ne rien risquer sur le chemin de demarrage normal du mod.
 */
public final class ShadercProbe {
    private ShadercProbe() {}

    private static final String FRAGMENT_MINIMAL = """
            #version 450
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = vec4(1.0, 0.0, 0.0, 1.0);
            }
            """;

    /**
     * Compile le fragment shader minimal ci-dessus en SPIR-V. Renvoie {@code true} si la
     * compilation reussit reellement (bytecode SPIR-V non vide recu en retour), {@code false}
     * sinon — jamais d'exception qui remonterait casser autre chose, cf {@link Lanterne#LOG}.
     */
    public static boolean test() {
        long compiler = 0L;
        long result = 0L;
        try {
            compiler = Shaderc.shaderc_compiler_initialize();
            if (compiler == 0L) {
                Lanterne.LOG.warn("[SHADERC] shaderc_compiler_initialize a renvoye 0 (natives absentes ?)");
                return false;
            }
            result = Shaderc.shaderc_compile_into_spv(compiler, FRAGMENT_MINIMAL,
                    Shaderc.shaderc_glsl_fragment_shader, "probe.frag", "main", 0L);
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            long errors = Shaderc.shaderc_result_get_num_errors(result);
            if (status != 0 || errors > 0) {
                String message = Shaderc.shaderc_result_get_error_message(result);
                Lanterne.LOG.warn("[SHADERC] Compilation GLSL->SPIR-V echouee (statut={}, erreurs={}) : {}",
                        status, errors, message);
                return false;
            }
            ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
            boolean ok = spirv != null && spirv.remaining() > 0;
            Lanterne.LOG.info("[SHADERC] Compilation GLSL->SPIR-V reussie : {} octets SPIR-V produits.",
                    ok ? spirv.remaining() : 0);
            return ok;
        } catch (Throwable problem) {
            Lanterne.LOG.warn("[SHADERC] Chaine d'outils indisponible dans cet environnement : {}",
                    problem.toString());
            return false;
        } finally {
            if (result != 0L) {
                Shaderc.shaderc_result_release(result);
            }
            if (compiler != 0L) {
                Shaderc.shaderc_compiler_release(compiler);
            }
        }
    }

    /**
     * Deuxieme preuve isolee (21/09/2026, suite de {@link #test()}) : le shader minimal ci-dessus
     * ne prouvait que shaderc seul. Ici, un VRAI fichier composite d'un VRAI shaderpack passe
     * d'abord par le preprocesseur C d'Iris ({@link JcppProcessor}, deja migre et fonctionnel
     * cette nuit, voir commit 115e33b) puis par shaderc — la vraie chaine bout en bout, jamais
     * branchee au rendu, resultat jamais utilise.
     *
     * <p>Ne resout PAS les directives {@code #include} du shaderpack (mecanisme separe cote Iris,
     * hors perimetre de cette preuve) — le fichier de test doit donc etre autonome, ou ses
     * {@code #include} resteront litteralement dans la source et feront probablement echouer
     * shaderc avec une erreur claire, ce qui est une reponse honnete en soi.
     */
    public static boolean testReal(String glslSource, boolean fragment) {
        long compiler = 0L;
        long result = 0L;
        try {
            String preprocessed = JcppProcessor.glslPreprocessSource(glslSource,
                    List.of(new net.irisshaders.iris.helpers.StringPair(
                            fragment ? "FRAGMENT_SHADER" : "VERTEX_SHADER", "")));
            Lanterne.LOG.info("[SHADERC] Preprocessing Iris reussi, {} caracteres produits.",
                    preprocessed.length());

            compiler = Shaderc.shaderc_compiler_initialize();
            if (compiler == 0L) {
                Lanterne.LOG.warn("[SHADERC] shaderc_compiler_initialize a renvoye 0 (natives absentes ?)");
                return false;
            }
            result = Shaderc.shaderc_compile_into_spv(compiler, preprocessed,
                    fragment ? Shaderc.shaderc_glsl_fragment_shader : Shaderc.shaderc_glsl_vertex_shader,
                    "probe-real.glsl", "main", 0L);
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            long errors = Shaderc.shaderc_result_get_num_errors(result);
            if (status != 0 || errors > 0) {
                String message = Shaderc.shaderc_result_get_error_message(result);
                Lanterne.LOG.warn("[SHADERC] Compilation du VRAI shader echouee (statut={}, erreurs={}) : {}",
                        status, errors, message);
                return false;
            }
            ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
            boolean ok = spirv != null && spirv.remaining() > 0;
            Lanterne.LOG.info("[SHADERC] Compilation du VRAI shader reussie : {} octets SPIR-V produits.",
                    ok ? spirv.remaining() : 0);
            return ok;
        } catch (Throwable problem) {
            Lanterne.LOG.warn("[SHADERC] Echec sur le vrai shader : {}", problem.toString());
            return false;
        } finally {
            if (result != 0L) {
                Shaderc.shaderc_result_release(result);
            }
            if (compiler != 0L) {
                Shaderc.shaderc_compiler_release(compiler);
            }
        }
    }

    /** Lanceur standalone (java -cp ...) pour cette preuve precise, sans passer par le jeu. */
    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(args[0]));
        boolean ok = testReal(source, true);
        System.out.println("RESULTAT: " + (ok ? "SUCCES" : "ECHEC"));
        System.exit(ok ? 0 : 1);
    }
}
