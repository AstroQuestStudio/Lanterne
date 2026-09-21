package net.irisshaders.iris.compat.vertexformat;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

/**
 * Club Citrouille : seule classe qui reference encore ChunkVertexType comme TYPE (champ/parametre),
 * isolee du meme motif que SodiumVertexSerializers - jamais chargee tant qu'un appelant ne l'a pas
 * explicitement instanciee. Les deux seuls appelants restants (IrisRenderingPipeline avec shaderpack,
 * VanillaRenderingPipeline en repli) referencent deja Sodium directement pour calculer le format
 * (FormatAnalyzer.createFormat / ChunkMeshFormats.COMPACT) - cette classe ne fait qu'eviter que
 * WorldRenderingSettings, chargee bien plus tot et bien plus souvent, porte elle aussi cette dependance.
 */
public final class SodiumVertexFormatSource implements VertexFormatSource {
	private final ChunkVertexType delegate;

	public SodiumVertexFormatSource(ChunkVertexType delegate) {
		this.delegate = delegate;
	}

	@Override
	public VertexFormat getVertexFormat() {
		return delegate.getVertexFormat();
	}

	public ChunkVertexType sodiumDelegate() {
		return delegate;
	}
}
