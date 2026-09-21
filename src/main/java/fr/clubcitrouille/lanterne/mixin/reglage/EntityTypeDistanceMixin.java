package fr.clubcitrouille.lanterne.mixin.reglage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

import fr.clubcitrouille.lanterne.client.reglage.Reglages;

/**
 * Distance d'affichage des cadres, peintures et supports d'armure. {@code shouldRender} reçoit la
 * position de la caméra (sources 26.3) : un {@code false} suffit à ne pas préparer l'entité pour
 * le rendu. Inactif tant que les deux distances sont à 0.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityTypeDistanceMixin {
    @Inject(method = "shouldRender(Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/client/renderer/culling/Frustum;DDDF)Z", at = @At("HEAD"), cancellable = true)
    private void lanterne$entiteTropLoin(Entity entity, Frustum frustum, double camX, double camY,
            double camZ, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
        if ((Reglages.cadresDistanceCarre > 0d || Reglages.armorStandsDistanceCarre > 0d)
                && Reglages.entiteTropLoin(entity, camX, camY, camZ)) {
            cir.setReturnValue(false);
        }
    }
}
