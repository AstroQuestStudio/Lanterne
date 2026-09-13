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
     *
     * <h2>Et pourquoi pas au milieu non plus</h2>
     *
     * <p>Le site était en (16, 16), c'est-à-dire <b>à l'intérieur du chantier du banc de vitesse</b> —
     * qui pose dix mille trémies et deux mille cinq cents coffres sur un carré de cent quatre-vingt-
     * douze blocs, à l'altitude même de cette chaîne. Le monde étant conservé d'une épreuve à
     * l'autre, une chaîne de six trémies a fini par annoncer 288 944 objets déplacés et un débit
     * <em>négatif</em> : elle mesurait le chantier d'un autre banc.
     *
     * <p>Cent vingt blocs met le site hors de ce chantier — qui s'arrête à quatre-vingt-seize — et le
     * laisse à l'intérieur du rayon de simulation, qui est de dix chunks, soit cent soixante blocs.
     * Dégager le terrain aurait été l'autre réponse, et la mauvaise : creuser sous les fours aurait
     * fait descendre la carte des hauteurs d'une exécution à l'autre, ce qui est très exactement le
     * défaut qui avait fait monter la dalle de pierre de quatre blocs par reconstruction.
     */
    private static final int BASE_X = 120;
    private static final int BASE_Z = 0;
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
    /** Altitude de la chaîne. Fixe : deux phases doivent se dérouler au même endroit. */
    private static final int CHAIN_Y = 80;
    private static BlockPos hopperEnd;

    /**
     * Ticks auxquels on relève le débit.
     *
     * <h2>Pourquoi trois marques, et non une</h2>
     *
     * <p>Un débit est une pente, et une pente ne se lit pas sur un point. Un relevé unique ne permet
     * pas de distinguer « le mod transfère moins vite » de « le mod a démarré deux ticks plus tard » —
     * or les deux donnent le même chiffre à un instant donné, et n'ont rien à voir.
     *
     * <p>Trois marques donnent deux pentes de plus, et ce sont elles qui comptent : si le mod préserve
     * le débit, les écarts entre marques doivent coïncider même si le départ diffère.
     */
    private static final int[] MARKS = {60, 120, 180};
    /** Relevés : première ligne avec le mod, seconde sans. */
    private static final int[][] MOVED = new int[2][MARKS.length];
    /** Durée minimale d'une phase : sans elle, la dernière marque ne serait pas atteinte. */
    private static final int FLOOR = 200;

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
        fr.clubcitrouille.lanterne.core.Bulk.reset();
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
        // <h2>Cent trente-six objets arrivés au bout d'une chaîne qui n'en reçoit que soixante-quatre</h2>
        //
        // Le chiffre était impossible, et il a fallu le voir écrit pour s'en apercevoir. La seconde
        // moitié de l'épreuve reposait ses trémies <b>aux mêmes emplacements</b> que la première ; or
        // poser un bloc identique sur lui-même ne recrée pas son bloc-entité. Les trémies gardaient donc
        // leur contenu, et la phase témoin commençait avec l'avance accumulée par la phase précédente.
        //
        // <h2>Le même héritage, revenu par le sol</h2>
        //
        // Le correctif ci-dessus — effacer d'abord — était juste, et il n'a pas suffi : trois
        // exécutions identiques ont rendu 18/30, puis 39/16, puis 16/16. Le témoin lui-même variait du
        // simple au double, et un chiffre qu'on ne sait pas reproduire n'est pas une mesure.
        //
        // La cause était dans le correctif. Casser une trémie n'efface pas son contenu : il le
        // RELÂCHE. LevelChunk.setBlockState appelle preRemoveSideEffects, qui appelle
        // Containers.dropContents dès que le bloc-entité est un conteneur. Les soixante-quatre
        // cailloux tombaient donc en objets, à l'endroit exact où la boucle suivante reposait les
        // trémies — qui les ré-aspiraient aussitôt, en nombre variable selon la dispersion aléatoire
        // du lâcher.
        //
        // On vide donc AVANT de casser, et l'on balaie le sol après : le contenu ne peut alors ni
        // survivre dans le bloc-entité, ni revenir par le sol.
        for (int i = 0; i < CHAIN; i++) {
            BlockPos pos = new BlockPos(BASE_X + i, CHAIN_Y, BASE_Z + 8);
            if (level.getBlockEntity(pos)
                    instanceof net.minecraft.world.level.block.entity.HopperBlockEntity old) {
                old.clearContent();
            }
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
        sweep(level);

        for (int i = 0; i < CHAIN; i++) {
            BlockPos pos = new BlockPos(BASE_X + i, CHAIN_Y, BASE_Z + 8);
            level.setBlockAndUpdate(pos, Blocks.HOPPER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.HopperBlock.FACING,
                            net.minecraft.core.Direction.EAST));
        }
        BlockPos source = new BlockPos(BASE_X, CHAIN_Y, BASE_Z + 8);
        if (level.getBlockEntity(source)
                instanceof net.minecraft.world.level.block.entity.HopperBlockEntity hopper) {
            hopper.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
            hopper.setChanged();
        }
        hopperEnd = new BlockPos(BASE_X + CHAIN - 1, CHAIN_Y, BASE_Z + 8);
    }

    /**
     * Efface tout objet traînant autour de la chaîne.
     *
     * <p>La zone descend de vingt-quatre blocs sous la chaîne : un objet lâché tombe, et l'on ne
     * balaie pas seulement là où il est né.
     */
    private static void sweep(ServerLevel level) {
        var zone = new net.minecraft.world.phys.AABB(
                BASE_X - 2, CHAIN_Y - 24, BASE_Z + 6,
                BASE_X + CHAIN + 2, CHAIN_Y + 4, BASE_Z + 10);
        int wiped = 0;
        for (var loose : level.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, zone)) {
            loose.discard();
            wiped++;
        }
        if (wiped > 0) {
            Lanterne.LOG.info("[ENTONNOIR] {} objet(s) balayé(s) autour de la chaîne avant la phase.",
                    wiped);
        }
    }

    /** Attend l'apparition du premier lingot dans chaque four. */
    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();
        elapsed++;

        // <h2>Un débit compté sur deux durées différentes</h2>
        //
        // Le relevé des entonnoirs se faisait en fin de phase — c'est-à-dire quand les fours avaient
        // fini de cuire. Or les deux phases ne s'achèvent pas au même tick : quelques ticks d'écart
        // suffisent à décaler un débit continu de plusieurs objets.
        //
        // La mesure dépendait donc d'un évènement sans rapport avec elle. On relève maintenant à un
        // tick fixe, identique des deux côtés : c'est la seule façon de comparer deux débits.
        int phase = step == Step.COOKING_ON ? 0 : 1;
        for (int m = 0; m < MARKS.length; m++) {
            if (elapsed == MARKS[m]) {
                MOVED[phase][m] = countAtEnd(level);
            }
        }

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

        // La cuisson peut s'achever avant la dernière marque de débit. On ne laisse pas la phase
        // partir tant que les entonnoirs n'ont pas été relevés : sinon les deux phases se
        // compareraient sur des durées différentes, ce qui était déjà le défaut de la version
        // précédente.
        if ((allDone && elapsed >= FLOOR) || elapsed >= PATIENCE) {
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
            // Le relevé a lieu au tick fixe, plus haut : ici la phase peut s'achever à un autre moment.
            step = Step.COOKING_OFF;
            Settings.setEnabled(false);
            light(level);
            chain(level);
            Lanterne.LOG.info("[CUISSON] Seconde moitié, mod éteint.");
            return;
        }
        // Idem : ne rien relever ici, la durée de cette phase n'est pas celle de l'autre.
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
        // <h2>Le volet qui ne se reproduisait pas, et ce qui l'en empêchait</h2>
        //
        // Trois défauts de protocole se sont succédé ici, chacun corrigeant le précédent sans le
        // suffire :
        //
        //   1. Les trémies n'étaient pas vidées entre les phases — d'où cent trente-six objets arrivés
        //      au bout d'une chaîne qui n'en reçoit que soixante-quatre.
        //   2. Le relevé se faisait en fin de phase, donc sur deux durées différentes.
        //   3. Le correctif du premier défaut CASSAIT les trémies, ce qui relâche leur contenu au sol,
        //      à l'endroit exact où les nouvelles étaient reposées. Elles le ré-aspiraient, en nombre
        //      variable. C'est ce qui donnait 18/30, puis 39/16, puis 16/16 : le témoin variait du
        //      simple au double, et l'écart mesuré était entièrement fabriqué.
        //
        // Le troisième est corrigé dans chain() : on vide avant de casser, et l'on balaie le sol. Le
        // protocole n'est déclaré reproductible que si trois exécutions rendent le même témoin — c'est
        // au lanceur de l'épreuve de le vérifier, et le rapport lui en donne les moyens en publiant
        // les trois marques plutôt qu'un total.
        Lanterne.LOG.info("[ENTONNOIR] ── Objets parvenus au bout de la chaîne ──");
        for (int m = 0; m < MARKS.length; m++) {
            int without = MOVED[1][m];
            int with = MOVED[0][m];
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[ENTONNOIR] tick %3d : sans %3d · avec %3d · écart %+d",
                    MARKS[m], without, with, with - without));
        }
        int spanWithout = MOVED[1][MARKS.length - 1] - MOVED[1][0];
        int spanWith = MOVED[0][MARKS.length - 1] - MOVED[0][0];
        int window = MARKS[MARKS.length - 1] - MARKS[0];
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[ENTONNOIR] DÉBIT sur %d ticks — sans %.2f obj/s · avec %.2f obj/s · %s",
                window, spanWithout * 20d / window, spanWith * 20d / window,
                spanWithout == 0
                        ? "témoin nul, épreuve sans valeur"
                        : String.format(Locale.ROOT, "×%.2f", spanWith / (double) spanWithout)));
        Lanterne.LOG.info("[ENTONNOIR] lot moyen : {} objet(s) par transfert, {} transfert(s), "
                        + "{} objet(s) déplacé(s).",
                String.format(Locale.ROOT, "%.2f", fr.clubcitrouille.lanterne.core.Bulk.averageLot()),
                fr.clubcitrouille.lanterne.core.Bulk.transfers(),
                fr.clubcitrouille.lanterne.core.Bulk.items());
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
