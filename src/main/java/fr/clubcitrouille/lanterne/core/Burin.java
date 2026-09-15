package fr.clubcitrouille.lanterne.core;

import net.minecraft.server.level.ServerPlayer;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le burin : casser un bloc sur une mauvaise connexion, et que le bloc parte.
 *
 * <h2>Le défaut, dans les mots du joueur</h2>
 *
 * <blockquote>« Je casse, mais à cause de mes problèmes réseau ça veut pas tout casser. »</blockquote>
 *
 * <p>Casser un bloc en multijoueur est <b>prédit par le client et arbitré par le serveur</b>. Le
 * client applique la casse chez lui tout de suite, numérote son geste, et attend. Le serveur juge,
 * puis accuse ce numéro — <b>qu'il ait détruit ou non</b> :
 *
 * <pre>
 * // ServerGamePacketListenerImpl.handlePlayerAction
 * this.player.gameMode.handleBlockBreakAction(pos, action, …, packet.getSequence());
 * this.player.connection.ackBlockChangesUpTo = packet.getSequence();   // dans TOUS les cas
 * </pre>
 *
 * <p>Le client, en recevant l'accusé, <b>annule sa prédiction</b> et remet l'état qu'il connaissait :
 * {@code BlockStatePredictionHandler.endPredictionsUpTo} rappelle {@code syncBlockState}. Le bloc
 * réapparaît. C'est le bloc fantôme.
 *
 * <h2>Ce qu'il faut avoir compris avant de toucher à quoi que ce soit</h2>
 *
 * <p><b>Il n'y a aucun délai d'expiration à élargir.</b> On cherche naturellement « combien de temps
 * le client garde-t-il sa prédiction avant d'abandonner ». La réponse est : <b>il n'abandonne
 * jamais tout seul</b>. {@code BlockStatePredictionHandler} ne porte aucune horloge — seulement un
 * numéro de séquence, et une carte de positions qu'il vide quand l'accusé arrive. Le serveur, lui,
 * envoie cet accusé en <b>tête du tick suivant</b>. Le bloc fantôme n'est donc pas une prédiction
 * périmée : <b>c'est un refus</b>, appliqué exactement comme il faut.
 *
 * <p>La conséquence est nette, et elle tranche la question « faut-il garder la prédiction plus
 * longtemps côté client ? » : <b>non</b>. La garder plus longtemps reviendrait à <em>ignorer un
 * accusé reçu</em>, c'est-à-dire à montrer au joueur un bloc cassé dont le serveur vient de dire
 * qu'il ne l'est pas. Il le traverserait des yeux et pas des pieds, le remettrait en place au
 * moindre rechargement de chunk, et minerait dans le vide. <b>Un bloc fantôme qui revient vite est
 * désagréable ; un bloc fantôme qui reste est un mensonge.</b> On corrige donc le refus, jamais son
 * affichage.
 *
 * <h2>Les deux refus que le réseau provoque, et qui sont réparables</h2>
 *
 * <h3>Un — la portée du bras, jugée sur une position périmée</h3>
 *
 * <pre>
 * if (!this.player.isWithinBlockInteractionRange(pos, 1.0)) {
 *     this.debugLogging(pos, false, sequence, "too far");   // et RIEN d'autre
 * }
 * </pre>
 *
 * <p>Ce contrôle existe pour refuser la triche, et il doit rester. Mais il compare la case visée à
 * la position que le <b>serveur</b> croit au joueur — laquelle a l'âge du dernier paquet de
 * mouvement reçu, soit une demi-latence. Un joueur qui marche vers un arbre à 5,6 blocs par seconde
 * avec 400 ms d'aller-retour est, pour le serveur, <b>1,1 bloc en arrière</b> de là où il se voit.
 * La marge de vanilla vaut un bloc. Elle est franchie, et l'action est jetée <em>en silence</em> :
 * pas de destruction, pas de renvoi de l'état du bloc, mais l'accusé part tout de même. Fantôme.
 *
 * <p>On élargit donc cette marge — et <b>seulement en proportion de la latence réellement
 * mesurée</b>. C'est la règle qui prime sur le confort : la tolérance qu'on ajoute vaut exactement
 * l'incertitude que la ligne a créée, elle est plafonnée, et elle <b>revient au chiffre de vanilla
 * sur une bonne connexion</b>. Un joueur à 20 ms ne gagne rien du tout. Un joueur qui casserait à
 * dix blocs reste refusé, quelle que soit sa latence.
 *
 * <h3>Deux — la place unique du rattrapage, qui se coince</h3>
 *
 * <p>Quand le serveur trouve la progression sous les sept dixièmes, il ne jette pas la case : il la
 * range pour l'abattre lui-même, plus tard, à cent pour cent. C'est le filet de vanilla, et il est
 * bon. Mais il n'a <b>qu'une seule place</b> :
 *
 * <pre>
 * if (!this.hasDelayedDestroy) {      // ← si elle est prise, le bloc n'est pas différé : il est PERDU
 *     this.hasDelayedDestroy = true;
 *     this.delayedDestroyPos = pos;
 * }
 * </pre>
 *
 * <p>et son tick passe <b>avant</b> celui du cassage en cours :
 *
 * <pre>
 * if (this.hasDelayedDestroy) { … } else if (this.isDestroyingBlock) { … }
 * </pre>
 *
 * <p>Un seul refus, et tant que la case retenue n'est pas échue, <b>les suivantes n'ont plus de
 * filet</b> et n'avancent même plus à l'écran. Sur une mauvaise ligne les refus arrivent en série,
 * et c'est très exactement « ça veut pas <em>tout</em> casser » : ce n'est pas un bloc qui résiste,
 * c'est le premier refus qui condamne ceux d'après.
 *
 * <p>La règle qu'on pose tient en une phrase : <b>la place du rattrapage appartient toujours à la
 * case sur laquelle le joueur travaille maintenant.</b> Dès qu'une action porte sur une autre case,
 * l'ancienne rend la place.
 *
 * <p>Ce n'est <b>pas</b> un élargissement de tolérance : la case qui prend la place devra toujours
 * atteindre <b>1,0</b>, c'est-à-dire plus que les 0,7 du jugement direct. Et la case qui rend la
 * place n'est pas volée au joueur : c'est celle qu'il a <em>quittée</em>, et que vanilla aurait fait
 * tomber dans son dos une seconde plus tard — un défaut que tout le monde connaît sous le nom de
 * « les blocs se cassent tout seuls ».
 *
 * <h2>Ce que ce module ne fait pas, et ne doit jamais faire</h2>
 *
 * <ul>
 *   <li><b>Il ne touche pas au seuil de 0,7.</b> Un client qui mentirait sur sa vitesse de minage
 *       serait refusé exactement comme avant.</li>
 *   <li><b>Il ne touche à rien côté client.</b> Voir plus haut : il n'y a pas de délai à allonger,
 *       et allonger un affichage serait pire que le défaut.</li>
 *   <li><b>Il ne s'élargit jamais sur la foi d'un symptôme.</b> Compter les refus et élargir quand
 *       ils se multiplient serait commode — et ce serait offrir au tricheur le moyen d'ouvrir
 *       lui-même la porte, en frappant à distance jusqu'à ce qu'elle cède. <b>Seule la latence
 *       mesurée par le serveur peut élargir la marge.</b></li>
 * </ul>
 *
 * <h2>La forme : plancher, hystérésis, délai de grâce</h2>
 *
 * <p>Reprise de {@link Tide} et {@link TickBudget}, avec une asymétrie <b>inversée</b>, et c'est
 * délibéré. La marée protège la fréquence d'images : elle se dégrade vite et se rétablit lentement.
 * Ici, ce qui s'élargit est une <b>surface d'attaque</b> : on l'accorde donc <em>lentement</em> et
 * on la retire <em>vite</em>. Une ligne qui s'améliore doit refermer la porte au plus tôt ; une
 * ligne qui se dégrade peut attendre quelques secondes de plus.
 */
