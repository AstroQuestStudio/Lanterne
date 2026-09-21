package net.irisshaders.iris.uniforms;

import net.irisshaders.iris.compat.sodium.bridge.IrisRenderBridge;
import net.minecraft.client.renderer.fog.FogData;

/**
 * Le brouillard de la derniere image, tel que vanilla l'a calcule ({@link FogData}).
 *
 * <p>Avant, Iris le lisait dans un champ ajoute a {@code GameRenderer} par Sodium. Sans Sodium, il
 * est capture ici par {@code MixinFogRenderer} au retour de {@code setupFog}. Avec Sodium present,
 * on continue de lire la valeur de Sodium (comportement inchange), via le pont isole.
 */
public final class IrisFogState {
	/** Valeurs de brouillard : couleur RGBA et bornes de l'environnement. */
	public record Parameters(float red, float green, float blue, float alpha,
							 float environmentalStart, float environmentalEnd) {
		public static final Parameters NONE = new Parameters(0f, 0f, 0f, 0f, Float.MAX_VALUE, Float.MAX_VALUE);
	}

	private static volatile Parameters captured = Parameters.NONE;

	private IrisFogState() {
	}

	/** Appele par le mixin de {@code FogRenderer} avec le resultat de {@code setupFog}. */
	public static void capture(FogData data) {
		if (data == null) {
			captured = Parameters.NONE;
			return;
		}
		captured = new Parameters(data.color.x, data.color.y, data.color.z, data.color.w,
				data.environmentalStart, data.environmentalEnd);
	}

	public static Parameters current() {
		return IrisRenderBridge.fogParameters(captured);
	}
}
