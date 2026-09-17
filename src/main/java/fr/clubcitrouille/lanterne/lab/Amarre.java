package fr.clubcitrouille.lanterne.lab;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Elastique;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.TickBudget;

/**
 * L'amarre : ce qui retient le joueur quand le serveur voudrait le renvoyer en arrière.
 *
 * <h2>Ce que cette épreuve doit prouver, et dans quel ordre</h2>
 *
 * <p>Le module {@link Elastique} élargit trois seuils anti-triche du jeu. Deux affirmations le
 * rendent acceptable, et aucune des deux ne se croit sur parole :
 *
 * <ol>
 *   <li><b>Un serveur sain se comporte exactement comme sans le mod.</b> Pas « à peu près » : le
 *       même {@code float}, le même {@code double}, le même bit. Tableau 1.</li>
 *   <li><b>La tolérance ne s'élargit que proportionnellement au retard constaté</b>, et jamais
 *       au-delà de ses plafonds. Tableau 2.</li>
 * </ol>
 *
 * <p>Puis, seulement ensuite, la question qui a motivé le module : <b>est-ce que cela sert à quelque
 * chose ?</b> Tableau 3, et c'est le seul des trois qui puisse refuser de conclure.
 *
 * <h2>Pourquoi les deux premiers tableaux ne touchent pas au monde</h2>
 *
 * <p>La loi du module est une fonction pure de la durée écoulée — c'est pour cela qu'elle a été
 * écrite ainsi, et non par goût. On peut donc la <b>balayer</b> : dix-sept durées choisies de part
 * et d'autre de chaque borne, et la valeur attendue calculée à la main. Aucun aléa, aucune charge,
 * aucun tick — une épreuve qui ne peut pas être « flottante » un jour et « conforme » le lendemain.
 *
 * <p>C'est délibérément l'inverse du troisième tableau, qui lui dépend d'une scène, et qui en porte
 * toute la fragilité.
 *
 * <h2>Le troisième tableau, et les deux pièges qu'il a fallu désamorcer</h2>
 *
 * <p>Il faut un joueur qui bouge. Une doublure ordinaire n'envoie jamais rien : on lui pousse donc de
 * vrais {@code ServerboundMovePlayerPacket} dans sa vraie connexion, au rythme réel de vingt par
 * seconde — ce qu'un client fait quoi qu'il arrive, y compris pendant que le serveur rame.
 *
 * <p><b>Premier piège : le serveur n'écoutait pas.</b> Après l'inscription d'un joueur, le jeu attend
 * un accusé de réception de téléportation ; tant qu'il ne l'a pas, {@code updateAwaitingTeleport()}
 * <em>ignore</em> toute position annoncée et ne retient que la rotation. Une épreuve qui n'accuse pas
 * réception mesure donc zéro retour en arrière — et conclut triomphalement que tout va bien. On
 * accuse donc, comme un vrai client, à partir du numéro relevé sur la ligne.
 *
 * <p><b>Second piège : un client qui triche ne prouve rien.</b> Si la doublure annonçait une position
 * que la géométrie interdit, le serveur la refuserait — à juste titre, par
 * {@code isEntityCollidingWithAnythingNew}, que ce module ne touche pas. Les deux bras compteraient
 * le même nombre de refus, tous légitimes, et le tableau serait du bruit.
 *
 * <p>La doublure avance donc <b>par petits pas</b>, un par tick de client, chacun validé contre les
 * collisions du monde et glissant axe par axe — exactement comme le fait la physique d'un vrai
 * client. Sa position annoncée est donc toujours atteignable, et toujours libre.
 *
 * <h2>Le mécanisme que l'épreuve a fini par établir, et celui qu'on croyait</h2>
 *
 * <p>On supposait au départ que l'élastique venait du <em>contrôle de cohérence</em> : le serveur
 * refaisant d'un seul grand pas le chemin que le client avait parcouru en plusieurs petits, et
 * heurtant ce que les petits contournaient.
 *
 * <p><b>C'est faux</b>, et le code du jeu le dit : {@code lastGood} est remis à jour après chaque
 * paquet accepté, si bien que chaque appel à {@code move()} ne couvre jamais qu'un seul pas de
 * client, retard ou pas. Le relevé a tranché dans le même sens — <b>zéro</b> déclenchement de
 * cohérence sur toutes les exécutions.
 *
 * <p>Ce qui s'accumule pendant un à-coup, c'est l'écart à {@code firstGood}, qui lui reste figé tout
 * le tick pendant que le client continue d'avancer. Passé cinq paquets dans la même fenêtre, le jeu
 * ramène {@code deltaPackets} à un : la tolérance retombe à cent blocs au carré au moment précis où
 * le joueur a le plus bougé. C'est le <b>contrôle de vitesse</b> qui le renvoie en arrière, et c'est
 * ce que l'épreuve mesure.
 *
 * <h2>Ce que l'épreuve refuse de faire</h2>
 *
 * <p>Elle ne publie pas le tableau 3 si la scène n'a pas reproduit le symptôme. Deux conditions :
 * le bras sans protection doit avoir subi au moins {@value #ROLLBACKS_NEEDED} retour(s) en arrière,
 * et la charge doit avoir réellement mis le serveur en retard. Faute de quoi les deux bras ont
 * mesuré le même code sur une scène tranquille, et l'écart annoncé ne serait que du bruit.
 *
 * <p>Elle ne conclut pas non plus si l'on ne lui a pas laissé les deux bras : les compteurs sont
 * remis à zéro entre eux, et un module resté allumé pendant le bras témoin fausserait tout.
 *
 * <h2>L'interrupteur qui doit la faire échouer</h2>
 *
 * <p>{@code LANTERNE_BREAK_ELASTIQUE=1} laisse le module branché, compté et journalisé, mais lui fait
 * rendre un retard de un en toutes circonstances. Le tableau 1 passe alors <b>toujours</b> — c'est
 * normal, un module sans dents est trivialement identique à vanilla — et le <b>tableau 2 doit
 * échouer</b> : la loi ne s'applique plus nulle part.
 *
 * <p>Si le tableau 2 réussit avec cet interrupteur posé, ce n'est pas le module qui est bon : c'est
 * cette épreuve qui ne mesure rien, et c'est elle qu'il faut réparer.
 */
