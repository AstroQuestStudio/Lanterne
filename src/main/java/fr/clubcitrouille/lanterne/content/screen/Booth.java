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
 * <h2>La liste blanche part vide, et c'est délibéré</h2>
 *
 * <p>La tentation est d'y mettre d'emblée les grands hébergeurs de vidéo pour que le bloc « marche
 * tout de suite ». Deux raisons de ne pas le faire, et la seconde suffirait.
 *
 * <p><b>Ce serait une promesse fausse.</b> Inscrire un hébergeur qui ne sert pas de fichier vidéo
 * directement — et aucun des grands ne le fait — donne un domaine autorisé dont rien ne sortira. Le
 * joueur conclura que le mod est cassé, ce qui est une conclusion raisonnable.
 *
 * <p><b>Ce serait choisir à la place de quelqu'un dont c'est la responsabilité.</b> Autoriser un
 * domaine, c'est accepter que l'adresse IP de chaque joueur du serveur lui soit communiquée — voir
 * {@link Sieve}. Cette décision appartient à celui qui répond des joueurs, pas à l'auteur d'un mod
 * qui ne saura jamais de quel serveur il s'agit.
 *
 * <p>Le prix à payer est qu'un écran neuf refuse la première source qu'on lui donne. Il le refuse en
 * disant exactement quoi faire, et c'est la seule façon acceptable de refuser.
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
                "AVERTISSEMENT, a lire une fois :",
                "Quand un joueur pose un lien sur un ecran, ce n'est pas le serveur qui va le",
                "chercher : c'est CHAQUE CLIENT qui regarde. L'hote distant recoit donc l'adresse IP",
                "de tous vos joueurs. Un lien vers un serveur que quelqu'un controle lui donne la",
                "liste des IP de votre communaute, sans aucune faille et sans aucun outil.",
                "",
                "La liste blanche ci-dessous est la seule protection reelle contre cela. Elle est",
                "active par defaut et VIDE par defaut : rien ne se chargera tant que vous n'aurez pas",
                "inscrit vous-meme les domaines auxquels vous acceptez d'exposer vos joueurs.",
                "",
                "Un joueur peut de son cote refuser TOUT media distant, et son refus prime sur cette",
                "liste. C'est dans son fichier lanterne-projecteur-client.toml.").push("projection");

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
                "A faux, n'importe quel lien http(s) public est accepte. Ne mettez faux que si vous",
                "savez precisement a qui vous exposez les IP de vos joueurs.")
                .define("active", true);

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
            // Avant le chargement du fichier, on refuse. Ouvrir « en attendant » ferait passer
            // exactement la source qu'on est en train d'essayer d'arreter, et le moment ou la
            // configuration n'est pas encore lue est le premier moment d'une partie.
            return Sieve.Verdict.HORS_LISTE;
        }
        return Sieve.admits(source, DOMAINS.get(), FILTER.get());
    }

    /** La liste telle qu'on l'annonce aux clients, pour qu'ils appliquent le même tamis. */
    public static List<String> domains() {
        if (!loaded()) {
            return List.of();
        }
        return DOMAINS.get().stream().map(String::valueOf).toList();
    }

    public static boolean filtering() {
        return !loaded() || FILTER.get();
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
            Lanterne.LOG.warn("[PROJECTION] La liste blanche est DÉSACTIVÉE. N'importe quel lien "
                    + "public sera chargé par tous les clients, qui communiqueront donc leur adresse "
                    + "IP à qui l'a posé. Voir config/lanterne-projecteur.toml.");
        } else if (DOMAINS.get().isEmpty()) {
            Lanterne.LOG.info("[PROJECTION] Liste blanche active et vide : aucune source ne sera "
                    + "acceptée. Inscris les domaines dans config/lanterne-projecteur.toml, ou par "
                    + "« /lanterne projection domaine ajouter <domaine> ».");
        } else {
            Lanterne.LOG.info("[PROJECTION] Liste blanche active — {} domaine(s).",
                    DOMAINS.get().size());
        }
    }
}
