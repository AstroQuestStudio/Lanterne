package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.ChunkDecode;
import fr.clubcitrouille.lanterne.core.Quota;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * La restitution : plusieurs courtes relectures d'une grille déjà écrite, déchargée pour de vrai à
 * chaque fois — avec et sans {@code ChunkDecode}, médiane retenue de chaque côté.
 *
 * <h2>Pourquoi {@code lab/Quarry} ne suffisait pas pour ce module précis</h2>
 *
 * <p>{@code Quarry} chronomètre le régime « chargement » en demandant les chunks <b>un par un</b>,
 * chaque appel bloquant jusqu'à son retour avant le suivant ({@code getChunk(..., true)}). C'est le
 * bon protocole pour mesurer un chunk isolé, et c'est exactement celui qui ne peut <b>jamais</b>
 * montrer l'effet de {@code ChunkDecode} : ce module ne change rien tant qu'une seule lecture est en
 * vol à la fois. Cette épreuve reprend donc le protocole à demandes concurrentes de {@code lab/Swarm}
 * (plusieurs requêtes en vol, tick pompé via {@code managedBlock}), appliqué à des chunks
 * <b>déjà écrits</b> plutôt qu'à générer.
 *
 * <h2>Pourquoi une seule longue fenêtre par côté ne suffisait pas non plus</h2>
 *
 * <p>Une première version chronométrait une seule fenêtre de 144 chunks par côté. Une fois une course
 * de synchronisation corrigée (voir {@code Settings#decode} et {@link #SETTLE_AFTER_FLAG_TICKS}), la
 * seconde moitié mesurée s'est mise à durer 90 à 145 fois plus longtemps que la première — alors que
 * les deux tournaient sur du code rigoureusement identique ({@code ChunkDecode.engaged()} valait zéro
 * des deux côtés). Une pause du ramasse-miettes concentrée sur une seule fenêtre de plusieurs secondes
 * suffit à produire exactement ce résultat, et {@link fr.clubcitrouille.lanterne.report.Bench} comme
 * {@link Flow} expliquent déjà pourquoi ce dépôt ne fait jamais confiance à une mesure unique pour
 * cette raison précise. La réponse retenue ici est la même que celle de {@code Flow} : {@link
 * #READINGS} courtes relectures indépendantes par côté, chauffe jetée, médiane retenue sur le reste —
 * une pause GC isolée ne peut plus alors dominer qu'un seul relevé parmi plusieurs, au lieu d'une
 * fenêtre entière.
 *
 * <h2>L'éviction confirmée, empruntée telle quelle à {@code Quarry}</h2>
 *
 * <p>Sauvegarde forcée ({@code ServerChunkCache.save(true)}), puis attente que {@code getChunkNow}
 * réponde {@code null} pour les {@value #GRID_CHUNKS} chunks de la grille, plus une marge de
 * confirmation — répétée avant <b>chaque</b> relevé, pas seulement une fois par côté : sans quoi un
 * relevé mesurerait une relecture mémoire plutôt qu'une lecture disque, exactement le piège que
 * {@code lab/Swarm} a lui-même appris à la dure.
 *
 * <h2>Conformité avant vitesse</h2>
 *
 * <p>La hauteur du terrain ({@code MOTION_BLOCKING_NO_LEAVES}) à un point fixe de chaque chunk est
 * relevée une fois, avant toute éviction. Elle est comparée après CHAQUE relevé, module actif ou
 * non : un seul écart invalide tout le reste avant même de regarder une horloge.
 */
public final class Restitution {
    /** Côté de la grille de chunks — réduit par rapport à la première version (144) : {@value
     *  #READINGS} relevés de {@value #GRID_CHUNKS} coûtent, en éviction confirmée comprise, à peu
     *  près ce que coûtait une seule fenêtre de 144. */
    private static final int SIDE = 6;
    private static final int GRID_CHUNKS = SIDE * SIDE;

    /** Origine (en coordonnées de chunk), loin de toute autre épreuve du dépôt. */
    private static final int ORIGIN_CX = -3125;
    private static final int ORIGIN_CZ = -3125;

    /** Demandes en vol simultanément — voir {@code lab/Swarm}, qui a établi cette échelle. */
    private static final int WIDTH = 32;

    private static final int MAX_EVICT_WAIT = 400;
    private static final int SETTLE_AFTER_EVICTION_TICKS = 20;

    /**
     * Ticks d'attente pure après avoir changé {@link Settings#decode}, avant de commencer à
     * chronométrer — seulement à l'entrée de chaque côté, pas avant chaque relevé.
     *
     * <h2>La course que le premier relevé a débusquée</h2>
     *
     * <p>{@code Settings.setDecode(true)} s'exécute sur le fil principal, mais {@code loadAsync} est
     * intercepté sur le fil {@code worldgen} — un bassin de travail distinct qui peut déjà porter des
     * demandes émises <em>avant</em> le changement de réglage. Basculer le réglage puis chronométrer
     * dans le même tick laisse ce reliquat se mélanger à la mesure : trois relevés consécutifs ont
     * rendu 0 décodage engagé sur 536 à 784 appels, contre 392 sur un relevé isolé, sans qu'une seule
     * ligne de code n'ait changé entre les deux — la signature d'une course, pas d'un défaut
     * déterministe. Cette pause laisse le bassin de l'{@code IOWorker} se vider de tout travail
     * antérieur au changement avant que le premier relevé ne commence.
     */
    private static final int SETTLE_AFTER_FLAG_TICKS = 20;

    /** Relevés par côté, chauffe comprise — même esprit que {@link Flow#READINGS}, revu à la baisse
     *  parce que chaque relevé ici coûte un cycle d'éviction confirmée entier, pas une pose de bloc. */
    private static final int READINGS = 8;
    /** Parmi les {@link #READINGS} relevés, ceux qu'on jette en tête. */
    private static final int WARMUP = 2;

    private enum Step { OFF, SEEDING, SAVE, WAIT, SETTLE, MEASURE, DONE }
    private enum Phase { ON, OFF }

    private static Step step = Step.OFF;
    private static Phase phase = Phase.ON;
    private static MinecraftServer host;

    private static int issued;
    private static int done;
    private static long phaseStart;
    private static final List<CompletableFuture<?>> IN_FLIGHT = new ArrayList<>();

    private static int evictWait;
    private static int settleLeft;
    private static int flagSettle;
    private static int reading;

    private static final int[] baselineHeights = new int[GRID_CHUNKS];
    private static boolean conform = true;

    private static final long[] onNanos = new long[READINGS];
    private static final long[] offNanos = new long[READINGS];

    private Restitution() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        ServerLevel level = server.overworld();
        WorldBorder border = level.getWorldBorder();
        ChunkPos near = new ChunkPos(ORIGIN_CX, ORIGIN_CZ);
        ChunkPos far = new ChunkPos(ORIGIN_CX + SIDE - 1, ORIGIN_CZ + SIDE - 1);
        if (!border.isWithinBounds(near) || !border.isWithinBounds(far)) {
            Lanterne.LOG.error("[RESTITUTION] MESURE REFUSÉE : la grille de {}×{} chunks déborde de la "
                    + "bordure du monde.", SIDE, SIDE);
            host.halt(false);
            return;
        }

        Settings.setEnabled(true);
        Settings.setDecode(false);
        ChunkDecode.resetStats();
        resetGrid();
        conform = true;
        phase = Phase.ON;
        reading = 0;
        step = Step.SEEDING;
        Lanterne.LOG.info("[RESTITUTION] Épreuve lancée — {} chunks en ({},{}), {} relevé(s) par côté "
                        + "dont {} de chauffe, {} demande(s) en vol, {} cœur(s) promis à la machine "
                        + "virtuelle (bassin de décodage : {} fil(s)).",
                GRID_CHUNKS, ORIGIN_CX, ORIGIN_CZ, READINGS, WARMUP, WIDTH, Quota.cores(),
                Math.max(1, Math.min(Quota.cores() - 1, 16)));
    }

    private static void resetGrid() {
        issued = 0;
        done = 0;
        IN_FLIGHT.clear();
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();
        ServerChunkCache source = level.getChunkSource();

        switch (step) {
            case SEEDING -> {
                if (pumpGrid(server, source)) {
                    for (int i = 0; i < GRID_CHUNKS; i++) {
                        baselineHeights[i] = heightAt(level, i);
                    }
                    Lanterne.LOG.info("[RESTITUTION] Grille écrite sur disque. Hauteurs de référence "
                            + "relevées avant toute éviction.");
                    step = Step.SAVE;
                }
            }
            case SAVE -> {
                source.save(true);
                evictWait = 0;
                settleLeft = 0;
                step = Step.WAIT;
            }
            case WAIT -> waitEviction(source, () -> {
                if (reading == 0) {
                    // Seule la toute première entrée dans un côté doit stabiliser le réglage — les
                    // relevés suivants du même côté n'ont rien changé qui doive se stabiliser.
                    Settings.setDecode(phase == Phase.ON);
                    if (phase == Phase.ON) {
                        ChunkDecode.resetStats();
                    }
                    flagSettle = SETTLE_AFTER_FLAG_TICKS;
                    step = Step.SETTLE;
                } else {
                    resetGrid();
                    phaseStart = System.nanoTime();
                    step = Step.MEASURE;
                }
            }, () -> {
                Lanterne.LOG.error("[RESTITUTION] Relevé {} de la moitié « {} » ABANDONNÉ — éviction "
                                + "non confirmée.", reading + 1, phase == Phase.ON ? "actif" : "éteint");
                onNanos[0] = -1L; // marque l'échec : voir report(), un seul -1 suffit à invalider le côté
                if (phase == Phase.ON) {
                    switchPhase();
                } else {
                    finish();
                }
            });
            case SETTLE -> {
                if (--flagSettle <= 0) {
                    resetGrid();
                    phaseStart = System.nanoTime();
                    step = Step.MEASURE;
                }
            }
            case MEASURE -> {
                if (pumpGrid(server, source)) {
                    long elapsed = System.nanoTime() - phaseStart;
                    for (int i = 0; i < GRID_CHUNKS; i++) {
                        if (heightAt(level, i) != baselineHeights[i]) {
                            conform = false;
                        }
                    }
                    (phase == Phase.ON ? onNanos : offNanos)[reading] = elapsed;
                    reading++;
                    if (reading >= READINGS) {
                        Lanterne.LOG.info(String.format(Locale.ROOT,
                                "[RESTITUTION] Moitié « %s » terminée — %d relevé(s), médiane %.1f ms.",
                                phase == Phase.ON ? "actif" : "éteint", READINGS,
                                median(phase == Phase.ON ? onNanos : offNanos) / 1.0E6d));
                        if (phase == Phase.ON) {
                            switchPhase();
                        } else {
                            finish();
                        }
                    } else {
                        step = Step.SAVE;
                    }
                }
            }
            default -> { }
        }
    }

    private static void switchPhase() {
        phase = Phase.OFF;
        reading = 0;
        step = Step.SAVE;
    }

    /**
     * Maintient {@value #WIDTH} demandes en vol jusqu'à ce que toute la grille soit servie.
     *
     * @return vrai quand les {@value #GRID_CHUNKS} chunks ont abouti
     */
    private static boolean pumpGrid(MinecraftServer server, ServerChunkCache source) {
        IN_FLIGHT.removeIf(pending -> {
            if (pending.isDone()) {
                done++;
                return true;
            }
            return false;
        });

        while (IN_FLIGHT.size() < WIDTH && issued < GRID_CHUNKS) {
            ChunkPos pos = chunkPosAt(issued);
            IN_FLIGHT.add(source.getChunkFuture(pos.x(), pos.z(), ChunkStatus.FULL, true));
            issued++;
        }

        if (!IN_FLIGHT.isEmpty()) {
            server.managedBlock(() -> {
                for (CompletableFuture<?> pending : IN_FLIGHT) {
                    if (pending.isDone()) {
                        return true;
                    }
                }
                return false;
            });
        }

        return done >= GRID_CHUNKS;
    }

    private static void waitEviction(ServerChunkCache source, Runnable onConfirmed, Runnable onTimeout) {
        boolean evicted = gridEvicted(source);

        if (settleLeft > 0) {
            if (!evicted) {
                Lanterne.LOG.warn("[RESTITUTION] Un chunk est redevenu résident pendant la marge de "
                        + "confirmation — on recompte depuis zéro.");
                settleLeft = 0;
            } else {
                settleLeft--;
                if (settleLeft == 0) {
                    onConfirmed.run();
                }
                return;
            }
        }

        if (evicted) {
            settleLeft = SETTLE_AFTER_EVICTION_TICKS;
            return;
        }

        evictWait++;
        if (evictWait > MAX_EVICT_WAIT) {
            onTimeout.run();
        }
    }

    private static boolean gridEvicted(ServerChunkCache source) {
        for (int i = 0; i < GRID_CHUNKS; i++) {
            ChunkPos pos = chunkPosAt(i);
            if (source.getChunkNow(pos.x(), pos.z()) != null) {
                return false;
            }
        }
        return true;
    }

    private static int heightAt(ServerLevel level, int index) {
        ChunkPos pos = chunkPosAt(index);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (pos.x() << 4) + 8, (pos.z() << 4) + 8);
    }

    private static ChunkPos chunkPosAt(int index) {
        return new ChunkPos(ORIGIN_CX + index % SIDE, ORIGIN_CZ + index / SIDE);
    }

    private static void finish() {
        step = Step.DONE;
        report();
        if (host != null) {
            host.halt(false);
        }
    }

    /** La médiane des relevés utiles, chauffe jetée — même geste que {@code Flow#median}. */
    private static double median(long[] values) {
        long[] copy = new long[READINGS - WARMUP];
        System.arraycopy(values, WARMUP, copy, 0, copy.length);
        java.util.Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0 ? (copy[middle - 1] + copy[middle]) / 2d : copy[middle];
    }

    private static void report() {
        Lanterne.LOG.info("[RESTITUTION] ── Verdict de CONFORMITÉ (avant toute vitesse) ──");
        boolean abandoned = onNanos[0] == -1L || offNanos[0] == -1L;
        if (abandoned) {
            Lanterne.LOG.error("[RESTITUTION] VERDICT DE VITESSE : indisponible — au moins un relevé a "
                    + "été abandonné faute d'éviction confirmée. Rien n'est publié.");
            return;
        }
        if (!conform) {
            Lanterne.LOG.error("[RESTITUTION] ÉCHEC DE CONFORMITÉ : au moins un chunk relu, sur un des "
                    + "relevés, ne rend pas la même hauteur qu'avant l'éviction. Le module doit être "
                    + "retiré tant que ce n'est pas expliqué.");
            return;
        }
        Lanterne.LOG.info("[RESTITUTION] CONFORME : tous les relevés rendent la même hauteur de terrain "
                + "qu'avant l'éviction, module actif ou non.");

        double onMedianMs = median(onNanos) / 1.0E6d;
        double offMedianMs = median(offNanos) / 1.0E6d;

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RESTITUTION] Module actif  : médiane %.1f ms pour %d chunks, soit %.1f chunk(s)/s "
                        + "(%d relevé(s) utile(s) sur %d).",
                onMedianMs, GRID_CHUNKS, GRID_CHUNKS / (onMedianMs / 1000d), READINGS - WARMUP, READINGS));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RESTITUTION] Module éteint : médiane %.1f ms pour %d chunks, soit %.1f chunk(s)/s "
                        + "(%d relevé(s) utile(s) sur %d).",
                offMedianMs, GRID_CHUNKS, GRID_CHUNKS / (offMedianMs / 1000d), READINGS - WARMUP, READINGS));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RESTITUTION] Décodages effectués sur le bassin séparé depuis le démarrage : %d, "
                        + "%.3f ms/décodage en moyenne.",
                ChunkDecode.decoded(), ChunkDecode.averageMillis()));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RESTITUTION] DIAGNOSTIC : loadAsync intercepté %d fois, dont %d avec le module engagé.",
                ChunkDecode.entered(), ChunkDecode.engaged()));
        Lanterne.LOG.info("[RESTITUTION] Relevés bruts (ms), actif : {}", formatMs(onNanos));
        Lanterne.LOG.info("[RESTITUTION] Relevés bruts (ms), éteint : {}", formatMs(offNanos));

        double ratio = onMedianMs <= 0d ? 0d : offMedianMs / onMedianMs;
        Lanterne.LOG.info("[RESTITUTION] ── Verdict de VITESSE ──");
        if (ratio > 1.05d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[RESTITUTION] VERDICT : gain ×%.2f (%.0f %% de temps en moins pour relire la "
                            + "grille avec %d demandes en vol, médiane sur %d relevés).",
                    ratio, (1d - 1d / ratio) * 100d, WIDTH, READINGS - WARMUP));
        } else if (ratio < 0.95d) {
            Lanterne.LOG.warn(String.format(Locale.ROOT,
                    "[RESTITUTION] VERDICT : PERTE ×%.2f — plus cher avec le module. Ne pas l'activer "
                            + "par défaut dans cet état.", ratio));
        } else {
            Lanterne.LOG.info("[RESTITUTION] VERDICT : aucun effet mesurable (écart sous le bruit de "
                    + "fond).");
        }
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RESTITUTION] Rappel : %d cœur(s) annoncé(s) à cette machine virtuelle. Sur la cible "
                        + "mono-cœur du déploiement dédié, le bassin de décodage se réduit à un seul "
                        + "fil et ce verdict ne s'y transpose pas — il ne vaut que pour du matériel "
                        + "multi-cœurs.",
                Quota.cores()));
    }

    private static String formatMs(long[] nanos) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < nanos.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            if (i == WARMUP) {
                sb.append("| "); // sépare visuellement la chauffe jetée des relevés retenus
            }
            sb.append(String.format(Locale.ROOT, "%.1f", nanos[i] / 1.0E6d));
        }
        return sb.append(']').toString();
    }
}
