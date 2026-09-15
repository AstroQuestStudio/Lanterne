package fr.clubcitrouille.lanterne.core;

/**
 * La lentille : rendre le monde plus petit que l'écran, puis l'y étaler.
 *
 * <h2>D'où vient le gain, et pourquoi il est si direct</h2>
 *
 * <p>Le coût d'une image est à peu près proportionnel au nombre de pixels qu'elle contient. Rendre à
 * deux tiers de chaque dimension, ce n'est pas deux tiers du travail : c'est
 * {@code 0,67 × 0,67 ≈ 45 %} de pixels en moins. Rendre à la moitié de chaque dimension en supprime
 * les trois quarts.
 *
 * <p>C'est le principe de toutes les techniques de mise à l'échelle — DLSS chez NVIDIA, FSR chez
 * AMD, XeSS chez Intel. Elles ne diffèrent que par la <b>façon de remonter</b> l'image à la taille
 * de l'écran, pas par l'origine du gain.
 *
 * <h2>Pourquoi FSR et non DLSS, et pourquoi ce n'est pas une consolation</h2>
 *
 * <p>DLSS exige une carte NVIDIA RTX <em>et</em> la bibliothèque du constructeur, qui n'est pas
 * redistribuable. Il exige en outre un contexte Vulkan, là où Minecraft rend en OpenGL : le mod de
 * référence sur ce sujet embarque pour cette seule raison sa propre couche Vulkan, soit près de
 * mille fichiers.
 *
 * <p>FSR, lui, <b>n'est pas réservé aux cartes AMD</b>. C'est un shader ordinaire qui s'exécute sur
 * n'importe quel processeur graphique. Sur un parc entièrement NVIDIA, ce n'est donc pas un pis-aller
 * : c'est le seul des deux qui fonctionne pour tout le monde, sans dépendance ni exclusion.
 *
 * <h2>Ce que ce module fait aujourd'hui, et ce qu'il ne fait pas encore</h2>
 *
 * <p>Il fait la <b>première moitié</b>, celle qui porte le gain : le monde est rendu dans une cible
 * réduite, que le jeu étire ensuite lui-même jusqu'à l'écran. L'étirement est celui d'OpenGL —
 * bilinéaire, donc un peu doux.
 *
 * <p>La seconde moitié — les deux passes d'AMD, {@code EASU} qui reconstruit les contours et
 * {@code RCAS} qui les raffermit — n'est pas encore là. Elle ne changera rien à la vitesse, elle
 * changera la netteté. Les deux sont séparées à dessein : le gain est mesurable au banc, la netteté
 * ne se juge qu'à l'œil.
 *
 * <h2>Le compromis à connaître avant d'activer</h2>
 *
 * <p>L'interface est rendue dans la même cible que le monde. Elle est donc réduite puis étirée comme
 * lui, et le texte s'en ressent. L'échelle d'interface est corrigée pour que rien ne change de
 * taille apparente, mais la netteté du texte, elle, baisse avec le facteur choisi.
 *
 * <p>C'est la raison pour laquelle le réglage par défaut est <b>désactivé</b> : ce module échange de
 * la qualité contre de la vitesse, et cet arbitrage appartient au joueur, pas au mod.
 */
public final class Lens {
    /**
     * Les préréglages, repris des noms que DLSS et FSR emploient tous deux.
     *
     * <p>Les facteurs sont ceux de la table publiée par AMD pour FSR 1, à une exception près :
     * {@code ULTRA_PERFORMANCE}, qui appartient à DLSS et qu'AMD ne recommande pas — il est fourni
     * parce qu'il dépanne sur une machine très faible, et parce qu'un joueur qui le cherche doit le
     * trouver plutôt que d'aller chercher un autre mod.
     *
     * <p>Le facteur est un <b>diviseur par dimension</b>. {@code QUALITY} vaut 1,5 : chaque
     * dimension est divisée par 1,5, donc l'image contient {@code 1 / 1,5² ≈ 44 %} des pixels.
     */
    public enum Preset {
        /** Rendu à taille réelle. Le jeu tel quel. */
        NATIF(1.0d),
        /** 77 % de chaque dimension — 59 % des pixels. Différence à peine visible. */
        ULTRA_QUALITE(1.3d),
        /** 67 % — 44 % des pixels. Le réglage recommandé par AMD. */
        QUALITE(1.5d),
        /** 59 % — 35 % des pixels. */
        EQUILIBRE(1.7d),
        /** 50 % — 25 % des pixels. Un quart du travail de rendu. */
        PERFORMANCE(2.0d),
        /** 33 % — 11 % des pixels. Pour dépanner une machine à bout de souffle. */
        ULTRA_PERFORMANCE(3.0d);

        private final double divisor;

        Preset(double divisor) {
            this.divisor = divisor;
        }

        /** Le diviseur appliqué à chaque dimension. */
        public double divisor() {
            return this.divisor;
        }

        /** Part des pixels effectivement rendus, pour l'afficher au joueur. */
        public int pixelPercent() {
            return (int) Math.round(100d / (this.divisor * this.divisor));
        }
    }

    private static Preset preset = Preset.NATIF;

    /**
     * Vrai une fois le jeu réellement démarré.
     *
     * <h2>Pourquoi ce verrou est indispensable</h2>
     *
     * <p>{@code Window.getWidth()} est consulté <b>pendant la création de la fenêtre elle-même</b>,
     * bien avant qu'un monde existe. Mentir à ce moment-là donnerait une fenêtre mal dimensionnée,
     * un contexte graphique bâti sur de mauvaises valeurs, et des défauts impossibles à rattacher à
     * leur cause.
     *
     * <p>Le module ne répond donc rien tant que le jeu n'a pas fini de se construire — et c'est le
     * genre de précaution qu'on n'ajoute qu'après s'être fait avoir, ou en lisant celui qui s'est
     * fait avoir avant.
     */
    private static volatile boolean awake;

    private Lens() {}

    /** Ouvre le module. Appelé une fois le client entièrement construit. */
    public static void awaken() {
        awake = true;
    }





    public static void tune(Preset wanted) {
        preset = wanted == null ? Preset.NATIF : wanted;
    }

    public static Preset preset() {
        return preset;
    }
}
