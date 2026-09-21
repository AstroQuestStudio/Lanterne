package fr.clubcitrouille.lanterne.render.terrain;

/**
 * Les attributs de sommet que les shaders Iris attendent du terrain, et d'où Lanterne les tire sans Sodium.
 * S'appuie sur {@code client.terrain.TerrainVertexFormat} (format étendu branché par
 * {@code RenderPipelinesMixin}) : liste de contrôle, chaque valeur dit d'où vient la donnée.
 */
public enum IrisVertexAttributes {
    /** {@code mc_Entity} : identifiant du bloc, connu au moment du maillage de la section. */
    BLOCK_ID("mc_Entity", Source.MESHER_BLOCK_STATE),
    /** {@code mc_midTexCoord} : centre du quad dans l'atlas, dérivé des UV du quad. */
    MID_TEX_COORD("mc_midTexCoord", Source.MESHER_QUAD),
    /** {@code at_tangent} : tangente du quad, dérivée de sa géométrie. */
    TANGENT("at_tangent", Source.MESHER_QUAD),
    /** {@code at_midBlock} : décalage du sommet au centre du bloc. */
    MID_BLOCK("at_midBlock", Source.MESHER_QUAD),
    /** {@code vaNormal} : normale de la face ; à défaut, dérivable en shader par dérivées. */
    NORMAL("vaNormal", Source.MESHER_QUAD_OR_SHADER);

    /** D'où la donnée est calculée. */
    public enum Source { MESHER_BLOCK_STATE, MESHER_QUAD, MESHER_QUAD_OR_SHADER }

    private final String shaderName;
    private final Source source;

    IrisVertexAttributes(String shaderName, Source source) {
        this.shaderName = shaderName;
        this.source = source;
    }

    /** Nom de l'attribut dans les shaders Iris. */
    public String shaderName() {
        return shaderName;
    }

    /** Origine de la donnée. */
    public Source source() {
        return source;
    }
}
