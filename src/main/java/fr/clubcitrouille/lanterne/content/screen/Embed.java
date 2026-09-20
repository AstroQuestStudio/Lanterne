package fr.clubcitrouille.lanterne.content.screen;

import java.net.URI;
import java.util.Locale;

/**
 * La page qu'on affiche à la place de celle qu'on vous a donnée.
 *
 * <h2>La distinction que ce fichier existe pour tenir, et que j'avais ratée</h2>
 *
 * <p>La première reconnaissance de ce chantier écartait YouTube en bloc, au motif que ses conditions
 * d'utilisation interdisent d'accéder au contenu par des moyens automatisés. <b>C'était une confusion
 * entre deux choses très différentes</b>, et elle a coûté un écran noir à un joueur.
 *
 * <p><b>L'extraction</b> — récupérer l'adresse du fichier vidéo pour le décoder soi-même, à la façon
 * de {@code yt-dlp} — est bien ce que les conditions interdisent : c'est du téléchargement, par un
 * moyen automatisé, et cela suppose souvent de contourner une protection.
 *
 * <p><b>L'intégration</b> ne l'est pas. Le texte interdit l'accès « par tout moyen autre que les pages
 * de lecture vidéo, <em>le lecteur intégrable</em>, ou d'autres moyens explicitement autorisés ». Le
 * lecteur intégrable est donc <b>nommément autorisé</b> — c'est ce pour quoi il existe, c'est ce que
 * fait n'importe quel site qui incorpore une vidéo, la publicité est servie, et la vue est comptée.
 *
 * <p>Ce fichier ne fait donc rien d'astucieux : il transforme l'adresse que le joueur a collée en
 * <b>la forme que l'hébergeur publie pour cet usage</b>. C'est plus léger qu'une page complète — pas
 * de commentaires, pas de recommandations, pas de barre latérale — et c'est ce qui rend l'affichage
 * légitime.
 *
 * <h2>Ce qui reste refusé</h2>
 *
 * <p>Rien ici ne va chercher un flux, ne devine une adresse de fichier, ni ne contourne quoi que ce
 * soit. Si un hébergeur ne publie pas de lecteur intégrable, il n'apparaît pas dans ce fichier et
 * l'écran le dira. Voir {@code client.screen.Slate}.
 */
public final class Embed {
    private Embed() {}

    /**
     * Le préfixe qui distingue une adresse YouTube des autres lecteurs intégrables.
     *
     * <p>Sert à {@code client.screen.Chrome} pour savoir s'il faut passer par {@code Hote} — voir
     * ce fichier pour pourquoi YouTube en a besoin spécifiquement, et pas Vimeo ni Dailymotion.
     */
    public static final String YOUTUBE_EMBED_PREFIX = "https://www.youtube-nocookie.com/embed/";

    /** Ce qu'on a reconnu, et ce qu'il faut pour l'afficher. */
    public record Form(String url, Kind kind, String host) {
        /** Faut-il un navigateur, ou un décodeur suffit-il ? */
        public boolean needsBrowser() {
            return this.kind == Kind.LECTEUR;
        }
    }

    public enum Kind {
        /** Un fichier ou un flux : {@code FFmpeg} le lit directement. */
        FICHIER,
        /** Un lecteur intégrable : il faut un navigateur. */
        LECTEUR,
        /** Une page quelconque. On l'affichera si un navigateur est là, sans rien promettre. */
        PAGE
    }

    /** Les extensions qui désignent un média qu'on sait décoder sans navigateur. */
    private static final String[] MEDIA = {".mp4", ".webm", ".mkv", ".mov", ".m4v", ".m3u8", ".mpd",
            ".ogv", ".avi", ".ts", ".flv", ".mp3", ".ogg", ".wav", ".opus", ".flac", ".aac"};

