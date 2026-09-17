package fr.clubcitrouille.lanterne.client;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Config;

/**
 * Le coffre côté client : les chunks déjà vus, gardés d'une session à l'autre. Voir
 * {@code core.Souvenir} pour le protocole qui décide quand un chunk mis en cache reste valable.
 *
 * <h2>Rangé comme les fichiers de région de vanilla, en plus simple</h2>
 *
 * <p>Un fichier par région de trente-deux chunks de côté, plutôt qu'un fichier par chunk : quelques
 * milliers de fichiers minuscules coûteraient cher en accès disque et en occupation de système de
 * fichiers, exactement la raison pour laquelle vanilla lui-même range ses propres chunks par région.
 *
 * <h2>Ce que cette version ne fait pas encore, et pourquoi c'est écrit ici plutôt que caché</h2>
 *
 * <p>Le fichier est <b>ajouté-à-la-fin</b>, jamais compacté : une entrée réécrite laisse l'ancienne
 * copie morte dans le fichier, retrouvée mais jamais reprise. Sur une très longue session, un fichier
 * de région peut donc grossir bien au-delà de son contenu utile. Ce n'est jamais un défaut de
 * correction — la lecture ne retient toujours que la <b>dernière</b> occurrence de chaque case, la
 * plus récente l'emporte toujours — seulement d'espace disque. Une vraie mise en service demanderait
 * une compaction périodique, qui n'a pas été écrite dans cette passe.
 *
 * <p>Le plafond de {@link Config#SOUVENIR_BUDGET_MB} est vérifié en lecture globale avant chaque
 * écriture, mais rien n'est encore <b>évincé</b> quand il est dépassé : on arrête simplement d'écrire
 * de nouvelles entrées. Une éviction par ancienneté de fichier de région, plus grossière que celle
 * d'{@code Emballage} mais dans le même esprit, reste à écrire.
 *
 * <p>Toutes les lectures et écritures sont <b>synchrones</b>, sur le fil qui les demande. Pour un
 * module qui reste éteint tant qu'aucun banc ne l'a mesuré, c'est acceptable ; ce ne le serait plus
 * pour une activation par défaut, où ce travail devrait passer hors du fil de rendu.
 */
public final class SouvenirVault {
    private static final byte[] MAGIC = {'L', 'V', 'S', '1'};
    private static final int CHUNKS_PER_REGION = 32;

    /** Un fichier de région ouvert cette session : son index, construit une seule fois en le lisant. */
    private static final class Region {
        final Path file;
        final Int2ObjectOpenHashMap<long[]> index = new Int2ObjectOpenHashMap<>();
        long length;

        Region(Path file) {
            this.file = file;
        }
    }

    private static final Map<Path, Region> OPEN = new HashMap<>();
    private static long writtenBytes;

    private SouvenirVault() {}

    public static void reset() {
        OPEN.clear();
        writtenBytes = 0L;
    }

    private static Path root(UUID worldId, Identifier dimension) {
        String dim = sanitize(dimension.toString());
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("lanterne").resolve("souvenir").resolve(worldId.toString()).resolve(dim);
    }

    private static String sanitize(String raw) {
        return raw.replace(':', '_').replace('/', '_');
    }

    private static Path regionFile(UUID worldId, Identifier dimension, ChunkPos pos) {
        int rx = pos.x() >> 5;
        int rz = pos.z() >> 5;
        return root(worldId, dimension).resolve("r." + rx + "." + rz + ".lvs");
    }

    private static int localIndex(ChunkPos pos) {
        int lx = Math.floorMod(pos.x(), CHUNKS_PER_REGION);
        int lz = Math.floorMod(pos.z(), CHUNKS_PER_REGION);
        return (lx << 5) | lz;
    }

    /**
     * Ouvre le fichier de région d'un chunk, en le lisant en entier la première fois qu'on le
     * demande dans cette session. Les lectures suivantes ne consultent que l'index déjà en mémoire.
     */
    private static Region open(UUID worldId, Identifier dimension, ChunkPos pos) {
        Path file = regionFile(worldId, dimension, pos);
        Region region = OPEN.get(file);
        if (region != null) {
            return region;
        }
        region = new Region(file);
        OPEN.put(file, region);
        readInto(region);
        return region;
    }

    /** La révision connue pour un chunk, ou {@code 0} si aucune n'est en mémoire de disque. */
    public static long knownRevision(UUID worldId, Identifier dimension, ChunkPos pos) {
        Region region = open(worldId, dimension, pos);
        long[] entry = region.index.get(localIndex(pos));
        return entry == null ? 0L : entry[0];
    }

