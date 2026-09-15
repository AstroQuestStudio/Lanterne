package fr.clubcitrouille.lanterne.client.screen;

import java.util.Locale;

import fr.clubcitrouille.lanterne.content.screen.Feed;
import fr.clubcitrouille.lanterne.content.screen.Sieve;

/**
 * Ce qu'un écran dit quand il n'a pas d'image.
 *
 * <h2>Le défaut que ce fichier existe pour réparer</h2>
 *
 * <p>Il était écrit, dans la note de {@link Engine}, qu'il n'existait « aucun chemin vers l'écran
 * noir ». C'était faux, et un joueur l'a établi en trois minutes : mur de huit sur cinq, entièrement
 * noir, rien à lire.
 *
 * <p>Trois fautes se cumulaient, et aucune n'était dans la logique de repli — {@link Still} était
 * bien atteint. Elles étaient toutes les trois dans <b>ce qu'il avait à dire</b> :
 *
 * <ol>
 *   <li><b>Une source vide ne disait rien du tout.</b> Le rendu mettait la ligne à vide quand aucune
 *       source n'était posée, au motif qu'il n'y avait « rien à signaler ». Un écran qu'on vient de
 *       bâtir est précisément le moment où l'on a le plus besoin qu'il dise quoi faire.</li>
 *   <li><b>Le message était faux quand tout allait bien.</b> {@code Still.slate} regardait le verdict
 *       du moteur ; moteur prêt, il tombait dans le cas par défaut et affichait « aucun décodeur
 *       vidéo ». Le décodeur était installé et fonctionnel : c'est <em>l'adresse</em> qui n'était pas
 *       lisible.</li>
 *   <li><b>Le fond était presque noir</b> — vingt sur vingt-six — et éclairé par la pièce. De nuit,
 *       il valait zéro. L'ardoise avait la couleur de ce qu'elle devait remplacer.</li>
 * </ol>
 *
 * <p>D'où cette classe, qui ne fait qu'une chose : <b>répondre par la vraie raison</b>. Le rendu ne
 * décide plus de rien ; il peint ce qu'on lui donne, et on lui donne toujours quelque chose.
 *
 * <h2>Deux lignes, et la seconde est un ordre</h2>
 *
 * <p>La première dit ce qui se passe, la seconde dit quoi faire. Un constat sans geste laisse le
 * joueur devant un écran qui a l'air cassé ; c'est ce qui vient d'arriver.
 */
public record Slate(String what, String todo) {
    /** L'ardoise vide : il y a une image, rien à dire. */
    public static final Slate NONE = new Slate("", "");

    public boolean silent() {
        return this.what.isEmpty();
    }

    /**
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
            ".ogv", ".avi", ".ts", ".flv", ".mp3", ".ogg", ".wav"};

    /**
     * L'ardoise d'un écran, d'après tout ce qu'on sait de lui.
     *
     * <p>L'ordre des questions est celui dans lequel elles se posent au joueur : « ai-je mis une
     * source ? », « est-elle acceptée ? », « ai-je autorisé ma machine ? », « le décodeur est-il
     * là ? », « le flux s'ouvre-t-il ? ». Chaque réponse arrête la chaîne, et c'est la première qui
     * bloque réellement qui s'affiche — jamais la dernière, qui serait la moins utile.
     *
     * @param trouble ce que la bobine a rapporté, ou {@code null} si elle n'a rien dit
     */
    public static Slate of(Feed feed, boolean opening, boolean playing, String trouble) {
        if (playing) {
            return NONE;
        }
        if (feed.idle()) {
            return new Slate("Écran sans source",
                    "Clique ce bloc pour poser un lien vidéo");
        }

        Sieve.Verdict verdict = Gaze.admits(feed.source());
        if (!verdict.ok()) {
            return new Slate("Source refusée : " + verdict.label(), advice(feed.source()));
        }

        if (!Consent.remoteAllowed()) {
            return new Slate("Médias distants non autorisés sur ce client",
                    "Clique ce bloc → « Autoriser les médias distants »");
        }

        switch (Fetch.state()) {
            case EN_COURS -> {
                return new Slate("Installation du décodeur — " + Fetch.percent() + " %",
                        "Une trentaine de mébioctets, une seule fois");
            }
            case ECHEC -> {
                return new Slate("Décodeur non installé", Fetch.trouble());
            }
            case ABSENT -> {
                return new Slate("Décodeur pas encore installé",
                        "Clique ce bloc pour lancer le téléchargement");
            }
            default -> { }
        }

        // Le décodeur est là. Tout ce qui reste est une affaire d'adresse — et c'est très
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
}
