package net.irisshaders.iris.compat.vertexformat;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;

/** Le format de sommet du terrain quand le mailleur est celui de Minecraft : le format BLOCK vanilla. */
public final class VanillaVertexFormatSource implements VertexFormatSource {
	@Override
	public VertexFormat getVertexFormat() {
		return DefaultVertexFormat.BLOCK;
	}
}
