package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.flowpowered.math.vector.Vector2i;

import net.minecraft.server.level.ServerPlayer;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.RenderManager;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Tout le code touchant réellement les types BlueMap ({@link BlueMapAPI}, {@link BlueMapMap},
 * {@link RenderManager}), isolé ici et nulle part ailleurs.
 *
 * <h2>Pourquoi cette classe existe séparément de {@link Cadence}</h2>
 *
 * <p>La JVM résout — et donc charge — TOUS les types référencés dans la signature des méthodes
 * d'une classe (paramètres, champs, imports utilisés dans le bytecode) dès que cette classe est
 * chargée, même si la méthode en question n'est jamais appelée. Un simple {@code if
 * (Compagnons.present(...))} AUTOUR d'un appel ne suffit donc pas si la classe appelante a
 * elle-même une méthode dont un paramètre est de type {@code BlueMapAPI} — charger CETTE classe
 * suffit à faire planter le démarrage sur {@code ClassNotFoundException} si BlueMap est absent.
 * Vécu en vrai le 21/09/2026 sur un serveur de test jetable sans BlueMap : {@code Cadence} plantait
 * tout Lanterne au démarrage à cause de sa méthode privée {@code updateAround(BlueMapAPI, ...)},
 * jamais appelée dans ce cas mais quand même résolue au chargement de la classe.
 *
 * <p>Solution standard en Java pour une dépendance optionnelle : aucune classe qui N'A PAS déjà
 * vérifié la présence du mod ne doit référencer un type de ce mod dans sa propre signature. Cette
 * classe-ci n'est donc appelée par {@link Cadence} qu'après un {@code Compagnons.present(BLUEMAP)}
 * vérifié — {@code Cadence} lui-même ne mentionne plus aucun type BlueMap nulle part.
 */
final class CadenceBlueMap {
    /** Rayon, en tuiles BlueMap, mis à jour autour d'un joueur connecté. */
    private static final int TILE_RADIUS = 6;

    private CadenceBlueMap() {}

    /** {@code true} si le rendu complet a bien été trouvé actif et mis en pause. */
    static boolean pauseFullRender() {
        Optional<BlueMapAPI> api = BlueMapAPI.getInstance();
        if (api.isEmpty()) {
            return false;
        }
        RenderManager manager = api.get().getRenderManager();
        if (!manager.isRunning()) {
            return false;
        }
        manager.stop();
        Lanterne.LOG.info("[CADENCE] rendu complet BlueMap mis en pause — un joueur est connecté. "
                + "La carte autour de lui continuera d'être mise à jour toutes les 5 minutes.");
        return true;
    }

    static void resumeFullRender() {
        BlueMapAPI.getInstance().ifPresent(api -> {
            api.getRenderManager().start();
            Lanterne.LOG.info("[CADENCE] plus aucun joueur connecté — rendu complet BlueMap repris.");
        });
    }

    /**
     * Démarre le rendu complet dès que BlueMap devient prêt, si 0 joueur n'est déjà connecté.
     *
     * <p>Signalé par le patron le 21/09/2026 : après un redémarrage du serveur, BlueMap restait
     * indéfiniment sur « Render-Threads are STOPPED » tant que personne ne se connectait PUIS ne se
     * déconnectait — {@link #resumeFullRender()} n'était jamais appelée au démarrage lui-même,
     * seulement sur la transition « dernier joueur part ». {@link BlueMapAPI#onEnable} est le
     * point d'accroche correct : le plugin BlueMap charge ses ressources et son stockage de façon
     * asynchrone après {@code ServerStartedEvent} (constaté dans les logs — « Loaded! » arrive
     * plusieurs secondes après « Done »), donc appeler {@link #resumeFullRender()} directement à
     * {@code ServerStartedEvent} risquait fortement de tomber sur {@link BlueMapAPI#getInstance()}
     * encore vide et de ne rien faire silencieusement.
     */
    static void startOnReady(java.util.function.Supplier<Boolean> aucunJoueurConnecte) {
        BlueMapAPI.onEnable(api -> {
            if (!Boolean.TRUE.equals(aucunJoueurConnecte.get())) {
                return; // Un joueur s'est deja connecte avant que BlueMap ne soit pret : Cadence
                        // gere deja ce cas via onPlayerJoin, ne pas demarrer par-dessus.
            }
            api.getRenderManager().start();
            Lanterne.LOG.info("[CADENCE] BlueMap prêt, 0 joueur connecté — rendu complet démarré "
                    + "automatiquement (plus besoin d'attendre une connexion/déconnexion).");
        });
    }

    static void updateAroundPlayers(List<ServerPlayer> players) {
        BlueMapAPI.getInstance().ifPresent(api -> {
            for (ServerPlayer player : players) {
                updateAround(api, player);
            }
        });
    }

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
