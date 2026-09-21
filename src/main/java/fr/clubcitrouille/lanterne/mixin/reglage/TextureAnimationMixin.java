package fr.clubcitrouille.lanterne.mixin.reglage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.texture.TextureAtlas;

import fr.clubcitrouille.lanterne.client.reglage.Reglages;

/**
 * Fluidité des textures animées : {@code cycleAnimationFrames} fait avancer l'état de chaque
 * animation PUIS envoie les images changées au GPU (une passe de rendu par niveau de mipmap, sources
 * 26.3). On saute seulement l'envoi : l'animation garde son rythme, et la prochaine image envoyée
 * montre l'état courant.
 */
@Mixin(TextureAtlas.class)
public abstract class TextureAnimationMixin {
    @Inject(method = "uploadAnimationFrames()V", at = @At("HEAD"), cancellable = true)
    private void lanterne$sauterEnvoi(CallbackInfo ci) {
        if (Reglages.texturesMode != 0 && Reglages.sauterAnimationTextures()) {
            ci.cancel();
        }
    }
}
