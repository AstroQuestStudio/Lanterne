package fr.clubcitrouille.lanterne.client.upscale;

import java.util.Locale;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Lens;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * La mise à l'échelle : ce que le module décide, séparé de ce qu'il dessine.
 *
 * <h2>Pourquoi cette classe ne connaît pas Minecraft</h2>
 *
 * <p>Elle est lue depuis {@code core.Settings}, qui est chargée <b>sur un serveur dédié</b>. Sur un
 * serveur, les classes {@code net.minecraft.client.*} n'existent pas : une seule référence, même
 * jamais exécutée, suffit à empêcher le démarrage. C'est la leçon déjà écrite dans
 * {@code client.Pane}, appliquée ici parce que le même piège s'est présenté une seconde fois.
 *
 * <p>Tout ce qui touche à une cible de rendu vit donc dans {@link Scene} et {@link Resolve}, et ces
 * deux-là ne sont nommées que depuis le mixin, qui est déclaré côté client.
 *
 * <h2>D'où vient le gain, et pourquoi il est si direct</h2>
 *
 * <p>Le coût d'une image est à peu près proportionnel au nombre de pixels. Rendre le monde à deux
 * tiers de chaque dimension, ce n'est pas deux tiers du travail : c'est {@code 0,67² ≈ 45 %} de
 * pixels en moins. À la moitié de chaque dimension, on en supprime les trois quarts.
 *
 * <p>C'est le principe de DLSS, de FSR et de XeSS. Ils ne diffèrent que par la <b>façon de
 * remonter</b> l'image, pas par l'origine du gain. FSR 1.0 est le seul des trois qui soit un pur
 * nuanceur : pas de vecteurs de mouvement, pas d'historique, pas de bibliothèque native, pas
 * d'exigence de matériel. Il tourne sur OpenGL comme sur Vulkan, donc pour tout le monde — alors que
 * le backend Vulkan de 26.2 est marqué « expérimental » et n'est pas le défaut.
 *
 * <h2>L'interface reste en résolution native, et c'est tout l'enjeu</h2>
 *
 * <p>La première tentative de ce projet mentait à {@code Window.getWidth()} : le monde <em>et</em>
 * l'interface rétrécissaient ensemble, et le texte devenait illisible. Voir {@link Lens} pour ce que
 * cet essai a donné à l'écran.
 *
 * <p>Ici, le monde est rendu dans une <b>cible séparée</b>, remonté par FSR vers la cible
 * principale, et l'interface est peinte par-dessus à la résolution de la fenêtre. Le texte ne perd
 * rien.
 *
 * <h2>Tout échec retombe en rendu natif</h2>
 *
 * <p>Un shader qui ne compile pas, une cible qu'on ne peut pas allouer, un pilote qui refuse : le
 * module s'éteint pour la session, écrit une ligne de journal, et le jeu continue en résolution
 * native. Un joueur qui voit un écran noir désinstalle le mod ; un joueur qui ne voit rien de
 * spécial ouvre le journal, ou pas.
 */
public final class Upscale {
    /**
     * La netteté, en quatre crans.
     *
     * <p>Chaque cran est une <b>chaîne de post-traitement distincte</b>, et non un réglage passé au
     * nuanceur. La raison est mécanique : les uniformes d'une chaîne {@code PostChain} sont figés à
     * la construction de la passe, à partir du JSON. Pour les rendre variables il faudrait bâtir le
     * pipeline à la main — beaucoup plus de code, pour un réglage qu'on change trois fois dans une
     * vie de joueur.
     */
    public enum Edge {
        /** EASU seul. La remontée d'échelle, sans raffermissement. */
        AUCUNE("aucune", "aucune"),
        /** RCAS à 0,35. Pour qui trouve le raffermissement voyant. */
        DOUCE("douce", "douce"),
        /** RCAS à 0,60. Le défaut. */
        MOYENNE("moyenne", "moyenne"),
        /** RCAS à 0,90, la limite que RCAS s'autorise. */
        FORTE("forte", "forte");

        private final String path;
        private final String label;

        Edge(String path, String label) {
            this.path = path;
            this.label = label;
        }

        /** Le chemin de la chaîne {@code post_effect} correspondante. */
        public String path() {
            return "upscale_" + this.path;
        }

        public String label() {
            return this.label;
        }
    }

