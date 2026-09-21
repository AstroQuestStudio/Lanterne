package net.irisshaders.iris.compat.sodium.mixin;

import net.caffeinemc.mods.sodium.client.gui.SodiumOptions;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.vertexformat.SodiumVertexFormatSource;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(RenderSectionManager.class)
public class MixinRenderSectionManager {
	@ModifyArg(method = "<init>", remap = false,
		at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer;<init>(Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V"))
	private ChunkVertexType iris$useExtendedVertexFormat$1(ChunkVertexType vertexType) {
		// Club Citrouille : mixin cible une classe Sodium, ne s'applique que si Sodium est present -
		// l'instance est donc toujours un SodiumVertexFormatSource ici, comportement inchange.
		var source = WorldRenderingSettings.INSTANCE.getVertexFormat();
		if (source instanceof SodiumVertexFormatSource sodium) {
			return sodium.sodiumDelegate();
		}
		return vertexType;
	}

	@ModifyArg(method = "<init>",
		at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkBuilder;<init>(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V"))
	private ChunkVertexType iris$useExtendedVertexFormat$2(ChunkVertexType vertexType) {
		var source = WorldRenderingSettings.INSTANCE.getVertexFormat();
		if (source instanceof SodiumVertexFormatSource sodium) {
			return sodium.sodiumDelegate();
		}
		return vertexType;
	}

	@Redirect(method = "getSearchDistance", remap = false,
		at = @At(value = "FIELD",
			target = "Lnet/caffeinemc/mods/sodium/client/gui/SodiumOptions$PerformanceSettings;useFogOcclusion:Z",
			remap = false))
	private boolean iris$disableFogOcclusion(SodiumOptions.PerformanceSettings instance) {
		if (Iris.getCurrentPack().isPresent()) {
			return false;
		} else {
			return instance.useFogOcclusion;
		}
	}
}
