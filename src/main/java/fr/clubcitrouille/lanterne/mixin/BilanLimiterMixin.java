package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.FramerateLimiter;

import fr.clubcitrouille.lanterne.client.bilan.BilanThrottle;

/**
 * Mesure le temps que le fil de rendu passe à ATTENDRE dans la limitation de FPS de Minecraft
 * ({@code FramerateLimiter.limitDisplayFPS}), pour que le bilan sache si une mesure de FPS est
 * faussée par un plafond (AFK, fenêtre sans focus, vsync, plafond de FPS) plutôt que par le jeu.
 * Deux lectures d'horloge par image, aucune allocation ; sans effet si le bilan est coupé.
 */
@Mixin(FramerateLimiter.class)
public abstract class BilanLimiterMixin {
    @Inject(method = "limitDisplayFPS", at = @At("HEAD"))
    private static void lanterne$limiterStart(int limit, CallbackInfo ci) {
        BilanThrottle.limiterStart();
    }

    @Inject(method = "limitDisplayFPS", at = @At("RETURN"))
    private static void lanterne$limiterEnd(int limit, CallbackInfo ci) {
        BilanThrottle.limiterEnd();
    }
}
