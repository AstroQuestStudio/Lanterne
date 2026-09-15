package fr.clubcitrouille.lanterne.client.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import org.joml.Vector3f;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import fr.clubcitrouille.lanterne.content.screen.BeamerEntity;
import fr.clubcitrouille.lanterne.content.screen.Feed;

/**
 * Le dessin d'un projecteur : une image qui flotte, et le cône qui la relie à son bloc.
 *
 * <h2>Le cône n'est pas une décoration</h2>
 *
 * <p>Une image de cinq blocs de large posée en l'air, sans rien autour, pose deux questions au joueur
 * qui la découvre : d'où vient-elle, et comment l'éteindre. Le faisceau y répond des deux côtés à la
 * fois — il pointe le bloc, et il montre le chemin.
 *
 * <p>C'est aussi le seul repère quand on <b>règle</b> le projecteur. Faire glisser la portée de huit à
 * vingt blocs sans faisceau, c'est voir une image disparaître et une autre apparaître ailleurs, sans
 * comprendre que c'est la même. Avec le faisceau, on voit le bras s'allonger.
 *
 * <h2>Quatre faces, et pas de fond</h2>
 *
 * <p>Le cône est une pyramide tronquée : quatre quadrilatères entre un petit carré à l'objectif et le
 * rectangle de l'image. Ni couvercle ni fond — l'objectif est caché par le bloc, et l'autre bout est
 * l'image elle-même.
 *
 * <p>Il est peint en translucide additif, du type des rayons de balise, et il <b>s'estompe vers
 * l'image</b> : l'opacité décroît le long du trajet. Un faisceau d'opacité constante ressemble à une
 * boîte de verre ; un faisceau qui s'éteint ressemble à de la lumière dans la poussière, ce qu'il est
 * censé être.
 *
 * <h2>Le cône ne s'arrête à aucun mur, et c'est assumé</h2>
 *
 * <p>Un vrai faisceau s'arrêterait sur la première surface rencontrée. Le calculer demanderait un
 * lancer de rayon <b>par image et par projecteur</b>, c'est-à-dire précisément le genre de dépense
 * que ce mod passe son temps à supprimer ailleurs.
 *
 * <p>La portée est donc réglée à la main — c'est la fonction {@code portée} de l'écran de réglages —
 * et le joueur la pose où il veut que l'image atterrisse. Il obtient plus de contrôle qu'avec une
 * détection automatique, qui l'aurait forcé à bâtir un mur là où il voulait de l'air. Ce qu'on perd
 * est un cas : viser au travers d'une cloison affiche l'image de l'autre côté. C'est un défaut connu,
 * et il vaut mieux qu'un lancer de rayon permanent.
 */
public class BeamerRenderer implements BlockEntityRenderer<BeamerEntity, BeamerRenderer.State> {
    /** Demi-côté du carré à l'objectif, en blocs. La lentille, vue de face. */
    private static final float LENS = 0.16f;

    /** Opacité du faisceau à sa sortie, sur 255. Au-delà, il masque ce qu'il traverse. */
    private static final int HAZE = 56;

    private static final Identifier BEAM =
            Identifier.fromNamespaceAndPath("lanterne", "textures/block/faisceau.png");

    private static final Identifier SLATE_TEXTURE =
            Identifier.fromNamespaceAndPath("lanterne", "textures/block/ardoise.png");

    private static final int SLATE = 0xFF14141A;

    public BeamerRenderer(BlockEntityRendererProvider.Context context) {
    }

