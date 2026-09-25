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

    /**
     * Reprend le rendu complet — et surtout, reprogramme un balayage de toutes les cartes avant de
     * le faire.
     *
     * <h2>Le trou qui laissait des chantiers invisibles indéfiniment</h2>
     *
     * <p>{@code manager.start()} seul ne fait que relancer la file de rendu EXISTANTE. Pendant une
     * session, seule {@link #updateAroundPlayers} programme des mises à jour — et seulement dans un
     * rayon de {@value #TILE_RADIUS} tuiles autour de chaque joueur connecté, en suivi live. Tout ce
     * qui a été modifié ou nouvellement exploré HORS de ce rayon — un chantier de l'AutoMiner loin
     * du joueur, une zone traversée puis quittée avant que le suivi ne l'atteigne — n'était jamais
     * reprogrammé : {@code start()} relançait une file vide pour ces régions-là, qui restaient donc
     * invisibles sur la carte jusqu'au prochain redémarrage complet du serveur (seul autre point
     * d'appel de {@link #renderEverything}).
     *
     * <p>On reprogramme donc un balayage complet — non forcé, {@code scheduleMapUpdateTask(map,
     * false)} : BlueMap compare ses propres horodatages de région et ne refait que ce qui a
     * réellement changé, exactement comme {@link #renderEverything} le fait déjà après la
     * pré-génération. Le coût d'un balayage sur une carte à jour est donc nul ; son absence, elle,
     * coûtait des chantiers entiers jamais référencés.
     */
    static void resumeFullRender() {
        BlueMapAPI.getInstance().ifPresent(api -> {
            for (BlueMapMap map : api.getMaps()) {
                api.getRenderManager().scheduleMapUpdateTask(map, false);
            }
            api.getRenderManager().start();
            Lanterne.LOG.info("[CADENCE] plus aucun joueur connecté — balayage complet reprogrammé, "
                    + "rendu repris.");
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
            // Comme resumeFullRender/renderEverything : programmer un balayage (non forcé) avant de
            // démarrer, sinon manager.start() ne fait que relancer une file VIDE — rien ne serait
            // jamais rendu tant qu'aucune autre mise à jour n'a été programmée ailleurs.
            for (BlueMapMap map : api.getMaps()) {
                api.getRenderManager().scheduleMapUpdateTask(map, false);
            }
            api.getRenderManager().start();
            Lanterne.LOG.info("[CADENCE] BlueMap prêt, 0 joueur connecté — balayage programmé, rendu "
                    + "complet démarré automatiquement (plus besoin d'attendre une connexion/déconnexion).");
        });
        demarrerFiletMinute(aucunJoueurConnecte);
    }

    /** Fil unique du filet minute, créé une seule fois par processus. */
    private static java.util.concurrent.ScheduledExecutorService filet;

    /**
     * Lance le filet minute sur un fil à part, PAS sur le tick serveur : avec
     * {@code pause-when-empty-seconds}, vanilla cesse d'émettre les événements de tick une minute
     * après le départ du dernier joueur (voir la Javadoc de {@link Pregen#keepServerAwake}) — un
     * compteur de ticks ne se déclencherait donc jamais dans le seul cas qui l'intéresse. BlueMap
     * lit les régions sur disque avec ses propres fils et n'a pas besoin que le serveur tique ; son
     * {@link RenderManager} est sûr à appeler depuis un autre fil.
     */
    private static synchronized void demarrerFiletMinute(
            java.util.function.Supplier<Boolean> aucunJoueurConnecte) {
        if (filet != null) {
            return;
        }
        filet = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Lanterne-Cadence-filet");
            t.setDaemon(true);
            return t;
        });
        filet.scheduleWithFixedDelay(() -> {
            try {
                if (net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer() == null) {
                    return; // arrêt en cours : ne rien relancer
                }
                ensureRenderingIfIdle(Boolean.TRUE.equals(aucunJoueurConnecte.get()));
            } catch (Throwable t) {
                Lanterne.LOG.warn("[CADENCE] vérification minute impossible : {}", t.toString());
            }
        }, 1, 1, java.util.concurrent.TimeUnit.MINUTES);
    }

    /**
     * Filet périodique (une fois par minute, voir {@link #demarrerFiletMinute}) : si personne n'est
     * connecté et qu'aucun rendu n'est déjà en cours, (re)programme un balayage non forcé de toutes
     * les cartes et redémarre le rendu. Couvre les cas où ni {@link #startOnReady} ni
     * {@link #resumeFullRender} n'ont pu s'exécuter au bon moment (BlueMap devenu prêt entre deux
     * vérifications, plugin rechargé, file épuisée sans que personne ne se soit reconnecté…), sans
     * jamais lancer une deuxième file par-dessus une déjà active.
     */
    static void ensureRenderingIfIdle(boolean aucunJoueurConnecte) {
        if (!aucunJoueurConnecte) {
            return;
        }
        BlueMapAPI.getInstance().ifPresentOrElse(api -> {
            RenderManager manager = api.getRenderManager();
            if (manager.isRunning()) {
                Lanterne.LOG.debug("[CADENCE] vérification minute : 0 joueur, rendu déjà en cours, "
                        + "rien à faire.");
                return;
            }
            for (BlueMapMap map : api.getMaps()) {
                manager.scheduleMapUpdateTask(map, false);
            }
            manager.start();
            Lanterne.LOG.info("[CADENCE] vérification minute : 0 joueur, rendu inactif — balayage "
                    + "reprogrammé et rendu relancé.");
        }, () -> Lanterne.LOG.debug("[CADENCE] vérification minute : BlueMap pas encore prêt."));
    }

    /**
     * Programme une mise à jour de TOUTES les cartes (rien de forcé : seules les régions modifiées
     * sont refaites), puis démarre le rendu si personne n'est connecté. Appelé une fois la
     * pré-génération terminée, pour que BlueMap ait tout le territoire généré.
     */
    static void renderEverything(boolean aucunJoueur) {
        BlueMapAPI.getInstance().ifPresent(api -> {
            int count = 0;
            for (BlueMapMap map : api.getMaps()) {
                api.getRenderManager().scheduleMapUpdateTask(map, false);
                count++;
            }
            Lanterne.LOG.info("[CADENCE] BlueMap : mise à jour complète programmée pour {} carte(s).",
                    count);
            if (aucunJoueur) {
                api.getRenderManager().start();
            }
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
