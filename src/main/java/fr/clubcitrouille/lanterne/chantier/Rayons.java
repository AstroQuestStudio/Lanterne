package fr.clubcitrouille.lanterne.chantier;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Le rastériseur du rendu 3D : un rayon par pixel, lancé dans la copie des blocs ({@link Rendu3d}).
 *
 * <p>Pourquoi des rayons et pas des triangles : la caméra peut être DANS une salle, la vue peut être
 * coupée par un plan, le verre doit se composer avec ce qu'il y a derrière — trois choses qu'un
 * rayon fait sans rien ajouter (pas de découpe au plan proche, pas de tri des transparents). Et le
 * coût suit la surface de l'image, pas le volume : un rayon s'arrête au premier bloc opaque.
 *
 * <p>Parcours de grille d'Amanatides et Woo (une cellule par pas, sans racine ni division), puis dans
 * chaque cellule non vide, intersection avec les quads de sa {@link Forme}. Les quads sont à sens
 * unique comme dans le jeu : une face vue de dos n'existe pas, ce qui rend la caméra intérieure
 * correcte sans rien de plus. Composition avant→arrière : on s'arrête dès que l'opacité cumulée
 * dépasse 98,5 %.
 *
 * <p>Un seul fil, aucune allocation par pixel ; l'état du rayon courant vit dans des champs.
 */
final class Rayons {
    // La scène, en coordonnées de grille (monde − g0), ordre y, z, x.
    private final int gx0, gy0, gz0, nx, ny, nz;
    private final char[] cel;
    private final byte[] lum;
    private final Forme[] formes;
    private final BlockState[] etats;
    // Boîte visible (bornes incluses, coordonnées de grille) : hors d'elle, tout est air.
    private final int vx0, vy0, vz0, vx1, vy1, vz1;

    boolean lumiere = true;
    boolean occlusion = true;
    int fond = 0xFFD8E4EC;
    boolean ciel;

    // Caméra.
    private boolean ortho;
    private final double[] f = new double[3], r = new double[3], u = new double[3], base = new double[3];
    private double echelle, tanH, aspect, tMax = Double.POSITIVE_INFINITY;
    /**
     * Coupe devant : plan VERTICAL face à l'observateur (normale = visée ramenée à l'horizontale, ou
     * la visée elle-même si elle plonge à plus de 72°) ; tout ce qui est en deçà disparaît. Un plan
     * vertical se lit comme une coupe d'architecte ; un plan penché avec le regard ne se lisait pas.
     */
    private boolean coupe;
    private final double[] cn = new double[3];
    private double coupePlan;

    private final Long2ByteOpenHashMap faces = new Long2ByteOpenHashMap();
    private static final Direction[] DIRS = Direction.values();

    // Rayon courant.
    private double ar, ag, ab, aa;
    private int texel;

    long rayons;
    long cellules;

    Rayons(int gx0, int gy0, int gz0, int nx, int ny, int nz, char[] cel, byte[] lum, Forme[] formes, BlockState[] etats,
           int[] visible) {
        this.gx0 = gx0;
        this.gy0 = gy0;
        this.gz0 = gz0;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.cel = cel;
        this.lum = lum;
        this.formes = formes;
        this.etats = etats;
        vx0 = visible[0] - gx0;
        vy0 = visible[1] - gy0;
        vz0 = visible[2] - gz0;
        vx1 = visible[3] - gx0;
        vy1 = visible[4] - gy0;
        vz1 = visible[5] - gz0;
        faces.defaultReturnValue((byte) -1);
    }

    /** Base orthonormée d'une visée : avant {@code d}, droite = avant × haut, haut. */
    private void visee(double[] d) {
        double l = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        f[0] = d[0] / l;
        f[1] = d[1] / l;
        f[2] = d[2] / l;
        // Visée verticale : on prend le nord comme « haut » de l'image.
        double hx = 0, hy = 1, hz = 0;
        if (Math.abs(f[1]) > 0.9999) {
            hy = 0;
            hz = -1;
        }
        r[0] = f[1] * hz - f[2] * hy;
        r[1] = f[2] * hx - f[0] * hz;
        r[2] = f[0] * hy - f[1] * hx;
        double lr = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
        r[0] /= lr;
        r[1] /= lr;
        r[2] /= lr;
        u[0] = r[1] * f[2] - r[2] * f[1];
        u[1] = r[2] * f[0] - r[0] * f[2];
        u[2] = r[0] * f[1] - r[1] * f[0];
    }

