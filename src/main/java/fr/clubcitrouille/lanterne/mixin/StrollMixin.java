package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;

import fr.clubcitrouille.lanterne.core.Pasture;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * La décision d'errance, retirée à qui a prouvé qu'il n'allait nulle part.
 *
 * <p>Le raisonnement et le critère sont dans {@link Pasture}.
 *
 * <h2>Un seul point d'accroche pour toutes les bêtes de ferme</h2>
 *
 * <p>{@code WaterAvoidingRandomStrollGoal} — celui des vaches, moutons, cochons, poules — hérite de
 * {@code RandomStrollGoal} et ne redéfinit que {@code getPosition()}. Le {@code canUse()} est donc
 * partagé, et un seul mixin couvre l'ensemble sans avoir à énumérer les espèces ni les mods.
 *
 * <h2>Ce que vanilla fait déjà, et qu'il ne faut pas refaire</h2>
 *
 * <pre>
 * if (this.checkNoActionTime &amp;&amp; this.mob.getNoActionTime() &gt;= 100) {
 *     return false;
 * }
 * </pre>
 *
 * <p>Le jeu supprime lui-même l'errance des bêtes qu'aucun joueur n'approche. Ce module ne sert donc
 * <b>que</b> dans le cas inverse — le joueur debout dans sa ferme — et c'est précisément celui que le
 * niveau de détail n'a pas le droit de dégrader.
 */
@Mixin(RandomStrollGoal.class)
public abstract class StrollMixin {
    @Shadow
    @Final
    protected PathfinderMob mob;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void lanterne$stayPut(CallbackInfoReturnable<Boolean> callback) {
        if (!Settings.pasture()) {
            return;
        }
        if (Pasture.penned(this.mob)) {
            Pasture.noteRefusal();
            callback.setReturnValue(false);
        }
    }
}
