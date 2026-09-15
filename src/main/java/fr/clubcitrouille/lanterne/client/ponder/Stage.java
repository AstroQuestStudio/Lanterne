package fr.clubcitrouille.lanterne.client.ponder;

import org.joml.Matrix3x2f;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/**
 * Le plateau : une vue en trois dimensions dessinée avec les outils d'une interface en deux.
 *
 * <h2>Le problème, et pourquoi la réponse évidente est la mauvaise</h2>
 *
 * <p>La réponse évidente est celle de Ponder, le guide animé de Create : fabriquer un faux niveau,
 * y poser une structure, et le rendre avec le moteur du jeu. Elle est bonne — sur Minecraft 1.21.1,
 * pour laquelle elle est écrite.
 *
 * <p>En 26.2 elle ne compile pas. Le rendu a été refait : {@code MultiBufferSource} a disparu,
 * {@code BakedModel} n'existe plus, {@code ModelBlockRenderer.tesselateBlock} non plus,
 * {@code RenderSystem.setProjectionMatrix} non plus, les blocs-entités passent par un système de
 * soumission neuf. Porter cet étage-là, ce serait le réécrire en entier, contre des interfaces
 * fraîches, <b>sans jamais pouvoir lancer le client pour regarder</b>. C'est un pari qu'on perd.
 *
 * <h2>Ce qu'on fait à la place, et pourquoi c'est mieux ici</h2>
 *
 * <p>Un bloc dessiné dans une interface, c'est exactement ce que fait déjà l'inventaire du jeu, et
 * {@link GuiGraphicsExtractor#item} le fait en une ligne. Mieux : en 26.2, ce rendu est <b>mis en
 * cache dans un atlas de textures</b> — le modèle n'est calculé qu'une fois, puis chaque apparition
 * n'est plus qu'un quad texturé. Cent blocs à l'écran coûtent cent quads tirés d'une seule texture,
 * là où le faux monde de Ponder retransforme chaque sommet sur le processeur à chaque image.
 *
 * <p>Pour un mod dont le propos est la performance, ce n'était pas un compromis : c'était la bonne
 * réponse, et le fait qu'elle soit aussi la plus simple à écrire est une chance.
 *
 * <h2>Ce que cela coûte : l'angle n'est pas négociable</h2>
 *
 * <p>Le rendu d'objet impose l'angle de l'inventaire — {@code rotation [30, 225, 0]}, lisible dans
 * {@code assets/minecraft/models/block/block.json}. On ne peut donc pas <b>tourner</b> autour de la
 * scène. On peut la parcourir et s'en approcher, ce qui est continu et suffit ; on ne peut pas en
 * faire le tour.
 *
 * <p>C'est la seule chose que Ponder sait faire et pas nous. Elle est écrite ici plutôt que
 * découverte plus tard.
 *
 * <h2>La projection, et d'où sortent ces nombres</h2>
 *
 * <p>Ils ne sont pas tâtonnés, ils se déduisent. Le modèle est tourné de 30° autour de X puis de 225°
 * autour de Y, mis à l'échelle 0,625, et logé dans une case de seize pixels. Un pas d'un bloc vaut
 * donc 16 × 0,625 = 10 pixels dans l'espace tourné, et il ne reste qu'à lire les colonnes de la
 * matrice {@code Rx(30) · Ry(225)} :
 *
 * <pre>
 * un pas vers l'est  (+X) -> (-7,071 ; +3,536) pixels
 * un pas vers le haut(+Y) -> ( 0     ; -8,660)
 * un pas vers le sud (+Z) -> (-7,071 ; -3,536)
 * </pre>
 *
 * <p>Vérification : un cube occupe alors 14,1 pixels de large et 15,7 de haut. Il tient dans sa case
 * de seize sans la remplir tout à fait, ce qui est exactement l'aspect d'un bloc dans l'inventaire.
 * Si ces nombres étaient faux, les blocs ne joindraient pas.
 *
 * <p>La troisième ligne de la même matrice donne la <b>profondeur</b>, dont on ne se sert que pour
 * ordonner le dessin. Elle place l'œil au nord-est et en hauteur : on voit le dessus, la face est et
 * la face nord. C'est bien ce qu'on observe dans l'inventaire, où la façade d'un fourneau — orienté
 * au nord par défaut — est visible, et sur la droite.
 *
 * <h2>Le peintre, et pourquoi il suffit</h2>
 *
 * <p>Il n'y a pas de tampon de profondeur ici : le dessin le plus lointain doit partir en premier.
 * C'est possible parce que le rendu d'interface de 26.2 <b>empile automatiquement</b> tout élément
 * qui en recouvre un autre au-dessus de lui — voir {@code GuiRenderState.addItem}. L'ordre de
 * soumission fait donc l'ordre de superposition, ce qui est précisément ce qu'on demande au peintre.
 */
