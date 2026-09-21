package net.irisshaders.iris.compat.vertexformat;

import com.mojang.renderpearl.api.vertex.VertexFormat;

/**
 * Club Citrouille : abstraction neutre sur net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType,
 * introduite pour que WorldRenderingSettings (charge tres tot, INSTANCE = static field) n'ait plus besoin
 * de referencer un type Sodium au niveau de sa propre declaration de champ. Voir SodiumVertexFormatSource
 * pour l'implementation reelle utilisee quand Sodium est present (toujours le cas cette nuit).
 */
public interface VertexFormatSource {
	VertexFormat getVertexFormat();
}
