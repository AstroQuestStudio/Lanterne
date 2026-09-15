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

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'épreuve de la cognée : une bûche tombe-t-elle quand le serveur est en retard ?
 *
 * <h2>Le défaut rapporté en jeu, et pourquoi il fallait deux épreuves</h2>
 *
 * <blockquote>« Quand je casse du bois il veut pas — genre terre etc. c'est ok, mais bois non. »</blockquote>
 *
 * <p>La cause s'est révélée être <b>ailleurs</b> : dans le marteau d'AutoMiner, côté client, qui
 * confisquait le clic gauche du joueur dès qu'un arbre entier entrait dans sa file d'attente. Rien
 * de ce dépôt n'y est pour quoi que ce soit, et l'épreuve qui l'attrape vit là-bas
 * ({@code tools/HammerSelfTest.java}).
 *
 * <p>Mais l'enquête a mis {@code MiningMixin} en accusation, et une accusation ne se lève pas par
 * un raisonnement : elle se lève par une mesure. Cette épreuve existe pour cela, et elle sert
 * ensuite de garde-fou — car ce mixin réécrit, à chaque tick, le compteur dont dépend <b>tout</b> le
 * cassage de blocs du serveur. Une faute d'une unité y rendrait la forêt incassable, et aucun banc
 * de vitesse ne le verrait : un serveur où l'on ne casse rien est très rapide.
 *
 * <h2>Les deux compteurs, et la seule chose qui peut les séparer</h2>
 *
 * <p>Le serveur juge un cassage avec ces deux lignes, lues dans {@code ServerPlayerGameMode} :
 *
 * <pre>
 * int ticksSpentDestroying = this.gameTicks - this.destroyProgressStart;
 * float destroyProgress = state.getDestroyProgress(…) * (ticksSpentDestroying + 1);
 * if (destroyProgress &gt;= 0.7F) { destroyAndAck(…); }
 * </pre>
 *
 * <p>Le client, lui, compte ses propres ticks, qui tombent vingt fois par seconde quoi qu'il arrive.
 * Tant que le serveur tient les vingt ticks par seconde, les deux compteurs disent la même chose, et
 * <b>aucune épreuve à vitesse normale ne peut distinguer le mixin de son absence</b>. C'est
 * exactement pourquoi cette épreuve joue deux manches.
 *
 * <ol>
 *   <li><b>Serveur à l'heure.</b> Le témoin. Elle doit réussir dans tous les cas — avec le module,
 *       sans lui, et sur du vanilla. Si elle échoue, c'est l'épreuve qu'il faut réparer.</li>
 *   <li><b>Serveur en retard.</b> On fait délibérément durer chaque tick {@value #LAG_MS}
 *       millisecondes, ce qui ramène le serveur à un tiers de sa cadence. Le joueur, lui, tient son
 *       bouton pendant la <b>même durée réelle</b> qu'avant — c'est tout ce qu'il sait faire. Sans
 *       le module, le serveur n'a compté qu'un tiers des ticks, la progression tombe sous les sept
 *       dixièmes, et il refuse de détruire. Avec le module, il compte le temps qui passe et tombe
 *       d'accord avec le client.</li>
 * </ol>
 *
 * <h2>Ce que l'épreuve ne mesure pas</h2>
 *
 * <p>Elle ne touche pas au client : elle envoie au serveur les deux mêmes actions qu'un client
 * enverrait — {@code START_DESTROY_BLOCK} puis, une fois sa propre progression arrivée à un,
 * {@code STOP_DESTROY_BLOCK} — et regarde si la case est partie. C'est le seul verdict qui compte,
 * et c'est celui que le joueur voit.
 *
 * <p>Elle ne mesure pas non plus la <b>destruction différée</b> : quand le serveur refuse, il retient
 * la case et l'abat plus tard, de lui-même, à cent pour cent. Le bloc finit donc par tomber dans les
 * deux cas — mais une, deux, trois secondes après le geste, et c'est précisément ce que le joueur
 * décrit quand il dit « il veut pas ». On juge donc à l'instant du geste, pas plus tard.
 *
 * <h2>L'emploi</h2>
 *
 * <pre>
 * LANTERNE_COGNEE=1 ./gradlew runServer -Pbanc=cognee
 * LANTERNE_COGNEE=1 LANTERNE_BREAK_MINING=1 ./gradlew runServer -Pbanc=cognee   # DOIT échouer
 * </pre>
 */
public final class Cognee {

    /**
     * Durée imposée à chaque tick de la manche en retard, en millisecondes.
     *
     * <p>Cent cinquante plutôt que soixante : à soixante, le serveur tombe à seize ticks par seconde
     * et la progression perdue ne suffit pas toujours à passer sous les sept dixièmes — l'épreuve
     * réussirait parfois <b>sans</b> le module, et ne prouverait plus rien. À cent cinquante, le
     * serveur est à un tiers de sa cadence : la marge est nette des deux côtés, et le verdict ne
     * dépend plus de l'humeur de la machine.
     */
    private static final int LAG_MS = 150;

    /** Ticks laissés au monde et à la doublure pour se poser. */
    private static final int SETTLE = 40;

    /** Ticks au-delà desquels on renonce à attendre une condition qui n'arrive pas. */
    private static final int PATIENCE = 1200;

    /** Le seuil du serveur, recopié de {@code ServerPlayerGameMode}. */
    private static final float SERVER_THRESHOLD = 0.7F;

    /** Les deux manches : le retard imposé à chaque tick, en millisecondes. */
    private static final int[] ROUNDS = { 0, LAG_MS };
    private static final String[] ROUND_NAMES = { "serveur à l'heure", "serveur en retard" };

    private enum Step { OFF, BUILDING, SETTLING, MINING, DONE }

    private static Step step = Step.OFF;
    private static int waiting;
    private static int patience;
    private static MinecraftServer host;

    private static int round;
    private static BlockPos log;
    private static float part;
    private static int clientTicks;
    private static long holdNanos;
    private static long startNanos;
    private static int serverTicks;
    private static int sequence;

    private static final StringBuilder VERDICTS = new StringBuilder();
    private static boolean anyFailure;

    private Cognee() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        Settings.setEnabled(true);
        round = 0;
        anyFailure = false;
        sequence = 0;
        VERDICTS.setLength(0);
        step = Step.BUILDING;
        waiting = 0;
        Lanterne.LOG.info("[COGNEE] Épreuve armée — une bûche de chêne cassée à la main, en {} "
                + "manche(s). Module de cassage : {}.",
                ROUNDS.length, Settings.mining() ? "ACTIF" : "INACTIF");
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
        if (round >= ROUNDS.length) {
            finish();
            return;
        }
        ServerLevel level = server.overworld();
        ServerPlayer actor = Understudy.actor(0);
        if (actor == null) {
            note(round, "ÉPREUVE INVALIDE : aucune doublure en scène. Sans joueur, personne ne peut "
                    + "envoyer d'action de cassage — le serveur n'en accepte que d'un joueur.");
            anyFailure = true;
            round++;
            return;
        }

        // <h2>Une case différente à chaque manche</h2>
        //
        // Quand le serveur refuse de détruire, il RETIENT la case dans une destruction différée qui
        // porte sa position. Rejouer la manche suivante au même endroit ferait tomber la nouvelle
        // bûche pour le compte de l'ancienne, et l'épreuve applaudirait un refus.
        BlockPos feet = actor.blockPosition();
        log = round == 0 ? feet.offset(2, 0, 0) : feet.offset(0, 0, 2);
        level.setBlock(log, Blocks.OAK_LOG.defaultBlockState(), 3);

        step = Step.SETTLING;
        waiting = SETTLE;
        patience = PATIENCE;
        Lanterne.LOG.info("[COGNEE] Manche {} ({}) · bûche posée en {}, doublure en {}.",
                round + 1, ROUND_NAMES[round], log, feet);
    }

    /**
     * La scène est-elle prête à recevoir un coup ?
     *
     * <p>Trois conditions, et aucune n'est un délai. La bûche doit exister — un chunk qui se
     * régénère l'effacerait ; la case doit être à portée du bras, sans quoi le serveur écarte
     * l'action pour « too far » et l'épreuve conclurait sur un refus qui n'a rien à voir avec le
     * temps ; et la doublure doit être vivante.
     */
    private static boolean settled(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer actor = Understudy.actor(0);
        return actor != null
                && actor.isAlive()
                && level.getBlockState(log).is(Blocks.OAK_LOG)
                && actor.isWithinBlockInteractionRange(log, 1.0d);
    }

    // ------------------------------------------------------------------ le coup

    private static void strike(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer actor = Understudy.actor(0);
        if (actor == null || !level.getBlockState(log).is(Blocks.OAK_LOG)) {
            note(round, "ÉPREUVE INVALIDE : la bûche ou la doublure a disparu avant le coup.");
            anyFailure = true;
            nextRound();
            return;
        }
        if (!actor.isWithinBlockInteractionRange(log, 1.0d)) {
            note(round, "ÉPREUVE INVALIDE : la bûche est hors de portée du bras. Le serveur "
                    + "écarterait l'action pour « too far », et l'on aurait mesuré une distance au "
                    + "lieu d'une horloge.");
            anyFailure = true;
            nextRound();
            return;
        }

        BlockState state = level.getBlockState(log);
        part = state.getDestroyProgress(actor, level, log);
        if (part <= 0f || part >= 1f) {
            note(round, String.format(Locale.ROOT,
                    "ÉPREUVE INVALIDE : la bûche vaut %.4f de progression par tick. En dessous de "
                    + "zéro elle est incassable, au-dessus de un elle tombe au premier coup — dans "
                    + "les deux cas il n'y a pas d'horloge à éprouver.", part));
            anyFailure = true;
            nextRound();
            return;
        }

        // Ce que le CLIENT compte : il additionne sa part à chaque tick et conclut à un. C'est la
        // durée réelle pendant laquelle le joueur tient son bouton, et rien d'autre ne la fixe.
        clientTicks = (int) Math.ceil(1d / part);
        holdNanos = clientTicks * 50L * 1_000_000L;

        actor.gameMode.handleBlockBreakAction(log,
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                Direction.UP, level.getMaxY(), ++sequence);

        serverTicks = 0;
        startNanos = System.nanoTime();
        step = Step.MINING;
        // « pieds au sol » est dit parce qu'il explique à lui seul un facteur cinq sur la part :
        // getDestroyProgress divise par cinq un joueur en l'air. Sans cette ligne, un lecteur qui
        // trouve 0,0033 au lieu de 0,0167 croit à une erreur de calcul.
        Lanterne.LOG.info("[COGNEE] Manche {} ({}) · coup parti. Part par tick : {} (pieds au sol : "
                + "{}). Le client conclura au bout de {} ticks, soit {} ms de bouton tenu. Retard "
                + "imposé à chaque tick : {} ms.",
                round + 1, ROUND_NAMES[round], String.format(Locale.ROOT, "%.5f", part),
                actor.onGround() ? "oui" : "non",
                clientTicks, clientTicks * 50, ROUNDS[round]);
    }

    // ------------------------------------------------------------------ le relevé

    /**
     * Fait passer le temps, puis lâche le bouton.
     *
     * <h2>Pourquoi le retard s'impose ICI et pas dans un réglage</h2>
     *
     * <p>On veut un serveur réellement en retard, pas un serveur à qui l'on aurait <em>dit</em>
     * qu'il l'était. Faire durer le tick est la seule façon honnête de l'obtenir : c'est ce que fait
     * un ramassage mémoire, un autre mod qui bloque le fil, ou une machine trop chargée. Tout le
     * reste — décompter des ticks, mentir au compteur — reviendrait à éprouver la mise en scène.
     */
    private static void mine(MinecraftServer server) {
        serverTicks++;
        if (ROUNDS[round] > 0) {
            try {
                Thread.sleep(ROUNDS[round]);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (System.nanoTime() - startNanos < holdNanos) {
            return;
        }
        judge(server);
        nextRound();
    }

    private static void judge(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer actor = Understudy.actor(0);
        if (actor == null) {
            note(round, "ÉPREUVE INVALIDE : la doublure a quitté la scène pendant le coup.");
            anyFailure = true;
            return;
        }

        long heldMs = (System.nanoTime() - startNanos) / 1_000_000L;
        // Ce que le serveur aurait compté sans le module : ses propres tours de boucle.
        float vanillaProgress = part * (serverTicks + 1);
        // Ce que le client a compté, et ce que le module fait compter au serveur.
        float clockProgress = part * (heldMs / 50f + 1f);

        // <h2>La part doit être la même au début et à la fin</h2>
        //
        // {@code getDestroyProgress} divise par cinq quand le joueur n'a pas les pieds au sol, et la
        // doublure peut très bien se poser en cours de route. La part quintuplerait alors, la
        // progression au compte des ticks passerait toute seule au-dessus du seuil, et la manche
        // « serveur en retard » réussirait SANS le module — en applaudissant une chute, pas une
        // horloge. On refuse de conclure plutôt que de conclure à faux.
        float now = level.getBlockState(log).getDestroyProgress(actor, level, log);
        if (Math.abs(now - part) > 1.0e-6f) {
            note(round, String.format(Locale.ROOT,
                    "ÉPREUVE INVALIDE : la part par tick a changé pendant le coup (%.5f au départ, "
                    + "%.5f à l'arrivée). La doublure s'est posée, a changé d'outil ou est entrée "
                    + "dans l'eau : ce qu'on aurait mesuré n'est plus une horloge.", part, now));
            anyFailure = true;
            return;
        }

        // <h2>Et la manche en retard doit VRAIMENT être en retard</h2>
        //
        // Si la machine a tenu la cadence malgré le ralentissement demandé, le serveur a compté
        // assez de ticks pour franchir le seuil tout seul. La bûche tomberait alors avec ou sans le
        // module, et un « conforme » ne prouverait rien du tout. C'est la faute que Duel nomme sous
        // le nom de zone franche, transposée à une horloge.
        if (ROUNDS[round] > 0 && vanillaProgress >= SERVER_THRESHOLD) {
            note(round, String.format(Locale.ROOT,
                    "ÉPREUVE INVALIDE : le serveur a tout de même compté %d tick(s), soit %.2f de "
                    + "progression — au-dessus du seuil de %.2f qu'il exige. Il aurait donc détruit "
                    + "la bûche SANS la correction d'horloge, et cette manche ne distingue plus rien. "
                    + "Il faut allonger le retard imposé à chaque tick.",
                    serverTicks, vanillaProgress, SERVER_THRESHOLD));
            anyFailure = true;
            return;
        }

        actor.gameMode.handleBlockBreakAction(log,
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                Direction.UP, level.getMaxY(), ++sequence);

        boolean gone = level.getBlockState(log).isAir();

        Lanterne.LOG.info("[COGNEE] Manche {} ({}) · bouton tenu {} ms · ticks serveur : {} · "
                + "ticks d'horloge : {} · progression au compte des ticks : {} · au compte du temps : {}",
                round + 1, ROUND_NAMES[round], heldMs, serverTicks, (int) (heldMs / 50L),
                String.format(Locale.ROOT, "%.2f", vanillaProgress),
                String.format(Locale.ROOT, "%.2f", clockProgress));

        if (gone) {
            note(round, String.format(Locale.ROOT,
                    "CONFORME : la bûche est tombée au geste, après %d ms de bouton tenu pour %d "
                    + "tick(s) serveur. Le serveur et le client tombent d'accord.", heldMs, serverTicks));
            return;
        }

        anyFailure = true;
        note(round, String.format(Locale.ROOT,
                "NON CONFORME : la bûche TIENT ENCORE. Le client a compté %d ticks et lâché son "
                + "bouton ; le serveur n'en a compté que %d, soit %.2f de progression — sous le "
                + "seuil de %.2f qu'il exige. Il range la case en destruction différée et l'abattra "
                + "de lui-même une seconde ou deux plus tard : c'est exactement ce que le joueur "
                + "décrit en disant « il veut pas ». Compter des tours de boucle au lieu du temps "
                + "qui passe fait perdre au joueur ce que le serveur a perdu.",
                clientTicks, serverTicks, vanillaProgress, SERVER_THRESHOLD));
    }

    // ------------------------------------------------------------------ l'enchaînement

    private static void nextRound() {
        ServerPlayer actor = Understudy.actor(0);
        if (actor != null && log != null) {
            // Refermer proprement : sans cet abandon, le serveur garde un cassage en cours sur une
            // case de la manche précédente, et la suivante commencerait par le faire annuler.
            actor.gameMode.handleBlockBreakAction(log,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    Direction.UP, actor.level().getMaxY(), ++sequence);
        }
        round++;
        step = Step.BUILDING;
    }

    private static void note(int which, String what) {
        String line = ROUND_NAMES[Math.min(which, ROUND_NAMES.length - 1)] + " · " + what;
        VERDICTS.append(line).append('\n');
        if (what.startsWith("CONFORME")) {
            Lanterne.LOG.info("[COGNEE] {}", line);
        } else {
            Lanterne.LOG.error("[COGNEE] {}", line);
        }
    }

    private static void finish() {
        step = Step.DONE;
        Lanterne.LOG.info("[COGNEE] ── Verdict ──");
        for (String line : VERDICTS.toString().split("\n")) {
            if (!line.isBlank()) {
                Lanterne.LOG.info("[COGNEE] {}", line);
            }
        }
        if (anyFailure) {
            Lanterne.LOG.error("[COGNEE] Au moins une manche a échoué. Un bloc doit tomber au geste "
                    + "du joueur, y compris quand le serveur a pris du retard — sinon le retard du "
                    + "serveur devient une pénalité de minage, que personne ne comprend et que "
                    + "personne n'a demandée.");
        } else {
            Lanterne.LOG.info("[COGNEE] Les {} manches sont conformes. Rappel : "
                    + "LANTERNE_BREAK_MINING=1 éteint la correction d'horloge — la manche « serveur "
                    + "en retard » DOIT alors échouer, et celle « à l'heure » réussir. Si les deux "
                    + "réussissent, l'épreuve ne mesure rien.", ROUNDS.length);
        }
        if (host != null) {
            host.halt(false);
        }
    }
}
