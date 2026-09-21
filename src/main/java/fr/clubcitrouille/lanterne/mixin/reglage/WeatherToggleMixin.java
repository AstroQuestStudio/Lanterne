package fr.clubcitrouille.lanterne.mixin.reglage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.WeatherEffectRenderer;

import fr.clubcitrouille.lanterne.client.reglage.Reglages;

/**
 * Pluie et neige non dessinées : la préparation des colonnes et les deux passes de dessin (normale
 * et transparence ordonnée) sont sautées. L'extraction de l'état reste faite, car d'autres parties
 * du rendu (brouillard, ciel) lisent l'intensité de la météo.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherToggleMixin {
    @Inject(method = "prepare(Lnet/minecraft/world/phys/Vec3;"
            + "Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void lanterne$pasDePreparation(net.minecraft.world.phys.Vec3 camera,
            net.minecraft.client.renderer.state.level.WeatherRenderState state, CallbackInfo ci) {
        if (Reglages.meteoCoupee) {
            ci.cancel();
        }
    }

    @Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;"
            + "Lcom/mojang/renderpearl/api/commands/RenderPass;)V", at = @At("HEAD"), cancellable = true)
    private void lanterne$pasDeDessin(net.minecraft.client.renderer.state.level.WeatherRenderState state,
            com.mojang.renderpearl.api.commands.RenderPass pass, CallbackInfo ci) {
        if (Reglages.meteoCoupee) {
            ci.cancel();
        }
    }

    @Inject(method = "renderOit(Lnet/minecraft/client/renderer/oit/OitStage;"
            + "Lnet/minecraft/client/renderer/state/level/WeatherRenderState;"
            + "Lcom/mojang/renderpearl/api/commands/RenderPass;)V", at = @At("HEAD"), cancellable = true)
    private void lanterne$pasDeDessinOit(net.minecraft.client.renderer.oit.OitStage stage,
            net.minecraft.client.renderer.state.level.WeatherRenderState state,
            com.mojang.renderpearl.api.commands.RenderPass pass, CallbackInfo ci) {
        if (Reglages.meteoCoupee) {
            ci.cancel();
        }
    }
}