    /**
     * Vue orthographique cadrée sur la boîte visible : {@code d} la direction de visée ; l'image
     * {@code W×H} contient les huit coins avec 3 % de marge.
     */
    void orthographique(double[] d, int W, int H, double coupeDevant) {
        ortho = true;
        visee(d);
        double cx = (vx0 + vx1 + 1) / 2.0 + gx0, cy = (vy0 + vy1 + 1) / 2.0 + gy0, cz = (vz0 + vz1 + 1) / 2.0 + gz0;
        double amin = Double.MAX_VALUE, amax = -Double.MAX_VALUE, bmin = Double.MAX_VALUE, bmax = -Double.MAX_VALUE;
        double pmin = Double.MAX_VALUE, pmax = -Double.MAX_VALUE;
        for (int k = 0; k < 8; k++) {
            double x = ((k & 1) == 0 ? vx0 : vx1 + 1) + gx0 - cx;
            double y = ((k & 2) == 0 ? vy0 : vy1 + 1) + gy0 - cy;
            double z = ((k & 4) == 0 ? vz0 : vz1 + 1) + gz0 - cz;
            double a = x * r[0] + y * r[1] + z * r[2];
            double b = x * u[0] + y * u[1] + z * u[2];
            double p = x * f[0] + y * f[1] + z * f[2];
            amin = Math.min(amin, a);
            amax = Math.max(amax, a);
            bmin = Math.min(bmin, b);
            bmax = Math.max(bmax, b);
            pmin = Math.min(pmin, p);
            pmax = Math.max(pmax, p);
        }
        echelle = Math.max((amax - amin) / (W * 0.94), (bmax - bmin) / (H * 0.94));
        double ca = (amax + amin) / 2, cb = (bmax + bmin) / 2;
        double recul = -pmin + 2;
        for (int k = 0; k < 3; k++) {
            double c = k == 0 ? cx : k == 1 ? cy : cz;
            base[k] = c + r[k] * (ca - W / 2.0 * echelle) + u[k] * (cb + H / 2.0 * echelle) - f[k] * recul;
        }
        if (!Double.isNaN(coupeDevant)) {
            // Plan à coupeDevant blocs derrière le coin de la boîte le plus proche de l'observateur.
            normaleCoupe();
            double m = Double.MAX_VALUE;
            for (int k = 0; k < 8; k++) {
                double x = ((k & 1) == 0 ? vx0 : vx1 + 1) + gx0;
                double y = ((k & 2) == 0 ? vy0 : vy1 + 1) + gy0;
                double z = ((k & 4) == 0 ? vz0 : vz1 + 1) + gz0;
                m = Math.min(m, x * cn[0] + y * cn[1] + z * cn[2]);
            }
            coupePlan = m + coupeDevant;
        }
    }

    private void normaleCoupe() {
        coupe = true;
        double h = Math.sqrt(f[0] * f[0] + f[2] * f[2]);
        if (h > 0.3) {
            cn[0] = f[0] / h;
            cn[1] = 0;
            cn[2] = f[2] / h;
        } else {
            cn[0] = f[0];
            cn[1] = f[1];
            cn[2] = f[2];
        }
    }

    void perspective(double[] pos, double[] d, double fovDeg, int W, int H, double distance, double coupeDevant) {
        ortho = false;
        visee(d);
        base[0] = pos[0];
        base[1] = pos[1];
        base[2] = pos[2];
        tanH = Math.tan(Math.toRadians(fovDeg) / 2);
        aspect = (double) W / H;
        tMax = distance;
        if (!Double.isNaN(coupeDevant) && coupeDevant > 0) {
            normaleCoupe();
            coupePlan = pos[0] * cn[0] + pos[1] * cn[1] + pos[2] * cn[2] + coupeDevant;
        }
    }

