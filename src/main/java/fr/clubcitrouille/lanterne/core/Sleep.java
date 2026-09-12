package fr.clubcitrouille.lanterne.core;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.core.BlockPos;

/**
 * Le sommeil à échéance : ne pas recalculer une réponse qu'on connaît déjà.
 *
 * <h2>La troisième voie</h2>
 *
 * <p>Face à un bloc-entité coûteux — four, fumoir, haut fourneau — deux réponses viennent à
 * l'esprit, et toutes deux sont mauvaises.
 *
 * <p><b>Le laisser tourner</b> coûte ce qu'il coûte : deux allocations et une recherche de recette
 * par tick, pour incrémenter un compteur. <b>Le ralentir</b> divise son débit, et un four qui cuit
 * seize fois moins vite est un four cassé — c'est la raison pour laquelle ce mod avait d'abord
 * renoncé à y toucher.
 *
 * <p>La troisième voie tient dans une observation : <b>en régime établi, l'issue est connue
 * d'avance</b>. Un four allumé qui cuit finira sa cuisson dans un nombre de ticks calculable, ou
 * s'éteindra faute de combustible à un tick calculable. Entre maintenant et la première de ces deux
 * échéances, rien d'observable ne se produit.
 *
 * <p>On ne ralentit donc pas : <b>on dort jusqu'à l'échéance</b>. Le four s'éteint au même tick,
 * l'objet est cuit au même tick, le débit est identique — on cesse simplement de redemander chaque
 * tick une réponse qui ne changera pas.
 *
 * <h2>Pourquoi c'est exact, et non « à peu près »</h2>
 *
 * <p>Le rattrapage n'est pas une approximation. Au réveil, les compteurs sont avancés du nombre
 * exact de ticks écoulés, valeur qu'ils auraient atteinte en comptant un par un. Le tick vanilla
 * s'exécute ensuite normalement et constate exactement ce qu'il aurait constaté.
 *
 * <p>C'est la différence de fond avec la cadence appliquée aux créatures : là, ralentir dégrade
 * réellement quelque chose — un déplacement, une décision — et l'on accepte cette dégradation
 * parce qu'elle est invisible. Ici, <b>rien n'est dégradé du tout</b>.
 *
 * <h2>Ce qui rompt le sommeil</h2>
 *
 * <p>Le sommeil repose sur une hypothèse : que rien ne change. Trois garde-fous la protègent.
 *
 * <ol>
 *   <li><b>Le jeu prévient lui-même.</b> {@code setChanged()} est appelé à chaque modification de
 *       contenu — joueur, entonnoir, dispenseur. On s'y raccroche plutôt que de surveiller
 *       l'inventaire : le jeu sait déjà quand il change, et il le dit.</li>
 *   <li><b>L'échéance est un plafond.</b> On ne dort jamais au-delà du prochain évènement
 *       calculé.</li>
 *   <li><b>Un plafond absolu.</b> Même sans évènement prévu, le sommeil ne dépasse pas quelques
 *       secondes. Au pire, on économise un peu moins ; jamais on ne dort trop.</li>
 * </ol>
 */
public final class Sleep {
    /**
     * Sommeil le plus long autorisé, en ticks.
     *
     * <p>Cinq secondes. Ce n'est pas une limite de justesse — les échéances sont exactes — mais une
     * limite de prudence : si un mod tiers modifiait un bloc-entité sans passer par le signal du
     * jeu, le réveil périodique rattraperait l'affaire en quelques secondes plutôt que jamais.
     */
    private static final int LONGEST = 100;

    /** Position encodée vers le tick auquel ce bloc-entité doit se réveiller. */
    private static final Long2LongOpenHashMap WAKE = new Long2LongOpenHashMap();
    /**
     * Position encodée vers le tick auquel ce bloc-entité s'est endormi.
     *
     * <p>On retient l'instant du coucher, et non la durée prévue. La première version rendait la
     * durée planifiée : si le réveil survenait à un autre moment que prévu — et il le peut, un
     * contenu modifié rompant le sommeil — le rattrapage portait sur un nombre de ticks qui n'avait
     * pas été réellement dormi. Les fours cuisaient alors deux ticks trop vite.
     *
     * <p>Le temps écoulé, lui, est toujours juste : c'est une soustraction entre deux instants
     * observés, pas une prévision.
     */
    private static final Long2LongOpenHashMap ASLEEP_SINCE = new Long2LongOpenHashMap();

    private static long slept;
    private static long ticked;

    static {
        WAKE.defaultReturnValue(Long.MIN_VALUE);
        ASLEEP_SINCE.defaultReturnValue(Long.MIN_VALUE);
    }

    private Sleep() {}

    /**
     * Ce bloc-entité doit-il travailler à ce tick ?
     *
     * @return le nombre de ticks dormis qu'il faut rattraper, ou {@code -1} s'il faut dormir encore
     */
    public static int due(BlockPos pos, long now) {
        long key = pos.asLong();
        long wake = WAKE.get(key);

        if (wake == Long.MIN_VALUE) {
            ticked++;
            return 0; // aucun sommeil en cours : il travaille, et l'on avisera après
        }
        if (now < wake) {
            slept++;
            return -1;
        }

        // L'échéance est atteinte. Le nombre de ticks à rattraper est celui qui s'est réellement
        // écoulé depuis le coucher, moins le tick du coucher lui-même — qui, lui, a été joué.
        long since = ASLEEP_SINCE.get(key);
        WAKE.remove(key);
        ASLEEP_SINCE.remove(key);
        ticked++;
        if (since == Long.MIN_VALUE) {
            return 0;
        }
        return (int) Math.max(0L, Math.min(LONGEST, now - since - 1L));
    }

    /**
     * Programme le prochain réveil.
     *
     * <p>Un sommeil d'un tick ou moins n'en vaut pas la peine : le tenir coûterait deux écritures
     * dans une table pour épargner un seul passage.
     *
     * @param until ticks pendant lesquels rien d'observable ne peut se produire
     */
    public static void plan(BlockPos pos, long now, int until) {
        long key = pos.asLong();
        int span = Math.max(0, Math.min(LONGEST, until));
        if (span <= 1) {
            WAKE.remove(key);
            ASLEEP_SINCE.remove(key);
            return;
        }
        WAKE.put(key, now + span);
        ASLEEP_SINCE.put(key, now);
    }

    /** Réveille immédiatement : le contenu a changé, l'échéance calculée ne vaut plus rien. */
    public static void wake(BlockPos pos) {
        long key = pos.asLong();
        if (!WAKE.isEmpty()) {
            WAKE.remove(key);
            ASLEEP_SINCE.remove(key);
        }
    }

    /** Part des ticks épargnés, pour le rapport. */
    public static double savedShare() {
        long total = slept + ticked;
        return total == 0L ? 0d : (double) slept / total;
    }

    public static long sleptTicks() {
        return slept;
    }

    public static void resetCounters() {
        slept = 0L;
        ticked = 0L;
    }

    public static void reset() {
        WAKE.clear();
        ASLEEP_SINCE.clear();
        resetCounters();
    }
}
