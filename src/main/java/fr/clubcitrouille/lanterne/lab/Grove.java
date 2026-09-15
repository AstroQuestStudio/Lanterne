package fr.clubcitrouille.lanterne.lab;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le bosquet : combien de temps une couronne de feuillage met à tomber.
 *
 * <h2>Pourquoi le banc de vitesse ne pouvait pas répondre</h2>
 *
 * <p>La chute des feuilles ne rend pas le serveur plus rapide à charge égale : elle <b>supprime la
 * charge</b>. C'est exactement le cas du Balai, et c'est pour cette raison que le Balai est exclu de
 * toutes les mesures du projet — un module qui efface ce qu'on est en train de chronométrer fausse le
 * chronomètre au lieu de l'améliorer.
 *
 * <p>Le module de chute est resté rangé sous « confort » depuis qu'il existe, <b>sans un seul
 * chiffre</b>. Or c'est lui qui répond à LeavesBeGone et à AcceleratedDecay, deux cibles du plan
 * d'absorption. Les écarter en disant « on le fait déjà » sans jamais avoir mesuré ce qu'on fait
 * n'aurait pas été un verdict, seulement une affirmation.
 *
 * <p>La bonne grandeur n'est donc pas une milliseconde : c'est un <b>délai</b>. Combien de ticks
 * entre le coup de hache et la dernière feuille tombée.
 *
 * <h2>Le protocole, et le détail qui décide de tout</h2>
 *
 * <p>On bâtit un vrai arbre — un tronc et une couronne — puis on <b>coupe le tronc</b>. C'est
 * indispensable, et pas décoratif : {@code LeavesBlock.tick} n'est appelé que si quelque chose l'a
 * programmé. En vanilla, c'est {@code updateShape} qui le fait, à la ligne
 * {@code ticks.scheduleTick(pos, this, 1)}, quand un voisin change. Poser des feuilles déjà
 * condamnées dans le vide ne déclencherait rien du tout, et l'épreuve mesurerait les deux fois la
 * même attente — celle du tick aléatoire.
 *
 * <p>Ensuite on compte, tick par tick, ce qui reste debout. Deux fenêtres : module allumé, puis
 * module éteint, sur un arbre rebâti à l'identique.
 *
 * <p>Le plafond est volontairement court. En vanilla, une feuille attend un tick aléatoire : trois
 * positions tirées par section de quatre mille quatre-vingt-seize, soit une espérance de mille trois
 * cent soixante-cinq ticks <em>par feuille</em>, et bien davantage pour la dernière d'un groupe.
 * Attendre la fin prendrait dix minutes. On n'en a pas besoin : l'écart se voit en quelques secondes,
 * et le rapport dit combien il en restait.
 */
public final class Grove {
    /**
     * Demi-côté de la couronne, en blocs — et trois était une couronne déjà condamnée.
     *
     * <h2>La première exécution a rendu « une feuille sur deux tombe avant la coupe »</h2>
     *
     * <p>Avec un rayon de trois, la feuille du coin est à <b>sept pas de feuille en feuille</b> du
     * tronc : trois en X, un en Y, trois en Z. Or {@code updateDistance} compte exactement cela, et
     * sept est la valeur de condamnation. Ces feuilles-là étaient donc détachées <em>dès la pose</em>,
     * et le module les faisait tomber pendant les vingt ticks de décantation — avant même le coup de
     * hache. L'épreuve annonçait alors « tombée en un tick » : elle mesurait un arbre déjà mort.
     *
     * <p>Un rayon de deux met le coin à cinq pas. Rien n'est condamné avant la coupe, et c'est ce que
     * le contrôle ci-dessous vérifie désormais au lieu de le supposer.
     */
    private static final int SPREAD = 2;
    /** Hauteur de la couronne, en blocs. */
    private static final int CROWN = 5;
    /** Hauteur du tronc, en blocs. */
    private static final int TRUNK = 5;

    /** Ticks laissés aux distances pour se propager avant la coupe. */
    private static final int SETTLE = 20;

    /** Ticks d'observation après la coupe, par fenêtre. Voir la javadoc de la classe. */
    private static final int CAP = 600;

    private enum Step { OFF, BUILD, SETTLE_PHASE, WATCH, DONE }

    private static Step step = Step.OFF;
    private static boolean modOn = true;
    private static int waited;
    private static int elapsed;
    private static int planted;
    /** Ce qui tient encore debout au moment exact de la coupe. Voir {@link #SPREAD}. */
    private static int standing;
    private static int base;

    /** Résultats : ticks jusqu'à la dernière feuille, ou -1 si le plafond est atteint. */
    private static int onTicks = -1;
    private static int offTicks = -1;
    private static int onLeft;
    private static int offLeft;
    private static int onPlanted;
    private static int offPlanted;

