package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import fr.clubcitrouille.lanterne.core.Grele;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * La grêle, posée à l'unique endroit où vanilla envoie une particule d'indicateur de dégâts.
 *
 * <p>Le raisonnement complet est dans {@link Grele}. Ce fichier ne fait que le brancher.
 *
 * <h2>Le point d'accroche, vérifié au {@code javap} sur ce moteur</h2>
 *
 * <p>{@code Player.damageStatsAndHearts(Entity, float)} — méthode privée appelée par
 * {@code attack(Entity)} à chaque coup porté — est le SEUL endroit du jar patché où
 * {@code ServerLevel.sendParticles} est appelée avec {@code ParticleTypes.DAMAGE_INDICATOR}. Un
 * second appel à {@code sendParticles} existe dans {@code itemAttackInteraction} pour
 * {@code ParticleTypes.SWEEP_ATTACK}, mais avec un compte fixe à zéro — la convention vanilla pour
 * « une seule particule, sans dispersion » — donc rien à plafonner là-bas, et ce mixin ne le
 * touche pas.
 *
 * <p>Un {@code @Redirect} sans {@code ordinal} suffit : {@code damageStatsAndHearts} n'appelle
 * {@code sendParticles} qu'une seule fois dans tout son corps.
 */
@Mixin(Player.class)
public abstract class DamageIndicatorMixin {
    @Redirect(
            method = "damageStatsAndHearts",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;"
                            + "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;"
                            + "DDDIDDDD)I"))
    private int lanterne$grele(ServerLevel level, ParticleOptions particle, double x, double y,
            double z, int count, double dx, double dy, double dz, double speed) {
        int sent = Settings.grele() ? Grele.cap(count) : count;
        return level.sendParticles(particle, x, y, z, sent, dx, dy, dz, speed);
    }
}