    private static Edge edge = Edge.MOYENNE;

    /**
     * La houle : le facteur suit le taux d'images au lieu d'être choisi une fois.
     *
     * <p>Comme la netteté, ce réglage ne survit pas au redémarrage. {@code core.ClientConfig} est
     * tenu par un autre chantier au moment où ce module est écrit, et lui ajouter deux lignes aurait
     * créé un conflit d'édition. Deux entrées de configuration suffiront à le rendre persistant ;
     * c'est signalé dans le rapport plutôt que fait à la sauvette.
     */
    private static boolean swell;

    /**
     * Le module a échoué et ne réessaiera pas de la session.
     *
     * <h2>Pourquoi un échec est définitif et non retenté</h2>
     *
     * <p>Les causes d'échec possibles — un nuanceur qui ne compile pas, une allocation refusée — ne
     * se corrigent pas d'elles-mêmes d'une image à l'autre. Réessayer soixante fois par seconde
     * remplirait le journal, et surtout : {@code ShaderManager.getPostChain} déclenche à son premier
     * échec une <b>procédure de récupération des paquets de ressources</b>. La déclencher en boucle
     * ferait bien plus de dégâts que de rendre en résolution native.
     */
    private static boolean broken;

    private Upscale() {}

    /**
     * Le module agit-il ?
     *
     * <p>Sans le {@code master &&} des autres réglages : l'interrupteur général est basculé vingt
     * fois par mesure par le banc, et changer la taille d'une cible de rendu en pleine image n'est
     * pas une opération qu'on veut voir arriver au milieu d'un relevé.
     */
    public static boolean active() {
        return !broken && Settings.lens() && divisor() > 1.0001d;
    }

    /**
     * Le diviseur appliqué à chaque dimension.
     *
     * <p>En houle, c'est l'échelon courant ; sinon, le préréglage choisi. La valeur est toujours
     * supérieure ou égale à un : on ne rend jamais <em>plus</em> grand que l'écran, ce qui coûterait
     * sans rien apporter.
     */
    public static double divisor() {
        double wanted = swell ? Swell.divisor() : Lens.preset().divisor();
        return Math.max(1.0d, wanted);
    }

    /** Part des pixels effectivement rendus, pour l'afficher au joueur. */
    public static int pixelPercent() {
        double d = divisor();
        return (int) Math.round(100d / (d * d));
    }

    public static Edge edge() {
        return edge;
    }

    public static boolean swell() {
        return swell;
    }

    /** Fait tourner la netteté d'un cran, dans un sens ou dans l'autre. */
    public static void cycleEdge(int step) {
        Edge[] all = Edge.values();
        edge = all[Math.floorMod(edge.ordinal() + step, all.length)];
    }

    /** Allume ou éteint la houle. Repart toujours du natif, pour que l'essai soit lisible. */
    public static void toggleSwell() {
        swell = !swell;
        Swell.reset();
    }

    /**
     * Le préréglage vient de changer : la houle repart du natif.
     *
     * <p>Sans cela, elle jugerait le nouveau plancher sur des durées d'image relevées avec l'ancien,
     * et le premier palier tomberait pour de mauvaises raisons.
     */
    public static void retune() {
        Swell.reset();
    }

    /**
     * Signale un échec et éteint le module pour de bon.
     *
     * <p>Rend toujours {@code false}, pour s'écrire en une ligne au point d'appel :
     * {@code if (chose == null) return Upscale.fail("…");}
     */
    static boolean fail(String why) {
        if (!broken) {
            broken = true;
            Lanterne.LOG.warn("[ÉCHELLE] Mise à l'échelle abandonnée pour cette session : {}. "
                    + "Le monde est rendu en résolution native.", why);
        }
        return false;
    }

    /** Vrai si le module s'est éteint tout seul. Pour l'écran de réglages, qui doit le dire. */
    public static boolean broken() {
        return broken;
    }

    /** L'état courant, pour la jauge et pour le journal. Vide si le module ne fait rien. */
    public static String describe() {
        if (broken) {
            return "lentille-en-panne";
        }
        if (!active()) {
            return "";
        }
        return String.format(Locale.ROOT, "lentille-%d%%%s-%s",
                pixelPercent(), swell ? "-houle" : "", edge.path);
    }
}