public final class Amarre {
    private enum Step { OFF, SETTLING, LOADING, CALM_SETTLE, CALM, CHARGING, STORM_BARE,
                        STORM_GUARDED, DONE }

    /** Durée nominale d'un tick, en nanosecondes. */
    private static final long NOMINAL_NANOS = 50_000_000L;

    /** Ticks de repos avant de faire entrer la doublure. */
    private static final int SETTLE = 60;

    /** Ticks laissés aux chunks pour arriver autour d'elle. */
    private static final int LOAD = 120;

    /** Ticks de relevé pour la phase au repos. */
    private static final int CALM_TICKS = 100;

    /**
     * Ticks consécutifs sous cinquante millisecondes exigés avant de déclarer le serveur au repos.
     *
     * <h2>Une phase « au repos » qui ne l'était pas</h2>
     *
     * <p>Le quatrième relevé a annoncé <b>NON CONFORME</b> sur la garantie la plus importante du
     * module : quinze pas sur cent trente élargis « alors que le serveur était au repos », avec un
     * pire retard de ×2,76.
     *
     * <p>Le module n'y était pour rien. La phase démarrait immédiatement après la pose de neuf mille
     * colonnes de pierre et pendant que les chunks arrivaient encore : le serveur avait réellement du
     * retard, et le module a réellement fait ce qu'on lui demande. C'était l'épreuve qui mentait sur
     * ses propres conditions.
     *
     * <p>On exige donc d'abord une <b>preuve de calme</b> — une série ininterrompue de ticks tenus —
     * avant de compter quoi que ce soit. Et si le calme se rompt pendant le relevé, l'épreuve refuse
     * de conclure au lieu d'accuser.
     */
    private static final int CALM_STREAK = 40;

    /** Ticks au-delà desquels on renonce à attendre le calme. */
    private static final int CALM_PATIENCE = 1200;

    /** Ticks laissés à la charge pour s'installer avant qu'on relève quoi que ce soit. */
    private static final int CHARGE_SETTLE = 100;

    /** Ticks de relevé pour chacun des deux bras en charge. */
    private static final int STORM_TICKS = 300;

    /** Créatures de la charge. Des villageois : leurs cerveaux sont ce qui coûte le plus cher. */
    private static final int CROWD = 700;

    /**
     * Ticks entre deux gels provoqués, pendant les bras en charge.
     *
     * <h2>Pourquoi l'épreuve fige le serveur elle-même</h2>
     *
     * <p>La première version comptait sur sept cents villageois pour produire le retard. Le relevé a
     * été net et décevant : tick moyen à 26,9 ms, pire retard constaté <b>×1,00</b>, et un refus de
     * conclure parfaitement mérité. Une charge d'entités allonge le tick, mais elle l'allonge
     * <em>régulièrement</em> — et un tick régulièrement à 27 ms ne renvoie personne en arrière.
     *
     * <p>Ce qui renvoie un joueur en arrière, c'est un <b>à-coup</b> : une pause du ramasse-miettes,
     * un chargement de région, un autre mod qui bloque le fil une seconde. Sur la machine visée — un
     * seul cœur — il n'y a aucun autre cœur pour l'absorber, et c'est le cas réel qu'il faut
     * reproduire.
     *
     * <p>L'épreuve endort donc le fil du serveur, franchement, pendant {@value #HICCUP_MILLIS}
     * millisecondes. C'est un modèle grossier d'une pause « stop-the-world », et il est fidèle sur le
     * seul point qui compte ici : le client, lui, a continué d'envoyer ses vingt pas par seconde
     * pendant tout ce temps.
     *
     * <p>La charge de villageois est conservée par-dessus — elle place le serveur dans un état
     * réaliste — mais ce n'est plus d'elle qu'on attend le retard, et l'épreuve ne fait plus semblant
     * du contraire.
     */
    private static final int HICCUP_EVERY = 30;

    /** Durée d'un gel provoqué, en millisecondes. Voir {@link #HICCUP_EVERY}. */
    private static final long HICCUP_MILLIS = 3500L;

    /** Demi-côté de la dalle posée sous la doublure, en blocs. */
    private static final int SLAB = 48;

    /**
     * Retours en arrière qu'il faut avoir subis, sans protection, pour que le tableau veuille dire
     * quelque chose.
     *
     * <p>Un seul suffirait à prouver que le mécanisme existe, mais pas à le distinguer d'un hasard de
     * chargement de chunk. Cinq, sur trois cents ticks, c'est un symptôme.
     */
    private static final int ROLLBACKS_NEEDED = 5;

    /**
     * Vitesse d'un pas de client, en blocs par tick de client.
     *
     * <p>Celle d'un joueur qui sprinte, à peu près. La valeur exacte importe peu ; ce qui importe,
     * c'est qu'elle soit la même dans les deux bras, et elle l'est.
     */
    private static final double STEP = 0.22d;

    /**
     * Demi-côté du terrain où la doublure fait les cent pas, en blocs.
     *
     * <p>Plus petit que la dalle, et il le faut : pendant un à-coup la doublure parcourt d'un trait
     * {@code HICCUP_MILLIS / 50 × STEP} blocs, soit une quinzaine. Si elle faisait demi-tour au bord
     * de la dalle, son écart à {@code firstGood} cesserait de croître au milieu de la salve — et
     * c'est très exactement cet écart que l'épreuve mesure.
     */
    private static final double PADDOCK = 30d;

