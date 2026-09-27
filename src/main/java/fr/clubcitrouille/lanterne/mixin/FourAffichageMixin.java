package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Sleep;

/**
 * La flèche d'un four endormi avance quand même.
 *
 * <p>{@code FurnaceMixin} endort un four entre deux échéances connues : ses compteurs ne bougent
 * plus jusqu'au réveil, où ils rattrapent d'un coup le temps écoulé. Le serveur n'a rien à calculer,
 * mais un joueur qui a le four ouvert voyait la flèche et la flamme figées pendant jusqu'à cinq
 * secondes, puis sauter.
 *
 * <p>Le menu lit les compteurs par ce {@code ContainerData} (la classe anonyme du four) pour les
 * envoyer au client. On y rend la valeur que le compteur <b>aurait</b> si le four n'avait pas dormi :
 * le compteur réel plus les ticks écoulés depuis le coucher, bornés comme au réveil. Rien n'est écrit
 * dans le four ; le calcul n'a lieu que pour un menu ouvert, à l'envoi. Le joueur voit la flèche
 * avancer tick par tick, le serveur ne travaille toujours qu'aux échéances.
 */
@Mixin(targets = "net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity$1")
public abstract class FourAffichageMixin {
    @Shadow
    @Final
    AbstractFurnaceBlockEntity this$0;

    @Inject(method = "get", at = @At("RETURN"), cancellable = true)
    private void lanterne$flecheQuiAvance(int id, CallbackInfoReturnable<Integer> callback) {
        if ((id != 0 && id != 2) || !Settings.sleep()) {
            return;
        }
        Level level = this$0.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }
        int ecoule = Sleep.ecoule(this$0.getBlockPos(), level.getGameTime());
        if (ecoule <= 0) {
            return;
        }
        FurnaceProgressAccessor four = (FurnaceProgressAccessor) this$0;
        int valeur = callback.getReturnValueI();
        if (id == 2) {
            callback.setReturnValue(Math.min(valeur + ecoule, Math.max(valeur, four.lanterne$cookingTotalTime() - 1)));
        } else if (four.lanterne$litTotalTime() <= Short.MAX_VALUE) {
            callback.setReturnValue(Math.max(0, valeur - ecoule));
        }
    }
}