    /** L'image, {@code lissage}² rayons par pixel. */
    int[] rendre(int W, int H, int lissage) {
        int[] out = new int[W * H];
        double inv = 1.0 / (lissage * lissage);
        for (int py = 0; py < H; py++) {
            // Une ligne ≈ 1 ms : on rend la main au fil serveur s'il attend le cœur (VPS mono-cœur).
            Thread.yield();
            for (int px = 0; px < W; px++) {
                double sr = 0, sg = 0, sb = 0, sa = 0;
                for (int j = 0; j < lissage; j++) {
                    for (int i = 0; i < lissage; i++) {
                        int c = rayon(px + (i + 0.5) / lissage, py + (j + 0.5) / lissage, W, H);
                        double a = (c >>> 24) / 255.0;
                        sr += ((c >> 16) & 0xFF) * a;
                        sg += ((c >> 8) & 0xFF) * a;
                        sb += (c & 0xFF) * a;
                        sa += a;
                    }
                }
                if (sa <= 0) {
                    out[py * W + px] = 0;
                } else {
                    int a = (int) Math.round(sa * inv * 255);
                    out[py * W + px] = a << 24 | (int) Math.round(sr / sa) << 16 | (int) Math.round(sg / sa) << 8
                            | (int) Math.round(sb / sa);
                }
            }
        }
        return out;
    }

    private int rayon(double fx, double fy, int W, int H) {
        double ox, oy, oz, dx, dy, dz, t0 = 0;
        boolean coupeIci = false;
        if (ortho) {
            ox = base[0] + r[0] * fx * echelle - u[0] * fy * echelle;
            oy = base[1] + r[1] * fx * echelle - u[1] * fy * echelle;
            oz = base[2] + r[2] * fx * echelle - u[2] * fy * echelle;
            dx = f[0];
            dy = f[1];
            dz = f[2];
        } else {
            double a = (2 * fx / W - 1) * tanH * aspect;
            double b = (1 - 2 * fy / H) * tanH;
            dx = f[0] + r[0] * a + u[0] * b;
            dy = f[1] + r[1] * a + u[1] * b;
            dz = f[2] + r[2] * a + u[2] * b;
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= l;
            dy /= l;
            dz /= l;
            ox = base[0];
            oy = base[1];
            oz = base[2];
        }
        rayons++;
        ar = ag = ab = aa = 0;
        boolean rien = false;
        if (coupe) {
            double den = dx * cn[0] + dy * cn[1] + dz * cn[2];
            double ecart = coupePlan - (ox * cn[0] + oy * cn[1] + oz * cn[2]);
            if (ecart > 0) {
                // L'origine est en deçà du plan : le rayon ne voit rien avant de l'avoir franchi.
                if (den <= 1e-9) {
                    rien = true;
                } else {
                    t0 = ecart / den;
                    coupeIci = true;
                }
            }
        }
        if (!rien) {
            tracer(ox - gx0, oy - gy0, oz - gz0, dx, dy, dz, t0, tMax, coupeIci);
        }
        int bg = fond;
        if (ciel) {
            // Dégradé de ciel selon la hauteur du regard ; sous l'horizon, un gris-bleu neutre.
            double h = Math.max(-1, Math.min(1, dy));
            int zen = 0x6F9FE6, hor = 0xC8DCF2, sol = 0x9AA6B2;
            bg = 0xFF000000 | (h >= 0 ? melange(hor, zen, Math.sqrt(h)) : melange(hor, sol, Math.min(1, -h * 4)));
        }
        double ba = (bg >>> 24) / 255.0 * (1 - aa);
        double ta = aa + ba;
        if (ta <= 0) {
            return 0;
        }
        double rr = (ar + ((bg >> 16) & 0xFF) * ba) / ta;
        double gg = (ag + ((bg >> 8) & 0xFF) * ba) / ta;
        double bb = (ab + (bg & 0xFF) * ba) / ta;
        return (int) Math.round(Math.min(1, ta) * 255) << 24 | sat(rr) << 16 | sat(gg) << 8 | sat(bb);
    }

