package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'épreuve du four : la cuisson dure-t-elle exactement aussi longtemps ?
 *
 * <h2>La question que le sommeil à échéance doit affronter</h2>
 *
 * <p>Faire dormir un four repose sur un raisonnement : entre deux échéances, rien d'observable ne se
 * produit, donc on peut ne rien faire et rattraper d'un coup. Le raisonnement est juste ; sa mise en
 * œuvre peut être fausse d'un tick, et un tick suffit à décaler une horloge à redstone.
 *
 * <p>Ce projet a appris à ne pas se fier au raisonnement seul. La cadence appliquée aux créatures
 * paraissait tout aussi solide, et elle divisait les chutes par seize sans que rien ne le signale —
 * il a fallu une épreuve pour le voir.
 *
 * <p>On mesure donc la seule chose qui compte : <b>combien de ticks entre la mise en route et
 * l'apparition du résultat</b>, avec et sans le mod. Si l'écart n'est pas nul, le sommeil est
 * retiré, quel qu'en soit le gain.
 */
public final class Kitchen {
    /**
     * Où l'on pose les fours d'essai.
     *
     * <p>Près du joueur, et non « loin de tout » comme le voulait la première version : à deux cents
     * blocs, les fours étaient hors du rayon de simulation et <b>aucun n'a cuit</b> — ni avec le mod,
     * ni sans. Le verdict annonçait « exact » en comparant deux échecs identiques.
     */
    private static final int BASE_X = 16;
    private static final int BASE_Z = 16;
    /** Nombre de fours de l'épreuve : la médiane de plusieurs vaut mieux qu'une mesure unique. */
    private static final int OVENS = 5;
    /** Ticks au-delà desquels on cesse d'attendre une cuisson. */
    private static final int PATIENCE = 600;

    private static final List<BlockPos> PLACES = new ArrayList<>();
    private static final int[] WITH = new int[OVENS];
    private static final int[] WITHOUT = new int[OVENS];

    private enum Step { OFF, COOKING_ON, COOKING_OFF, DONE }

    /** Longueur de la chaîne d'entonnoirs éprouvée. */
    private static final int CHAIN = 6;
    private static BlockPos hopperEnd;
    private static final int[] MOVED = new int[2];

    private static Step step = Step.OFF;
    private static int elapsed;
    private static MinecraftServer host;

