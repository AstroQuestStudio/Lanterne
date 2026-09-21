package fr.clubcitrouille.lanterne.lab;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.util.shaderc.Shaderc;

import com.google.common.collect.ImmutableList;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;

import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import net.irisshaders.iris.shaderpack.include.IncludeProcessor;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.shaderpack.texture.TextureStage;

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

    /**
     * Troisieme preuve isolee (21/09/2026, suite de {@link #testReal}) : la chaine complete
     * jcpp -> {@code glsl-transformer} (via {@link TransformPatcher#patchComposite}) -> shaderc,
     * DANS un contexte client reel avec un GPU vivant. Le blocage precedent (documente dans
     * {@code RECHERCHE-VULKAN-SANS-SODIUM.md}, commit 994df5e) etait que
     * {@code TransformPatcher.patchComposite} appelle {@code RenderSystem.getDevice().getDeviceInfo()}
     * comme premiere ligne de {@code patchDepth}/{@code CacheKey} — un standalone (sans fenetre GPU)
     * ne peut jamais fournir ce device. Cette methode n'existe QUE pour etre appelee depuis un vrai
     * client lance (voir {@code C:\lanterne-client-test\launch7.bat}), jamais au demarrage normal
     * du mod — voir la note de classe.
     *
     * <p>Ne resout toujours PAS les {@code #include} (comme {@link #testReal}) — utiliser un
     * fichier composite autonome sans dependance externe pour ce test precis.
     */
    public static boolean testCompositeInClient(String rawFragmentSource) {
        try {
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap = Object2ObjectMaps.emptyMap();
            Map<PatchShaderType, String> patched = TransformPatcher.patchComposite(
                    "probe-composite", null, null, rawFragmentSource,
                    TextureStage.COMPOSITE_AND_FINAL, textureMap, Set.of());
            String fragmentPatched = patched.get(PatchShaderType.FRAGMENT);
            if (fragmentPatched == null) {
                Lanterne.LOG.warn("[SHADERC] TransformPatcher.patchComposite n'a produit aucun fragment patche.");
                return false;
            }
            Lanterne.LOG.info("[SHADERC] TransformPatcher.patchComposite reussi ({} caracteres patches), tentative shaderc...",
                    fragmentPatched.length());
            return compileSpirv(fragmentPatched, Shaderc.shaderc_glsl_fragment_shader, "probe-composite-patched.glsl");
        } catch (Throwable problem) {
            Lanterne.LOG.warn("[SHADERC] Echec de patchComposite (contexte client, device GPU requis) : {}",
                    problem.toString());
            return false;
        }
    }

    /**
     * Quatrieme preuve isolee (21/09/2026, suite de {@link #testCompositeInClient}) : jusqu'ici
     * seul {@code patchComposite} avait ete exerce. {@code patchVanilla} est le point d'entree le
     * PLUS utilise reellement par Iris (shaders "gbuffers", vertex+fragment, sur la quasi-totalite
     * du rendu de terrain/entites) — une preuve differente, pas une redite de la precedente. Memes
     * limites que {@link #testCompositeInClient} (pas de {@code #include}, DOIT tourner dans un
     * vrai contexte client).
     */
    public static boolean testVanillaInClient(String vertexSource, String fragmentSource) {
        try {
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap = Object2ObjectMaps.emptyMap();
            ShaderAttributeInputs inputs = new ShaderAttributeInputs(true, true, false, true, false);
            Map<PatchShaderType, String> patched = TransformPatcher.patchVanilla(
                    "probe-vanilla", vertexSource, null, null, null, fragmentSource,
                    AlphaTest.ALWAYS, false, false, false, inputs, textureMap, Set.of());
            String vertexPatched = patched.get(PatchShaderType.VERTEX);
            String fragmentPatched = patched.get(PatchShaderType.FRAGMENT);
            if (vertexPatched == null || fragmentPatched == null) {
                Lanterne.LOG.warn("[SHADERC] TransformPatcher.patchVanilla n'a pas produit les deux etages (vertex={}, fragment={}).",
                        vertexPatched != null, fragmentPatched != null);
                return false;
            }
            Lanterne.LOG.info("[SHADERC] TransformPatcher.patchVanilla reussi ({} + {} caracteres patches), tentative shaderc sur les deux etages...",
                    vertexPatched.length(), fragmentPatched.length());
            boolean vertexOk = compileSpirv(vertexPatched, Shaderc.shaderc_glsl_vertex_shader, "probe-vanilla-vertex.glsl");
            boolean fragmentOk = compileSpirv(fragmentPatched, Shaderc.shaderc_glsl_fragment_shader, "probe-vanilla-fragment.glsl");
            return vertexOk && fragmentOk;
        } catch (Throwable problem) {
            Lanterne.LOG.warn("[SHADERC] Echec de patchVanilla (contexte client, device GPU requis) : {}",
                    problem.toString());
            return false;
        }
    }

    /**
     * Cinquieme preuve isolee (21/09/2026, reponse a l'etape 5 de {@code RECHERCHE-VULKAN-SANS-SODIUM.md}) :
     * {@link #testVanillaInClient} echoue car {@code patchVanilla} declare ses uniforms non-opaques
     * hors bloc UBO — legal OpenGL, illegal SPIR-V/Vulkan. Ici, le meme GLSL patche par
     * {@code TransformPatcher.patchVanilla} (code Iris inchange) est post-traite par
     * {@link UniformReblocker#reblock} (nouveau, notre code, pas Iris) AVANT shaderc — un uniform
     * "classique" en variable globale devient un membre d'un unique bloc
     * {@code layout(binding=N, std140) uniform ... { ... };}, ce qui est autorise par SPIR-V.
     */
    public static boolean testVanillaReblockedInClient(String vertexSource, String fragmentSource) {
        try {
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap = Object2ObjectMaps.emptyMap();
            ShaderAttributeInputs inputs = new ShaderAttributeInputs(true, true, false, true, false);
            Map<PatchShaderType, String> patched = TransformPatcher.patchVanilla(
                    "probe-vanilla-reblocked", vertexSource, null, null, null, fragmentSource,
                    AlphaTest.ALWAYS, false, false, false, inputs, textureMap, Set.of());
            String vertexPatched = patched.get(PatchShaderType.VERTEX);
            String fragmentPatched = patched.get(PatchShaderType.FRAGMENT);
            if (vertexPatched == null || fragmentPatched == null) {
                Lanterne.LOG.warn("[SHADERC] TransformPatcher.patchVanilla (reblock) n'a pas produit les deux etages (vertex={}, fragment={}).",
                        vertexPatched != null, fragmentPatched != null);
                return false;
            }
            Lanterne.LOG.info("[SHADERC] patchVanilla reussi (vertex {} caracteres, fragment {} caracteres), reblock vertex...",
                    vertexPatched.length(), fragmentPatched.length());
            String vertexReblocked = UniformReblocker.reblock(vertexPatched, "LanterneVertexUBO", 0);
            Lanterne.LOG.info("[SHADERC] reblock vertex reussi ({} caracteres), reblock fragment...", vertexReblocked.length());
            String fragmentReblocked = UniformReblocker.reblock(fragmentPatched, "LanterneFragmentUBO", 0);
            Lanterne.LOG.info("[SHADERC] UniformReblocker applique (vertex {} -> {} caracteres, fragment {} -> {} caracteres), shaderc vertex...",
                    vertexPatched.length(), vertexReblocked.length(), fragmentPatched.length(), fragmentReblocked.length());
            boolean vertexOk = compileSpirv(vertexReblocked, Shaderc.shaderc_glsl_vertex_shader, "probe-vanilla-vertex-reblocked.glsl");
            Lanterne.LOG.info("[SHADERC] shaderc vertex termine (ok={}), shaderc fragment...", vertexOk);
            boolean fragmentOk = compileSpirv(fragmentReblocked, Shaderc.shaderc_glsl_fragment_shader, "probe-vanilla-fragment-reblocked.glsl");
            Lanterne.LOG.info("[SHADERC] shaderc fragment termine (ok={}).", fragmentOk);
            return vertexOk && fragmentOk;
        } catch (Throwable problem) {
            // Etape 12 (21/09, reprise apres deblocage Gradle) : ce catch(Throwable) avalait
            // silencieusement la stack trace via toString() -- impossible de savoir OU exactement
            // l'OutOfMemoryError/StackOverflowError se produisait dans la chaine patchVanilla ->
            // reblock -> shaderc. Log complet desormais pour localiser precisement, sans changer le
            // comportement (toujours capture et renvoie false, jamais propage).
            Lanterne.LOG.warn("[SHADERC] Echec de patchVanilla+reblock (contexte client, device GPU requis) : {}",
                    problem.toString(), problem);
            return false;
        }
    }

    /**
     * Sixieme preuve isolee (21/09/2026, suite de {@link #testVanillaReblockedInClient}) : les
     * preuves precedentes utilisaient un shader "gbuffers" minimal ecrit a la main, pas un VRAI
     * fichier d'un VRAI shaderpack (limite honnetement documentee dans {@code TACHES-NUIT.md}).
     * Ici, {@code shadersRoot} est le dossier {@code shaders/} extrait d'un shaderpack reel
     * (ex: ComplementaryReimagined) et {@code vertexPath}/{@code fragmentPath} des chemins
     * absolus de pack (ex: {@code /world0/gbuffers_terrain.vsh}) — les {@code #include} sont
     * resolus pour de vrai via {@link IncludeGraph}/{@link IncludeProcessor} (le meme mecanisme
     * qu'utilise reellement Iris pour charger un shaderpack, deja migre cette nuit), contrairement
     * a {@link #testVanillaInClient} qui exige un fichier autonome sans {@code #include}.
     */
    public static boolean testRealPackVanillaInClient(Path shadersRoot, String vertexPath, String fragmentPath) {
        // Pile JVM par defaut du thread appelant potentiellement trop petite pour cette chaine sur
        // un VRAI fichier de shaderpack (10k+ lignes apres #include, ~100k caracteres) : un
        // "java.lang.OutOfMemoryError: Out of stack space" reel a ete observe cette nuit sur le
        // fragment (21/09/2026, RECHERCHE-VULKAN-SANS-SODIUM.md etape 8) — suspect le plus probable
        // est le parseur recursif-descendant de glslang (sous shaderc, natif, JNI, partage la pile
        // OS du thread appelant) sur les expressions imbriquees d'un shader de cette taille reelle ;
        // JcppProcessor (aussi recursif pour l'expansion de macros) et UniformReblocker (regex sur
        // gros texte) restent des suspects secondaires, donc toute la chaine est executee ici dans
        // un thread dedie a pile large (64 Mo) plutot que de cibler un seul appel — solution de
        // prototype (pile fixe genereuse), pas un correctif de production.
        return runWithLargeStack(() -> testRealPackVanillaInClientOnCurrentThread(shadersRoot, vertexPath, fragmentPath));
    }

    private static boolean testRealPackVanillaInClientOnCurrentThread(Path shadersRoot, String vertexPath, String fragmentPath) {
        try {
            AbsolutePackPath vertexAbs = AbsolutePackPath.fromAbsolutePath(vertexPath);
            AbsolutePackPath fragmentAbs = AbsolutePackPath.fromAbsolutePath(fragmentPath);
            IncludeGraph graph = new IncludeGraph(shadersRoot, ImmutableList.of(vertexAbs, fragmentAbs), false);
            if (!graph.getFailures().isEmpty()) {
                Lanterne.LOG.warn("[SHADERC] Resolution des #include du vrai shaderpack echouee : {}",
                        graph.getFailures());
                return false;
            }
            IncludeProcessor processor = new IncludeProcessor(graph);
            String vertexFlat = String.join("\n", processor.getIncludedFile(vertexAbs));
            String fragmentFlat = String.join("\n", processor.getIncludedFile(fragmentAbs));
            Lanterne.LOG.info("[SHADERC] #include resolus pour de vrai (vertex {} lignes, fragment {} lignes), "
                    + "preprocessing jcpp (defines/ifdef) avant patchVanilla + reblock...",
                    vertexFlat.lines().count(), fragmentFlat.lines().count());
            // patchVanilla (comme le vrai pipeline Iris) attend un GLSL deja preprocesse — les
            // directives #define/#ifdef du #include brut (elles viennent des lignes litterales des
            // fichiers .vsh/.fsh, cf ShadercProbeSelfCheck) doivent d'abord passer par le meme
            // preprocesseur C qu'utilise reellement Iris pour un shaderpack (deja migre, deja
            // prouve sur le composite via testReal) - sinon patchVanilla rejette la source avec
            // "Unparsed preprocessor directives... may not be present at this stage".
            String vertexPreprocessed = JcppProcessor.glslPreprocessSource(vertexFlat,
                    List.of(new net.irisshaders.iris.helpers.StringPair("VERTEX_SHADER", "")));
            String fragmentPreprocessed = JcppProcessor.glslPreprocessSource(fragmentFlat,
                    List.of(new net.irisshaders.iris.helpers.StringPair("FRAGMENT_SHADER", "")));
            return testVanillaReblockedInClient(vertexPreprocessed, fragmentPreprocessed);
        } catch (Throwable problem) {
            Lanterne.LOG.warn("[SHADERC] Echec sur le vrai fichier de shaderpack ({} / {}) : {}",
                    vertexPath, fragmentPath, problem.toString());
            return false;
        }
    }

    /**
     * Execute {@code task} dans un thread dedie avec une pile OS de 64 Mo (au lieu de la pile par
     * defaut du thread appelant, souvent 512 Ko-1 Mo sur Windows pour un thread non-principal) puis
     * attend son resultat. Reponse ciblee au "Out of stack space" reel rencontre sur un VRAI fichier
     * de shaderpack cette nuit (voir {@link #testRealPackVanillaInClient}) — jamais utilise sur le
     * chemin de rendu normal du mod, uniquement par ce prototype de preuve isole.
     */
    private static boolean runWithLargeStack(java.util.concurrent.Callable<Boolean> task) {
        boolean[] resultHolder = {false};
        Throwable[] errorHolder = {null};
        // 64 Mo insuffisant sur le vrai fragment gbuffers_terrain (99760 caracteres reblockes) :
        // OutOfMemoryError: Out of stack space confirme le 21/09/2026 dans un vrai contexte client
        // (RECHERCHE-VULKAN-SANS-SODIUM.md etape 8), meme apres le fix "3 uniforms non captures".
        // Porte a 256 Mo pour ce meme test -- toujours un prototype isole, jamais branche au rendu.
        // Etape 12 (21/09, reprise apres deblocage Gradle) : preuve obtenue que 256 Mo etait deja
        // insuffisant, et que l'OutOfMemoryError se produit precisement dans l'appel natif JNI
        // Shaderc.shaderc_compile_into_spv (pas dans UniformReblocker/JcppProcessor, tous les deux
        // Java purs et confirmes non fautifs par le log de checkpoint ajoute cette passe). Teste ici
        // 512 Mo pour determiner si c'est encore une question de profondeur (shaderc/glslang interne,
        // parseur recursif-descendant natif) ou une vraie recursion non bornee independante de la
        // taille de pile (auquel cas ce chiffre ne changera rien non plus).
        Thread worker = new Thread(null, () -> {
            try {
                resultHolder[0] = task.call();
            } catch (Throwable problem) {
                errorHolder[0] = problem;
            }
        }, "lanterne-shaderc-probe-large-stack", 512L * 1024 * 1024);
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Lanterne.LOG.warn("[SHADERC] Thread a pile etendue interrompu avant la fin.");
            return false;
        }
        if (errorHolder[0] != null) {
            Lanterne.LOG.warn("[SHADERC] Echec dans le thread a pile etendue (64 Mo) : {}", errorHolder[0].toString());
            return false;
        }
        return resultHolder[0];
    }

    private static boolean compileSpirv(String glslSource, int shaderKind, String fileNameForErrors) {
        long compiler = 0L;
        long options = 0L;
        long result = 0L;
        try {
            compiler = Shaderc.shaderc_compiler_initialize();
            if (compiler == 0L) {
                Lanterne.LOG.warn("[SHADERC] shaderc_compiler_initialize a renvoye 0 (natives absentes ?)");
                return false;
            }
            // Le GLSL produit par TransformPatcher est du GLSL "style OpenGL" (uniforms/entrees-sorties
            // sans layout(binding=)/layout(location=) explicite, jamais necessaire pour compiler vers
            // du bytecode GL) — SPIR-V exige ces decorations explicitement. shaderc sait les assigner
            // automatiquement (meme mecanisme que glslang --auto-map-bindings) plutot que de forcer une
            // reecriture du GLSL en amont ; decouvert en reponse a l'echec precis observe dans un vrai
            // contexte client (21/09/2026, voir RECHERCHE-VULKAN-SANS-SODIUM.md).
            options = Shaderc.shaderc_compile_options_initialize();
            if (options != 0L) {
                Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
                Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
            }
            result = Shaderc.shaderc_compile_into_spv(compiler, glslSource, shaderKind, fileNameForErrors, "main", options);
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
        } finally {
            if (result != 0L) {
                Shaderc.shaderc_result_release(result);
            }
            if (options != 0L) {
                Shaderc.shaderc_compile_options_release(options);
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