    public static class State extends BlockEntityRenderState {
        public Direction facing = Direction.NORTH;
        public float reach = 8f;
        public float span = 5f;
        public boolean lit;
        public int light;
        public @Nullable Identifier film;
        public float aspect = 16f / 9f;
        public Feed.Shape shape = Feed.Shape.ENTIER;
        public int tint = 0xFFC857;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(BeamerEntity beamer, State state, float partialTicks,
            Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(beamer, state, partialTicks, cameraPosition,
                breakProgress);
        Feed feed = beamer.feed();
        state.facing = beamer.facing();
        state.reach = beamer.reach();
        state.span = beamer.span();
        state.shape = feed.shape();
        state.lit = !feed.idle() && !beamer.clock().paused();
        state.light = LightCoordsUtil.withBlock(state.lightCoords,
                Math.max(LightCoordsUtil.block(state.lightCoords), feed.brightness()));

        double distance = Math.sqrt(cameraPosition.distanceToSqr(
                Vec3.atCenterOf(beamer.getBlockPos())));
        Gaze.notice(beamer.getBlockPos(), distance);

        Film film = Gaze.film(beamer.getBlockPos());
        state.film = film == null ? null : film.id();
        if (film != null) {
            state.aspect = (float) film.width() / Math.max(1, film.height());
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState camera) {
        if (!state.lit) {
            return; // éteint : ni cône, ni image. Le bloc seul, comme n'importe quelle machine.
        }
        Direction facing = state.facing;
        Vector3f normal = vector(facing);
        Vector3f right = rightOf(facing);
        Vector3f up = new Vector3f(normal).cross(right, new Vector3f()).mul(-1f);

        // Le centre de l'image, à la portée réglée. On part du centre du bloc et non de sa face :
        // « huit blocs » veut dire huit blocs entre les deux blocs, ce que le joueur compte à l'œil.
        Vector3f lens = new Vector3f(0.5f, 0.5f, 0.5f).fma(0.5f, normal);
        Vector3f target = new Vector3f(0.5f, 0.5f, 0.5f).fma(state.reach, normal);

        float wide = state.span;
        float high = state.shape == Feed.Shape.ETIRE ? state.span * 9f / 16f
                : state.span / Math.max(0.1f, state.aspect);

        cone(state, poseStack, collector, lens, target, right, up, wide, high, normal);
        image(state, poseStack, collector, target, right, up, wide, high, normal);
    }

    /**
     * La pyramide tronquée, de l'objectif à l'image.
     *
     * <p>L'opacité décroît d'un bout à l'autre : pleine à l'objectif, presque nulle à l'arrivée. Le
     * dégradé est porté par la <b>couleur de sommet</b> et non par une texture, ce qui le rend
     * indépendant de la longueur du faisceau — un projecteur réglé à trente-deux blocs s'estompe
     * exactement comme un projecteur réglé à trois, ce qui est le comportement qu'on attend d'un
     * cône de lumière et non celui d'une texture étirée.
     */
    private static void cone(State state, PoseStack poseStack, SubmitNodeCollector collector,
            Vector3f lens, Vector3f target, Vector3f right, Vector3f up, float wide, float high,
            Vector3f normal) {
        int near = ARGB.color(HAZE, ARGB.red(state.tint), ARGB.green(state.tint),
                ARGB.blue(state.tint));
        int far = ARGB.color(0, ARGB.red(state.tint), ARGB.green(state.tint),
                ARGB.blue(state.tint));

        collector.submitCustomGeometry(poseStack, RenderTypes.beaconBeam(BEAM, true),
                (pose, buffer) -> {
                    float hw = wide / 2f;
                    float hh = high / 2f;
                    // Les quatre coins, à l'objectif puis à l'arrivée, dans le sens horaire vu de
                    // derrière le projecteur. L'ordre compte : inversé, les quatre faces seraient
                    // dos au spectateur et le cône serait invisible du bon côté.
                    Vector3f[] small = {
                            corner(lens, right, up, -LENS, -LENS),
                            corner(lens, right, up, LENS, -LENS),
                            corner(lens, right, up, LENS, LENS),
                            corner(lens, right, up, -LENS, LENS)};
                    Vector3f[] big = {
                            corner(target, right, up, -hw, -hh),
                            corner(target, right, up, hw, -hh),
                            corner(target, right, up, hw, hh),
                            corner(target, right, up, -hw, hh)};
                    for (int side = 0; side < 4; side++) {
                        int next = (side + 1) % 4;
                        vertex(pose, buffer, small[side], 0f, 0f, state.light, near, normal);
                        vertex(pose, buffer, small[next], 1f, 0f, state.light, near, normal);
                        vertex(pose, buffer, big[next], 1f, 1f, state.light, far, normal);
                        vertex(pose, buffer, big[side], 0f, 1f, state.light, far, normal);
                    }
                });
    }

    /** L'image, ou l'ardoise qui dit pourquoi il n'y en a pas. */
    private static void image(State state, PoseStack poseStack, SubmitNodeCollector collector,
            Vector3f target, Vector3f right, Vector3f up, float wide, float high, Vector3f normal) {
        float hw = wide / 2f;
        float hh = high / 2f;
        Vector3f corner = corner(target, right, up, -hw, -hh);
        boolean hasFilm = state.film != null;
        Identifier texture = hasFilm ? state.film : SLATE_TEXTURE;
        int colour = hasFilm ? -1 : SLATE;
        // Émissive : l'image projetée est de la lumière, et l'éclairer par la pièce reviendrait à
        // l'éteindre dans le noir — exactement là où l'on projette.
        collector.submitCustomGeometry(poseStack,
                RenderTypes.entityTranslucentEmissive(texture),
                (pose, buffer) -> {
                    vertex(pose, buffer, corner(corner, right, up, 0f, 0f), 0f, 1f,
                            LightCoordsUtil.FULL_BRIGHT, colour, normal);
                    vertex(pose, buffer, corner(corner, right, up, wide, 0f), 1f, 1f,
                            LightCoordsUtil.FULL_BRIGHT, colour, normal);
                    vertex(pose, buffer, corner(corner, right, up, wide, high), 1f, 0f,
                            LightCoordsUtil.FULL_BRIGHT, colour, normal);
                    vertex(pose, buffer, corner(corner, right, up, 0f, high), 0f, 0f,
                            LightCoordsUtil.FULL_BRIGHT, colour, normal);
                });
    }

    private static Vector3f corner(Vector3f centre, Vector3f right, Vector3f up, float x, float y) {
        return new Vector3f(centre).fma(x, right).fma(y, up);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, Vector3f at, float u,
            float v, int light, int colour, Vector3f normal) {
        buffer.addVertex(pose, at.x(), at.y(), at.z())
                .setColor(colour)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, normal.x(), normal.y(), normal.z());
    }

