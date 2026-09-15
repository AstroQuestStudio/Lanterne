package fr.clubcitrouille.lanterne.content.screen;

import java.util.List;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La cabine de projection : ce que l'administrateur décide, et que personne ne contourne.
 *
 * <h2>Un fichier à part, et pourquoi</h2>
 *
 * <p>{@code Config.SPEC} occupe déjà le fichier {@code SERVER} par défaut, et l'atelier a le sien.
 * Celui-ci s'appelle {@code lanterne-projecteur.toml}, pour la même raison que la boutique a le sien :
 * un administrateur qui cherche la liste blanche des domaines doit la trouver dans un fichier qui
 * porte le nom du sujet, pas au milieu de quarante interrupteurs d'optimisation.
 *
 * <p>Il est de type {@code SERVER} sans hésitation possible. La liste blanche n'est pas une
 * préférence d'affichage : c'est une règle que le serveur fait respecter, et un client ne doit jamais
 * pouvoir s'inventer la sienne.
 *
 * <h2>Le filtrage par domaine est OUVERT par défaut, et c'est une décision d'administrateur</h2>
 *
 * <p>La première version livrait l'inverse : liste active et vide, donc rien ne passait tant que
 * l'administrateur n'avait pas inscrit un domaine. C'était défendable, et c'était mauvais à l'usage —
 * un écran neuf refusait la première source qu'on lui donnait, et le joueur en concluait, non sans
 * raison, que le bloc ne marchait pas.
 *
 * <p>Le filtrage reste écrit, et disponible ; il est simplement <b>éteint</b> au départ. Un
 * administrateur qui veut refermer met {@code active = true} et remplit la liste, par le fichier ou
 * par {@code /projection domaine ajouter}. Rien n'a été retiré : c'est le défaut qui a changé de
 * camp, sur décision de celui qui répond des joueurs.
 *
 * <h2>Ce qui ne s'ouvre pas, et qui n'est pas de la politique</h2>
 *
 * <p><b>Les adresses de réseau privé restent refusées, liste ouverte ou fermée.</b> Une source
 * pointant vers {@code 192.168.1.1} ne sort pas d'Internet : elle fait frapper à la porte du routeur
 * de <em>chaque spectateur</em>, et le temps de réponse suffit à dire si la porte existe. Un
 * administrateur peut ouvrir son serveur à tout Internet ; il ne peut pas ouvrir le réseau
 * domestique de ses joueurs, parce que ce n'est pas le sien. Voir {@link Sieve}.
 *
 * <p><b>Et le joueur garde le dernier mot.</b> Son refus de charger un média distant prime sur toute
 * liste blanche, et rien dans ce fichier ne le contourne.
 */
public final class Booth {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ACTIVE;
    public static final ModConfigSpec.IntValue WALL_CAP;
    public static final ModConfigSpec.IntValue RANGE_CAP;
    public static final ModConfigSpec.IntValue THROW_CAP;
    public static final ModConfigSpec.BooleanValue FILTER;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DOMAINS;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment(
                "Lanterne - l'ecran et le projecteur.",
                "",
                "A SAVOIR, une fois :",
                "Quand un joueur pose un lien sur un ecran, ce n'est pas le serveur qui va le",
                "chercher : c'est CHAQUE CLIENT qui regarde. L'hote distant recoit donc l'adresse IP",
                "de tous vos joueurs, comme n'importe quel site qu'ils visiteraient.",
                "",
                "Le filtrage par domaine est DESACTIVE par defaut : tout lien http(s) public est",
                "accepte. Pour refermer, mettez liste_blanche.active a true et remplissez",
                "liste_blanche.domaines.",
                "",
                "Deux choses ne s'ouvrent JAMAIS, et ce ne sont pas des preferences :",
                "  - les adresses de reseau prive (127.x, 10.x, 192.168.x, 172.16-31.x, localhost,",
                "    fe80::...) sont toujours refusees : elles feraient sonder le reseau domestique",
                "    de vos joueurs, qui ne vous appartient pas ;",
                "  - le refus d'un joueur de charger un media distant prime sur tout ce fichier.",
                "    C'est dans son lanterne-projecteur-client.toml, et dans l'ecran du bloc.").push("projection");

        ACTIVE = BUILDER.comment(
                "Les ecrans et les projecteurs fonctionnent-ils sur ce serveur ?",
                "A faux, les blocs restent poses et fabricables mais ne jouent rien.",
                "Un interrupteur ne doit jamais transformer une construction en air.")
                .define("actif", true);

        WALL_CAP = BUILDER.comment(
                "Blocs au maximum dans un mur d'ecran assemble.",
                "256 blocs, c'est 16x16 : un ecran de cinema. Au-dela, la texture qu'un client",
                "doit tenir pour l'alimenter grandit plus vite que ce qu'on y gagne a le regarder.")
                .defineInRange("mur_blocs_max", 256, 1, 1024);

        RANGE_CAP = BUILDER.comment(
                "Portee sonore maximale qu'un joueur peut regler, en blocs.",
                "Au-dela, un ecran s'entend d'un autre quartier - ce qui est un probleme social",
                "avant d'etre un probleme technique.")
                .defineInRange("portee_sonore_max", 48, 1, Feed.RANGE_CAP);

        THROW_CAP = BUILDER.comment(
                "Distance maximale de projection d'un projecteur, en blocs.",
                "C'est aussi la distance a laquelle il cherche une surface.")
                .defineInRange("projection_max", 32, 2, 64);

        BUILDER.pop();

