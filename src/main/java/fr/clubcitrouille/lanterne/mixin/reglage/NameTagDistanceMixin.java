package fr.clubcitrouille.lanterne.mixin.reglage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.renderer.entity.EntityRenderer;

import fr.clubcitrouille.lanterne.client.reglage.Reglages;

/**
 * Distance des plaques de nom : la distance passée à {@code extractNameTags} (64 par défaut, sources
 * 26.3) est ramenée à la valeur choisie. Le test de distance fait déjà partie de la méthode : on
 * ne change qu'un nombre.
 */
@Mixin(EntityRenderer.class)
public abstract class NameTagDistanceMixin {
    @ModifyVariable(method = "extractNameTags(Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private double lanterne$distanceDesPlaques(double distance) {
        double voulue = Reglages.plaquesDistance;
        return voulue > 0d && voulue < distance ? voulue : distance;
    }
}
