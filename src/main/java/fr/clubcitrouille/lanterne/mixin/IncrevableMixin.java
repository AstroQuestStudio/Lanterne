package fr.clubcitrouille.lanterne.mixin;

import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import fr.clubcitrouille.lanterne.core.Increvable;

/**
 * Le point d'ancrage du pont AutoMiner — voir {@link Increvable} pour tout le raisonnement.
 *
 * <h2>Le surcharge ciblé, et pourquoi lui précisément</h2>
 *
 * <p>{@code ItemStack} porte trois surcharges de {@code hurtAndBreak} (vérifié au {@code javap} sur
 * {@code minecraft_26.3_client.jar}) :
 *
 * <pre>
 * hurtAndBreak(int, ServerLevel, ServerPlayer, Consumer&lt;ItemStack&gt;)
 * hurtAndBreak(int, LivingEntity, InteractionHand)
 * hurtAndBreak(int, LivingEntity, EquipmentSlot)
 * </pre>
 *
 * <p>Seul le premier reçoit un {@code ServerPlayer} directement — c'est le chemin de bris de bloc
 * (le calcul de dégât d'outil, y compris le jet aléatoire de l'enchantement Solidité, a besoin du
 * générateur aléatoire du niveau ET du joueur pour les avancements liés à l'outil cassé). Les deux
 * autres portent un {@code LivingEntity} générique — combat, dégât d'armure encaissé — et ne sont
 * <b>jamais</b> ciblés ici : un mob qui frappe un joueur ne passe jamais par ce surcharge-ci, et
 * l'armure d'un joueur en combat continue de s'user normalement.
 */
@Mixin(ItemStack.class)
public abstract class IncrevableMixin {

    @Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), cancellable = true)
    private void lanterne$epargneMinage(int amount, ServerLevel level, ServerPlayer player,
            Consumer<ItemStack> onBroken, CallbackInfo ci) {
        if (Increvable.epargne(player)) {
            ci.cancel();
        }
    }
}
