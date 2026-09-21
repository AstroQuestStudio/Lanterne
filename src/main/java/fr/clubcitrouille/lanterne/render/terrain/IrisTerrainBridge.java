package fr.clubcitrouille.lanterne.render.terrain;

/**
 * Couche (e) : le pont entre le moteur de terrain et les pipelines Iris. C'est la seule interface qu'Iris
 * appelle pour le terrain ; elle remplace tous les usages de {@code net.caffeinemc.*} dans
 * {@code net.irisshaders}.
 *
 * <p>Iris ne connaît ni les tampons partagés ni les sections : il demande « dessine la passe X avec ce
 * programme », et le pont s'en charge.
 */
public interface IrisTerrainBridge {
    /**
     * Prépare la liste de la passe de caméra {@code pass} pour l'image en cours (réutilise le travail de vanilla).
     */
    DrawList prepareCamera(TerrainPass pass);

    /**
     * Prépare la liste d'une passe d'ombre, ou renvoie {@code null} si {@link ShadowCachePolicy} juge la
     * carte précédente réutilisable.
     */
    DrawList prepareShadow(TerrainPass pass, LightFrustum light);

    /** Statistiques de la dernière image, pour l'écran de diagnostic (Radiographie). */
    TerrainMetrics lastFrameMetrics();
}