        BUILDER.comment(
                "La liste blanche des domaines.",
                "",
                "Un domaine par ligne, entre guillemets. Deux ecritures :",
                "  \"exemple.com\"    - ce domaine exactement, et rien d'autre",
                "  \"*.exemple.com\"  - ce domaine ET tous ses sous-domaines",
                "",
                "Les adresses de reseau prive (127.x, 10.x, 192.168.x, 172.16-31.x, localhost,",
                "fe80::...) sont refusees MEME si vous desactivez la liste : elles feraient sonder",
                "le reseau domestique de vos joueurs, ce qui ne vous appartient pas.").push("liste_blanche");

        FILTER = BUILDER.comment(
                "Appliquer la liste blanche.",
                "",
                "FAUX par defaut : n'importe quel lien http(s) public est accepte.",
                "A VRAI, seuls les domaines ci-dessous passent.",
                "",
                "Dans les deux cas les adresses de reseau prive restent refusees : ce n'est pas la",
                "liste qui les arrete mais une regle a part, et elle protege le reseau domestique de",
                "vos joueurs plutot que votre serveur.")
                .define("active", false);

        DOMAINS = BUILDER.comment("Les domaines autorises.")
                .defineList("domaines", List.<String>of(), () -> "exemple.com",
                        entry -> entry instanceof String text && !text.isBlank());

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private Booth() {}

    /**
     * Le verdict du serveur sur une source.
     *
     * <p>Une seule méthode, appelée depuis le seul endroit où un client peut proposer une source.
     * Toute autre entrée serait un contournement, et c'est pourquoi {@link Sieve} ne lit jamais la
     * configuration lui-même : il ne peut pas être appelé « par erreur » avec la mauvaise liste.
     */
    public static Sieve.Verdict admits(String source) {
        if (!loaded()) {
            // Avant le chargement du fichier, on ne sait pas si l'administrateur a referme. On
            // applique donc le tamis SANS liste : ce qui n'est pas de la politique — le protocole,
            // les adresses privees — joue quand meme, et c'est tout ce qui compte dans les
            // quelques millisecondes ou la configuration n'est pas encore lue.
            return Sieve.admits(source, java.util.List.of(), false);
        }
        return Sieve.admits(source, DOMAINS.get(), filtering());
    }

    /** La liste telle qu'on l'annonce aux clients, pour qu'ils appliquent le même tamis. */
    public static List<String> domains() {
        if (!loaded()) {
            return List.of();
        }
        return DOMAINS.get().stream().map(String::valueOf).toList();
    }

    /**
     * Le tamis par domaine s'applique-t-il ?
     *
     * <h2>Une liste active et vide n'est pas une décision, c'est un oubli</h2>
     *
     * <p>Une liste blanche <b>active mais vide</b> refuse absolument tout, et c'est très exactement
     * ce qui s'est produit : l'utilisateur a vu son mod lui répondre « l'administrateur doit en
     * inscrire » alors qu'il <em>était</em> l'administrateur et n'avait jamais rien demandé de tel.
     * Le réglage venait d'un défaut désormais abandonné, que son fichier avait conservé — un fichier
     * de configuration n'est jamais réécrit quand un défaut change.
     *
     * <p>Or cette combinaison n'a <b>aucune valeur</b>. Un administrateur qui veut réellement fermer
     * inscrit des domaines ; un administrateur qui ne veut aucune vidéo éteint la projection
     * elle-même. Personne ne choisit « accepter uniquement les domaines de la liste » puis « aucun
     * domaine » — c'est la signature d'un réglage qu'on n'a pas touché.
     *
     * <p>Une liste vide ne filtre donc plus rien. La friction disparaît, et il ne se perd aucune
     * protection : il n'y en avait pas, il n'y avait qu'un refus.
     *
     * <p><b>Ce qui ne change pas</b> : les adresses de réseau privé restent refusées, liste ou pas.
     * Voir {@link Sieve} — un administrateur peut ouvrir <em>son</em> serveur à l'internet, il ne
     * peut pas ouvrir le réseau domestique de ses joueurs.
     */
    public static boolean filtering() {
        return loaded() && FILTER.get() && !DOMAINS.get().isEmpty();
    }

    public static boolean active() {
        return loaded() && ACTIVE.get();
    }

    public static int wallCap() {
        return loaded() ? WALL_CAP.get() : 256;
    }

    public static int rangeCap() {
        return loaded() ? RANGE_CAP.get() : 48;
    }

    public static int throwCap() {
        return loaded() ? THROW_CAP.get() : 32;
    }

    /**
     * Le fichier a-t-il été lu ?
     *
     * <p>Lire une valeur avant le chargement lève « Cannot get config value before config is
     * loaded ». Cela arrive pour de bon : un client en menu principal n'a chargé aucun fichier
     * {@code SERVER}, et il exécute pourtant du code qui voudrait connaître la liste.
     */
    private static boolean loaded() {
        return SPEC.isLoaded();
    }

    public static void apply(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        if (!(event instanceof ModConfigEvent.Loading) && !(event instanceof ModConfigEvent.Reloading)) {
            return;
        }
        if (!FILTER.get()) {
            Lanterne.LOG.info("[PROJECTION] Filtrage par domaine désactivé : tout lien http(s) "
                    + "public est accepté. Les adresses de réseau privé restent refusées. Pour "
                    + "refermer : « /projection domaine ajouter <domaine> », puis "
                    + "liste_blanche.active = true.");
        } else if (DOMAINS.get().isEmpty()) {
            Lanterne.LOG.info("[PROJECTION] Liste blanche demandée mais VIDE — elle ne filtre donc "
                    + "rien, et tout lien public est accepté. Une liste active et vide refuserait "
                    + "tout sans que personne ne l'ait voulu : inscris des domaines si tu veux "
                    + "réellement fermer.");
        } else {
            Lanterne.LOG.info("[PROJECTION] Liste blanche active — {} domaine(s).",
                    DOMAINS.get().size());
        }
    }
}
