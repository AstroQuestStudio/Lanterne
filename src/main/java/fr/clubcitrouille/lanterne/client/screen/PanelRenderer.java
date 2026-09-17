package fr.clubcitrouille.lanterne.client.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import org.joml.Matrix4f;
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

    /**
     * L'ardoise, et pourquoi elle a été éclaircie.
     *
     * <p>Elle valait {@code 0xFF14141A} — vingt sur vingt-six — et elle était <b>éclairée par la
     * pièce</b>. De nuit, elle valait donc zéro : un joueur a posé un mur de huit sur cinq et n'a
     * rien vu du tout. L'ardoise avait exactement la couleur de ce qu'elle devait remplacer.
     *
     * <p>Deux corrections, et il fallait les deux. La couleur monte à un bleu ardoise franchement
     * visible ; et surtout, tout ce qui appartient à l'ardoise est peint à <b>pleine lumière</b>,
     * donc identique à midi et à minuit. Une surface qui sert à dire « je n'ai pas d'image » ne peut
     * pas dépendre de la lumière ambiante pour se faire lire.
     */
    private static final int SLATE = 0xFF2A3040;

    /** Le liseré : la preuve, sans savoir lire, que le bloc est vivant et qu'il a quelque chose à dire. */
    private static final int EDGE = 0xFFFFC857;

    /** La seconde ligne, plus discrète que la première : elle dit quoi faire, pas ce qui se passe. */
    private static final int HINT = 0xFFBFC6D4;

    /** Épaisseur du liseré, en fraction de bloc. */
    private static final float BAND = 0.06f;

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
        public fr.clubcitrouille.lanterne.content.screen.Sight sight =
                fr.clubcitrouille.lanterne.content.screen.Sight.PLUMB;
        public String what = "";
        public String todo = "";
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

        state.sight = feed.sight();

        Gaze.Picture picture = Gaze.picture(panel.getBlockPos());
        if (picture != null) {
            state.film = picture.id();
            state.aspect = picture.aspect();
            state.what = "";
            state.todo = "";
        } else {
            state.film = null;
            // Toujours quelque chose. C'est Gaze qui connaît la vraie raison — le rendu ne la
            // devine plus, il la peint.
            Slate slate = Gaze.slate(panel.getBlockPos(), feed);
            state.what = slate.what();
            state.todo = slate.todo();
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
            slate(state, poseStack, collector, origin, right, up, wide, high, normal);
            return;
        }

        // Le cadrage décide de la portion d'image qu'on montre et de la portion de mur qu'on couvre.
        float[] box = frame(state, wide, high);
        Identifier texture = state.film;
        int turns = state.sight.spin() / 90;
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(texture),
                (pose, buffer) -> quad(pose, buffer,
                        new Vector3f(origin).fma(box[0], right).fma(box[1], up),
                        right, up, box[2], box[3],
                        box[4], box[5], box[6], box[7], state.light, -1, normal, turns));

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
        float[] box = fit(state, wide, high);
        // Le calage s'applique APRÈS le cadrage, et c'est le bon ordre : le cadrage décide de la
        // forme que l'image doit avoir dans ce mur, le calage la déplace et la redimensionne à
        // partir de là. L'inverse aurait fait recalculer le cadrage sur une image déjà décalée, et
        // le zoom aurait changé le sens des bandes noires.
        float scale = state.sight.scale();
        float grownW = box[2] * scale;
        float grownH = box[3] * scale;
        box[0] += (box[2] - grownW) / 2f + state.sight.blocksX();
        box[1] += (box[3] - grownH) / 2f + state.sight.blocksY();
        box[2] = grownW;
        box[3] = grownH;
        return box;
    }

    private static float[] fit(State state, float wide, float high) {
        float wall = wide / high;
        // Un quart de tour échange largeur et hauteur : c'est l'image tournée qu'il faut faire
        // tenir, pas l'image d'origine.
        float video = state.sight.quarterTurned() ? 1f / state.aspect : state.aspect;
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
     * L'ardoise entière : un fond, un liseré, et deux lignes.
     *
     * <h2>Pourquoi un liseré, alors qu'il ne dit rien</h2>
     *
     * <p>Il dit une chose, et c'est la plus importante : <b>ce bloc fonctionne</b>. Un rectangle uni
     * est indiscernable d'un bloc mal texturé, d'un mod qui n'a pas chargé, ou d'un écran éteint. Un
     * cadre ambre franc ne peut être que délibéré.
     *
     * <p>Et il se voit de plus loin que le texte. Sur un mur qu'on aperçoit à trente blocs, la ligne
     * est illisible mais le cadre se lit tout de suite : « il y a quelque chose à aller voir ».
     *
     * <h2>Tout à pleine lumière</h2>
     *
     * <p>Fond, liseré et texte : {@code FULL_BRIGHT}. L'ardoise est le repli, elle doit se lire dans
     * une cave à minuit exactement comme en plein jour. C'est la correction du défaut qui a rendu un
     * mur entier invisible de nuit.
     */
    private void slate(State state, PoseStack poseStack, SubmitNodeCollector collector,
            Vector3f origin, Vector3f right, Vector3f up, float wide, float high, Vector3f normal) {
        int lit = LightCoordsUtil.FULL_BRIGHT;
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(SLATE_TEXTURE),
                (pose, buffer) -> {
                    quad(pose, buffer, origin, right, up, wide, high, 0f, 0f, 1f, 1f,
                            lit, SLATE, normal);
                    float band = Math.min(BAND, Math.min(wide, high) / 8f);
                    // Les quatre bandes du liseré, très légèrement en avant du fond pour que le
                    // tampon de profondeur n'ait pas à départager deux plans confondus.
                    Vector3f front = new Vector3f(origin).fma(LIP, normal);
                    quad(pose, buffer, front, right, up, wide, band, 0f, 0f, 1f, 1f, lit, EDGE, normal);
                    quad(pose, buffer, new Vector3f(front).fma(high - band, up), right, up,
                            wide, band, 0f, 0f, 1f, 1f, lit, EDGE, normal);
                    quad(pose, buffer, new Vector3f(front).fma(band, up), right, up,
                            band, high - 2f * band, 0f, 0f, 1f, 1f, lit, EDGE, normal);
                    quad(pose, buffer, new Vector3f(front).fma(wide - band, right).fma(band, up),
                            right, up, band, high - 2f * band, 0f, 0f, 1f, 1f, lit, EDGE, normal);
                });
        if (state.what.isEmpty()) {
            return;
        }
        // Le texte nettement en avant du fond : un centième de bloc. La police est dessinée en
        // « POLYGON_OFFSET », qui suffit d'ordinaire ; sur une surface qu'on peut regarder très en
        // biais, un écart réel coûte moins cher qu'un texte qui clignote.
        Vector3f face = new Vector3f(origin).fma(0.01f, normal);
        Vector3f centre = new Vector3f(face).fma(wide / 2f, right).fma(high / 2f, up);

        poseStack.pushPose();
        poseStack.translate(centre.x(), centre.y(), centre.z());
        poseStack.mulPose(new Matrix4f().rotation(Axis.YP.rotationDegrees(-state.facing.toYRot())));
        line(poseStack, collector, state.what, wide, -10f, EDGE, lit);
        if (!state.todo.isEmpty()) {
            line(poseStack, collector, state.todo, wide, 2f, HINT, lit);
        }
        poseStack.popPose();
    }

    /**
     * Une ligne, mise à l'échelle pour <b>tenir dans le mur</b>.
     *
     * <h2>La taille se déduit du texte, et non l'inverse</h2>
     *
     * <p>La version précédente choisissait l'échelle d'après la largeur du mur seule. Sur un mur de
     * huit blocs elle donnait 0,06, et une phrase de deux cent cinquante unités de police occupait
     * alors quinze blocs — presque le double du mur. La ligne débordait des deux côtés et flottait
     * dans le vide, ce qui est une façon très efficace de rendre un message illisible.
     *
     * <p>On part donc de la <b>largeur du texte</b> : l'échelle est celle qui le fait tenir dans
     * quatre-vingt-cinq pour cent du mur, plafonnée pour qu'un mot court sur un grand mur ne devienne
     * pas une enseigne. Une phrase longue rapetisse au lieu de déborder — c'est le même arbitrage que
     * {@code client.Fit} fait pour les panneaux d'interface, et pour la même raison.
     */
    private void line(PoseStack poseStack, SubmitNodeCollector collector, String text, float wide,
            float y, int colour, int lit) {
        var ordered = Component.literal(text).getVisualOrderText();
        int width = Math.max(1, this.font.width(ordered));
        float scale = Math.min(0.045f, wide * 0.85f / width);
        poseStack.pushPose();
        poseStack.scale(scale, -scale, scale);
        collector.submitText(poseStack, -width / 2f, y, ordered, false,
                Font.DisplayMode.POLYGON_OFFSET, lit, colour, 0, 0);
        poseStack.popPose();
    }

    /** Un rectangle dans le plan (droite, haut), à partir d'un coin. */
    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, Vector3f corner,
            Vector3f right, Vector3f up, float w, float h,
            float u0, float v0, float u1, float v1, int light, int colour, Vector3f normal) {
        quad(pose, buffer, corner, right, up, w, h, u0, v0, u1, v1, light, colour, normal, 0);
    }

    /**
     * Le même rectangle, l'image tournée de {@code turns} quarts de tour.
     *
     * <h2>On tourne les coordonnées de texture, pas la géométrie</h2>
     *
     * <p>Faire pivoter les quatre sommets autour du centre marcherait et coûterait plus cher :
     * il faudrait recalculer le cadrage, décider ce que deviennent les coins qui sortent du mur, et
     * refaire la boîte englobante. Permuter les quatre coins de texture donne exactement le même
     * résultat à l'écran pour quatre affectations, et la géométrie reste un rectangle droit — ce
     * qui est justement la raison pour laquelle la rotation est limitée aux quarts de tour.
     */
    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, Vector3f corner,
            Vector3f right, Vector3f up, float w, float h,
            float u0, float v0, float u1, float v1, int light, int colour, Vector3f normal,
            int turns) {
        // Les coordonnées de texture descendent quand on monte : une image a son origine en haut à
        // gauche, un mur a la sienne en bas à gauche. L'oublier retourne l'image, ce qui ne se voit
        // que le jour où un texte apparaît dedans.
        float[][] uv = {{u0, v1}, {u1, v1}, {u1, v0}, {u0, v0}};
        int step = Math.floorMod(turns, 4);
        vertex(pose, buffer, corner, 0f, 0f, right, up,
                uv[step][0], uv[step][1], light, colour, normal);
        vertex(pose, buffer, corner, w, 0f, right, up,
                uv[(step + 1) % 4][0], uv[(step + 1) % 4][1], light, colour, normal);
        vertex(pose, buffer, corner, w, h, right, up,
                uv[(step + 2) % 4][0], uv[(step + 2) % 4][1], light, colour, normal);
        vertex(pose, buffer, corner, 0f, h, right, up,
                uv[(step + 3) % 4][0], uv[(step + 3) % 4][1], light, colour, normal);
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