public final class Burin {

    /**
     * Retire la robustesse au réseau, exprès, pour vérifier que l'épreuve sait la voir.
     *
     * <p>Même principe que {@code EntityThrottle.BROKEN_ON_PURPOSE} : une épreuve qui ne peut pas
     * échouer ne prouve rien. {@code LANTERNE_BREAK_BURIN=1} rend au serveur sa marge d'un bloc et
     * sa place unique ; {@code lab.Friture} <b>doit</b> alors annoncer des blocs fantômes.
     */
    private static final boolean BROKEN_ON_PURPOSE =
            "1".equals(System.getenv("LANTERNE_BREAK_BURIN"));

    /**
     * La marge de vanilla, en blocs. Le plancher, et il n'est jamais franchi vers le bas.
     *
     * <p>Lue dans {@code ServerPlayerGameMode.handleBlockBreakAction} :
     * {@code isWithinBlockInteractionRange(pos, 1.0)}.
     */
    public static final double VANILLA_BUFFER = 1.0d;

    /**
     * Latence en deçà de laquelle on n'ajoute rien du tout, en millisecondes.
     *
     * <p>Le plancher au sens de {@link Tide} : une bonne connexion ne doit pas payer l'existence de
     * ce module, et surtout ne doit pas en tirer un avantage. Soixante millisecondes d'aller-retour,
     * c'est une demi-latence de trente, soit dix-sept centimètres de doute sur la position d'un
     * joueur qui sprinte. La marge de vanilla les couvre six fois.
     */
    private static final double FREE_MS = 60d;

