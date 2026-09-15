package fr.clubcitrouille.lanterne.lab;

import java.util.Locale;
import java.util.Set;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le seuil : ce que coûte une arrivée, et quelle part on repaie à chaque fois.
 *
 * <h2>La question, et pourquoi elle n'a qu'une réponse utile</h2>
 *
 * <p>Arriver sur un serveur fait tomber le tick. Passer une dimension aussi. Tout le monde le
 * constate, et l'on trouve pour l'expliquer deux récits incompatibles :
 *
 * <ul>
 *   <li>« <b>c'est la génération</b> » — le serveur fabrique le terrain qu'il faut livrer ;</li>
 *   <li>« <b>c'est l'envoi</b> » — le serveur emballe quatre cents chunks et les pousse.</li>
 * </ul>
 *
 * <p>Les deux sont vrais, et cela ne sert à rien de le savoir : le remède n'est pas le même. La
 * génération se supprime en <b>pré-générant</b>, une fois pour toutes. L'emballage, lui, se repaie
 * <b>à chaque arrivée</b> — et aucune pré-génération n'y peut quoi que ce soit.
 *
 * <p>La seule question utile est donc : <b>quelle est la part qu'on repaie ?</b>
 *
 * <h2>Comment on la sépare, sans instrument dans le chemin chaud</h2>
 *
 * <p>On ne mesure pas l'intérieur du serveur, on fait varier une condition et une seule :
 *
 * <pre>
 * A — arrivee a FROID : premiere doublure, les chunks sont a lire ou a fabriquer
 * B — arrivee a CHAUD : seconde doublure AU MEME ENDROIT, la premiere n'ayant pas bouge
 * </pre>
 *
 * <p>En B, les chunks sont déjà chargés en mémoire : il ne reste que l'emballage et l'envoi. Le
 * rapport {@code B / A} est donc directement la part repayée, sans qu'on ait eu à poser une sonde
 * nulle part.
 *
 * <p>Et la lecture est sans ambiguïté :
 *
 * <ul>
 *   <li>{@code B} proche de {@code A} → c'est l'emballage qui domine. Un joueur qui revient paie
 *       plein tarif, et pré-générer n'y changera rien.</li>
 *   <li>{@code B} très inférieur à {@code A} → c'est le chargement qui domine, et la
 *       pré-génération est la réponse — elle l'est déjà.</li>
 * </ul>
 *
 * <h2>Ce que ce banc ne mesure pas, et le dit</h2>
 *
 * <p>Le changement de dimension est relevé par une <b>téléportation directe</b>, sans portail. Ce
 * qui est mesuré est donc le coût des chunks d'une autre dimension, et <b>pas</b> la recherche du
 * portail de destination — qui est un tout autre poste, et qu'il faudra mesurer à part plutôt que
 * de le laisser se confondre avec celui-ci.
 *
 * <p>Ni la compression, ni le chiffrement, ni l'écriture sur la socket : voir
 * {@link SilentConnection}. Ce sont des coûts réels, mais ils appartiennent au réseau du joueur.
 *
 * <h2>L'échappatoire éprouve l'instrument, pas le module</h2>
 *
 * <p>{@code LANTERNE_BREAK_SEUIL=1} coupe l'accusé de réception des lots de chunks. Le serveur
 * n'en envoie alors qu'<b>un</b>, soit neuf chunks au lieu de quatre cent quarante et un, et le
 * banc doit <b>refuser de conclure</b> au lieu d'annoncer joyeusement qu'arriver ne coûte rien.
 *
 * <p>C'est exactement le mensonge que ce banc a failli raconter : la doublure existait depuis
 * longtemps, elle n'accusait rien, et personne ne s'en était aperçu parce que les bancs d'entités
 * n'en souffrent pas.
 */
public final class Seuil {
    /**
     * Sous ce nombre de chunks livrés, le relevé n'a rien mesuré et ne conclut pas.
     *
     * <p>Le seuil est mis juste au-dessus des neuf chunks d'un lot unique : c'est très exactement
     * la valeur que produit un accusé manquant, et c'est donc celle qu'il faut rendre impossible à
     * confondre avec un résultat.
     */
    private static final long LEAST_CHUNKS = 32L;

