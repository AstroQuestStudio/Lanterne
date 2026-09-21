import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Comparaison d'iso-rendu de deux captures PNG de la même scène figée.
 * Usage : java Cmp.java <a.png> <b.png> [seuil_max_sur_255 (défaut 1)]
 *
 * Sortie : différence maximale par canal (0-255), PSNR global, part de pixels différents, et VERDICT.
 * Seuil documenté : écart maximal <= 1/255 sur au moins 99,9 % des pixels = « iso-rendu » ; le bruit temporel
 * (TAA, nuages animés, particules) impose des captures prises au même instant de la scène figée. Une image
 * différente en taille est refusée.
 */
public class Cmp {
    public static void main(String[] a) throws Exception {
        BufferedImage x = ImageIO.read(new File(a[0]));
        BufferedImage y = ImageIO.read(new File(a[1]));
        int limit = a.length > 2 ? Integer.parseInt(a[2]) : 1;
        if (x == null || y == null || x.getWidth() != y.getWidth() || x.getHeight() != y.getHeight()) {
            System.out.println("VERDICT: INCOMPARABLE (images absentes ou de tailles différentes)");
            System.exit(2);
        }
        int w = x.getWidth(), h = x.getHeight();
        long sumSq = 0, differing = 0;
        int max = 0;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int p = x.getRGB(i, j), q = y.getRGB(i, j);
                int d = 0;
                for (int s = 0; s <= 16; s += 8) {
                    int e = Math.abs(((p >> s) & 255) - ((q >> s) & 255));
                    sumSq += (long) e * e;
                    d = Math.max(d, e);
                }
                max = Math.max(max, d);
                if (d > limit) differing++;
            }
        }
        double mse = sumSq / (3.0 * w * h);
        double psnr = mse == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(255.0 * 255.0 / mse);
        double share = 100.0 * differing / ((double) w * h);
        System.out.printf(java.util.Locale.ROOT,
                "taille=%dx%d ecart_max=%d/255 PSNR=%.2f dB pixels_au_dela_de_%d=%.4f%%%n", w, h, max, psnr, limit, share);
        System.out.println(share <= 0.1 ? "VERDICT: ISO-RENDU (dans le seuil)" : "VERDICT: DIFFERENT (hors seuil)");
    }
}
