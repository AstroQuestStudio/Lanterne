package fr.clubcitrouille.lanterne.lab;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Impasse;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'épreuve du coincé : un cochon enfermé qui vise, tick après tick, un point qu'il ne peut pas
 * atteindre. C'est le scénario exact que {@link Impasse} vise — un loup derrière une porte fermée,
 * un golem qui vise une position murée.
 *
 * <h2>Pourquoi commander le déplacement directement, sans passer par l'IA</h2>
 *
 * <p>Les intervalles de reconsidération des sélecteurs d'objectifs varient d'un mob à l'autre et ne
 * sont pas faits pour être pilotés depuis un banc. On appelle donc {@code PathNavigation.moveTo(x,
 * y, z, speed)} nous-mêmes, une fois par tick : c'est exactement le chemin que
 * {@code createPath(BlockPos, int)} emprunte pour atteindre {@code createPath(Set, int, boolean,
 * int, float)}, le point d'accroche vérifié de {@code PathfindMixin} — et vanilla n'y court-circuite
 * rien tant qu'aucun chemin valide n'existe déjà. Un chemin qui vient d'échouer laisse justement
 * {@code this.path} nul : rien à réutiliser, donc une vraie recherche à chaque appel sans le module.
 *
 * <h2>Conformité avant vitesse — la même règle que {@code Volley}</h2>
 *
 * <p>Le cochon est scellé dans une boîte de pierre pleine ; la cible est à vingt blocs, dehors. Le
 * verdict de conformité est simple et absolu : le cochon ne doit <b>jamais</b> sortir de la boîte, ni
 * avec le module ni sans lui. Une seule position hors de la boîte signale un chemin inventé, et
 * invalide tout le reste.
 */
public final class Coince {
    private static final int CENTER_X = -1536;
    private static final int CENTER_Z = -1536;
    /** Rayon (excentré) de la boîte scellée, en blocs. */
    private static final int BOX_RADIUS = 3;
    /** Distance de la cible, hors de la boîte, jamais atteignable. */
    private static final int TARGET_OFFSET = 20;

    /** Ticks mesurés par moitié, chauffe comprise. */
    private static final int TICKS = 120;
    private static final int WARMUP_TICKS = 20;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int BUILD_FLAGS = Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE;

    private enum Step { OFF, RUN_ON, RUN_OFF, DONE }

    private static Step step = Step.OFF;
    private static int tick;
    private static int floorY;
    private static Pig subject;
    private static MinecraftServer host;

    private static final long[] onNanos = new long[TICKS - WARMUP_TICKS];
    private static final long[] offNanos = new long[TICKS - WARMUP_TICKS];
    private static long onSkippedBefore;
    private static long onRecordedBefore;
    private static long offSkippedBefore;
    private static long offRecordedBefore;
    private static long onSkipped;
    private static long onRecorded;
    private static long offSkipped;
    private static long offRecorded;
    private static boolean escapedOn;
    private static boolean escapedOff;