    /**
     * Ticks sans nouveau chunk après lesquels on considère la livraison terminée.
     *
     * <p>Généreux, et ce n'est pas de la prudence gratuite : sur un monde neuf, un chunk doit être
     * <b>fabriqué</b> avant d'être livré, et le débit tombe alors à une quinzaine par seconde, par
     * à-coups. Un silence court coupait la mesure en plein milieu — la première version de ce banc
     * a clos sa phase de froid sur <em>zéro</em> chunk livré, ce qui aurait été lu comme « arriver
     * ne coûte rien » si l'instrument n'avait pas su se taire.
     */
    private static final int QUIET_TICKS = 200;

    /**
     * Patience maximale par phase, en ticks. Cinq minutes.
     *
     * <p>Un banc qui attend indéfiniment n'échoue jamais : il pend. Et un banc qui pend est pire
     * qu'un banc qui échoue, parce qu'on finit par le tuer sans savoir ce qu'il aurait dit.
     */
    private static final int PATIENCE_TICKS = 6000;

    private enum Phase { OFF, COLD, WARM, OTHER_WORLD, DONE }

    private Seuil() {}

    private static Phase phase = Phase.OFF;
    private static long tickStart;
    private static long phaseNanos;
    private static long worstTickNanos;
    private static int phaseTicks;
    private static int watched;
    /**
     * Ce que la doublure observée avait déjà reçu à l'ouverture de la phase.
     *
     * <p>Le compteur d'une doublure est cumulatif. La troisième phase réobserve la PREMIÈRE, qui a
     * déjà reçu ses quatre cent quarante-cinq chunks : sans ce retranchement, la phase se croyait
     * terminée au premier tick et publiait « 473 chunks en 1 tick », ce qui n'était pas une mesure
     * rapide mais une mesure inexistante.
     */
    private static long chunksAtPhaseStart;
    private static int quiet;
    private static long lastSeenChunks;
    private static int expectedChunks;

    private static double coldMs;
    private static long coldChunks;
    private static double warmMs;
    private static long warmChunks;

    public static boolean running() {
        return phase != Phase.OFF && phase != Phase.DONE;
    }

    /** Ouvre le relevé. Le monde doit être vide de joueurs. */
    public static void begin(MinecraftServer server) {
        boolean sabotaged = "1".equals(System.getenv("LANTERNE_BREAK_SEUIL"));
        Understudy.answerChunkBatches(!sabotaged);
        if (sabotaged) {
            Lanterne.LOG.warn("[SEUIL] SABOTAGE demandé : les doublures n'accuseront pas leurs lots. "
                    + "Le serveur va donc s'arrêter après un seul lot, et ce banc DOIT refuser de "
                    + "conclure. S'il conclut quand même, c'est lui qu'il faut corriger.");
        }

        Understudy.leave(server);
        // Les doublures doivent annoncer la distance de vue du SERVEUR, sans quoi elles en
        // demandent deux et n'en reçoivent que quarante-neuf : on mesurerait alors le neuvième d'une
        // arrivée en croyant en mesurer une.
        Understudy.announceViewDistance(server.getPlayerList().getViewDistance());
        Lanterne.LOG.info("[SEUIL] relevé ouvert. Distance de vue du serveur : {} — soit {} chunks "
                + "attendus par arrivée.", server.getPlayerList().getViewDistance(),
                expected(server));
        expectedChunks = expected(server);
        // La première doublure arrive dans un monde que personne n'a chargé : c'est le cas du
        // premier joueur du jour, et le plus cher des deux. On ouvre la phase APRÈS l'avoir placée,
        // parce que la phase doit nommer la doublure qu'elle observe et que celle-ci n'existe pas
        // avant.
        int first = Understudy.addOne(server, server.overworld(), 0d, 0d);
        if (first < 0) {
            Lanterne.LOG.error("[SEUIL] le serveur a refusé la première doublure. Rien ne sera "
                    + "mesuré, et rien ne sera conclu.");
            phase = Phase.DONE;
            return;
        }
        openPhase(Phase.COLD, first);
    }

