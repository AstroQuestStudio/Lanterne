package fr.clubcitrouille.lanterne.core;

import net.minecraft.server.MinecraftServer;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La marée : les distances montent quand le serveur respire, et redescendent avant qu'il ne suffoque.
 *
 * <h2>Le problème que les réglages fixes ne peuvent pas résoudre</h2>
 *
 * <p>Un administrateur choisit une distance de vue une fois pour toutes. S'il la met à dix, son
 * serveur est magnifique à deux joueurs et s'effondre à vingt. S'il la met à six, il est sûr de ne
 * jamais ramer, et il aura rogné sur l'expérience de tout le monde pendant les quatre-vingt-dix pour
 * cent du temps où la machine s'ennuyait.
 *
 * <p>Le réglage juste n'existe pas, parce que la charge n'est pas constante. Ce qui existe, c'est un
 * réglage <b>qui suit la charge</b>.
 *
 * <h2>Le principe</h2>
 *
 * <p>Une fois par seconde, on lit le temps de tick lissé que le serveur tient lui-même :
 *
 * <ul>
 *   <li>au-dessus de la cible, on <b>dégrade un seul</b> réglage, le moins douloureux d'abord ;</li>
 *   <li>en dessous, on en <b>restaure un seul</b>, le plus précieux d'abord ;</li>
 *   <li>entre les deux, on ne touche à rien.</li>
 * </ul>
 *
 * <p>Un seul palier à la fois, et jamais deux réglages dans le même passage : une correction brutale
 * dépasserait sa cible et provoquerait l'oscillation qu'on cherche à éviter.
 *
 * <h2>L'ordre de sacrifice, et pourquoi il est dans ce sens</h2>
 *
 * <ol>
 *   <li><b>La distance de simulation</b> d'abord. Elle décide de ce qui <em>vit</em> loin du joueur :
 *       créatures, pousses, redstone. La réduire coûte peu à qui regarde le paysage, et c'est elle
 *       qui pèse le plus lourd — chaque chunk simulé tick ses entités et ses blocs.</li>
 *   <li><b>La distance de vue</b> ensuite. Elle décide de ce qu'on <em>voit</em>. La réduire se
 *       remarque immédiatement, mais elle coûte surtout en envoi de données, pas en calcul.</li>
 * </ol>
 *
 * <p>Ce sens peut surprendre : on abîme d'abord ce qui se voit le moins, et la simulation lointaine
 * est justement ce dont personne ne s'aperçoit. Un joueur remarque tout de suite un horizon qui
 * recule ; il ne remarque pas qu'un mouton s'est arrêté de brouter à cent quatre-vingts blocs.
 *
 * <h2>Les deux garde-fous</h2>
 *
 * <ul>
 *   <li><b>Une bande morte.</b> On ne réagit qu'au-delà de {@value #MARGIN_MS} millisecondes de part
 *       et d'autre de la cible. Sans elle, le serveur passerait son temps à corriger le bruit.</li>
 *   <li><b>Un délai entre deux gestes.</b> {@value #COOLDOWN_SECONDS} secondes, pour laisser à la
 *       mesure lissée le temps de refléter le changement précédent. Sans ce délai, la marée
 *       réagirait à ses propres effets avant de les avoir constatés, et descendrait jusqu'au plancher
 *       en quelques secondes.</li>
 * </ul>
 *
 * <h2>Ce que la marée ne fait jamais</h2>
 *
 * <p>Elle ne dépasse pas les valeurs que l'administrateur a demandées. Le plafond est ce qui figure
 * dans {@code server.properties} au démarrage : la marée peut descendre en dessous et y revenir,
 * jamais aller au-delà. Un serveur réglé en dix ne se retrouvera pas en seize parce que la machine
 * avait de la marge.
 *
 * <p>Inspirée de ServerCore (MIT), que la licence GPL-3.0 de ce mod absorbe.
 */
public final class Tide {
    /** Temps de tick visé, en millisecondes. Le budget d'un tick est de cinquante. */
    private static final double TARGET_MS = 42.0;

    /** Bande morte de part et d'autre de la cible, en millisecondes. */
    private static final double MARGIN_MS = 6.0;

    /** Secondes d'attente entre deux ajustements. */
    private static final int COOLDOWN_SECONDS = 8;

    /**
     * Secondes de grâce après l'ouverture du monde, pendant lesquelles la marée ne fait rien.
     *
     * <h2>Le défaut que ceci évite</h2>
     *
     * <p>Les premières secondes d'un monde ne ressemblent à rien de ce qui suit : les chunks
     * arrivent, les entités se chargent, le compilateur à la volée n'a rien optimisé. Le tick lissé
     * y dépasse largement la cible — <em>sans</em> que le serveur soit en difficulté.
     *
     * <p>Sans cette grâce, la marée conclurait à une surcharge et ferait reculer l'horizon du joueur
     * dans les secondes qui suivent son arrivée, pour le lui rendre une minute plus tard. En solo,
     * c'est le premier geste qu'il verrait du mod, et il passerait pour un défaut.
     *
     * <p>C'est le même raisonnement que la chauffe du banc, qui jette ses deux cents premiers ticks
     * pour la même raison : ce qu'on mesure au démarrage n'est pas ce qu'on veut mesurer.
     */
    private static final int GRACE_SECONDS = 90;

    /** Distance de vue en dessous de laquelle on ne descend jamais. */
    private static final int FLOOR_VIEW = 4;

    /** Distance de simulation en dessous de laquelle on ne descend jamais. */
    private static final int FLOOR_SIMULATION = 3;

    /** Les plafonds, relevés au démarrage : ce que l'administrateur a demandé. */
    private static int ceilingView = -1;
    private static int ceilingSimulation = -1;

    private static int sinceLastMove;
    private static int grace;
    private static long adjustments;

    private Tide() {}

    /** Relève les plafonds. Appelé une fois, quand le serveur est prêt. */
    public static void anchor(MinecraftServer server) {
        ceilingView = server.getPlayerList().getViewDistance();
        ceilingSimulation = server.getPlayerList().getSimulationDistance();
        sinceLastMove = 0;
        grace = GRACE_SECONDS;
        adjustments = 0L;
        Lanterne.LOG.info("[MARÉE] Plafonds relevés — vue {}, simulation {}. La marée ne montera "
                + "jamais au-dessus, et ne touchera à rien pendant {} s.",
                ceilingView, ceilingSimulation, GRACE_SECONDS);
    }

    /**
     * Lève la grâce sur-le-champ.
     *
     * <p>Réservé à l'épreuve de marée, qui pose sa charge elle-même et n'a pas à attendre une
     * minute et demie pour voir si le module réagit. En jeu, personne n'appelle ceci : la grâce
     * existe précisément pour que le démarrage d'un monde ne soit pas pris pour une surcharge.
     */
    public static void skipGrace() {
        grace = 0;
    }

    /**
     * Fait battre la marée. À appeler à chaque tick de serveur ; n'agit qu'une fois par seconde.
     */
    public static void tick(MinecraftServer server) {
        if (!Settings.tide() || ceilingView < 0) {
            return;
        }
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        if (grace > 0) {
            grace--;
            return;
        }
        // Personne a servir : les distances n'ont aucun effet, et le tick lisse d'un serveur vide
        // ferait remonter la maree au plafond sans que cela signifie quoi que ce soit.
        if (server.getPlayerList().getPlayerCount() == 0) {
            return;
        }
        if (sinceLastMove < COOLDOWN_SECONDS) {
            sinceLastMove++;
            return;
        }

        double mspt = server.getCurrentSmoothedTickTime();
        if (mspt > TARGET_MS + MARGIN_MS) {
            if (lower(server)) {
                sinceLastMove = 0;
            }
        } else if (mspt < TARGET_MS - MARGIN_MS) {
            if (raise(server)) {
                sinceLastMove = 0;
            }
        }
    }

    /** Dégrade un réglage. Rend vrai si quelque chose a bougé. */
    private static boolean lower(MinecraftServer server) {
        var players = server.getPlayerList();
        int simulation = players.getSimulationDistance();
        if (simulation > FLOOR_SIMULATION) {
            players.setSimulationDistance(simulation - 1);
            announce("descend", "simulation", simulation, simulation - 1, server);
            return true;
        }
        int view = players.getViewDistance();
        if (view > FLOOR_VIEW) {
            players.setViewDistance(view - 1);
            announce("descend", "vue", view, view - 1, server);
            return true;
        }
        // Tout est au plancher et le serveur rame encore : ce n'est plus un problème de distance.
        return false;
    }

    /** Restaure un réglage. Rend vrai si quelque chose a bougé. */
    private static boolean raise(MinecraftServer server) {
        var players = server.getPlayerList();
        // L'ordre inverse de la descente : on rend d'abord ce qu'on a retiré en dernier.
        int view = players.getViewDistance();
        if (view < ceilingView) {
            players.setViewDistance(view + 1);
            announce("remonte", "vue", view, view + 1, server);
            return true;
        }
        int simulation = players.getSimulationDistance();
        if (simulation < ceilingSimulation) {
            players.setSimulationDistance(simulation + 1);
            announce("remonte", "simulation", simulation, simulation + 1, server);
            return true;
        }
        return false;
    }

    private static void announce(String way, String what, int from, int to, MinecraftServer server) {
        adjustments++;
        Lanterne.LOG.info("[MARÉE] La distance de {} {} : {} → {} (tick lissé à {} ms).",
                what, way, from, to,
                String.format(java.util.Locale.ROOT, "%.1f", server.getCurrentSmoothedTickTime()));
    }

    /** Combien de fois la marée a bougé depuis le démarrage. Pour le rapport du banc. */
    public static long adjustments() {
        return adjustments;
    }

    /** L'état courant, en une ligne, pour la commande d'état. */
    public static String describe(MinecraftServer server) {
        if (ceilingView < 0) {
            return "marée non amorcée";
        }
        var players = server.getPlayerList();
        return String.format(java.util.Locale.ROOT,
                "vue %d/%d · simulation %d/%d · tick lissé %.1f ms · %d ajustement(s)%s",
                players.getViewDistance(), ceilingView,
                players.getSimulationDistance(), ceilingSimulation,
                server.getCurrentSmoothedTickTime(), adjustments,
                grace > 0 ? " · en grâce encore " + grace + " s" : "");
    }

    public static void reset() {
        adjustments = 0L;
        sinceLastMove = 0;
    }
}