    private static int sat(double v) {
        return v <= 0 ? 0 : v >= 255 ? 255 : (int) (v + 0.5);
    }

    private static int melange(int a, int b, double t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return r << 16 | g << 8 | bl;
    }

    private void tracer(double ox, double oy, double oz, double dx, double dy, double dz, double tMin, double tFin,
                        boolean coupeIci) {
        double t0 = tMin, t1 = tFin;
        // Découpe du rayon par la boîte visible (méthode des dalles), axe par axe.
        for (int k = 0; k < 3; k++) {
            double o = k == 0 ? ox : k == 1 ? oy : oz;
            double d = k == 0 ? dx : k == 1 ? dy : dz;
            double lo = k == 0 ? vx0 : k == 1 ? vy0 : vz0;
            double hi = (k == 0 ? vx1 : k == 1 ? vy1 : vz1) + 1;
            if (Math.abs(d) < 1e-12) {
                if (o < lo || o > hi) {
                    return;
                }
            } else {
                double a = (lo - o) / d;
                double b = (hi - o) / d;
                t0 = Math.max(t0, Math.min(a, b));
                t1 = Math.min(t1, Math.max(a, b));
            }
        }
        if (t0 >= t1) {
            return;
        }
        // La coupe ne vaut que si elle tombe dans la boîte : sinon le rayon entre par une face normale.
        boolean couper = coupeIci && t0 == tMin;
        double e = t0 + 1e-9;
        int ix = Math.max(vx0, Math.min(vx1, (int) Math.floor(ox + dx * e)));
        int iy = Math.max(vy0, Math.min(vy1, (int) Math.floor(oy + dy * e)));
        int iz = Math.max(vz0, Math.min(vz1, (int) Math.floor(oz + dz * e)));
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double inf = Double.POSITIVE_INFINITY;
        double tdx = dx == 0 ? inf : Math.abs(1 / dx), tdy = dy == 0 ? inf : Math.abs(1 / dy), tdz = dz == 0 ? inf : Math.abs(1 / dz);
        double tnx = dx > 0 ? (ix + 1 - ox) / dx : dx < 0 ? (ix - ox) / dx : inf;
        double tny = dy > 0 ? (iy + 1 - oy) / dy : dy < 0 ? (iy - oy) / dy : inf;
        double tnz = dz > 0 ? (iz + 1 - oz) / dz : dz < 0 ? (iz - oz) / dz : inf;
        double tEntree = t0;
        boolean premier = true;
        while (true) {
            cellules++;
            double tSortie = Math.min(Math.min(tnx, tny), Math.min(tnz, t1));
            int idx = (iy * nz + iz) * nx + ix;
            Forme fo = formes[cel[idx]];
            if (!fo.vide()) {
                if (premier && couper && fo.plein) {
                    tranche(fo, ox - ix, oy - iy, oz - iz, dx, dy, dz, t0);
                    return;
                }
                if (cellule(fo, idx, ix, iy, iz, ox, oy, oz, dx, dy, dz, tEntree, tSortie)) {
                    return;
                }
            }
            premier = false;
            if (tnx <= tny && tnx <= tnz) {
                if (tnx >= t1) {
                    return;
                }
                ix += sx;
                if (ix < vx0 || ix > vx1) {
                    return;
                }
                tEntree = tnx;
                tnx += tdx;
            } else if (tny <= tnz) {
                if (tny >= t1) {
                    return;
                }
                iy += sy;
                if (iy < vy0 || iy > vy1) {
                    return;
                }
                tEntree = tny;
                tny += tdy;
            } else {
                if (tnz >= t1) {
                    return;
                }
                iz += sz;
                if (iz < vz0 || iz > vz1) {
                    return;
                }
                tEntree = tnz;
                tnz += tdz;
            }
        }
    }

