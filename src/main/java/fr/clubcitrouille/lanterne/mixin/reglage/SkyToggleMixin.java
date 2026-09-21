package fr.clubcitrouille.lanterne.mixin.reglage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.SkyRenderer;

import fr.clubcitrouille.lanterne.client.reglage.Reglages;

/** Soleil, lune et étoiles : chacun a sa méthode de dessin dans {@code SkyRenderer} (lu dans les sources). */
@Mixin(SkyRenderer.class)
public abstract class SkyToggleMixin {
    @Inject(method = "renderSun(Lcom/mojang/renderpearl/api/commands/RenderPass;F"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("HEAD"), cancellable = true)
    private void lanterne$pasDeSoleil(com.mojang.renderpearl.api.commands.RenderPass pass, float rain,
            com.mojang.blaze3d.vertex.PoseStack pose, CallbackInfo ci) {
        if (Reglages.astresCoupes) {
            ci.cancel();
        }
    }

    @Inject(method = "renderMoon(Lcom/mojang/renderpearl/api/commands/RenderPass;"
            + "Lnet/minecraft/world/level/MoonPhase;FLcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("HEAD"), cancellable = true)
    private void lanterne$pasDeLune(com.mojang.renderpearl.api.commands.RenderPass pass,
            net.minecraft.world.level.MoonPhase phase, float rain,
            com.mojang.blaze3d.vertex.PoseStack pose, CallbackInfo ci) {
        if (Reglages.astresCoupes) {
            ci.cancel();
        }
    }

    @Inject(method = "renderStars(Lcom/mojang/renderpearl/api/commands/RenderPass;F"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;)V", at = @At("HEAD"), cancellable = true)
    private void lanterne$pasDEtoiles(com.mojang.renderpearl.api.commands.RenderPass pass,
            float brightness, com.mojang.blaze3d.vertex.PoseStack pose, CallbackInfo ci) {
        if (Reglages.etoilesCoupees) {
            ci.cancel();
        }
    }
}
