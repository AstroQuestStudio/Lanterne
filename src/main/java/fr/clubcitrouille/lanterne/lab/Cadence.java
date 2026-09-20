package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.flowpowered.math.vector.Vector2i;

import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.RenderManager;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Coordonne deux gros consommateurs de CPU serveur — la pré-génération ({@link Pregen}) et le
 * rendu complet de BlueMap — avec la présence d'un vrai joueur, demande explicite du patron le
 * 20/09/2026.
 *
 * <h2>Pré-génération : déjà géré, rien à refaire</h2>
 *
 * <p>{@link Pregen#suspended} met déjà en pause automatiquement à la connexion d'un joueur et
 * reprend au départ du dernier — voir sa Javadoc de classe. Cette classe-ci n'ajoute que ce qui
 * manquait vraiment : la reprise après un CRASH/redémarrage (une pause volontaire reste en
 * mémoire, un redémarrage la perd) — voir {@link Pregen#resumeIfPending}, appelée ici au
 * démarrage du monde principal.
 *
 * <h2>BlueMap : pause du rendu complet, mise à jour ciblée autour des joueurs</h2>
 *
 * <p>{@code RenderManager.stop()}/{@code start()} (API Java de BlueMap, {@code BlueMapAPI.getInstance()}
 * — vérifié présent sur le vrai jar {@code bluemap-5.27-neoforge.jar} du VPS, pas une supposition)
 * coupe/reprend le rendu de fond. Mais couper entièrement priverait un joueur connecté de mises à
 * jour de la carte autour de lui pendant potentiellement des heures. On coupe donc le rendu
 * GÉNÉRAL, et on planifie à la place, toutes les {@link #UPDATE_PERIOD_TICKS} ticks (5 minutes),
 * une mise à jour CIBLÉE des tuiles autour de chaque joueur connecté —
 * {@code RenderManager.scheduleMapUpdateTask(BlueMapMap, Collection&lt;Vector2i&gt;, boolean)},
 * les tuiles étant calculées via {@code BlueMapMap.posToTile(x, z)} sur un carré autour du
 * joueur, jamais un rayon supposé en blocs ou en chunks — l'API attend des coordonnées de tuile.
 *
 * <p>Optionnel par construction ({@code BlueMapAPI.getInstance()} renvoie un {@code Optional}) :
 * un serveur sans BlueMap installé ne voit jamais s'exécuter une ligne de cette classe qui le
 * concerne.
 */
public final class Cadence {
    /** Rayon, en tuiles BlueMap, mis à jour autour d'un joueur connecté. */
    private static final int TILE_RADIUS = 6;

    /** Cinq minutes à 20 ticks/s — voir la demande du patron : « max 5 min actualisé ». */
    private static final int UPDATE_PERIOD_TICKS = 20 * 60 * 5;

    private static int ticksSinceUpdate;
    private static boolean fullRenderPaused;

    private Cadence() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(Cadence.class);
    }

    /** Reprend une pré-génération interrompue par un crash, une fois par démarrage du monde. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        Pregen.resumeIfPending(event.getServer().overworld());
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        // Pregen se met en pause tout seul (voir Javadoc de classe) — seul BlueMap a besoin d'un
        // geste explicite ici, la logique de pause ne lui appartenant pas.
        BlueMapAPI.getInstance().ifPresent(api -> {
            RenderManager manager = api.getRenderManager();
            if (manager.isRunning()) {
                manager.stop();
                fullRenderPaused = true;
                Lanterne.LOG.info("[CADENCE] rendu complet BlueMap mis en pause — un joueur est "
                        + "connecté. La carte autour de lui continuera d'être mise à jour toutes "
                        + "les 5 minutes.");
            }
        });
        ticksSinceUpdate = UPDATE_PERIOD_TICKS; // Force une mise à jour immédiate à la connexion.
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!event.getEntity().level().getServer().getPlayerList().getPlayers().isEmpty()) {
            return; // Il reste au moins un joueur : rien ne change.
        }
        if (!fullRenderPaused) {
            return;
        }
        BlueMapAPI.getInstance().ifPresent(api -> {
            api.getRenderManager().start();
            fullRenderPaused = false;
            Lanterne.LOG.info("[CADENCE] plus aucun joueur connecté — rendu complet BlueMap repris.");
        });
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        List<ServerPlayer> players = event.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        if (++ticksSinceUpdate < UPDATE_PERIOD_TICKS) {
            return;
        }
        ticksSinceUpdate = 0;
        Optional<BlueMapAPI> api = BlueMapAPI.getInstance();
        if (api.isEmpty()) {
            return;
        }
        for (ServerPlayer player : players) {
            updateAround(api.get(), player);
        }
    }

    /** Planifie une mise à jour des tuiles BlueMap autour d'un joueur, sur chacune de ses cartes. */
    private static void updateAround(BlueMapAPI api, ServerPlayer player) {
        api.getWorld(player.level().dimension().identifier()).ifPresent(world -> {
            double x = player.getX();
            double z = player.getZ();
            for (BlueMapMap map : world.getMaps()) {
                Vector2i center = map.posToTile(x, z);
                List<Vector2i> tiles = new ArrayList<>();
                for (int dx = -TILE_RADIUS; dx <= TILE_RADIUS; dx++) {
                    for (int dz = -TILE_RADIUS; dz <= TILE_RADIUS; dz++) {
                        tiles.add(new Vector2i(center.getX() + dx, center.getY() + dz));
                    }
                }
                api.getRenderManager().scheduleMapUpdateTask(map, tiles, false);
            }
        });
    }
}