public final class Stage {
    /** Un pas d'un bloc vers l'est, en pixels d'interface, à l'échelle un. */
    private static final float EAST_X = -7.0710678f;
    private static final float EAST_Y = 3.5355339f;
    /** Un pas d'un bloc vers le haut. Il n'a pas de composante horizontale : l'axe Y reste vertical. */
    private static final float UP_Y = -8.6602540f;
    /** Un pas d'un bloc vers le sud. */
    private static final float SOUTH_X = -7.0710678f;
    private static final float SOUTH_Y = -3.5355339f;

    /** La profondeur d'un pas : plus grande, plus près de l'œil. Troisième ligne de la matrice. */
    private static final float DEEP_EAST = 0.6123724f;
    private static final float DEEP_UP = 0.5f;
    private static final float DEEP_SOUTH = -0.6123724f;

    private float focusX;
    private float focusY;
    private float focusZ;
    private float zoom = 1f;
    private float centreX;
    private float centreY;

    /** Le point de l'écran où atterrit le point visé. À régler une fois, à l'ouverture du hublot. */
    public void centre(float x, float y) {
        this.centreX = x;
        this.centreY = y;
    }

    /** Le point du monde qu'on regarde, et de combien on s'en approche. */
    public void aim(float x, float y, float z, float zoom) {
        this.focusX = x;
        this.focusY = y;
        this.focusZ = z;
        this.zoom = zoom;
    }

    public float zoom() {
        return this.zoom;
    }

    /** L'abscisse écran d'un point du monde. */
    public float screenX(double x, double y, double z) {
        return this.centreX + this.zoom
                * (float) ((x - this.focusX) * EAST_X + (z - this.focusZ) * SOUTH_X);
    }

    /** L'ordonnée écran d'un point du monde. */
    public float screenY(double x, double y, double z) {
        return this.centreY + this.zoom * (float) ((x - this.focusX) * EAST_Y
                + (y - this.focusY) * UP_Y + (z - this.focusZ) * SOUTH_Y);
    }

    /**
     * La profondeur d'un point : plus grande, plus près de l'œil.
     *
     * <p>Ne dépend pas de la caméra — elle ne bouge pas — donc se calcule une fois pour toutes à la
     * construction de la scène plutôt qu'à chaque image. C'est ce qui permet de trier les figurants
     * une seule fois et de n'avoir plus rien à trier ensuite.
     */
    public static float depth(double x, double y, double z) {
        return (float) (x * DEEP_EAST + y * DEEP_UP + z * DEEP_SOUTH);
    }

    /**
     * Un bloc, dessiné centré sur sa case.
     *
     * <p>{@code fakeItem} plutôt que {@code item} : la seule différence est qu'il ne transmet pas le
     * joueur, dont certains modèles se servent. Ici il n'y a pas de porteur, et exiger un joueur
     * ferait tomber l'écran sur un client sans monde chargé.
     *
     * @param size 1 pour un bloc plein ; moins pour un objet qu'on tient ou qui vole
     * @param lift décalage vertical en blocs, positif vers le haut — le temps d'une chute
     */
    public void block(GuiGraphicsExtractor graphics, ItemStack look,
                      double x, double y, double z, float size, float lift) {
        if (look.isEmpty() || size <= 0.01f) {
            return;
        }
        float px = screenX(x, y + lift, z);
        float py = screenY(x, y + lift, z);
        float k = this.zoom * size;
        graphics.pose().pushMatrix();
        graphics.pose().translate(px, py);
        graphics.pose().scale(k, k);
        // La case du rendu d'objet fait seize pixels et le modèle y est centré : reculer de huit met
        // donc le centre du bloc exactement sur le point visé, quelle que soit l'échelle.
        graphics.fakeItem(look, -8, -8);
        graphics.pose().popMatrix();
    }