    private Coince() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        ServerLevel level = server.overworld();
        floorY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, CENTER_X, CENTER_Z) + 24;

        int chunkX = CENTER_X >> 4;
        int chunkZ = CENTER_Z >> 4;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setChunkForced(chunkX + dx, chunkZ + dz, true);
            }
        }
        Lanterne.LOG.info("[COINCÉ] Vingt-cinq chunks forcés autour de ({}, {}).", chunkX, chunkZ);

        buildBox(level);
        Settings.setEnabled(true);
        step = Step.RUN_ON;
        Settings.setImpasse(true);
        onSkippedBefore = Impasse.skipped();
        onRecordedBefore = Impasse.recorded();
        tick = 0;
        spawnSubject(level);
        Lanterne.LOG.info("[COINCÉ] Première moitié, module IMPASSE actif — {} ticks, {} de chauffe.",
                TICKS, WARMUP_TICKS);
    }

    private static void buildBox(ServerLevel level) {
        BlockPos min = new BlockPos(CENTER_X - BOX_RADIUS, floorY, CENTER_Z - BOX_RADIUS);
        BlockPos max = new BlockPos(CENTER_X + BOX_RADIUS, floorY + BOX_RADIUS * 2, CENTER_Z + BOX_RADIUS);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            boolean shell = pos.getX() == min.getX() || pos.getX() == max.getX()
                    || pos.getY() == min.getY() || pos.getY() == max.getY()
                    || pos.getZ() == min.getZ() || pos.getZ() == max.getZ();
            level.setBlock(pos, shell ? STONE : AIR, BUILD_FLAGS);
        }
        // La cible : loin dehors, sur un sol dégagé, jamais accessible depuis l'intérieur scellé.
        BlockPos targetGround = new BlockPos(CENTER_X + TARGET_OFFSET, floorY, CENTER_Z + TARGET_OFFSET);
        for (BlockPos pos : BlockPos.betweenClosed(
                targetGround.offset(-1, 0, -1), targetGround.offset(1, 2, 1))) {
            level.setBlock(pos, pos.getY() == floorY ? STONE : AIR, BUILD_FLAGS);
        }
    }

    private static void spawnSubject(ServerLevel level) {
        Pig pig = net.minecraft.world.entity.EntityTypes.PIG.create(level, EntitySpawnReason.COMMAND);
        if (pig == null) {
            Lanterne.LOG.error("[COINCÉ] impossible de créer le cochon — épreuve abandonnée.");
            step = Step.DONE;
            return;
        }
        pig.snapTo(CENTER_X + 0.5d, floorY + 1d, CENTER_Z + 0.5d, 0f, 0f);
        pig.setNoAi(false);
        pig.setPersistenceRequired();
        level.addFreshEntity(pig);
        subject = pig;
    }

    public static void tick(MinecraftServer server) {
        if (!running() || subject == null || !subject.isAlive()) {
            return;
        }
        ServerLevel level = server.overworld();

        double tx = CENTER_X + TARGET_OFFSET + 0.5d;
        double tz = CENTER_Z + TARGET_OFFSET + 0.5d;
        long start = System.nanoTime();
        subject.getNavigation().moveTo(tx, floorY + 1d, tz, 1.0d);
        long elapsed = System.nanoTime() - start;

        boolean outside = Math.abs(subject.getX() - (CENTER_X + 0.5d)) > BOX_RADIUS + 1d
                || Math.abs(subject.getZ() - (CENTER_Z + 0.5d)) > BOX_RADIUS + 1d;

        if (tick >= WARMUP_TICKS) {
            int index = tick - WARMUP_TICKS;
            if (step == Step.RUN_ON) {
                onNanos[index] = elapsed;
                escapedOn |= outside;
            } else {
                offNanos[index] = elapsed;
                escapedOff |= outside;
            }
        } else if (outside) {
            // Même pendant la chauffe : une évasion est une évasion, on ne la jette jamais.
            if (step == Step.RUN_ON) {
                escapedOn = true;
            } else {
                escapedOff = true;
            }
        }

        tick++;
        if (tick >= TICKS) {
            advance(level);
        }
    }

    private static void advance(ServerLevel level) {
        if (step == Step.RUN_ON) {
            onSkipped = Impasse.skipped() - onSkippedBefore;
            onRecorded = Impasse.recorded() - onRecordedBefore;
            subject.discard();
            subject = null;
            step = Step.RUN_OFF;
            Settings.setImpasse(false);
            offSkippedBefore = Impasse.skipped();
            offRecordedBefore = Impasse.recorded();
            tick = 0;
            spawnSubject(level);
            Lanterne.LOG.info("[COINCÉ] Seconde moitié, module IMPASSE éteint.");
            return;
        }

        offSkipped = Impasse.skipped() - offSkippedBefore;
        offRecorded = Impasse.recorded() - offRecordedBefore;
        if (subject != null) {
            subject.discard();
            subject = null;
        }
        step = Step.DONE;
        Settings.setImpasse(true);
        report();
        if (host != null) {
            host.halt(false);
        }
    }

    private static void report() {
        Lanterne.LOG.info("[COINCÉ] ── Verdict de CONFORMITÉ (avant toute vitesse) ──");
        boolean conform = !escapedOn && !escapedOff;
        if (!conform) {
            Lanterne.LOG.error("[COINCÉ] ÉCHEC DE CONFORMITÉ : le cochon a quitté la boîte scellée "
                    + "(actif={}, éteint={}). Un chemin a été inventé — le module doit être retiré tant "
                    + "que ce n'est pas expliqué.", escapedOn, escapedOff);
            return;
        }
        Lanterne.LOG.info("[COINCÉ] CONFORME : le cochon n'a jamais quitté la boîte scellée, module "
                + "actif ou non — aucun chemin inventé.");

        if (onRecorded == 0L && offRecorded == 0L) {
            Lanterne.LOG.error("[COINCÉ] ÉPREUVE INVALIDE : aucun échec de recherche enregistré d'un "
                    + "côté comme de l'autre. Le protocole est en cause — la cible est peut-être "
                    + "atteignable, ou le cochon n'a pas navigué du tout.");
            return;
        }

        double onMedian = median(onNanos);
        double offMedian = median(offNanos);
        long onTotal = total(onNanos);
        long offTotal = total(offNanos);

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[COINCÉ] Module actif  : médiane %.3f µs/appel · total %.2f ms sur %d appels · "
                        + "%d recherche(s) évitée(s), %d retenue(s) comme référence.",
                onMedian / 1e3, onTotal / 1e6, onNanos.length, onSkipped, onRecorded));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[COINCÉ] Module éteint : médiane %.3f µs/appel · total %.2f ms sur %d appels · "
                        + "%d recherche(s) évitée(s), %d retenue(s) comme référence.",
                offMedian / 1e3, offTotal / 1e6, offNanos.length, offSkipped, offRecorded));

        if (offSkipped > 0L) {
            Lanterne.LOG.error("[COINCÉ] ÉPREUVE INVALIDE : des recherches ont été évitées alors que le "
                    + "module était éteint ({}). L'interrupteur ne coupe pas ce qu'il devrait.",
                    offSkipped);
            return;
        }

        double ratio = onMedian <= 0d ? 0d : offMedian / onMedian;
        Lanterne.LOG.info("[COINCÉ] ── Verdict de VITESSE ──");
        if (ratio > 1.05d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[COINCÉ] VERDICT : gain ×%.2f (%.0f %% de temps en moins par appel de "
                            + "navigation, sur un cochon authentiquement bloqué).",
                    ratio, (1d - 1d / ratio) * 100d));
        } else if (ratio < 0.95d) {
            Lanterne.LOG.warn(String.format(Locale.ROOT,
                    "[COINCÉ] VERDICT : PERTE ×%.2f — plus cher avec le module. Ne pas l'activer par "
                            + "défaut dans cet état.", ratio));
        } else {
            Lanterne.LOG.info("[COINCÉ] VERDICT : aucun effet mesurable (écart sous le bruit de fond).");
        }
    }

    private static double median(long[] values) {
        long[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0 ? (copy[middle - 1] + copy[middle]) / 2d : copy[middle];
    }

    private static long total(long[] values) {
        long sum = 0L;
        for (long value : values) {
            sum += value;
        }
        return sum;
    }
}
