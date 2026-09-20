package fr.clubcitrouille.lanterne.client.reseau;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;

import fr.clubcitrouille.lanterne.core.Settings;

/**
 * La lueur : le petit signe de vie commun à tout le système de coffres/réseau.
 *
 * <h2>Ce que le patron a demandé, et pourquoi ce n'est PAS le Cœur de repère</h2>
 *
 * <p>« Fais pour le Trieur et le Réseau ce que tu as fait pour le portail. » Le portail — voir
 * {@code content.waypoint.Heart} — n'a pourtant aucun code d'animation à lui : une fois allumé, le
 * cadre forme un VRAI {@code minecraft:nether_portal} vanilla, et c'est le tourbillon violet natif du
 * jeu qui tourne, pas une ligne écrite dans ce mod. Ce que le patron a en tête n'est donc pas un bout
 * de code à copier, mais une IMPRESSION à égaler : un bloc qui semble vivant plutôt que posé là.
 *
 * <p>Cette classe est la réponse à cette impression pour cinq blocs qui n'ont, eux, aucun équivalent
 * vanilla à activer : {@code Trieur}, {@code Aiguillage}, {@code Guichet}, {@code CoffreDepot},
 * {@code CoffreReception}. Un seul dessin partagé, pour que les cinq parlent le même langage visuel
 * au lieu de cinq effets bricolés séparément.
 *
 * <h2>Une croix de deux plans, pas un modèle</h2>
 *
 * <p>Même procédé que les fleurs et hautes herbes de vanilla : deux quadrilatères perpendiculaires,
 * chacun soumis des deux côtés pour rester visible quel que soit l'angle, sans jamais calculer une
 * orientation face-caméra. Quatre appels {@code addVertex} par plan, seize au total — un coût que
 * même un grand trieur de 194 blocs (voir {@code bot.GrandTrieurSelfCheck} du dépôt AutoMiner, à
 * l'échelle de 64 coffres) ne fait pas mesurer.
 *
 * <h2>Émissif, et non additif</h2>
 *
 * <p>{@code RenderTypes.entityTranslucentEmissive} — le type déjà retenu par {@link
 * fr.clubcitrouille.lanterne.client.screen.BeamerRenderer} pour son image projetée — est préféré au
 * type « faisceau de balise » que ce même renderer utilise pour son cône : celui-ci porte un second
 * argument booléen dont ni le {@code javap} ni aucune source décompilée de ce dépôt ne disent le sens
 * exact, et un effet censé rester SOBRE ne doit pas dépendre d'un pari sur un paramètre muet. Le type
 * émissif est sans ambiguïté : plein jour comme en plein noir, et occulté par un mur comme n'importe
 * quelle géométrie normale — jamais visible à travers ce qu'il ne devrait pas traverser.
 *
 * <h2>Une seule horloge, un déphasage par position</h2>
 *
 * <p>{@code Mth.sin} sur le temps de jeu (ticks + fraction de tick, comme partout ailleurs dans ce
 * mod pour une lecture fluide) donne le battement. Sans déphasage, soixante-quatre coffres d'un même
 * entrepôt clignoteraient À L'UNISSON, ce qui a l'air d'un néon défectueux plutôt que d'un réseau
 * vivant — un simple mélange de bits de la position, gratuit, suffit à désynchroniser chaque bloc du
 * suivant.
 *
 * <h2>Une seule porte pour couper l'effet</h2>
 *
 * <p>{@link Settings#reseauLueur()} est vérifié ICI, au même endroit pour les cinq blocs, plutôt que
 * répété dans chaque renderer : un joueur qui coupe le réglage l'éteint partout d'un coup, et aucun
 * appelant ne peut l'oublier.
 */
public final class Lueur {
    /** Même teinte que {@code client.Dials#AMBER} : un seul ambre pour tout le mod. */
    private static final int R = 0xFF;
    private static final int G = 0xC8;
    private static final int B = 0x57;

    /** Bornes d'opacité, sur 255 : assez pour se voir, assez peu pour rester un DÉTAIL. */
    private static final float ALPHA_MIN = 30f;
    private static final float ALPHA_MAX = 100f;

    /** Une respiration toutes les trois secondes environ (60 ticks) — ni un clignotement, ni figé. */
    private static final float VITESSE = (float) (2.0 * Math.PI / 60.0);

    /** Demi-largeur de chaque plan, en blocs. */
    private static final float RAYON = 0.22f;

