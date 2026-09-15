# -*- coding: utf-8 -*-
"""L'ardoise nomme le lecteur integre et le mod compagnon. Jetable."""
import io, os, sys

p = os.path.join(os.path.dirname(__file__), "..", "src", "main", "java", "fr",
                 "clubcitrouille", "lanterne", "client", "screen", "Slate.java")
s = io.open(p, encoding="utf-8").read()

OLD_DOC = """    /**
     * Les hébergeurs qui servent une <b>page</b> et non un fichier.
     *
     * <h2>Pourquoi ils sont nommés un par un plutôt que devinés</h2>
     *
     * <p>Le joueur qui a essuyé l'écran noir avait posé un lien YouTube. FFmpeg l'a ouvert, y a
     * trouvé du HTML, et a rendu « Invalid data found when processing input » — parfaitement exact et
     * parfaitement inutile pour qui ne sait pas ce qu'est un conteneur.
     *
     * <p>On pourrait s'en tenir au message générique. Le nommer vaut mieux : « YouTube n'est pas
     * lisible directement » est une phrase qu'on comprend du premier coup, et elle évite les dix
     * minutes passées à réessayer d'autres liens du même site.
     *
     * <p>Et il faut être clair sur ce qu'on ne fera pas : extraire le flux d'une de ces pages demande
     * de contourner des mesures que leurs conditions d'utilisation interdisent expressément de
     * contourner. Voir {@code notes/projecteur.md}. Ce mod ne le fera pas, et l'écran doit donc le
     * dire plutôt que de laisser espérer.
     */
    private static final String[] PAGES = {
            "youtube.com", "youtu.be", "youtube-nocookie.com",
            "vimeo.com", "dailymotion.com", "dai.ly",
            "twitch.tv", "tiktok.com", "instagram.com", "facebook.com",
            "x.com", "twitter.com", "bilibili.com", "netflix.com"};

    /** Les extensions dont on sait qu'elles désignent un média, pour conseiller juste. */
    private static final String[] MEDIA = {".mp4", ".webm", ".mkv", ".mov", ".m4v", ".m3u8", ".mpd",
            ".ogv", ".avi", ".ts", ".flv", ".mp3", ".ogg", ".wav"};"""

NEW_DOC = """    /** Le nom exact du mod compagnon à installer. L'écran doit le nommer, pas le décrire. */
    public static final String COMPANION = "Rinku";

    /** Où le prendre. Une ardoise qui dit « installe X » sans dire où est une demi-réponse. */
    public static final String COMPANION_URL = "modrinth.com/mod/rinku";"""

if OLD_DOC not in s:
    print("ABSENT: doc")
    sys.exit(1)
s = s.replace(OLD_DOC, NEW_DOC, 1)

OLD_BODY = """        // Le décodeur est là. Tout ce qui reste est une affaire d'adresse — et c'est très
        // exactement le cas que l'ancienne version annonçait comme « aucun décodeur vidéo ».
        String host = Sieve.domain(feed.source());
        if (page(host)) {
            return new Slate(host + " sert une page, pas un fichier vidéo",
                    "Donne un lien direct : .mp4, .webm, .m3u8");
        }
        if (opening) {
            return new Slate("Ouverture du flux…", host);
        }
        if (trouble != null && !trouble.isBlank()) {
            return new Slate("Flux illisible — " + trouble, advice(feed.source()));
        }
        return new Slate("Ouverture du flux…", host);
    }

    /** Le conseil qui suit un refus, adapté à ce qui a été tapé. */
    private static String advice(String source) {
        return looksLikeMedia(source)
                ? "Vérifie que le lien est bien accessible publiquement"
                : "Un lien direct vers un fichier : .mp4, .webm, .m3u8";
    }

    private static boolean page(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        for (String known : PAGES) {
            if (lower.equals(known) || lower.endsWith("." + known)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeMedia(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        int query = lower.indexOf('?');
        String path = query < 0 ? lower : lower.substring(0, query);
        for (String extension : MEDIA) {
            if (path.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cette source a-t-elle une chance d'être lue ?
     *
     * <p>Sert à l'écran de réglages, qui doit prévenir <b>avant</b> l'envoi. Refuser après coup, dans
     * le fil de discussion, c'est apprendre trop tard — et c'est ce qui vient de se passer.
     */
    public static String warning(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        String host = Sieve.domain(source);
        if (page(host)) {
            return "⚠ " + host + " sert une page : rien ne sera lu. Il faut un fichier.";
        }
        if (!looksLikeMedia(source)) {
            return "⚠ Ce lien ne finit pas par .mp4 / .webm / .m3u8 — à vérifier.";
        }
        return "";
    }
}"""

