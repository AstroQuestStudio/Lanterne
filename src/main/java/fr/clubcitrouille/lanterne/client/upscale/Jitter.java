package fr.clubcitrouille.lanterne.client.upscale;

import org.joml.Matrix4f;

/**
 * Le décalage sous-pixellaire de la matrice de projection — ce que tout remonteur temporel exige.
 *
 * <h2>Pourquoi un remonteur temporel ne peut pas s'en passer</h2>
 *
 * <p>FSR 1.0 regarde une image et en déduit une image plus grande : toute l'information dont il
 * dispose est dans le pixel et ses voisins. Un remonteur <b>temporel</b> — DLSS, FSR 2, TAAU — fait
 * autre chose : il accumule plusieurs images basse résolution pour en reconstituer une seule de
 * haute résolution. Cela ne marche qu'à une condition : que chaque image apporte une information
 * <b>nouvelle</b>.
 *
 * <p>Or une caméra immobile rend exactement la même image à chaque fois. Les mêmes centres de
 * pixels échantillonnent les mêmes points de la scène, et accumuler cent images identiques ne donne
 * rien de plus qu'une seule. Le décalage est ce qui brise cette identité : on déplace la grille
 * d'échantillonnage d'une fraction de pixel à chaque image, de sorte que la scène soit sondée à un
 * endroit légèrement différent. En huit ou seize images, les sondes couvrent la surface d'un pixel
 * basse résolution, et c'est de là — et de nulle part ailleurs — que sort la netteté.
 *
 * <h2>Pourquoi Halton et non un tirage au sort</h2>
 *
 * <p>Un tirage aléatoire laisse des trous et des grappes : sur seize images, deux sondes tombent au
 * même endroit et un coin du pixel n'est jamais visité. La suite de Halton est une suite à
 * <b>discrépance faible</b> : chaque nouveau terme se place là où la place manque le plus. Sur un
 * nombre quelconque d'images, la couverture est plus régulière qu'un tirage au sort, et — ce qui
 * compte autant — elle est <b>déterministe</b> : deux machines calculent la même suite, et un
 * défaut se reproduit au lieu de scintiller une fois sur dix.
 *
 * <p>Les bases 2 et 3 sont le choix universel (DLSS, FSR 2, Unreal, Unity) : ce sont les deux plus
 * petits nombres premiers, donc les deux suites qui se corrèlent le moins entre elles.
 *
 * <h2>Pourquoi cette classe ne décale rien toute seule</h2>
 *
 * <p>Un décalage <b>sans</b> remonteur temporel pour le consommer ne fait pas « rien » : il fait
 * <b>pire</b>. L'image tremble d'un demi-pixel soixante fois par seconde, visiblement, et aucune
 * accumulation ne vient racheter ce tremblement. Cette classe est donc un calcul pur qu'on interroge
 * ; c'est l'appelant — et lui seul, quand il a réellement de quoi accumuler — qui décide de
 * l'appliquer. Tant que le mod n'a que FSR 1.0, personne ne l'appelle, et c'est correct.
 *
 * <h2>Le signe est une convention, et il est rendu explicite</h2>
 *
 * <p>Chaque remonteur attend le décalage dans son propre repère : DLSS veut des pixels de la cible
 * <em>basse</em> résolution, FSR 2 aussi mais avec l'axe vertical parfois retourné, et la matrice de
 * projection raisonne en coordonnées normalisées. Se tromper de signe ne produit pas une erreur : ça
 * produit une image qui traîne au lieu de s'affiner, et on cherche des heures.
 *
 * <p>Cette classe rend donc {@link #offsetX()} et {@link #offsetY()} <b>en pixels de la cible de
 * rendu</b>, centrés sur zéro, et n'applique la conversion vers la matrice que dans
 * {@link #applyTo(Matrix4f, int, int)}, dont le javadoc dit exactement quelle convention elle suit.
 * Le consommateur natif reçoit les pixels bruts et fait sa propre conversion.
 */
public final class Jitter {
    /**
     * Le nombre d'images au bout duquel la suite reboucle.
     *
     * <p>NVIDIA recommande {@code 8 × (largeurAffichée / largeurRendue)²} : plus on agrandit, plus
     * il faut de sondes pour couvrir un pixel basse résolution. Ce plafond évite qu'un facteur
     * d'échelle extrême demande une période si longue que l'image mette une seconde à se stabiliser
     * — une seconde de flou après chaque mouvement de tête est pire que le gain.
     */
    private static final int PERIOD_CAP = 64;

    /** En deçà, la couverture est trop grossière pour qu'une accumulation serve à quelque chose. */
    private static final int PERIOD_FLOOR = 8;

    private int period = PERIOD_FLOOR;
    private int index;
    private float offsetX;
    private float offsetY;

