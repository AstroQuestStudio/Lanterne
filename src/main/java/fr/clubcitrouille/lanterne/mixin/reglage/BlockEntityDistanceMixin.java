package fr.clubcitrouille.lanterne.mixin.reglage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;

import fr.clubcitrouille.lanterne.client.reglage.Reglages;

/**
 * Distance d'affichage des blocs animés. On ne touche qu'à la surcharge à cinq arguments : celle à
 * quatre y renvoie (sources 26.3), et son retour nul est déjà prévu (l'annotation {@code @Nullable}).
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityDistanceMixin {
    @Inject(method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;F"
            + "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z"
            + "Lnet/minecraft/client/renderer/culling/Frustum;)"
            + "Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
            at = @At("HEAD"), cancellable = true)
    private void lanterne$blocTropLoin(BlockEntity blockEntity, float partialTicks,
            net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay overlay,
            boolean globally, net.minecraft.client.renderer.culling.Frustum frustum,
            CallbackInfoReturnable<BlockEntityRenderState> cir) {
        if (Reglages.blocsDistanceCarre > 0d && Reglages.blocTropLoin(blockEntity.getBlockPos())) {
            cir.setReturnValue(null);
        }
    }
}