NEW_BODY = """        // Ce qui reste est une affaire de FORME d'adresse, et non de décodeur. C'est très
        // exactement le cas que l'ancienne version annonçait comme « aucun décodeur vidéo » alors
        // que le décodeur était installé et fonctionnel.
        Embed.Form form = Embed.of(feed.source());
        if (form.needsBrowser() || form.kind() == Embed.Kind.PAGE) {
            // Un lecteur intégré demande un navigateur. Lanterne n'en embarque pas — ce serait
            // cent quatre-vingts mébioctets pour tout le monde, y compris ceux qui ne lisent que
            // des fichiers. Le joueur qui veut YouTube installe le mod compagnon, et l'écran doit
            // lui dire son NOM, pas lui décrire le problème.
            if (Chrome.INSTANCE.verdict() == Engine.Verdict.SANS_MOD) {
                return new Slate(
                        form.needsBrowser()
                                ? form.host() + " demande le lecteur intégré"
                                : "Cette page demande un navigateur",
                        "Installe « " + COMPANION + " » — " + COMPANION_URL);
            }
        }
        String host = form.host().isEmpty() ? Sieve.domain(feed.source()) : form.host();
        if (opening) {
            return new Slate("Ouverture…", host);
        }
        if (trouble != null && !trouble.isBlank()) {
            return new Slate("Flux illisible — " + trouble, advice(feed.source()));
        }
        return new Slate("Ouverture…", host);
    }

    /** Le conseil qui suit un refus, adapté à ce qui a été tapé. */
    private static String advice(String source) {
        return Embed.of(source).kind() == Embed.Kind.FICHIER
                ? "Vérifie que le lien est bien accessible publiquement"
                : "Un lien direct vers un fichier : .mp4, .webm, .m3u8";
    }

    /**
     * Ce qu'il faut savoir avant d'envoyer cette source.
     *
     * <p>Sert à l'écran de réglages, qui doit prévenir <b>pendant qu'on tape</b>. Refuser après coup,
     * dans le fil de discussion, c'est apprendre trop tard — et c'est ce qui est arrivé.
     *
     * <p>Rend une chaîne vide quand tout va bien : un avertissement affiché en permanence n'est plus
     * un avertissement.
     */
    public static String warning(String source) {
        if (source == null || source.isBlank()) {
            return "";
        }
        Embed.Form form = Embed.of(source);
        boolean browser = Chrome.INSTANCE.verdict() != Engine.Verdict.SANS_MOD;
        return switch (form.kind()) {
            case FICHIER -> "";
            case LECTEUR -> browser
                    ? "▶ " + form.host() + " — lecteur intégré, via " + COMPANION
                    : "⚠ " + form.host() + " demande « " + COMPANION + " » — pas installé";
            case PAGE -> browser
                    ? "⚠ Page quelconque : elle s'affichera, sans garantie de vidéo."
                    : "⚠ Ni fichier vidéo, ni lecteur connu. Essaie un lien .mp4 / .m3u8.";
        };
    }
}"""

if OLD_BODY not in s:
    print("ABSENT: body")
    sys.exit(1)
s = s.replace(OLD_BODY, NEW_BODY, 1)

s = s.replace("import java.util.Locale;\n\n", "")
s = s.replace("import fr.clubcitrouille.lanterne.content.screen.Feed;",
              "import fr.clubcitrouille.lanterne.content.screen.Embed;\n"
              "import fr.clubcitrouille.lanterne.content.screen.Feed;")

io.open(p, "w", encoding="utf-8").write(s)
print("Slate.java : nomme le compagnon")
