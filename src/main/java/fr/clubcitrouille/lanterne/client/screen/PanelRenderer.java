package fr.clubcitrouille.lanterne.client.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import org.joml.Vector3f;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import fr.clubcitrouille.lanterne.content.screen.Feed;
import fr.clubcitrouille.lanterne.content.screen.PanelEntity;
import fr.clubcitrouille.lanterne.content.screen.Wall;

/**
 * Le dessin d'un mur d'écran.
 *
 * <h2>Quatre sommets pour deux cent cinquante-six blocs</h2>
 *
 * <p>C'est la récompense du multibloc, et elle est disproportionnée. Le maître dessine <b>un</b>
 * rectangle qui couvre la surface entière ; les autres blocs du mur ne dessinent rien du tout. Un
 * écran de seize sur seize coûte donc exactement ce que coûte un écran d'un sur un.
 *
 * <p>Le contraste avec {@code content.painting.CanvasRenderer} vaut d'être noté, parce qu'il découpe
 * ses tableaux bloc par bloc. Ce n'est pas une incohérence : il le fait <b>pour la lumière</b>, parce
 * qu'un tableau est éclairé par le monde et qu'un seul grand rectangle n'aurait que quatre sommets à
 * éclairer, donc un dégradé faux à cheval sur deux pièces. Un écran, lui, <b>émet</b> sa lumière —
 * voir {@link Feed#brightness()} — et un éclairage uniforme est justement ce qu'on veut. La
 * contrainte disparue, le découpage n'a plus de raison d'être.
 *
 * <h2>Le décodeur n'est jamais appelé d'ici</h2>
 *
 * <p>Ce fichier ne décode rien, n'ouvre rien et n'alloue rien. Il signale à {@link Gaze} qu'un écran
 * est sous les yeux du joueur et à quelle distance, puis demande la pellicule que {@link Gaze} a bien
 * voulu lui préparer. Décoder depuis le rendu reviendrait à faire attendre l'image du jeu qu'une
 * image de film soit prête — le défaut classique, et celui qui transforme une saccade de décodage en
 * saccade de jeu.
 *
 * <h2>Jamais de carré noir</h2>
 *
 * <p>Sans pellicule, on ne s'abstient pas : on peint une ardoise sombre et une ligne qui dit
 * pourquoi. Voir {@link Still#slate()}. Un écran muet est un écran qu'on croit cassé ; un écran qui
 * s'explique est un écran qui attend quelque chose.
 */
public class PanelRenderer implements BlockEntityRenderer<PanelEntity, PanelRenderer.State> {
    /**
     * De combien l'image avance sur la face qu'elle couvre.
     *
     * <p>Un demi-millième de bloc, comme le cadre des tableaux : assez pour que le tampon de
     * profondeur sache laquelle des deux surfaces est devant, assez peu pour qu'aucun relief ne se
     * voie. Si un clignotement apparaissait entre l'image et le bloc, c'est ce nombre qu'il faudrait
     * doubler.
     */
    private static final float LIP = 0.0005f;

    /** L'ardoise : un gris très sombre, mais pas noir — un noir pur passerait pour un trou. */
    private static final int SLATE = 0xFF14141A;

    /**
     * La texture unie qui sert d'ardoise et de bandes noires.
     *
     * <h2>Pourquoi une texture, alors qu'une couleur suffirait</h2>
     *
     * <p>Parce que le <b>format de sommet</b> doit être le même partout. {@code RenderTypes.textBackground}
     * paraît fait pour cela — un rectangle uni, sans texture — mais son gabarit est
     * {@code POSITION_COLOR_LIGHTMAP} : ni coordonnée de texture, ni normale, ni recouvrement. Y
     * écrire les sommets qu'attend {@code entityCutout} ne compile pas moins bien et ne se voit pas
     * moins tard : cela casse à l'exécution, sur la première image, chez le joueur.
     *
     * <p>Une case unie dans notre propre atlas ramène tout au même gabarit et au même type de rendu.
     * L'image, l'ardoise et les bandes partent donc dans le même lot de dessin — c'est même une
     * petite économie, là où l'on cherchait seulement à ne pas se tromper.
     */
    private static final Identifier SLATE_TEXTURE =
            Identifier.fromNamespaceAndPath("lanterne", "textures/block/ardoise.png");

    private final Font font;

