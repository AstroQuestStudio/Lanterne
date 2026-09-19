package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.item.trading.MerchantOffer;

import fr.clubcitrouille.lanterne.core.Config;

/**
 * La MANNE : {@code MerchantOffer.increaseUses()} n'incrémente plus rien quand le module est actif.
 *
 * <h2>Le seul point d'accroche qui compte, vérifié au {@code javap}</h2>
 *
 * <p>{@code increaseUses()} est un incrément d'une ligne ({@code this.uses++}), appelé une seule
 * fois par échange réussi (côté serveur, {@code MerchantOffers} garde l'autorité). {@code
 * isOutOfStock()} compare ensuite {@code uses >= maxUses} — geler {@code uses} à sa valeur de départ
 * suffit donc à garder tout échange disponible indéfiniment, sans toucher au prix, à la demande, ni
 * à l'expérience versée, tous calculés ailleurs et jamais lus par cette méthode.
 *
 * <p>Annuler l'injection dès la tête de la méthode ({@code @At("HEAD")}, {@code cancellable}) est
 * donc exact bit à bit : aucune autre ligne de {@code increaseUses()} n'existe à préserver.
 */
@Mixin(MerchantOffer.class)
public abstract class ManneMixin {

    @Inject(method = "increaseUses", at = @At("HEAD"), cancellable = true)
    private void lanterne$stockInfini(CallbackInfo ci) {
        if (Config.MANNE.get()) {
            ci.cancel();
        }
    }
}