    /**
     * Un axe horizontal perpendiculaire à la visée.
     *
     * <p>{@code getCounterClockWise} n'est défini que pour les quatre directions horizontales : un
     * projecteur au plafond n'a pas de « sens horaire » sur son axe. On lui en donne un arbitraire —
     * l'est — et c'est sans conséquence : l'image tourne alors avec le bloc, et un joueur qui veut
     * l'incliner tourne simplement le projecteur.
     */
    private static Vector3f rightOf(Direction facing) {
        return facing.getAxis().isVertical()
                ? vector(Direction.EAST)
                : vector(facing.getCounterClockWise());
    }

    private static Vector3f vector(Direction direction) {
        return new Vector3f(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    /** L'emprise du faisceau. Voir {@code BeamerEntity.beamBox} pour ce qu'elle évite. */
    @Override
    public AABB getRenderBoundingBox(BeamerEntity beamer) {
        return beamer.beamBox();
    }

    /**
     * Visible au-delà de la distance ordinaire des bloc-entités.
     *
     * <p>Un projecteur réglé à trente-deux blocs doit encore être dessiné quand on regarde son image
     * de l'autre bout : sinon le bloc sort du champ, le rendu s'arrête, et l'image disparaît alors
     * qu'elle est en plein milieu de l'écran.
     */
    @Override
    public int getViewDistance() {
        return 128;
    }
}
