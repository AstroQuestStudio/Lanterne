package fr.clubcitrouille.lanterne.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * L'ajusteur : un panneau qui ne tient pas dans l'écran rétrécit au lieu de déborder.
 *
 * <h2>Le défaut, et pourquoi il ne se voyait pas ici</h2>
 *
 * <p>Le retour du joueur était précis : <em>« en taille 3 on voit tout, mais en mode auto
 * l'interface dépasse »</em>. Ce n'est pas une préférence d'affichage, c'est de l'arithmétique.
 *
 * <p>L'échelle d'interface de Minecraft divise la fenêtre. Sur un écran de 1920 × 1080 :
 *
 * <pre>
 * échelle 3 -> 640 × 360 pixels virtuels
 * échelle 4 -> 480 × 270 pixels virtuels
 * </pre>
 *
 * <p>Or l'écran des tableaux demande 620 × 312, et celui des réglages 560 × 292. En échelle 3, les
 * deux tiennent. En échelle <b>automatique</b>, Minecraft choisit la plus grande échelle que la
 * fenêtre supporte — souvent 4 — et les deux débordent, en largeur comme en hauteur.
 *
 * <p>Le défaut est invisible en développement parce qu'on travaille en fenêtré, à une échelle où
 * tout tient. Il n'apparaît qu'en plein écran, chez le joueur.
 *
 * <h2>Rétrécir plutôt que reconstruire</h2>
 *
 * <p>Deux remèdes existaient.
 *
 * <p><b>Rendre chaque écran élastique</b> — dériver toute la géométrie de la place disponible, comme
 * le fait déjà l'écran de la boutique. C'est le plus propre, et c'est aussi une réécriture de cinq
 * écrans dont chacun a une mise en page dessinée à la main. Une colonne de réglages qui se
 * réorganise à mi-largeur est un second dessin à faire, pas un calcul.
 *
 * <p><b>Rétrécir le panneau entier</b> — ce que fait cette classe. La mise en page ne change pas
 * d'un pixel, elle est simplement dessinée plus petit. Et c'est exactement ce que le joueur demande
 * quand il dit que l'échelle 3 lui convient : il ne veut pas un autre dessin, il veut le même, plus
 * petit.
 *
 * <p>Le résultat est même meilleur que l'échelle 3 : en échelle 4 réduite de 480/560, le panneau est
 * dessiné à l'équivalent d'une échelle 3,43. Plus gros qu'en échelle 3, et il tient.
 *
 * <h2>Ce qu'il ne faut pas oublier, et qui casse tout si on l'oublie</h2>
 *
 * <p>Rétrécir le dessin <b>ne rétrécit pas la souris</b>. Les coordonnées que le jeu transmet sont
 * dans l'espace de l'écran ; le panneau, lui, vit désormais dans un espace agrandi d'un facteur
 * inverse. Un clic tomberait à côté — d'autant plus loin qu'on s'éloigne du coin supérieur gauche,
 * ce qui donne le symptôme le plus déroutant qui soit : les boutons du haut répondent, ceux du bas
 * ne répondent plus.
 *
 * <p>D'où {@link #x(double)} et {@link #y(double)}, à appliquer à <b>toute</b> coordonnée de souris :
 * clic, survol, molette, glissement. Il n'y a pas d'exception, et en oublier une se voit mal.
 */
public final class Fit {
    /**
     * Marge laissée autour du panneau, en pixels virtuels.
     *
     * <p>Un panneau collé aux quatre bords paraît coincé, et surtout : l'ombre portée et le liseré
     * d'un pixel qu'ils dessinent tous tomberaient hors de l'écran, ce qui donne un bord franc d'un
     * côté et un bord fini de l'autre.
     */
    private static final int MARGIN = 8;

    private float factor = 1f;
    private int viewWidth;
    private int viewHeight;