    /** La croix flotte juste au-dessus du bloc : lisible sans jamais gêner la silhouette du modèle. */
    private static final float BAS = 1.02f;
    private static final float HAUT = 1.34f;

    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath("lanterne", "textures/block/faisceau.png");

    private Lueur() {}

    /**
     * Soumet la croix pulsée, centrée au-dessus de {@code pos}. N'appelle rien si l'effet est
     * coupé au réglage — l'appelant n'a donc pas besoin de tester {@link Settings#reseauLueur()}
     * lui-même avant d'appeler cette méthode.
     *
     * <p>{@code animTime} se calcule dans {@code extractRenderState} — le seul endroit où un
     * renderer voit encore le bloc-entité et donc son niveau — et voyage jusqu'ici via l'état de
     * rendu : {@code submit} ne reçoit plus jamais le bloc-entité, seulement ce que l'extraction y a
     * écrit. Voir chaque renderer pour le calcul exact (temps de jeu + fraction de tick).
     */
    public static void pulse(PoseStack poseStack, SubmitNodeCollector collector, BlockPos pos,
            float animTime) {
        if (!Settings.reseauLueur()) {
            return;
        }
        float temps = animTime;
        float phase = phaseOf(pos);
        float onde = 0.5f + 0.5f * Mth.sin(temps * VITESSE + phase);
        int alpha = Math.round(ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * onde);
        int couleur = ARGB.color(alpha, R, G, B);

        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(TEXTURE),
                (pose, buffer) -> croix(pose, buffer, couleur));
    }

    /**
     * Un déphasage stable par bloc, tiré de sa position — jamais deux blocs voisins en phase, sans
     * le moindre état à tenir à jour ni la moindre allocation.
     */
    private static float phaseOf(BlockPos pos) {
        int mix = pos.getX() * 0x2545F491 ^ pos.getY() * 0x27D4EB2F ^ pos.getZ() * 0x165667B1;
        return (mix & 0xFFFF) / 65536f * (float) (2.0 * Math.PI);
    }

    private static void croix(PoseStack.Pose pose, VertexConsumer buffer, int couleur) {
        // Plan aligné sur l'axe X (large en X, fin en Z), les deux faces pour rester visible de face
        // comme de dos sans dépendre du sens de culling du type de rendu.
        plan(pose, buffer, couleur,
                0.5f - RAYON, BAS, 0.5f, 0.5f + RAYON, BAS, 0.5f,
                0.5f + RAYON, HAUT, 0.5f, 0.5f - RAYON, HAUT, 0.5f, 0f, 0f, 1f);
        plan(pose, buffer, couleur,
                0.5f + RAYON, BAS, 0.5f, 0.5f - RAYON, BAS, 0.5f,
                0.5f - RAYON, HAUT, 0.5f, 0.5f + RAYON, HAUT, 0.5f, 0f, 0f, -1f);
        // Plan aligné sur l'axe Z.
        plan(pose, buffer, couleur,
                0.5f, BAS, 0.5f - RAYON, 0.5f, BAS, 0.5f + RAYON,
                0.5f, HAUT, 0.5f + RAYON, 0.5f, HAUT, 0.5f - RAYON, 1f, 0f, 0f);
        plan(pose, buffer, couleur,
                0.5f, BAS, 0.5f + RAYON, 0.5f, BAS, 0.5f - RAYON,
                0.5f, HAUT, 0.5f - RAYON, 0.5f, HAUT, 0.5f + RAYON, -1f, 0f, 0f);
    }

    private static void plan(PoseStack.Pose pose, VertexConsumer buffer, int couleur,
            float x0, float y0, float z0, float x1, float y1, float z1,
            float x2, float y2, float z2, float x3, float y3, float z3,
            float nx, float ny, float nz) {
        sommet(pose, buffer, x0, y0, z0, 0f, 1f, couleur, nx, ny, nz);
        sommet(pose, buffer, x1, y1, z1, 1f, 1f, couleur, nx, ny, nz);
        sommet(pose, buffer, x2, y2, z2, 1f, 0f, couleur, nx, ny, nz);
        sommet(pose, buffer, x3, y3, z3, 0f, 0f, couleur, nx, ny, nz);
    }

    private static void sommet(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z,
            float u, float v, int couleur, float nx, float ny, float nz) {
        buffer.addVertex(pose, x, y, z)
                .setColor(couleur)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightCoordsUtil.FULL_BRIGHT)
                .setNormal(pose, nx, ny, nz);
    }
}
