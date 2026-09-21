package net.irisshaders.iris.compat.sodium.bridge;

import net.caffeinemc.mods.sodium.api.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.FogStorage;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.irisshaders.iris.compat.vertexformat.SodiumVertexFormatSource;
import net.irisshaders.iris.compat.vertexformat.VertexFormatSource;
import net.irisshaders.iris.uniforms.IrisFogState;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4fc;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import java.io.IOException;
import java.util.List;

/**
 * Tout ce qui, dans Iris, touche encore reellement des types Sodium en dehors du moteur de terrain,
 * isole ici et nulle part ailleurs. Cette classe n'est JAMAIS chargee sans Sodium : seul
 * {@link IrisRenderBridge} l'appelle, apres avoir verifie la presence de Sodium.
 */
final class SodiumBridge {
	private SodiumBridge() {
	}

	static IrisFogState.Parameters fogParameters(GameRenderer gameRenderer) {
		FogParameters fog = ((FogStorage) gameRenderer).sodium$getFogParameters();
		if (fog == FogParameters.NONE) {
			return IrisFogState.Parameters.NONE;
		}
		return new IrisFogState.Parameters(fog.red(), fog.green(), fog.blue(), fog.alpha(),
				fog.environmentalStart(), fog.environmentalEnd());
	}

	static Matrix4fc levelProjection(GameRenderer gameRenderer) {
		return ((GameRendererStorage) gameRenderer).sodium$getProjectionMatrix();
	}

	static String chunksDebugString() {
		return SodiumWorldRenderer.instance().getChunksDebugString();
	}

	static boolean isCompactTerrainFormat(VertexFormat format) {
		return java.util.Objects.equals(format, ChunkMeshFormats.COMPACT.getVertexFormat());
	}

	static VertexFormatSource terrainVertexFormatSource() {
		return new SodiumVertexFormatSource(ChunkMeshFormats.COMPACT);
	}

	static void addChunkBindGroup(List<BindGroupLayout.UniformDescription> layouts) {
		layouts.addAll(ShaderChunkRenderer.BIND_GROUP.uniforms());
	}

	static void fixNoErrorContext() {
		SodiumClientMod.options().performance.useNoErrorGLContext = false;
		try {
			SodiumOptions.writeToDisk(SodiumClientMod.options());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	static boolean noErrorContextEnabled() {
		return SodiumClientMod.options().performance.useNoErrorGLContext;
	}

	static void markSpriteActive(net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
		SpriteUtil.INSTANCE.markSpriteActive(sprite);
	}
}
