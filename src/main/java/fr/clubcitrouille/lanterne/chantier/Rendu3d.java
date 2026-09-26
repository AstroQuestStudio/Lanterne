package fr.clubcitrouille.lanterne.chantier;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;

/**
 * Le rendu 3D texturé d'une zone : isométrique, perspective (caméra libre, y compris dans une salle),
 * coupes. Pour « voir » une construction sans client ni BlueMap.
 *
 * <h2>Deux temps, deux fils</h2>
 *
 * <p><b>Copie</b>, sur le thread serveur, section de chunk par section de chunk sous le budget du
 * chantier : l'état et la lumière (bloc, ciel) de chaque bloc de la zone élargie d'un bloc (pour
 * l'éclairage et les faces cachées du bord), dans une palette. Rien d'autre ne touche au monde.
 *
 * <p><b>Rastérisation</b> ({@link #conclure}), sur un fil à part de basse priorité — jamais le fil des
 * journaux : un rendu de quelques secondes ne doit pas retarder l'écriture d'un journal d'annulation.
 * Les modèles et textures viennent d'un jar client ({@link Modeles}), les rayons de {@link Rayons}.
 *
 * <h2>Vues</h2>
 *
 * <ul>
 *   <li><b>iso</b> : orthographique, depuis un des quatre coins ({@code angle} nord-est, nord-ouest,
 *       sud-est, sud-ouest), {@code inclinaison} 30° par défaut, cadrée sur la boîte.</li>
 *   <li><b>perspective</b> : {@code camera} [x, y, z] (l'œil), {@code lacet}/{@code tangage} à la
 *       manière du jeu (lacet 0 = sud, 90 = ouest, 180 = nord, -90 = est ; tangage positif = vers le
 *       bas) ou {@code regarder} [x, y, z] ; {@code fov} vertical ; {@code distance_max}.</li>
 *   <li><b>coupes</b> : {@code coupe_y} (tout ce qui est au-dessus disparaît), {@code coupe_plan}
 *       ({@code axe}, {@code valeur}, {@code garder} moins|plus), {@code coupe_devant} (tout ce qui est
 *       à moins de N blocs de l'œil, le long de la visée, disparaît ; les blocs pleins tranchés
 *       montrent leur matière, assombrie).</li>
 * </ul>
 */
final class Rendu3d extends Travail {
    /** Le fil de rastérisation : un seul, basse priorité, démon. */
    static final ExecutorService FIL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Lanterne-Chantier-Rendu3d");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private final JsonObject corps;
    /** Boîte demandée, boîte copiée (élargie d'un bloc), boîte visible (après coupes). */
    private final Boite boite;
    private final Boite copie;
    private final Boite visible;
    private final boolean perspective;
    private final int W, H, lissage;
    // Libérés dès l'image faite : un travail fini reste connu de l'Atelier un moment, pas sa copie.
    private char[] cel;
    private byte[] lum;
    private final IdentityHashMap<BlockState, Integer> indices = new IdentityHashMap<>();
    private final List<BlockState> etats = new ArrayList<>();
    private final List<Integer> cartes = new ArrayList<>();
    private final List<Long> chunks;
    private int c;
    /** Couche en cours de copie dans la colonne {@code c} : l'échéance est vérifiée à chaque couche (≤ 256 blocs). */
    private int couche;
    private double[] camera;
    private double[] visee;
    private String orientation = "";

