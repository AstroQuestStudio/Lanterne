package fr.clubcitrouille.lanterne.client.upscale;

import java.util.Arrays;
import java.util.Locale;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Lens;

/**
 * La houle : l'échelle monte quand la carte graphique respire, et redescend avant qu'elle ne suffoque.
 *
 * <h2>Le même raisonnement que la marée, appliqué à l'autre bout</h2>
 *
 * <p>{@code core.Tide} fait descendre les distances d'un serveur quand son tick s'allonge, et les
 * remonte quand il se détend. La question posée ici est la même, sur une autre grandeur : un joueur
 * choisit un facteur d'échelle une fois pour toutes, alors que sa charge graphique varie d'un
 * facteur cinq entre une grotte et un village au crépuscule sous la pluie.
 *
 * <p>La forme est donc reprise telle quelle — un palier à la fois, une bande morte, un délai entre
 * deux gestes, une grâce au démarrage — parce que la cohérence du mod vaut mieux qu'une seconde
 * invention. Seules les valeurs changent, et la mesure : un tick lissé là-bas, la <b>médiane</b> des
 * durées d'image ici.
 *
 * <h2>Pourquoi la médiane et non la moyenne</h2>
 *
 * <p>Une seconde de jeu contient soixante images et, souvent, une seule très longue : un chunk qui
 * arrive, un nuanceur qui se compile, un ramassage de mémoire. Cette image-là ajoute une
 * milliseconde et demie à la moyenne — assez pour franchir la bande morte et déclencher une
 * dégradation qui n'a aucune raison d'être. La médiane l'ignore.
 *
 * <h2>Le piège de la synchronisation verticale</h2>
 *
 * <p>C'est le défaut que la marée ne pouvait pas rencontrer, et il fallait le traiter avant de
 * pouvoir croire ce module. Un joueur en v-sync à 60 Hz voit <b>toutes</b> ses images durer 16,7 ms,
 * qu'il ait dix pour cent ou quatre-vingts pour cent de marge. La règle de la marée — remonter
 * seulement quand la mesure passe <em>sous</em> la cible — ne se déclencherait jamais : la houle
 * descendrait une fois et ne remonterait plus.
 *
 * <p>La règle est donc asymétrique. On dégrade quand la médiane dépasse nettement la cible ; on
 * restaure dès qu'elle la tient, à deux pour cent près — c'est-à-dire exactement ce que produit un
 * plafond atteint. Le prix de cette asymétrie est une oscillation possible d'un échelon, que le
 * délai entre deux gestes et l'exigence de <b>deux verdicts consécutifs</b> avant de remonter
 * suffisent à rendre invisible.
 */
public final class Swell {
    /** Durées d'image retenues. Deux secondes à 120 images par seconde. */
    private static final int WINDOW = 240;

    /** Secondes entre deux ajustements. Un changement d'échelle réalloue une texture plein écran. */
    private static final int COOLDOWN_SECONDS = 4;

    /**
     * Secondes de grâce après l'activation.
     *
     * <p>Les premières secondes d'un monde ne ressemblent à rien de ce qui suit : les chunks
     * arrivent, les nuanceurs se compilent, le compilateur à la volée n'a rien optimisé. Sans cette
     * grâce, la houle conclurait à une surcharge et ferait chuter la résolution dans les secondes
     * qui suivent l'arrivée du joueur, pour la lui rendre une minute plus tard. C'est le même
     * raisonnement, et la même erreur évitée, que dans la marée.
     */
    private static final int GRACE_SECONDS = 10;

    /** Au-delà de ce facteur de la cible, on dégrade. */
    private static final double SLOW = 1.15d;

    /** En deçà de ce facteur de la cible, on restaure. Voir le piège de la v-sync. */
    private static final double FAST = 1.02d;

    private static final Lens.Preset[] LADDER = Lens.Preset.values();

    private static final long[] GAPS = new long[WINDOW];
    private static int filled;
    private static int cursor;

    private static int rung;
    private static long lastLook;
    private static int grace;
    private static int sinceLastMove;
    private static boolean previousSaidFast;
    private static long adjustments;

    private Swell() {}

    /**
     * Repart du natif et réarme la grâce.
     *
     * <p>Appelée quand la houle s'allume ou que le joueur change de préréglage : dans les deux cas,
     * l'échelon courant ne veut plus rien dire, et le reprendre ferait juger le nouveau réglage sur
     * des mesures qui appartenaient à l'ancien.
     */
    public static void reset() {
        rung = 0;
        filled = 0;
        cursor = 0;
        lastLook = 0L;
        grace = GRACE_SECONDS;
        sinceLastMove = 0;
        previousSaidFast = false;
    }

    /** Le diviseur de l'échelon courant. */
    public static double divisor() {
        return LADDER[Math.min(rung, LADDER.length - 1)].divisor();
    }

    /** Combien de fois la houle a bougé. Pour le rapport du banc. */
    public static long adjustments() {
        return adjustments;
    }

    /**
     * Fait battre la houle. À appeler une fois par image ; n'agit qu'une fois par seconde.
     *
     * @param frameNanos durée de l'image précédente
     * @param targetMs   durée d'image visée, déduite du plafond de taux d'images du joueur
     */
    public static void beat(long frameNanos, double targetMs) {
        if (frameNanos > 0L) {
            GAPS[cursor] = frameNanos;
            cursor = (cursor + 1) % WINDOW;
            if (filled < WINDOW) {
                filled++;
            }
        }

        long now = System.nanoTime();
        if (lastLook == 0L) {
            lastLook = now;
            return;
        }
        if (now - lastLook < 1_000_000_000L) {
            return;
        }
        lastLook = now;

        if (grace > 0) {
            grace--;
            return;
        }
        // Moins d'un quart de fenêtre : l'affichage vient de reprendre après une pause, un
        // chargement ou une réduction de fenêtre. Une médiane sur cinq images ne décrit rien.
        if (filled < WINDOW / 4) {
            return;
        }
        if (sinceLastMove < COOLDOWN_SECONDS) {
            sinceLastMove++;
            return;
        }

        long[] sorted = Arrays.copyOf(GAPS, filled);
        Arrays.sort(sorted);
        double median = sorted[filled / 2] / 1.0e6d;

        int floor = Math.min(Lens.preset().ordinal(), LADDER.length - 1);
        if (median > targetMs * SLOW) {
            previousSaidFast = false;
            if (rung < floor) {
                move(rung + 1, "descend", median, targetMs);
            }
        } else if (median < targetMs * FAST) {
            // Deux verdicts de suite : un seul pourrait n'être qu'un passage devant un mur.
            if (previousSaidFast && rung > 0) {
                move(rung - 1, "remonte", median, targetMs);
            }
            previousSaidFast = true;
        } else {
            previousSaidFast = false;
        }
    }

    private static void move(int to, String way, double median, double targetMs) {
        Lens.Preset from = LADDER[rung];
        rung = to;
        sinceLastMove = 0;
        adjustments++;
        previousSaidFast = false;
        Lanterne.LOG.info("[HOULE] L'échelle {} : {} → {} (image médiane {} ms, cible {} ms).",
                way, from.name().toLowerCase(Locale.ROOT), LADDER[to].name().toLowerCase(Locale.ROOT),
                String.format(Locale.ROOT, "%.1f", median),
                String.format(Locale.ROOT, "%.1f", targetMs));
    }
}
