package fr.clubcitrouille.lanterne.client.screen;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.screen.Embed;

/**
 * L'hôte : sert, en boucle locale seulement, une page minuscule qui encadre un lecteur YouTube
 * dans un vrai {@code <iframe>} — pour lui donner un Referer, ce qu'une navigation nue n'a pas.
 *
 * <h2>Pourquoi ce détour existe</h2>
 *
 * <p>Fin 2025, YouTube a durci la validation de son lecteur intégrable : une requête sans
 * Referer/Origin venant d'une vraie page hôte échoue avec « Error 153 — Video player configuration
 * error ». C'est documenté pour Chrome, Safari, <b>et</b> CEF de la même façon — pas une
 * particularité de ce mod. {@link Chrome} naviguait jusqu'ici en direct vers l'adresse de l'embed :
 * aucune page ne l'entoure, donc structurellement aucun Referer à envoyer. Une page qui l'encadre
 * dans un {@code <iframe>}, même minuscule et locale, en a un — c'est tout ce que ce fichier fournit.
 *
 * <h2>Pourquoi ceci ne repasse pas par {@code Sieve}</h2>
 *
 * <p>{@code content.screen.Sieve} refuse toute adresse de réseau privé, et {@code 127.0.0.1} en est
 * une — à raison : la note du tamis explique qu'un joueur peut planter un lien qui fait sonder le
 * routeur d'un <em>autre</em> spectateur. Ce n'est pas le cas ici. Ce serveur ne sert qu'à la
 * machine qui l'a démarré, ne lit ni n'écrit rien de choisi par un joueur : {@link #frame} ne
 * construit une réponse que pour une adresse qui commence déjà par
 * {@link Embed#YOUTUBE_EMBED_PREFIX} — c'est-à-dire une adresse que {@code Embed.of} a elle-même
 * fabriquée à partir d'un identifiant filtré à l'alphanumérique, jamais du texte libre reçu d'un
 * autre joueur. Il n'y a donc rien ici qu'un joueur malveillant puisse faire pointer ailleurs, et
 * rien qui quitte la machine du spectateur : ce n'est pas la surface que {@code Sieve} ferme.
 */
final class Hote {
    private Hote() {}

    private static final String CONTEXT = "/cadre";

    private static volatile HttpServer server;
    private static volatile int port = -1;

    /** L'adresse locale à donner au navigateur pour un embed YouTube, ou nul si indisponible. */
    static synchronized String frame(String embedUrl) {
        int p = ensure();
        if (p < 0) {
            return null;
        }
        return "http://127.0.0.1:" + p + CONTEXT + "?src="
                + URLEncoder.encode(embedUrl, StandardCharsets.UTF_8);
    }

    private static int ensure() {
        if (server != null) {
            return port;
        }
        try {
            HttpServer created = HttpServer.create(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            created.createContext(CONTEXT, Hote::serve);
            created.setExecutor(null);
            created.start();
            server = created;
            port = created.getAddress().getPort();
            Lanterne.LOG.info("[PROJECTION] hote local demarre sur 127.0.0.1:{}", port);
        } catch (IOException impossible) {
            Lanterne.LOG.warn("[PROJECTION] hote local indisponible : {}", String.valueOf(impossible));
            port = -1;
        }
        return port;
    }

    private static void serve(HttpExchange exchange) {
        try {
            String src = param(exchange.getRequestURI().getRawQuery(), "src");
            if (src == null || !src.startsWith(Embed.YOUTUBE_EMBED_PREFIX)) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            byte[] body = page(src).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException failed) {
            Lanterne.LOG.debug("[PROJECTION] reponse hote local echouee : {}", String.valueOf(failed));
        } finally {
            exchange.close();
        }
    }

    private static String param(String query, String key) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * La page hôte : un {@code <iframe>} en plein cadre, et rien d'autre — pas de barre, pas de
     * script tiers. {@link Chrome} pilote l'iframe depuis l'extérieur, par {@code postMessage}.
     */
    private static String page(String embedUrl) {
        // « origin » dit à YouTube quelle page a le droit de la piloter par postMessage — sans
        // lui, certaines versions du lecteur ignorent les commandes distantes. On peut enfin le
        // renseigner : c'est précisément ce que cette page hôte apporte, une vraie origine.
        String withOrigin = embedUrl + "&origin=" + URLEncoder.encode(
                "http://127.0.0.1:" + port, StandardCharsets.UTF_8);
        // Échappement d'attribut seulement : embedUrl est un préfixe fixe suivi d'un identifiant
        // déjà filtré par Embed.clean() (alphanumérique, « - », « _ ») et de paramètres littéraux
        // écrits par Embed.java ou ci-dessus — jamais de texte libre reçu d'un joueur.
        String escaped = withOrigin.replace("&", "&amp;").replace("\"", "&quot;");
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Lanterne</title><style>"
                + "html,body{margin:0;padding:0;background:#000}"
                + "iframe{position:fixed;inset:0;width:100%;height:100%;border:0}"
                + "</style></head><body>"
                + "<iframe id=\"p\" src=\"" + escaped + "\" allow=\"autoplay\" allowfullscreen></iframe>"
                + "</body></html>";
    }
}
