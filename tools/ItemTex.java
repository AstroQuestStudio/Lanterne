import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Les deux objets du carnet : le carnet lui-même, et la boussole d'ancre.
 *
 * <p>Seize sur seize, contour sombre, palette restreinte : la grammaire des objets de Minecraft. Un
 * objet se lit à la taille d'une case d'inventaire, c'est-à-dire minuscule — d'où le contour, qui
 * n'est pas un ornement mais ce qui le détache du fond.
 *
 * <p>Lancer : {@code java tools/ItemTex.java <dossier-de-sortie>}
 */
public final class ItemTex {
    private static final int EDGE = 0xFF16161A;

    // Cuir du carnet, du sombre au clair.
    private static final int[] LEATHER = {0xFF5A2F1B, 0xFF7A4426, 0xFF9A5C34, 0xFFB87546};
    // Pages.
    private static final int[] PAGE = {0xFFD8CDB4, 0xFFEDE4CF, 0xFFFFF8E8};
    // Ambre du mod.
    private static final int AMBER = 0xFFFFC857;
    private static final int AMBER_DEEP = 0xFFDE7A1C;

    // Acier de la boussole.
    private static final int[] STEEL = {0xFF2A2A32, 0xFF41414C, 0xFF6B6B77, 0xFF8D8D99};

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : ".");
        out.mkdirs();
        ImageIO.write(carnet(), "png", new File(out, "carnet.png"));
        ImageIO.write(compass(), "png", new File(out, "boussole_ancre.png"));
        System.out.println("Deux objets écrits dans " + out.getAbsolutePath());
    }

    /**
     * Le carnet : un livre de cuir, tranche de pages visible, signet d'ambre.
     *
     * <p>Le signet est ce qui le distingue d'un livre vanilla dans une case d'inventaire. Sans lui,
     * deux objets de seize pixels se ressembleraient trop pour qu'on les sépare d'un coup d'œil.
     */
    private static BufferedImage carnet() {
        BufferedImage img = blank();

        // Couverture, de (3,1) à (12,14).
        for (int x = 3; x <= 12; x++) {
            for (int y = 1; y <= 14; y++) {
                boolean border = x == 3 || x == 12 || y == 1 || y == 14;
                // Un damier — (x + y) % 2 — ferait un motif net et régulier à seize pixels, pas du
                // grain de cuir. Une teinte unie, éclaircie vers le haut où la lumière tombe.
                img.setRGB(x, y, border ? EDGE : (y <= 5 ? LEATHER[2] : LEATHER[1]));
            }
        }
        // Le dos, à gauche : plus sombre, c'est là que la lumière ne va pas.
        for (int y = 2; y <= 13; y++) {
            img.setRGB(4, y, LEATHER[0]);
            img.setRGB(5, y, LEATHER[1]);
        }
        // La tranche de pages, à droite.
        for (int y = 3; y <= 12; y++) {
            img.setRGB(10, y, PAGE[0]);
            img.setRGB(11, y, PAGE[1 + (y % 2)]);
        }
        // Le signet d'ambre, qui dépasse en bas.
        img.setRGB(8, 10, AMBER);
        img.setRGB(8, 11, AMBER);
        img.setRGB(8, 12, AMBER);
        img.setRGB(8, 13, AMBER_DEEP);
        img.setRGB(8, 14, AMBER_DEEP);
        img.setRGB(8, 15, AMBER_DEEP);
        // Un fermoir, pour que la couverture ne soit pas une plaque unie.
        img.setRGB(6, 6, LEATHER[3]);
        img.setRGB(7, 6, LEATHER[3]);
        img.setRGB(6, 7, LEATHER[2]);
        img.setRGB(7, 7, LEATHER[2]);
        return img;
    }

    /**
     * La boussole d'ancre : un cadran d'acier, une aiguille en forme d'ancre.
     *
     * <p>L'aiguille ne tourne pas — le modèle d'objet de 26.1 saurait le faire, mais une aiguille qui
     * tourne demande un état synchronisé par objet et par joueur. Celle-ci est un dessin ; c'est le
     * clic droit qui dit la direction, et il la dit en toutes lettres.
     */
    private static BufferedImage compass() {
        BufferedImage img = blank();

        for (int x = 2; x <= 13; x++) {
            for (int y = 2; y <= 13; y++) {
                double d = Math.hypot(x - 7.5, y - 7.5);
                if (d > 6.0) {
                    continue;
                }
                if (d > 4.6) {
                    img.setRGB(x, y, d > 5.4 ? EDGE : STEEL[1]);
                } else {
                    img.setRGB(x, y, STEEL[0]);
                }
            }
        }
        // Quatre repères cardinaux, en acier clair.
        img.setRGB(7, 3, STEEL[3]);
        img.setRGB(8, 3, STEEL[3]);
        img.setRGB(7, 12, STEEL[2]);
        img.setRGB(8, 12, STEEL[2]);
        img.setRGB(3, 7, STEEL[2]);
        img.setRGB(3, 8, STEEL[2]);
        img.setRGB(12, 7, STEEL[2]);
        img.setRGB(12, 8, STEEL[2]);

        // L'ancre, en ambre : une tige, un jas, deux bras.
        for (int y = 5; y <= 10; y++) {
            img.setRGB(7, y, AMBER);
            img.setRGB(8, y, AMBER_DEEP);
        }
        img.setRGB(6, 6, AMBER);
        img.setRGB(9, 6, AMBER);
        img.setRGB(5, 9, AMBER_DEEP);
        img.setRGB(6, 10, AMBER);
        img.setRGB(9, 10, AMBER);
        img.setRGB(10, 9, AMBER_DEEP);
        img.setRGB(7, 4, AMBER);
        img.setRGB(8, 4, AMBER);
        return img;
    }

    private static BufferedImage blank() {
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    private ItemTex() {}
}
