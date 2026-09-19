package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Leurre;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * La palissade : l'anti x-ray ({@link Leurre}) cache-t-il vraiment ce qu'il doit cacher, et ce
 * qu'il ajoute au prix d'un envoi de chunk se voit-il, sur du terrain réellement engendré ?
 *
 * <h2>Deux épreuves, dans l'ordre où elles comptent</h2>
 *
 * <p>Comme {@code Rappel} pour {@code Souvenir} : la conformité d'abord, la vitesse ensuite. Un
 * module rapide qui laisserait fuiter un filon aurait échoué, quel que soit son chiffre.
 *
 * <ol>
 *   <li>{@link Step#CORRECTNESS} — un cas synthétique et déterministe, pas laissé au hasard de la
 *       génération : un diamant entouré de pierre de tous les côtés doit disparaître du paquet
 *       ({@link Leurre#voile}, pas seulement supposé) ; le même diamant, une face ouverte sur de
 *       l'air, doit y reparaître. La VRAIE section du chunk — celle que le jeu utilise pour la
 *       logique — n'est jamais touchée : seule la copie envoyée l'est, et ce test le vérifie aussi.</li>
 *   <li>{@link Step#MEASURE_OFF}/{@code ON_COLD}/{@code ON_WARM} — le coût, sur une grille de
 *       chunks réellement engendrés (pas un monde vide, pas de filons placés à la main) : combien
 *       ajoute un premier envoi qui doit tout calculer, et combien ajoute un second envoi du même
 *       chunk une fois le cache par section rempli — c'est la promesse de {@link Leurre} qu'il
 *       s'agit de vérifier, pas de supposer.</li>
 * </ol>
 *
 * <p>Les deux bras (SANS/AVEC) tournent dans la MÊME exécution, sur les MÊMES chunks, via {@link
 * Settings#setLeurre} — la seule façon de comparer sans laisser une différence de génération ou de
 * cache d'OS fausser le résultat.
 */
public final class Palissade {
    private static final int SIDE = 10;
    private static final int GRID_CHUNKS = SIDE * SIDE;
    // Même voisinage que lab/Rappel (-3300,-3300) : déjà généré sur disque par les précédentes
    // épreuves de ce dépôt. Une zone jamais visitée forcerait une génération fraîche complète
    // (relief, cavernes, structures) à chaque essai, ce qui a été mesuré comme largement dominant
    // le temps total sur la machine de développement — pas ce que ce banc cherche à isoler. Voir
    // le rapport de mission : Rappel, à cet endroit, traite 144 chunks en quelques secondes ; la
    // même grille sur un point jamais généré n'a pas fini en plusieurs minutes.
    private static final int ORIGIN_CX = -3300;
    private static final int ORIGIN_CZ = -3300;

    private static final int READINGS = 8;
    private static final int WARMUP = 2;

    private enum Step {
        OFF, SEEDING, SETTLE, CORRECTNESS, MEASURE_OFF, MEASURE_ON_COLD, MEASURE_ON_WARM, DONE
    }

    private static Step step = Step.OFF;
    private static MinecraftServer host;
    private static final List<ChunkPos> positions = new ArrayList<>();

    private static int issued;
    private static int done;
    private static final List<java.util.concurrent.CompletableFuture<?>> IN_FLIGHT = new ArrayList<>();
    private static int settleLeft;
    private static final int SETTLE_TICKS = 20;

    private static boolean savedLeurre;

    private static boolean correctnessRan;
    private static boolean correctnessOk = true;
    private static final StringBuilder correctnessLog = new StringBuilder();

    private static final long[] offNanos = new long[READINGS];
    private static final long[] onColdNanos = new long[READINGS];
    private static final long[] onWarmNanos = new long[READINGS];
    private static int reading;

    private static long sectionsVuesAvant;
    private static long sectionsSubstitueesAvant;
    private static long blocsCachesAvant;

    private Palissade() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        positions.clear();
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                positions.add(new ChunkPos(ORIGIN_CX + x, ORIGIN_CZ + z));
            }
        }
        ServerLevel level = server.overworld();
        for (ChunkPos pos : positions) {
            level.setChunkForced(pos.x(), pos.z(), true);
        }
        savedLeurre = Settings.leurre();
        issued = 0;
        done = 0;
        IN_FLIGHT.clear();
        reading = 0;
        correctnessRan = false;
        correctnessOk = true;
        correctnessLog.setLength(0);
        step = Step.SEEDING;
        Lanterne.LOG.info("[PALISSADE] Épreuve lancée — {} chunks en ({},{}), terrain réellement "
                + "engendré, aucun filon placé à la main pour la mesure.", GRID_CHUNKS, ORIGIN_CX, ORIGIN_CZ);
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();
        switch (step) {
            case SEEDING -> {
                if (pumpLoad(server)) {
                    settleLeft = SETTLE_TICKS;
                    step = Step.SETTLE;
                }
            }
            case SETTLE -> {
                if (--settleLeft <= 0) {
                    step = Step.CORRECTNESS;
                }
            }
            case CORRECTNESS -> {
                runCorrectness(level);
                correctnessRan = true;
                Settings.setLeurre(false);
                Leurre.purge();
                reading = 0;
                step = Step.MEASURE_OFF;
            }
            case MEASURE_OFF -> {
                long elapsed = buildAll(level);
                if (reading >= WARMUP) {
                    offNanos[reading - WARMUP] = elapsed;
                }
                reading++;
                if (reading >= READINGS) {
                    Settings.setLeurre(true);
                    Leurre.purge();
                    sectionsVuesAvant = Leurre.sectionsVues();
                    sectionsSubstitueesAvant = Leurre.sectionsSubstituees();
                    blocsCachesAvant = Leurre.blocsCaches();
                    reading = 0;
                    step = Step.MEASURE_ON_COLD;
                }
            }
            case MEASURE_ON_COLD -> {
                // Une seule lecture : c'est le pire cas, un cache entièrement vide, et il ne doit
                // pas être noyé dans une moyenne avec des lectures déjà tièdes.
                long elapsed = buildAll(level);
                onColdNanos[0] = elapsed;
                reading = 0;
                step = Step.MEASURE_ON_WARM;
            }
            case MEASURE_ON_WARM -> {
                // Le cache est maintenant plein pour toute la grille : chaque lecture qui suit ne
                // doit plus recalculer une seule section.
                long elapsed = buildAll(level);
                if (reading >= WARMUP) {
                    onWarmNanos[reading - WARMUP] = elapsed;
                }
                reading++;
                if (reading >= READINGS) {
                    step = Step.DONE;
                    for (ChunkPos pos : positions) {
                        level.setChunkForced(pos.x(), pos.z(), false);
                    }
                    Settings.setLeurre(savedLeurre);
                    report();
                    if (host != null) {
                        host.halt(false);
                    }
                }
            }
            default -> { }
        }
    }

    private static boolean pumpLoad(MinecraftServer server) {
        ServerLevel level = server.overworld();
        var source = level.getChunkSource();
        while (IN_FLIGHT.size() < 16 && issued < positions.size()) {
            ChunkPos pos = positions.get(issued++);
            IN_FLIGHT.add(source.getChunkFuture(pos.x(), pos.z(),
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true));
        }
        IN_FLIGHT.removeIf(f -> {
            if (f.isDone()) {
                done++;
                return true;
            }
            return false;
        });
        return done >= positions.size();
    }

    /** Construit le paquet réel de chaque chunk de la grille ; rend le temps cumulé, en nanosecondes. */
    private static long buildAll(ServerLevel level) {
        long start = System.nanoTime();
        for (ChunkPos pos : positions) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
            if (chunk != null) {
                new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null);
            }
        }
        return System.nanoTime() - start;
    }

    /**
     * Le cas synthétique : un diamant caché, puis exposé. Déterministe — ne dépend d'aucun filon
     * réellement engendré, contrairement aux épreuves de vitesse qui suivent.
     */
    private static void runCorrectness(ServerLevel level) {
        int flags = Block.UPDATE_ALL;
        BlockPos target = new BlockPos((ORIGIN_CX + SIDE / 2) * 16 + 8, -40,
                (ORIGIN_CZ + SIDE / 2) * 16 + 8);

        // Contexte solide garanti de toutes parts, indépendamment de ce que la génération a produit.
        level.setBlock(target, Blocks.STONE.defaultBlockState(), flags);
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            level.setBlock(target.relative(dir), Blocks.STONE.defaultBlockState(), flags);
        }
        Settings.setLeurre(true);

        LevelChunk chunk = level.getChunkSource().getChunkNow(target.getX() >> 4, target.getZ() >> 4);
        if (chunk == null) {
            correctnessOk = false;
            correctnessLog.append("chunk cible introuvable — épreuve invalide. ");
            return;
        }
        int sectionIndex = chunk.getSectionIndex(target.getY());
        int lx = target.getX() & 15;
        int ly = target.getY() & 15;
        int lz = target.getZ() & 15;

        level.setBlock(target, Blocks.DIAMOND_ORE.defaultBlockState(), flags);
        LevelChunkSection reelle = chunk.getSections()[sectionIndex];
        Leurre.purge(); // force un vrai recalcul, pas un colis laissé par une passe précédente
        LevelChunkSection voilee = Leurre.voile(chunk, reelle);

        boolean vraieDonneeIntacte = reelle.getBlockState(lx, ly, lz).is(Blocks.DIAMOND_ORE);
        boolean envoiCachePendantOcclusion = !voilee.getBlockState(lx, ly, lz).is(Blocks.DIAMOND_ORE);
        if (!vraieDonneeIntacte) {
            correctnessOk = false;
            correctnessLog.append("ÉCHEC : la VRAIE section a été altérée — corruption du monde. ");
        }
        if (!envoiCachePendantOcclusion) {
            correctnessOk = false;
            correctnessLog.append("ÉCHEC : le diamant totalement enfoui est parti tel quel — fuite. ");
        }

        // Une face s'ouvre : le mécanisme normal de mise à jour de bloc doit désormais tout dire.
        level.setBlock(target.above(), Blocks.AIR.defaultBlockState(), flags);
        LevelChunkSection reelleApres = chunk.getSections()[sectionIndex];
        LevelChunkSection voileeApres = Leurre.voile(chunk, reelleApres);
        boolean envoiVraiApresOuverture = voileeApres.getBlockState(lx, ly, lz).is(Blocks.DIAMOND_ORE);
        if (!envoiVraiApresOuverture) {
            correctnessOk = false;
            correctnessLog.append("ÉCHEC : le diamant exposé (une face ouverte) reste caché après "
                    + "invalidation — faux négatif visible du joueur. ");
        }

        // Nettoyage : on referme, pour ne rien laisser d'anormal dans le monde du banc.
        level.setBlock(target.above(), Blocks.STONE.defaultBlockState(), flags);
        level.setBlock(target, Blocks.STONE.defaultBlockState(), flags);
    }

    private static void report() {
        Lanterne.LOG.info("[PALISSADE] ── Verdict de CONFORMITÉ (avant toute vitesse) ──");
        if (!correctnessRan) {
            Lanterne.LOG.error("[PALISSADE] Épreuve de conformité jamais exécutée — banc en cause.");
        } else if (correctnessOk) {
            Lanterne.LOG.info("[PALISSADE] CONFORME : un diamant totalement enfoui ne quitte pas le "
                    + "serveur, la vraie section du chunk reste intacte, et le diamant redevient "
                    + "visible dès qu'une face s'expose.");
        } else {
            Lanterne.LOG.error("[PALISSADE] ÉCHEC DE CONFORMITÉ : {}", correctnessLog);
        }

        Lanterne.LOG.info("[PALISSADE] Sur la grille réellement engendrée ({} chunks) : {} section(s) "
                        + "examinée(s), {} substituée(s) (au moins un bloc protégé non exposé), "
                        + "{} position(s) effectivement cachée(s).",
                GRID_CHUNKS,
                Leurre.sectionsVues() - sectionsVuesAvant,
                Leurre.sectionsSubstituees() - sectionsSubstitueesAvant,
                Leurre.blocsCaches() - blocsCachesAvant);

        double offMedian = median(offNanos);
        double coldValue = onColdNanos[0];
        double warmMedian = median(onWarmNanos);

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[PALISSADE] SANS leurre (%d chunks, %d lecture(s)) : médiane %.2f ms, "
                        + "soit %.4f ms/chunk.",
                GRID_CHUNKS, READINGS - WARMUP, offMedian / 1e6, offMedian / 1e6 / GRID_CHUNKS));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[PALISSADE] AVEC leurre, cache VIDE (premier envoi, pire cas) : %.2f ms, "
                        + "soit %.4f ms/chunk.",
                coldValue / 1e6, coldValue / 1e6 / GRID_CHUNKS));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[PALISSADE] AVEC leurre, cache PLEIN (%d lecture(s), chunks déjà vus) : médiane "
                        + "%.2f ms, soit %.4f ms/chunk.",
                READINGS - WARMUP, warmMedian / 1e6, warmMedian / 1e6 / GRID_CHUNKS));

        if (offMedian <= 0d || warmMedian <= 0d) {
            Lanterne.LOG.warn("[PALISSADE] VERDICT : au moins une médiane nulle ou négative — signal "
                    + "sous le bruit, aucun ratio publié.");
            return;
        }
        double surcoutFroid = (coldValue - offMedian) / GRID_CHUNKS / 1e6;
        double surcoutChaud = (warmMedian - offMedian) / GRID_CHUNKS / 1e6;
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[PALISSADE] VERDICT : surcoût du premier envoi (calcul complet) %.4f ms/chunk ; "
                        + "surcoût une fois le cache par section rempli %.4f ms/chunk (%.1f %% du "
                        + "coût SANS le module). Le coût réel d'un envoi normal en régime établi "
                        + "est ce dernier chiffre, pas le premier.",
                surcoutFroid, surcoutChaud, 100d * warmMedian / offMedian - 100d));
    }

    private static double median(long[] values) {
        long[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0 ? (copy[middle - 1] + copy[middle]) / 2d : copy[middle];
    }
}
