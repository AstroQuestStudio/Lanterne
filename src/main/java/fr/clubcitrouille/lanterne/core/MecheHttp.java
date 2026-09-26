package fr.clubcitrouille.lanterne.core;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le dernier recours de la Mèche : un tout petit serveur HTTP qui ne sert QU'UN fichier, le jar de
 * Lanterne que ce serveur fait tourner — pour le joueur dont le client est trop ancien pour se mettre
 * à jour tout seul (voir {@link MecheCompat#refus}). Son adresse est affichée dans le message de refus.
 *
 * <h2>Éteint par défaut</h2>
 *
 * <p>{@code [maj] http_port = 0} dans {@code lanterne-common.toml} : rien n'écoute. Un port ouvert est
 * une décision d'administrateur (pare-feu, hébergeur), pas un réglage que le mod peut prendre seul.
 * {@code http_adresse} donne l'URL publique complète quand l'adresse vue du joueur n'est pas celle du
 * jeu (mandataire, nom de domaine) ; sinon on reprend l'hôte que le joueur a tapé pour se connecter.
 *
 * <h2>Ce qu'il sert, et rien d'autre</h2>
 *
 * <p>Toute requête reçoit le même fichier : aucun chemin demandé n'est jamais résolu sur le disque —
 * pas de traversée de répertoire possible par construction. Lecture seule, un fil, pas de journal
 * d'accès.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class MecheHttp {
    private static HttpServer serveur;
    private static int portActif;

    private MecheHttp() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        int port;
        try {
            port = Config.MECHE_HTTP_PORT.get();
        } catch (RuntimeException pasCharge) {
            return;
        }
        if (port <= 0 || port > 65535) {
            return;
        }
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(port), 8);
            http.createContext("/", MecheHttp::servir);
            http.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Lanterne-mèche-http");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            serveur = http;
            portActif = port;
            Lanterne.LOG.info("[MÈCHE] téléchargement HTTP du jar ouvert sur le port {}.", port);
        } catch (Exception impossible) {
            Lanterne.LOG.warn("[MÈCHE] serveur HTTP de téléchargement impossible sur le port {} : {}", port,
                    impossible.toString());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (serveur != null) {
            serveur.stop(0);
            serveur = null;
            portActif = 0;
        }
    }

    private static void servir(HttpExchange echange) throws java.io.IOException {
        try (echange) {
            Meche.JarInfo info = Meche.describeOwnJar(Lanterne.ID);
            byte[] data = Meche.ownJarBytesPourConfig();
            if (info == null || data == null || !"GET".equals(echange.getRequestMethod())) {
                echange.sendResponseHeaders(404, -1);
                return;
            }
            echange.getResponseHeaders().add("Content-Type", "application/java-archive");
            echange.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename=\"lanterne-" + info.version() + ".jar\"");
            echange.sendResponseHeaders(200, data.length);
            try (OutputStream out = echange.getResponseBody()) {
                out.write(data);
            }
        }
    }

    /**
     * L'adresse à afficher au joueur, ou {@code null} si ce service est éteint.
     *
     * @param hote l'hôte que le joueur a tapé pour se connecter (peut être {@code null}).
     */
    public static String adresse(String hote, String version) {
        try {
            String configuree = Config.MECHE_HTTP_ADRESSE.get().strip();
            if (!configuree.isEmpty()) {
                return configuree;
            }
        } catch (RuntimeException pasCharge) {
            return null;
        }
        if (serveur == null || portActif <= 0) {
            return null;
        }
        String h = hote == null || hote.isBlank() ? "<adresse du serveur>" : hote;
        if (h.contains(":") && !h.startsWith("[")) {
            h = "[" + h + "]"; // IPv6 littérale
        }
        return "http://" + h + ":" + portActif + "/lanterne-" + version + ".jar";
    }
}
