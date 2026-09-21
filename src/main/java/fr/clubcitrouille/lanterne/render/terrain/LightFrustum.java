package fr.clubcitrouille.lanterne.render.terrain;

/**
 * Le volume vu par la lumière pour la carte d'ombres. Iris en calcule déjà une version (voir
 * {@code net.irisshaders.iris.shadows.frustum}) ; cette interface est ce que le sélecteur d'ombres
 * demande, pour ne pas dépendre de la classe Iris concrète.
 */
public interface LightFrustum {
    /**
     * Le cube de 16 blocs de la section {@code (sx, sy, sz)} (en coordonnées de section) touche-t-il le volume ?
     * Faux négatif interdit (une ombre manquante se voit), faux positif toléré (un coût en plus).
     */
    boolean intersectsSection(int sx, int sy, int sz);

    /** Distance maximale d'ombre, en blocs, au-delà de laquelle on ne dessine plus. */
    double maxDistance();
}
