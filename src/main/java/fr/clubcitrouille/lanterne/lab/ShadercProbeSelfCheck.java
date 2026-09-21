package fr.clubcitrouille.lanterne.lab;

import net.minecraft.client.Minecraft;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Vérification automatique, dans un vrai contexte client (fenêtre + GPU vivants), de
 * {@link ShadercProbe#testCompositeInClient} — même besoin que {@code regard.RegardSelfCheck} :
 * confirmer un comportement sans humain devant l'écran, via le lanceur client jetable
 * ({@code C:\lanterne-client-test\launch7.bat}).
 *
 * <h2>Pourquoi ceci ne pouvait pas être vérifié en standalone</h2>
 *
 * <p>{@code TransformPatcher.patchComposite}/{@code patchDepth} appellent
 * {@code RenderSystem.getDevice().getDeviceInfo()} — un device qui n'existe qu'une fois la fenêtre
 * du jeu ouverte. Voir {@code RECHERCHE-VULKAN-SANS-SODIUM.md} (commit 994df5e) pour le blocage
 * précédent, rencontré en essayant d'appeler ce même code depuis {@code ShadercProbe.main}
 * (processus Java nu, jamais de fenêtre).
 *
 * <h2>Gated, comme le reste de ce laboratoire</h2>
 *
 * <p>{@code LANTERNE_SHADERC_COMPOSITE_TEST=1} uniquement — jamais actif pour un joueur normal.
 * Sans variable, cette classe ne fait rien du tout et ne touche jamais le rendu réel.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class ShadercProbeSelfCheck {
    private static final boolean ARMED = "1".equals(System.getenv("LANTERNE_SHADERC_COMPOSITE_TEST"));

    /**
     * Fragment composite autonome, sans {@code #include} (voir la Javadoc de
     * {@link ShadercProbe#testCompositeInClient} pour pourquoi) — assez proche d'un vrai composite
     * de shaderpack (échantillonne une texture nommée comme le fait Iris, {@code colortex0}) pour
     * que la preuve porte sur la vraie chaîne, pas sur un shader jouet sans rapport.
     */
    private static final String COMPOSITE_SOURCE = """
            #version 150

            uniform sampler2D colortex0;

            in vec2 texcoord;

            /* DRAWBUFFERS:0 */
            layout(location = 0) out vec4 outColor0;

            void main() {
                vec4 color = texture(colortex0, texcoord);
                outColor0 = vec4(color.rgb * 0.5, color.a);
            }
            """;

    /**
     * Paire vertex+fragment autonome pour {@link ShadercProbe#testVanillaInClient} — un "gbuffers"
     * minimal mais realiste (position, couleur de vertex, lumiere), le point d'entree le PLUS
     * utilise reellement par Iris (voir sa Javadoc pour pourquoi tester ceci en plus du composite).
     *
     * <p>Version SANS builtins GLSL legacy ({@code ftransform()}, {@code gl_TexCoord},
     * {@code gl_FrontColor}, {@code gl_Color}, {@code texture2D}, {@code gl_FragData}) — remplaces
     * par leurs equivalents modernes explicites (matrice/attributs/varyings/fonctions nommes),
     * pour isoler le test du re-blocage UBO (voir {@code RECHERCHE-VULKAN-SANS-SODIUM.md}, etape 6)
     * du probleme separe et deja documente : {@code CompatibilityTransformer} ne modernise pas ces
     * builtins dans le cas testes ici. La premiere version (avec builtins legacy) avait deja prouve
     * que le re-blocage marchait sur l'etage FRAGMENT (3532 octets SPIR-V) ; seul l'etage VERTEX
     * restait a confirmer une fois ce probleme annexe retire.
     */
    private static final String VANILLA_VERTEX_SOURCE = """
            #version 150

            in vec3 mc_Entity;
            in vec4 mc_midTexCoord;

            uniform mat4 modelViewMatrix;
            uniform mat4 projectionMatrix;

            in vec4 vaPosition;
            in vec4 vaColor;
            in vec2 vaUV0;

            out vec4 frontColor;
            out vec2 texCoord;

            void main() {
                gl_Position = projectionMatrix * modelViewMatrix * vaPosition;
                frontColor = vaColor;
                texCoord = vaUV0;
            }
            """;

    private static final String VANILLA_FRAGMENT_SOURCE = """
            #version 150

            uniform sampler2D texture;

            in vec4 frontColor;
            in vec2 texCoord;

            layout(location = 0) out vec4 fragColor;

            void main() {
                vec4 color = texture(texture, texCoord);
                fragColor = color * frontColor;
            }
            """;

    private static boolean ran;

    private ShadercProbeSelfCheck() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ARMED || ran) {
            return;
        }
        // Le menu principal suffit (voir TACHES-NUIT.md, TÂCHE C : la fenêtre/le device GPU sont
        // prêts dès le menu principal, aucun monde n'est requis pour ce test) — inutile d'attendre
        // qu'un joueur charge une sauvegarde.
        if (Minecraft.getInstance().getWindow() == null) {
            return;
        }
        ran = true;
        Lanterne.LOG.info("[SHADERC-TEST] contexte client prêt, lancement de la chaîne complète "
                + "jcpp -> glsl-transformer -> shaderc (composite)...");
        boolean compositeOk = ShadercProbe.testCompositeInClient(COMPOSITE_SOURCE);
        Lanterne.LOG.info("[SHADERC-TEST] RESULTAT COMPOSITE: {}", compositeOk ? "SUCCES" : "ECHEC");

        Lanterne.LOG.info("[SHADERC-TEST] meme chaîne sur patchVanilla (gbuffers, vertex+fragment)...");
        boolean vanillaOk = ShadercProbe.testVanillaInClient(VANILLA_VERTEX_SOURCE, VANILLA_FRAGMENT_SOURCE);
        Lanterne.LOG.info("[SHADERC-TEST] RESULTAT VANILLA: {}", vanillaOk ? "SUCCES" : "ECHEC");

        Lanterne.LOG.info("[SHADERC-TEST] patchVanilla + re-blocage des uniforms en UBO (reponse au "
                + "blocage 'non-opaque uniforms outside a block')...");
        boolean vanillaReblockedOk = ShadercProbe.testVanillaReblockedInClient(VANILLA_VERTEX_SOURCE, VANILLA_FRAGMENT_SOURCE);
        Lanterne.LOG.info("[SHADERC-TEST] RESULTAT VANILLA REBLOCKED: {}", vanillaReblockedOk ? "SUCCES" : "ECHEC");
    }
}
