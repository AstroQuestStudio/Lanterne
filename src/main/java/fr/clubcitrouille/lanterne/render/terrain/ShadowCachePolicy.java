package fr.clubcitrouille.lanterne.render.terrain;

/**
 * Décide si la carte d'ombres doit être redessinée cette image, ou si celle de l'image précédente est
 * réutilisable (technique publique de mise en cache des cartes d'ombres : recalculer moins souvent quand
 * ni la lumière ni la géométrie proche n'ont bougé).
 *
 * <p>Le critère est volontairement conservateur : au moindre doute, on redessine.
 */
public interface ShadowCachePolicy {
    /**
     * @param sunAngleDelta variation de l'angle du soleil depuis le dernier dessin, en degrés
     * @param cameraMovedBlocks distance parcourue par la caméra depuis le dernier dessin
     * @param sectionsChangedNearCamera vrai si une section proche a été reconstruite depuis
     * @param framesSinceRedraw images écoulées depuis le dernier dessin
     * @return vrai si la carte d'ombres doit être redessinée maintenant
     */
    boolean mustRedraw(double sunAngleDelta, double cameraMovedBlocks, boolean sectionsChangedNearCamera,
            int framesSinceRedraw);
}
