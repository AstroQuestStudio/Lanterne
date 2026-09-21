package fr.clubcitrouille.lanterne.render.terrain;

/**
 * Couche (c) du moteur : choisit les sections à dessiner pour une passe.
 *
 * <p>Pour les passes de caméra, l'implémentation par défaut réutilise le résultat de vanilla
 * ({@code LevelRenderer.visibleSections}, issu du graphe d'occlusion) : rien à réécrire. Pour les passes
 * d'ombres, c'est l'apport de Lanterne : parcourir les sections chargées et retenir celles que
 * {@link LightFrustum} touche, sans passer par le graphe d'occlusion de la caméra (une section cachée
 * de la caméra peut très bien porter une ombre visible).
 */
public interface DrawListBuilder {
    /**
     * Construit la liste d'une passe.
     *
     * @param pass la passe
     * @param light le volume de lumière pour une passe d'ombre, {@code null} pour une passe de caméra
     */
    DrawList build(TerrainPass pass, LightFrustum light);
}
