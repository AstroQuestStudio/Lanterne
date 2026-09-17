package fr.clubcitrouille.lanterne.lab;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Config;
import fr.clubcitrouille.lanterne.core.Digue;
import fr.clubcitrouille.lanterne.core.Quota;
import fr.clubcitrouille.lanterne.core.TickBudget;

/**
 * La lisière : repousser en continu le bord du monde déjà écrit, juste devant les joueurs.
 *
 * <h2>{@link Pregen}, mais sans commande, sans rayon fixe, sans fin</h2>
 *
 * <p>{@link Pregen} sait déjà tout ce qu'il faut savoir sur <em>comment</em> fabriquer un chunk sans
 * bloquer le fil principal — voir sa Javadoc de classe pour la mesure (155-209 chunks/s servis depuis
 * le disque contre 13-17 fabriqués devant le joueur) et le ticket {@code SPAWN_SEARCH} qui charge sans
 * simuler et s'efface tout seul. Ce fichier ne réinvente rien de cela : il appelle le même
 * {@code ServerChunkCache.addTicketAndLoadWithRadius}, avec le même ticket.
 *
 * <p>Ce qu'il change, c'est <em>où</em> et <em>quand</em>. {@code Pregen} vise un disque fixe autour
 * d'un point choisi par un opérateur, une fois, jusqu'au bout ou jusqu'à interruption. Ce module vise
 * en permanence l'anneau juste au-delà de ce que chaque joueur voit déjà — c'est-à-dire l'endroit où
 * il va marcher dans les prochaines minutes — et ne s'arrête jamais de lui-même. Les deux ne se
 * marchent jamais dessus : tant que {@link Pregen#running()} répond vrai, ce module ne soumet rien.
 *
 * <h2>Le signal qui décide : la marge du tick, pas la présence d'un joueur</h2>
 *
 * <p>Un serveur loué avec un « cœur flex » — une capacité supplémentaire, offerte en burst par
 * l'hébergeur quand il n'en a pas besoin ailleurs, reprise sans préavis — ne peut pas être détecté
 * depuis l'intérieur d'une JVM avec certitude : rien ne distingue, au niveau du système, un cœur flex
 * réellement présent d'une machine simplement peu chargée à cet instant précis. Ce module ne prétend
 * donc pas détecter un cœur flex au sens strict. Il observe {@link TickBudget#pressure()} — la même
 * jauge lissée que {@code TickBudget} tient déjà pour lui-même, zéro à un, qui monte vite et redescend
 * lentement — et en déduit qu'il y a de la marge <b>quelle qu'en soit la cause</b> : un cœur flex
 * disponible, ou simplement personne d'assez actif en ce moment. La cause importe peu ; ce qui compte
 * est que la marge, quand elle existe, ne coûte rien à utiliser tant qu'on la rend dès qu'elle
 * disparaît.
 *
 * <p>Une seule bascule, avec hystérésis plutôt qu'un seuil unique : en dessous de {@link #ENGAGE_BELOW}
 * on s'autorise à soumettre, au-dessus de {@link #DISENGAGE_AT} on s'arrête. Deux seuils distincts,
 * pas un, pour ne pas battre en soumettant puis en s'arrêtant plusieurs fois par seconde si la pression
 * oscille pile sur un seuil unique — le même défaut que {@code Crowd} évite entre {@code SEUIL} et
 * {@code LACHER} pour une raison différente mais la même logique.
 *
 * <h2>Le cas du cœur unique confirmé : aucune marge n'est réelle</h2>
 *
 * <p>{@link Quota#cores()} dit ce que la machine promet vraiment, cgroup lu quand c'est possible — voir
 * sa Javadoc. Quand ce nombre vaut un et qu'au moins un joueur est connecté, ce module se tait
 * entièrement, quelle que soit la pression du tick à l'instant : une accalmie momentanée sur un cœur
 * unique n'est pas une marge sur laquelle on peut compter, c'est une respiration entre deux pics, et la
 * voler reviendrait exactement au défaut que {@code Pregen.PAUSE_ON_JOIN} existe déjà pour éviter. Ce
 * n'est qu'avec un second cœur confirmé — ou dans le doute, quand la détection de {@link Quota} n'a pu
 * s'appuyer que sur son repli le moins fiable — que la jauge de pression seule décide.
 *
 * <h2>Où chercher : un anneau tournant, pas un balayage complet</h2>
 *
 * <p>Un joueur qui explore avance dans une direction qu'on ne connaît pas à l'avance sans suivre sa
 * vélocité — un raffinement possible, pas fait ici, voir plus bas. On échantillonne donc un cercle
 * complet autour de lui, à un rayon fixe au-delà de la distance de vue, avec un petit nombre de points
 * par passage ({@link #SAMPLES}) dont l'angle de départ tourne à chaque cycle : sur plusieurs dizaines
 * de secondes, le cercle entier finit couvert, sans jamais payer le coût d'un anneau complet d'un seul
 * coup. Le rayon lui-même — quelques dizaines de chunks au-delà de la vue — n'a pas besoin d'être plus
 * large : c'est très exactement la marge que {@link Pregen}'s propre Javadoc chiffre à dix-huit
 * méga-octets par chunk en vol, et ce module ne vise jamais à couvrir un territoire, seulement à
 * garder une longueur d'avance.
 *
 * <h2>Ce qu'un chunk déjà chargé ne coûte pas deux fois</h2>
 *
 * <p>{@link Digue#now} répond sans jamais bloquer si un chunk est déjà résident — chargé par ce
 * module lors d'un cycle précédent, par la distance de vue normale du jeu, ou par un joueur qui l'a
 * déjà atteint. On ne soumet jamais pour un chunk déjà là : ni gain, ni sens.
 *
 * <h2>Ce qui n'est délibérément pas fait ici</h2>
 *
 * <ul>
 *   <li><b>Pas de suivi de vélocité.</b> Viser dans la direction où le joueur se dirige vraiment,
 *       plutôt qu'un cercle complet autour de lui, doublerait ou triplerait l'efficacité par chunk
 *       soumis. C'est une piste ouverte, pas fermée — elle demande de suivre une moyenne mobile de
 *       déplacement par joueur, ce qui dépasse ce qu'un seul passage peut prouver correct.</li>
 *   <li><b>Pas de lecture des en-têtes de région comme {@link Pregen#readExistingChunks}.</b> Cette
 *       optimisation vaut pour balayer un carré de millions de chunks sans les charger. Ici, la
 *       fenêtre visée à chaque cycle tient en quelques dizaines de positions : {@link Digue#now} est
 *       la vérification la moins chère qui existe pour un aussi petit ensemble, et lire un en-tête de
 *       région pour économiser une lecture de table serait le remède plus cher que le mal.</li>
 * </ul>
 */