    /**
     * La forme affichable d'une adresse.
     *
     * <p>Ne lève jamais : une adresse incompréhensible est rendue telle quelle, en {@link Kind#PAGE}.
     * C'est le rôle du tamis de refuser ce qui n'a rien à faire là, pas celui de ce fichier.
     */
    public static Form of(String source) {
        if (source == null || source.isBlank()) {
            return new Form("", Kind.PAGE, "");
        }
        String raw = source.trim();
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (RuntimeException unreadable) {
            return new Form(raw, Kind.PAGE, "");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        String query = uri.getQuery() == null ? "" : uri.getQuery();

        if (media(path)) {
            return new Form(raw, Kind.FICHIER, host);
        }

        String youtube = youtubeId(host, path, query);
        if (youtube != null) {
            // « enablejsapi » n'est pas décoratif : c'est lui qui autorise le pilotage par script,
            // donc l'horloge partagée. Sans lui, chaque client jouerait la même vidéo à son
            // heure — ce qui est très exactement ce que ce mod existe pour éviter.
            //
            // « autoplay=0 » parce que c'est l'horloge qui décide de partir, pas la page.
            // « controls=0 » parce qu'un joueur ne peut pas cliquer dedans : les commandes sont
            // dans l'écran du bloc, et en montrer d'autres qui ne répondent pas serait cruel.
            return new Form(YOUTUBE_EMBED_PREFIX + youtube
                    + "?enablejsapi=1&autoplay=0&controls=0&rel=0&modestbranding=1"
                    + "&playsinline=1&iv_load_policy=3", Kind.LECTEUR, host);
        }

        String vimeo = vimeoId(host, path);
        if (vimeo != null) {
            return new Form("https://player.vimeo.com/video/" + vimeo
                    + "?autoplay=0&title=0&byline=0&portrait=0", Kind.LECTEUR, host);
        }

        String dailymotion = dailymotionId(host, path);
        if (dailymotion != null) {
            return new Form("https://geo.dailymotion.com/player.html?video=" + dailymotion
                    + "&autoplay=false&controls=false", Kind.LECTEUR, host);
        }

        return new Form(raw, Kind.PAGE, host);
    }

    /**
     * L'identifiant d'une vidéo YouTube, sous n'importe laquelle de ses écritures.
     *
     * <p>Quatre formes circulent, et un joueur colle celle que son navigateur lui a donnée :
     * {@code watch?v=}, le lien court {@code youtu.be/}, {@code /shorts/} et {@code /embed/}. Les
     * traiter toutes coûte quinze lignes ; n'en traiter qu'une donne un écran noir sur trois liens
     * sur quatre, et le joueur conclura que le mod ne marche pas.
     */
    private static String youtubeId(String host, String path, String query) {
        boolean shortLink = host.equals("youtu.be");
        boolean main = host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com");
        if (!shortLink && !main) {
            return null;
        }
        if (shortLink) {
            return clean(path.startsWith("/") ? path.substring(1) : path);
        }
        for (String prefix : new String[] {"/embed/", "/shorts/", "/v/", "/live/"}) {
            if (path.startsWith(prefix)) {
                return clean(path.substring(prefix.length()));
            }
        }
        for (String pair : query.split("&")) {
            if (pair.startsWith("v=")) {
                return clean(pair.substring(2));
            }
        }
        return null;
    }

    private static String vimeoId(String host, String path) {
        if (!host.endsWith("vimeo.com")) {
            return null;
        }
        String id = clean(path.startsWith("/") ? path.substring(1) : path);
        return id != null && id.chars().allMatch(Character::isDigit) ? id : null;
    }

    private static String dailymotionId(String host, String path) {
        if (!host.endsWith("dailymotion.com") && !host.equals("dai.ly")) {
            return null;
        }
        String trimmed = path.startsWith("/video/") ? path.substring(7)
                : path.startsWith("/") ? path.substring(1) : path;
        return clean(trimmed);
    }

    /**
     * Un identifiant réduit à ce qu'il peut être.
     *
     * <p>Il finit dans une adresse qu'on donne à un navigateur : une barre oblique ou un point
     * d'interrogation qui passerait ici sortirait de la page prévue. On ne garde donc que les
     * caractères que ces hébergeurs emploient, et rien d'autre — c'est court à écrire et cela ferme
     * la question.
     */
    private static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        int cut = raw.length();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '-' || c == '_';
            if (!ok) {
                cut = i;
                break;
            }
        }
        String id = raw.substring(0, cut);
        return id.isEmpty() || id.length() > 64 ? null : id;
    }

    private static boolean media(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String extension : MEDIA) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
}
