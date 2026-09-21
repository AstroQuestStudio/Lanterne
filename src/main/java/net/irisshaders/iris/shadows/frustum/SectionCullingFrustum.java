package net.irisshaders.iris.shadows.frustum;

import org.joml.Vector3d;

/**
 * Interface NEUTRE des frustums d'ombre : les quatre tests de boîte dont le parcours des sections a besoin, plus
 * l'origine de la vue. Elle remplace les interfaces de Sodium (ViewportProvider, frustum.Frustum) que ces classes
 * implémentaient : sans Sodium, aucune classe d'ombre ne référence plus un type Sodium (sinon la transformation de la
 * classe échoue avec « Cannot find class ...ViewportProvider »). Avec Sodium, {@code compat.sodium.SodiumViewportBridge}
 * adapte cette interface à la sienne, dans une classe chargée seulement si Sodium est présent.
 */
public interface SectionCullingFrustum {
	boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);

	int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ);

	boolean testSection(float x, float y, float z);

	boolean testSectionExpanded(float x, float y, float z, float extend);

	/** Position de la caméra (origine de la vue) telle que préparée par {@code prepare(x, y, z)}. */
	Vector3d viewportOrigin();
}
