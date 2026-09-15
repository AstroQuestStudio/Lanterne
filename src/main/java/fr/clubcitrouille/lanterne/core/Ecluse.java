package fr.clubcitrouille.lanterne.core;

/**
 * L'écluse : la livraison des chunks passe après tout le reste.
 *
 * <h2>Ce que la mesure a établi</h2>
 *
 * <p>Le banc du seuil ({@code lab/Seuil}) a chiffré une arrivée à distance de vue dix, sur un monde
 * déjà écrit :
 *
 * <pre>
 * arrivee a froid : 444 chunks, 2197 ms cumulees, PIRE TICK 96 ms
 * arrivee a chaud : 441 chunks,  939 ms cumulees, pire tick 41 ms
 * changement de dimension : 442 chunks, 2626 ms, pire tick 44 ms
 * </pre>
 *
 * <p>Le chiffre qui se <b>ressent</b> n'est aucun des totaux : c'est le <b>pire tick</b>. Un tick de
 * quatre-vingt-seize millisecondes, c'est le serveur qui saute deux battements — et sur la machine
 * visée, à un cœur et deux gigahertz, il faut compter deux à trois fois plus. C'est exactement ce
 * qu'un joueur décrit quand il dit que le serveur « lague quand quelqu'un se connecte ».
 *
 * <h2>ÉTEINT PAR DÉFAUT : la mesure n'a pas donné raison à ce module</h2>
 *
 * <p>Il faut lire les deux lignes du tableau ci-dessus dans le bon sens. À froid, le pire tick vaut
 * quatre-vingt-seize millisecondes ; à chaud, quand les chunks sont <b>déjà en mémoire</b> et qu'il
 * ne reste que l'emballage et l'envoi, il tombe à <b>trente-quatre</b>.
 *
 * <p>Les trois quarts du pic sont donc dans le <b>chargement</b>, et le chargement n'a pas lieu
 * ici. Une écluse posée à l'entrée de l'envoi ne peut rien contre lui — et c'est ce que la mesure
 * a confirmé, en cinq exécutions alternées :
 *
 * <pre>
 * pire tick SANS ecluse : 96,0 / 94,6 / 128,7 / 105,1 ms   moyenne 106
 * pire tick AVEC ecluse : 117,5 /  75,8 / 132,9 ms         moyenne 109
 * </pre>
 *
 * <p>Aucune différence, et l'arrivée dure onze pour cent de plus. Le seul signe favorable porte sur
 * le changement de dimension — soixante-dix-sept millisecondes de pire tick sans, soixante-quatre
 * avec — mais la dispersion y est du même ordre que l'écart, et deux échantillons de chaque côté ne
 * concluent rien.
 *
 * <p>Ce fichier reste, parce qu'il est juste et qu'éteint il coûte une comparaison. Ce qui
 * disparaît, c'est la prétention qu'il servait à quelque chose. <b>La leçon est ailleurs et elle
 * vaut mieux que le module</b> : le lag d'une arrivée n'est pas dans l'envoi des chunks, il est
 * dans leur mise en mémoire. C'est là qu'il faudra revenir.
 *
 * <h2>Pourquoi on peut agir, et pourquoi c'est ici</h2>
 *
 * <p>Vanilla livre les chunks à la <b>toute fin</b> du tick du serveur, après les mondes, après les
 * entités, après tout. Le placement est heureux : à cet instant, le coût du tick est <b>déjà
 * connu</b>. On n'a donc rien à prévoir ni à estimer — il suffit de regarder l'horloge.
 *
 * <p>Ce que l'écluse fait tient en une phrase : <b>si le tick a déjà dépassé sa part, la livraison
 * attend le tick suivant.</b> Rien n'est perdu, rien n'est annulé ; les chunks restent en attente et
 * partiront dès qu'il y aura de la place.
 *
 * <h2>Ce que cela ne fait pas, et qu'il ne faut pas lui prêter</h2>
 *
 * <p>Cela ne réduit <b>pas</b> le travail total. Les mêmes chunks sont emballés, le même nombre de
 * fois. Ce qui change est leur <em>répartition</em> : au lieu de deux ticks à quatre-vingt-seize
 * millisecondes, plusieurs ticks qui tiennent dans leur budget.
 *
 * <p>Et cela se paie : une arrivée peut durer un peu plus longtemps. C'est le bon échange — un
 * joueur qui voit son monde se remplir en trois secondes au lieu de deux ne le remarque pas ; tous
 * les autres joueurs remarquent un serveur qui saute.
 *
 * <h2>Le garde-fou, sans lequel ce module serait une panne</h2>
 *
 * <p>Sur un serveur durablement au-delà du budget — mille entités, une ferme qui tourne — la
 * condition serait vraie à <b>chaque</b> tick, et le joueur n'aurait <b>jamais</b> ses chunks. Il
 * resterait dans un monde vide, indéfiniment, et ce serait bien pire que le lag qu'on voulait
 * corriger.
 *
 * <p>Une famine est donc impossible par construction : au bout de {@link #STARVE_TICKS} refus
 * consécutifs, on laisse passer quoi qu'il arrive. Le débit minimal garanti est donc d'un lot tous
 * les quarts de seconde — lent, mais monotone, et surtout jamais nul.
 */
public final class Ecluse {
    /**
     * Part du tick au-delà de laquelle la livraison attend, en millisecondes.
     *
     * <p>Un tick dure cinquante millisecondes. Trente laisse vingt millisecondes de livraison avant
     * de déborder, ce qui est plus que ce qu'un lot coûte en régime normal — l'écluse ne se ferme
     * donc que lorsqu'il se passe réellement quelque chose d'autre.
     *
     * <p>Ce n'est volontairement pas un réglage : un seuil exposé serait réglé au hasard, et il n'y
     * a rien à régler. La valeur se déduit de la durée d'un tick, qui ne change pas.
     */
    private static final double GATE_MS = 30d;

    /**
     * Refus consécutifs après lesquels on laisse passer de force.
     *
     * <p>Cinq ticks, soit un quart de seconde. Voir la note sur la famine dans l'en-tête : c'est ce
     * nombre-là qui transforme « la livraison attend » en « la livraison ralentit ».
     */
    private static final int STARVE_TICKS = 5;

    private Ecluse() {}

    /** Refus consécutifs, tous joueurs confondus. Voir {@link #STARVE_TICKS}. */
    private static int refused;

    /** Livraisons retenues depuis le démarrage, pour le rapport. */
    private static long held;

    /**
     * La livraison peut-elle avoir lieu maintenant ?
     *
     * <p>Appelée une fois par joueur et par tick, à la toute fin du tick. Le compteur de refus est
     * <b>commun</b> et non par joueur, et c'est voulu : ce qu'on protège est le tick du serveur, qui
     * est commun lui aussi. Compter par joueur laisserait dix joueurs franchir dix fois le même
     * garde-fou.
     */
    public static boolean mayDeliver() {
        if (!Settings.ecluse()) {
            return true;
        }
        if (refused >= STARVE_TICKS) {
            refused = 0;
            return true;
        }
        if (TickBudget.elapsedThisTickMillis() < GATE_MS) {
            refused = 0;
            return true;
        }
        refused++;
        held++;
        return false;
    }

    /** Nombre de livraisons reportées depuis le démarrage. */
    public static long held() {
        return held;
    }
}
