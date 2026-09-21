package fr.clubcitrouille.lanterne.render.terrain;

/**
 * Ce qu'on mesure d'une image du moteur de terrain, pour prouver chaque optimisation par un avant/après.
 *
 * @param sectionsDrawn sections dessinées (toutes passes confondues)
 * @param drawCommands commandes de dessin émises
 * @param indicesSubmitted index soumis au GPU
 * @param shadowRedrawn vrai si la carte d'ombres a été redessinée cette image
 * @param cpuNanosBuildingLists temps passé à construire les listes, en nanosecondes
 */
public record TerrainMetrics(int sectionsDrawn, int drawCommands, long indicesSubmitted, boolean shadowRedrawn,
        long cpuNanosBuildingLists) {
    /** Image vide : rien n'a été dessiné. */
    public static final TerrainMetrics NONE = new TerrainMetrics(0, 0, 0L, false, 0L);
}
