package fr.clubcitrouille.lanterne.client.upscale;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * La matrice de reprojection : où se trouvait, à l'image précédente, ce qu'on voit maintenant.
 *
 * <h2>Pourquoi cette classe existe séparément des nuanceurs</h2>
 *
 * <p>Un remonteur temporel accumule plusieurs images basse résolution. Pour additionner deux images
 * il faut savoir <b>quel pixel de l'ancienne correspond à quel pixel de la nouvelle</b> — sans quoi
 * on mélange un mur avec le ciel et l'on obtient une traînée. C'est tout ce que fait cette classe :
 * elle réduit cette question à <b>une seule matrice</b>, que le nuanceur applique en une
 * multiplication.
 *
 * <p>Ce partage n'est pas cosmétique. L'algèbre ci-dessous est l'endroit où un portage à l'aveugle
 * se trompe, et c'est aussi le seul endroit qu'on puisse <b>éprouver sans regarder une image</b> :
 * on projette un point connu, on reprojette, on vérifie qu'on retombe dessus. Voir
 * {@code tools/EssaiReproject.java}. Le nuanceur, lui, ne fera qu'un produit matrice-vecteur, et
 * un produit matrice-vecteur ne se trompe pas.
 *
 * <h2>Le piège : la matrice de vue de Minecraft ne contient pas la caméra</h2>
 *
 * <p>Dans la plupart des moteurs, la matrice de vue contient la rotation <em>et</em> la position de
 * la caméra, et reprojeter se résume à {@code vueProjPrécédente × inverse(vueProjCourante)}.
 *
 * <p><b>En 26.2, ce n'est pas le cas.</b> Le champ s'appelle {@code viewRotationMatrix}, et il
 * porte bien son nom : il ne contient qu'une <b>rotation</b>. La position de la caméra est retirée
 * ailleurs — le monde est dessiné relativement à elle, pour que les coordonnées restent petites et
 * que la précision des flottants tienne à mille blocs de l'origine.
 *
 * <p>Conséquence : la formule naïve ne reprojette correctement que si la caméra <b>n'a pas
 * bougé</b>. Dès qu'elle avance, tout glisse. Il faut intercaler une translation du déplacement de
 * la caméra entre les deux :
 *
 * <pre>reprojection = vueProj(n-1) · T(caméra(n) − caméra(n-1)) · inverse(vueProj(n))</pre>
 *
 * <p>La démonstration tient en trois lignes. Soit {@code p} un point du monde :
 * l'inverse rend {@code p − caméra(n)} ; la translation en fait
 * {@code p − caméra(n) + caméra(n) − caméra(n-1) = p − caméra(n-1)} ; la matrice précédente le
 * projette. C'est exactement ce que l'épreuve mesure.
 *
 * <h2>Le second piège : le décalage ne doit pas entrer ici</h2>
 *
 * <p>Les matrices confiées à {@link #advance} doivent être <b>non décalées</b>. Le décalage de
 * {@link Jitter} déplace la grille d'échantillonnage, pas la scène : l'y inclure ferait croire au
 * remonteur que le monde entier tremble d'un demi-pixel par image, et il passerait son temps à
 * corriger un mouvement qui n'existe pas — c'est-à-dire à effacer très exactement l'information
 * qu'on avait ajoutée.
 */
public final class Reproject {
    private final Matrix4f previousViewProj = new Matrix4f();
    private final Matrix4f currentViewProj = new Matrix4f();
    private final Matrix4f reprojection = new Matrix4f();
    private final Matrix4f scratch = new Matrix4f();

    private double previousCamX;
    private double previousCamY;
    private double previousCamZ;

    /** Faux tant qu'aucune image précédente n'existe : à la toute première, il n'y a rien à mélanger. */
    private boolean primed;

    /**
     * Avance d'une image et calcule la matrice de reprojection.
     *
     * <p>À appeler <b>une fois par image</b>, avant que le monde ne soit dessiné, avec la
     * projection et la rotation de vue <b>non décalées</b>.
     *
     * <p>La position de la caméra est reçue en {@code double} et la différence n'est réduite en
     * {@code float} qu'<b>après</b> soustraction. L'ordre compte : à dix mille blocs de l'origine,
     * un {@code float} ne distingue plus deux positions séparées d'un millimètre, et convertir
     * d'abord reviendrait à perdre le déplacement qu'on cherche précisément à mesurer.
     *
     * @return vrai si la matrice est utilisable, faux à la première image ou après {@link #reset}
     */
    public boolean advance(Matrix4fc projection, Matrix4fc viewRotation,
            double camX, double camY, double camZ) {
        this.currentViewProj.set(projection).mul(viewRotation);
        boolean usable = this.primed;
        if (usable) {
            float dx = (float) (camX - this.previousCamX);
            float dy = (float) (camY - this.previousCamY);
            float dz = (float) (camZ - this.previousCamZ);
            // reprojection = vueProj(n-1) · T(delta) · inverse(vueProj(n)).
            // JOML post-multiplie : translate() puis mul() composent bien dans cet ordre.
            this.reprojection.set(this.previousViewProj)
                    .translate(dx, dy, dz)
                    .mul(this.currentViewProj.invert(this.scratch));
        }
        this.previousViewProj.set(this.currentViewProj);
        this.previousCamX = camX;
        this.previousCamY = camY;
        this.previousCamZ = camZ;
        this.primed = true;
        return usable;
    }

    /**
     * La matrice à confier au nuanceur.
     *
     * <p>Elle s'applique aux coordonnées normalisées du pixel courant complétées d'un 1 —
     * {@code vec4(ndcX, ndcY, ndcZ, 1.0)} — et le résultat redonne des coordonnées normalisées
     * après division par {@code w}. On peut employer les coordonnées normalisées plutôt que les
     * coordonnées de découpe parce que la division finale annule le facteur d'échelle qui les
     * sépare.
     *
     * <p>Rappel pour le nuanceur : en 26.2 la profondeur est <b>inversée</b>. Le proche vaut 1,0 et
     * le lointain 0,0, et la coordonnée normalisée en z se lit donc telle quelle dans le tampon,
     * sans la remise à l'échelle {@code 2·z−1} qu'on écrit par habitude.
     */
    public Matrix4fc matrix() {
        return this.reprojection;
    }

    /** Y a-t-il une image précédente à laquelle se raccrocher ? */
    public boolean primed() {
        return this.primed;
    }

    /**
     * Oublie l'image précédente.
     *
     * <p>À appeler quand l'historique accumulé cesse d'avoir un sens : changement de dimension,
     * téléportation, redimensionnement de la fenêtre. Sans cela, le remonteur mélangerait le Nether
     * avec l'Overworld pendant une dizaine d'images — ce qui se voit.
     */
    public void reset() {
        this.primed = false;
        this.reprojection.identity();
    }
}
