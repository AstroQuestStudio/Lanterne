package net.irisshaders.iris.compat.sodium.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.compat.vertexformat.SodiumVertexFormatSource;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(ChunkMeshFormats.class)
public class MixinChunkMeshFormats {
	/**
	 * @author
	 * @reason
	 */
	@Overwrite
	public static ChunkVertexType getCurrent() {
		// Club Citrouille : WorldRenderingSettings.getVertexFormat() retourne desormais l'abstraction
		// neutre VertexFormatSource - ce mixin ne s'applique de toute facon que si Sodium (la classe
		// ciblee, ChunkMeshFormats) est present, donc l'instance est toujours un SodiumVertexFormatSource
		// ici, exactement comme avant ce refactor.
		var source = WorldRenderingSettings.INSTANCE.getVertexFormat();
		if (source instanceof SodiumVertexFormatSource sodium) {
			return sodium.sodiumDelegate();
		}
		return ChunkMeshFormats.COMPACT;
	}
}
