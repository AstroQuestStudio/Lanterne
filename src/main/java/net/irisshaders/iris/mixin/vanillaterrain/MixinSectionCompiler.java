package net.irisshaders.iris.mixin.vanillaterrain;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder;
import net.irisshaders.iris.vertices.VanillaTerrainContext;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Le mailleur de sections de Minecraft 26.3 ({@code SectionCompiler}) prévient Iris du bloc en
 * cours, comme Sodium le fait pour le sien : sans cela {@code mc_Entity} et {@code at_midBlock}
 * restent vides et un shaderpack ne reconnaît ni l'eau, ni les feuilles, ni les émetteurs de lumière.
 *
 * <p>Le mailleur tourne sur des fils de fond : le contexte est donc PAR FIL
 * ({@link VanillaTerrainContext}). Actif seulement si {@link VanillaTerrainContext#enabled()}.
 */
@Mixin(SectionCompiler.class)
public class MixinSectionCompiler {
	private static final String COMPILE = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderSectionRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;";

	/** Marque le fil comme « en train de mailler une section » : le format étendu est alors imposé. */
	@WrapMethod(method = COMPILE)
	private SectionCompiler.Results iris$meshingScope(net.minecraft.core.SectionPos sectionPos,
			net.minecraft.client.renderer.chunk.RenderSectionRegion region,
			com.mojang.blaze3d.vertex.VertexSorting sorting,
			net.minecraft.client.renderer.SectionBufferBuilderPack builders,
			java.util.List<net.neoforged.neoforge.client.event.AddSectionGeometryEvent.AdditionalSectionRenderer> additional,
			Operation<SectionCompiler.Results> original) {
		if (!VanillaTerrainContext.enabled()) {
			return original.call(sectionPos, region, sorting, builders, additional);
		}
		VanillaTerrainContext.setMeshing(true);
		try {
			return original.call(sectionPos, region, sorting, builders, additional);
		} finally {
			VanillaTerrainContext.setMeshing(false);
		}
	}

	@WrapOperation(method = COMPILE,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V"))
	private void iris$blockContext(net.minecraft.client.renderer.block.ModelBlockRenderer renderer,
			net.minecraft.client.renderer.block.BlockQuadOutput out, float x, float y, float z,
			net.minecraft.client.renderer.block.BlockAndTintGetter level, BlockPos pos, BlockState state,
			net.minecraft.client.renderer.block.dispatch.BlockStateModel model, long seed,
			Operation<Void> original) {
		if (VanillaTerrainContext.isMeshing() && WorldRenderingSettings.INSTANCE.getBlockStateIds() != null) {
			VanillaTerrainContext.begin(WorldRenderingSettings.INSTANCE.getBlockStateIds().getOrDefault(state, -1),
					(byte) 0, (byte) state.getLightEmission(), pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
		}
		try {
			original.call(renderer, out, x, y, z, level, pos, state, model, seed);
		} finally {
			VanillaTerrainContext.end();
		}
	}

	@WrapOperation(method = COMPILE,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
	private void iris$fluidContext(net.minecraft.client.renderer.block.FluidRenderer renderer,
			net.minecraft.client.renderer.block.BlockAndTintGetter level, BlockPos pos,
			net.minecraft.client.renderer.block.FluidRenderer.Output out, BlockState state,
			FluidState fluidState, Operation<Void> original) {
		if (VanillaTerrainContext.isMeshing() && WorldRenderingSettings.INSTANCE.getBlockStateIds() != null) {
			VanillaTerrainContext.begin(WorldRenderingSettings.INSTANCE.getBlockStateIds().getOrDefault(fluidState.createLegacyBlock(), -1),
					(byte) 1, (byte) state.getLightEmission(), pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
		}
		try {
			original.call(renderer, level, pos, out, state, fluidState);
		} finally {
			VanillaTerrainContext.end();
		}
	}

	/** Chaque fois que le mailleur prend le tampon d'une couche pour y écrire un quad : lui donner le bloc courant. */
	@Inject(method = "getOrBeginLayer", at = @At("RETURN"))
	private void iris$feedBuilder(java.util.Map<?, ?> started, net.minecraft.client.renderer.SectionBufferBuilderPack buffers,
			net.minecraft.client.renderer.chunk.ChunkSectionLayer layer, CallbackInfoReturnable<BufferBuilder> cir) {
		VanillaTerrainContext.Block block = VanillaTerrainContext.current();
		if (block.active && cir.getReturnValue() instanceof BlockSensitiveBufferBuilder sensitive) {
			sensitive.beginBlock(block.id, block.renderType, block.emission, block.x, block.y, block.z);
		}
	}
}
