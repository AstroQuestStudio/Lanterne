import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Un cube isométrique bâti à partir des trois textures d'un bloc.
 *
 * <p>Montrer un bloc dans un README par sa texture à plat ne dit rien : personne ne reconnaît un bloc
 * de Minecraft à sa face dépliée. Il faut le voir <b>comme on le voit en jeu</b> — trois faces, et
 * l'ombrage que le jeu leur applique.
 *
 * <p>Le rendu se fait par <b>projection inverse</b> : pour chaque pixel de l'image, on cherche à
 * quelle face et à quel texel il correspond. C'est l'inverse de la méthode naïve — dessiner chaque
 * texel comme un losange — qui laisse des trous dès que l'échelle n'est pas entière, parce que deux
 * losanges voisins peuvent ne pas se toucher.
 *
 * <p>Lancer : {@code java tools/Iso.java <dossier-textures> <prefixe> <sortie.png>}
 */
public final class Iso {
    /** Côté d'une texture, en texels. */
    private static final int N = 16;

    /** Facteur d'échelle. Douze donne 768 pixels de côté, net sur un écran moderne. */
    private static final int S = 12;

    /**
     * L'ombrage de Minecraft.
     *
     * <p>Ce ne sont pas des valeurs choisies au goût : ce sont celles que le jeu applique à ses
     * propres faces. S'en écarter donnerait un bloc qui a l'air d'appartenir à un autre jeu.
     */
    private static final double TOP = 1.0d;
    private static final double LEFT = 0.72d;
    private static final double RIGHT = 0.52d;

    public static void main(String[] args) throws Exception {
        File folder = new File(args[0]);
        String prefix = args[1];
        BufferedImage top = ImageIO.read(new File(folder, prefix + "_top.png"));
        BufferedImage side = ImageIO.read(new File(folder, prefix + "_side.png"));

        int a = 2 * S;
        int b = S;
        int width = 4 * N * S;
        int height = 4 * N * S;
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        // Le sommet du cube est au milieu en largeur, et le cube occupe toute la hauteur.
        int originX = width / 2;
        int originY = 2 * N * b;

        for (int px = 0; px < width; px++) {
            for (int py = 0; py < height; py++) {
                double sx = (px - originX + 0.5d) / a;
                double sy = (py - originY + 0.5d) / b;

                // Dessus : P = u·ex + v·ez, avec ex = (a, b) et ez = (-a, b), décalé de N vers le haut.
                double lifted = sy + 2.0d * N;
                double u = (sx + lifted) / 2.0d;
                double v = (lifted - sx) / 2.0d;
                if (inside(u) && inside(v)) {
                    out.setRGB(px, py, shade(top.getRGB(clamp(u), clamp(v)), TOP));
                    continue;
                }

                // Face de gauche : à z = N, donc P = u·ex + N·ez + y·ey.
                double lu = sx + N;
                double ly = (lu + N - sy) / 2.0d;
                if (inside(lu) && inside(ly)) {
                    out.setRGB(px, py, shade(side.getRGB(clamp(lu), N - 1 - clamp(ly)), LEFT));
                    continue;
                }

                // Face de droite : à x = N, donc P = N·ex + v·ez + y·ey.
                double rv = N - sx;
                double ry = (N + rv - sy) / 2.0d;
                if (inside(rv) && inside(ry)) {
                    out.setRGB(px, py, shade(side.getRGB(clamp(rv), N - 1 - clamp(ry)), RIGHT));
                }
            }
        }

        ImageIO.write(out, "png", new File(args[2]));
        System.out.println("Rendu écrit : " + new File(args[2]).getAbsolutePath());
    }

    private static boolean inside(double value) {
        return value >= 0.0d && value < N;
    }

    private static int clamp(double value) {
        return Math.max(0, Math.min(N - 1, (int) value));
    }

    /** Applique l'ombrage d'une face, en conservant la transparence. */
    private static int shade(int argb, double factor) {
        int alpha = (argb >>> 24) & 0xFF;
        if (alpha == 0) {
            return 0;
        }
        int r = (int) Math.round(((argb >> 16) & 0xFF) * factor);
        int g = (int) Math.round(((argb >> 8) & 0xFF) * factor);
        int b = (int) Math.round((argb & 0xFF) * factor);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    private Iso() {}
}