public final class Lisiere {
    /** Ticks entre deux cycles de balayage. */
    private static final int SCAN_PERIOD = 10;

    /** Chunks au-delà de la distance de vue visés par l'anneau. */
    private static final int LOOK_AHEAD = 16;

    /** Points échantillonnés sur le cercle, par joueur, par cycle. */
    private static final int SAMPLES = 8;

    /** Chunks en vol au maximum, tous joueurs et toutes dimensions confondus. */
    private static final int CAP = 4;

    /** Pression de tick en dessous de laquelle on s'autorise à soumettre. */
    private static final double ENGAGE_BELOW = 0.10d;

    /** Pression de tick à partir de laquelle on s'arrête, immédiatement. */
    private static final double DISENGAGE_AT = 0.30d;

    /** Ticks pendant lesquels une position récemment tentée n'est pas retentée. */
    private static final long RETRY_TTL_TICKS = 200L;

    /** Taille au-delà de laquelle la mémoire des tentatives récentes est simplement vidée. */
    private static final int MAX_TRACKED = 4096;

    private static final java.util.concurrent.atomic.AtomicInteger AIRBORNE =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Position (mêlée à l'identité du monde) → dernier tick de tentative. */
    private static final Long2LongOpenHashMap RECENT = new Long2LongOpenHashMap();

    /** Vrai tant que la marge observée reste suffisante — hystérésis, voir la Javadoc de classe. */
    private static boolean engaged;

    /** Angle de départ de l'échantillonnage, avance à chaque cycle pour couvrir tout le cercle. */
    private static double angle;

    private static int cooldown;

    /** {@link #submittedTotal} au moment du dernier engagement — pour dire ce qu'un passage a fait. */
    private static long submittedAtEngage;

    /** Ticks entre deux lignes de résumé pendant qu'on est engagé — voir {@link #reportIfDue}. */
    private static final int REPORT_PERIOD = 200;
    private static int reportCooldown;

    static {
        RECENT.defaultReturnValue(Long.MIN_VALUE);
    }

    private Lisiere() {}

    /** Chunks soumis depuis le démarrage — le gain, en clair, une fois mesuré. */
    public static long submitted() {
        return submittedTotal;
    }

    private static long submittedTotal;

    /** Vrai si la marge observée permet actuellement de soumettre. Pour le rapport/diagnostic. */
    public static boolean engaged() {
        return engaged;
    }

    /** Appelé une fois par tick serveur, après {@link Pregen#tick}. */
    public static void tick(MinecraftServer server) {
        if (!Config.PREGEN_CONTINUOUS.get()) {
            if (!RECENT.isEmpty()) {
                RECENT.clear();
            }
            engaged = false;
            return;
        }
        // La pré-génération manuelle a un opérateur derrière elle ; elle ne doit jamais se disputer
        // le même travail avec un service qui tourne sans que personne ne l'ait demandé maintenant.
        if (Pregen.running()) {
            return;
        }

        boolean anyPlayers = server.getPlayerList().getPlayerCount() > 0;
        if (Quota.cores() <= 1 && anyPlayers) {
            // Un seul cœur confirmé : aucune accalmie de tick ne vaut une vraie marge tant que
            // quelqu'un joue. Voir la Javadoc de classe.
            engaged = false;
            return;
        }

        double pressure = TickBudget.pressure();
        if (engaged) {
            if (pressure >= DISENGAGE_AT) {
                engaged = false;
                Lanterne.LOG.info(String.format(java.util.Locale.ROOT,
                        "[LISIÈRE] Pression de tick remontée (%.2f) — pré-génération suspendue. "
                                + "%d chunk(s) soumis pendant cet engagement.",
                        pressure, submittedTotal - submittedAtEngage));
            }
        } else if (pressure < ENGAGE_BELOW) {
            engaged = true;
            submittedAtEngage = submittedTotal;
            reportCooldown = 0;
            Lanterne.LOG.info(String.format(java.util.Locale.ROOT,
                    "[LISIÈRE] Marge de tick détectée (pression %.2f) — pré-génération en avant "
                            + "des joueurs activée.", pressure));
        }
        if (!engaged) {
            return;
        }

        reportIfDue();

        if (--cooldown > 0) {
            return;
        }
        cooldown = SCAN_PERIOD;

        if (RECENT.size() > MAX_TRACKED) {
            // Le pire que perdre ce souvenir puisse coûter est une resoumission — jamais une erreur.
            // Voir la Javadoc de classe sur la vérification par Digue.now, qui reste la garde réelle.
            RECENT.clear();
        }

        int viewDistance = server.getPlayerList().getViewDistance();
        int radius = viewDistance + LOOK_AHEAD;
        angle += 0.7d;

        for (ServerLevel level : server.getAllLevels()) {
            if (AIRBORNE.get() >= CAP) {
                break;
            }
            for (ServerPlayer player : level.players()) {
                if (player.isSpectator()) {
                    continue;
                }
                if (AIRBORNE.get() >= CAP) {
                    break;
                }
                scanAround(level, player, radius);
            }
        }
    }

    /**
     * Une ligne de résumé toutes les {@link #REPORT_PERIOD} ticks pendant qu'on est engagé — jamais
     * pendant le silence, pour ne pas remplir le journal d'un serveur qui n'a jamais de marge.
     */
    private static void reportIfDue() {
        if (--reportCooldown > 0) {
            return;
        }
        reportCooldown = REPORT_PERIOD;
        Lanterne.LOG.info(String.format(java.util.Locale.ROOT,
                "[LISIÈRE] toujours engagée — %d chunk(s) soumis depuis le début de cet engagement, "
                        + "%d en vol.",
                submittedTotal - submittedAtEngage, AIRBORNE.get()));
    }

    /** Échantillonne {@link #SAMPLES} points sur le cercle de ce rayon, autour de ce joueur. */
    private static void scanAround(ServerLevel level, ServerPlayer player, int radius) {
        int baseX = SectionPos.blockToSectionCoord(player.getBlockX());
        int baseZ = SectionPos.blockToSectionCoord(player.getBlockZ());
        long now = level.getGameTime();

        for (int i = 0; i < SAMPLES; i++) {
            if (AIRBORNE.get() >= CAP) {
                return;
            }
            double a = angle + i * (2d * Math.PI / SAMPLES);
            int chunkX = baseX + (int) Math.round(Math.cos(a) * radius);
            int chunkZ = baseZ + (int) Math.round(Math.sin(a) * radius);
            considerSpot(level, chunkX, chunkZ, now);
        }
    }

    /** Soumet ce chunk s'il n'est ni déjà résident, ni déjà tenté récemment. */
    private static void considerSpot(ServerLevel level, int chunkX, int chunkZ, long now) {
        long packed = ChunkPos.pack(chunkX, chunkZ);
        long key = mix(level, packed);

        long lastTry = RECENT.get(key);
        if (lastTry != Long.MIN_VALUE && now - lastTry < RETRY_TTL_TICKS) {
            return;
        }
        if (Digue.now(level, chunkX, chunkZ) != null) {
            // Déjà en mémoire — par ce module, par la vue normale du joueur, ou par sa propre
            // marche. Rien à gagner, et on ne l'inscrit pas non plus : le retenir aussi longtemps
            // que RETRY_TTL_TICKS empêcherait de remarquer qu'il a été déchargé entre-temps.
            return;
        }

        RECENT.put(key, now);
        AIRBORNE.incrementAndGet();
        submittedTotal++;
        ServerChunkCache source = level.getChunkSource();
        ChunkPos spot = new ChunkPos(chunkX, chunkZ);
        try {
            source.addTicketAndLoadWithRadius(TicketType.SPAWN_SEARCH, spot, 0)
                    .whenComplete((outcome, failure) -> AIRBORNE.decrementAndGet());
        } catch (RuntimeException refused) {
            // Le jeu refuse la demande — un monde en cours de fermeture, par exemple. On ne
            // relance pas dans la foulée : la marque posée ci-dessus empêche de réessayer avant
            // RETRY_TTL_TICKS, ce qui est exactement le comportement voulu face à un refus.
            AIRBORNE.decrementAndGet();
        }
    }

    /** Mêle une position empaquetée à l'identité du monde — même geste qu'{@code Emballage.cle}. */
    private static long mix(ServerLevel level, long packed) {
        return packed * 0x9E3779B97F4A7C15L + System.identityHashCode(level);
    }
}
