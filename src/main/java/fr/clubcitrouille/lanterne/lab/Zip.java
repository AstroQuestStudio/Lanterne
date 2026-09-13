package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;

import io.netty.buffer.Unpooled;

import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Wire;

/**
 * L'épreuve de compression : le niveau six contre le niveau un, sur de vrais paquets de chunk.
 *
 * <h2>Pourquoi le banc de vitesse ne pouvait pas mesurer ce module</h2>
 *
 * <p>Deux raisons, et chacune aurait suffi.
 *
 * <p><b>La compression n'a pas lieu sur le fil du serveur.</b> Elle s'exécute sur les fils de Netty.
 * Le banc chronomètre le tick ; il ne verrait donc jamais ce module, quelle que soit son efficacité.
 *
 * <p><b>Les doublures du banc n'ont pas de connexion réseau.</b> Aucun paquet n'est encodé, donc
 * aucun n'est compressé. Le premier essai a rendu ce qu'il devait rendre : <em>zéro paquet</em>. Un
 * banc qui mesure zéro n'a pas mesuré peu — il n'a pas mesuré.
 *
 * <h2>Ce que cette épreuve mesure, et pourquoi c'est la bonne grandeur</h2>
 *
 * <p>Elle prend les chunks réellement chargés, en extrait la charge utile <b>par le même encodeur que
 * le jeu</b> — {@code ClientboundLevelChunkPacketData.extractChunkData} — et compresse ces octets aux
 * deux niveaux, en alternance, après chauffe.
 *
 * <p>C'est exactement ce que le serveur fait quand un joueur arrive : rien n'est simulé, rien n'est
 * approché. La seule chose absente est le coût d'envoi, que ce module ne change pas.
 *
 * <h2>L'alternance, et la chauffe</h2>
 *
 * <p>Les deux niveaux sont mesurés <b>en alternance</b> et non l'un après l'autre : le compilateur à
 * la volée optimise pendant la première série, et une mesure séquentielle avantagerait
 * systématiquement la seconde. C'est la faute qui a déjà coûté à ce projet une sous-estimation de
 * vingt-sept pour cent sur un autre banc.
 */
public final class Zip {
    /** Chunks échantillonnés autour du point de référence. */
    private static final int REACH = 8;

    /** Passes de chauffe, jetées. */
    private static final int WARMUP = 3;

    /** Passes mesurées, alternées entre les deux niveaux. */
    private static final int PASSES = 12;

    private Zip() {}

    public static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        List<byte[]> payloads = harvest(level);
        if (payloads.isEmpty()) {
            Lanterne.LOG.error("[COMPRESSION] aucun chunk chargé — rien à mesurer.");
            return;
        }

        long rawTotal = 0L;
        for (byte[] payload : payloads) {
            rawTotal += payload.length;
        }

        // Chauffe : on compresse aux deux niveaux sans rien retenir.
        for (int pass = 0; pass < WARMUP; pass++) {
            for (byte[] payload : payloads) {
                squeeze(payload, 6);
                squeeze(payload, Wire.LEVEL);
            }
        }

        long sixNanos = 0L;
        long oneNanos = 0L;
        long sixBytes = 0L;
        long oneBytes = 0L;

        for (int pass = 0; pass < PASSES; pass++) {
            // L'ordre s'inverse à chaque passe : si un effet de cache favorisait le premier appelé,
            // il favoriserait les deux également.
            boolean sixFirst = (pass % 2) == 0;
            for (byte[] payload : payloads) {
                if (sixFirst) {
                    long[] six = timed(payload, 6);
                    long[] one = timed(payload, Wire.LEVEL);
                    sixNanos += six[0];
                    sixBytes += six[1];
                    oneNanos += one[0];
                    oneBytes += one[1];
                } else {
                    long[] one = timed(payload, Wire.LEVEL);
                    long[] six = timed(payload, 6);
                    oneNanos += one[0];
                    oneBytes += one[1];
                    sixNanos += six[0];
                    sixBytes += six[1];
                }
            }
        }

