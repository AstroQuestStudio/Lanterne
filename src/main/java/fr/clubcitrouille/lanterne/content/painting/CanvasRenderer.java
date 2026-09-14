package fr.clubcitrouille.lanterne.content.painting;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Le dessin d'une toile.
 *
 * <h2>Ce que ce fichier ne fait pas, et c'est l'essentiel</h2>
 *
 * <p>Il ne décode aucune image, n'en redimensionne aucune, ne choisit aucun niveau de détail,
 * n'interroge aucune table de textures, n'alloue rien. Tout cela a été fait une fois, ailleurs, à
 * l'import ou à l'arrivée de l'image. Ce qu'il reste tient en une multiplication de matrice, une
 * poignée d'additions et l'écriture de quelques sommets.
 *
 * <p>C'est la différence de fond avec le mod dont ce paquet reprend l'idée, qui calcule à chaque
 * image et pour chaque tableau une densité de pixels à partir de la distance et du champ de vision —
 * tangente comprise — pour choisir laquelle de ses cinq textures utiliser. Ce travail est ici confié
 * aux mipmaps de la mosaïque, c'est-à-dire au matériel, qui le fait gratuitement et mieux.
 *
 * <h2>Un seul type de rendu pour tous les tableaux</h2>
 *
 * <p>{@code RenderTypes.entityCutout} est mémoïsé par identifiant de texture : appelé mille fois
 * avec {@link Mosaic#ID}, il rend mille fois le même objet. Tous les tableaux du monde soumettent
 * donc leur géométrie dans le <b>même</b> lot, et la carte graphique ne change de texture qu'une
 * seule fois pour tous. Voir {@link Mosaic} pour ce que cela évite.
 *
 * <p>Le dos et les tranches puisent dans une case de la mosaïque, elle aussi — une petite zone unie.
 * Ils auraient pu utiliser une couleur de sommet sur une texture blanche, mais cela aurait demandé
 * une <em>seconde</em> texture, donc un second lot, et aurait annulé sur le dos ce que l'on gagne
 * sur la face.
 */
@OnlyIn(Dist.CLIENT)
public class CanvasRenderer extends EntityRenderer<Canvas, CanvasState> {
    /** Demi-épaisseur, reprise de la toile. */
    private static final float DEPTH = Canvas.HALF_DEPTH;

    /**
     * De combien le cadre avance sur la face qu'il borde.
     *
     * <p>Un demi-millième de bloc — un huitième de pixel. Assez pour que la carte graphique sache
     * laquelle des deux surfaces est devant, assez peu pour qu'aucun relief ne se voie. Si un
     * clignotement apparaissait entre le cadre et l'image, c'est ce nombre qu'il faudrait doubler.
     */
    private static final float LIP = 0.0005f;

    public CanvasRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    /**
     * Branche le rendu, la remontée d'images et l'oubli de fin de partie.
     *
     * <p>Appelée uniquement côté client : elle touche à la mosaïque et au magot, qu'un serveur dédié
     * ne doit jamais charger.
     */
    public static void register(IEventBus modBus) {
        // Les mains d'abord : c'est par elles que tout paquet descendant sera traite, et une classe
        // cliente ne doit etre nommee que depuis ici, derriere la garde de distribution.
        Hands.install();
        modBus.addListener(CanvasRenderer::onRegisterRenderers);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event)
                        -> Hoard.forget());
        // La remontée d'une image apportée par le joueur, quelques tranches par tick. Quand rien
        // ne monte — c'est-à-dire presque toujours — cet appel est un test de nullité.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> Hoard.pump());
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Easel.CANVAS.get(), CanvasRenderer::new);
    }

    @Override
    public CanvasState createRenderState() {
        return new CanvasState();
    }

    /**
     * Lit le monde une fois par image et par toile.
     *
     * <p>La recherche de la case dans la mosaïque est une consultation de table de hachage sur une
     * chaîne de trente-deux caractères. On pourrait la mettre en cache dans l'entité ; on ne le fait
     * pas, parce qu'une case peut apparaître <em>entre deux images</em> — c'est exactement ce qui se
     * passe quand une image finit d'arriver — et qu'un cache ferait alors attendre le tableau
     * jusqu'à un évènement qui n'existe pas.
     */
    @Override
    public void extractRenderState(Canvas entity, CanvasState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        state.direction = entity.getDirection();
        state.width = entity.blockWidth();
        state.height = entity.blockHeight();
        state.trim = entity.trim();
        state.cell = Mosaic.cell(entity.hash());
        state.back = Mosaic.back();

        int blocks = state.width * state.height;
        if (state.light.length != blocks) {
            state.light = new int[blocks];
        }
        Level level = entity.level();
        Direction direction = state.direction;
        float leftX = -state.width / 2.0f;
        float bottomY = -state.height / 2.0f;
        for (int row = 0; row < state.height; row++) {
            for (int column = 0; column < state.width; column++) {
                float alongX = column + leftX + 0.5f;
                float alongY = row + bottomY + 0.5f;
                int x = entity.getBlockX();
                int y = Mth.floor(entity.getY() + alongY);
                int z = entity.getBlockZ();
                // L'axe « le long du tableau » n'est pas le même selon le mur : on parcourt la
                // largeur vers l'ouest sur un mur nord, vers le nord sur un mur ouest, et ainsi de
                // suite. Les signes suivent la même convention que vanilla, faute de quoi la lumière
                // serait lue en miroir de l'image.
                switch (direction) {
                    case NORTH -> x = Mth.floor(entity.getX() + alongX);
                    case SOUTH -> x = Mth.floor(entity.getX() - alongX);
                    case WEST -> z = Mth.floor(entity.getZ() - alongX);
                    case EAST -> z = Mth.floor(entity.getZ() + alongX);
                    default -> { }
                }
                state.light[column + row * state.width] =
                        LevelRenderer.getLightCoords(level, new BlockPos(x, y, z));
            }
        }
    }

    @Override
    public void submit(CanvasState state, PoseStack poseStack,
            SubmitNodeCollector collector, CameraRenderState camera) {
        Mosaic.Cell back = state.back;
        if (back == null) {
            return;
        }
        poseStack.pushPose();
        // Le tableau est modélisé face au sud, comme celui de vanilla ; on le tourne vers son mur.
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - state.direction.get2DDataValue() * 90));
        RenderType type = RenderTypes.entityCutout(Mosaic.ID);
        // La case de l'image, ou celle du dos si elle n'est pas encore arrivée : un tableau en
        // attente est une plaque sombre, jamais un trou.
        Mosaic.Cell front = state.cell != null ? state.cell : back;
        collector.submitCustomGeometry(poseStack, type,
                (pose, buffer) -> weave(pose, buffer, state, front, back));
        poseStack.popPose();
        super.submit(state, poseStack, collector, camera);
    }

    /**
     * Tisse la géométrie : une face avant et une face arrière par bloc, et des tranches au bord.
     *
     * <h2>Pourquoi découper par bloc plutôt qu'un seul grand rectangle</h2>
     *
     * <p>Uniquement pour la lumière. Minecraft éclaire par sommet, et un rectangle de huit blocs
     * n'aurait que quatre sommets : la lumière y serait interpolée en diagonale d'un coin à l'autre,
     * et une fresque à cheval sur une pièce éclairée et un couloir noir aurait un dégradé faux.
     * Découper par bloc donne un sommet par jointure, donc un éclairage juste.
     *
     * <p>Le prix est de quatre sommets par bloc et par face. Pour un tableau de huit sur huit, le
     * maximum autorisé par défaut, cela fait cinq cent douze sommets — de quoi occuper une carte
     * graphique moderne pendant environ rien.
     */
    private static void weave(PoseStack.Pose pose, VertexConsumer buffer, CanvasState state,
            Mosaic.Cell front, Mosaic.Cell back) {
        int width = state.width;
        int height = state.height;
        float leftX = -width / 2.0f;
        float bottomY = -height / 2.0f;
        Trim trim = state.trim;
        // Le décalage de la face avant dépend de l'encadrement : une toile encadrée avance d'un
        // pixel entier, un motif sans cadre d'un quart seulement. Voir Trim.skin().
        float skin = -trim.skin();

        // Le dos et les tranches piochent dans un coin de la case unie, loin de ses bords : la case
        // fait seize pixels, le filtrage linéaire en mord un demi, et viser le centre met à l'abri.
        float bu = (back.u0() + back.u1()) * 0.5f;
        float bv = (back.v0() + back.v1()) * 0.5f;

        for (int column = 0; column < width; column++) {
            for (int row = 0; row < height; row++) {
                float x0 = leftX + column + 1;
                float x1 = leftX + column;
                float y0 = bottomY + row + 1;
                float y1 = bottomY + row;
                int light = state.light[column + row * width];

                // Les coordonnées de texture décroissent quand x croît : après la rotation, l'axe
                // des x local pointe vers la gauche du spectateur.
                float u0 = lerp(front.u0(), front.u1(), (width - column) / (float) width);
                float u1 = lerp(front.u0(), front.u1(), (width - column - 1) / (float) width);
                float v0 = lerp(front.v0(), front.v1(), (height - row) / (float) height);
                float v1 = lerp(front.v0(), front.v1(), (height - row - 1) / (float) height);

                // Face avant. La normale pointe vers -Z, c'est-à-dire vers le spectateur une fois la
                // toile tournée vers son mur : c'est le sens qui fait que l'éclairage directionnel
                // l'allume au lieu de l'éteindre.
                vertex(pose, buffer, x0, y1, skin, u1, v0, 0, 0, -1, light, -1);
                vertex(pose, buffer, x1, y1, skin, u0, v0, 0, 0, -1, light, -1);
                vertex(pose, buffer, x1, y0, skin, u0, v1, 0, 0, -1, light, -1);
                vertex(pose, buffer, x0, y0, skin, u1, v1, 0, 0, -1, light, -1);

                if (!trim.framed()) {
                    // Sans cadre, il n'y a ni dos ni tranches : le motif est peint SUR le mur, et
                    // rien ne dépasse. C'est ce qui le distingue vraiment d'un cadre de largeur
                    // nulle, qui n'aurait été qu'un tableau sans bordure.
                    continue;
                }

                // Face arrière.
                vertex(pose, buffer, x0, y0, DEPTH, bu, bv, 0, 0, 1, light, -1);
                vertex(pose, buffer, x1, y0, DEPTH, bu, bv, 0, 0, 1, light, -1);
                vertex(pose, buffer, x1, y1, DEPTH, bu, bv, 0, 0, 1, light, -1);
                vertex(pose, buffer, x0, y1, DEPTH, bu, bv, 0, 0, 1, light, -1);

                if (row == height - 1) {
                    vertex(pose, buffer, x0, y0, skin, bu, bv, 0, 1, 0, light, -1);
                    vertex(pose, buffer, x1, y0, skin, bu, bv, 0, 1, 0, light, -1);
                    vertex(pose, buffer, x1, y0, DEPTH, bu, bv, 0, 1, 0, light, -1);
                    vertex(pose, buffer, x0, y0, DEPTH, bu, bv, 0, 1, 0, light, -1);
                }
                if (row == 0) {
                    vertex(pose, buffer, x0, y1, DEPTH, bu, bv, 0, -1, 0, light, -1);
                    vertex(pose, buffer, x1, y1, DEPTH, bu, bv, 0, -1, 0, light, -1);
                    vertex(pose, buffer, x1, y1, skin, bu, bv, 0, -1, 0, light, -1);
                    vertex(pose, buffer, x0, y1, skin, bu, bv, 0, -1, 0, light, -1);
                }
                if (column == width - 1) {
                    vertex(pose, buffer, x0, y0, DEPTH, bu, bv, -1, 0, 0, light, -1);
                    vertex(pose, buffer, x0, y1, DEPTH, bu, bv, -1, 0, 0, light, -1);
                    vertex(pose, buffer, x0, y1, skin, bu, bv, -1, 0, 0, light, -1);
                    vertex(pose, buffer, x0, y0, skin, bu, bv, -1, 0, 0, light, -1);
                }
                if (column == 0) {
                    vertex(pose, buffer, x1, y0, skin, bu, bv, 1, 0, 0, light, -1);
                    vertex(pose, buffer, x1, y1, skin, bu, bv, 1, 0, 0, light, -1);
                    vertex(pose, buffer, x1, y1, DEPTH, bu, bv, 1, 0, 0, light, -1);
                    vertex(pose, buffer, x1, y0, DEPTH, bu, bv, 1, 0, 0, light, -1);
                }
            }
        }

        if (trim.framed()) {
            border(pose, buffer, state, back, bu, bv);
        }
    }

    /**
     * Les quatre bandes du cadre, posées par-dessus le bord de l'image.
     *
     * <h2>Par-dessus, et non autour</h2>
     *
     * <p>Un cadre <em>autour</em> de l'image supposerait de rétrécir l'image pour lui faire de la
     * place, donc de changer les coordonnées de texture des blocs du bord, donc de traiter le bord
     * différemment du centre dans une boucle déjà chargée. Un cadre <b>par-dessus</b> ne demande que
     * quatre rectangles, et c'est aussi ce que fait un vrai cadre : il mord sur la toile.
     *
     * <p>Les bandes sont légèrement en avant de la face — {@link #LIP} — pour que le tampon de
     * profondeur n'ait pas à départager deux plans confondus. C'est le même souci qu'entre un motif
     * sans cadre et son mur, à une échelle plus petite.
     *
     * <h2>La couleur vient du sommet, pas d'une texture</h2>
     *
     * <p>Les quatre bandes puisent dans la même case unie que le dos, et c'est la couleur de sommet
     * qui les distingue. Tous les cadres restent donc dans le lot de dessin unique de la mosaïque —
     * une texture de cadre l'aurait brisé, et c'est précisément ce que ce paquet reproche au mod dont
     * il reprend l'idée.
     *
     * <p>L'éclairage retenu est celui du coin bas-gauche, pour les quatre bandes. Un cadre est une
     * pièce rigide d'une seule matière : lui donner un dégradé par bloc l'aurait fait ressembler à
     * quatre planches mal assorties.
     */
    private static void border(PoseStack.Pose pose, VertexConsumer buffer, CanvasState state,
            Mosaic.Cell back, float bu, float bv) {
        float half = state.width / 2.0f;
        float halfY = state.height / 2.0f;
        float band = Math.min(state.trim.width(), Math.min(half, halfY) * 0.6f);
        float z = -state.trim.skin() - LIP;
        int colour = state.trim.colour();
        int light = state.light[0];

        // Bas et haut, sur toute la largeur.
        quad(pose, buffer, -half, half, -halfY, -halfY + band, z, bu, bv, light, colour);
        quad(pose, buffer, -half, half, halfY - band, halfY, z, bu, bv, light, colour);
        // Gauche et droite, entre les deux précédentes pour ne pas doubler les coins.
        quad(pose, buffer, -half, -half + band, -halfY + band, halfY - band, z, bu, bv, light, colour);
        quad(pose, buffer, half - band, half, -halfY + band, halfY - band, z, bu, bv, light, colour);
    }

    /** Un rectangle plat face au spectateur, d'une seule couleur. */
    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, float x0, float x1,
            float y0, float y1, float z, float u, float v, int light, int colour) {
        vertex(pose, buffer, x1, y0, z, u, v, 0, 0, -1, light, colour);
        vertex(pose, buffer, x0, y0, z, u, v, 0, 0, -1, light, colour);
        vertex(pose, buffer, x0, y1, z, u, v, 0, 0, -1, light, colour);
        vertex(pose, buffer, x1, y1, z, u, v, 0, 0, -1, light, colour);
    }

    private static float lerp(float from, float to, float fraction) {
        return from + (to - from) * fraction;
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z,
            float u, float v, int nx, int ny, int nz, int light, int colour) {
        buffer.addVertex(pose, x, y, z)
                .setColor(colour)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
