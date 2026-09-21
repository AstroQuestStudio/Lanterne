package net.irisshaders.iris.compat.sodium.bridge;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.compat.vertexformat.VertexFormatSource;
import net.irisshaders.iris.mixin.IrisMixinPlugin;
import net.irisshaders.iris.uniforms.IrisFogState;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Le seul point par lequel le code d'Iris (hors moteur de terrain) demande quelque chose qui,
 * historiquement, venait de Sodium. Cette classe n'importe AUCUN type Sodium : elle peut donc etre
 * chargee sans Sodium. Chaque methode a deux voies :
 *
 * <ul>
 *   <li>Sodium present : delegation a {@link SodiumBridge} (chargee seulement a ce moment, donc
 *       comportement strictement identique a avant) ;</li>
 *   <li>Sodium absent : equivalent vanilla ecrit pour Lanterne.</li>
 * </ul>
 */
public final class IrisRenderBridge {
	private IrisRenderBridge() {
	}

	private static boolean sodium() {
		return IrisMixinPlugin.sodiumPresent();
	}

	/** Brouillard de la derniere image : valeur de Sodium si present, sinon celle capturee par vanilla. */
	public static IrisFogState.Parameters fogParameters(IrisFogState.Parameters captured) {
		return sodium() ? SodiumBridge.fogParameters(net.minecraft.client.Minecraft.getInstance().gameRenderer) : captured;
	}

	/** Matrice de projection du niveau (avant correction du Z inverse). Jamais {@code null}. */
	public static Matrix4fc levelProjection(GameRenderer gameRenderer, CameraRenderState cameraState) {
		if (sodium()) {
			return SodiumBridge.levelProjection(gameRenderer);
		}
		return cameraState.projectionMatrix != null ? cameraState.projectionMatrix : new Matrix4f();
	}

	/** Ligne de debug sur les chunks des ombres, ou {@code null} sans moteur qui la fournisse. */
	public static String shadowChunksDebugString() {
		return sodium() ? SodiumBridge.chunksDebugString() : null;
	}

	/** Ce format est-il le format compact du terrain de Sodium ? Toujours faux sans Sodium. */
	public static boolean isCompactTerrainFormat(VertexFormat format) {
		return sodium() && SodiumBridge.isCompactTerrainFormat(format);
	}

	/** Source du format de sommet du terrain : compact (Sodium) ou format de bloc vanilla. */
	public static VertexFormatSource terrainVertexFormatSource() {
		return sodium() ? SodiumBridge.terrainVertexFormatSource() : VanillaVertexFormatSource.INSTANCE;
	}

	/** Ajoute le groupe de liaison du rendu de terrain de Sodium, si Sodium est la. */
	public static void addChunkBindGroup(java.util.List<com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription> layouts) {
		if (sodium()) {
			SodiumBridge.addChunkBindGroup(layouts);
		}
	}

	/** L option « contexte GL sans verification d erreur » de Sodium est-elle active ? Faux sans Sodium. */
	public static boolean noErrorContextEnabled() {
		return sodium() && SodiumBridge.noErrorContextEnabled();
	}

	/** Corrige, si Sodium est la, son option de contexte GL sans verification d erreur. */
	public static void fixNoErrorContext() {
		if (sodium()) {
			SodiumBridge.fixNoErrorContext();
		}
	}

	/** Marque un sprite actif aupres de Sodium (animation), sans effet sans Sodium. */
	public static void markSpriteActive(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
		if (sodium()) {
			SodiumBridge.markSpriteActive(sprite);
		}
	}

	/** Frustum qui ne coupe rien : version Sodium (interfaces de viewport) ou version vanilla pure. */
	public static net.minecraft.client.renderer.culling.Frustum newNonCullingFrustum(Matrix4fc modelView, Matrix4f projection) {
		return sodium()
				? SodiumBridge.newNonCullingFrustum(modelView, projection)
				: new net.irisshaders.iris.helpers.VanillaNonCullingFrustum(modelView, projection);
	}

	/** Format de bloc vanilla : ce que fournit le maillage vanilla quand Sodium est absent. */
	private static final class VanillaVertexFormatSource implements VertexFormatSource {
		static final VanillaVertexFormatSource INSTANCE = new VanillaVertexFormatSource();

		@Override
		public VertexFormat getVertexFormat() {
			return DefaultVertexFormat.BLOCK;
		}
	}
}