        double sixMs = sixNanos / 1_000_000d;
        double oneMs = oneNanos / 1_000_000d;
        int chunks = payloads.size();

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[COMPRESSION] ── %d chunk(s), %d passe(s), %.2f Mo de charge utile ──",
                chunks, PASSES, rawTotal / 1048576d));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[COMPRESSION] niveau 6 (vanilla) : %8.1f ms · %.2f Mo émis · ÷%.2f",
                sixMs, sixBytes / 1048576d / PASSES, rawTotal * (double) PASSES / sixBytes));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[COMPRESSION] niveau %d (Lanterne) : %8.1f ms · %.2f Mo émis · ÷%.2f",
                Wire.LEVEL, oneMs, oneBytes / 1048576d / PASSES,
                rawTotal * (double) PASSES / oneBytes));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[COMPRESSION] VERDICT : ×%.2f plus rapide, pour %+.1f %% d'octets émis.",
                sixMs / oneMs, (oneBytes - sixBytes) * 100d / sixBytes));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[COMPRESSION] Sur une arrivée de joueur à 32 chunks de vue — 441 chunks — cela "
                + "represente %.0f ms de coeur au lieu de %.0f.",
                oneMs / PASSES / chunks * 441d, sixMs / PASSES / chunks * 441d));
    }

    /** La charge utile d'un chunk, par le même encodeur que le jeu. */
    private static List<byte[]> harvest(ServerLevel level) {
        List<byte[]> payloads = new ArrayList<>();
        int centreX = SectionPos.blockToSectionCoord(0);
        int centreZ = SectionPos.blockToSectionCoord(0);
        for (int dx = -REACH; dx <= REACH; dx++) {
            for (int dz = -REACH; dz <= REACH; dz++) {
                // On FORCE le chargement au lieu d'attendre que le monde veuille bien l'avoir fait.
                // Le premier essai testait « hasChunk » soixante ticks après le démarrage et n'en
                // trouvait aucun : l'épreuve annonçait « rien à mesurer » alors que le monde entier
                // était sur le disque, à portée d'un appel.
                LevelChunk chunk = level.getChunk(centreX + dx, centreZ + dz);
                try {
                    // extractChunkData exige un tampon PRE-DIMENSIONNE EXACTEMENT : elle vérifie à la
                    // fin qu'il est plein au dernier octet, et lève sinon. Un tampon qui grandit tout
                    // seul — Unpooled.buffer() — la fait donc échouer sur tous les chunks, ce qu'elle
                    // a fait au premier essai. La taille est la somme des sections, comme le calcule
                    // le constructeur du paquet.
                    int size = 0;
                    for (var section : chunk.getSections()) {
                        size += section.getSerializedSize();
                    }
                    byte[] raw = new byte[size];
                    var writable = Unpooled.wrappedBuffer(raw);
                    writable.writerIndex(0);
                    ClientboundLevelChunkPacketData.extractChunkData(new FriendlyByteBuf(writable), chunk);
                    payloads.add(raw);
                } catch (Throwable refused) {
                    Lanterne.LOG.warn("[COMPRESSION] chunk illisible, ignoré : {}", refused.toString());
                }
            }
        }
        return payloads;
    }

    /** @return {nanosecondes, octets produits} */
    private static long[] timed(byte[] payload, int level) {
        long started = System.nanoTime();
        int produced = squeeze(payload, level);
        return new long[] {System.nanoTime() - started, produced};
    }

    /**
     * Compresse, et rend la taille produite.
     *
     * <p>Un {@code Deflater} neuf par appel, comme vanilla en crée un par connexion et le remet à
     * zéro entre les paquets. Réutiliser le même ici avantagerait le second niveau mesuré.
     */
    private static int squeeze(byte[] payload, int level) {
        Deflater deflater = new Deflater(level);
        try {
            deflater.setInput(payload);
            deflater.finish();
            byte[] scratch = new byte[8192];
            int produced = 0;
            while (!deflater.finished()) {
                produced += deflater.deflate(scratch);
            }
            return produced;
        } finally {
            deflater.end();
        }
    }
}
