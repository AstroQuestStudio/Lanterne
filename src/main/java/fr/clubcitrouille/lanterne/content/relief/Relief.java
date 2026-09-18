package fr.clubcitrouille.lanterne.content.relief;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * RELIEF : la carte 3D du serveur — notre réponse à BlueMap, écrite dans ce mod plutôt que déployée
 * à côté de lui. Lit ce qui existe déjà sur disque, colonne par colonne, et écrit des tuiles
 * binaires (hauteur + couleur réelle du bloc de surface) que le panel web charge dans un viewer 3D.
 *
 * <h2>Ce que ce module ne fait pas</h2>
 *
 * <p>Il ne génère aucun chunk — {@link fr.clubcitrouille.lanterne.lab.Pregen} s'en charge déjà, et
 * mélanger les deux aurait rendu la carte responsable d'une génération que personne n'a demandée.
 * Il ne force le chargement d'aucun chunk dans la simulation vivante : les fichiers de région sont
 * lus directement, exactement comme {@link fr.clubcitrouille.lanterne.core.ChunkDecode} le fait déjà
 * pour les lectures normales du jeu, mais ici hors du chemin du joueur.
 *
 * <h2>Le budget, et pourquoi il se mesure en millisecondes de balayage plutôt qu'en chunks</h2>
 *
 * <p>Même principe que {@link fr.clubcitrouille.lanterne.lab.Pregen#tick} : une horloge, un plafond,
 * un arrêt propre dès qu'il est dépassé — jamais un calcul entier forcé à finir dans le même tick.
 * Une région entière (1024 chunks) se traite donc sur autant de ticks qu'il faut, et le jeu ne le
 * remarque jamais.
 *
 * <h2>La reprise après redémarrage tient dans le système de fichiers, pas dans un fichier d'état</h2>
 *
 * <p>Une région n'est retraitée que si son fichier {@code .mca} est plus récent que sa tuile déjà
 * écrite. Un redémarrage du serveur ne perd donc rien et ne refait rien : soit la tuile est à jour et
 * reste ignorée, soit elle ne l'est pas et sera reprise au prochain balayage, exactement comme avant
 * l'arrêt.
 */
public final class Relief {
    private static final int REGION_CHUNKS = 32;
    private static final int REGION_BLOCKS = REGION_CHUNKS * 16;
    private static final short UNEXPLORED = Short.MIN_VALUE;
    private static final byte[] MAGIC = {'L', 'R', 'E', 'L', 1};

    private static final Map<String, int[]> COLORS = loadColors();
    private static final int[] FALLBACK_COLOR = {130, 130, 130};

    private static final AtomicLong regionsWritten = new AtomicLong();
    private static final AtomicLong chunksDecoded = new AtomicLong();
    private static final AtomicLong unknownBlocks = new AtomicLong();

    private static final ArrayDeque<Path> pending = new ArrayDeque<>();
    private static int rescanCooldown;

    // État de la région en cours de traitement, réparti sur plusieurs ticks.
    private static Path currentMca;
    private static Path currentTileDir;
    private static RegionFile currentRegion;
    private static short[] currentHeights;
    private static byte[] currentColors;
    private static int currentChunkIndex;

    private Relief() {}

    public static void tick(MinecraftServer server) {
        if (!ReliefConfig.ACTIVE.get()) return;

        if (rescanCooldown <= 0) {
            rescan(server);
            rescanCooldown = ReliefConfig.RESCAN_TICKS.get();
        } else {
            rescanCooldown--;
        }

        long deadline = System.nanoTime() + ReliefConfig.BUDGET_MS.get() * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (currentRegion == null) {
                Path next = pending.poll();
                if (next == null) return; // rien à faire ce tick
                if (!beginRegion(next)) continue;
            }
            if (!processOneChunk()) {
                finishRegion();
            }
        }
    }

    /** Balaie les trois dimensions standard à la recherche de régions plus récentes que leur tuile. */
    private static void rescan(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        for (ResourceKey<Level> key : DIMENSIONS) {
            Path regionDir = DimensionType.getStorageFolder(key, root).resolve("region");
            if (!Files.isDirectory(regionDir)) continue;
            Path tileDir = regionDir.resolveSibling("lanterne_carte3d");
            try (DirectoryStream<Path> files = Files.newDirectoryStream(regionDir, "r.*.mca")) {
                for (Path mca : files) {
                    if (pending.contains(mca)) continue;
                    Path tile = tileFor(tileDir, mca);
                    if (needsProcessing(mca, tile)) pending.add(mca);
                }
            } catch (IOException e) {
                Lanterne.LOG.warn("[RELIEF] balayage de {} impossible : {}", regionDir, e.toString());
            }
            // Reconstruit le manifeste meme si rien de nouveau n'a ete traite : une region deja a
            // jour ne repasse jamais par finishRegion(), qui est le seul autre appelant.
            if (Files.isDirectory(tileDir)) updateManifest(tileDir);
        }
    }

    /**
     * Le dossier de tuiles d'une région : à côté de "region/", pour rester dans le monde et suivre
     * ses sauvegardes/copies sans configuration séparée. Dérivé du chemin de la région elle-même,
     * pour qu'un seul calcul serve à la fois au balayage et à l'écriture — les deux désaccordaient
     * avant cette version, et la carte se remplissait silencieusement au mauvais endroit.
     */
    private static Path tileDirFor(Path mca) {
        return mca.getParent().resolveSibling("lanterne_carte3d");
    }

    private static boolean needsProcessing(Path mca, Path tile) {
        try {
            if (!Files.exists(tile)) return true;
            return Files.getLastModifiedTime(mca).compareTo(Files.getLastModifiedTime(tile)) > 0;
        } catch (IOException e) {
            return true;
        }
    }

    private static Path tileFor(Path tileDir, Path mca) {
        String name = mca.getFileName().toString(); // r.X.Z.mca
        String base = name.substring(0, name.length() - 4);
        return tileDir.resolve(base + ".bin");
    }

    private static final List<ResourceKey<Level>> DIMENSIONS =
            List.of(Level.OVERWORLD, Level.NETHER, Level.END);

    private static boolean beginRegion(Path mca) {
        try {
            currentRegion = new RegionFile(
                    new net.minecraft.world.level.chunk.storage.RegionStorageInfo(
                            mca.toString(), Level.OVERWORLD, "relief"),
                    mca, mca.getParent(), false);
        } catch (IOException e) {
            Lanterne.LOG.warn("[RELIEF] ouverture de {} impossible : {}", mca, e.toString());
            return false;
        }
        currentMca = mca;
        currentTileDir = tileDirFor(mca);
        currentHeights = new short[REGION_BLOCKS * REGION_BLOCKS];
        java.util.Arrays.fill(currentHeights, UNEXPLORED);
        currentColors = new byte[REGION_BLOCKS * REGION_BLOCKS * 3];
        currentChunkIndex = 0;
        return true;
    }

    /** Traite un chunk de la région en cours. Retourne faux quand la région est épuisée. */
    private static boolean processOneChunk() {
        if (currentChunkIndex >= REGION_CHUNKS * REGION_CHUNKS) return false;
        int localX = currentChunkIndex % REGION_CHUNKS;
        int localZ = currentChunkIndex / REGION_CHUNKS;
        currentChunkIndex++;

        int regionX = regionCoord(currentMca, 1);
        int regionZ = regionCoord(currentMca, 2);
        ChunkPos pos = new ChunkPos(regionX * REGION_CHUNKS + localX, regionZ * REGION_CHUNKS + localZ);
        if (!currentRegion.hasChunk(pos)) return true;

        try (DataInputStream in = currentRegion.getChunkDataInputStream(pos)) {
            if (in == null) return true;
            CompoundTag root = NbtIo.read(in);
            if (root == null) return true;
            decodeChunkSurface(root, localX, localZ);
            chunksDecoded.incrementAndGet();
        } catch (IOException e) {
            Lanterne.LOG.debug("[RELIEF] chunk {} illisible dans {} : {}", pos, currentMca, e.toString());
        }
        return true;
    }

    private static int regionCoord(Path mca, int part) {
        String[] p = mca.getFileName().toString().split("\\.");
        return Integer.parseInt(p[part]);
    }

    private static void decodeChunkSurface(CompoundTag root, int chunkLocalX, int chunkLocalZ) {
        Optional<Integer> yPos = root.getInt("yPos");
        Optional<ListTag> sectionsOpt = root.getList("sections");
        Optional<CompoundTag> heightmapsOpt = root.getCompound("Heightmaps");
        if (yPos.isEmpty() || sectionsOpt.isEmpty() || heightmapsOpt.isEmpty()) return;

        ListTag sections = sectionsOpt.get();
        int worldMinY = yPos.get() * 16;
        int worldHeight = sections.size() * 16;
        int hmBits = bitsFor(worldHeight + 1);
        int hmPerLong = 64 / hmBits;

        CompoundTag heightmaps = heightmapsOpt.get();
        Optional<long[]> hmData = heightmaps.getLongArray("MOTION_BLOCKING");
        if (hmData.isEmpty()) hmData = heightmaps.getLongArray("WORLD_SURFACE");
        if (hmData.isEmpty()) return;
        long[] heightArr = hmData.get();

        // Index les sections par leur Y pour un acces direct colonne -> section.
        Map<Integer, ListTag> paletteBySection = new HashMap<>();
        Map<Integer, long[]> dataBySection = new HashMap<>();
        for (int i = 0; i < sections.size(); i++) {
            Optional<CompoundTag> sOpt = sections.getCompound(i);
            if (sOpt.isEmpty()) continue;
            CompoundTag s = sOpt.get();
            Optional<Integer> sy = s.getInt("Y");
            Optional<CompoundTag> bs = s.getCompound("block_states");
            if (sy.isEmpty() || bs.isEmpty()) continue;
            Optional<ListTag> palette = bs.get().getList("palette");
            if (palette.isEmpty()) continue;
            paletteBySection.put(sy.get(), palette.get());
            bs.get().getLongArray("data").ifPresent(d -> dataBySection.put(sy.get(), d));
        }

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int colIdx = z * 16 + x;
                long h = unpack(heightArr, colIdx, hmBits, hmPerLong);
                int surfaceY = (int) h + worldMinY - 1;
                int sectionY = Math.floorDiv(surfaceY, 16);
                ListTag palette = paletteBySection.get(sectionY);
                if (palette == null) continue; // colonne vide (void, ou hauteur hors section connue)
                int localY = surfaceY - sectionY * 16;
                long[] data = dataBySection.get(sectionY);
                String blockId = blockAt(palette, data, x, localY, z);
                if (blockId == null || blockId.equals("minecraft:air") || blockId.equals("minecraft:cave_air")
                        || blockId.equals("minecraft:void_air")) {
                    continue; // colonne d'air pur (void) : reste UNEXPLORED, le client ne la dessine pas
                }
                int rx = chunkLocalX * 16 + x;
                int rz = chunkLocalZ * 16 + z;
                int flat = rz * REGION_BLOCKS + rx;
                currentHeights[flat] = (short) surfaceY;
                int[] rgb = COLORS.getOrDefault(blockId, null);
                if (rgb == null) { rgb = FALLBACK_COLOR; unknownBlocks.incrementAndGet(); }
                currentColors[flat * 3] = (byte) rgb[0];
                currentColors[flat * 3 + 1] = (byte) rgb[1];
                currentColors[flat * 3 + 2] = (byte) rgb[2];
            }
        }
    }

    /** Nom du bloc a la position locale (x,y,z) dans une section de 16^3, via son palette+data. */
    private static String blockAt(ListTag palette, long[] data, int x, int y, int z) {
        int idx;
        if (data == null) {
            idx = 0; // section uniforme : palette n'a qu'une entree
        } else {
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            int perLong = 64 / bits;
            int linear = y * 256 + z * 16 + x;
            idx = (int) unpack(data, linear, bits, perLong);
        }
        if (idx < 0 || idx >= palette.size()) return null;
        Optional<String> asString = palette.getString(idx);
        if (asString.isPresent()) return asString.get();
        Optional<CompoundTag> asCompound = palette.getCompound(idx);
        if (asCompound.isPresent()) {
            CompoundTag c = asCompound.get();
            // Trois encodages vus en pratique pour la meme information : le format vanilla classique
            // ({Name: "..."}), et un format compact rencontre sur d'autres mondes ({id: "..."} avec
            // proprietes, ou {"": "..."} sans proprietes). On les accepte tous plutot que de deviner
            // lequel cette sauvegarde utilise.
            Optional<String> name = c.getString("Name");
            if (name.isPresent()) return name.get();
            Optional<String> id = c.getString("id");
            if (id.isPresent()) return id.get();
            Optional<String> bare = c.getString("");
            if (bare.isPresent()) return bare.get();
        }
        return null;
    }

    private static long unpack(long[] arr, int index, int bits, int perLong) {
        int longIdx = index / perLong;
        if (longIdx >= arr.length) return 0;
        int subIdx = index % perLong;
        long mask = (1L << bits) - 1;
        return (arr[longIdx] >>> (subIdx * bits)) & mask;
    }

    private static int bitsFor(int distinctValues) {
        int bits = 32 - Integer.numberOfLeadingZeros(Math.max(1, distinctValues - 1));
        return Math.max(1, bits);
    }

    private static void finishRegion() {
        try {
            currentRegion.close();
        } catch (IOException ignored) { }
        try {
            Files.createDirectories(currentTileDir);
            Path tmp = currentTileDir.resolve(currentMca.getFileName().toString() + ".tmp");
            Path finalPath = tileFor(currentTileDir, currentMca);
            try (DataOutputStream out = new DataOutputStream(
                    new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.write(MAGIC);
                for (short h : currentHeights) out.writeShort(h);
                out.write(currentColors);
            }
            Files.move(tmp, finalPath, StandardCopyOption.REPLACE_EXISTING);
            regionsWritten.incrementAndGet();
            updateManifest(currentTileDir);
        } catch (IOException e) {
            Lanterne.LOG.warn("[RELIEF] écriture de la tuile pour {} impossible : {}", currentMca, e.toString());
        }
        currentRegion = null;
        currentMca = null;
        currentHeights = null;
        currentColors = null;
    }

    /**
     * La liste des régions disponibles dans un dossier de tuiles, pour que le viewer web sache quoi
     * demander sans deviner des coordonnées à l'aveugle. Reconstruite depuis le disque à chaque
     * région terminée — un coup de {@code list} sur quelques centaines de fichiers au plus, largement
     * sous le budget d'un seul tick.
     */
    private static void updateManifest(Path tileDir) {
        List<int[]> regions = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(tileDir, "r.*.bin")) {
            for (Path p : files) {
                String[] parts = p.getFileName().toString().split("\\.");
                if (parts.length != 4) continue;
                try {
                    regions.add(new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])});
                } catch (NumberFormatException ignored) { }
            }
        } catch (IOException e) {
            return;
        }
        StringBuilder json = new StringBuilder("{\"regions\":[");
        for (int i = 0; i < regions.size(); i++) {
            int[] r = regions.get(i);
            json.append("[").append(r[0]).append(",").append(r[1]).append("]");
            if (i < regions.size() - 1) json.append(",");
        }
        json.append("]}");
        try {
            Path tmp = tileDir.resolve("manifest.json.tmp");
            Files.writeString(tmp, json.toString(), java.nio.charset.StandardCharsets.UTF_8);
            Files.move(tmp, tileDir.resolve("manifest.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }

    private static Map<String, int[]> loadColors() {
        Map<String, int[]> map = new HashMap<>();
        try (InputStream in = Relief.class.getClassLoader()
                .getResourceAsStream("lanterne/block_colors.json")) {
            if (in == null) {
                Lanterne.LOG.warn("[RELIEF] block_colors.json introuvable — toutes les couleurs "
                        + "retomberont sur le gris de repli.");
                return map;
            }
            Gson gson = new Gson();
            var type = new TypeToken<Map<String, int[]>>() {}.getType();
            Map<String, int[]> loaded = gson.fromJson(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), type);
            if (loaded != null) map.putAll(loaded);
        } catch (IOException e) {
            Lanterne.LOG.warn("[RELIEF] lecture de block_colors.json impossible : {}", e.toString());
        }
        return map;
    }

    public static String report() {
        return String.format(Locale.ROOT,
                "%d région(s) écrite(s), %d chunk(s) décodé(s), %d bloc(s) de surface sans couleur connue",
                regionsWritten.get(), chunksDecoded.get(), unknownBlocks.get());
    }
}
