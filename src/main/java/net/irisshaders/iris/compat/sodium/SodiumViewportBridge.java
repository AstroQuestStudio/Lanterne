package net.irisshaders.iris.compat.sodium;

import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import net.irisshaders.iris.shadows.frustum.SectionCullingFrustum;

/**
 * Pont fin vers Sodium : crée son {@link Viewport} à partir d'un frustum d'ombre neutre. Cette classe est la SEULE à
 * toucher aux types de viewport de Sodium pour les ombres ; elle n'est chargée que sur le chemin « Sodium présent »
 * de {@code ShadowRenderer}.
 */
public final class SodiumViewportBridge {
	private SodiumViewportBridge() {
	}

	public static Viewport create(SectionCullingFrustum frustum) {
		return new Viewport(new Adapter(frustum), frustum.viewportOrigin());
	}

	private record Adapter(SectionCullingFrustum delegate) implements Frustum {
		@Override
		public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			return delegate.testAab(minX, minY, minZ, maxX, maxY, maxZ);
		}

		@Override
		public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
			return delegate.intersectAab(minX, minY, minZ, maxX, maxY, maxZ);
		}

		@Override
		public boolean testSection(float x, float y, float z) {
			return delegate.testSection(x, y, z);
		}

		@Override
		public boolean testSectionExpanded(float x, float y, float z, float extend) {
			return delegate.testSectionExpanded(x, y, z, extend);
		}
	}
}