    public PanelRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.font();
    }

    /** L'état lu du monde, une fois par image et par mur. */
    public static class State extends BlockEntityRenderState {
        public boolean master;
        public Direction facing = Direction.NORTH;
        public int wide = 1;
        public int high = 1;
        public int light;
        public @Nullable Identifier film;
        public float aspect = 16f / 9f;
        public Feed.Shape shape = Feed.Shape.ENTIER;
        public String slate = "";
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(PanelEntity panel, State state, float partialTicks,
            Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(panel, state, partialTicks, cameraPosition,
                breakProgress);
        state.master = panel.isMaster();
        if (!state.master) {
            return; // un esclave ne dessine rien : tout est déjà couvert par le maître
        }
        state.facing = panel.facing();
        state.wide = panel.wallWidth();
        state.high = panel.wallHeight();
        Feed feed = panel.feed();
        state.shape = feed.shape();

        // La lumière est celle du bloc, relevée d'autant que la luminosité réglée. Un écran à quinze
        // se voit dans le noir ; un écran à zéro reste soumis à l'éclairage de la pièce, ce qui est
        // exactement ce qu'on veut d'un écran éteint accroché dans un couloir.
        state.light = LightCoordsUtil.withBlock(state.lightCoords,
                Math.max(LightCoordsUtil.block(state.lightCoords), feed.brightness()));

        // Le seul dialogue avec le décodeur : « je suis visible, à telle distance ». La décision
        // d'ouvrir, de fermer ou de ralentir appartient entièrement à Gaze.
        double distance = Math.sqrt(cameraPosition.distanceToSqr(
                Vec3.atCenterOf(panel.getBlockPos())));
        Gaze.notice(panel.getBlockPos(), distance, panel.wallWidth());

        Film film = Gaze.film(panel.getBlockPos());
        if (film != null) {
            state.film = film.id();
            state.aspect = (float) film.width() / Math.max(1, film.height());
            state.slate = "";
        } else {
            state.film = null;
            state.slate = feed.idle() ? "" : Still.slate();
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState camera) {
        if (!state.master) {
            return;
        }
        Direction facing = state.facing;
        Direction across = Wall.across(facing);

        Vector3f normal = vector(facing);
        Vector3f right = vector(across);
        Vector3f up = new Vector3f(0f, 1f, 0f);

        // Le coin bas-gauche du spectateur, sur la face du bloc maître. Voir Wall.across pour le
        // sens de « gauche », et pourquoi il n'est pas arbitraire.
        Vector3f origin = new Vector3f(0.5f, 0.5f, 0.5f)
                .fma(0.5f + LIP, normal)
                .fma(-0.5f, right)
                .fma(-0.5f, up);

        float wide = state.wide;
        float high = state.high;

        if (state.film == null) {
            // L'ardoise occupe le mur entier : une ardoise rétrécie laisserait une bordure de bloc
            // visible et l'on croirait à un défaut d'assemblage.
            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(SLATE_TEXTURE),
                    (pose, buffer) -> quad(pose, buffer, origin, right, up, wide, high,
                            0f, 0f, 1f, 1f, state.light, SLATE, normal));
            if (!state.slate.isEmpty()) {
                caption(state, poseStack, collector, origin, right, up, wide, high);
            }
            return;
        }

        // Le cadrage décide de la portion d'image qu'on montre et de la portion de mur qu'on couvre.
        float[] box = frame(state, wide, high);
        Identifier texture = state.film;
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture),
                (pose, buffer) -> quad(pose, buffer,
                        new Vector3f(origin).fma(box[0], right).fma(box[1], up),
                        right, up, box[2], box[3],
                        box[4], box[5], box[6], box[7], state.light, -1, normal));

        // « Entier » laisse des bandes : elles sont peintes, et non laissées en trou. Un trou
        // montrerait le bloc derrière, dont la texture n'a rien à faire dans l'image.
        if (state.shape == Feed.Shape.ENTIER && (box[2] < wide - 0.001f || box[3] < high - 0.001f)) {
            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(SLATE_TEXTURE),
                    (pose, buffer) -> letterbox(pose, buffer, origin, right, up, wide, high, box,
                            state.light, normal));
        }
    }

    /**
     * Où poser l'image dans le mur, et quelle part de l'image montrer.
     *
     * <p>Rend huit nombres : décalage et taille sur le mur, puis les quatre coordonnées de texture.
     * Les trois cadrages se ramènent à ce seul calcul, ce qui évite trois chemins de rendu séparés —
     * et trois occasions d'en corriger un et pas les autres.
     */
    private static float[] frame(State state, float wide, float high) {
        float wall = wide / high;
        float video = state.aspect;
        return switch (state.shape) {
            // Étiré : l'image entière sur le mur entier, tant pis pour les proportions.
            case ETIRE -> new float[] {0f, 0f, wide, high, 0f, 0f, 1f, 1f};
            case ENTIER -> {
                // L'image entière, rétrécie jusqu'à tenir. Ce qui reste du mur sera peint en noir.
                float w = wall > video ? high * video : wide;
                float h = wall > video ? high : wide / video;
                yield new float[] {(wide - w) / 2f, (high - h) / 2f, w, h, 0f, 0f, 1f, 1f};
            }
            case REMPLI -> {
                // Le mur entier, image rognée. On réduit la fenêtre de texture, pas la géométrie :
                // la carte graphique fait le recadrage gratuitement, au filtrage près.
                float keepU = wall > video ? 1f : video / wall;
                float keepV = wall > video ? wall / video : 1f;
                float u = (1f - 1f / Math.max(1f, keepU)) / 2f;
                float v = (1f - 1f / Math.max(1f, keepV)) / 2f;
                yield new float[] {0f, 0f, wide, high, u, v, 1f - u, 1f - v};
            }
        };
    }

    /** Les bandes noires autour d'une image « entière », peintes plutôt que laissées en trou. */
    private static void letterbox(PoseStack.Pose pose, VertexConsumer buffer, Vector3f origin,
            Vector3f right, Vector3f up, float wide, float high, float[] box, int light,
            Vector3f normal) {
        float left = box[0];
        float bottom = box[1];
        float w = box[2];
        float h = box[3];
        if (left > 0.001f) {
            band(pose, buffer, origin, right, up, 0f, 0f, left, high, light, normal);
            band(pose, buffer, origin, right, up, left + w, 0f, wide - left - w, high, light, normal);
        }
        if (bottom > 0.001f) {
            band(pose, buffer, origin, right, up, left, 0f, w, bottom, light, normal);
            band(pose, buffer, origin, right, up, left, bottom + h, w, high - bottom - h, light,
                    normal);
        }
    }

    private static void band(PoseStack.Pose pose, VertexConsumer buffer, Vector3f origin,
            Vector3f right, Vector3f up, float x, float y, float w, float h, int light,
            Vector3f normal) {
        if (w <= 0.001f || h <= 0.001f) {
            return;
        }
        quad(pose, buffer, new Vector3f(origin).fma(x, right).fma(y, up), right, up, w, h,
                0f, 0f, 1f, 1f, light, SLATE, normal);
    }

    /**
     * La ligne de l'ardoise, posée au milieu du mur.
     *
     * <p>La rotation amène le repère local du texte dans le plan de l'écran : {@code -toYRot} envoie
     * le {@code +Z} local sur la normale du mur, et le {@code +X} local sur la droite du spectateur.
     * L'échelle négative en Y retourne l'axe vertical, parce qu'une police compte ses lignes vers le
     * bas et que le monde compte sa hauteur vers le haut.
     */
    private void caption(State state, PoseStack poseStack, SubmitNodeCollector collector,
            Vector3f origin, Vector3f right, Vector3f up, float wide, float high) {
        // Une taille qui suit le mur, bornée : sur un mur d'un bloc la ligne doit rester lisible,
        // sur un mur de seize elle ne doit pas devenir une enseigne.
        float scale = Math.min(0.06f, 0.012f * Math.max(1f, wide));
        Vector3f centre = new Vector3f(origin).fma(wide / 2f, right).fma(high / 2f, up);
        var text = Component.literal(state.slate).getVisualOrderText();

        poseStack.pushPose();
        poseStack.translate(centre.x(), centre.y(), centre.z());
        poseStack.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()));
        poseStack.scale(scale, -scale, scale);
        collector.submitText(poseStack, -this.font.width(text) / 2f, -4f, text, false,
                Font.DisplayMode.POLYGON_OFFSET, LightCoordsUtil.FULL_BRIGHT, 0xFFFFC857, 0, 0);
        poseStack.popPose();
    }

    /** Un rectangle dans le plan (droite, haut), à partir d'un coin. */
    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, Vector3f corner,
            Vector3f right, Vector3f up, float w, float h,
            float u0, float v0, float u1, float v1, int light, int colour, Vector3f normal) {
        // Les coordonnées de texture descendent quand on monte : une image a son origine en haut à
        // gauche, un mur a la sienne en bas à gauche. L'oublier retourne l'image, ce qui ne se voit
        // que le jour où un texte apparaît dedans.
        vertex(pose, buffer, corner, 0f, 0f, right, up, u0, v1, light, colour, normal);
        vertex(pose, buffer, corner, w, 0f, right, up, u1, v1, light, colour, normal);
        vertex(pose, buffer, corner, w, h, right, up, u1, v0, light, colour, normal);
        vertex(pose, buffer, corner, 0f, h, right, up, u0, v0, light, colour, normal);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, Vector3f corner,
            float alongRight, float alongUp, Vector3f right, Vector3f up, float u, float v,
            int light, int colour, Vector3f normal) {
        Vector3f at = new Vector3f(corner).fma(alongRight, right).fma(alongUp, up);
        buffer.addVertex(pose, at.x(), at.y(), at.z())
                .setColor(colour)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, normal.x(), normal.y(), normal.z());
    }

    private static Vector3f vector(Direction direction) {
        return new Vector3f(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    /**
     * L'emprise : le mur entier, et non le cube du maître.
     *
     * <p>Le calcul vit dans {@code PanelEntity.wallBox} — il ne dépend que de l'assemblage, qui n'est
     * pas une affaire de rendu. Voir la note qui l'accompagne pour le symptôme qu'il évite.
     */
    @Override
    public AABB getRenderBoundingBox(PanelEntity panel) {
        return panel.isMaster() ? panel.wallBox() : new AABB(panel.getBlockPos());
    }
}