    /** Pas de client qu'on refuse de rejouer d'un coup, quoi qu'il se soit passé. */
    private static final int MAX_BURST = 80;

    private static Step step = Step.OFF;
    private static int waiting;
    private static MinecraftServer host;
    private static boolean anyFailure;

    /** Centre de la dalle : la doublure fait ses allers-retours autour de ce point. */
    private static double originX;
    private static double originZ;

    /** La position que le client virtuel croit occuper, et sa direction. */
    private static double clientX;
    private static double clientY;
    private static double clientZ;
    private static double headingX = 0.78d;
    private static double headingZ = 0.62d;

    /** Instant du dernier pas rejoué, pour tenir le rythme de vingt par seconde en temps réel. */
    private static long lastStepNanos;

    /** Ce à quoi la doublure a déjà répondu — voir {@link #drive} et {@link #animate}. */
    private static int answeredTeleport = -1;
    private static long answeredKeepAlive;

    /** Pas de client réellement poussés dans la connexion, par bras. */
    private static long bareSteps;
    private static long guardedSteps;

    /** Retours en arrière relevés, par bras, et par mécanisme. */
    private static long bareRollbacks;
    private static long guardedRollbacks;
    private static long bareSpeedRollbacks;
    private static long guardedSpeedRollbacks;
    private static long bareCoherenceRollbacks;
    private static long guardedCoherenceRollbacks;

    /** Pire retard constaté, par bras. */
    private static double bareLateness;
    private static double guardedLateness;

    /** Ticks passés au-dessus de cinquante millisecondes, par bras. */
    private static int bareOverruns;
    private static int guardedOverruns;

    /** Ce que la phase au repos a constaté. */
    private static long calmSteps;
    private static long calmWidened;
    private static double calmLateness;
    private static int calmOverruns;
    private static int calmStreak;
    private static int calmRestarts;
    private static boolean calmGaveUp;

    /**
     * Reprises encore autorisées à la phase au repos avant qu'elle ne renonce.
     *
     * <p>Assez pour absorber les à-coups d'une machine de développement qui fait tourner autre chose
     * à côté ; pas assez pour qu'une épreuve tourne indéfiniment sur un serveur qui ne se calmera
     * jamais.
     */
    private static int calmBudget = 40;

    /** Verdicts des deux tableaux déterministes. */
    private static boolean deadBandOk;
    private static boolean lawOk;

    private static long tickStarted;

    private Amarre() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    /** Chronomètre le tick entier, et fait vivre la doublure. Depuis {@code ServerTickEvent.Pre}. */
    public static void beginTick() {
        if (!running()) {
            return;
        }
        tickStarted = System.nanoTime();
        animate();
    }

    /**
     * Fait ticker la connexion de la doublure, comme le serveur le ferait pour un vrai joueur.
     *
     * <h2>Le défaut de doublure qui a coûté trois relevés</h2>
     *
     * <p>Les trois premiers relevés ont annoncé « pire retard ×1,00 » alors que l'épreuve figeait le
     * serveur trois secondes et demie. La trace a désigné le coupable sans ambiguïté : la ligne 1092
     * de {@code handleMovePlayer},
     *
     * <pre>
     * if (this.tickCount == 0) {
     *     this.resetPosition();
     * }
     * </pre>
     *
     * <p>{@code tickCount} n'augmente que dans {@code tickPlayer()}, qui n'est appelé que par
     * {@code ServerGamePacketListenerImpl.tick()}, lui-même appelé par {@code Connection.tick()}.
     * Or {@link SilentConnection} <b>redéfinit</b> {@code tick()} pour ne faire que vider sa file :
     * l'écouteur d'une doublure n'est donc jamais tické, son {@code tickCount} reste éternellement à
     * zéro, et <b>chaque paquet de mouvement regèle la position de référence</b>.
     *
     * <p>Conséquence : aucune fenêtre ne pouvait jamais dépasser un paquet. Ni le retard, ni l'écart
     * à {@code firstGood} ne pouvaient croître — l'épreuve mesurait une situation qui n'existe chez
     * aucun joueur réel, et elle l'aurait fait en silence.
     *
     * <p>Cela vaut d'être noté au-delà de cette épreuve : <b>une doublure de ce dépôt n'est pas un
     * joueur complet</b>. Tout ce qui passe par le tick de la connexion — mouvement, présence, délai
     * d'inactivité — ne s'exécute pas pour elle. On répare donc ici, localement, plutôt que dans
     * {@link SilentConnection} : une vingtaine d'autres épreuves s'appuient sur des doublures inertes,
     * et leur rendre un tick de connexion changerait leurs mesures sans qu'on l'ait demandé.
     *
     * <p>Ticker la connexion réveille le contrôle de présence, qui déconnecte au bout de trente
     * secondes sans réponse. On y répond donc, comme un vrai client.
     */
    private static void animate() {
        if (step != Step.CALM && step != Step.CHARGING
                && step != Step.STORM_BARE && step != Step.STORM_GUARDED) {
            return;
        }
        ServerPlayer actor = Understudy.actor(0);
        SilentConnection line = Understudy.line(0);
        if (actor == null || line == null || actor.connection == null) {
            return;
        }
        long challenge = line.lastKeepAlive();
        if (challenge != 0L && challenge != answeredKeepAlive) {
            answeredKeepAlive = challenge;
            actor.connection.handleKeepAlive(new ServerboundKeepAlivePacket(challenge));
        }
        actor.connection.tick();
    }