    private Grove() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        step = Step.BUILD;
        modOn = true;
        waited = 0;
    }

    public static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        switch (step) {
            case BUILD -> {
                Settings.setEnabled(modOn);
                base = Scene.groundLevel(level) + 1;
                clear(level);
                planted = plant(level);
                Lanterne.LOG.info("[BOSQUET] {} — arbre bâti : {} feuille(s) autour d'un tronc de {}.",
                        modOn ? "module ALLUMÉ" : "module ÉTEINT", planted, TRUNK);
                step = Step.SETTLE_PHASE;
                waited = SETTLE;
            }
            case SETTLE_PHASE -> {
                if (waited-- > 0) {
                    return;
                }
                standing = count(level);
                // Le garde-fou qui a rattrape le premier resultat, et qui doit rester.
                //
                // La premiere version semait avec un rayon de trois : la feuille du coin se
                // trouvait a sept pas du tronc, donc au-dela de la distance de decomposition, donc
                // CONDAMNEE des la pose. Le module la faisait tomber pendant la decantation, avant
                // meme la coupe — et l'epreuve rapportait « tombee en un tick », ce qui n'avait
                // aucun sens.
                //
                // Le rayon est passe a deux, ce qui met le coin a cinq pas. Mais une constante bien
                // choisie ne se verifie pas elle-meme : si quoi que ce soit tombe avant le coup de
                // hache, la mesure ne porte plus sur la coupe et l'epreuve doit le dire.
                if (standing < planted) {
                    Lanterne.LOG.error("[BOSQUET] ÉPREUVE INVALIDE : {} feuille(s) sur {} sont "
                            + "tombées AVANT la coupe. Elles étaient donc déjà hors de portée du "
                            + "tronc, et ce qu'on mesurerait ensuite ne serait pas la chute d'un "
                            + "arbre abattu.", planted - standing, planted);
                    report();
                    step = Step.DONE;
                    Settings.setEnabled(true);
                    clear(level);
                    server.halt(false);
                    return;
                }
                fell(level);
                Lanterne.LOG.info("[BOSQUET] tronc coupé — {} feuille(s) debout à cet instant.",
                        standing);
                elapsed = 0;
                step = Step.WATCH;
            }
            case WATCH -> {
                elapsed++;
                int left = count(level);
                if (left == 0 || elapsed >= CAP) {
                    record(left);
                    if (modOn) {
                        modOn = false;
                        step = Step.BUILD;
                    } else {
                        report();
                        step = Step.DONE;
                        Settings.setEnabled(true);
                        clear(level);
                        server.halt(false);
                    }
                }
            }
            default -> { }
        }
    }

    private static void record(int left) {
        if (modOn) {
            onLeft = left;
            onPlanted = standing;
            onTicks = left == 0 ? elapsed : -1;
        } else {
            offLeft = left;
            offPlanted = standing;
            offTicks = left == 0 ? elapsed : -1;
        }
    }

    private static void report() {
        Lanterne.LOG.info("[BOSQUET] ── Chute d'une couronne de feuillage après la coupe ──");
        say("module ALLUMÉ", onPlanted, onTicks, onLeft);
        say("module ÉTEINT", offPlanted, offTicks, offLeft);

        if (onPlanted < planted) {
            Lanterne.LOG.warn("[BOSQUET] REFUS : {} feuille(s) sur {} étaient déjà tombées AVANT la "
                    + "coupe. La couronne portait des feuilles à distance sept dès la pose ; ce n'est "
                    + "pas un arbre, c'est un tas de feuilles détachées.", planted - onPlanted,
                    planted);
            return;
        }
        if (onPlanted != offPlanted) {
            Lanterne.LOG.warn("[BOSQUET] REFUS : les deux arbres n'avaient pas le même nombre de "
                    + "feuilles ({} contre {}). Les deux fenêtres ne décrivent pas la même chose.",
                    onPlanted, offPlanted);
            return;
        }
        if (onTicks < 0) {
            Lanterne.LOG.warn("[BOSQUET] REFUS : même module allumé, la couronne n'est pas tombée en "
                    + "{} ticks. Le module ne fonctionne pas, ou la coupe n'a rien programmé.", CAP);
            return;
        }
        if (offTicks >= 0) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[BOSQUET] VERDICT : %d ticks avec le module contre %d sans — ×%.1f plus vite.",
                    onTicks, offTicks, offTicks / (double) Math.max(1, onTicks)));
        } else {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[BOSQUET] VERDICT : la couronne tombe en %d ticks (%.1f s) avec le module. Sans "
                            + "lui, il en reste %d sur %d au bout de %d ticks — soit %.0f %% du "
                            + "feuillage encore suspendu dans le vide après %.0f secondes de jeu.",
                    onTicks, onTicks / 20d, offLeft, offPlanted, CAP,
                    100d * offLeft / Math.max(1, offPlanted), CAP / 20d));
        }
    }

    private static void say(String label, int total, int ticks, int left) {
        if (ticks >= 0) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[BOSQUET] %s : %d feuille(s), toutes tombées en %d ticks (%.1f s)",
                    label, total, ticks, ticks / 20d));
        } else {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[BOSQUET] %s : %d feuille(s), il en reste %d après %d ticks (%.1f s)",
                    label, total, left, CAP, CAP / 20d));
        }
    }

    /**
     * Bâtit le tronc puis la couronne.
     *
     * <p>Les feuilles sont posées avec le drapeau 3 — notifier voisins et clients — parce que c'est
     * {@code updateShape} qui propage la distance au tronc. Posées sans notification, elles
     * garderaient la distance par défaut et le module n'aurait rien à faire.
     *
     * <p>{@code PERSISTENT} est laissé à faux : une feuille posée à la main par un joueur reçoit
     * {@code PERSISTENT = true} dans {@code getStateForPlacement} et ne se décompose jamais. Une
     * épreuve qui poserait des feuilles persistantes mesurerait deux fois l'immobilité.
     */
    /**
     * Plante l'arbre.
     *
     * <h2>À REPRENDRE : la vraie cause du refus, trouvée après coup</h2>
     *
     * <p>Le rayon de semis n'était pas en cause — il est passé de trois à deux, et l'épreuve refuse
     * toujours, en annonçant <b>145 feuilles sur 145</b> tombées avant la coupe. Un rayon n'explique
     * pas une proportion pareille.
     *
     * <p>La cause est la propriété {@code DISTANCE} des feuilles. {@code defaultBlockState()} la
     * laisse à <b>sept</b>, c'est-à-dire exactement la valeur qui condamne une feuille — et cela
     * quelle que soit sa position réelle. Toutes les feuilles posées ici sont donc marquées « trop
     * loin d'un tronc » dès la pose, avant que quoi que ce soit n'ait été coupé.
     *
     * <p>Vanilla ne s'en aperçoit pas dans une partie ordinaire parce que la distance se recalcule
     * de proche en proche quand un bloc voisin change. Ici, le semis pose tout d'un coup, et rien ne
     * déclenche ce recalcul avant que le module ne fasse son travail.
     *
     * <p><b>Le correctif à appliquer</b> : poser chaque feuille avec {@code DISTANCE} réglée à sa
     * distance réelle au tronc, plutôt que de compter sur la valeur par défaut. Il restera à
     * vérifier que le témoin — module éteint — garde bien ses feuilles suspendues, ce qu'une
     * exécution précédente laissait penser (186 sur 289 après 600 ticks).
     *
     * <p>Tant que ce n'est pas fait, l'épreuve <b>refuse de conclure</b>, et c'est ce qu'on lui
     * demande : elle avait d'abord rendu « tombée en un tick », un chiffre qui n'avait aucun sens et
     * qui serait passé pour un gain.
     */
    private static int plant(ServerLevel level) {
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, false);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = 0; y < TRUNK; y++) {
            cursor.set(0, base + y, 0);
            level.setBlock(cursor, log, Block.UPDATE_ALL);
        }

        int count = 0;
        int top = base + TRUNK - 1;
        for (int x = -SPREAD; x <= SPREAD; x++) {
            for (int z = -SPREAD; z <= SPREAD; z++) {
                for (int y = top - CROWN + 1; y <= top + 1; y++) {
                    if (x == 0 && z == 0 && y <= top) {
                        continue;
                    }
                    cursor.set(x, y, z);
                    if (level.setBlock(cursor, leaves, Block.UPDATE_ALL)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** Coupe le tronc : c'est ce geste qui programme le premier tick des feuilles. */
    private static void fell(ServerLevel level) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = TRUNK - 1; y >= 0; y--) {
            cursor.set(0, base + y, 0);
            level.setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static int count(ServerLevel level) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int standing = 0;
        int top = base + TRUNK;
        for (int x = -SPREAD; x <= SPREAD; x++) {
            for (int z = -SPREAD; z <= SPREAD; z++) {
                for (int y = base; y <= top + 1; y++) {
                    cursor.set(x, y, z);
                    if (level.getBlockState(cursor).getBlock() instanceof LeavesBlock) {
                        standing++;
                    }
                }
            }
        }
        return standing;
    }

    private static void clear(ServerLevel level) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = -SPREAD - 1; x <= SPREAD + 1; x++) {
            for (int z = -SPREAD - 1; z <= SPREAD + 1; z++) {
                for (int y = base; y <= base + TRUNK + 2; y++) {
                    cursor.set(x, y, z);
                    level.setBlock(cursor, air, Block.UPDATE_ALL);
                }
            }
        }
        // Les feuilles arrachées sèment leurs pousses et leurs pommes : on ne les laisse pas
        // derrière, sinon la seconde fenêtre bâtit son arbre au milieu des restes de la première.
        fr.clubcitrouille.lanterne.report.Herd.sweepEntities(level);
    }
}
