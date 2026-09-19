package fr.clubcitrouille.lanterne.mixin;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.phys.AABB;

import fr.clubcitrouille.lanterne.core.Aureole;

/**
 * La balise, repensée — voir {@link Aureole} pour le calcul, ici seulement le branchement.
 *
 * <h2>Trois points d'accroche, chacun pour une raison précise</h2>
 *
 * <p><b>{@code applyEffects}</b> — remplacée en tête, avec annulation, plutôt que retouchée par
 * morceaux : la portée vanilla ({@code niveau*10+10}) et la forme de sa boîte ne sont accessibles
 * nulle part ailleurs dans la méthode qu'en recalculant la même chose, matériau en plus. Voir la
 * Javadoc de {@link Aureole#effectArea} pour ce qui change réellement dans la boîte.
 *
 * <p><b>{@code getRequiredLevelsFor}</b> — élargie en tête : si {@link Aureole#extraEffectTier}
 * reconnaît l'effet, son palier remplace {@code Integer.MAX_VALUE} et
 * {@code BeaconBlockEntity.validateEffects} (jamais touchée ici) l'accepte ou le refuse avec
 * exactement la même logique qu'un effet vanilla du même palier — y compris la règle qui réserve le
 * palier 4 au choix secondaire.
 *
 * <p><b>Le champ {@code VALID_EFFECTS}</b>, redirigé à la lecture dans {@code filterEffect} ET
 * {@code loadEffect} : c'est le filet qui empêche un effet ajouté par ce module d'être effacé à la
 * relecture d'une balise sauvegardée. Rediriger le CHAMP plutôt que l'appel {@code Set.contains}
 * couvre les deux méthodes d'un seul coup — {@code loadEffect} n'appelle pas {@code contains}
 * directement dans son propre bytecode (une référence de méthode liée, invisible à un
 * {@code @Redirect} ordinaire), alors que la lecture du champ, elle, est littérale dans les deux.
 */
@Mixin(BeaconBlockEntity.class)
public abstract class AureoleMixin {

    @Inject(method = "applyEffects", at = @At("HEAD"), cancellable = true)
    private static void lanterne$applyEffects(Level level, BlockPos pos, int levels,
            Holder<MobEffect> primary, Holder<MobEffect> secondary, CallbackInfo ci) {
        if (!Aureole.active() || level.isClientSide() || primary == null) {
            return;
        }
        double range = Aureole.horizontalRange(level, pos, levels);
        AABB area = Aureole.effectArea(level, pos, range);
        boolean sameEffect = levels >= 4 && Objects.equals(primary, secondary);
        int duration = (9 + levels * 2) * 20;

        List<Player> players = level.getEntitiesOfClass(Player.class, area);
        for (Player player : players) {
            player.addEffect(new MobEffectInstance(primary, duration, sameEffect ? 1 : 0, true, true));
        }
        if (levels >= 4 && secondary != null && !Objects.equals(primary, secondary)) {
            for (Player player : players) {
                player.addEffect(new MobEffectInstance(secondary, duration, 0, true, true));
            }
        }
        ci.cancel();
    }

    @Inject(method = "getRequiredLevelsFor", at = @At("HEAD"), cancellable = true)
    private static void lanterne$widenTiers(Holder<MobEffect> effect,
            CallbackInfoReturnable<Integer> callback) {
        int tier = Aureole.extraEffectTier(effect);
        if (tier > 0) {
            callback.setReturnValue(tier);
        }
    }

    @Redirect(method = {"filterEffect", "loadEffect"}, at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/level/block/entity/BeaconBlockEntity;VALID_EFFECTS:Ljava/util/Set;"))
    private static Set<Holder<MobEffect>> lanterne$widenValidEffects(Set<Holder<MobEffect>> vanilla) {
        return Aureole.widenedValidEffects(vanilla);
    }
}
