package fr.clubcitrouille.lanterne.client.screen;

import fr.clubcitrouille.lanterne.content.screen.Embed;
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

    /** Le nom exact du mod compagnon à installer. L'écran doit le nommer, pas le décrire. */
    public static final String COMPANION = "Rinku";

    /** Où le prendre. Une ardoise qui dit « installe X » sans dire où est une demi-réponse. */
    public static final String COMPANION_URL = "modrinth.com/mod/rinku";

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

        // LA FORME D'ABORD, et l'ordre inverse disait n'importe quoi. Un lien YouTube n'a aucun
        // besoin de FFmpeg : il lui faut un navigateur. Interroger l'état du décodeur avant de
        // regarder l'adresse faisait répondre « décodeur pas encore installé » à un joueur qui
        // aurait pu télécharger trente et un mébioctets sans que cela ne change rien pour lui.
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
        } else {
            // Un fichier : c'est FFmpeg qu'il faut, et lui seul.
            switch (Fetch.state()) {
                case EN_COURS -> {
                    return new Slate("Installation du décodeur — " + Fetch.percent() + " %",
                            "31 Mio, une seule fois");
                }
                case ECHEC -> {
                    return new Slate("Décodeur non installé", Fetch.trouble());
                }
                case ABSENT -> {
                    return new Slate("Ce lien demande le décodeur — 31 Mio à télécharger",
                            "Clique ce bloc → « Télécharger le décodeur »");
                }
                default -> { }
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
}
