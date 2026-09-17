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
 * La restitution : une grille de chunks déjà écrite, déchargée pour de vrai, relue en vol — avec et
 * sans {@code ChunkDecode}.
 *
 * <h2>Pourquoi {@code lab/Quarry} ne suffisait pas pour ce module précis</h2>
 *
 * <p>{@code Quarry} chronomètre le régime « chargement » en demandant les chunks <b>un par un</b>,
 * chaque appel bloquant jusqu'à son retour avant le suivant ({@code getChunk(..., true)}). C'est le
 * bon protocole pour mesurer un chunk isolé, et c'est exactement celui qui ne peut <b>jamais</b>
 * montrer l'effet de {@code ChunkDecode} : ce module ne change rien tant qu'une seule lecture est en
 * vol à la fois — son bassin séparé n'a de sens que si plusieurs décodages tournent en même temps,
 * pendant qu'un autre flux s'ouvre déjà sur le fil de l'{@code IOWorker}. Cette épreuve reprend donc
 * le protocole à demandes concurrentes de {@code lab/Swarm} (plusieurs requêtes en vol, tick pompé
 * via {@code managedBlock}), appliqué à des chunks <b>déjà écrits</b> plutôt qu'à générer — l'exact
 * inverse de ce que {@code Swarm} mesure, et la confusion que sa propre Javadoc met en garde de ne
 * jamais faire par accident.
 *
 * <h2>L'éviction confirmée, empruntée telle quelle à {@code Quarry}</h2>
 *
 * <p>Sauvegarde forcée ({@code ServerChunkCache.save(true)}), puis attente que {@code getChunkNow}
 * réponde {@code null} pour les {@value #GRID_CHUNKS} chunks de la grille, plus une marge de
 * confirmation — le même protocole, documenté en détail dans {@code Quarry}, et les mêmes raisons de
 * ne jamais lui faire confiance avant l'échéance.
 *
 * <h2>Conformité avant vitesse</h2>
 *
 * <p>La hauteur du terrain ({@code MOTION_BLOCKING_NO_LEAVES}) à un point fixe de chaque chunk est
 * relevée une fois, avant toute éviction. Elle est comparée après CHAQUE relecture, module actif ou
 * non : un seul écart signale un chunk mal assemblé — le bassin séparé qui aurait mélangé deux flux,
 * par exemple — et invalide tout le reste avant même de regarder une horloge.
 */
public final class Restitution {
    /** Côté de la grille de chunks. */
    private static final int SIDE = 12;
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
     * chronométrer.
     *
     * <h2>La course que le premier relevé a débusquée</h2>
     *
     * <p>{@code Settings.setDecode(true)} s'exécute sur le fil principal, mais {@code loadAsync} est
     * intercepté sur le fil {@code worldgen} — un bassin de travail distinct qui peut déjà porter des
     * demandes émises <em>avant</em> le changement de réglage (reliquat de {@code SEEDING} ou de la
     * sauvegarde forcée). Basculer le réglage puis chronométrer dans le même tick laisse ce reliquat
     * se mélanger à la mesure : trois relevés consécutifs ont rendu 0 décodage engagé sur 536 à 784
     * appels, contre 392 sur un relevé isolé, <b>sans qu'une seule ligne de code n'ait changé entre les
     * deux</b> — la signature d'une course, pas d'un défaut déterministe. Cette pause laisse le bassin
     * de l'{@code IOWorker} se vider de tout travail antérieur au changement avant que le chronomètre
     * ne parte.
     */
    private static final int SETTLE_AFTER_FLAG_TICKS = 20;

    private enum Step {
        OFF, SEEDING, SAVE_1, WAIT_1, SETTLE_ON, MEASURE_ON,
        SAVE_2, WAIT_2, SETTLE_OFF, MEASURE_OFF, DONE
    }

    private static Step step = Step.OFF;
    private static MinecraftServer host;

    private static int issued;
    private static int done;
    private static long phaseStart;
    private static final List<CompletableFuture<?>> IN_FLIGHT = new ArrayList<>();

    private static int evictWait;
    private static int settleLeft;
    /** Ticks restants avant de commencer à chronométrer — voir {@link #SETTLE_AFTER_FLAG_TICKS}. */
    private static int flagSettle;

    private static final int[] baselineHeights = new int[GRID_CHUNKS];
    private static final int[] onHeights = new int[GRID_CHUNKS];
    private static final int[] offHeights = new int[GRID_CHUNKS];
    private static boolean conformOn = true;
    private static boolean conformOff = true;

    private static double onMs;
    private static double offMs;

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
        step = Step.SEEDING;
        Lanterne.LOG.info("[RESTITUTION] Épreuve lancée — {} chunks en ({},{}), {} demande(s) en vol, "
                + "{} cœur(s) promis à la machine virtuelle (bassin de décodage : {} fil(s)).",
                GRID_CHUNKS, ORIGIN_CX, ORIGIN_CZ, WIDTH, Quota.cores(),
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
                if (pumpGrid(server, source, false)) {
                    Lanterne.LOG.info("[RESTITUTION] Grille écrite sur disque. Relevé des hauteurs de "
                            + "référence avant toute éviction.");
                    for (int i = 0; i < GRID_CHUNKS; i++) {
                        baselineHeights[i] = heightAt(level, i);
                    }
                    step = Step.SAVE_1;
                }
            }
            case SAVE_1 -> {
                source.save(true);
                Lanterne.LOG.info("[RESTITUTION] Sauvegarde forcée — attente de l'éviction mémoire avant "
                        + "la moitié « module actif ».");
                evictWait = 0;
                settleLeft = 0;
                step = Step.WAIT_1;
            }
            case WAIT_1 -> waitEviction(source, () -> {
                Settings.setDecode(true);
                ChunkDecode.resetStats();
                flagSettle = SETTLE_AFTER_FLAG_TICKS;
                step = Step.SETTLE_ON;
            }, () -> {
                // Éviction jamais confirmée : rien à mesurer côté "actif", on passe directement à
                // "éteint" plutôt que de publier un chiffre douteux.
                Lanterne.LOG.error("[RESTITUTION] Moitié « actif » ABANDONNÉE — éviction non confirmée.");
                onMs = -1d;
                Settings.setDecode(false);
                step = Step.SAVE_2;
            });
            case SETTLE_ON -> {
                if (--flagSettle <= 0) {
                    Lanterne.LOG.info("[RESTITUTION] Réglage stabilisé ({} ticks) — Settings.decode()={}.",
                            SETTLE_AFTER_FLAG_TICKS, Settings.decode());
                    resetGrid();
                    phaseStart = System.nanoTime();
                    step = Step.MEASURE_ON;
                }
            }
            case MEASURE_ON -> {
                if (pumpGrid(server, source, true)) {
                    onMs = (System.nanoTime() - phaseStart) / 1.0E6d;
                    for (int i = 0; i < GRID_CHUNKS; i++) {
                        onHeights[i] = heightAt(level, i);
                        if (onHeights[i] != baselineHeights[i]) {
                            conformOn = false;
                        }
                    }
                    Lanterne.LOG.info(String.format(Locale.ROOT,
                            "[RESTITUTION] Module actif : %d chunk(s) relus en %.1f ms.",
                            GRID_CHUNKS, onMs));
                    step = Step.SAVE_2;
                }
            }
            case SAVE_2 -> {
                source.save(true);
                Lanterne.LOG.info("[RESTITUTION] Sauvegarde forcée — attente de l'éviction mémoire avant "
                        + "la moitié « module éteint ».");
                evictWait = 0;
                settleLeft = 0;
                step = Step.WAIT_2;
            }
            case WAIT_2 -> waitEviction(source, () -> {
                Settings.setDecode(false);
                flagSettle = SETTLE_AFTER_FLAG_TICKS;
                step = Step.SETTLE_OFF;
            }, () -> {
                Lanterne.LOG.error("[RESTITUTION] Moitié « éteint » ABANDONNÉE — éviction non confirmée.");
                offMs = -1d;
                finish();
            });
            case SETTLE_OFF -> {
                if (--flagSettle <= 0) {
                    Lanterne.LOG.info("[RESTITUTION] Réglage stabilisé ({} ticks) — Settings.decode()={}.",
                            SETTLE_AFTER_FLAG_TICKS, Settings.decode());
                    resetGrid();
                    phaseStart = System.nanoTime();
                    step = Step.MEASURE_OFF;
                }
            }
            case MEASURE_OFF -> {
                if (pumpGrid(server, source, true)) {
                    offMs = (System.nanoTime() - phaseStart) / 1.0E6d;
                    for (int i = 0; i < GRID_CHUNKS; i++) {
                        offHeights[i] = heightAt(level, i);
                        if (offHeights[i] != baselineHeights[i]) {
                            conformOff = false;
                        }
                    }
                    Lanterne.LOG.info(String.format(Locale.ROOT,
                            "[RESTITUTION] Module éteint : %d chunk(s) relus en %.1f ms.",
                            GRID_CHUNKS, offMs));
                    Settings.setDecode(false);
                    finish();
                }
            }
            default -> { }
        }
    }

    /**
     * Maintient {@value #WIDTH} demandes en vol jusqu'à ce que toute la grille soit servie.
     *
     * @param timed vrai pendant une phase mesurée : ne change rien au protocole, seulement présent
     *              pour lisibilité à l'appel — le chronométrage lui-même vit dans {@link #tick}.
     * @return vrai quand les {@value #GRID_CHUNKS} chunks ont abouti
     */
    private static boolean pumpGrid(MinecraftServer server, ServerChunkCache source, boolean timed) {
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
                    Lanterne.LOG.info("[RESTITUTION] Éviction confirmée puis stable {} ticks de plus.",
                            SETTLE_AFTER_EVICTION_TICKS);
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

    private static void report() {
        Lanterne.LOG.info("[RESTITUTION] ── Verdict de CONFORMITÉ (avant toute vitesse) ──");
        boolean conform = (onMs < 0d || conformOn) && (offMs < 0d || conformOff);
        if (!conform) {
            Lanterne.LOG.error("[RESTITUTION] ÉCHEC DE CONFORMITÉ : au moins un chunk relu ne rend pas "
                    + "la même hauteur qu'avant l'éviction (actif conforme={}, éteint conforme={}). Le "
                    + "module doit être retiré tant que ce n'est pas expliqué.", conformOn, conformOff);
            return;
        }
        Lanterne.LOG.info("[RESTITUTION] CONFORME : les {} chunks relus rendent la même hauteur de "
                + "terrain qu'avant l'éviction, module actif ou non.", GRID_CHUNKS);

        if (onMs < 0d || offMs < 0d) {
            Lanterne.LOG.error("[RESTITUTION] VERDICT DE VITESSE : indisponible — au moins une moitié a "
                    + "été abandonnée faute d'éviction confirmée. Rien n'est publié.");
            return;
        }

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RESTITUTION] Module actif  : %.1f ms pour %d chunks, soit %.1f chunk(s)/s.",
                onMs, GRID_CHUNKS, GRID_CHUNKS / (onMs / 1000d)));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RESTITUTION] Module éteint : %.1f ms pour %d chunks, soit %.1f chunk(s)/s.",
                offMs, GRID_CHUNKS, GRID_CHUNKS / (offMs / 1000d)));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RESTITUTION] Décodages effectués sur le bassin séparé depuis le démarrage : %d, "
                        + "%.3f ms/décodage en moyenne.",
                ChunkDecode.decoded(), ChunkDecode.averageMillis()));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RESTITUTION] DIAGNOSTIC : loadAsync intercepté %d fois, dont %d avec le module engagé.",
                ChunkDecode.entered(), ChunkDecode.engaged()));

        double ratio = onMs <= 0d ? 0d : offMs / onMs;
        Lanterne.LOG.info("[RESTITUTION] ── Verdict de VITESSE ──");
        if (ratio > 1.05d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[RESTITUTION] VERDICT : gain ×%.2f (%.0f %% de temps en moins pour relire la "
                            + "grille avec %d demandes en vol).",
                    ratio, (1d - 1d / ratio) * 100d, WIDTH));
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
}
