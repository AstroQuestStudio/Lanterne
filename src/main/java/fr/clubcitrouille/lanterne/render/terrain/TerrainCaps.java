package fr.clubcitrouille.lanterne.render.terrain;

/**
 * Ce que le périphérique GPU actif sait faire, lu une fois au démarrage depuis les
 * {@code DeviceFeatures} / {@code DeviceLimits} de vanilla ({@code com.mojang.renderpearl.api.device}).
 * Les backends GL et Vulkan sont déjà derrière cette abstraction : il n'y a pas de couche « backend » à
 * écrire, seulement des choix selon ces capacités.
 *
 * @param vulkan vrai si le backend actif est Vulkan
 * @param multiDrawIndirect dessin multi-lots indirect disponible
 * @param drawIndirect dessin indirect simple disponible
 * @param persistentMapping mappage persistant des tampons disponible
 * @param maxIndirectDrawCount nombre maximal de commandes par appel indirect
 */
public record TerrainCaps(boolean vulkan, boolean multiDrawIndirect, boolean drawIndirect,
        boolean persistentMapping, int maxIndirectDrawCount) {}