    /** Un cube plein tranché par la coupe : sa matière vue en plan, texture de la face tournée vers nous, assombrie. */
    private void tranche(Forme fo, double cx, double cy, double cz, double dx, double dy, double dz, double t) {
        double lx = clamp01(cx + dx * t), ly = clamp01(cy + dy * t), lz = clamp01(cz + dz * t);
        // La face du bloc la plus tournée vers l'observateur, vue à travers le plan de coupe.
        int k = Math.abs(cn[0]) >= Math.abs(cn[1]) && Math.abs(cn[0]) >= Math.abs(cn[2]) ? 0 : Math.abs(cn[1]) >= Math.abs(cn[2]) ? 1 : 2;
        double s = cn[k];
        int dir = Forme.direction(k, s < 0);
        int c = 0xFF000000 | fo.carte;
        for (int q = 0; q < fo.n; q++) {
            if (fo.axe[q] == dir) {
                c = echantillon(fo, q, 0, 0, lx, ly, lz, true);
                if (fo.teinte[q]) {
                    c = teinter(c, fo.couleurTeinte);
                }
                break;
            }
        }
        composer(c, 1.0, 0.62);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    /** Vrai si le rayon est devenu opaque dans cette cellule. */
    private boolean cellule(Forme fo, int idx, int ix, int iy, int iz, double ox, double oy, double oz,
                            double dx, double dy, double dz, double tEntree, double tSortie) {
        double cx = ox - ix, cy = oy - iy, cz = oz - iz;
        Forme fl = null;
        if (fo.fluide != 0 && fo.eau != null) {
            int dessus = iy + 1 <= vy1 ? idx + nx * nz : -1;
            fl = dessus >= 0 && formes[cel[dessus]].fluide == fo.fluide ? fo.eauPleine : fo.eau;
        }
        double lo = tEntree - 1e-7;
        for (int garde = 0; garde < 64; garde++) {
            Forme meilleure = null;
            int mq = -1;
            double mt = tSortie + 1e-7;
            int mtex = 0;
            boolean bord = false;
            for (int passe = 0; passe < 2; passe++) {
                // Le fluide d'abord : à égalité, le bloc (dessiné après) l'emporte.
                Forme F = passe == 0 ? fl : fo;
                if (F == null) {
                    continue;
                }
                double[] g = F.g;
                for (int q = 0, o = 0; q < F.n; q++, o += Forme.G) {
                    double nxq = g[o + 9], nyq = g[o + 10], nzq = g[o + 11];
                    double den = dx * nxq + dy * nyq + dz * nzq;
                    if (den >= -1e-12) {
                        continue;
                    }
                    double t = ((g[o] - cx) * nxq + (g[o + 1] - cy) * nyq + (g[o + 2] - cz) * nzq) / den;
                    if (t <= lo || t > mt + 1e-9) {
                        continue;
                    }
                    double hx = cx + dx * t, hy = cy + dy * t, hz = cz + dz * t;
                    double px = hx - g[o], py = hy - g[o + 1], pz = hz - g[o + 2];
                    double uu = (px * g[o + 3] + py * g[o + 4] + pz * g[o + 5]) * g[o + 12];
                    if (uu < -1e-9 || uu > 1 + 1e-9) {
                        continue;
                    }
                    double vv = (px * g[o + 6] + py * g[o + 7] + pz * g[o + 8]) * g[o + 13];
                    if (vv < -1e-9 || vv > 1 + 1e-9) {
                        continue;
                    }
                    int c = echantillon(F, q, uu, vv, hx, hy, hz, F.uvlock[q]);
                    if ((c >>> 24) < 8) {
                        continue;
                    }
                    if (F.cull[q] >= 0 && !faceVisible(F, idx, ix, iy, iz, F.cull[q])) {
                        continue;
                    }
                    meilleure = F;
                    mq = q;
                    mt = t;
                    mtex = c;
                    bord = uu < 0.06 || uu > 0.94 || vv < 0.06 || vv > 0.94;
                }
            }
            if (meilleure == null) {
                return false;
            }
            int o = mq * Forme.G;
            double a;
            if (meilleure.estFluide) {
                a = meilleure.fluide == 1 ? 0.7 : 1.0;
            } else {
                a = fo.plein ? 1.0 : (mtex >>> 24) / 255.0;
            }
            int c = meilleure.teinte[mq] ? teinter(mtex, meilleure.couleurTeinte) : mtex;
            double k = meilleure.g[o + 14];
            if (meilleure.repli && bord) {
                // Forme de repli (coffre, lit… rendus par entité côté client) : arêtes marquées, pour
                // qu'un coffre en planches se détache d'un mur en planches.
                k *= 0.5;
            }
            double hx = cx + dx * mt, hy = cy + dy * mt, hz = cz + dz * mt;
            if (lumiere) {
                k *= clarte(idx, ix, iy, iz, hx + meilleure.g[o + 9] * 0.02, hy + meilleure.g[o + 10] * 0.02,
                        hz + meilleure.g[o + 11] * 0.02);
            }
            if (occlusion && meilleure.axe[mq] >= 0) {
                k *= occlusion(ix, iy, iz, meilleure.axe[mq], hx, hy, hz);
            }
            composer(c, a, k);
            if (aa >= 0.985) {
                return true;
            }
            lo = mt + 1e-7;
        }
        return false;
    }

    private void composer(int c, double a, double k) {
        double w = (1 - aa) * a;
        ar += ((c >> 16) & 0xFF) * k * w;
        ag += ((c >> 8) & 0xFF) * k * w;
        ab += (c & 0xFF) * k * w;
        aa += w;
    }

    private static int teinter(int c, int t) {
        int r = ((c >> 16) & 0xFF) * ((t >> 16) & 0xFF) / 255;
        int g = ((c >> 8) & 0xFF) * ((t >> 8) & 0xFF) / 255;
        int b = (c & 0xFF) * (t & 0xFF) / 255;
        return (c & 0xFF000000) | r << 16 | g << 8 | b;
    }

    /**
     * Texel du quad {@code q} en (u, v) ; en {@code uvlock} (ou pour une tranche), projection de la
     * position sur la face — ce que fait le client pour une face pleine verrouillée.
     */
    private static int echantillon(Forme F, int q, double u, double v, double lx, double ly, double lz, boolean projete) {
        Png.Image im = F.tex[q];
        if (im == null) {
            // Sans texture : la couleur de carte ; un bloc qui n'en a pas (verre, barrière) reste un voile.
            return F.carte == 0 ? 0x50C8DCE8 : 0xFF000000 | F.carte;
        }
        double s, t;
        if (projete) {
            int o = q * Forme.G;
            double nx = F.g[o + 9], ny = F.g[o + 10], nz = F.g[o + 11];
            if (Math.abs(ny) >= Math.abs(nx) && Math.abs(ny) >= Math.abs(nz)) {
                s = lx;
                t = ny > 0 ? lz : 1 - lz;
            } else if (Math.abs(nz) >= Math.abs(nx)) {
                s = nz > 0 ? lx : 1 - lx;
                t = 1 - ly;
            } else {
                s = nx > 0 ? 1 - lz : lz;
                t = 1 - ly;
            }
        } else {
            double uu = u, vv = v;
            switch (F.rot[q]) {
                case 1 -> {
                    uu = v;
                    vv = 1 - u;
                }
                case 2 -> {
                    uu = 1 - u;
                    vv = 1 - v;
                }
                case 3 -> {
                    uu = 1 - v;
                    vv = u;
                }
                default -> { }
            }
            int o = q * 4;
            s = F.uv[o] + uu * (F.uv[o + 2] - F.uv[o]);
            t = F.uv[o + 1] + vv * (F.uv[o + 3] - F.uv[o + 1]);
        }
        int x = (int) Math.floor(s * im.w());
        int y = (int) Math.floor(t * im.h());
        x = x < 0 ? 0 : x >= im.w() ? im.w() - 1 : x;
        y = y < 0 ? 0 : y >= im.h() ? im.h() - 1 : y;
        return im.argb()[y * im.w() + x];
    }

    private boolean dansVisible(int x, int y, int z) {
        return x >= vx0 && x <= vx1 && y >= vy0 && y <= vy1 && z >= vz0 && z <= vz1;
    }

    /** {@code Block.shouldRenderFace} du jeu (verre contre verre…), en cache par paire d'états. */
    private boolean faceVisible(Forme F, int idx, int ix, int iy, int iz, int dir) {
        int[] d = Forme.DIR[dir];
        int x = ix + d[0], y = iy + d[1], z = iz + d[2];
        if (!dansVisible(x, y, z)) {
            return true;
        }
        int j = (y * nz + z) * nx + x;
        if (F.estFluide) {
            Forme v = formes[cel[j]];
            return v.fluide != F.fluide && !v.plein;
        }
        long cle = ((long) cel[idx] << 20 | (long) cel[j] << 3 | dir);
        byte b = faces.get(cle);
        if (b < 0) {
            BlockState s = etats[cel[idx]];
            BlockState n = etats[cel[j]];
            b = (byte) (Block.shouldRenderFace(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, s, n, DIRS[dir]) ? 1 : 0);
            faces.put(cle, b);
        }
        return b == 1;
    }

    /**
     * Niveau de lumière vu par la face : celui de la cellule devant elle (la lumière d'un bloc opaque
     * vaut 0). Hors de la boîte visible, ou contre une coupe, on éclaire franchement.
     */
    private double clarte(int idx, int ix, int iy, int iz, double hx, double hy, double hz) {
        int x = ix + (int) Math.floor(hx), y = iy + (int) Math.floor(hy), z = iz + (int) Math.floor(hz);
        int l;
        if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) {
            l = 15;
        } else {
            int j = (y * nz + z) * nx + x;
            boolean opaque = formes[cel[j]].plein;
            if (!dansVisible(x, y, z) && opaque) {
                l = 15;
            } else if (opaque) {
                l = niveau(idx);
                if (formes[cel[idx]].plein) {
                    l = 15;
                }
            } else {
                l = niveau(j);
            }
        }
        double v = 1 - l / 15.0;
        return 0.2 + 0.8 * (1 - v * v * v);
    }

