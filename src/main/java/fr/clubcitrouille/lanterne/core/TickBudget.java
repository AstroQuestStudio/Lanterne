package fr.clubcitrouille.lanterne.core;

/**
 * Le budget de temps d'un tick, et sa répartition.
 *
 * <h2>Ce qu'aucun mod d'optimisation ne fait</h2>
 *
 * <p>Une instance chargée en compte facilement vingt : Lithium, ModernFix, FerriteCore, ServerCore,
 * les réglages adaptatifs, les limiteurs d'IA, les fusionneurs d'objets au sol. Chacun est
 * raisonnable. Aucun ne sait ce que font les autres, et surtout : <b>aucun ne sait combien de temps
 * il reste dans le tick</b>.
 *
 * <p>Ils optimisent donc à l'aveugle, avec une sévérité fixée d'avance dans un fichier de
 * configuration. Trop douce, elle laisse le serveur ramer aux heures de pointe ; trop dure, elle
 * dégrade le jeu en permanence pour un problème qui ne survient qu'une fois par heure. Le réglage
 * juste n'existe pas, parce que la bonne réponse dépend de l'instant.
 *
 * <p>Un tick dispose de cinquante millisecondes. C'est un fait, il est mesurable, et il devrait
 * commander toute la sévérité du système. C'est ce que fait cette classe, et c'est ce qui distingue
 * un ensemble de mods d'un mod unique : <b>on ne peut pas répartir un budget qu'on ne connaît
 * pas</b>.
 *
 * <h2>La mesure, et ce qu'elle doit ignorer</h2>
 *
 * <p>On mesure le tick complet, en temps réel, et l'on en tire une pression entre zéro et un. Mais
 * la mesure brute est inutilisable telle quelle : une sauvegarde automatique, une génération de
 * chunk ou une pause du ramasse-miettes produisent des pics de plusieurs centaines de millisecondes
 * qui n'ont rien à voir avec la charge de simulation. Réagir à ces pics ferait osciller la sévérité
 * d'un extrême à l'autre — et l'oscillation est pire que le mal, parce que le joueur la voit.
 *
 * <p>On lisse donc, fortement, et l'on monte plus vite qu'on ne descend : se protéger doit être
 * immédiat, se détendre doit être prudent. C'est la même asymétrie qu'un limiteur audio, et pour la
 * même raison.
 */
public final class TickBudget {
    /** Durée nominale d'un tick, en nanosecondes : vingt ticks par seconde. */
    private static final long NOMINAL_NANOS = 50_000_000L;

    /**
     * Part du tick qu'on vise pour la simulation.
     *
     * <p>Pas la totalité : il faut laisser de la place au réseau, à la sauvegarde et à la génération
     * de terrain, qui ne passent pas par ici. Viser cent pour cent reviendrait à ne se réveiller
     * qu'une fois le serveur déjà en retard.
     */
    private static final double TARGET_SHARE = 0.6d;

    /** Constante de lissage à la montée : on se protège vite. */
    private static final double RISE = 0.25d;
    /** Constante de lissage à la descente : on se détend lentement. */
    private static final double FALL = 0.02d;

    /**
     * Au-delà, la mesure est jetée.
     *
     * <p>Un tick de plus d'une seconde n'est pas un tick chargé, c'est un évènement : une
     * sauvegarde, un chargement de dimension, une pause du ramasse-miettes. En tenir compte
     * ferait bondir la sévérité au maximum pour une cause qu'aucune dégradation ne résoudra.
     */
    private static final long OUTLIER_NANOS = 1_000_000_000L;

    private static long tickStart;
    private static double pressure;
    private static long ticks;

    /** Moyenne glissante du temps de tick, en nanosecondes — pour le rapport. */
    private static double averageNanos;
    /** Pire tick observé depuis la dernière remise à zéro. */
    private static long worstNanos;

    private TickBudget() {}

    /** Ouvre la mesure. Appelé au tout début du tick du serveur. */
    public static void beginTick() {
        tickStart = System.nanoTime();
    }

    /** Ferme la mesure et met à jour la pression. Appelé à la toute fin du tick. */
    public static void endTick() {
        if (tickStart == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - tickStart;
        tickStart = 0L;
        ticks++;

        if (elapsed > OUTLIER_NANOS) {
            return; // une sauvegarde ou un ramasse-miettes : rien à apprendre de ce tick
        }

        averageNanos += (elapsed - averageNanos) * 0.05d;
        worstNanos = Math.max(worstNanos, elapsed);

        // Zéro quand le tick tient dans la part visée, un quand il déborde du tick entier.
        double used = (double) elapsed / NOMINAL_NANOS;
        double wanted = (used - TARGET_SHARE) / (1d - TARGET_SHARE);
        wanted = wanted < 0d ? 0d : wanted > 1d ? 1d : wanted;

        double rate = wanted > pressure ? RISE : FALL;
        pressure += (wanted - pressure) * rate;
    }

    /**
     * La sévérité à appliquer, de zéro à un.
     *
     * <p>Zéro : le serveur est au repos, on ne dégrade rien. Un : il est en difficulté, on économise
     * tout ce qui peut l'être. Entre les deux, un continuum — et c'est le continuum qui compte, car
     * il permet au système de ne jamais être ni trop doux ni trop dur.
     */
    public static double pressure() {
        return pressure;
    }

    /** Temps de tick moyen en millisecondes, pour le rapport. */
    public static double averageMillis() {
        return averageNanos / 1_000_000d;
    }

    /** Pire tick observé, en millisecondes. */
    public static double worstMillis() {
        return worstNanos / 1_000_000d;
    }

    public static long ticksObserved() {
        return ticks;
    }

    public static void resetPeaks() {
        worstNanos = 0L;
    }
}
