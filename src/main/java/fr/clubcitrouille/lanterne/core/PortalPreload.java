package fr.clubcitrouille.lanterne.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Précharge les chunks d'arrivée d'un portail, avant même que le joueur ne le franchisse.
 *
 * <h2>Ce que ça corrige, et ce que ça ne corrige pas</h2>
 *
 * <p>Ceci n'est PAS un rendu à travers le portail — voir un autre chantier à part si un jour on veut
 * cela. Ici, on s'attaque à autre chose : le temps mort au moment du franchissement, pendant lequel
 * vanilla charge les chunks de l'autre dimension <em>après</em> la téléportation, à froid.
 *
 * <p>{@code core/Emballage.java} a déjà mesuré ce que coûte une arrivée : quatre cent quarante-quatre
 * chunks pèsent 2197 ms à froid (lire le disque puis emballer le paquet réseau) contre 939 ms à chaud
 * (chunks déjà en mémoire, emballage seul) — quarante-trois pour cent du coût est de l'emballage pur,
 * repayé à chaque changement de dimension. Ce module vise justement à faire en sorte que l'arrivée
 * dans l'autre dimension trouve les chunks déjà chauds : le vrai gain de temps ne vient pas d'ici,
 * mais du fait qu'{@code Emballage} peut alors faire son travail bien avant que le joueur n'arrive.
 *
 * <h2>Le mécanisme : le même ticket que vanilla pose après coup, posé avant</h2>
 *
 * <p>{@code Entity.placePortalTicket} pose, une fois arrivé, un {@code TicketType.PORTAL} de rayon
 * trois autour du point d'arrivée — vanilla le fait déjà, seulement après. Ce ticket expire de
 * lui-même au bout de trois cents ticks ({@code TicketType.PORTAL} l'enregistre ainsi) : pas besoin
 * de le retirer nous-mêmes, il n'y a donc aucune fuite possible à gérer.
 *
 * <p>Le point d'arrivée est calculé par {@code Portal.getPortalDestination}, la même méthode que
 * vanilla appelle au moment réel du transit — pas une réimplémentation approximative de la recherche
 * de portail correspondant, mais l'appel exact que ferait le jeu, seulement un peu plus tôt. Un joueur
 * qui approche d'un portail (jusqu'à trois blocs, sans même y être entré) déclenche donc, toutes les
 * dix ticks, le calcul de sa destination et la pose du ticket là-bas.
 *
 * <h2>Pourquoi la marge existe, et pourquoi elle ne suffit pas toujours</h2>
 *
 * <p>Un portail du Nether impose, en survie, un délai avant le transit —
 * {@code GameRules.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY}, quatre-vingts ticks par défaut — pendant
 * lequel le joueur reste debout dans le portail. Combiné à l'approche à trois blocs, cela laisse
 * largement le temps au préchargement de finir avant le vrai transit. En créatif, ce délai tombe
 * près de zéro ({@code PLAYERS_NETHER_PORTAL_CREATIVE_DELAY}) : le préchargement n'a alors que le
 * temps de la marche d'approche, ce qui peut ne pas suffire sur un point d'arrivée jamais visité —
 * dans ce cas, on retombe simplement sur le chargement vanilla habituel, jamais sur un blocage.
 *
 * <h2>Ce qui n'est vérifiable qu'en jouant</h2>
 *
 * <p>Tout ce qui précède est vérifié contre les sources décompilées de la 26.3 et compile. Ce qui ne
 * l'est PAS, faute de client lancé dans ce laboratoire : que le préchargement ait réellement terminé
 * à temps pour un joueur donné, et le ressenti de fluidité lui-même. À éprouver en jeu.
 */
public final class PortalPreload {
    /** Rayon de détection d'un portail proche, en blocs. */
    private static final int SCAN_RADIUS = 3;
    /** Ticks entre deux passages de détection — inutile de scruter chaque tick. */
    private static final int SCAN_INTERVAL = 10;
    /** Rayon du ticket posé autour du point d'arrivée — identique à celui de vanilla. */
    private static final int TICKET_RADIUS = 3;

    /** Le dernier portail préchargé par joueur, pour ne pas recalculer à chaque passage. */
    private static final Map<UUID, BlockPos> lastPreloaded = new HashMap<>();

    private static int tickCounter;

    private PortalPreload() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(PortalPreload.class);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % SCAN_INTERVAL != 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            scanNear(player);
        }
    }

    /** Cherche un portail dans le voisinage immédiat du joueur, et déclenche son préchargement. */
    private static void scanNear(ServerPlayer player) {
        ServerLevel level = player.level();
        BlockPos center = player.blockPosition();
        BlockPos from = center.offset(-SCAN_RADIUS, -SCAN_RADIUS, -SCAN_RADIUS);
        BlockPos to = center.offset(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS);
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof Portal portal) {
                preload(player, level, portal, pos.immutable());
                return; // un portail détecté suffit pour ce passage
            }
        }
    }

    /**
     * Calcule la vraie destination du portail trouvé, et y pose le ticket de chargement.
     *
     * <p>{@code lastPreloaded} évite de refaire ce calcul à chaque passage tant que le joueur reste
     * près du même portail — {@code getPortalDestination} peut chercher ou créer un portail lié côté
     * destination, ce n'est pas un appel gratuit.
     */
    private static void preload(ServerPlayer player, ServerLevel level, Portal portal, BlockPos portalPos) {
        if (portalPos.equals(lastPreloaded.get(player.getUUID()))) {
            return;
        }
        TeleportTransition transition = portal.getPortalDestination(level, player, portalPos);
        if (transition == null) {
            return;
        }
        lastPreloaded.put(player.getUUID(), portalPos);
        BlockPos arrival = BlockPos.containing(transition.position());
        transition.newLevel().getChunkSource()
                .addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(arrival), TICKET_RADIUS);
    }
}
