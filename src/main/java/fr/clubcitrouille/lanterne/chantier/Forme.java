package fr.clubcitrouille.lanterne.chantier;

import java.util.Arrays;

import org.jspecify.annotations.Nullable;

/**
 * La géométrie d'un état de bloc, prête pour le lancer de rayons : une liste de quadrilatères dans le
 * cube unité du bloc ([0,1]³), déjà passés par les rotations d'élément et de variante.
 *
 * <p>Un quad est un parallélogramme {@code P0 + u·Eu + v·Ev}, (u, v) ∈ [0,1]² ; (u, v) = (0, 0) est le
 * coin haut-gauche de la texture telle que le modèle la pose, ce qui rend l'échantillonnage direct.
 * Tableaux plats plutôt qu'objets : le rastériseur en teste des millions par image.
 */
final class Forme {
    /** Par quad : P0 (3), Eu (3), Ev (3), normale sortante (3), 1/|Eu|², 1/|Ev|², ombrage de face. */
    static final int G = 15;
    static final Forme VIDE = new Forme(0);

    int n;
    double[] g;
    /** Par quad : rectangle uv du modèle, en fraction de texture (u0, v0, u1, v1). */
    float[] uv;
    byte[] rot;
    /** Direction de {@code cullface} (ordinal de Direction) après rotation, ou -1. */
    byte[] cull;
    /** Face alignée sur un axe ET posée sur une frontière du bloc : direction (pour l'occlusion), sinon -1. */
    byte[] axe;
    boolean[] teinte;
    boolean[] uvlock;
    Png.@Nullable Image[] tex;

    /** Couleur de carte (repli sans texture). */
    int carte;
    int couleurTeinte = 0xFFFFFF;
    /** Cube plein opaque (isSolidRender) : occulte ses voisins, peut être coupé net. */
    boolean plein;
    /** 0 rien, 1 eau, 2 lave. */
    int fluide;
    float hautFluide;
    /** Faces de fluide : coupées contre le même fluide plutôt que par Block.shouldRenderFace. */
    boolean estFluide;
    /** Le modèle n'a pas été trouvé : forme tirée de la collision, texture de particule ou couleur de carte. */
    boolean repli;
    @Nullable Forme eau;
    @Nullable Forme eauPleine;

    Forme(int capacite) {
        g = new double[capacite * G];
        uv = new float[capacite * 4];
        rot = new byte[capacite];
        cull = new byte[capacite];
        axe = new byte[capacite];
        teinte = new boolean[capacite];
        uvlock = new boolean[capacite];
        tex = new Png.Image[capacite];
    }

    boolean vide() {
        return n == 0 && eau == null;
    }

    /** Ajoute un quad ; les points sont dans [0,1]³, {@code nrm} la normale sortante voulue (sens). */
    void ajouter(double[] p0, double[] pu, double[] pv, float[] rect, int rotation, int cullface,
                 boolean teinte, boolean uvlock, Png.@Nullable Image tex, double @Nullable [] ombrageDir, boolean ombre) {
        double ux = pu[0] - p0[0], uy = pu[1] - p0[1], uz = pu[2] - p0[2];
        double vx = pv[0] - p0[0], vy = pv[1] - p0[1], vz = pv[2] - p0[2];
        double lu = ux * ux + uy * uy + uz * uz;
        double lv = vx * vx + vy * vy + vz * vz;
        if (lu < 1e-12 || lv < 1e-12) {
            return;
        }
        // Normale sortante = -(Eu × Ev) : vérifié face par face sur la table de Modeles.quad.
        double nx = -(uy * vz - uz * vy), ny = -(uz * vx - ux * vz), nz = -(ux * vy - uy * vx);
        double ln = Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= ln;
        ny /= ln;
        nz /= ln;
        if (n * G >= g.length) {
            int c = Math.max(8, n * 2);
            g = Arrays.copyOf(g, c * G);
            uv = Arrays.copyOf(uv, c * 4);
            rot = Arrays.copyOf(rot, c);
            cull = Arrays.copyOf(cull, c);
            axe = Arrays.copyOf(axe, c);
            this.teinte = Arrays.copyOf(this.teinte, c);
            this.uvlock = Arrays.copyOf(this.uvlock, c);
            this.tex = Arrays.copyOf(this.tex, c);
        }
        int o = n * G;
        g[o] = p0[0];
        g[o + 1] = p0[1];
        g[o + 2] = p0[2];
        g[o + 3] = ux;
        g[o + 4] = uy;
        g[o + 5] = uz;
        g[o + 6] = vx;
        g[o + 7] = vy;
        g[o + 8] = vz;
        g[o + 9] = nx;
        g[o + 10] = ny;
        g[o + 11] = nz;
        g[o + 12] = 1.0 / lu;
        g[o + 13] = 1.0 / lv;
        double[] d = ombrageDir != null ? ombrageDir : new double[] {nx, ny, nz};
        // L'ombrage directionnel de Minecraft : dessus 1, dessous 0,5, nord/sud 0,8, est/ouest 0,6.
        g[o + 14] = !ombre ? 1.0 : Math.min(1.0, d[0] * d[0] * 0.6 + d[2] * d[2] * 0.8 + d[1] * d[1] * (d[1] > 0 ? 1.0 : 0.5));
        System.arraycopy(rect, 0, uv, n * 4, 4);
        rot[n] = (byte) rotation;
        cull[n] = (byte) cullface;
        byte a = -1;
        if (Math.abs(nx) > 0.9999 || Math.abs(ny) > 0.9999 || Math.abs(nz) > 0.9999) {
            int k = Math.abs(nx) > 0.9999 ? 0 : Math.abs(ny) > 0.9999 ? 1 : 2;
            double plan = p0[k];
            double sens = k == 0 ? nx : k == 1 ? ny : nz;
            if ((sens > 0 && Math.abs(plan - 1) < 1e-6) || (sens < 0 && Math.abs(plan) < 1e-6)) {
                a = (byte) direction(k, sens > 0);
            }
        }
        axe[n] = a;
        this.teinte[n] = teinte;
        this.uvlock[n] = uvlock;
        this.tex[n] = tex;
        n++;
    }

    /** Ordinal de {@code Direction} : DOWN 0, UP 1, NORTH 2, SOUTH 3, WEST 4, EAST 5. */
    static int direction(int axe, boolean positif) {
        return switch (axe) {
            case 0 -> positif ? 5 : 4;
            case 1 -> positif ? 1 : 0;
            default -> positif ? 3 : 2;
        };
    }

    static final int[][] DIR = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    /** Un pavé [a, b] (en fraction de bloc) aux six faces, texture posée comme le ferait un modèle par défaut. */
    void pave(double[] a, double[] b, Png.@Nullable Image tex, boolean teinte, int cullSiBord) {
        for (int f = 0; f < 6; f++) {
            double[][] q = Modeles.quad(f, a, b);
            float[] rect = Modeles.uvDefaut(f, a, b);
            boolean bord = switch (f) {
                case 0 -> a[1] <= 0;
                case 1 -> b[1] >= 1;
                case 2 -> a[2] <= 0;
                case 3 -> b[2] >= 1;
                case 4 -> a[0] <= 0;
                default -> b[0] >= 1;
            };
            ajouter(q[0], q[1], q[2], rect, 0, bord && cullSiBord >= 0 ? f : -1, teinte, false, tex, null, true);
        }
    }
}
