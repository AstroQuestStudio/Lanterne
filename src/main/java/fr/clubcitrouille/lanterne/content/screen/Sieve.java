package fr.clubcitrouille.lanterne.content.screen;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Le tamis : ce qu'une source a le droit d'être.
 *
 * <h2>Le fait technique qui commande tout ce fichier</h2>
 *
 * <p>Quand un joueur pose un lien sur un écran, ce n'est pas le serveur qui va le chercher : ce sont
 * <b>tous les clients qui regardent</b>. Chacun ouvre sa propre connexion vers l'adresse indiquée.
 *
 * <p>La conséquence n'est pas une opinion, c'est du réseau : l'hôte distant reçoit l'adresse IP de
 * chaque spectateur, son agent utilisateur, et l'horodatage de sa visite. Un joueur qui pose un lien
 * vers un serveur qu'il contrôle obtient donc, en une minute, <b>l'adresse IP de tous les joueurs
 * connectés qui passeront devant son écran</b>. Il n'a besoin d'aucune faille et d'aucun outil ; il
 * lui suffit de lire son journal d'accès.
 *
 * <p>Ce n'est pas une menace théorique : c'est le mode d'emploi. Tout mod qui affiche une URL
 * arbitraire chez tous les clients a cette propriété, et la plupart ne le disent pas.
 *
 * <h2>La deuxième surface, qu'on oublie toujours</h2>
 *
 * <p>Le client qui va chercher le lien le fait <b>depuis le réseau local du joueur</b>. Une source
 * pointant vers {@code http://192.168.1.1/} ou {@code http://127.0.0.1:8080/} ne sort pas d'Internet :
 * elle frappe à la porte du routeur du spectateur, ou d'un service qui n'écoute que sa machine. Le
 * temps de réponse suffit à dire si la porte existe.
 *
 * <p>C'est pourquoi {@link #admits} refuse les adresses locales <b>même quand la liste blanche est
 * désactivée</b>. Un administrateur peut choisir d'ouvrir son serveur à tout Internet ; il ne peut pas
 * choisir d'ouvrir le réseau domestique de ses joueurs, parce que ce n'est pas le sien.
 *
 * <h2>Trois barrières, et pourquoi il en faut trois</h2>
 *
 * <ol>
 *   <li><b>Le serveur refuse</b> à la pose. C'est la barrière qui protège les autres joueurs, et la
 *       seule sur laquelle l'administrateur ait la main.</li>
 *   <li><b>Le client refuse</b> avant d'aller chercher. Elle est redondante avec la première <em>sur
 *       un serveur honnête</em>, et c'est précisément pourquoi elle existe : elle protège d'un
 *       serveur qui ne l'est pas. Un client n'a aucune raison de croire la liste qu'on lui envoie
 *       plus que ses propres yeux.</li>
 *   <li><b>Le joueur refuse tout</b>, une bonne fois. Voir le consentement dans
 *       {@code client.screen.Consent} : rien ne part de sa machine, quelle que soit la liste.</li>
 * </ol>
 */
public final class Sieve {
    /** Pourquoi une source a été refusée. Le libellé est écrit pour être montré au joueur. */
    public enum Verdict {
        ADMISE(""),
        VIDE("aucune source"),
        MALFORMEE("ce n'est pas une adresse valide"),
        PROTOCOLE("seuls « http:// » et « https:// » sont acceptés"),
        LOCALE("cette adresse pointe vers un réseau privé — refusée pour tout le monde"),
        HORS_LISTE("ce domaine n'est pas dans la liste blanche du serveur");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        public boolean ok() {
            return this == ADMISE;
        }
    }

    private Sieve() {}

    /**
     * Cette source est-elle acceptable ?
     *
     * <p>La liste est un paramètre et non une lecture de configuration : le serveur la tient dans
     * {@link Booth}, le client dans ce que le serveur lui a annoncé, et les deux doivent exécuter le
     * <b>même</b> code. Deux tamis écrits séparément finissent toujours par différer sur un cas
     * limite, et c'est exactement le cas limite qui sert à passer au travers.
     *
     * @param allowed les motifs autorisés — {@code exemple.com} ou {@code *.exemple.com}
     * @param enforced la liste est-elle appliquée ? Les refus locaux le sont de toute façon.
     */
    public static Verdict admits(String source, List<? extends String> allowed, boolean enforced) {
        if (source == null || source.isBlank()) {
            return Verdict.VIDE;
        }
        if (source.length() > Feed.SOURCE_LIMIT) {
            return Verdict.MALFORMEE;
        }
        URI uri;
        try {
            uri = URI.create(source.trim());
        } catch (IllegalArgumentException malformed) {
            return Verdict.MALFORMEE;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return Verdict.MALFORMEE;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        // « file: », « jar: », « data: » et les autres ne sont pas des oublis à combler : chacun
        // permettrait de faire lire au client un contenu qui n'est pas sur le réseau. « file: » lit
        // le disque du spectateur, « data: » contourne toute liste blanche puisqu'il n'a pas d'hôte.
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Verdict.PROTOCOLE;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Verdict.MALFORMEE;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (local(host)) {
            return Verdict.LOCALE;
        }
        if (!enforced) {
            return Verdict.ADMISE;
        }
        for (String pattern : allowed) {
            if (matches(host, pattern)) {
                return Verdict.ADMISE;
            }
        }
        return Verdict.HORS_LISTE;
    }

    /**
     * Ce nom d'hôte désigne-t-il la machine du spectateur ou son réseau ?
     *
     * <h2>Ce que ce test attrape, et ce qu'il n'attrape pas</h2>
     *
     * <p>Il attrape les adresses écrites en clair : {@code localhost}, {@code 127.x}, {@code 10.x},
     * {@code 192.168.x}, {@code 172.16–31.x}, le lien-local de l'auto-configuration, et leurs
     * équivalents en IPv6.
     *
     * <p>Il <b>n'attrape pas</b> un nom de domaine public qui <em>résout</em> vers une adresse privée
     * — la technique est connue et porte un nom. L'attraper demanderait de résoudre le nom nous-mêmes
     * puis d'imposer au client de se connecter à l'adresse résolue, ce qu'aucune bibliothèque HTTP
     * ordinaire ne permet de faire proprement, et ce qu'un moteur embarquant un navigateur entier ne
     * permet pas du tout.
     *
     * <p>Le dire est plus utile que de faire semblant : <b>la liste blanche est la vraie protection</b>,
     * ce test n'est qu'un garde-fou pour le cas où l'administrateur l'a ouverte. C'est la même
     * honnêteté qu'ailleurs dans ce dépôt — une barrière annoncée pour ce qu'elle arrête vaut mieux
     * qu'une barrière annoncée pour ce qu'elle voudrait arrêter.
     */
    private static boolean local(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")) {
            return true;
        }
        // IPv6 : URI.getHost rend les crochets. On ne cherche pas à tout comprendre, seulement à
        // reconnaître la boucle locale et le lien-local, qui sont les deux seules formes utiles à
        // qui sonde.
        if (host.startsWith("[")) {
            String inner = host.substring(1, Math.max(1, host.length() - 1));
            return inner.equals("::1") || inner.startsWith("fe80:") || inner.startsWith("fc")
                    || inner.startsWith("fd");
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException notAnAddress) {
                return false; // un vrai nom de domaine : la liste blanche s'en charge
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }
        return octets[0] == 0
                || octets[0] == 127
                || octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168)
                || (octets[0] == 169 && octets[1] == 254)
                || (octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127);
    }

    /**
     * Cet hôte correspond-il à ce motif ?
     *
     * <p>{@code *.exemple.com} accepte {@code www.exemple.com} <b>et</b> {@code exemple.com} : un
     * administrateur qui écrit l'un veut l'autre, et lui demander d'écrire les deux lignes n'est pas
     * une rigueur, c'est un piège. {@code exemple.com} seul, en revanche, n'accepte que lui —
     * l'inverse laisserait {@code exemple.com.attaquant.net} passer, ce qui est le piège classique.
     */
    private static boolean matches(String host, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String wanted = pattern.trim().toLowerCase(Locale.ROOT);
        if (wanted.startsWith("*.")) {
            String root = wanted.substring(2);
            return host.equals(root) || host.endsWith("." + root);
        }
        return host.equals(wanted);
    }

    /**
     * Le domaine d'une source, pour l'écrire dans une interface.
     *
     * <p>Montrer le domaine et non l'adresse entière est un choix : une adresse de deux cents
     * caractères ne se lit pas, alors que le domaine est très exactement l'information qui compte
     * pour décider si l'on veut être vu en train de la demander.
     */
    public static String domain(String source) {
        try {
            String host = URI.create(source.trim()).getHost();
            return host == null ? "?" : host;
        } catch (RuntimeException unreadable) {
            return "?";
        }
    }
}