    Rendu3d(ServerLevel level, JsonObject corps, long max) {
        super("rendu3d", level);
        this.corps = corps;
        String vue = Json.texte(corps, "vue", corps.has("camera") ? "perspective" : "iso");
        perspective = switch (vue) {
            case "perspective" -> true;
            case "iso" -> false;
            default -> throw new Refus(400, "vue inconnue « " + vue + " » (iso | perspective)");
        };
        W = Json.entier(corps, "largeur", 1280, 16, 4096);
        H = Json.entier(corps, "hauteur", 720, 16, 4096);
        lissage = Json.entier(corps, "lissage", 1, 1, 3);
        if ((long) W * H * lissage * lissage > 4096L * 4096L) {
            throw new Refus(413, "plus de 16 millions de rayons : baisse la taille ou le lissage");
        }
        if (perspective) {
            camera = Json.reels(corps, "camera");
            if (camera == null) {
                throw new Refus(400, "vue perspective : « camera » [x, y, z] requise (position de l'œil)");
            }
            double[] cible = Json.reels(corps, "regarder");
            if (cible != null) {
                visee = new double[] {cible[0] - camera[0], cible[1] - camera[1], cible[2] - camera[2]};
                if (visee[0] * visee[0] + visee[1] * visee[1] + visee[2] * visee[2] < 1e-6) {
                    throw new Refus(400, "« regarder » confondu avec la caméra");
                }
            } else {
                double lacet = Math.toRadians(Json.reel(corps, "lacet", 0, -3600, 3600));
                double tangage = Math.toRadians(Json.reel(corps, "tangage", 0, -90, 90));
                visee = new double[] {-Math.sin(lacet) * Math.cos(tangage), -Math.sin(tangage), Math.cos(lacet) * Math.cos(tangage)};
            }
        }
        int dist = Json.entier(corps, "distance_max", 64, 4, 512);
        if (corps.has("de") || corps.has("a")) {
            boite = Boite.lire(corps, level, max);
        } else if (perspective) {
            int cx = (int) Math.floor(camera[0]), cy = (int) Math.floor(camera[1]), cz = (int) Math.floor(camera[2]);
            boite = Boite.de(cx - dist, Math.max(level.getMinY(), cy - dist), cz - dist,
                    cx + dist, Math.min(level.getMaxY(), cy + dist), cz + dist);
            boite.verifier(level, max);
        } else {
            throw new Refus(400, "vue iso : « de » et « a » requis (la boîte à montrer)");
        }
        copie = Boite.de(boite.minX() - 1, Math.max(level.getMinY(), boite.minY() - 1), boite.minZ() - 1,
                boite.maxX() + 1, Math.min(level.getMaxY(), boite.maxY() + 1), boite.maxZ() + 1);
        if (copie.volume() > Integer.MAX_VALUE - 16) {
            throw new Refus(413, "zone de " + copie.volume() + " blocs : trop grande pour une copie");
        }
        visible = couper(boite, corps);
        cel = new char[(int) copie.volume()];
        lum = new byte[(int) copie.volume()];
        chunks = copie.chunks();
        couche = copie.minY();
    }

    private static Boite couper(Boite b, JsonObject corps) {
        int x0 = b.minX(), y0 = b.minY(), z0 = b.minZ(), x1 = b.maxX(), y1 = b.maxY(), z1 = b.maxZ();
        if (corps.has("coupe_y")) {
            y1 = Math.min(y1, Json.entier(corps, "coupe_y", 0, -4096, 4096));
        }
        if (corps.has("coupe_plan")) {
            if (!corps.get("coupe_plan").isJsonObject()) {
                throw new Refus(400, "« coupe_plan » : {\"axe\": \"x|y|z\", \"valeur\": n, \"garder\": \"moins|plus\"}");
            }
            JsonObject p = corps.getAsJsonObject("coupe_plan");
            String axe = Json.texte(p, "axe", "x");
            int v = Json.entier(p, "valeur", 0, -30_000_000, 30_000_000);
            boolean moins = switch (Json.texte(p, "garder", "moins")) {
                case "moins" -> true;
                case "plus" -> false;
                default -> throw new Refus(400, "coupe_plan.garder : « moins » (garder ≤ valeur) ou « plus » (garder ≥ valeur)");
            };
            switch (axe) {
                case "x" -> { if (moins) x1 = Math.min(x1, v); else x0 = Math.max(x0, v); }
                case "y" -> { if (moins) y1 = Math.min(y1, v); else y0 = Math.max(y0, v); }
                case "z" -> { if (moins) z1 = Math.min(z1, v); else z0 = Math.max(z0, v); }
                default -> throw new Refus(400, "coupe_plan.axe : x, y ou z");
            }
        }
        if (x0 > x1 || y0 > y1 || z0 > z1) {
            throw new Refus(400, "la coupe ne laisse rien de la boîte");
        }
        return new Boite(x0, y0, z0, x1, y1, z1);
    }

