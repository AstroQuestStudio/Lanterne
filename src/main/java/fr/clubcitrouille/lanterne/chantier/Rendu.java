package fr.clubcitrouille.lanterne.chantier;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;

/**
 * Le rendu d'une boîte en PNG, côté serveur, pour vérifier une construction sans client.
 *
 * <p>Chaque pixel est le premier bloc visible depuis l'observateur, dans la couleur de carte du jeu
 * ({@link MapColor} — celle des cartes en papier, que tout joueur sait lire). Un bloc sans couleur
 * de carte (air, verre, barrière…) est traversé : les fenêtres se voient comme des trous.
 *
 * <ul>
 *   <li><b>dessus</b> : nord en haut, ouest à gauche. Ombrage des cartes vanilla (plus haut que le
 *       voisin nord = plus clair), eau assombrie par sa profondeur. Pour un plan d'étage, abaisser le
 *       haut de la boîte juste au-dessus du plancher.</li>
 *   <li><b>nord / sud / est / ouest</b> : élévation vue DEPUIS ce côté (« nord » = l'observateur est
 *       au nord et regarde vers le sud, donc l'est est à sa gauche). Le haut de l'image est le haut de
 *       la boîte ; plus un bloc est loin, plus il est sombre ; {@code profondeur} borne la vue.</li>
 * </ul>
 *
 * <p>Le chunk est l'unité de travail : les chunks sont lus du plus proche au plus lointain de
 * l'observateur, et un pixel déjà trouvé n'est plus cherché — l'essentiel du volume n'est jamais lu.
 * L'encodage PNG (écrit à la main, sans AWT : un serveur Linux sans bibliothèques graphiques doit
 * pouvoir rendre) se fait hors du thread serveur.
 */
final class Rendu extends Travail {
    enum Vue { DESSUS, NORD, SUD, EST, OUEST }

    private final Boite boite;
    private final Vue vue;
    private final int profondeur;
    private final int echelle;
    private final int grille;
    private final int fond;
    private final int w;
    private final int h;
    /** Profondeur du bloc vu, par pixel (MAX_VALUE = rien). */
    private final int[] prof;
    private final int[] couleur;
    /** Dessus : y du bloc vu, et profondeur d'eau au-dessus du fond. */
    private final int[] hauteur;
    private final byte[] eau;
    private final BlockState[] vu;
    private final List<Long> chunks;
    private int c;

    Rendu(ServerLevel level, Boite boite, Vue vue, int profondeur, int echelle, int grille, int fond) {
        super("rendu", level);
        this.boite = boite;
        this.vue = vue;
        this.grille = grille;
        this.fond = fond;
        switch (vue) {
            case DESSUS -> { w = boite.sx(); h = boite.sz(); }
            case NORD, SUD -> { w = boite.sx(); h = boite.sy(); }
            default -> { w = boite.sz(); h = boite.sy(); }
        }
        int etendue = switch (vue) {
            case DESSUS -> boite.sy();
            case NORD, SUD -> boite.sz();
            default -> boite.sx();
        };
        this.profondeur = profondeur <= 0 ? etendue : Math.min(profondeur, etendue);
        if ((long) w * h > 2048L * 2048L) {
            throw new Refus(413, "rendu de " + w + "×" + h + " blocs : 2048×2048 au plus");
        }
        this.echelle = echelle > 0 ? echelle : Math.max(1, Math.min(16, 1024 / Math.max(w, h)));
        if ((long) w * this.echelle > 8192 || (long) h * this.echelle > 8192) {
            throw new Refus(413, "image de plus de 8192 pixels de côté : baisse l'échelle");
        }
        int n = w * h;
        prof = new int[n];
        Arrays.fill(prof, Integer.MAX_VALUE);
        couleur = new int[n];
        hauteur = new int[n];
        eau = new byte[n];
        vu = new BlockState[n];
        List<Long> tri = new ArrayList<>(boite.chunks());
        // Du plus proche au plus lointain de l'observateur : c'est ce qui permet de sauter les pixels trouvés.
        switch (vue) {
            case NORD -> tri.sort((a, b) -> Integer.compare(ChunkPos.getZ(a), ChunkPos.getZ(b)));
            case SUD -> tri.sort((a, b) -> Integer.compare(ChunkPos.getZ(b), ChunkPos.getZ(a)));
            case OUEST -> tri.sort((a, b) -> Integer.compare(ChunkPos.getX(a), ChunkPos.getX(b)));
            case EST -> tri.sort((a, b) -> Integer.compare(ChunkPos.getX(b), ChunkPos.getX(a)));
            default -> { }
        }
        this.chunks = tri;
    }

