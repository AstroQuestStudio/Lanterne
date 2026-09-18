package fr.clubcitrouille.lanterne.lab;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * La traversée : ce que coûte réellement un franchissement de portail vers une destination jamais
 * visitée, et ce que {@link fr.clubcitrouille.lanterne.core.PortalPreload} en retire.
 *
 * <h2>La question posée, et pourquoi elle ne se répond pas en lisant le code</h2>
 *
 * <p>{@code lab/Seuil} a déjà établi qu'une arrivée à froid coûte 2197 ms pour 444 chunks, et que
 * 43 % de ce coût est de l'emballage repayé à chaque fois — mais son propre javadoc le dit : « le
 * changement de dimension est relevé par une téléportation directe, sans portail […] pas la
 * recherche du portail de destination, qui est un tout autre poste ». C'est ce poste-ci, et lui
 * précisément, que cette épreuve chronomètre — sur le <b>vrai</b> code de recherche/création de
 * portail ({@code NetherPortalBlock.getPortalDestination} → {@code PortalForcer}), pas une
 * réimplémentation.
 *
 * <p>La lecture du code de {@code PortalForcer.createPortal} l'a montré avant même de lancer un
 * relevé : la recherche d'une destination inédite fait tourner {@code BlockPos.spiralAround(origin,
 * 16, …)} et interroge {@code level.getHeight(…)} colonne par colonne — donc <b>force la génération
 * des chunks qu'elle inspecte</b>, sur le fil principal, avant même que le joueur n'ait quitté sa
 * dimension d'origine. « Recherche de portail » et « génération de chunks à la destination » ne sont
 * donc pas deux postes candidats séparés : c'est le <b>même</b> poste, vu sous deux noms.
 *
 * <h2>Le protocole : deux traversées, une seule condition qui change</h2>
 *
 * <pre>
 * SANS préchargement : le portail est franchi à froid, exactement comme vanilla le ferait avec
 *                       {@code core.PortalPreload} éteint (ou en créatif, délai de portail nul).
 * AVEC préchargement : une doublure se poste devant un AUTRE portail, jamais visité lui non plus,
 *                       {@link Settings#setPortalPreload(boolean)} est armé, et 200 ticks s'écoulent
 *                       — dix secondes réelles, largement au-dessus du délai de portail par défaut
 *                       de la survie (quatre-vingts ticks) et du débit de génération mesuré par
 *                       {@code core.Pregen} (11 à 13 chunks/s : le ticket de rayon trois,
 *                       quarante-neuf chunks, a le temps de finir). LA MÊME traversée est ensuite
 *                       déclenchée, avec le même appel exact.
 * </pre>
 *
 * <p>Chaque bras vise des coordonnées <b>différentes et jamais visitées</b> — vérifié avant le
 * premier relevé contre les fichiers de région du monde réel chargé par ce banc (voir
 * {@code run-seuil/world}, une copie du monde importé, pas un monde vide) : recoupé jusqu'à x = ±12
 * régions, z = ±10 côté Surmonde, x = ±3, z = ±3 côté Nether. Les deux bras de cette épreuve visent
 * x = ±50 000 — trois à quatre ordres de grandeur au-delà de ce qui a jamais été chargé, aussi loin
 * du spawn que possible sans dépasser la bordure du monde (29 999 984), de sorte qu'aucun ne puisse
 * hériter, même partiellement, d'un chunk déjà généré.
 *
 * <h2>Ce que ce banc ne mesure pas</h2>
 *
 * <p>La marche d'approche du joueur vers le portail n'est pas chronométrée : la doublure est posée
 * directement dans le portail, et sa propre arrivée sur place (chargement de la zone source) est
 * attendue et purgée du chrono avant que la traversée ne commence — sans quoi le coût de « se rendre
 * au portail » se mélangerait à celui de « le franchir ». Et le transit lui-même passe par
 * {@code ServerPlayer.teleportTo(ServerLevel, …)} — la même méthode déjà éprouvée par {@code Seuil}
 * — plutôt que par {@code Entity.teleport(TeleportTransition)} : la <b>destination</b> vient du vrai
 * appel vanilla ({@code Portal.getPortalDestination}), seul le déplacement de l'entité emprunte un
 * chemin déjà validé par ce dépôt plutôt qu'un second chemin jamais éprouvé ici. La différence est le
 * post-traitement du portail ({@code TeleportTransition.PLACE_PORTAL_TICKET}, un ticket de rayon
 * trois) : sans effet mesurable ici, puisque ce banc pose lui-même une doublure et compte ses chunks
 * directement.
 *
 * <h2>Le verdict, trois relevés sur le monde réel de ce dépôt</h2>
 *
 * <pre>
 * relevé 1 : SANS 6662 ms (recherche 1773 ms) · AVEC 4840 ms (recherche 0,5 ms)   ×1,38  -27 %
 * relevé 2 : SANS 1631 ms (recherche  129 ms) · AVEC 1633 ms (recherche 0,6 ms)   neutre, sous le bruit
 * relevé 3 : SANS 1896 ms (recherche  146 ms) · AVEC 1524 ms (recherche 0,3 ms)   ×1,24  -20 %
 * </pre>
 *
 * <p>La recherche de destination s'effondre à chaque fois, sans exception : le second appel à
 * {@code getPortalDestination} retrouve le portail que le préchargement a déjà créé au lieu de
 * relancer {@code PortalForcer.createPortal}. C'est le poste le plus coûteux et le plus irrégulier du
 * bras SANS (de 129 à 1773 ms selon ce qui traînait déjà en mémoire de la JVM), et le préchargement
 * le rend intégralement prévisible. La livraison du reste de la vue, elle, ne gagne rien de fiable —
 * le rayon du ticket de {@code core.PortalPreload} reste volontairement celui de vanilla ; voir son
 * javadoc pour l'essai à rayon cinq qui a perdu.
 */
public final class Traversee {
    /** Coordonnées du bras SANS préchargement — jamais visitées, loin de tout. */
    private static final int COLD_X = 50_000;
    private static final int COLD_Z = 50_000;
    /** Coordonnées du bras AVEC préchargement — différentes, pour n'hériter de rien du premier bras. */
    private static final int WARM_X = 50_000;
    private static final int WARM_Z = -50_000;

    /**
     * Ticks d'attente après avoir armé le préchargement.
     *
     * <p>Compté en tickS de serveur écoulés, pas en secondes réelles : si le serveur prend du retard
     * pendant l'attente, davantage de temps réel s'écoule avant la traversée, ce qui ne peut que
     * laisser plus de marge au chargement — jamais moins. Trois cents ticks couvrent large le rayon
     * cinq du ticket (cent vingt et un chunks, le même compte que la livraison mesurée).
     */
    private static final int PRELOAD_WAIT_TICKS = 300;

    /** Sous ce nombre de chunks livrés, le relevé n'a rien mesuré. Voir {@code lab.Seuil}. */
    private static final long LEAST_CHUNKS = 32L;
    /** Ticks sans nouveau chunk après lesquels une livraison est considérée finie. */
    private static final int QUIET_TICKS = 200;
    /** Patience maximale par attente de livraison, en ticks. Dix minutes. */
    private static final int PATIENCE_TICKS = 12_000;

    private enum Etape {
        ARRET,
        COLD_PLACE, COLD_SETTLE, COLD_CROSS, COLD_STREAM,
        WARM_PLACE, WARM_SETTLE, WARM_PRELOAD_WAIT, WARM_CROSS, WARM_STREAM,
        FINI
    }

    private Traversee() {}

    private static Etape etape = Etape.ARRET;
    private static int doublureIndex;
    private static int expectedChunks;
    private static int preloadCountdown;

    // --- état de la sous-attente de livraison (réutilisé par les quatre phases qui attendent) ---
    private static long baseline;
    private static long waitStartNanos;
    private static long lastSeenChunks;
    private static int quiet;
    private static int patience;

    // --- résultats ---
    private static double coldSearchMs;
    private static double coldTeleportMs;
    private static double coldStreamMs;
    private static long coldChunks;
    private static double warmSearchMs;
    private static double warmTeleportMs;
    private static double warmStreamMs;
    private static long warmChunks;

    public static boolean running() {
        return etape != Etape.ARRET && etape != Etape.FINI;
    }

    public static void begin(MinecraftServer server) {
        Settings.setPortalPreload(false);
        Understudy.answerChunkBatches(true);
        Understudy.announceViewDistance(server.getPlayerList().getViewDistance());
        expectedChunks = expected(server);
        Understudy.leave(server);

        Lanterne.LOG.info("[TRAVERSÉE] Épreuve armée : {} chunk(s) attendus par traversée, "
                + "bras SANS à x={} z={}, bras AVEC à x={} z={}.",
                expectedChunks, COLD_X, COLD_Z, WARM_X, WARM_Z);

        etape = Etape.COLD_PLACE;
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel overworld = server.overworld();

        switch (etape) {
            case COLD_PLACE -> {
                int idx = Understudy.addOne(server, overworld, COLD_X, COLD_Z);
                if (idx < 0) {
                    Lanterne.LOG.error("[TRAVERSÉE] REFUS DE CONCLURE : le serveur a refusé la "
                            + "doublure du bras SANS.");
                    terminer(server);
                    return;
                }
                doublureIndex = idx;
                beginWait();
                etape = Etape.COLD_SETTLE;
            }
            case COLD_SETTLE -> {
                if (!waitDelivered(server)) {
                    return;
                }
                // Le portail est posé aux pieds de la doublure, une fois sa zone d'origine chargée —
                // pas avant, sans quoi le placement forcerait lui-même les chunks qu'on veut mesurer.
                BlockPos portalPos = placePortal(overworld, doublureIndex);
                crossPortal(server, overworld, portalPos, true);
                beginWait();
                etape = Etape.COLD_STREAM;
            }
            case COLD_STREAM -> {
                if (!waitDelivered(server)) {
                    return;
                }
                coldStreamMs = elapsedWaitMs();
                coldChunks = delivered();
                Lanterne.LOG.info(String.format(Locale.ROOT,
                        "[TRAVERSÉE] SANS préchargement — recherche %.1f ms, téléportation %.1f ms, "
                        + "livraison %.1f ms pour %d chunk(s).",
                        coldSearchMs, coldTeleportMs, coldStreamMs, coldChunks));
                Understudy.leave(server);
                etape = Etape.WARM_PLACE;
            }
            case WARM_PLACE -> {
                int idx = Understudy.addOne(server, overworld, WARM_X, WARM_Z);
                if (idx < 0) {
                    Lanterne.LOG.error("[TRAVERSÉE] REFUS DE CONCLURE : le serveur a refusé la "
                            + "doublure du bras AVEC.");
                    terminer(server);
                    return;
                }
                doublureIndex = idx;
                beginWait();
                etape = Etape.WARM_SETTLE;
            }
            case WARM_SETTLE -> {
                if (!waitDelivered(server)) {
                    return;
                }
                BlockPos portalPos = placePortal(overworld, doublureIndex);
                warmPortalPos = portalPos;
                Settings.setPortalPreload(true);
                preloadCountdown = PRELOAD_WAIT_TICKS;
                Lanterne.LOG.info("[TRAVERSÉE] préchargement armé, doublure postée devant un portail "
                        + "à x={} z={} — {} ticks d'attente avant la vraie traversée.",
                        portalPos.getX(), portalPos.getZ(), PRELOAD_WAIT_TICKS);
                etape = Etape.WARM_PRELOAD_WAIT;
            }
            case WARM_PRELOAD_WAIT -> {
                if (--preloadCountdown > 0) {
                    return;
                }
                crossPortal(server, overworld, warmPortalPos, false);
                beginWait();
                etape = Etape.WARM_STREAM;
            }
            case WARM_STREAM -> {
                if (!waitDelivered(server)) {
                    return;
                }
                warmStreamMs = elapsedWaitMs();
                warmChunks = delivered();
                Lanterne.LOG.info(String.format(Locale.ROOT,
                        "[TRAVERSÉE] AVEC préchargement — recherche %.1f ms, téléportation %.1f ms, "
                        + "livraison %.1f ms pour %d chunk(s).",
                        warmSearchMs, warmTeleportMs, warmStreamMs, warmChunks));
                rapporter();
                terminer(server);
            }
            default -> { }
        }
    }

    private static BlockPos warmPortalPos;

    /** Pose un portail du Nether, sans cadre, aux pieds de la doublure désignée. */
    private static BlockPos placePortal(ServerLevel overworld, int index) {
        ServerPlayer player = Understudy.actor(index);
        BlockPos base = player.blockPosition();
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState()
                .setValue(NetherPortalBlock.AXIS, Direction.Axis.X);
        int flags = Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE;
        for (int dy = 0; dy < 3; dy++) {
            for (int dx = 0; dx < 2; dx++) {
                overworld.setBlock(base.offset(dx, dy, 0), portal, flags);
            }
        }
        return base;
    }

    /**
     * Le vrai transit : le même appel que vanilla ferait au moment du franchissement — pas une
     * réimplémentation de la recherche de destination.
     */
    private static void crossPortal(MinecraftServer server, ServerLevel overworld, BlockPos portalPos,
            boolean cold) {
        ServerPlayer player = Understudy.actor(doublureIndex);
        Portal portalBlock = (Portal) Blocks.NETHER_PORTAL;

        long t0 = System.nanoTime();
        TeleportTransition transition = portalBlock.getPortalDestination(overworld, player, portalPos);
        long t1 = System.nanoTime();

        if (transition == null) {
            Lanterne.LOG.error("[TRAVERSÉE] REFUS DE CONCLURE : getPortalDestination a rendu null "
                    + "(pas de Nether sur ce serveur, ou bordure du monde atteinte ?).");
            terminer(server);
            return;
        }

        player.teleportTo(transition.newLevel(), transition.position().x, transition.position().y,
                transition.position().z, transition.relatives(), transition.yRot(), transition.xRot(),
                false);
        long t2 = System.nanoTime();

        double searchMs = (t1 - t0) / 1e6d;
        double teleportMs = (t2 - t1) / 1e6d;
        if (cold) {
            coldSearchMs = searchMs;
            coldTeleportMs = teleportMs;
        } else {
            warmSearchMs = searchMs;
            warmTeleportMs = teleportMs;
        }
    }

    // ────────────────────────────── l'attente de livraison ──────────────────────────────

    private static void beginWait() {
        baseline = Understudy.chunksDeliveredTo(doublureIndex);
        waitStartNanos = System.nanoTime();
        lastSeenChunks = 0L;
        quiet = 0;
        patience = 0;
    }

    /** Rend vrai une fois que la livraison est finie (ou abandonnée). Faux tant qu'il faut attendre. */
    private static boolean waitDelivered(MinecraftServer server) {
        long delivered = Understudy.chunksDeliveredTo(doublureIndex) - baseline;
        if (delivered != lastSeenChunks) {
            lastSeenChunks = delivered;
            quiet = 0;
        } else {
            quiet++;
        }
        patience++;

        boolean complete = delivered >= expectedChunks;
        boolean starved = delivered > 0L && quiet >= QUIET_TICKS;
        boolean exhausted = patience >= PATIENCE_TICKS;

        if (!complete && !starved && !exhausted) {
            return false;
        }
        if (exhausted && !complete) {
            Lanterne.LOG.warn("[TRAVERSÉE] patience épuisée : {} chunk(s) sur {} attendus. Le "
                    + "chiffre qui suit est un plancher, pas une livraison complète.",
                    delivered, expectedChunks);
        }
        return true;
    }

    private static long delivered() {
        return Understudy.chunksDeliveredTo(doublureIndex) - baseline;
    }

    private static double elapsedWaitMs() {
        return (System.nanoTime() - waitStartNanos) / 1e6d;
    }

    private static int expected(MinecraftServer server) {
        int view = server.getPlayerList().getViewDistance();
        int side = view * 2 + 1;
        return side * side;
    }

    // ────────────────────────────── le verdict ──────────────────────────────

    private static void rapporter() {
        if (coldChunks < LEAST_CHUNKS || warmChunks < LEAST_CHUNKS) {
            Lanterne.LOG.error("[TRAVERSÉE] REFUS DE CONCLURE : {} chunk(s) sans et {} avec, sous le "
                    + "seuil de {}. Un instrument qui ne mesure pas doit se taire.",
                    coldChunks, warmChunks, LEAST_CHUNKS);
            return;
        }

        double coldTotal = coldSearchMs + coldTeleportMs + coldStreamMs;
        double warmTotal = warmSearchMs + warmTeleportMs + warmStreamMs;
        double ratio = warmTotal <= 0d ? 0d : coldTotal / warmTotal;

        Lanterne.LOG.info("[TRAVERSÉE] ─────────────────────────────────────────────────────────");
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[TRAVERSÉE] SANS : recherche %.1f ms + téléportation %.1f ms + livraison %.1f ms "
                + "= %.1f ms pour %d chunk(s).",
                coldSearchMs, coldTeleportMs, coldStreamMs, coldTotal, coldChunks));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[TRAVERSÉE] AVEC : recherche %.1f ms + téléportation %.1f ms + livraison %.1f ms "
                + "= %.1f ms pour %d chunk(s).",
                warmSearchMs, warmTeleportMs, warmStreamMs, warmTotal, warmChunks));

        if (ratio > 1.05d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[TRAVERSÉE] VERDICT : gain ×%.2f, soit %.0f %% de temps de coupure en moins "
                    + "pour franchir un portail vers une destination jamais visitée.",
                    ratio, (1d - warmTotal / coldTotal) * 100d));
        } else if (ratio < 0.95d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[TRAVERSÉE] VERDICT : PERTE ×%.2f — le préchargement coûte plus qu'il ne "
                    + "rapporte sur cette charge. À laisser éteint.", ratio));
        } else {
            Lanterne.LOG.info("[TRAVERSÉE] VERDICT : aucun effet mesurable — l'écart est sous le "
                    + "bruit de fond.");
        }
        Lanterne.LOG.info("[TRAVERSÉE] ─────────────────────────────────────────────────────────");
    }

    private static void terminer(MinecraftServer server) {
        Settings.setPortalPreload(false);
        Understudy.leave(server);
        etape = Etape.FINI;
        server.halt(false);
    }
}
