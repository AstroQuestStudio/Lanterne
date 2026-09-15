package fr.clubcitrouille.lanterne.report;

import java.util.ArrayList;
import java.util.List;

import net.neoforged.fml.ModList;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Les compagnons : qui d'autre est chargé, et ce que Lanterne en fait.
 *
 * <h2>Le défaut que ce bloc corrige</h2>
 *
 * <p>Un joueur qui installe cinq mods ne sait pas lesquels se parlent. Il voit cinq bannières qui
 * annoncent chacune son propre nom, et aucune ne dit ce qui change <b>parce que l'autre est là</b>.
 * Or c'est exactement ce qui change : les réglages de Lanterne déménagent quand Sodium tient l'écran
 * vidéo, la marée peut descendre plus bas quand Distant Horizons comble derrière elle, et le carnet
 * de repères cohabite avec celui de JourneyMap au lieu de le remplacer.
 *
 * <p>Aucune de ces trois choses n'est devinable. Elles sont donc dites, une fois, au démarrage, à
 * l'endroit où l'administrateur regarde déjà.
 *
 * <h2>Pourquoi un bloc séparé et non trois lignes de plus dans le mot d'accueil</h2>
 *
 * <p>{@code core.Herald} répond à deux questions : <em>que vaut cette machine</em> et <em>que va
 * faire ce mod</em>. Celui-ci en pose une troisième — <em>avec qui</em> — qui ne dépend ni de la
 * machine ni des réglages, et dont la longueur varie avec le contenu du dossier {@code mods}. Une
 * bannière qui grandit sans borne cesse d'être lue ; deux cadres courts se lisent mieux qu'un long.
 *
 * <h2>Pourquoi l'identifiant de mod, et jamais un nom de classe</h2>
 *
 * <p>La tentation est de chercher une classe connue — {@code Class.forName} sur un renderer de
 * Distant Horizons, par exemple. C'est deux fautes en une : un nom de classe est un détail
 * d'implémentation qui bouge d'une version à l'autre sans prévenir, et le chercher <b>charge</b> la
 * classe, avec tout ce qu'elle entraîne. L'identifiant de mod, lui, est un contrat public : c'est ce
 * que le mod promet de ne pas changer, et {@link ModList#isLoaded(String)} n'ouvre rien.
 *
 * <h2>Pourquoi seuls les présents sont nommés</h2>
 *
 * <p>Énumérer les absents serait du bruit : « Distant Horizons non détecté » n'apprend rien à qui ne
 * l'a pas installé, et fait passer pour un manque ce qui est un choix. Un mod qui n'est pas là n'a
 * rien à dire.
 *
 * <h2>Ce que ce bloc ne peut pas voir, et qu'il ne prétend pas voir</h2>
 *
 * <p>Il est écrit au démarrage du <b>serveur</b>, comme le mot d'accueil. En solo, c'est le serveur
 * intégré : la liste est celle du joueur, et elle est juste. Sur un serveur dédié, elle est celle du
 * serveur — quatre des cinq compagnons de ce modpack sont des mods de client, et l'administrateur
 * lira donc surtout une liste courte. C'est correct : le serveur ne <em>peut pas</em> savoir ce que
 * ses joueurs ont installé, et prétendre le contraire serait pire que se taire.
 */
public final class Compagnons {
    /** Même largeur que le mot d'accueil, pour que les deux cadres s'alignent à l'œil. */
    private static final String LINE = "─".repeat(66);

    /** Sodium — le moteur de rendu de chunks. Voir {@code client.sodium.Graft}. */
    public static final String SODIUM = "sodium";

    /** Distant Horizons — le terrain lointain en niveaux de détail. */
    public static final String HORIZONS = "distanthorizons";

    /** JourneyMap — la carte et ses propres repères. Voir {@code content.waypoint}. */
    public static final String JOURNEYMAP = "journeymap";

    /** Jade — l'infobulle du bloc visé. */
    public static final String JADE = "jade";

    /** JEI — la liste des objets. Voir {@code client.ponder.Hint}. */
    public static final String JEI = "jei";

    private Compagnons() {}

    /**
     * Ce mod est-il chargé ?
     *
     * <p>Le {@code catch} n'est pas de la superstition : {@link ModList#get()} lève tant que la liste
     * n'a pas été peuplée, et une question posée trop tôt ne doit pas empêcher un serveur de
     * démarrer. Répondre « non » est le sens d'erreur qui ne casse rien — au pire, une ligne
     * d'information manque.
     */
    public static boolean present(String modId) {
        try {
            return ModList.get().isLoaded(modId);
        } catch (Throwable tooEarly) {
            return false;
        }
    }

    /** La version déclarée d'un mod, ou une chaîne vide si on ne peut pas la lire. */
    private static String version(String modId) {
        try {
            return ModList.get().getModContainerById(modId)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("");
        } catch (Throwable unreadable) {
            return "";
        }
    }

    /** Écrit le bloc. Appelé une fois, juste après le mot d'accueil. */
    public static void say() {
        List<String> lines = new ArrayList<>();

        if (present(SODIUM)) {
            head(lines, "Sodium", version(SODIUM));
            say(lines, "il tient l'écran vidéo : nos réglages sont dans SA");
            say(lines, "colonne de gauche, et dans la liste des mods");
            say(lines, "feuilles masquées : il appelle skipRendering, ça tient");
        }
        if (present(HORIZONS)) {
            head(lines, "Distant Horizons", version(HORIZONS));
            say(lines, "son plan de coupe suit la distance de vue VANILLA,");
            say(lines, "image par image : quand la marée descend, il comble");
            say(lines, "aussitôt — mais SEULEMENT là où tu es déjà passé");
            say(lines, "/lanterne pregen le nourrit, chunk par chunk");
        }
        if (present(JOURNEYMAP)) {
            head(lines, "JourneyMap", version(JOURNEYMAP));
            say(lines, "deux carnets, et c'est voulu : les siens sont à toi");
            say(lines, "seul, les nôtres sont ceux du serveur et commandent");
            say(lines, "les portails. Notre carnet est sur G, B/J/N sont à lui");
        }
        if (present(JADE)) {
            head(lines, "Jade", version(JADE));
            say(lines, "aucun de nos blocs n'expose d'inventaire : il n'a rien");
            say(lines, "à demander au serveur, ni rien à afficher de faux");
        }
        if (present(JEI)) {
            head(lines, "JEI", version(JEI));
            say(lines, "le guide illustré passe par l'infobulle, pas par une");
            say(lines, "greffe : il marche dans sa liste comme à l'inventaire");
        }

        for (String warning : warnings()) {
            lines.add("  ⚠  " + warning);
        }

        if (lines.isEmpty()) {
            return;
        }

        say("┌" + LINE + "┐");
        say(pad("  Compagnons  ·  ce que Lanterne fait de chacun"));
        say("├" + LINE + "┤");
        for (String line : lines) {
            say(pad(line));
        }
        say("└" + LINE + "┘");
        say("");
    }

    /**
     * Les cas où deux modules se marchent dessus, et ce qu'il faut éteindre.
     *
     * <h2>Pourquoi cette liste est courte, et pourquoi c'est une bonne nouvelle</h2>
     *
     * <p>{@code notes/mine-mods-26-2.md} §4.1 a établi la règle : deux boucles d'asservissement sur
     * la même grandeur oscillent, et trois régulateurs de distance de simulation valent moins qu'un.
     * Appliquée à ce modpack, la règle ne trouve <b>presque rien</b> — les cinq compagnons ont été
     * choisis sur des postes disjoints. Ce qui reste est écrit ici, et rien de plus : un
     * avertissement qui crie pour rien n'est plus lu quand il a raison.
     */
    private static List<String> warnings() {
        List<String> found = new ArrayList<>();
        // Sur un seul cœur, le préréglage de fils de Distant Horizons ne sert à rien : son calcul est
        // « plafond(cœurs × pourcentage) », borné à [1, cœurs] — ThreadPresetConfigEventHandler
        // .getThreadCountByPercent. À un cœur, les cinq préréglages rendent donc 1. Le seul levier
        // qui bouge encore est le rapport de temps de marche : 0,5 au préréglage « impact minimal »,
        // 1,0 partout ailleurs. Le dire évite de chercher une heure dans le mauvais réglage.
        if (present(HORIZONS) && Runtime.getRuntime().availableProcessors() == 1) {
            found.add("Un seul cœur : les fils de Distant Horizons vaudront 1,");
            found.add("   quel que soit le préréglage. Seul le run time ratio agit.");
        }
        // La lentille rend le monde dans une toile réduite puis la remonte. Distant Horizons dessine
        // DANS ce même bloc de rendu : ses niveaux de détail héritent donc de la toile réduite et
        // sont remontés avec le reste — c'est cohérent, mais cela veut dire que baisser la lentille
        // baisse AUSSI la netteté de l'horizon lointain, là où elle se voit le plus.
        if (present(HORIZONS) && fr.clubcitrouille.lanterne.client.upscale.Upscale.active()) {
            found.add("Lentille + Distant Horizons : l'horizon lointain est remonté");
            found.add("   d'échelle comme le reste. Si ça bave, c'est la lentille.");
        }
        return found;
    }

    private static void head(List<String> lines, String name, String version) {
        lines.add("  · " + name + (version.isEmpty() ? "" : " " + version));
    }

    private static void say(List<String> lines, String what) {
        lines.add("       " + what);
    }

    /**
     * Complète une ligne pour que le cadre reste droit.
     *
     * <p>Même correction que le mot d'accueil, et pour la même raison : les caractères de cadre et
     * les émojis comptent pour deux colonnes dans la plupart des terminaux mais pour un seul
     * caractère en Java. Le calcul n'est pas partagé parce que celui du mot d'accueil est privé et
     * vit dans un module que ce fichier n'a pas à rouvrir ; il tient en six lignes, et les recopier
     * coûte moins qu'ouvrir une porte dans {@code core}.
     */
    private static String pad(String text) {
        int visible = text.codePointCount(0, text.length());
        for (int i = 0; i < text.length(); i++) {
            if (Character.isSurrogate(text.charAt(i)) || text.charAt(i) > 0x2500) {
                visible++;
            }
        }
        if (visible > LINE.length()) {
            int keep = Math.max(0, text.length() - (visible - LINE.length()) - 1);
            return "│" + text.substring(0, keep) + "…│";
        }
        return "│" + text + " ".repeat(Math.max(0, LINE.length() - visible)) + "│";
    }

    private static void say(String line) {
        Lanterne.LOG.info(line);
    }
}