    private static int expected(MinecraftServer server) {
        int view = server.getPlayerList().getViewDistance();
        int side = view * 2 + 1;
        return side * side;
    }

    /** À appeler au tout début du tick serveur. */
    public static void beginTick() {
        if (running()) {
            tickStart = System.nanoTime();
        }
    }

    /** Fait avancer le relevé. */
    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        long elapsed = System.nanoTime() - tickStart;
        phaseNanos += elapsed;
        phaseTicks++;
        worstTickNanos = Math.max(worstTickNanos, elapsed);

        long delivered = Understudy.chunksDeliveredTo(watched) - chunksAtPhaseStart;
        if (delivered != lastSeenChunks) {
            lastSeenChunks = delivered;
            quiet = 0;
        } else {
            quiet++;
        }

        // Le critère principal est le COMPTE, pas le silence : quand la doublure a reçu tout ce
        // qu'une distance de vue lui doit, la livraison est finie et il n'y a rien à attendre de
        // plus. Le silence n'est qu'un filet, pour le cas où le compte n'est jamais atteint.
        boolean complete = delivered >= expectedChunks;
        // Et l'on ne compte le silence QUE si quelque chose est déjà arrivé. Sans cette condition,
        // c'est le temps de mise en route qu'on prend pour la fin de la livraison.
        boolean starved = delivered > 0L && quiet >= QUIET_TICKS;
        boolean exhausted = phaseTicks >= PATIENCE_TICKS;