    public static void begin(MinecraftServer server) {
        host = server;
        Settings.setEnabled(true);
        // La marée changerait les distances sous les pieds de la doublure au milieu d'un bras, et
        // l'écart entre les deux bras porterait alors aussi sa signature. Voir Duel et Levee, qui
        // la figent pour la même raison.
        Settings.setTide(false);
        anyFailure = false;
        // Sans cela, le bras temoin ne compterait rien : voir Elastique.observing.
        Elastique.observe(true);

        Lanterne.LOG.info("[AMARRE] Épreuve de l'élastique armée.{}",
                Elastique.toothless()
                        ? " LANTERNE_BREAK_ELASTIQUE=1 est posé : le tableau 2 DOIT échouer."
                        : "");

        // Les deux tableaux déterministes ne demandent ni monde ni charge : on les passe tout de
        // suite, pour que le relevé soit lisible même si la scène tourne mal ensuite.
        deadBandOk = sweepDeadBand();
        lawOk = sweepLaw();

        step = Step.SETTLING;
        waiting = SETTLE;
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        switch (step) {
            case SETTLING -> {
                if (--waiting > 0) {
                    return;
                }
                Understudy.enter(server, server.overworld(), 1, 512);
                step = Step.LOADING;
                waiting = LOAD;
            }
            case LOADING -> {
                if (--waiting > 0) {
                    return;
                }
                if (!placeActor(server)) {
                    finish();
                    return;
                }
                Settings.setElastique(true);
                step = Step.CALM_SETTLE;
                calmStreak = 0;
                waiting = CALM_PATIENCE;
            }
            case CALM_SETTLE -> {
                drive(server);
                boolean held = tickStarted != 0L
                        && System.nanoTime() - tickStarted <= NOMINAL_NANOS;
                calmStreak = held ? calmStreak + 1 : 0;
                if (calmStreak < CALM_STREAK && --waiting > 0) {
                    return;
                }
                if (calmStreak < CALM_STREAK) {
                    Lanterne.LOG.warn("[AMARRE] Le serveur n'a pas tenu {} ticks d'affilée en {} "
                            + "ticks d'attente. La phase au repos va tourner sans repos, et refusera "
                            + "de conclure.", CALM_STREAK, CALM_PATIENCE);
                }
                Elastique.reset();
                calmOverruns = 0;
                calmRestarts = 0;
                calmGaveUp = false;
                step = Step.CALM;
                waiting = CALM_TICKS;
            }
            case CALM -> {
                drive(server);
                if (tickStarted != 0L && System.nanoTime() - tickStarted > NOMINAL_NANOS) {
                    calmOverruns++;
                }
                // <h2>On attend un état, pas un délai</h2>
                //
                // Si une fenêtre entre dans la zone d'élargissement, la phase cesse d'être « au
                // repos » et le relevé cesse de vouloir dire quelque chose. Plutôt que de le publier
                // en s'excusant, on le jette et l'on recommence : ce qu'on cherche, c'est une série
                // de cent mouvements jugés pendant laquelle le module n'a JAMAIS eu le droit d'agir.
                //
                // Sans cela, l'épreuve refusait de conclure une fois sur deux sur une machine de
                // développement — et une épreuve qui refuse au hasard finit par être ignorée.
                if (Elastique.worstLateness() >= 1.5d) {
                    calmRestarts++;
                    Elastique.reset();
                    calmOverruns = 0;
                    waiting = CALM_TICKS;
                    if (--calmBudget <= 0) {
                        calmGaveUp = true;
                    } else {
                        return;
                    }
                }
                if (!calmGaveUp && --waiting > 0) {
                    return;
                }
                calmSteps = Elastique.samples();
                calmWidened = Elastique.widened();
                calmLateness = Elastique.worstLateness();
                buildSlab(server);
                buildCharge(server);
                step = Step.CHARGING;
                waiting = CHARGE_SETTLE;
            }
            case CHARGING -> {
                drive(server);
                if (--waiting > 0) {
                    return;
                }
                // Le bras témoin : le module éteint, tout le reste identique.
                Settings.setElastique(false);
                Elastique.reset();
                bareOverruns = 0;
                step = Step.STORM_BARE;
                waiting = STORM_TICKS;
            }
            case STORM_BARE -> {
                hiccup();
                drive(server);
                if (tickStarted != 0L && System.nanoTime() - tickStarted > NOMINAL_NANOS) {
                    bareOverruns++;
                }
                if (--waiting > 0) {
                    return;
                }
                bareSteps = Elastique.samples();
                bareRollbacks = Elastique.rollbacks();
                bareSpeedRollbacks = Elastique.speedRollbacks();
                bareCoherenceRollbacks = Elastique.coherenceRollbacks();
                bareLateness = Elastique.worstLateness();

                Settings.setElastique(true);
                Elastique.reset();
                guardedOverruns = 0;
                step = Step.STORM_GUARDED;
                waiting = STORM_TICKS;
            }
            case STORM_GUARDED -> {
                hiccup();
                drive(server);
                if (tickStarted != 0L && System.nanoTime() - tickStarted > NOMINAL_NANOS) {
                    guardedOverruns++;
                }
                if (--waiting > 0) {
                    return;
                }
                guardedSteps = Elastique.samples();
                guardedRollbacks = Elastique.rollbacks();
                guardedSpeedRollbacks = Elastique.speedRollbacks();
                guardedCoherenceRollbacks = Elastique.coherenceRollbacks();
                guardedLateness = Elastique.worstLateness();
                verdict();
                finish();
            }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ tableau 1 : la bande morte

    /**
     * Le module rend-il les constantes du jeu <b>au bit près</b> tant que le serveur tient ?
     *
     * <p>On compare les motifs binaires, et non les nombres. {@code 100.0f == 100.0f} serait vrai
     * pour une valeur issue d'un {@code × 1.0} comme pour la constante d'origine ; les bits, eux, ne
     * laissent pas cette ambiguïté, et c'est toute la différence entre « équivalent » et
     * « identique ».
     */
    private static boolean sweepDeadBand() {
        long[] durations = {0L, 1L, 10L, 25L, 40L, 49L, 50L, 60L, 70L, 74L};
        boolean ok = true;
        for (long ms : durations) {
            long nanos = ms * 1_000_000L;
            float walk = Elastique.widenSpeed(100.0F, nanos, true);
            float fly = Elastique.widenSpeed(300.0F, nanos, true);
            double coherence = Elastique.widenResidual(0.0625D, nanos, true);
            boolean line = Float.floatToRawIntBits(walk) == Float.floatToRawIntBits(100.0F)
                    && Float.floatToRawIntBits(fly) == Float.floatToRawIntBits(300.0F)
                    && Double.doubleToRawLongBits(coherence) == Double.doubleToRawLongBits(0.0625D);
            if (!line) {
                ok = false;
                Lanterne.LOG.error(String.format(Locale.ROOT,
                        "[AMARRE] NON CONFORME : à %d ms de retard, les seuils ne sont plus ceux du "
                        + "jeu — %s / %s / %s au lieu de 100.0 / 300.0 / 0.0625.",
                        ms, Float.toString(walk), Float.toString(fly), Double.toString(coherence)));
            }
        }
        // Les compteurs viennent d'être pollués par le balayage : ils appartiennent au monde réel,
        // pas à une épreuve algébrique. Sans cette remise à zéro, la phase au repos hériterait de
        // dix échantillons qu'aucun joueur n'a produits.
        Elastique.reset();
        Lanterne.LOG.info("[AMARRE] ── Tableau 1 · la bande morte ──");
        if (ok) {
            Lanterne.LOG.info("[AMARRE] CONFORME : de 0 à 74 ms de retard, les trois seuils sont "
                    + "rendus au bit près ({} durées éprouvées).", durations.length);
        } else {
            Lanterne.LOG.error("[AMARRE] NON CONFORME : sous la bande morte, au moins un seuil n'est "
                    + "plus celui du jeu. La promesse « vanilla au bit près » est fausse — voir les "
                    + "lignes ci-dessus pour la durée fautive.");
            anyFailure = true;
        }
        return ok;
    }

    // ------------------------------------------------------------------ tableau 2 : la loi

    /**
     * Au-delà de la bande morte, l'élargissement suit-il le carré du retard, et s'arrête-t-il aux
     * plafonds ?
     *
     * <p>La valeur attendue est recalculée ici, à la main, à partir de la règle énoncée — et non
     * reprise du module. Une épreuve qui redemanderait au module ce qu'il pense faire ne vérifierait
     * que sa constance.
     */
    private static boolean sweepLaw() {
        long[] durations = {75L, 100L, 150L, 200L, 400L, 800L, 1600L, 5000L};
        boolean ok = true;
        StringBuilder relevé = new StringBuilder();
        for (long ms : durations) {
            long nanos = ms * 1_000_000L;
            double late = (double) ms / 50d;
            double wantedSpeed = Math.min(late, 16d) * Math.min(late, 16d);
            double wantedResidual = Math.min(late, 4d) * Math.min(late, 4d);

            float walk = Elastique.widenSpeed(100.0F, nanos, true);
            double coherence = Elastique.widenResidual(0.0625D, nanos, true);

            boolean speedOk = close(walk, 100.0D * wantedSpeed);
            boolean residualOk = close(coherence, 0.0625D * wantedResidual);
            if (!speedOk || !residualOk) {
                ok = false;
                Lanterne.LOG.error(String.format(Locale.ROOT,
                        "[AMARRE] NON CONFORME : à %d ms (retard ×%.1f), attendu vitesse %.1f et "
                        + "cohérence %.4f ; obtenu %.1f et %.4f.",
                        ms, late, 100.0D * wantedSpeed, 0.0625D * wantedResidual, walk, coherence));
            }
            relevé.append(String.format(Locale.ROOT, "%dms→×%.0f/×%.0f ",
                    ms, wantedSpeed, wantedResidual));
        }
        Elastique.reset();
        Lanterne.LOG.info("[AMARRE] ── Tableau 2 · la loi, et ses deux plafonds ──");
        Lanterne.LOG.info("[AMARRE] Facteurs attendus (vitesse / cohérence) : {}", relevé.toString().trim());
        if (ok) {
            Lanterne.LOG.info("[AMARRE] CONFORME : l'élargissement suit le carré du retard et "
                    + "s'arrête à ×256 (vitesse) et ×16 (cohérence).");
        } else {
            Lanterne.LOG.error("[AMARRE] NON CONFORME : l'élargissement ne suit PAS le carré du "
                    + "retard, ou ne s'arrête pas à ses plafonds. Voir les lignes ci-dessus pour les "
                    + "durées fautives.");
        }
        if (!ok) {
            anyFailure = true;
            if (Elastique.toothless()) {
                Lanterne.LOG.info("[AMARRE] (LANTERNE_BREAK_ELASTIQUE=1 était posé : ce tableau "
                        + "DEVAIT annoncer NON CONFORME.)");
            }
        } else if (Elastique.toothless()) {
            Lanterne.LOG.error("[AMARRE] LANTERNE_BREAK_ELASTIQUE=1 était posé et le tableau 2 a "
                    + "réussi quand même. C'est l'ÉPREUVE qu'il faut réparer, pas le module : elle "
                    + "ne mesure pas la loi qu'elle prétend mesurer.");
            anyFailure = true;
        }
        return ok;
    }

    private static boolean close(double got, double wanted) {
        return Math.abs(got - wanted) <= Math.abs(wanted) * 1e-6d + 1e-9d;
    }

    // ------------------------------------------------------------------ la scène

    /**
     * Pose le terrain et le client virtuel. Rend faux si la scène n'est pas utilisable.
     *
     * <h2>Une dalle nue, après une forêt de piliers</h2>
     *
     * <p>La première version semait des piliers, pour que le chemin ait des <b>coins</b> : l'idée
     * était d'éprouver le contrôle de cohérence, qui naît d'un grand pas heurtant ce que plusieurs
     * petits contournaient.
     *
     * <p>La lecture du code du jeu a montré que ce raisonnement était faux, et le relevé l'a
     * confirmé. Le serveur ne fait <b>pas</b> un grand pas : {@code lastGood} est remis à jour après
     * <em>chaque</em> paquet accepté, si bien que chaque appel à {@code move()} ne couvre qu'un seul
     * pas de client, quel que soit le retard. Ce qui s'accumule pendant un à-coup, c'est l'écart à
     * {@code firstGood}, qui lui reste figé tout le tick — et c'est donc le <b>contrôle de vitesse</b>
     * qui se déclenche, pas celui de cohérence.
     *
     * <p>Les piliers n'éprouvaient donc pas ce qu'on croyait, et ils nuisaient à ce qu'il fallait
     * éprouver : la doublure rebondissait dessus, restait sur place, et son écart à {@code firstGood}
     * ne montait jamais. Une dalle nue la laisse s'éloigner en ligne droite, ce qui est exactement la
     * situation d'un joueur qui court pendant que le serveur se fige.
     *
     * <p>C'est la seconde fois dans cette épreuve qu'une hypothèse plausible sur le code du jeu a dû
     * céder devant sa lecture. Elles sont conservées ici parce qu'elles sont instructives : le
     * mécanisme du symptôme n'est pas celui qu'on suppose spontanément.
     */
    private static boolean placeActor(MinecraftServer server) {
        ServerPlayer actor = Understudy.actor(0);
        if (actor == null) {
            Lanterne.LOG.error("[AMARRE] ÉPREUVE INVALIDE : aucune doublure n'est entrée en scène. "
                    + "Rien à faire bouger, donc rien à mesurer.");
            anyFailure = true;
            return false;
        }
        originX = actor.getX();
        originZ = actor.getZ();
        clientX = actor.getX();
        clientY = actor.getY();
        clientZ = actor.getZ();
        lastStepNanos = System.nanoTime();
        Lanterne.LOG.info("[AMARRE] Doublure en ({}, {}). La phase au repos se déroule sur le "
                + "terrain d'origine : bâtir la dalle AVANT elle rendrait le serveur occupé pendant "
                + "la seule phase où l'on prétend qu'il ne l'est pas.",
                (int) originX, (int) originZ);
        return true;
    }

    /**
     * Pose la dalle sur laquelle la doublure courra en ligne droite.
     *
     * <p>Une dalle nue, et non la forêt de piliers de la première version : voir
     * {@link #advanceOneClientTick}. Elle n'est bâtie qu'<b>après</b> la phase au repos, parce que
     * poser dix mille blocs occupe le serveur — et que le quatrième relevé de cette épreuve a accusé
     * le module d'un retard que l'épreuve avait elle-même fabriqué.
     */
    private static void buildSlab(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer actor = Understudy.actor(0);
        if (actor == null) {
            return;
        }
        int ground = Scene.groundLevel(level);
        int laid = 0;
        for (int dx = -SLAB; dx <= SLAB; dx++) {
            for (int dz = -SLAB; dz <= SLAB; dz++) {
                BlockPos floor = BlockPos.containing(originX + dx, ground, originZ + dz);
                // Sans mise à jour des voisins : on pose des dizaines de milliers de blocs, et
                // déclencher une cascade de propagation à chacun coûterait bien plus que la pose.
                level.setBlock(floor, Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 2);
                laid++;
            }
        }
        clientX = originX;
        clientY = ground + 1d;
        clientZ = originZ;
        actor.snapTo(clientX, clientY, clientZ, 0f, 0f);
        answeredTeleport = -1;
        Lanterne.LOG.info("[AMARRE] Dalle de {} colonnes posée, sol à {}.", laid, ground);
    }

    /**
     * Fige le fil du serveur, comme le ferait une pause du ramasse-miettes.
     *
     * <p>C'est le seul endroit de ce dépôt où l'on ralentit le serveur <b>exprès</b>, et cela mérite
     * d'être dit : voir {@link #HICCUP_EVERY} pour la raison, qui est qu'une charge régulière ne
     * reproduit pas un à-coup, et que c'est l'à-coup qui renvoie le joueur en arrière.
     */
    private static void hiccup() {
        if (waiting % HICCUP_EVERY != 0) {
            return;
        }
        try {
            Thread.sleep(HICCUP_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void buildCharge(MinecraftServer server) {
        int born = Scene.build(server.overworld(), Scene.Kind.VILLAGERS, CROWD, 64);
        Lanterne.LOG.info("[AMARRE] Charge posée : {} villageois. Leur cerveau est ce qui coûte le "
                + "plus cher dans un tick, et c'est le retard qu'on cherche à produire.", born);
    }

    // ------------------------------------------------------------------ le client virtuel

    /**
     * Rejoue ce qu'un vrai client aurait envoyé depuis le dernier passage.
     *
     * <p>Le compte de pas est tiré du <b>temps réel écoulé</b>, jamais du nombre de ticks serveur :
     * c'est toute la question. Un client ne ralentit pas parce que le serveur ralentit, et si cette
     * épreuve le faisait, elle supprimerait d'elle-même le phénomène qu'elle vient mesurer.
     */
    private static void drive(MinecraftServer server) {
        ServerPlayer actor = Understudy.actor(0);
        SilentConnection line = Understudy.line(0);
        if (actor == null || line == null || actor.connection == null) {
            return;
        }

        // Accuser réception, comme un vrai client. Sans cela le serveur ignore toute position
        // annoncée par ce joueur, et l'épreuve mesurerait zéro sans rien signaler.
        //
        // UNE SEULE FOIS PAR NUMÉRO, et c'est impératif : accuser deux fois le même fait tomber le
        // jeu dans « if (awaitingPositionFromClient == null) disconnect(invalid_player_movement) ».
        // La doublure serait expulsée au milieu du relevé, pour un motif qui n'a rien à voir.
        int teleport = line.lastTeleportId();
        if (teleport >= 0 && teleport != answeredTeleport) {
            answeredTeleport = teleport;
            net.minecraft.world.entity.PositionMoveRotation change = line.lastTeleportChange();
            actor.connection.handleAcceptTeleportPacket(
                    new ServerboundAcceptTeleportationPacket(teleport,
                            change.position().x, change.position().y, change.position().z,
                            change.yRot(), change.xRot()));
        }
        actor.connection.markClientLoaded();

        long now = System.nanoTime();
        long due = (now - lastStepNanos) / NOMINAL_NANOS;
        if (due <= 0L) {
            return;
        }
        int steps = (int) Math.min(due, MAX_BURST);
        lastStepNanos += (long) steps * NOMINAL_NANOS;

        ServerLevel level = actor.level();
        // <h2>Choisir la direction AVANT la salve, et non pendant</h2>
        //
        // Un relevé a rendu dix retours en arrière, le suivant zéro, sur le même code. La cause
        // n'était pas le module : quand un à-coup tombait alors que la doublure longeait le bord de
        // son terrain, elle faisait demi-tour au milieu de la salve. Son écart à « firstGood »
        // cessait alors de croître — et c'est cet écart, et lui seul, que le contrôle de vitesse
        // examine. L'épreuve mesurait donc la position du hasard, pas le comportement du module.
        //
        // On regarde donc d'abord si la salve tient dans le terrain, et l'on se retourne avant de
        // partir. Chaque salve est alors une ligne droite de longueur connue, dans les deux bras.
        double reach = steps * STEP;
        if (Math.abs(clientX + headingX * reach - originX) > PADDOCK) {
            headingX = -headingX;
        }
        if (Math.abs(clientZ + headingZ * reach - originZ) > PADDOCK) {
            headingZ = -headingZ;
        }
        for (int i = 0; i < steps; i++) {
            advanceOneClientTick(level, actor);
            actor.connection.handleMovePlayer(
                    new ServerboundMovePlayerPacket.Pos(clientX, clientY, clientZ, true, false));
        }

        // Un vrai client accepte la correction du serveur au lieu de s'entêter. Sans cela, le client
        // virtuel divergerait sans fin après le premier retour en arrière et déclencherait ensuite
        // des refus parfaitement légitimes — qui n'ont rien à voir avec ce qu'on mesure.
        clientX = actor.getX();
        clientY = actor.getY();
        clientZ = actor.getZ();
    }

    /**
     * Un pas de client : glissement axe par axe, chaque axe validé contre les collisions du monde.
     *
     * <p>C'est ce que fait la physique d'un vrai client, et c'est ce qui rend l'épreuve honnête : la
     * position annoncée est toujours atteignable par petits pas et toujours libre. Si le serveur la
     * refuse malgré tout, ce n'est pas parce que le client a menti — c'est parce que le serveur n'a
     * pas su refaire le chemin d'un seul grand pas.
     */
    private static void advanceOneClientTick(ServerLevel level, ServerPlayer actor) {
        if (Math.abs(clientX - originX) > PADDOCK || Math.abs(clientZ - originZ) > PADDOCK) {
            headingX = -headingX;
            headingZ = -headingZ;
        }
        double tryX = clientX + headingX * STEP;
        if (free(level, actor, tryX, clientY, clientZ)) {
            clientX = tryX;
        } else {
            headingX = -headingX;
        }
        double tryZ = clientZ + headingZ * STEP;
        if (free(level, actor, clientX, clientY, tryZ)) {
            clientZ = tryZ;
        } else {
            headingZ = -headingZ;
        }
    }

    private static boolean free(ServerLevel level, ServerPlayer actor, double x, double y, double z) {
        AABB box = actor.getBoundingBox()
                .move(x - actor.getX(), y - actor.getY(), z - actor.getZ());
        return level.noCollision(actor, box);
    }

    // ------------------------------------------------------------------ le verdict

    private static void verdict() {
        Lanterne.LOG.info("[AMARRE] ── Tableau 3 · le monde réel ──");
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[AMARRE] Au repos (module armé)  : %d pas · %d élargissement(s) · pire retard ×%.2f "
                        + "· %d tick(s) > 50 ms · %d reprise(s)",
                calmSteps, calmWidened, calmLateness, calmOverruns, calmRestarts));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[AMARRE] En charge, SANS le module : %d pas · %d retour(s) en arrière "
                        + "(%d vitesse, %d cohérence) · pire retard ×%.2f · %d tick(s) > 50 ms",
                bareSteps, bareRollbacks, bareSpeedRollbacks, bareCoherenceRollbacks,
                bareLateness, bareOverruns));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[AMARRE] En charge, AVEC le module : %d pas · %d retour(s) en arrière "
                        + "(%d vitesse, %d cohérence) · pire retard ×%.2f · %d tick(s) > 50 ms",
                guardedSteps, guardedRollbacks, guardedSpeedRollbacks, guardedCoherenceRollbacks,
                guardedLateness, guardedOverruns));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[AMARRE] Tick moyen en fin d'épreuve : %.1f ms · pire %.1f ms",
                TickBudget.averageMillis(), TickBudget.worstMillis()));

        Lanterne.LOG.info("[AMARRE] ── Verdict ──");

        // Le repos d'abord : c'est la garantie, et elle prime sur l'utilité.
        if (calmSteps == 0L) {
            Lanterne.LOG.error("[AMARRE] ÉPREUVE INVALIDE : aucun pas n'a été jugé au repos. Le "
                    + "serveur n'a pas écouté la doublure — accusé de réception manquant, ou joueur "
                    + "non chargé. Rien de ce qui suit ne veut dire quoi que ce soit.");
            anyFailure = true;
            return;
        }
        // Le seuil est le retard, et non le simple dépassement des 50 ms. Un tick à 60 ms est un
        // tick manqué, mais il reste DANS la bande morte : le module n'y a aucune latitude, et
        // constater qu'il n'a rien fait garde donc tout son sens. Ce qui ôterait son sens au tableau,
        // c'est une fenêtre réellement entrée dans la zone d'élargissement — là, élargir serait le
        // comportement demandé, et le relevé ne prouverait plus rien.
        if (calmGaveUp || calmLateness >= 1.5d) {
            Lanterne.LOG.error(String.format(Locale.ROOT,
                    "[AMARRE] REFUS DE CONCLURE sur le repos : après %d reprise(s), la machine n'a "
                    + "jamais tenu %d mouvements d'affilée sans qu'une fenêtre n'atteigne la zone "
                    + "d'élargissement (pire ×%.2f). Le module y a le DROIT d'agir : ce n'est pas "
                    + "lui qu'il faudrait accuser, c'est ce relevé qu'il faut jeter. La garantie "
                    + "reste éprouvée par le tableau 1, qui ne dépend d'aucune scène.",
                    calmRestarts, CALM_TICKS, calmLateness));
        } else if (calmWidened > 0L) {
            Lanterne.LOG.error(String.format(Locale.ROOT,
                    "[AMARRE] NON CONFORME : %d pas sur %d ont été élargis alors que le serveur "
                    + "tenait TOUS ses ticks (pire retard ×%.2f). La promesse « un serveur sain se "
                    + "comporte comme sans le mod » est fausse.",
                    calmWidened, calmSteps, calmLateness));
            anyFailure = true;
        } else {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[AMARRE] CONFORME : au repos, %d pas réellement jugés par le code du jeu, pire "
                    + "fenêtre ×%.2f (bande morte), zéro élargissement, %d reprise(s). Ce tableau "
                    + "éprouve le BRANCHEMENT — que le module voit bien passer les paquets et n'y "
                    + "touche pas ; c'est le tableau 1 qui éprouve la loi.",
                    calmSteps, calmLateness, calmRestarts));
        }

        // Puis l'utilité, qui a le droit de ne rien pouvoir dire.
        if (bareLateness < 1.5d) {
            Lanterne.LOG.error(String.format(Locale.ROOT,
                    "[AMARRE] REFUS DE CONCLURE : la charge n'a jamais mis le serveur en retard "
                    + "(pire ×%.2f, il en faut 1,5). Les deux bras ont donc tourné dans la bande "
                    + "morte, c'est-à-dire sur le MÊME code. Ne rien publier de ce tableau — "
                    + "augmenter la charge et recommencer.", bareLateness));
            if (Elastique.toothless()) {
                Lanterne.LOG.info("[AMARRE] (LANTERNE_BREAK_ELASTIQUE=1 était posé : le module rend "
                        + "un retard de un en toutes circonstances, donc l'épreuve ne PEUT pas "
                        + "attester de la scène. C'est attendu, et le tableau 2 a déjà échoué.)");
            }
            return;
        }
        if (bareRollbacks < ROLLBACKS_NEEDED) {
            Lanterne.LOG.error(String.format(Locale.ROOT,
                    "[AMARRE] REFUS DE CONCLURE : seulement %d retour(s) en arrière dans le bras sans "
                    + "protection, il en faut %d. La scène n'a pas reproduit le symptôme : l'écart "
                    + "ci-dessus est du bruit, et un zéro dans le bras protégé ne prouverait rien.",
                    bareRollbacks, ROLLBACKS_NEEDED));
            return;
        }
        if (guardedRollbacks >= bareRollbacks) {
            Lanterne.LOG.error(String.format(Locale.ROOT,
                    "[AMARRE] NON CONFORME : %d retour(s) en arrière avec le module contre %d sans. "
                    + "Le module ne tient pas sa seule promesse utile.",
                    guardedRollbacks, bareRollbacks));
            anyFailure = true;
            return;
        }
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[AMARRE] CONFORME : %d retour(s) en arrière sans le module, %d avec — soit %.0f %% "
                + "de moins. Même charge, même cadence de paquets, mêmes gels provoqués.",
                bareRollbacks, guardedRollbacks,
                100d * (bareRollbacks - guardedRollbacks) / bareRollbacks));
        // Le nombre de pas JUGÉS diffère entre les deux bras, et ce n'est pas un défaut : après un
        // retour en arrière, le jeu attend un accusé de réception et ignore le reste de la salve.
        // Moins de retours en arrière, c'est donc mécaniquement plus de mouvements traités — et
        // c'est la seconde façon de voir le même résultat.
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[AMARRE] Mouvements effectivement traités : %d sans le module, %d avec (+%.0f %%). "
                + "Un retour en arrière fait jeter la fin de la salve.",
                bareSteps, guardedSteps,
                bareSteps == 0L ? 0d : 100d * (guardedSteps - bareSteps) / bareSteps));
    }

    private static void finish() {
        if (anyFailure) {
            Lanterne.LOG.error("[AMARRE] Au moins un tableau a échoué. Rappel : "
                    + "LANTERNE_BREAK_ELASTIQUE=1 doit faire échouer le tableau 2 — si "
                    + "l'interrupteur n'était PAS posé, c'est le module qu'il faut réparer.");
        } else {
            Lanterne.LOG.info("[AMARRE] Tous les tableaux conclus sont conformes. Rappel : "
                    + "LANTERNE_BREAK_ELASTIQUE=1 retire les dents du module — le tableau 2 DOIT "
                    + "alors échouer, sans quoi c'est cette épreuve qu'il faut réparer.");
        }
        Elastique.observe(false);
        Settings.setElastique(false);
        step = Step.DONE;
        if (host != null) {
            host.halt(false);
        }
    }
}
