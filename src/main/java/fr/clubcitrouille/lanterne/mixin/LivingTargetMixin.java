package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.LivingEntity;

import fr.clubcitrouille.lanterne.core.Quarry;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le recensement des cibles, posé là où les créatures passent réellement.
 *
 * <h2>Un raccourci qui a failli casser le combat</h2>
 *
 * <p>La première version recensait les cibles depuis {@code EntityThrottle.shouldTick}, au même
 * endroit que les entités bloquantes de {@link fr.clubcitrouille.lanterne.core.Solid}. Le
 * raisonnement semblait solide : c'est le passage où chaque entité se présente une fois par tick.
 *
 * <p>Il ne l'est que pour les <b>non-créatures</b>. Le mixin qui l'appelle porte cette remarque, écrite
 * des mois plus tôt et que je n'avais pas reliée : <em>« Pourquoi les créatures ne passent plus par
 * ici »</em>. Elles sont traitées un cran plus bas, dans {@code aiStep}, pour que le ralentissement
 * n'arrête pas leurs horloges de production.
 *
 * <p>Or les cibles d'un projectile <b>sont</b> les créatures. Le recensement était donc
 * systématiquement vide, {@code noTargetIn} rendait toujours vrai, et <b>toutes les flèches
 * traversaient tout</b>. Le module annonçait ×4,04 sur son banc : il l'obtenait en supprimant le
 * combat.
 *
 * <p>L'épreuve de tir l'a établi en deux lignes — touchée sans le mod, épargnée avec — et le compteur
 * a nommé la cause : <em>0 cible possible vue au dernier tick</em>. Sans cette épreuve, le module
 * serait parti en production avec un chiffre flatteur et un jeu cassé.
 *
 * <h2>Pourquoi {@code tick} et non {@code aiStep}</h2>
 *
 * <p>{@code aiStep} est ce que le mod ralentit : y recenser reviendrait à ne plus voir une créature
 * dès qu'elle est dégradée, c'est-à-dire à reproduire le même défaut sous une autre forme.
 *
 * <p>{@code LivingEntity.tick} est appelé pour toute créature simulée, sans exception et sans
 * dégradation possible — <b>y compris les joueurs</b>, qui en héritent. Le coût ajouté est une
 * comparaison et un ajout dans une liste, sur un chemin qui en fait déjà des centaines.
 */
@Mixin(LivingEntity.class)
public abstract class LivingTargetMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void lanterne$noteAsTarget(CallbackInfo unused) {
        if (!Settings.projectiles()) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        if (Quarry.mayBeHit(self)) {
            Quarry.note(self);
        }
    }
}