    private Kitchen() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        step = Step.COOKING_ON;
        Settings.setEnabled(true);
        light(server.overworld());
        chain(server.overworld());
        Lanterne.LOG.info("[CUISSON] Épreuve lancée, mod actif.");
    }

    /**
     * Pose les fours et les allume.
     *
     * <p>Les fours sont posés en l'air, à bonne distance les uns des autres : on mesure une durée de
     * cuisson, pas une interaction entre voisins.
     */
    private static void light(ServerLevel level) {
        PLACES.clear();
        elapsed = 0;

        for (int i = 0; i < OVENS; i++) {
            int x = BASE_X + i * 4;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types
                    .MOTION_BLOCKING_NO_LEAVES, x, BASE_Z);
            BlockPos pos = new BlockPos(x, y, BASE_Z);
            level.setBlockAndUpdate(pos, Blocks.FURNACE.defaultBlockState());
            if (level.getBlockEntity(pos) instanceof FurnaceBlockEntity furnace) {
                furnace.setItem(0, new ItemStack(Items.RAW_IRON, 64));
                furnace.setItem(1, new ItemStack(Items.COAL, 64));
                furnace.setChanged();
                PLACES.add(pos);
            }
        }
        Lanterne.LOG.info("[CUISSON] {} four(s) allumé(s).", PLACES.size());
    }

    /**
     * Pose une chaîne d'entonnoirs et compte ce qui arrive au bout.
     *
     * <p>Le débit est la seule mesure qui vaille pour un entonnoir : un objet par huit ticks, et
     * tout écart se voit au bout d'une chaîne. On mesure donc ce qui est réellement arrivé, pas ce
     * que le compteur prétend.
     */
    private static void chain(ServerLevel level) {
        for (int i = 0; i < CHAIN; i++) {
            BlockPos pos = new BlockPos(BASE_X + i, 80, BASE_Z + 8);
            level.setBlockAndUpdate(pos, Blocks.HOPPER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.HopperBlock.FACING,
                            net.minecraft.core.Direction.EAST));
        }
        BlockPos source = new BlockPos(BASE_X, 80, BASE_Z + 8);
        if (level.getBlockEntity(source)
                instanceof net.minecraft.world.level.block.entity.HopperBlockEntity hopper) {
            hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            hopper.setChanged();
        }
        hopperEnd = new BlockPos(BASE_X + CHAIN - 1, 80, BASE_Z + 8);
    }

    /** Attend l'apparition du premier lingot dans chaque four. */
    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();
        elapsed++;

        int[] into = step == Step.COOKING_ON ? WITH : WITHOUT;
        boolean allDone = true;

        for (int i = 0; i < PLACES.size(); i++) {
            if (into[i] > 0) {
                continue;
            }
            if (level.getBlockEntity(PLACES.get(i)) instanceof FurnaceBlockEntity furnace
                    && !furnace.getItem(2).isEmpty()) {
                into[i] = elapsed;
            } else {
                allDone = false;
            }
        }

        if (allDone || elapsed >= PATIENCE) {
            for (int i = 0; i < PLACES.size(); i++) {
                if (into[i] == 0) {
                    into[i] = PATIENCE;
                }
                level.setBlockAndUpdate(PLACES.get(i), net.minecraft.world.level.block.Blocks.AIR
                        .defaultBlockState());
            }
            advance(level);
        }
    }

    private static void advance(ServerLevel level) {
        if (step == Step.COOKING_ON) {
            MOVED[0] = countAtEnd(level);
            step = Step.COOKING_OFF;
            Settings.setEnabled(false);
            light(level);
            chain(level);
            Lanterne.LOG.info("[CUISSON] Seconde moitié, mod éteint.");
            return;
        }
        MOVED[1] = countAtEnd(level);
        step = Step.DONE;
        Settings.setEnabled(true);
        report();
        if (host != null) {
            host.halt(false);
        }
    }

    /**
     * Le verdict.
     *
     * <p>Une cuisson vanilla dure deux cents ticks. Tout écart est un défaut, et il est dit tel quel :
     * ici, contrairement à la cadence des créatures, <b>il n'y a aucun compromis à accepter</b>. Le
     * sommeil ne prétend pas dégrader un peu pour gagner beaucoup — il prétend ne rien dégrader du
     * tout, et doit donc le prouver au tick près.
     */
    /** Objets parvenus au dernier entonnoir de la chaîne. */
    private static int countAtEnd(ServerLevel level) {
        if (hopperEnd == null || !(level.getBlockEntity(hopperEnd)
                instanceof net.minecraft.world.level.block.entity.HopperBlockEntity hopper)) {
            return -1;
        }
        int total = 0;
        for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
            total += hopper.getItem(slot).getCount();
        }
        return total;
    }

    private static void report() {
        Lanterne.LOG.info("[ENTONNOIR] au bout de la chaîne — sans {} objet(s) · avec {} objet(s)",
                MOVED[1], MOVED[0]);
        Lanterne.LOG.info("[CUISSON] ── Ticks jusqu'au premier lingot ──");
        int mismatches = 0;
        for (int i = 0; i < PLACES.size() || i < OVENS; i++) {
            if (WITHOUT[i] == 0 && WITH[i] == 0) {
                continue;
            }
            int gap = WITH[i] - WITHOUT[i];
            if (gap != 0) {
                mismatches++;
            }
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[CUISSON] four %d : sans %d ticks · avec %d ticks · écart %+d",
                    i, WITHOUT[i], WITH[i], gap));
        }
        Lanterne.LOG.info(mismatches == 0
                ? "[CUISSON] VERDICT : exact, au tick près."
                : "[CUISSON] VERDICT : " + mismatches + " four(s) décalé(s) — le sommeil est fautif.");
    }
}
