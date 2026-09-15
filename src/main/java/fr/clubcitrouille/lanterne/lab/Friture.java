package fr.clubcitrouille.lanterne.lab;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Burin;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'épreuve de la friture : casser du bois au bout d'une mauvaise ligne.
 *
 * <h2>Fabriquer une mauvaise connexion sans client, et pourquoi c'est possible</h2>
 *
 * <p>Il n'y a pas de client dans ce banc, et il ne peut pas y en avoir. La question est donc :
 * <b>peut-on encore fabriquer du mauvais réseau honnêtement ?</b> La réponse tient à une propriété
 * du jeu qu'on oublie souvent : <b>Minecraft parle en TCP</b>. Aucun paquet ne se perd, aucun
 * n'arrive dans le désordre. Tout ce qu'une mauvaise ligne peut faire à ce protocole, c'est
 * <b>déplacer l'instant d'arrivée</b> des paquets et <b>vieillir</b> l'image que chaque bout a de
 * l'autre.
 *
 * <p>Deux conséquences observables côté serveur, et deux seulement :
 *
 * <ol>
 *   <li>l'<b>écart en ticks serveur</b> entre l'arrivée du début et celle de la fin du cassage
 *       n'est plus celui que le client a compté — la gigue l'a comprimé ou étiré ;</li>
 *   <li>la <b>position</b> que le serveur croit au joueur a l'âge d'une traversée de ligne : le
 *       joueur qui marche est, pour lui, en arrière de là où il se voit.</li>
 * </ol>
 *
 * <p>Le banc reproduit ces deux choses-là, et rien d'autre — parce qu'il n'y a rien d'autre.
 * <b>Ce n'est donc pas une simulation approximative du réseau : c'est le modèle complet de ce qu'un
 * réseau peut faire à ce chemin de code.</b> Ce qui reste hors de portée est nommé plus bas.
 *
 * <h2>Ce que fait le banc, geste par geste</h2>
 *
 * <p>Une doublure ({@link Understudy}) est un <b>vrai</b> {@code ServerPlayer}, avec son
 * {@code ServerGamePacketListenerImpl} et son {@code ServerPlayerGameMode}. On lui fait jouer le
 * client à la main : le client accumule {@code getDestroyProgress} une fois par tick et conclut à
 * un — c'est arithmétique, et c'est ce que fait {@code MultiPlayerGameMode.continueDestroyBlock}
 * mot pour mot. On pousse alors dans le vrai {@code handleBlockBreakAction} les deux mêmes actions
 * qu'un client enverrait, aux instants que la ligne dicte.
 *
 * <h2>Les trois manches</h2>
 *
 * <ol>
 *   <li><b>La ligne propre.</b> Le témoin. Elle doit réussir dans tous les cas, avec le module comme
 *       sans lui. Si elle échoue, c'est le banc qu'il faut réparer.</li>
 *   <li><b>Le joueur qui marche.</b> La doublure est reculée de ce qu'une demi-latence de
 *       {@value #LATENCY_MS} ms vaut en blocs pour un joueur qui sprinte, au moment où le serveur
 *       juge — c'est-à-dire exactement ce qu'il croirait d'un joueur dont le dernier paquet de
 *       mouvement a cet âge. Sans le module, le contrôle de portée jette l'action en silence et le
 *       bloc revient.</li>
 *   <li><b>L'enchaînement.</b> Deux bûches, l'une après l'autre. La première est refusée — on lui
 *       coupe sa fin trop tôt, ce que la gigue fait tous les jours — et elle prend la place unique
 *       du rattrapage. Sans le module, la <b>seconde</b>, pourtant cassée dans les règles, n'a plus
 *       de filet : c'est « ça veut pas <em>tout</em> casser », et c'est la manche qui compte le
 *       plus.</li>
 * </ol>
 *
 * <h2>Ce que l'épreuve refuse de mesurer</h2>
 *
 * <p>Elle ne juge <b>pas</b> l'affichage du client : il n'y en a pas ici, et prétendre le contraire
 * serait mentir. Elle juge la seule chose qui compte et que le serveur décide seul : <b>la case
 * est-elle partie au geste du joueur ?</b>
 *
 * <p>Elle ne mesure pas non plus l'effet de la compression du fil, du chiffrement, ni de la taille
 * des paquets : ces trois-là changent le <em>moment</em> des arrivées, et ce moment, le banc le
 * pose lui-même. Les mesurer reviendrait à mesurer deux fois la même chose.
 *
 * <h2>L'emploi</h2>
 *
 * <pre>
 * LANTERNE_FRITURE=1 LANTERNE_MODULES=burin ./gradlew runServer -Pbanc=friture
 * LANTERNE_FRITURE=1 LANTERNE_BREAK_BURIN=1 ./gradlew runServer -Pbanc=friture   # DOIT échouer
 * </pre>
 */
public final class Friture {

    /**
     * Aller-retour imposé à la ligne, en millisecondes.
     *
     * <p>Quatre cents : c'est une mauvaise connexion réelle, pas une caricature. La demi-latence
     * vaut alors 200 ms, soit {@code 0,2 × 5,6 = 1,12} bloc de doute sur la position — <b>juste
     * au-delà</b> du bloc de marge que vanilla accorde. Choisir moins rendrait la manche réussie
     * sans le module ; choisir beaucoup plus la rendrait réussie par un plafond et non par une
     * mesure. Le banc doit se tenir sur le fil, sinon il n'éprouve pas le fil.
     */
    private static final int LATENCY_MS = 400;

    /** Vitesse de sprint, en blocs par seconde. La même que celle de {@link Burin}. */
    private static final double SPEED = 5.6d;

    /** Ticks laissés au monde et à la doublure pour se poser. */
    private static final int SETTLE = 40;

    /** Ticks au-delà desquels on renonce à attendre une condition qui n'arrive pas. */
    private static final int PATIENCE = 1200;

    /** Le seuil du serveur, recopié de {@code ServerPlayerGameMode}. */
    private static final float SERVER_THRESHOLD = 0.7F;

    /**
     * Secondes de mauvaise ligne qu'on fait vivre au module avant de le mesurer.
     *
     * <p>Trente pour épuiser la grâce, puis trois cents pour que le lissage à deux centièmes par
     * seconde ait convergé. Ce n'est pas de l'impatience déguisée : c'est la durée réelle qu'il faut
     * à une mauvaise ligne pour ouvrir la porte, et l'annoncer est plus honnête que de la forcer.
     */
    private static final int GRACE_THEN_SETTLE_SECONDS = 330;

    private static final String[] ROUND_NAMES = {
        "ligne propre",
        "joueur en retard d'une demi-latence",
        "enchaînement après un refus",
    };

    private enum Step { OFF, BUILDING, SETTLING, MINING, DONE }

    private static Step step = Step.OFF;
    private static int waiting;
    private static int patience;
    private static MinecraftServer host;

    private static int round;
    private static int sequence;

    /** La case de la manche, et la seconde case pour la manche d'enchaînement. */
    private static BlockPos log;
    private static BlockPos second;

    /** Part de progression par tick, relevée au départ. */
    private static float part;
    /** Ticks que le client compte avant de conclure. */
    private static int clientTicks;
    /** Ticks écoulés depuis que l'action de début a été poussée. */
    private static int held;
    /** Position d'origine de la doublure, pour la remettre en place après la manche. */
    private static Vec3 home;

    /** Vrai pendant la seconde moitié de la manche d'enchaînement. Voir {@link #judgeChain}. */
    private static boolean chaining;

    private static final StringBuilder VERDICTS = new StringBuilder();
    private static boolean anyFailure;

    private Friture() {}

    /**
     * La latence que la doublure doit annoncer au serveur, en millisecondes.
     *
     * <p>Lue par {@code report.SelfTest} avant de faire entrer la doublure : le chiffre voyage par
     * le jeton de connexion, et le jeton se pose à l'entrée. Voir {@link Understudy#announceLatency}.
     */
    public static int latencyMs() {
        return LATENCY_MS;
    }

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        Settings.setEnabled(true);
        // Les distances doivent rester fixes pendant la mesure : même raison que partout ailleurs.
        Settings.setTide(false);
        // <h2>Faire vivre au module les minutes de mauvaise ligne qu'il attend</h2>
        //
        // La marge s'ouvre lentement — deux centièmes par seconde, exprès : c'est une surface
        // d'attaque, et on ne l'accorde pas sur un sursaut. Un banc qui la lirait après trois
        // secondes mesurerait donc zéro, et conclurait que le module ne fait rien.
        //
        // On ne la force pas pour autant : on appelle son tick, celui-là même que le serveur appelle
        // une fois par seconde, autant de fois qu'il faudrait de secondes. Le module voit donc
        // exactement ce qu'il verrait d'une ligne mauvaise et STABLE — ce qu'est une mauvaise
        // connexion. Rien n'est écrit dans son état par le banc.
        Burin.reset();
        for (int second = 0; second < GRACE_THEN_SETTLE_SECONDS; second++) {
            Burin.tick(server);
        }
        round = 0;
        sequence = 0;
        anyFailure = false;
        VERDICTS.setLength(0);
        step = Step.BUILDING;
        waiting = 0;
        Lanterne.LOG.info("[FRITURE] Épreuve armée — {} manche(s) au bout d'une ligne à {} ms. "
                + "Burin : {}.", ROUND_NAMES.length, LATENCY_MS, Burin.describe());
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        switch (step) {
            case BUILDING -> build(server);
            case SETTLING -> {
                waiting--;
                patience--;
                if (waiting > 0) {
                    return;
                }
                if (settled(server) || patience <= 0) {
                    strike(server);
                }
            }
            case MINING -> mine(server);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ la mise en place

    private static void build(MinecraftServer server) {
        if (round >= ROUND_NAMES.length) {
            finish();
            return;
        }
        ServerLevel level = server.overworld();
        ServerPlayer actor = Understudy.actor(0);
        if (actor == null) {
            note("ÉPREUVE INVALIDE : aucune doublure en scène. Sans joueur, personne ne peut "
                    + "envoyer d'action de cassage — le serveur n'en accepte que d'un joueur.");
            anyFailure = true;
            round++;
            return;
        }

        // Une case différente à chaque manche : quand le serveur refuse, il RETIENT la case dans
        // une destruction différée qui porte sa position. Rejouer au même endroit ferait tomber la
        // nouvelle bûche pour le compte de l'ancienne, et l'épreuve applaudirait un refus. C'est la
        // leçon que lab.Cognee a déjà payée.
        home = actor.position();
        BlockPos feet = actor.blockPosition();
        // <h2>Où poser la bûche, et pourquoi ce n'est pas « devant »</h2>
        //
        // La première version la posait à deux blocs. Le contrôle de portée n'avait alors rien à
        // dire : 4,5 blocs de bras plus un de marge, contre deux blocs de distance — trois blocs et
        // demi de mou. Reculer le joueur d'un bloc ne changeait donc rien, et la manche s'est
        // déclarée INVALIDE, très correctement. Une marge ne s'éprouve qu'au BORD de la portée.
        //
        // On cherche donc le plus grand écart qui reste dans la portée de vanilla. Le chercher
        // plutôt que l'écrire en dur n'est pas de la coquetterie : la portée est un ATTRIBUT, qu'un
        // mod ou un effet peut déplacer, et un banc qui suppose 4,5 mesurerait autre chose le jour
        // où elle changerait.
        //
        // Et l'écart se cherche À LA PROFONDEUR OÙ LA CASE SERA POSÉE. La première version cherchait
        // en z=0 et posait en z=3 : la case se retrouvait à sqrt(5²+3²) = 5,83 blocs, donc HORS de
        // la portée de vanilla dès le départ. La mise en place attendait alors sa patience entière —
        // soixante secondes — et la manche mesurait une case injoignable en croyant mesurer une
        // marge. Une géométrie fausse ne fait pas échouer un banc : elle le fait mentir.
        int dz = round;
        int reach = 2;
        for (int candidate = 2; candidate <= 8; candidate++) {
            if (actor.isWithinBlockInteractionRange(feet.offset(candidate, 0, dz), Burin.VANILLA_BUFFER)) {
                reach = candidate;
            }
        }
        log = feet.offset(round == 1 ? reach : 2, 0, dz);
        second = feet.offset(-2, 0, dz);
        level.setBlock(log, Blocks.OAK_LOG.defaultBlockState(), 3);
        level.setBlock(second, Blocks.OAK_LOG.defaultBlockState(), 3);

        step = Step.SETTLING;
        waiting = SETTLE;
        patience = PATIENCE;
        Lanterne.LOG.info("[FRITURE] Manche {} ({}) · bûche(s) posée(s) en {} et {}, doublure en {}.",
                round + 1, ROUND_NAMES[round], log, second, feet);
    }

    private static boolean settled(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer actor = Understudy.actor(0);
        return actor != null
                && actor.isAlive()
                && level.getBlockState(log).is(Blocks.OAK_LOG)
                && actor.isWithinBlockInteractionRange(log, Burin.VANILLA_BUFFER);
    }

    // ------------------------------------------------------------------ le coup

    private static void strike(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer actor = Understudy.actor(0);
        if (actor == null || !level.getBlockState(log).is(Blocks.OAK_LOG)) {
            note("ÉPREUVE INVALIDE : la bûche ou la doublure a disparu avant le coup.");
            anyFailure = true;
            nextRound();
            return;
        }

        BlockState state = level.getBlockState(log);
        part = state.getDestroyProgress(actor, level, log);
        if (part <= 0f || part >= 1f) {
            note(String.format(Locale.ROOT,
                    "ÉPREUVE INVALIDE : la bûche vaut %.5f de progression par tick. En dessous de "
                    + "zéro elle est incassable, au-dessus de un elle tombe au premier coup — dans "
                    + "les deux cas il n'y a pas de ligne à éprouver.", part));
            anyFailure = true;
            nextRound();
            return;
        }

        // Ce que le CLIENT compte, mot pour mot : il additionne sa part à chaque tick et conclut à
        // un. Rien d'autre ne fixe la durée du geste.
        clientTicks = (int) Math.ceil(1d / part);

        push(actor, log, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
        held = 0;
        step = Step.MINING;
        Lanterne.LOG.info("[FRITURE] Manche {} ({}) · début poussé. Part par tick : {} (pieds au "
                + "sol : {}). Le client conclura au bout de {} ticks.",
                round + 1, ROUND_NAMES[round], String.format(Locale.ROOT, "%.5f", part),
                actor.onGround() ? "oui" : "non", clientTicks);
    }

    private static void push(ServerPlayer actor, BlockPos pos,
                             ServerboundPlayerActionPacket.Action action) {
        actor.gameMode.handleBlockBreakAction(pos, action, Direction.UP,
                actor.level().getMaxY(), ++sequence);
    }

    // ------------------------------------------------------------------ le relevé

    private static void mine(MinecraftServer server) {
        held++;
        if (held < clientTicks) {
            return;
        }
        if (chaining) {
            chaining = false;
            judgeChainSecond(server.overworld(), Understudy.actor(0));
            nextRound();
            return;
        }
        judge(server);
        // La manche d'enchaînement n'a pas fini : elle vient de lancer sa SECONDE bûche, et celle-ci
        // doit être tenue pendant de VRAIS ticks de serveur. Voir judgeChain.
        if (chaining) {
            return;
        }
        nextRound();
    }

    /**
     * Le verdict d'une manche.
     *
     * <p>Chaque manche fabrique son défaut de ligne juste avant de pousser la fin du cassage, et
     * jamais avant : ce qu'on veut éprouver est le <b>jugement</b>, et le jugement a lieu là.
     */
    private static void judge(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer actor = Understudy.actor(0);
        if (actor == null) {
            note("ÉPREUVE INVALIDE : la doublure a quitté la scène pendant le coup.");
            anyFailure = true;
            return;
        }

        // Le contrôle qui décide si l'épreuve a le droit de conclure : sur une ligne propre, la
        // progression comptée par le serveur doit franchir le seuil. Si elle ne le franchit pas, le
        // refus qu'on va observer ne viendra pas de la ligne mais du temps, et l'on aurait mesuré
        // lab.Cognee une seconde fois, plus mal.
        float serverProgress = part * (held + 1);
        if (serverProgress < SERVER_THRESHOLD) {
            note(String.format(Locale.ROOT,
                    "ÉPREUVE INVALIDE : le serveur n'a compté que %d tick(s), soit %.2f de "
                    + "progression — sous le seuil de %.2f. Le refus viendrait de l'horloge et non "
                    + "de la ligne. C'est lab.Cognee qui éprouve cela, pas ce banc.",
                    held, serverProgress, SERVER_THRESHOLD));
            anyFailure = true;
            return;
        }

        switch (round) {
            case 0 -> judgeCleanLine(level, actor);
            case 1 -> judgeLatePosition(level, actor);
            default -> judgeChain(level, actor);
        }
    }

    /** Manche 1 : le témoin. La bûche doit tomber, module ou non. */
    private static void judgeCleanLine(ServerLevel level, ServerPlayer actor) {
        push(actor, log, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
        if (level.getBlockState(log).isAir()) {
            note("CONFORME : sur une ligne propre la bûche tombe au geste. Témoin en place — ce "
                    + "banc mesure donc bien une ligne, et non un serveur en retard.");
            return;
        }
        anyFailure = true;
        note("ÉPREUVE INVALIDE : la bûche ne tombe même pas sur une ligne propre. Ce n'est plus la "
                + "ligne qui est en cause, c'est le banc ou l'horloge — voir lab.Cognee.");
    }

    /**
     * Manche 2 : la position vieille d'une demi-latence.
     *
     * <p>On recule la doublure de ce que la ligne vaut en blocs, <b>au moment du jugement</b>. Ce
     * n'est pas une mise en scène : c'est très exactement l'état du serveur quand le dernier paquet
     * de mouvement reçu a l'âge d'une traversée. Le client, lui, se voit à l'ancienne place et a
     * donc parfaitement le droit de frapper.
     */
    private static void judgeLatePosition(ServerLevel level, ServerPlayer actor) {
        double lag = LATENCY_MS / 2000d * SPEED;
        Vec3 behind = home.add(-lag, 0d, 0d);
        actor.snapTo(behind.x, behind.y, behind.z, actor.getYRot(), actor.getXRot());

        boolean vanillaWouldRefuse = !actor.isWithinBlockInteractionRange(log, Burin.VANILLA_BUFFER);
        if (!vanillaWouldRefuse) {
            anyFailure = true;
            note(String.format(Locale.ROOT,
                    "ÉPREUVE INVALIDE : reculée de %.2f bloc(s), la doublure reste dans la marge "
                    + "d'un bloc de vanilla. Vanilla accepterait donc aussi, et la manche ne "
                    + "distingue plus rien. Il faut une ligne plus mauvaise ou une case plus loin.",
                    lag));
            restore(actor);
            return;
        }

        push(actor, log, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
        boolean gone = level.getBlockState(log).isAir();
        double granted = Burin.VANILLA_BUFFER + Burin.extraBlocks(actor);
        restore(actor);

        if (gone) {
            note(String.format(Locale.ROOT,
                    "CONFORME : la bûche tombe alors que le serveur croit le joueur %.2f bloc(s) "
                    + "en arrière — ce que %d ms de ligne valent pour un joueur qui sprinte. Marge "
                    + "accordée : %.2f bloc(s), contre %.2f chez vanilla.",
                    lag, LATENCY_MS, granted, Burin.VANILLA_BUFFER));
            return;
        }
        anyFailure = true;
        note(String.format(Locale.ROOT,
                "NON CONFORME : la bûche TIENT ENCORE. Le serveur a jugé la portée sur une position "
                + "vieille d'une demi-latence — %.2f bloc(s) de retard — et a écarté l'action pour "
                + "« too far », EN SILENCE : ni destruction, ni renvoi de l'état du bloc, mais "
                + "l'accusé de séquence part tout de même. Le client annule alors sa prédiction et "
                + "remet la bûche en place. C'est le bloc fantôme, et le joueur n'a rien fait de "
                + "mal. Marge accordée : %.2f bloc(s).", lag, granted));
    }

    /**
     * Manche 3 : l'enchaînement, et c'est celle qui porte le rapport du joueur.
     *
     * <p>On refuse la première bûche en poussant sa fin trop tôt — ce que la gigue fait quand elle
     * comprime l'écart entre le début et la fin. Elle prend alors la place unique du rattrapage. On
     * casse ensuite la seconde <b>dans les règles</b>, et l'on regarde si elle tombe.
     */
    /**
     * Manche 3, première moitié : deux refus d'affilée, et la place unique qui décide.
     *
     * <h2>Pourquoi la version précédente ne prouvait rien</h2>
     *
     * <p>Elle refusait la première bûche, puis cassait la seconde <b>dans les règles</b>. Or une
     * case cassée dans les règles ne consulte jamais le rattrapage : elle franchit les sept
     * dixièmes et tombe sur-le-champ. La manche réussissait donc <em>aussi</em> avec le sabotage —
     * elle mesurait le chemin normal en croyant mesurer le filet. C'est la faute que ce dépôt
     * appelle « la zone franche », transposée à une place de rattrapage.
     *
     * <p>La place ne compte que lorsque la seconde case est <b>refusée elle aussi</b> — et c'est ce
     * qui arrive sur une mauvaise ligne, où les refus viennent en série. On refuse donc les deux, et
     * l'on regarde laquelle le serveur finit par abattre tout seul.
     */
    private static void judgeChain(ServerLevel level, ServerPlayer actor) {
        // Premier refus : on repart de zéro et l'on conclut aussitôt. Le serveur ne voit qu'un
        // tick écoulé, très en deçà du seuil — exactement ce que fait la gigue quand elle comprime
        // l'écart entre le début et la fin.
        push(actor, log, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
        push(actor, log, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
        push(actor, log, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
        if (level.getBlockState(log).isAir()) {
            anyFailure = true;
            note("ÉPREUVE INVALIDE : la première bûche est tombée alors qu'on voulait la faire "
                    + "refuser. Sans refus, la place du rattrapage reste libre et la manche ne "
                    + "distingue plus rien.");
            return;
        }

        // Second refus, sur l'autre case. C'est ici que tout se joue : vanilla trouve sa place déjà
        // prise et ne met RIEN dedans — la case n'est pas différée, elle est perdue.
        push(actor, second, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
        push(actor, second, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
        if (level.getBlockState(second).isAir()) {
            anyFailure = true;
            note("ÉPREUVE INVALIDE : la seconde bûche est tombée au geste alors qu'on voulait la "
                    + "faire refuser elle aussi. Sans second refus, le rattrapage n'est jamais "
                    + "consulté et la manche mesure le chemin normal.");
            return;
        }

        held = 0;
        chaining = true;
        Lanterne.LOG.info("[FRITURE] Manche {} · les DEUX bûches ont été refusées. On laisse "
                + "maintenant le serveur travailler {} ticks : son rattrapage abattra celle qu'il a "
                + "gardée, et l'autre restera debout pour toujours. Places rendues jusqu'ici : {}.",
                round + 1, clientTicks, Burin.handovers());
    }

    /**
     * Manche 3, seconde moitié : laquelle des deux le serveur a-t-il abattue ?
     *
     * <p>Aucune action n'est poussée ici. On regarde seulement ce que le serveur a fait tout seul,
     * ce qui est la seule façon d'observer le rattrapage : il ne s'exprime que dans son {@code
     * tick()}, jamais en réponse à un paquet.
     */
    private static void judgeChainSecond(ServerLevel level, ServerPlayer actor) {
        boolean firstGone = level.getBlockState(log).isAir();
        boolean secondGone = level.getBlockState(second).isAir();

        if (secondGone) {
            note(String.format(Locale.ROOT,
                    "CONFORME : les deux bûches ont été refusées, et c'est la DERNIÈRE — celle sur "
                    + "laquelle le joueur travaillait — que le serveur a fini par abattre. La place "
                    + "du rattrapage a changé de main %d fois. La première est restée debout, et "
                    + "c'est voulu : le joueur l'avait quittée, et vanilla l'aurait fait tomber dans "
                    + "son dos.", Burin.handovers()));
            return;
        }
        anyFailure = true;
        note(String.format(Locale.ROOT,
                "NON CONFORME : la seconde bûche est TOUJOURS debout, et le rattrapage a servi à "
                + "l'autre (tombée : %s). Le premier refus avait pris la place unique — "
                + "« if (!this.hasDelayedDestroy) » — et le second n'y a plus eu droit : il n'est "
                + "pas différé, il est PERDU. Le joueur voit donc tomber la case qu'il a quittée, "
                + "et rester debout celle qu'il minait. C'est très exactement « ça veut pas TOUT "
                + "casser » : ce n'est pas un bloc qui résiste, c'est le premier refus qui condamne "
                + "ceux d'après.", firstGone ? "oui" : "non"));
    }

    private static void restore(ServerPlayer actor) {
        if (home != null) {
            actor.snapTo(home.x, home.y, home.z, actor.getYRot(), actor.getXRot());
        }
    }

    // ------------------------------------------------------------------ l'enchaînement

    private static void nextRound() {
        ServerPlayer actor = Understudy.actor(0);
        if (actor != null && log != null) {
            // Refermer proprement : sans cet abandon, le serveur garde un cassage en cours sur une
            // case de la manche précédente, et la suivante commencerait par le faire annuler.
            push(actor, log, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
            restore(actor);
        }
        chaining = false;
        round++;
        step = Step.BUILDING;
    }

    private static void note(String what) {
        String line = ROUND_NAMES[Math.min(round, ROUND_NAMES.length - 1)] + " · " + what;
        VERDICTS.append(line).append('\n');
        if (what.startsWith("CONFORME")) {
            Lanterne.LOG.info("[FRITURE] {}", line);
        } else {
            Lanterne.LOG.error("[FRITURE] {}", line);
        }
    }

    private static void finish() {
        step = Step.DONE;
        Lanterne.LOG.info("[FRITURE] ── Verdict ──");
        for (String line : VERDICTS.toString().split("\n")) {
            if (!line.isBlank()) {
                Lanterne.LOG.info("[FRITURE] {}", line);
            }
        }
        Lanterne.LOG.info("[FRITURE] État du burin : {}", Burin.describe());
        if (anyFailure) {
            Lanterne.LOG.error("[FRITURE] Au moins une manche a échoué. Un geste juste doit "
                    + "aboutir, y compris au bout d'une mauvaise ligne — sinon la connexion du "
                    + "joueur devient une pénalité de minage, que personne ne comprend et que "
                    + "personne n'a demandée.");
        } else {
            Lanterne.LOG.info("[FRITURE] Les {} manches sont conformes. Rappel : "
                    + "LANTERNE_BREAK_BURIN=1 rend au serveur sa marge d'un bloc et sa place "
                    + "unique — les manches 2 et 3 DOIVENT alors échouer, et la première réussir. "
                    + "Si les trois réussissent, l'épreuve ne mesure rien.", ROUND_NAMES.length);
        }
        if (host != null) {
            host.halt(false);
        }
    }
}