    /**
     * Règle la période sur le facteur d'échelle en cours.
     *
     * <p>À rappeler quand la cible de rendu change de taille — ce qui arrive à chaque cran de la
     * houle adaptative, et non seulement au redimensionnement de la fenêtre.
     *
     * @param renderWidth  largeur à laquelle le monde est réellement rendu
     * @param displayWidth largeur à laquelle il sera présenté
     */
    public void retune(int renderWidth, int displayWidth) {
        if (renderWidth <= 0 || displayWidth <= 0) {
            this.period = PERIOD_FLOOR;
            return;
        }
        float ratio = (float) displayWidth / renderWidth;
        int wanted = Math.round(8f * ratio * ratio);
        this.period = Math.clamp(wanted, PERIOD_FLOOR, PERIOD_CAP);
    }

    /**
     * Avance d'une image et calcule le décalage suivant.
     *
     * <p>L'indice court de 1 à la période incluse et non de 0 : {@code halton(0)} vaut zéro dans
     * toutes les bases, c'est-à-dire un décalage nul — une image sur {@code period} n'apporterait
     * alors rien de nouveau, ce qui est exactement ce que la suite est censée empêcher.
     */
    public void advance() {
        this.index = this.index % this.period + 1;
        this.offsetX = halton(this.index, 2) - 0.5f;
        this.offsetY = halton(this.index, 3) - 0.5f;
    }

    /** Remet la suite à son début : à appeler quand l'historique accumulé devient invalide. */
    public void reset() {
        this.index = 0;
        this.offsetX = 0f;
        this.offsetY = 0f;
    }

    /** Le décalage horizontal de l'image courante, en pixels de la cible de rendu, dans [-0,5 ; 0,5[. */
    public float offsetX() {
        return this.offsetX;
    }

    /** Le décalage vertical de l'image courante, en pixels de la cible de rendu, dans [-0,5 ; 0,5[. */
    public float offsetY() {
        return this.offsetY;
    }

    /** Le numéro de l'image dans la période — utile au journal, et à personne d'autre. */
    public int index() {
        return this.index;
    }

    /** La longueur de la période en cours. */
    public int period() {
        return this.period;
    }

    /**
     * Décale une matrice de projection déjà construite.
     *
     * <h2>La convention, écrite noir sur blanc</h2>
     *
     * <p>Après division perspective, l'abscisse normalisée d'un point vaut
     * {@code x_ndc = -(m00·Xe)/Ze - m20}. Ajouter {@code d} à {@code m20} déplace donc toute l'image
     * de {@code -d} en coordonnées normalisées. Comme l'intervalle {@code [-1 ; 1]} couvre
     * {@code renderWidth} pixels, un pixel vaut {@code 2/renderWidth}.
     *
     * <p>Pour que l'image se déplace de {@code +offsetX} pixels vers la droite, on pose donc
     * {@code m20 -= 2·offsetX/renderWidth}. Même raisonnement en ordonnée avec {@code m21}.
     *
     * <p>La matrice est modifiée <b>sur place</b>, et c'est voulu : le point d'insertion visé est la
     * copie locale de {@code GameRenderer.renderLevel} (ligne 539 en 26.2), dont la surcharge
     * {@code ProjectionMatrixBuffer.getBuffer(Matrix4f)} qui la consomme ligne 561 n'a aucun cache
     * de version et réécrit son tampon à chaque appel. Rien à contourner.
     *
     * <p>Seuls {@code m20} et {@code m21} bougent : la profondeur n'est pas touchée, ce qui importe
     * en 26.2 où elle est <b>inversée</b> (le proche vaut 1,0 et le lointain 0,0, comme le prouve
     * l'effacement à {@code 0.0}).
     *
     * @return la matrice reçue, pour chaîner
     */
    public Matrix4f applyTo(Matrix4f projection, int renderWidth, int renderHeight) {
        if (renderWidth <= 0 || renderHeight <= 0) {
            return projection;
        }
        projection.m20(projection.m20() - 2f * this.offsetX / renderWidth);
        projection.m21(projection.m21() - 2f * this.offsetY / renderHeight);
        return projection;
    }

    /**
     * Le terme d'indice {@code index} de la suite de Halton en base {@code base}, dans {@code [0;1[}.
     *
     * <p>Le principe tient en une phrase : on écrit l'indice en base {@code base}, puis on
     * <b>retourne ses chiffres autour de la virgule</b>. L'indice 5 en base 2 s'écrit {@code 101},
     * qui retourné donne {@code 0,101} soit {@code 0,625}. Chaque chiffre de plus divise l'intervalle
     * en {@code base} parts et y place le point qui manquait — d'où la discrépance faible.
     */
    static float halton(int index, int base) {
        float result = 0f;
        float fraction = 1f / base;
        int rest = index;
        while (rest > 0) {
            result += fraction * (rest % base);
            rest /= base;
            fraction /= base;
        }
        return result;
    }
}