    static Vue vue(String v) {
        return switch (v) {
            case "dessus" -> Vue.DESSUS;
            case "nord" -> Vue.NORD;
            case "sud" -> Vue.SUD;
            case "est" -> Vue.EST;
            case "ouest" -> Vue.OUEST;
            default -> throw new Refus(400, "vue inconnue « " + v + " » (dessus | nord | sud | est | ouest)");
        };
    }

    @Override
    boolean avancer(long echeance) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        while (c < chunks.size()) {
            if (System.nanoTime() >= echeance) {
                return false;
            }
            long k = chunks.get(c);
            for (int j = c + 1; j < Math.min(chunks.size(), c + 7); j++) {
                demander(level, chunks.get(j));
            }
            LevelChunk ch = pret(level, k);
            if (ch == null) {
                return false;
            }
            int x0 = Math.max(boite.minX(), ChunkPos.getX(k) << 4);
            int x1 = Math.min(boite.maxX(), (ChunkPos.getX(k) << 4) + 15);
            int z0 = Math.max(boite.minZ(), ChunkPos.getZ(k) << 4);
            int z1 = Math.min(boite.maxZ(), (ChunkPos.getZ(k) << 4) + 15);
            if (vue == Vue.DESSUS) {
                dessus(ch, p, x0, x1, z0, z1);
            } else {
                elevation(ch, p, x0, x1, z0, z1);
            }
            c++;
        }
        return true;
    }

    private void dessus(LevelChunk ch, BlockPos.MutableBlockPos p, int x0, int x1, int z0, int z1) {
        int bas = Math.max(boite.minY(), boite.maxY() - profondeur + 1);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                int i = (z - boite.minZ()) * w + (x - boite.minX());
                for (int y = boite.maxY(); y >= bas; y--) {
                    BlockState s = ch.getBlockState(p.set(x, y, z));
                    if (s.isAir()) {
                        continue;
                    }
                    MapColor mc = s.getMapColor(level, p);
                    if (mc == MapColor.NONE) {
                        continue;
                    }
                    if (s.getFluidState().is(Fluids.WATER) && mc == MapColor.WATER) {
                        int d = 0;
                        int yy = y;
                        while (yy > bas && d < 15 && ch.getBlockState(p.set(x, yy - 1, z)).getFluidState().is(Fluids.WATER)) {
                            yy--;
                            d++;
                        }
                        eau[i] = (byte) (d + 1);
                    }
                    prof[i] = boite.maxY() - y;
                    hauteur[i] = y;
                    couleur[i] = mc.col;
                    vu[i] = s;
                    break;
                }
            }
        }
    }

    private void elevation(LevelChunk ch, BlockPos.MutableBlockPos p, int x0, int x1, int z0, int z1) {
        boolean selonZ = vue == Vue.NORD || vue == Vue.SUD;
        // d : profondeur depuis la face de la boîte tournée vers l'observateur.
        int dMin;
        int dMax;
        if (selonZ) {
            dMin = vue == Vue.NORD ? z0 - boite.minZ() : boite.maxZ() - z1;
            dMax = vue == Vue.NORD ? z1 - boite.minZ() : boite.maxZ() - z0;
        } else {
            dMin = vue == Vue.OUEST ? x0 - boite.minX() : boite.maxX() - x1;
            dMax = vue == Vue.OUEST ? x1 - boite.minX() : boite.maxX() - x0;
        }
        dMax = Math.min(dMax, profondeur - 1);
        int uMin = selonZ ? x0 : z0;
        int uMax = selonZ ? x1 : z1;
        for (int d = dMin; d <= dMax; d++) {
            int monde = switch (vue) {
                case NORD -> boite.minZ() + d;
                case SUD -> boite.maxZ() - d;
                case OUEST -> boite.minX() + d;
                default -> boite.maxX() - d;
            };
            for (int a = uMin; a <= uMax; a++) {
                int u = switch (vue) {
                    case NORD -> boite.maxX() - a;
                    case SUD -> a - boite.minX();
                    case OUEST -> a - boite.minZ();
                    default -> boite.maxZ() - a;
                };
                for (int y = boite.maxY(); y >= boite.minY(); y--) {
                    int i = (boite.maxY() - y) * w + u;
                    if (prof[i] <= d) {
                        continue;
                    }
                    if (selonZ) {
                        p.set(a, y, monde);
                    } else {
                        p.set(monde, y, a);
                    }
                    BlockState s = ch.getBlockState(p);
                    if (s.isAir()) {
                        continue;
                    }
                    MapColor mc = s.getMapColor(level, p);
                    if (mc == MapColor.NONE) {
                        continue;
                    }
                    prof[i] = d;
                    couleur[i] = mc.col;
                    vu[i] = s;
                }
            }
        }
    }

    @Override
    Resultat conclure() throws IOException {
        int n = w * h;
        int[] argb = new int[n];
        for (int i = 0; i < n; i++) {
            if (prof[i] == Integer.MAX_VALUE) {
                argb[i] = fond;
                continue;
            }
            int mod;
            if (vue == Vue.DESSUS) {
                int u = i % w;
                int v = i / w;
                if (eau[i] > 0) {
                    double d = (eau[i] - 1) * 0.1 + ((u + v) & 1) * 0.2;
                    mod = d < 0.5 ? 255 : d > 0.9 ? 180 : 220;
                } else {
                    int nord = v > 0 && prof[i - w] != Integer.MAX_VALUE ? hauteur[i - w] : hauteur[i];
                    mod = hauteur[i] > nord ? 255 : hauteur[i] < nord ? 180 : 220;
                }
            } else {
                mod = 255 - (int) (130.0 * prof[i] / Math.max(1, profondeur - 1));
            }
            argb[i] = 0xFF000000 | module(couleur[i], mod);
        }
        int W = w * echelle;
        int H = h * echelle;
        int[] image = new int[W * H];
        for (int v = 0; v < H; v++) {
            for (int u = 0; u < W; u++) {
                image[v * W + u] = argb[(v / echelle) * w + (u / echelle)];
            }
        }
        if (grille > 0) {
            tracerGrille(image, W, H);
        }
        byte[] png = png(image, W, H);
        JsonObject o = mesures();
        o.add("boite", boite.json());
        o.addProperty("vue", vue.name().toLowerCase(Locale.ROOT));
        o.addProperty("largeur_px", W);
        o.addProperty("hauteur_px", H);
        o.addProperty("echelle", echelle);
        o.addProperty("profondeur", profondeur);
        o.addProperty("orientation", switch (vue) {
            case DESSUS -> "nord en haut, ouest à gauche ; x croît vers la droite, z vers le bas";
            case NORD -> "vu depuis le nord : est à gauche (x décroît vers la droite), haut = y max";
            case SUD -> "vu depuis le sud : ouest à gauche (x croît vers la droite), haut = y max";
            case EST -> "vu depuis l'est : sud à gauche (z décroît vers la droite), haut = y max";
            case OUEST -> "vu depuis l'ouest : nord à gauche (z croît vers la droite), haut = y max";
        });
        o.add("legende", legende());
        return new Resultat(200, o, png);
    }

    /** Les blocs les plus visibles et leur couleur, pour lire l'image sans deviner. */
    private JsonArray legende() {
        IdentityHashMap<BlockState, int[]> compte = new IdentityHashMap<>();
        for (BlockState s : vu) {
            if (s != null) {
                compte.computeIfAbsent(s.getBlock().defaultBlockState(), k -> new int[1])[0]++;
            }
        }
        JsonArray l = new JsonArray();
        compte.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(16)
                .forEach(e -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("bloc", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(e.getKey().getBlock()).toString());
                    o.addProperty("couleur", String.format("#%06x", e.getKey().getMapColor(level, BlockPos.ZERO).col));
                    o.addProperty("pixels_blocs", e.getValue()[0]);
                    l.add(o);
                });
        return l;
    }

    private void tracerGrille(int[] image, int W, int H) {
        // Une ligne sur chaque multiple de « grille » en coordonnées du MONDE, pour relire des positions.
        for (int u = 0; u < w; u++) {
            int monde = switch (vue) {
                case DESSUS, SUD -> boite.minX() + u;
                case NORD -> boite.maxX() - u;
                case OUEST -> boite.minZ() + u;
                case EST -> boite.maxZ() - u;
            };
            if (Math.floorMod(monde, grille) == 0) {
                for (int v = 0; v < H; v++) {
                    int i = v * W + u * echelle;
                    image[i] = assombrir(image[i]);
                }
            }
        }
        for (int v = 0; v < h; v++) {
            int monde = vue == Vue.DESSUS ? boite.minZ() + v : boite.maxY() - v;
            if (Math.floorMod(monde, grille) == 0) {
                int ligne = vue == Vue.DESSUS ? v * echelle : v * echelle + echelle - 1;
                for (int u = 0; u < W; u++) {
                    int i = ligne * W + u;
                    image[i] = assombrir(image[i]);
                }
            }
        }
    }

    private static int assombrir(int argb) {
        if ((argb >>> 24) == 0) {
            return 0x80000000;
        }
        return 0xFF000000 | module(argb & 0xFFFFFF, 140);
    }

    private static int module(int rgb, int mod) {
        int r = ((rgb >> 16) & 0xFF) * mod / 255;
        int g = ((rgb >> 8) & 0xFF) * mod / 255;
        int b = (rgb & 0xFF) * mod / 255;
        return (r << 16) | (g << 8) | b;
    }

    /** Un PNG RGBA 8 bits, filtre nul, sans AWT. */
    static byte[] png(int[] argb, int W, int H) throws IOException {
        ByteArrayOutputStream brut = new ByteArrayOutputStream(W * H * 4 / 4 + 1024);
        Deflater def = new Deflater(6);
        try (DeflaterOutputStream z = new DeflaterOutputStream(brut, def, 65536)) {
            byte[] ligne = new byte[1 + W * 4];
            for (int y = 0; y < H; y++) {
                ligne[0] = 0;
                for (int x = 0; x < W; x++) {
                    int c = argb[y * W + x];
                    int o = 1 + x * 4;
                    ligne[o] = (byte) (c >> 16);
                    ligne[o + 1] = (byte) (c >> 8);
                    ligne[o + 2] = (byte) c;
                    ligne[o + 3] = (byte) (c >>> 24);
                }
                z.write(ligne);
            }
        } finally {
            def.end();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(brut.size() + 64);
        DataOutputStream d = new DataOutputStream(out);
        d.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        DataOutputStream i = new DataOutputStream(ihdr);
        i.writeInt(W);
        i.writeInt(H);
        i.writeByte(8);
        i.writeByte(6);
        i.writeByte(0);
        i.writeByte(0);
        i.writeByte(0);
        morceau(d, "IHDR", ihdr.toByteArray());
        morceau(d, "IDAT", brut.toByteArray());
        morceau(d, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void morceau(DataOutputStream d, String type, byte[] donnees) throws IOException {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        d.writeInt(donnees.length);
        d.write(t);
        d.write(donnees);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(donnees);
        d.writeInt((int) crc.getValue());
    }
}
