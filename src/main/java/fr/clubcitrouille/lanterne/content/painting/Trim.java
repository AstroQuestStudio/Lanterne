package fr.clubcitrouille.lanterne.content.painting;

/**
 * L'encadrement d'une toile : rien, ou un cadre.
 *
 * <h2>Pourquoi des couleurs et non des textures</h2>
 *
 * <p>Immersive Paintings modélise ses cadres en trois dimensions, les charge depuis des fichiers
 * {@code .obj} et les repousse sommet par sommet à chaque image. C'est joli de près et c'est
 * exactement la dépense que ce paquet refuse : la géométrie d'un cadre est <b>statique</b> pour une
 * taille donnée, et la recalculer soixante fois par seconde et par tableau visible n'apporte rien.
 *
 * <p>Ici un cadre est quatre bandes plates, teintées par la <b>couleur du sommet</b>. Cela coûte
 * seize sommets, aucune texture supplémentaire, et surtout aucun second lot de dessin : la bande
 * puise dans la même case unie de la {@link Mosaic} que le dos de la toile. Tous les cadres de tous
 * les tableaux du monde restent donc dans le lot unique — ce qui est tout l'intérêt de la mosaïque, et
 * qu'une texture de cadre aurait annulé.
 *
 * <h2>Le sans-cadre n'est pas « le cadre nul »</h2>
 *
 * <p>{@link #AUCUN} change plus que l'apparence de la bordure : la toile perd aussi son dos et ses
 * tranches, et sa face avant se rapproche du mur. Ce n'est plus un objet accroché, c'est un motif
 * <b>peint sur</b> le mur — l'affiche, le graffiti. Voir {@link #skin()} pour le détail du décalage,
 * qui est le seul endroit délicat de tout ce fichier.
 */
public enum Trim {
    /**
     * Sans cadre : l'image seule, plaquée contre le mur.
     *
     * <p>C'est le style que le commanditaire appelle « graffiti » ou « affiche ».
     */
    AUCUN(0, 0.0f, 0xFFFFFFFF),

    /** Bois sombre : le cadre ordinaire, celui qui ne se remarque pas. */
    BOIS(1, 0.085f, 0xFF6B4A2A),

    /** Or : pour ce qu'on veut montrer. */
    OR(2, 0.085f, 0xFFD8B24A),

    /** Pierre : sobre et froid, s'accorde aux murs de donjon. */
    PIERRE(3, 0.085f, 0xFF8A8A92),

    /** Fer noirci : le plus discret des cadres, presque un liseré. */
    FER(4, 0.055f, 0xFF3A3A44);

    private final int id;
    private final float width;
    private final int colour;

    Trim(int id, float width, int colour) {
        this.id = id;
        this.width = width;
        this.colour = colour;
    }

    public int id() {
        return this.id;
    }

    /** Largeur de la bande, en blocs. Zéro pour {@link #AUCUN}. */
    public float width() {
        return this.width;
    }

    /** Couleur de sommet appliquée à la bande. */
    public int colour() {
        return this.colour;
    }

    public boolean framed() {
        return this != AUCUN;
    }

    /**
     * De combien la face avant s'écarte du plan de la toile.
     *
     * <h2>Le seul réglage de tout ce paquet qu'un écran peut démentir</h2>
     *
     * <p>Une toile est accrochée à {@code 0,03125} bloc — un demi-pixel — de la face du mur. Un
     * tableau encadré pousse sa face avant à {@code 0,03125} de plus vers le spectateur, ce qui la
     * met à un pixel entier du mur : aucun risque de scintillement, et le relief se voit.
     *
     * <p>Sans cadre, on veut au contraire que le motif <b>épouse</b> le mur. La face avance donc
     * beaucoup moins — {@code 0,015}, soit un quart de pixel. C'est assez pour que le tampon de
     * profondeur tranche sans hésiter à distance de jeu, et assez peu pour qu'on ne voie pas le motif
     * flotter.
     *
     * <p><b>Si un scintillement apparaît malgré tout</b> — un damier qui clignote entre le motif et
     * le mur quand on s'éloigne —, c'est ce nombre qu'il faut augmenter, et lui seul. Il est isolé
     * ici pour cette raison. Le passer à {@code 0,025} réglerait le problème au prix d'un motif
     * visiblement décollé sous un angle rasant. Ce compromis n'a pas pu être arbitré en jeu.
     */
    public float skin() {
        return this == AUCUN ? 0.015f : Canvas.HALF_DEPTH;
    }

    public static Trim byId(int id) {
        for (Trim trim : values()) {
            if (trim.id == id) {
                return trim;
            }
        }
        return AUCUN;
    }

    /** Le suivant dans la liste, en boucle — ce que fait le bouton de l'atelier. */
    public Trim next() {
        Trim[] all = values();
        return all[(this.ordinal() + 1) % all.length];
    }

    /** Le nom affiché dans l'atelier. */
    public String label() {
        return switch (this) {
            case AUCUN -> "sans cadre";
            case BOIS -> "bois";
            case OR -> "or";
            case PIERRE -> "pierre";
            case FER -> "fer";
        };
    }
}