    @Override
    boolean avancer(long echeance) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        LayerLightEventListener bl = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        LayerLightEventListener ciel = level.getLightEngine().getLayerListener(LightLayer.SKY);
        while (c < chunks.size()) {
            long k = chunks.get(c);
            for (int j = c + 1; j < Math.min(chunks.size(), c + 7); j++) {
                demander(level, chunks.get(j));
            }
            LevelChunk ch = pret(level, k);
            if (ch == null) {
                return false;
            }
            while (couche <= copie.maxY()) {
                if (System.nanoTime() >= echeance) {
                    return false;
                }
                copier(ch, k, couche, p, bl, ciel);
                couche++;
            }
            couche = copie.minY();
            c++;
        }
        return true;
    }

    /**
     * Une couche d'une colonne de chunk (16×16 au plus) : états en palette, lumière bloc et ciel. Grain
     * fin (quelques dizaines de µs) pour tenir l'échéance au plus près. Les pas longs vus à froid sur un
     * seul cœur (jusqu'à 340 ms, juste après le démarrage) étaient du temps mur SANS temps CPU du fil
     * serveur — préempté par la JIT et le chargement des chunks —, mesuré par couche, pas la copie.
     */
    private void copier(LevelChunk ch, long k, int y, BlockPos.MutableBlockPos p, LayerLightEventListener bl,
                        LayerLightEventListener ciel) {
        int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
        int x0 = Math.max(copie.minX(), cx << 4), x1 = Math.min(copie.maxX(), (cx << 4) + 15);
        int z0 = Math.max(copie.minZ(), cz << 4), z1 = Math.min(copie.maxZ(), (cz << 4) + 15);
        int s = y >> 4;
        int idx = ch.getSectionIndexFromSectionY(s);
        LevelChunkSection sec = idx >= 0 && idx < ch.getSections().length ? ch.getSection(idx) : null;
        boolean vide = sec == null || sec.hasOnlyAir();
        SectionPos sp = SectionPos.of(cx, s, cz);
        DataLayer lb = bl.getDataLayerData(sp);
        DataLayer lc = ciel.getDataLayerData(sp);
        int sx = copie.sx(), sz = copie.sz();
        for (int z = z0; z <= z1; z++) {
            int i = ((y - copie.minY()) * sz + (z - copie.minZ())) * sx + (x0 - copie.minX());
            for (int x = x0; x <= x1; x++, i++) {
                BlockState st = vide ? Blocks.AIR.defaultBlockState() : sec.getBlockState(x & 15, y & 15, z & 15);
                Integer n = indices.get(st);
                if (n == null) {
                    n = etats.size();
                    if (n > 65535) {
                        throw new Refus(413, "plus de 65 536 états de blocs différents dans la zone");
                    }
                    indices.put(st, n);
                    etats.add(st);
                    cartes.add(st.getMapColor(level, p.set(x, y, z)).col);
                }
                cel[i] = (char) n.intValue();
                int b = lb != null ? lb.get(x & 15, y & 15, z & 15) : 0;
                int l = lc != null ? lc.get(x & 15, y & 15, z & 15) : ciel.getLightValue(p.set(x, y, z));
                lum[i] = (byte) (b << 4 | (l & 15));
            }
        }
    }

    @Override
    JsonObject avancement() {
        JsonObject o = new JsonObject();
        o.addProperty("colonnes_copiees", c);
        o.addProperty("colonnes", chunks.size());
        return o;
    }

    @Override
    Resultat conclure() throws IOException {
        if (interrompu != null) {
            throw new Refus(503, interrompu);
        }
        long t0 = System.nanoTime();
        String note = null;
        Path jar = jarClient();
        Modeles m = jar != null ? Modeles.pour(jar) : null;
        if (m == null) {
            note = jar == null ? "jar client introuvable (réglage rendu_jar_client, ou bluemap/minecraft-client-*.jar) : couleurs de carte"
                    : "jar client illisible : couleurs de carte";
        }
        Forme[] formes = new Forme[etats.size()];
        BlockState[] es = etats.toArray(new BlockState[0]);
        for (int i = 0; i < formes.length; i++) {
            formes[i] = m != null ? m.forme(es[i], cartes.get(i)) : Modeles.sansTextures(es[i], cartes.get(i));
        }
        long t1 = System.nanoTime();
        Rayons ry = new Rayons(copie.minX(), copie.minY(), copie.minZ(), copie.sx(), copie.sy(), copie.sz(), cel, lum,
                formes, es, new int[] {visible.minX(), visible.minY(), visible.minZ(), visible.maxX(), visible.maxY(), visible.maxZ()});
        ry.lumiere = Json.bool(corps, "lumiere", true);
        ry.occlusion = Json.bool(corps, "occlusion", true);
        String fond = Json.texte(corps, "fond", perspective ? "ciel" : "d8e4ec");
        if ("ciel".equals(fond)) {
            ry.ciel = true;
        } else {
            try {
                ry.fond = "transparent".equals(fond) ? 0 : 0xFF000000 | Integer.parseInt(fond.replace("#", ""), 16);
            } catch (NumberFormatException e) {
                throw new Refus(400, "fond : couleur hexa, « transparent » ou « ciel »");
            }
        }
        double coupe = corps.has("coupe_devant") ? Json.reel(corps, "coupe_devant", 0, 0, 4096) : Double.NaN;
        String angle = null;
        if (perspective) {
            ry.perspective(camera, visee, Json.reel(corps, "fov", 70, 10, 150), W, H,
                    Json.entier(corps, "distance_max", 64, 4, 512), coupe);
        } else {
            angle = Json.texte(corps, "angle", "sud-est");
            int ex, sz;
            switch (angle) {
                case "nord-est" -> { ex = 1; sz = -1; }
                case "nord-ouest" -> { ex = -1; sz = -1; }
                case "sud-est" -> { ex = 1; sz = 1; }
                case "sud-ouest" -> { ex = -1; sz = 1; }
                default -> throw new Refus(400, "angle : nord-est | nord-ouest | sud-est | sud-ouest (où se tient l'observateur)");
            }
            double inc = Math.toRadians(Json.reel(corps, "inclinaison", 30, 0, 90));
            visee = new double[] {-ex * Math.cos(inc) / Math.sqrt(2), -Math.sin(inc), -sz * Math.cos(inc) / Math.sqrt(2)};
            ry.orthographique(visee, W, H, coupe);
        }
        orientation = orientation(visee);
        // Le rendu travaille à la taille finale ; le lissage multiplie les rayons par pixel.
        int[] image = ry.rendre(W, H, lissage);
        cel = null;
        lum = null;
        long t2 = System.nanoTime();
        byte[] png = Rendu.png(image, W, H);
        long t3 = System.nanoTime();

        JsonObject o = mesures();
        o.addProperty("vue", perspective ? "perspective" : "iso");
        if (angle != null) {
            o.addProperty("angle", angle);
        }
        o.addProperty("largeur_px", W);
        o.addProperty("hauteur_px", H);
        o.addProperty("lissage", lissage);
        o.add("boite", boite.json());
        o.add("visible", visible.json());
        if (perspective) {
            JsonArray cam = new JsonArray();
            for (double v : camera) {
                cam.add(v);
            }
            o.add("camera", cam);
            double lacet = Math.toDegrees(Math.atan2(-visee[0], visee[2]));
            double h = Math.sqrt(visee[0] * visee[0] + visee[2] * visee[2]);
            double tangage = Math.toDegrees(Math.atan2(-visee[1], h));
            o.addProperty("lacet", Math.round(lacet * 10) / 10.0);
            o.addProperty("tangage", Math.round(tangage * 10) / 10.0);
        }
        o.addProperty("orientation", orientation);
        o.addProperty("textures", m != null ? m.jar().toString() : "aucune");
        if (note != null) {
            o.addProperty("note", note);
        }
        o.addProperty("etats_distincts", es.length);
        if (m != null) {
            o.addProperty("textures_en_cache", m.texturesChargees());
            JsonArray r = new JsonArray();
            m.replis.stream().limit(24).forEach(r::add);
            o.add("blocs_sans_modele", r);
        }
        o.addProperty("copie_blocs", copie.volume());
        o.addProperty("formes_ms", (t1 - t0) / 1_000_000L);
        o.addProperty("rayons_ms", (t2 - t1) / 1_000_000L);
        o.addProperty("png_ms", (t3 - t2) / 1_000_000L);
        o.addProperty("rayons", ry.rayons);
        o.addProperty("cellules_par_rayon", ry.rayons == 0 ? 0 : Math.round(10.0 * ry.cellules / ry.rayons) / 10.0);
        return new Resultat(200, o, png);
    }

    /** Où pointent la droite et le fond de l'image, en points cardinaux. */
    private static String orientation(double[] d) {
        double rx = -d[2], rz = d[0];
        return "droite de l'image vers le " + boussole(rx, rz) + ", regard vers le " + boussole(d[0], d[2])
                + (d[1] < -0.2 ? " (plongeant)" : d[1] > 0.2 ? " (vers le haut)" : "");
    }

    private static String boussole(double x, double z) {
        if (x * x + z * z < 1e-9) {
            return "bas";
        }
        String[] noms = {"sud", "sud-ouest", "ouest", "nord-ouest", "nord", "nord-est", "est", "sud-est"};
        double a = Math.toDegrees(Math.atan2(-x, z)); // 0 = sud, 90 = ouest, comme le lacet du jeu
        return noms[Math.floorMod((int) Math.round(a / 45.0), 8)];
    }

    // ------------------------------------------------------------------------------------------------
    // Le jar client

    private static final Pattern DONNEES_BLUEMAP = Pattern.compile("(?m)^\\s*data\\s*:\\s*\"([^\"]+)\"");

    /**
     * Le jar client à lire : le réglage {@code rendu_jar_client} s'il est donné, sinon celui que BlueMap
     * a téléchargé ({@code <data>/minecraft-client-<version>.jar}, {@code data} lu dans
     * {@code config/bluemap/core.conf}, « bluemap » par défaut). Chemins relatifs au dossier du serveur.
     */
    static @Nullable Path jarClient() {
        String r = Chantier.RENDU_JAR_CLIENT.get();
        if (r != null && !r.isBlank()) {
            Path p = Path.of(r.trim());
            return Files.isRegularFile(p) ? p : null;
        }
        List<Path> dossiers = new ArrayList<>();
        try {
            Path conf = Path.of("config", "bluemap", "core.conf");
            if (Files.isRegularFile(conf)) {
                Matcher mt = DONNEES_BLUEMAP.matcher(Files.readString(conf));
                if (mt.find()) {
                    dossiers.add(Path.of(mt.group(1)));
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // core.conf illisible : dossier par défaut
        }
        dossiers.add(Path.of("bluemap"));
        for (Path d : dossiers) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            Path meilleur = null;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "minecraft-client-*.jar")) {
                for (Path p : ds) {
                    if (meilleur == null || p.getFileName().toString().compareTo(meilleur.getFileName().toString()) > 0) {
                        meilleur = p;
                    }
                }
            } catch (IOException ignored) {
                // dossier illisible
            }
            if (meilleur != null) {
                return meilleur;
            }
        }
        return null;
    }
}
