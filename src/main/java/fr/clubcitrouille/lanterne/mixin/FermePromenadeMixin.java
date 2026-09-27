package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;

import fr.clubcitrouille.lanterne.core.Ferme;

/**
 * Les monstres d'une ferme ancrée continuent de se promener.
 *
 * <p>{@code RandomStrollGoal.canUse} refuse toute promenade dès que {@code getNoActionTime() >= 100},
 * et ce compteur monte de un par tick tant qu'aucun joueur n'est à moins de 32 blocs
 * ({@code Mob.checkDespawn}). Sans joueur, un monstre se fige donc au bout de cinq secondes — c'est
 * pourquoi les fermes vanilla exigent un joueur immobile à côté. Banc du 27/09 : 654 creepers
 * immobiles dans la salle d'apparition de la Fosse des Poudres, à un pas des trous.
 *
 * <p>Pour un monstre dans un chunk « ferme » (ou l'un de ses voisins), la question reçoit la réponse
 * « il vient de bouger » : il se promène comme près d'un joueur. Le compteur lui-même n'est pas touché
 * — la disparition aléatoire vanilla, qui en dépend, reste entière.
 */
@Mixin(RandomStrollGoal.class)
public abstract class FermePromenadeMixin {
    @Shadow
    @Final
    protected PathfinderMob mob;

    @WrapOperation(method = "canUse", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/PathfinderMob;getNoActionTime()I"))
    private int lanterne$promenade(PathfinderMob body, Operation<Integer> original) {
        int time = original.call(body);
        return time >= 100 && body.getType().getCategory() == MobCategory.MONSTER && Ferme.covers(body)
                ? 0 : time;
    }

    /**
     * Et sans attendre : vanilla ne tente une promenade qu'une fois sur {@code interval} ticks
     * (1 sur 120 : six secondes d'immobilité en moyenne), durant lesquelles le monstre occupe une
     * place du plafond de la ferme à un pas d'un trou. Dans une ferme, le tirage réussit toujours :
     * le monstre repart dès qu'il s'arrête. Banc du 27/09 : c'était le frein du débit, pas le nombre
     * de trous (des chats pour les faire fuir n'y changeaient rien).
     */
    @WrapOperation(method = "canUse", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/RandomSource;nextInt(I)I"))
    private int lanterne$sansAttendre(net.minecraft.util.RandomSource random, int bound, Operation<Integer> original) {
        if (this.mob.getType().getCategory() == MobCategory.MONSTER && Ferme.covers(this.mob)) {
            return 0;
        }
        return original.call(random, bound);
    }
}
