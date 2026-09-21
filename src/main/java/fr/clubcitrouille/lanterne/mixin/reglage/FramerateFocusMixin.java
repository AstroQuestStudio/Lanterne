package fr.clubcitrouille.lanterne.mixin.reglage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.platform.FramerateLimitTracker;

import fr.clubcitrouille.lanterne.client.reglage.Reglages;

/**
 * FPS hors focus. {@code FramerateLimitTracker.getFramerateLimit} (sources 26.3) ne bride que la
 * fenêtre réduite (10 images par seconde) ou inactive : une fenêtre visible mais en arrière-plan
 * tourne à plein régime. On abaisse la limite retournée quand la fenêtre n'a pas le focus.
 *
 * <p>Idée voisine de « Dynamic FPS » (MIT) ; le code est écrit pour Lanterne, sans en reprendre.
 */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateFocusMixin {
    @Inject(method = "getFramerateLimit()I", at = @At("RETURN"), cancellable = true)
    private void lanterne$horsFocus(CallbackInfoReturnable<Integer> cir) {
        if (Reglages.fpsHorsFocus > 0) {
            cir.setReturnValue(Reglages.limiteHorsFocus(cir.getReturnValueI()));
        }
    }
}
