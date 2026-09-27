package fr.clubcitrouille.lanterne.lab;

import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import fr.clubcitrouille.lanterne.report.Compagnons;

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
 * <h2>BlueMap : tout le vrai travail est dans {@link CadenceBlueMap}</h2>
 *
 * <p>Cette classe-ci ne mentionne AUCUN type BlueMap dans sa propre signature — voir la Javadoc de
 * {@link CadenceBlueMap} pour pourquoi c'est nécessaire, pas juste prudent : un crash réel a été
 * vécu le 21/09/2026 sur un serveur sans BlueMap, la classe précédente (qui référençait
 * {@code BlueMapAPI} dans une méthode privée jamais appelée dans ce cas) suffisait à planter tout
 * Lanterne au chargement de classe. Chaque appel ici passe par {@link Compagnons#present} d'abord.
 */
public final class Cadence {
    /** Cinq minutes à 20 ticks/s — voir la demande du patron : « max 5 min actualisé ». */
    private static final int UPDATE_PERIOD_TICKS = 20 * 60 * 5;

    private static int ticksSinceUpdate;
    private static boolean fullRenderPaused;

    private Cadence() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(Cadence.class);
        // Enregistre le callback tout de suite (pas besoin d'attendre ServerStartedEvent) :
        // BlueMapAPI.onEnable stocke le consumer et l'appelle des que le plugin devient pret, quel
        // que soit le moment. Voir la Javadoc de CadenceBlueMap#startOnReady.
        if (Compagnons.present(Compagnons.BLUEMAP)) {
            CadenceBlueMap.startOnReady(Cadence::aucunJoueurConnecte);
        }
    }

    private static boolean aucunJoueurConnecte() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null || server.getPlayerList().getPlayers().isEmpty();
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
        // Depuis la 26.3.154, la connexion ne met plus le rendu en pause : le régulateur de marge
        // ({@link #reguler}) le fait seulement si le serveur en a besoin.
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
        if (Compagnons.present(Compagnons.BLUEMAP)) {
            CadenceBlueMap.resumeFullRender();
        }
        fullRenderPaused = false;
    }

    /** Tick moyen (ms) au-delà duquel le rendu BlueMap s'arrête, et en deçà duquel il reprend. */
    private static final double SEUIL_PAUSE_MS = 35.0;
    private static final double SEUIL_REPRISE_MS = 25.0;

    /**
     * Le régulateur de marge (demande du 27/09) : BlueMap rend la carte en direct, joueurs connectés
     * ou non, tant que le serveur a de la marge ; dès que le tick moyen dépasse
     * {@link #SEUIL_PAUSE_MS}, ses fils de rendu s'arrêtent pour rendre le processeur au jeu, et
     * repartent sous {@link #SEUIL_REPRISE_MS}. L'écart entre les deux seuils évite de basculer à
     * chaque relevé. Une fois par minute : lire un nombre ne coûte rien, mais inutile de le faire plus. La priorité des fils (réglage de BlueMap) ne sert à rien sous Linux sans droits
     * particuliers : c'est donc ici, au niveau du jeu, que la décision se prend.
     */
    private static void reguler(net.minecraft.server.MinecraftServer server) {
        double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
        if (!fullRenderPaused && mspt > SEUIL_PAUSE_MS) {
            fullRenderPaused = CadenceBlueMap.pauseFullRender();
            if (fullRenderPaused) {
                fr.clubcitrouille.lanterne.Lanterne.LOG.info("[CADENCE] tick moyen {} ms : rendu BlueMap en pause.",
                        String.format("%.1f", mspt));
            }
        } else if (fullRenderPaused && mspt < SEUIL_REPRISE_MS) {
            CadenceBlueMap.resumeFullRender();
            fullRenderPaused = false;
            fr.clubcitrouille.lanterne.Lanterne.LOG.info("[CADENCE] tick moyen {} ms : rendu BlueMap relancé.",
                    String.format("%.1f", mspt));
        }
    }

    /** Ticks avant le rendu complet demandé par la fin d'une pré-génération ; -1 = rien à faire. */
    private static int fullRenderCountdown = -1;

    /**
     * La pré-génération vient de finir tout son plan : BlueMap doit maintenant avoir TOUT le
     * territoire. Recharge BlueMap (une dimension sans région au démarrage — l'End, le Nether —
     * n'avait pas de carte et en obtient une maintenant), puis programme la mise à jour de toutes
     * les cartes une fois le rechargement fini.
     */
    public static void onPregenFinished(net.minecraft.server.MinecraftServer server) {
        if (!Compagnons.present(Compagnons.BLUEMAP)) {
            return;
        }
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "bluemap reload");
        fullRenderCountdown = 20 * 30;
        fr.clubcitrouille.lanterne.Lanterne.LOG.info("[CADENCE] pré-génération terminée : BlueMap rechargé, rendu complet dans 30 s.");
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!Compagnons.present(Compagnons.BLUEMAP)) {
            return;
        }
        if (event.getServer().getTickCount() % 1200 == 0) {
            reguler(event.getServer());
        }
        if (fullRenderCountdown > 0 && --fullRenderCountdown == 0) {
            fullRenderCountdown = -1;
            CadenceBlueMap.renderEverything(event.getServer().getPlayerList().getPlayers().isEmpty());
        }
        java.util.List<ServerPlayer> players = event.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        if (++ticksSinceUpdate < UPDATE_PERIOD_TICKS) {
            return;
        }
        ticksSinceUpdate = 0;
        CadenceBlueMap.updateAroundPlayers(players);
    }
}