    /**
     * Vitesse retenue pour convertir du temps en blocs, en blocs par seconde.
     *
     * <p>C'est la vitesse de sprint d'un joueur à pied, et c'est le pire cas honnête : au-delà, le
     * joueur est monté sur quelque chose ou tire parti d'un effet, et il ne mine pas à la main en
     * même temps. On ne prend pas le sprint-saut (7,1) : il ne se tient pas sur la durée, et une
     * tolérance se dimensionne sur ce qui dure.
     */
    private static final double SPEED_BLOCKS_PER_SECOND = 5.6d;

    /**
     * Ce qu'on n'ajoutera jamais, quoi que dise la latence, en blocs.
     *
     * <p>Deux blocs : la portée passe donc au pire de 4,5 + 1 à 4,5 + 3, soit 7,5. Au-delà, une
     * connexion n'explique plus rien — c'est un client qui frappe loin, et il doit être refusé même
     * s'il se présente avec deux secondes de latence.
     */
    private static final double CAP_BLOCKS = 2.0d;

    /**
     * Lissage à l'élargissement, et à la fermeture.
     *
     * <p>Voir la note de classe : l'asymétrie est l'inverse de celle de {@link TickBudget}. On
     * accorde par pas de deux centièmes — il faut une bonne minute de mauvaise ligne pour ouvrir en
     * grand — et l'on referme par pas d'un quart, soit quelques secondes.
     */
    private static final double OPEN_RATE = 0.02d;
    private static final double CLOSE_RATE = 0.25d;

    /**
     * Secondes de grâce après le démarrage du serveur, pendant lesquelles la marge reste celle de
     * vanilla.
     *
     * <h2>Pourquoi une grâce alors qu'on part déjà de zéro</h2>
     *
     * <p>Parce que la latence que le serveur croit à un joueur qui vient d'arriver ne vient pas
     * d'une mesure : elle vient du jeton de connexion, recopié tel quel. La vraie mesure n'arrive
     * qu'au premier échange de présence, et le jeu n'en envoie un que toutes les <b>quinze
     * secondes</b>. Élargir une marge sur un chiffre qui n'a encore rien mesuré, c'est exactement la
     * faute que ce dépôt a commise cinq fois sous d'autres noms.
     */
    private static final int GRACE_SECONDS = 30;

