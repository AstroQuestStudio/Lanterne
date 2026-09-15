package fr.clubcitrouille.lanterne.client.ponder;

/**
 * Les courbes : de quoi passer d'une valeur à une autre sans que cela se voie.
 *
 * <h2>Pourquoi un fichier entier pour six formules</h2>
 *
 * <p>Parce que c'est la différence entre une animation qu'on regarde et une animation qu'on subit, et
 * que cette différence ne se voit jamais dans le code. Un objet qui traverse l'écran à vitesse
 * constante ne ressemble à rien de connu : rien, dans le monde, ne démarre à pleine vitesse ni ne
 * s'arrête net. L'œil le sait avant qu'on ait su le dire, et conclut que c'est mal fait.
 *
 * <p>Ponder, dont ce paquet s'inspire, n'a que trois courbes en tout et pour tout — une décroissance
 * exponentielle, une rampe linéaire, et des {@code fade * fade} écrits à la main là où il en fallait
 * une troisième. C'est le seul endroit où on peut faire mieux que lui pour presque rien : six
 * fonctions d'une ligne, et chaque mouvement peut recevoir la courbe qui lui convient.
 *
 * <h2>Le vocabulaire</h2>
 *
 * <p>Toutes prennent un avancement entre 0 et 1 et rendent un avancement entre 0 et 1 — sauf
 * {@link #back}, qui dépasse volontairement. Toutes valent exactement 0 en 0 et exactement 1 en 1 :
 * c'est ce qui garantit qu'un mouvement arrive bien là où on l'a envoyé, et qu'il n'y reste pas à
 * frémir d'un demi-pixel une fois terminé.
 */
public final class Ease {
    private Ease() {}

    /** Ramène un avancement dans ses bornes. À appeler avant toute courbe : aucune ne se protège. */
    public static float clamp(float t) {
        return t < 0f ? 0f : t > 1f ? 1f : t;
    }

    /** L'avancement d'un intervalle de temps, déjà borné. Rend 1 si l'intervalle est vide. */
    public static float over(float now, float from, float to) {
        return to <= from ? 1f : clamp((now - from) / (to - from));
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Le pas doux : départ lent, arrivée lente.
     *
     * <p>La courbe à employer par défaut, et de très loin la plus utile ici — c'est celle d'une caméra
     * qu'on déplace. Un mouvement de caméra qui démarre sec donne la nausée ; celui-ci glisse.
     */
    public static float smooth(float t) {
        t = clamp(t);
        return t * t * (3f - 2f * t);
    }

    /**
     * L'arrivée douce : départ franc, arrivée lente.
     *
     * <p>Pour tout ce qui <em>tombe</em> ou qu'on <em>lance</em> : le mouvement a déjà sa vitesse
     * quand on le voit apparaître, et c'est la fin qu'on regarde.
     */
    public static float out(float t) {
        t = 1f - clamp(t);
        return 1f - t * t * t;
    }

    /** Le départ doux : l'inverse. Pour ce qui s'en va, et qu'on ne doit pas regarder partir. */
    public static float in(float t) {
        t = clamp(t);
        return t * t * t;
    }

    /**
     * Le dépassement : va au-delà de la cible, puis y revient.
     *
     * <p><b>Rend des valeurs supérieures à 1.</b> C'est le geste d'un bloc qu'on pose — il s'écrase
     * une fraction de seconde en touchant le sol. C'est minuscule et c'est ce qui fait qu'on croit au
     * poids de l'objet.
     */
    public static float back(float t) {
        t = 1f - clamp(t);
        return 1f - (t * t * (2.70158f * t - 1.70158f));
    }

    /**
     * L'aller-retour : 0 au début, 1 au milieu, 0 à la fin.
     *
     * <p>Pour tout ce qui doit attirer l'œil puis se retirer sans qu'on ait à écrire deux temps — une
     * étincelle, une pulsation de surbrillance, l'ouverture puis la fermeture d'une bulle de texte.
     */
    public static float pulse(float t) {
        t = clamp(t);
        return smooth(t < 0.5f ? t * 2f : (1f - t) * 2f);
    }
}
