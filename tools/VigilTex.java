import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Les textures de la Lanterne de Veille, dessinées plutôt que peintes.
 *
 * <p>Seize sur seize, palette restreinte, pixels durs : c'est la grammaire du jeu, et s'en écarter
 * ferait un bloc qui a l'air emprunté à un autre. Le bloc doit dire ce qu'il fait — une cage d'acier
 * noirci, une flamme dedans, et de la lumière qui sort par les fentes.
 *
 * <p>Lancer : {@code java tools/VigilTex.java <dossier-de-sortie>}
 */
public final class VigilTex {
    // Acier noirci, du plus sombre au plus clair.
    private static final int[] IRON = {
        0xFF16161A, 0xFF232329, 0xFF31313A, 0xFF41414C, 0xFF54545F, 0xFF6B6B77
    };

    // Ambre, du bord vers le cœur.
    private static final int[] GLOW = {
        0xFF7A3208, 0xFFB35213, 0xFFDE7A1C, 0xFFF5A32E, 0xFFFFC857, 0xFFFFE49B, 0xFFFFF6D8
    };

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : ".");
        out.mkdirs();
        ImageIO.write(side(), "png", new File(out, "veilleuse_side.png"));
        ImageIO.write(top(), "png", new File(out, "veilleuse_top.png"));
        ImageIO.write(bottom(), "png", new File(out, "veilleuse_bottom.png"));
        System.out.println("Trois textures écrites dans " + out.getAbsolutePath());
    }

    /**
     * La face : une cage à trois fenêtres, une flamme au milieu.
     *
     * <p>Les barreaux ne sont pas là pour décorer. Sans eux, la face serait un carré de dégradé —
     * joli en grand, illisible à seize pixels, et impossible à distinguer d'une pierre lumineuse une
     * fois posé dans un mur.
     */
    private static BufferedImage side() {
        BufferedImage img = blank();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                img.setRGB(x, y, frame(x, y));
            }
        }

        // Le panneau intérieur, de (2,3) à (13,13). Le haut descend d'un pixel : la lanterne a un
        // chapeau, et un chapeau se voit à son épaisseur.
        for (int x = 2; x <= 13; x++) {
            for (int y = 3; y <= 13; y++) {
                boolean bar = x == 5 || x == 10;
                if (bar) {
                    img.setRGB(x, y, IRON[2 + ((y % 4 == 0) ? 1 : 0)]);
                    continue;
                }
                img.setRGB(x, y, ember(x, y));
            }
        }

        // La flamme : deux pixels de cœur presque blancs, sinon la lumière n'a pas de source.
        img.setRGB(7, 9, GLOW[6]);
        img.setRGB(8, 9, GLOW[6]);
        img.setRGB(7, 8, GLOW[5]);
        img.setRGB(8, 8, GLOW[5]);
        img.setRGB(7, 10, GLOW[5]);
        img.setRGB(8, 10, GLOW[5]);
        img.setRGB(7, 7, GLOW[4]);
        img.setRGB(8, 7, GLOW[4]);

        // L'anneau d'accroche, au sommet. Trois pixels, et le bloc cesse d'être une caisse.
        img.setRGB(7, 0, IRON[4]);
        img.setRGB(8, 0, IRON[4]);
        img.setRGB(7, 1, IRON[5]);
        img.setRGB(8, 1, IRON[3]);
        return img;
    }

    /** Le dessus : une grille sur un puits de lumière. */
    private static BufferedImage top() {
        BufferedImage img = blank();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                img.setRGB(x, y, frame(x, y));
            }
        }
        for (int x = 3; x <= 12; x++) {
            for (int y = 3; y <= 12; y++) {
                double d = Math.hypot(x - 7.5, y - 7.5);
                if (d > 5.2) {
                    continue;
                }
                // La grille : un croisillon d'acier par-dessus le puits.
                if (x == 7 || x == 8 || y == 7 || y == 8) {
                    img.setRGB(x, y, IRON[d < 2.4 ? 3 : 2]);
                    continue;
                }
                int step = (int) Math.round(Math.max(0, 5.2 - d) * 1.25);
                img.setRGB(x, y, GLOW[Math.min(GLOW.length - 1, step + 1)]);
            }
        }
        rivets(img);
        return img;
    }

    /** Le dessous : de l'acier, et juste assez de braise pour qu'on sache d'où vient la chaleur. */
    private static BufferedImage bottom() {
        BufferedImage img = blank();
        // Une plaque unie, et non un motif. La première version semait un pixel clair tous les sept
        // avec (x + y) % 7 : à seize pixels de côté, ce modulo ne fait pas du bruit, il fait des
        // RAYURES DIAGONALES — un motif net et régulier que l'œil lit aussitôt, et qui n'a rien à
        // voir avec de l'acier. Le hasard ne s'imite pas avec une division.
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                boolean rim = x == 0 || x == 15 || y == 0 || y == 15;
                img.setRGB(x, y, rim ? IRON[0] : IRON[1]);
            }
        }
        // Le fond de la cage : quatre fentes d'où filtre la braise.
        for (int x = 5; x <= 10; x++) {
            for (int y = 5; y <= 10; y++) {
                boolean slot = (x == 6 || x == 9) && y >= 6 && y <= 9;
                double d = Math.hypot(x - 7.5, y - 7.5);
                if (slot) {
                    img.setRGB(x, y, GLOW[2]);
                } else if (d < 1.3) {
                    img.setRGB(x, y, GLOW[3]);
                } else {
                    img.setRGB(x, y, IRON[2]);
                }
            }
        }
        rivets(img);
        return img;
    }

    /**
     * Le cadre d'acier, avec sa lumière venue d'en haut.
     *
     * <p>Une bordure d'une seule teinte fait un bloc plat. Le haut reçoit la lumière, le bas est dans
     * l'ombre : c'est ce que fait vanilla sur ses propres cadres, et c'est ce qui donne du relief à
     * seize pixels sans ajouter une seule couleur.
     */
    private static int frame(int x, int y) {
        boolean edge = x <= 1 || x >= 14 || y <= 1 || y >= 14;
        if (!edge) {
            return IRON[2];
        }
        if (y <= 1) {
            return IRON[4];
        }
        if (y >= 14) {
            return IRON[0];
        }
        return IRON[x <= 1 ? 3 : 1];
    }

    /** La braise derrière les barreaux : claire au centre, sombre aux angles. */
    private static int ember(int x, int y) {
        double d = Math.hypot((x - 7.5) * 0.85, (y - 8.5) * 0.72);
        int step = (int) Math.round(Math.max(0, 6.0 - d) * 0.95);
        return GLOW[Math.max(0, Math.min(GLOW.length - 2, step))];
    }

    /** Quatre rivets aux angles. Le détail qui dit « fabriqué » plutôt que « généré ». */
    private static void rivets(BufferedImage img) {
        int[][] spots = {{2, 2}, {13, 2}, {2, 13}, {13, 13}};
        for (int[] spot : spots) {
            img.setRGB(spot[0], spot[1], IRON[5]);
        }
    }

    private static BufferedImage blank() {
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

    private VigilTex() {}
}