    /**
     * Marge lissée par joueur, en blocs, hors plancher. Zéro sur une bonne ligne.
     *
     * <h2>Pourquoi une table et non une seule valeur</h2>
     *
     * <p>La marge compense l'incertitude de position d'<b>un</b> joueur, causée par <b>sa</b> ligne.
     * Une valeur unique ferait payer au joueur bien connecté la ligne de son voisin — et, dans
     * l'autre sens, lui ouvrirait la porte que le voisin a ouverte. C'est très exactement la faute
     * que {@code Census} a commise sur les distances, et qui a coûté six bancs à retrouver.
     *
     * <h2>Et pourquoi le lissage se fait au tick et non à l'appel</h2>
     *
     * <p>Première version : le lissage avançait d'un pas à chaque appel de {@link #buffer}. Or cette
     * méthode n'est appelée que lorsqu'un joueur casse quelque chose — deux ou trois fois par bloc.
     * Le pas d'ouverture valant deux centièmes, il fallait plusieurs <b>centaines de blocs</b> pour
     * que la marge s'ouvre. Un lissage dont la vitesse dépend de ce que le joueur fait ne mesure pas
     * le temps : il mesure l'activité. On l'a donc déplacé dans {@link #tick}, qui bat une fois par
     * seconde, quoi que fasse qui que ce soit.
     */
    private static final java.util.Map<java.util.UUID, Double> EXTRA = new java.util.HashMap<>();

    /** Secondes de grâce restantes. */
    private static int grace = GRACE_SECONDS;

    /** Latence retenue au dernier relevé, pour le rapport. */
    private static int lastLatencyMs;

    /** Nombre de places de rattrapage rendues. Pour le rapport, et pour l'épreuve. */
    private static long handovers;

    private Burin() {}

    /** Le module est-il réellement en fonction ? */
    public static boolean on() {
        return Settings.burin() && !BROKEN_ON_PURPOSE;
    }

    /**
     * Remet le module dans l'état d'un serveur qui vient de démarrer.
     *
     * <p>Les épreuves en ont besoin : une manche qui hérite de la marge ouverte par la précédente
     * mesurerait la manche d'avant.
     */
    public static void reset() {
        EXTRA.clear();
        grace = GRACE_SECONDS;
        lastLatencyMs = 0;
        handovers = 0L;
    }

