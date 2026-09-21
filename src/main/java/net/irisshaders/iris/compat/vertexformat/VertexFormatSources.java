package net.irisshaders.iris.compat.vertexformat;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.vertices.VanillaTerrainContext;

/**
 * Point d'entrée unique pour obtenir la source de format de sommet du terrain. Les classes Sodium
 * sont dans {@link SodiumHolder} et ne sont chargées QUE si Sodium est présent : une classe qui
 * référence un type absent ne doit pas être chargée, sinon {@code NoClassDefFoundError}.
 */
public final class VertexFormatSources {
	private VertexFormatSources() {}

	public static VertexFormatSource terrain() {
		return VanillaTerrainContext.sodiumPresent() ? SodiumHolder.source() : new VanillaVertexFormatSource();
	}

	/** Le format « compact » de Sodium, ou {@code null} sans Sodium. */
	public static VertexFormat sodiumCompactOrNull() {
		return VanillaTerrainContext.sodiumPresent() ? SodiumHolder.compact() : null;
	}

	private static final class SodiumHolder {
		static VertexFormatSource source() {
			return new SodiumVertexFormatSource(
					net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats.COMPACT);
		}

		static VertexFormat compact() {
			return net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats.COMPACT
					.getVertexFormat();
		}
	}
}