    /**
     * Toutes les révisions connues pour une dimension, tous fichiers de région confondus — pour
     * bâtir la manifeste envoyée au serveur à la connexion ou au changement de dimension.
     *
     * <p>Parcourt le dossier une fois : chaque fichier trouvé est ouvert comme {@link #open} le
     * ferait, et son index fusionné dans le résultat. Un dossier absent — première visite de ce
     * monde — rend simplement une table vide.
     */
    public static Map<Long, Long> allKnownRevisions(UUID worldId, Identifier dimension) {
        Map<Long, Long> found = new HashMap<>();
        Path dir = root(worldId, dimension);
        if (!Files.isDirectory(dir)) {
            return found;
        }
        try (var listing = Files.list(dir)) {
            listing.filter(path -> path.getFileName().toString().endsWith(".lvs"))
                    .forEach(path -> mergeRegion(path, found));
        } catch (IOException failed) {
            Lanterne.LOG.warn("[SOUVENIR] {} : dossier illisible.", dir);
        }
        return found;
    }

    /** Décode le nom d'un fichier de région et fusionne son index dans la table donnée. */
    private static void mergeRegion(Path file, Map<Long, Long> into) {
        String name = file.getFileName().toString();
        String[] parts = name.split("\\.");
        if (parts.length != 4) {
            return; // nom inattendu : on l'ignore plutôt que de deviner
        }
        int rx;
        int rz;
        try {
            rx = Integer.parseInt(parts[1]);
            rz = Integer.parseInt(parts[2]);
        } catch (NumberFormatException malformed) {
            return;
        }
        Region region = OPEN.get(file);
        if (region == null) {
            region = new Region(file);
            OPEN.put(file, region);
            readInto(region);
        }
        for (var it = region.index.int2ObjectEntrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            int local = entry.getIntKey();
            int lx = local >> 5;
            int lz = local & 31;
            long globalPos = ChunkPos.pack((rx << 5) + lx, (rz << 5) + lz);
            into.put(globalPos, entry.getValue()[0]);
        }
    }

    /** Le corps de lecture partagé par {@link #open} et {@link #mergeRegion}. */
    private static void readInto(Region region) {
        if (!Files.exists(region.file)) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(region.file.toFile(), "r")) {
            byte[] magic = new byte[MAGIC.length];
            if (raf.read(magic) != MAGIC.length || !java.util.Arrays.equals(magic, MAGIC)) {
                return;
            }
            long pointer = raf.getFilePointer();
            long total = raf.length();
            while (pointer + 16 <= total) {
                raf.seek(pointer);
                int localKey = raf.readInt();
                long revision = raf.readLong();
                int len = raf.readInt();
                long payloadOffset = raf.getFilePointer();
                if (payloadOffset + len > total) {
                    break;
                }
                region.index.put(localKey, new long[] {revision, payloadOffset, len});
                pointer = payloadOffset + len;
            }
            region.length = total;
        } catch (IOException failed) {
            Lanterne.LOG.warn("[SOUVENIR] {} : lecture impossible, fichier ignoré.", region.file);
        }
    }

    /** Les octets persistés d'un chunk, ou {@code null} si aucun n'est en cache. */
    public static byte[] loadBytes(UUID worldId, Identifier dimension, ChunkPos pos) {
        Region region = open(worldId, dimension, pos);
        long[] entry = region.index.get(localIndex(pos));
        if (entry == null) {
            return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(region.file.toFile(), "r")) {
            raf.seek(entry[1]);
            byte[] bytes = new byte[(int) entry[2]];
            raf.readFully(bytes);
            return bytes;
        } catch (IOException failed) {
            Lanterne.LOG.warn("[SOUVENIR] {} : relecture impossible pour {},{}.",
                    region.file, pos.x(), pos.z());
            return null;
        }
    }

    /** Persiste les octets d'un chunk tout juste reçu, sous sa révision annoncée. */
    public static void store(UUID worldId, Identifier dimension, ChunkPos pos, long revision, byte[] bytes) {
        long budget = Config.SOUVENIR_BUDGET_MB.getAsInt() * 1024L * 1024L;
        if (writtenBytes + bytes.length > budget) {
            return; // plafond atteint : pas d'éviction dans cette version, voir la javadoc de classe
        }
        Region region = open(worldId, dimension, pos);
        try {
            Files.createDirectories(region.file.getParent());
            try (RandomAccessFile raf = new RandomAccessFile(region.file.toFile(), "rw")) {
                if (raf.length() == 0L) {
                    raf.write(MAGIC);
                }
                long offset = raf.length();
                raf.seek(offset);
                raf.writeInt(localIndex(pos));
                raf.writeLong(revision);
                raf.writeInt(bytes.length);
                long payloadOffset = raf.getFilePointer();
                raf.write(bytes);
                region.index.put(localIndex(pos), new long[] {revision, payloadOffset, bytes.length});
                region.length = raf.length();
            }
            writtenBytes += bytes.length;
        } catch (IOException failed) {
            Lanterne.LOG.warn("[SOUVENIR] {} : écriture impossible, chunk non gardé.", region.file);
        }
    }
}
