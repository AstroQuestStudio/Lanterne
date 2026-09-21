package fr.clubcitrouille.lanterne.mixin.reglage;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;

import fr.clubcitrouille.lanterne.client.reglage.Reglages;

/**
 * Densité, catégories et distance des particules : une particule écartée n'est jamais créée, donc
 * ni mise à jour ni dessinée. Vanilla accepte déjà un retour nul de {@code createParticle} (son
 * annotation {@code @Nullable}, lue dans les sources 26.3), c'est le chemin d'un fournisseur absent.
 *
 * <p>Inactif — un seul test d'un booléen en cache — tant qu'aucun réglage de particules ne s'écarte
 * du défaut.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleDensityMixin {
    @Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)"
            + "Lnet/minecraft/client/particle/Particle;", at = @At("HEAD"), cancellable = true)
    private void lanterne$reglageParticule(ParticleOptions options, double x, double y, double z,
            double xa, double ya, double za, CallbackInfoReturnable<Particle> cir) {
        if (Reglages.particulesActives && !Reglages.creerParticule(options, x, y, z)) {
            cir.setReturnValue(null);
        }
    }
}