    private int niveau(int j) {
        int b = lum[j] & 0xFF;
        return Math.max(b >> 4, b & 15);
    }

    private boolean solide(int x, int y, int z) {
        return dansVisible(x, y, z) && formes[cel[(y * nz + z) * nx + x]].plein;
    }

    /** Occlusion ambiante « à la Minecraft » : aux quatre coins de la face, voisins pleins comptés, puis bilinéaire. */
    private double occlusion(int ix, int iy, int iz, int dir, double hx, double hy, double hz) {
        int[] d = Forme.DIR[dir];
        int ox = ix + d[0], oy = iy + d[1], oz = iz + d[2];
        int ka = d[0] != 0 ? 1 : 0;
        int kb = d[2] != 0 ? 1 : 2;
        double la = ka == 0 ? hx : hy;
        double lb = kb == 1 ? hy : hz;
        la = clamp01(la);
        lb = clamp01(lb);
        double somme = 0;
        for (int sa = -1; sa <= 1; sa += 2) {
            for (int sb = -1; sb <= 1; sb += 2) {
                int ax = ka == 0 ? sa : 0, ay = ka == 1 ? sa : 0;
                int bx = 0, by = kb == 1 ? sb : 0, bz = kb == 2 ? sb : 0;
                boolean s1 = solide(ox + ax, oy + ay, oz);
                boolean s2 = solide(ox + bx, oy + by, oz + bz);
                boolean c = solide(ox + ax + bx, oy + ay + by, oz + bz);
                int v = s1 && s2 ? 0 : 3 - ((s1 ? 1 : 0) + (s2 ? 1 : 0) + (c ? 1 : 0));
                double w = (sa > 0 ? la : 1 - la) * (sb > 0 ? lb : 1 - lb);
                somme += v * w;
            }
        }
        return 0.55 + 0.15 * somme;
    }
}