    /**
     * Mesure la réduction nécessaire.
     *
     * <p>À appeler dans {@code init()}, avant de calculer la position du panneau — et à nouveau si
     * la fenêtre change de taille, ce que Minecraft obtient en rappelant {@code init()}.
     *
     * @param screenWidth  largeur disponible, en pixels virtuels
     * @param screenHeight hauteur disponible
     * @param needWidth    largeur que le panneau réclame
     * @param needHeight   hauteur que le panneau réclame
     */
    public void measure(int screenWidth, int screenHeight, int needWidth, int needHeight) {
        double horizontal = (screenWidth - 2d * MARGIN) / needWidth;
        double vertical = (screenHeight - 2d * MARGIN) / needHeight;
        // Jamais au-dessus de un : agrandir un panneau qui tient deja serait le rendre flou pour
        // rien, et lui ferait perdre l'alignement au pixel que sa mise en page suppose.
        double wanted = Math.min(1d, Math.min(horizontal, vertical));
        // Et jamais en dessous d'un tiers. Sous ce seuil le texte n'est plus lisible, et un panneau
        // illisible mais entier ne vaut pas mieux qu'un panneau qui deborde : autant qu'il deborde,
        // le joueur comprendra au moins qu'il doit changer son echelle d'interface.
        this.factor = (float) Math.max(0.34d, wanted);
        this.viewWidth = (int) Math.ceil(screenWidth / this.factor);
        this.viewHeight = (int) Math.ceil(screenHeight / this.factor);
    }

    /** Le panneau est-il réellement réduit ? Faux quand tout tenait déjà. */
    public boolean reduced() {
        return this.factor < 0.999f;
    }

    public float factor() {
        return this.factor;
    }

    /**
     * La largeur de l'espace dans lequel l'écran doit se placer.
     *
     * <p>À employer partout où l'on écrivait {@code this.width} : centrer le panneau sur la largeur
     * réelle le décentrerait de tout le facteur de réduction.
     */
    public int viewWidth() {
        return this.viewWidth;
    }

    /** La hauteur de ce même espace. Voir {@link #viewWidth()}. */
    public int viewHeight() {
        return this.viewHeight;
    }

    /**
     * Ouvre l'espace réduit. Tout ce qui est dessiné ensuite l'est à l'échelle du panneau.
     *
     * <p>Doit être refermé par {@link #close(GuiGraphicsExtractor)}, y compris si le dessin part en
     * exception — sans quoi la pile de transformations déborde et c'est l'interface entière du jeu
     * qui se déforme, bien après que cet écran soit fermé.
     */
    public void open(GuiGraphicsExtractor graphics) {
        graphics.pose().pushMatrix();
        if (reduced()) {
            graphics.pose().scale(this.factor, this.factor);
        }
    }

    public void close(GuiGraphicsExtractor graphics) {
        graphics.pose().popMatrix();
    }

    /**
     * Vide la file des infobulles <b>avant</b> de refermer l'espace réduit.
     *
     * <h2>Pourquoi une infobulle ne se contente pas de suivre le dessin</h2>
     *
     * <p>Un bouton de vanilla ne dessine pas son infobulle : il la <em>dépose</em>, et le jeu la
     * peint après coup, une fois l'écran terminé — donc une fois notre réduction refermée. Elle
     * serait alors peinte à taille pleine, mais <b>positionnée</b> d'après des coordonnées calculées
     * dans l'espace du panneau : d'autant plus loin du curseur que la réduction est forte.
     *
     * <p>On la peint donc nous-mêmes, ici, tant que l'espace réduit est encore ouvert. Le jeu
     * repassera ensuite et ne trouvera plus rien à peindre — c'est exactement l'effet recherché, et
     * c'est aussi pourquoi cet appel doit rester le dernier avant {@link #close(GuiGraphicsExtractor)}.
     *
     * @param mouseX l'abscisse DÉJÀ ramenée dans l'espace du panneau
     * @param mouseY son ordonnée
     */
    public void tooltips(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        // Tant que rien n'est réduit, les deux espaces se confondent : on laisse le jeu faire, et
        // l'on évite de toucher à une file qu'il sait très bien vider tout seul.
        if (!reduced()) {
            return;
        }
        graphics.extractDeferredElements(mouseX, mouseY, partial);
    }

    /**
     * Le même évènement de souris, ramené dans l'espace du panneau.
     *
     * <p>Un écran qui dessine tout à la main peut se contenter de {@link #x(double)} et
     * {@link #y(double)}. Dès qu'il porte un bouton ou une zone de saisie de vanilla, non : ces
     * composants reçoivent l'évènement <em>entier</em> et y lisent eux-mêmes les coordonnées. Leur
     * transmettre l'original reviendrait à les placer dans un espace et à les cliquer dans un autre.
     */
    public MouseButtonEvent event(MouseButtonEvent event) {
        if (!reduced()) {
            return event;
        }
        return new MouseButtonEvent(x(event.x()), y(event.y()), event.buttonInfo());
    }

    /** Une abscisse de souris, ramenée dans l'espace du panneau. */
    public double x(double mouseX) {
        return mouseX / this.factor;
    }

    /** Une ordonnée de souris, ramenée dans l'espace du panneau. */
    public double y(double mouseY) {
        return mouseY / this.factor;
    }
}
