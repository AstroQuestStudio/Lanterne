package fr.clubcitrouille.lanterne.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import fr.clubcitrouille.lanterne.core.Gather;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Les deux freins de coût de la fusion au sol, retirés. Le frein de règle, conservé.
 *
 * <p>Le raisonnement est dans {@link Gather}. Ce qui compte ici tient en une phrase : on ne touche
 * <b>pas</b> à {@code isMergable()}, donc pas au plafond d'âge de six mille ticks, sans lequel un tas
 * d'objets mourant redeviendrait éternel dès qu'on lui jette un objet neuf à côté.
 */
@Mixin(ItemEntity.class)
public abstract class ItemMergeMixin {
    /**
     * La portée, élargie — surtout à la verticale.
     *
     * <p>Vanilla passe {@code inflate(0.5, 0.0, 0.5)}. Le zéro du milieu est le vrai coupable : deux
     * objets empilés ne se rejoignent jamais, quelle que soit la patience qu'on y met.
     */
    @ModifyExpressionValue(method = "mergeWithNeighbours",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/AABB;inflate(DDD)Lnet/minecraft/world/phys/AABB;"))
    private AABB lanterne$reachFurther(AABB given) {
        if (!Settings.gather()) {
            return given;
        }
        // On part de la boîte DÉJÀ élargie par vanilla, et l'on complète la différence. Repartir de
        // la boîte brute demanderait de la retrouver, et la retrouver demanderait de supposer ce que
        // vanilla y avait mis.
        return given.inflate(Gather.SPREAD - 0.5d, Gather.STACK, Gather.SPREAD - 0.5d);
    }

    /**
     * La fréquence pour un objet immobile.
     *
     * <p>{@code int rate = moved ? 2 : 40;} — la constante quarante n'apparaît qu'une fois dans tout
     * {@code ItemEntity}, ce qui rend la substitution sans ambiguïté.
     */
    @ModifyConstant(method = "tick", constant = @Constant(intValue = 40))
    private int lanterne$scanSettledMoreOften(int original) {
        return Settings.gather() ? Gather.SETTLED_RATE : original;
    }

    /**
     * Le compteur, posé là où une fusion a vraiment lieu.
     *
     * <h2>Pourquoi pas dans le calcul de fréquence</h2>
     *
     * <p>Il aurait été commode de compter au passage de la constante ci-dessus : une ligne, aucun
     * point d'injection de plus. Ce compteur aurait répondu « combien de fois un objet immobile a
     * consulté sa cadence » — et le rapport l'aurait publié sous le titre « fusions ».
     *
     * <p>C'est la forme la plus banale de faux rapport, et la plus difficile à repérer après coup :
     * un chiffre juste, sous un nom faux. Ce module compte donc les entités réellement <b>supprimées
     * par fusion</b>, ce qui est la seule grandeur dont le mod puisse se prévaloir.
     */
    @org.spongepowered.asm.mixin.injection.Inject(
            method = "merge(Lnet/minecraft/world/entity/item/ItemEntity;"
                    + "Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/entity/item/ItemEntity;"
                    + "Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("TAIL"))
    private static void lanterne$countAbsorbed(ItemEntity into, net.minecraft.world.item.ItemStack
            intoStack, ItemEntity from, net.minecraft.world.item.ItemStack fromStack,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo callback) {
        if (Settings.gather() && fromStack.isEmpty()) {
            Gather.noteMerge();
        }
    }
}
