package fr.clubcitrouille.lanterne.core;

/**
 * Le rationnement : réagir <em>pendant</em> le tick, et non au suivant.
 *
 * <h2>Ce que personne n'a réussi</h2>
 *
 * <p>Immersive Optimization a tenté un système de budget, puis l'a retiré. Son journal des
 * modifications le dit sans détour : <i>« Removed budgeting mechanics (It causes unexpected
 * behavior and has limited effect) »</i>. C'est le seul module que ce mod-là ait abandonné, et il
 * est resté depuis sur une sévérité calculée d'après la moyenne du tick précédent.
 *
 * <p>Le défaut de cette approche se voit sur un pic : le tick déborde, on le constate, et l'on
 * durcit la sévérité <b>pour le tick suivant</b>. Si le pic est isolé — une explosion, un afflux de
 * créatures, un chunk qui se génère — la correction arrive après la bataille et pénalise un tick qui
 * n'en avait pas besoin. Si le pic dure, on met plusieurs ticks à s'y adapter.
 *
 * <h2>Le rationnement, et pourquoi il est différent</h2>
 *
 * <p>Ici, on mesure le temps consommé <b>à l'intérieur</b> du tick en cours. Quand la part allouée à
 * la simulation est dépassée, la sévérité monte <em>immédiatement</em>, pour les créatures qui n'ont
 * pas encore été traitées dans ce même tick.
 *
 * <p>La conséquence est un lissage : un tick qui dérape est freiné avant de déraper tout à fait, au
 * lieu d'être laissé courir puis compensé après coup. C'est la différence entre un limiteur et un
 * thermostat.
 *
 * <h2>Pourquoi cela ne coûte presque rien</h2>
 *
 * <p>Lire l'horloge à chaque créature serait ruineux : {@code System.nanoTime()} est un appel
 * système déguisé, et cent mille appels par tick coûteraient plus cher que tout ce qu'on économise.
 *
 * <p>On ne la lit donc qu'une fois toutes les deux cent cinquante-six créatures, au moyen d'un
 * masque binaire. L'échantillon est largement assez dense pour repérer un débordement — deux cent
 * cinquante-six créatures se traitent en une fraction de milliseconde — et son coût se perd dans le
 * bruit.
 *
 * <h2>Ce qu'il ne fait jamais</h2>
 *
 * <p>Le rationnement <b>ne coupe rien</b>. Il ne fait qu'avancer le curseur de sévérité déjà en
 * place : les créatures proches restent en pleine simulation, les garanties tiennent, et ce qui
 * tombe tombe. Un budget qui, sous pression, se mettrait à figer ce que le joueur regarde ne serait
 * pas un budget mais une panne.
 */
public final class Rationing {
    /**
     * Part du tick réservée à la simulation des entités, en nanosecondes.
     *
     * <p>Vingt-cinq millisecondes sur les cinquante d'un tick. Le reste appartient au réseau, à la
     * sauvegarde, à la génération de terrain et aux blocs — que ce module ne commande pas et qu'il
     * n'a donc pas à se réserver.
     */
    private static final long SHARE_NANOS = 25_000_000L;

    /**
     * Une créature sur combien déclenche une lecture d'horloge.
     *
     * <p>Un masque plutôt qu'un modulo : deux cent cinquante-six est une puissance de deux, et le
     * compilateur en fait un simple « et » binaire.
     */
    private static final int SAMPLE_MASK = 0xFF;

    private static long tickStart;
    private static int seen;
    private static double overrun;

    /** Ticks où la part a été dépassée, pour le rapport. */
    private static long tightTicks;
    private static long totalTicks;

    private Rationing() {}

    /** Ouvre le tick. Remet le compteur de dépassement à zéro. */
    public static void beginTick() {
        tickStart = System.nanoTime();
        seen = 0;
        overrun = 0d;
        totalTicks++;
    }

    /**
     * Sévérité supplémentaire à appliquer, de zéro à un.
     *
     * <p>Appelé pour chaque créature. L'immense majorité des appels se résume à un incrément, un
     * « et » binaire et une comparaison.
     */
    public static double pressure() {
        if ((++seen & SAMPLE_MASK) == 0) {
            long spent = System.nanoTime() - tickStart;
            if (spent > SHARE_NANOS) {
                // Au-delà de la part, la sévérité monte avec le dépassement et sature à une part
                // entière de trop. Un tick deux fois trop long n'a pas besoin d'une sévérité
                // doublée : il a besoin de la sévérité maximale, et tout de suite.
                double fresh = Math.min(1d, (spent - SHARE_NANOS) / (double) SHARE_NANOS);
                if (fresh > overrun) {
                    if (overrun == 0d) {
                        tightTicks++;
                    }
                    overrun = fresh;
                }
            }
        }
        return overrun;
    }

    /** Part des ticks où la ration a été dépassée. */
    public static double tightShare() {
        return totalTicks == 0L ? 0d : (double) tightTicks / totalTicks;
    }

    public static void resetCounters() {
        tightTicks = 0L;
        totalTicks = 0L;
    }
}
