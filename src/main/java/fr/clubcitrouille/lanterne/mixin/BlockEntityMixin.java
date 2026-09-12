package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Sleep;

/**
 * Le réveil : laisser le jeu dire lui-même quand l'hypothèse tombe.
 *
 * <h2>Pourquoi ne pas surveiller l'inventaire</h2>
 *
 * <p>Le sommeil à échéance repose sur une hypothèse : que rien ne change pendant qu'on dort. La
 * tentation serait de la vérifier soi-même — comparer le contenu, guetter les entonnoirs, compter
 * les objets. Ce serait payer à chaque tick exactement ce qu'on cherche à économiser, et se tromper
 * un jour ou l'autre sur un mod tiers qu'on n'aurait pas prévu.
 *
 * <p>Le jeu tient déjà ce registre. {@code BlockEntity.setChanged()} est appelé par tout ce qui
 * modifie un bloc-entité — joueur, entonnoir, dispenseur, commande, mod tiers — parce que c'est par
 * là que passe la sauvegarde. <b>Ce qui n'appelle pas cette méthode ne persiste pas</b>, et ne peut
 * donc pas être une modification durable.
 *
 * <p>On s'y raccroche : le signal existe, il est exhaustif par construction, et il ne coûte rien
 * puisqu'il n'est émis qu'au moment des changements réels.
 *
 * <h2>Le coût de ce mixin</h2>
 *
 * <p>{@code setChanged} n'est pas un chemin chaud : elle n'est appelée que lorsque quelque chose
 * change vraiment, soit quelques dizaines de fois par seconde sur un serveur actif — contre des
 * centaines de milliers pour un tick d'entité. Une recherche dans une table d'entiers y est sans
 * conséquence mesurable.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    @Shadow
    @org.spongepowered.asm.mixin.Final
    protected BlockPos worldPosition;

    @Inject(method = "setChanged", at = @At("HEAD"))
    private void lanterne$wakeOnChange(CallbackInfo callback) {
        if (Settings.sleep()) {
            Sleep.wake(this.worldPosition);
        }
    }
}