        if (complete || starved || exhausted) {
            if (exhausted && !complete) {
                Lanterne.LOG.warn("[SEUIL] patience épuisée sur cette phase : {} chunk(s) sur {} "
                        + "attendus. Le relevé continue, mais ce chiffre-là est un plancher.",
                        delivered, expectedChunks);
            }
            closePhase(server);
        }
    }

    /**
     * Ouvre une phase et désigne la doublure qu'elle observe.
     *
     * @param observed indice de la doublure dont on suit la livraison
     */
    private static void openPhase(Phase next, int observed) {
        phase = next;
        phaseNanos = 0L;
        phaseTicks = 0;
        worstTickNanos = 0L;
        quiet = 0;
        watched = observed;
        chunksAtPhaseStart = Understudy.chunksDeliveredTo(observed);
        lastSeenChunks = 0L;
    }

    private static void closePhase(MinecraftServer server) {
        long chunks = Understudy.chunksDeliveredTo(watched) - chunksAtPhaseStart;
        // Les ticks de silence ne sont pas du travail : les laisser dans le total ferait passer un
        // banc lent pour un banc cher. On les retire, eux et leur durée estimée au prorata.
        double ms = phaseNanos / 1.0E6d;
        double worst = worstTickNanos / 1.0E6d;

        switch (phase) {
            case COLD -> {
                coldMs = ms;
                coldChunks = chunks;
                Lanterne.LOG.info(String.format(Locale.ROOT,
                        "[SEUIL] A — arrivée à FROID : %d chunk(s) livrés en %d tick(s), %.1f ms "
                        + "cumulées, pire tick %.1f ms.", chunks, phaseTicks, ms, worst));
                // Au MÊME endroit, et la première doublure n'a pas bougé : les chunks sont chargés,
                // il ne reste que l'emballage. C'est toute l'expérience.
                int second = Understudy.addOne(server, server.overworld(), 0d, 0d);
                if (second < 0) {
                    Lanterne.LOG.error("[SEUIL] le serveur a refusé la seconde doublure : sans elle "
                            + "il n'y a pas de comparaison, et donc rien à conclure.");
                    phase = Phase.DONE;
                    Understudy.leave(server);
                    return;
                }
                openPhase(Phase.WARM, second);
            }
            case WARM -> {
                warmMs = ms;
                warmChunks = chunks;
                Lanterne.LOG.info(String.format(Locale.ROOT,
                        "[SEUIL] B — arrivée à CHAUD : %d chunk(s) livrés en %d tick(s), %.1f ms "
                        + "cumulées, pire tick %.1f ms.", chunks, phaseTicks, ms, worst));
                openPhase(Phase.OTHER_WORLD, 0);
                ServerPlayer traveller = Understudy.actor(0);
                ServerLevel nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
                if (traveller == null || nether == null) {
                    Lanterne.LOG.warn("[SEUIL] pas de Nether sur ce serveur : la troisième phase est "
                            + "sautée, et rien n'en sera conclu.");
                    conclude(server, 0L, 0d, 0d, 0);
                    return;
                }
                traveller.teleportTo(nether, 0d, 80d, 0d, Set.<Relative>of(), 0f, 0f, false);
            }
            case OTHER_WORLD -> conclude(server, chunks, ms, worst, phaseTicks);
            default -> { }
        }
    }

    private static void conclude(MinecraftServer server, long otherChunks, double otherMs,
            double otherWorst, int otherTicks) {
        phase = Phase.DONE;

        if (coldChunks < LEAST_CHUNKS || warmChunks < LEAST_CHUNKS) {
            Lanterne.LOG.error("[SEUIL] RELEVÉ INVALIDE : {} chunk(s) à froid et {} à chaud, là où "
                    + "{} étaient attendus. En dessous de {}, c'est le signe qu'un seul lot est "
                    + "parti — donc que les accusés de réception ne reviennent pas. Rien n'est "
                    + "conclu : un instrument qui ne mesure pas doit se taire, pas arrondir.",
                    coldChunks, warmChunks, expected(server), LEAST_CHUNKS);
            Understudy.leave(server);
            return;
        }

        double ratio = warmMs / Math.max(0.001d, coldMs);
        Lanterne.LOG.info("[SEUIL] ─────────────────────────────────────────────────────────");
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[SEUIL] à froid %.1f ms pour %d chunk(s) · à chaud %.1f ms pour %d chunk(s)",
                coldMs, coldChunks, warmMs, warmChunks));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[SEUIL] part repayée à chaque arrivée : %.0f %% du coût d'une arrivée à froid.",
                ratio * 100d));
        if (otherChunks > 0L) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[SEUIL] changement de dimension (sans portail) : %d chunk(s) en %d tick(s), "
                    + "%.1f ms cumulées, pire tick %.1f ms.",
                    otherChunks, otherTicks, otherMs, otherWorst));
        }

        // La conclusion est dite en toutes lettres — mais SANS aller plus loin que ce que les deux
        // nombres établissent. La différence A - B est le coût de MISE EN MÉMOIRE : lecture sur
        // disque si le chunk existe, fabrication sinon. Ce banc ne sépare pas ces deux-là, et se
        // garder de conclure « donc pré-générez » est la seule lecture honnête : sur un monde déjà
        // écrit, pré-générer ne retirerait rien de ce temps, puisqu'il est déjà tout entier de la
        // lecture.
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[SEUIL] décomposition : %.1f ms de mise en mémoire (lecture disque ou fabrication, "
                + "ce banc ne les sépare pas) + %.1f ms d'emballage.",
                Math.max(0d, coldMs - warmMs), warmMs));
        Lanterne.LOG.info("[SEUIL] LECTURE : l'emballage est le seul poste qu'on repaie ENTIÈREMENT "
                + "à chaque arrivée, et aucune pré-génération ne l'enlève. La mise en mémoire, elle, "
                + "n'est évitable que pour les chunks qui restent chargés — c'est-à-dire pour "
                + "personne, sur un serveur où les joueurs vont et viennent.");
        Lanterne.LOG.info("[SEUIL] ─────────────────────────────────────────────────────────");
        Understudy.leave(server);
    }

    /** Referme tout, pour que le banc suivant parte d'une scène vide. */
    public static void halt(MinecraftServer server) {
        if (running()) {
            phase = Phase.DONE;
            Understudy.leave(server);
        }
        Understudy.answerChunkBatches(false);
    }
}
