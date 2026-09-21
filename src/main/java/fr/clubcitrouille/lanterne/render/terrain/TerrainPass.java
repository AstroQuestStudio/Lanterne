package fr.clubcitrouille.lanterne.render.terrain;

/**
 * Une passe de dessin du terrain. Les trois premières existent déjà dans vanilla
 * ({@code ChunkSectionLayer}) ; les deux dernières sont celles qu'Iris ajoute pour la carte d'ombres.
 */
public enum TerrainPass {
    /** Terrain opaque, vue de la caméra. */
    SOLID(false, false),
    /** Terrain à découpe (feuillage, herbe haute), vue de la caméra. */
    CUTOUT(false, false),
    /** Terrain translucide (eau, verre teinté), vue de la caméra, trié de l'arrière vers l'avant. */
    TRANSLUCENT(true, false),
    /** Terrain opaque vu depuis la lumière (carte d'ombres). */
    SHADOW_SOLID(false, true),
    /** Terrain à découpe vu depuis la lumière. */
    SHADOW_CUTOUT(false, true);

    private final boolean translucent;
    private final boolean shadow;

    TerrainPass(boolean translucent, boolean shadow) {
        this.translucent = translucent;
        this.shadow = shadow;
    }

    /** Vrai si les sections doivent être dessinées de l'arrière vers l'avant. */
    public boolean translucent() {
        return translucent;
    }

    /** Vrai si la passe est vue depuis la lumière plutôt que depuis la caméra. */
    public boolean shadow() {
        return shadow;
    }
}