    /**
     * Fait passer une seconde de grâce. À appeler une fois par seconde de serveur.
     *
     * <p>Séparé de {@link #buffer} parce que la grâce est une propriété du <b>serveur</b> et non du
     * joueur : elle ne doit pas s'écouler plus vite parce qu'il y a plus de monde, ni s'arrêter
     * parce que personne ne mine.
     */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        if (grace > 0) {
            grace--;
            if (grace == 0 && on()) {
                Lanterne.LOG.info("[BURIN] Fin du délai de grâce : la marge de portée suivra "
                        + "désormais la latence mesurée, à partir de {} bloc(s) et sans jamais "
                        + "dépasser {}.", VANILLA_BUFFER, VANILLA_BUFFER + CAP_BLOCKS);
            }
            return;
        }
        if (!on() || server == null) {
            EXTRA.clear();
            return;
        }
        // On ne garde que les présents : une table qui grandit à chaque connexion est une fuite,
        // et une marge héritée d'un joueur parti serait accordée au suivant qui reprend son nom.
        EXTRA.keySet().retainAll(server.getPlayerList().getPlayers().stream()
                .map(ServerPlayer::getUUID).collect(java.util.stream.Collectors.toSet()));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            smooth(player);
        }
    }

    /** Fait avancer d'une seconde le lissage de ce joueur, et rend sa marge accordée. */
    private static double smooth(ServerPlayer player) {
        if (player.connection == null) {
            return 0d;
        }
        int latencyMs = player.connection.latency();
        lastLatencyMs = latencyMs;

        // La position que le serveur croit au joueur a l'âge du dernier paquet de mouvement reçu,
        // c'est-à-dire UNE SEULE traversée de la ligne. La latence, elle, en compte deux. C'est
        // donc la demi-latence qui mesure le doute, et prendre la latence entière reviendrait à
        // doubler la porte sans rien pour le justifier.
        double staleSeconds = Math.max(0d, latencyMs - FREE_MS) / 2000d;
        double wanted = Math.min(CAP_BLOCKS, staleSeconds * SPEED_BLOCKS_PER_SECOND);

        double current = EXTRA.getOrDefault(player.getUUID(), 0d);
        double rate = wanted > current ? OPEN_RATE : CLOSE_RATE;
        current += (wanted - current) * rate;
        if (current < 1.0e-3d) {
            current = 0d; // une marge d'un millième de bloc n'est pas une marge : c'est du bruit
        }
        EXTRA.put(player.getUUID(), current);
        return current;
    }

    /**
     * La marge de portée à accorder à ce joueur, en blocs, plancher compris.
     *
     * <p>Appelée par {@code BurinMixin} à la place de la constante {@code 1.0} de vanilla. Elle ne
     * rend jamais moins que {@link #VANILLA_BUFFER}, et jamais plus que
     * {@code VANILLA_BUFFER + }{@link #CAP_BLOCKS}.
     */
    public static double buffer(ServerPlayer player) {
        if (!on() || grace > 0 || player == null || player.connection == null) {
            return VANILLA_BUFFER;
        }
        // Lecture seule : le lissage avance dans tick(), une fois par seconde. Voir la note de
        // EXTRA — le faire avancer ici ferait dépendre la vitesse d'ouverture du nombre de blocs
        // cassés, ce qui n'est pas une mesure du temps.
        return VANILLA_BUFFER + EXTRA.getOrDefault(player.getUUID(), 0d);
    }

    /**
     * La place du rattrapage doit-elle changer de main ?
     *
     * <p>Appelée par {@code BurinMixin} quand une action de cassage arrive sur une case, alors que
     * le serveur retient déjà une <b>autre</b> case en destruction différée. Voir la note de classe :
     * la place appartient à la case sur laquelle le joueur travaille maintenant.
     */
    public static boolean shouldHandOver() {
        if (!on()) {
            return false;
        }
        handovers++;
        return true;
    }

    /** Latence retenue au dernier relevé, en millisecondes. Zéro si rien n'a encore été mesuré. */
    public static int lastLatencyMs() {
        return lastLatencyMs;
    }

    /** Marge accordée à ce joueur au-delà de celle de vanilla, en blocs. Zéro sur une bonne ligne. */
    public static double extraBlocks(ServerPlayer player) {
        return player == null ? 0d : EXTRA.getOrDefault(player.getUUID(), 0d);
    }

    /** Nombre de places de rattrapage rendues depuis le démarrage. */
    public static long handovers() {
        return handovers;
    }

    /** Une ligne pour le rapport. */
    public static String describe() {
        if (!Settings.burin()) {
            return "burin éteint";
        }
        if (BROKEN_ON_PURPOSE) {
            return "burin SABOTÉ (LANTERNE_BREAK_BURIN=1) — marge de vanilla, place unique";
        }
        if (grace > 0) {
            return "burin en grâce (" + grace + " s) — marge de vanilla";
        }
        double widest = EXTRA.values().stream().mapToDouble(Double::doubleValue).max().orElse(0d);
        return String.format(java.util.Locale.ROOT,
                "burin · %d joueur(s) suivi(s) · latence %d ms · marge la plus large %.2f bloc(s) "
                + "dont %.2f accordé(s) · %d place(s) rendue(s)",
                EXTRA.size(), lastLatencyMs, VANILLA_BUFFER + widest, widest, handovers);
    }
}