    /**
     * Une face verticale d'une case, dessinée comme un parallélogramme.
     *
     * <p>Sert à ce qui n'a pas d'objet : la nappe d'un portail, par exemple, est un bloc incassable
     * et sans butin — {@code GateBlock.getCloneItemStack} refuse d'en donner un exemplaire même en
     * créatif. Il n'existe donc aucune pile à dessiner, et il en faut une autre représentation.
     *
     * <h2>Comment on obtient un parallélogramme avec un {@code fill}</h2>
     *
     * <p>{@code fill} dessine un rectangle dont les <b>quatre coins</b> passent chacun par la matrice
     * du dessin — voir {@code ColoredRectangleRenderState.buildVertices}, qui appelle
     * {@code addVertexWith2DPose} sommet par sommet. Une matrice de cisaillement transforme donc ce
     * rectangle en n'importe quel parallélogramme, et il suffit de lui donner pour colonnes les deux
     * arêtes de la face qu'on veut.
     *
     * @param alongX vrai si la face est dans le plan est-ouest, faux si elle est dans le plan nord-sud
     * @param low    couleur au bas de la face, {@code 0xAARRGGBB}
     * @param high   couleur en haut
     */
    public void pane(GuiGraphicsExtractor graphics, double x, double y, double z,
                     boolean alongX, int low, int high) {
        // Le coin bas-ouest de la case, décalé d'un demi-bloc pour se placer au milieu de son
        // épaisseur : une nappe vit au centre de sa case, pas sur sa paroi.
        double ox = alongX ? x - 0.5d : x;
        double oz = alongX ? z : z - 0.5d;
        float originX = screenX(ox, y - 0.5d, oz);
        float originY = screenY(ox, y - 0.5d, oz);

        float sideX = this.zoom * (alongX ? EAST_X : SOUTH_X);
        float sideY = this.zoom * (alongX ? EAST_Y : SOUTH_Y);
        float upY = this.zoom * UP_Y;

        // Colonnes de la matrice : l'image de (1,0) puis celle de (0,1), divisées par seize parce
        // que le rectangle local en fait seize de côté — ce qui laisse de la place pour des marges
        // entières si un jour on veut border la face.
        Matrix3x2f shear = new Matrix3x2f(
                sideX / 16f, sideY / 16f,
                0f, upY / 16f,
                originX, originY);

        graphics.pose().pushMatrix();
        graphics.pose().mul(shear);
        graphics.fillGradient(0, 0, 16, 16, low, high);
        graphics.pose().popMatrix();
    }

    /**
     * Un segment entre deux points du monde.
     *
     * <p>Un rectangle fin qu'on fait tourner, comme Ponder fait ses arêtes avec de minces pavés
     * plutôt qu'avec des lignes : la largeur de trait matérielle n'est pas la même d'une carte
     * graphique à l'autre, et un contour d'un pixel chez l'un en fait trois chez l'autre.
     *
     * @param thickness épaisseur en pixels d'écran — donc constante, elle ne grossit pas au zoom
     */
    public void edge(GuiGraphicsExtractor graphics,
                     double x1, double y1, double z1, double x2, double y2, double z2,
                     int colour, float thickness) {
        float ax = screenX(x1, y1, z1);
        float ay = screenY(x1, y1, z1);
        float dx = screenX(x2, y2, z2) - ax;
        float dy = screenY(x2, y2, z2) - ay;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 0.5f) {
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(ax, ay);
        graphics.pose().rotate((float) Math.atan2(dy, dx));
        // L'échelle est volontairement non uniforme : le segment s'allonge, il ne s'épaissit pas.
        graphics.pose().scale(length / 16f, Math.max(0.4f, thickness * 0.5f));
        graphics.fill(0, -1, 16, 1, colour);
        graphics.pose().popMatrix();
    }

    /** Les douze arêtes d'une case, en coordonnées locales de 0 à 1. */
    private static final int[][] EDGES = {
        {0, 0, 0, 1, 0, 0}, {0, 1, 0, 1, 1, 0}, {0, 0, 1, 1, 0, 1}, {0, 1, 1, 1, 1, 1},
        {0, 0, 0, 0, 1, 0}, {1, 0, 0, 1, 1, 0}, {0, 0, 1, 0, 1, 1}, {1, 0, 1, 1, 1, 1},
        {0, 0, 0, 0, 0, 1}, {1, 0, 0, 1, 0, 1}, {0, 1, 0, 0, 1, 1}, {1, 1, 0, 1, 1, 1},
    };

    /**
     * Le contour d'une case : neuf arêtes sur douze.
     *
     * <p>Les trois qui manquent sont celles du coin le plus éloigné de l'œil — le coin
     * {@code (min X, min Y, max Z)}, qu'on lit directement dans les signes de {@link #depth}. Les
     * dessiner donnerait une cage grillagée, qu'on lit mal parce qu'elle montre autant l'arrière que
     * l'avant ; les taire donne une silhouette, qu'on lit d'un coup d'œil.
     */
    public void cage(GuiGraphicsExtractor graphics, int x, int y, int z,
                     int colour, float thickness) {
        for (int[] e : EDGES) {
            if (hidden(e[0], e[1], e[2]) || hidden(e[3], e[4], e[5])) {
                continue;
            }
            edge(graphics, x - 0.5d + e[0], y - 0.5d + e[1], z - 0.5d + e[2],
                    x - 0.5d + e[3], y - 0.5d + e[4], z - 0.5d + e[5], colour, thickness);
        }
    }

    private static boolean hidden(int i, int j, int k) {
        return i == 0 && j == 0 && k == 1;
    }

    /** Une pastille carrée, centrée sur un point du monde. Pour les étincelles. */
    public void dot(GuiGraphicsExtractor graphics, double x, double y, double z,
                    float size, int colour) {
        int half = Math.max(1, Math.round(size * 0.5f));
        float px = screenX(x, y, z);
        float py = screenY(x, y, z);
        graphics.pose().pushMatrix();
        graphics.pose().translate(px, py);
        graphics.fill(-half, -half, half, half, colour);
        graphics.pose().popMatrix();
    }
}
