package net.irisshaders.iris.helpers;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Frustum qui ne coupe rien, sans AUCUNE reference a un mod de rendu tiers : il sert quand un
 * shaderpack demande de desactiver le culling par frustum et que Sodium n'est pas la. Son pendant
 * {@code shadows.frustum.fallback.NonCullingFrustum} implemente en plus les interfaces de Sodium, ce
 * qui empeche de le charger sans lui.
 */
public class VanillaNonCullingFrustum extends Frustum {
	public VanillaNonCullingFrustum(final Matrix4fc modelViewMatrix, final Matrix4f projectionMatrixForCulling) {
		super(modelViewMatrix, projectionMatrixForCulling);
	}

	@Override
	public boolean isVisible(AABB box) {
		return true;
	}

	@Override
	public int cubeInFrustum(BoundingBox boundingBox) {
		return FrustumIntersection.INSIDE;
	}

	@Override
	public boolean pointInFrustum(double x, double y, double z) {
		return true;
	}
}
