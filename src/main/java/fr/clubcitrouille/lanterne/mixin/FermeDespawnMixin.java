package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import fr.clubcitrouille.lanterne.core.Ferme;

/**
 * La disparition des monstres d'une ferme ancrée : jamais d'un coup, parfois au hasard.
 *
 * <p>{@code Mob.checkDespawn} appelle {@code removeWhenFarAway} deux fois :
 *
 * <pre>
 * if (!persistant &amp;&amp; d² &gt; 128² &amp;&amp; removeWhenFarAway(d²)) discard();    // ordinal 0 : instantané
 * if (!persistant &amp;&amp; noActionTime &gt; 600 &amp;&amp; random(800) == 0
 *         &amp;&amp; d² &gt; 32² &amp;&amp; removeWhenFarAway(d²)) discard();               // ordinal 1 : aléatoire
 * </pre>
 *
 * <p>On ne touche qu'au premier : dans un chunk « ferme » (ou l'un de ses huit voisins, voir
 * {@code Ferme.HALO}), un monstre loin du joueur n'est plus effacé
 * au tick suivant sa naissance. Le second reste celui de vanilla — un monstre coincé depuis trente
 * secondes a une chance sur huit cents par tick de partir, soit une demi-vie d'une trentaine de
 * secondes : la salle ne se bouche pas. Pourquoi pas {@code MobDespawnEvent} : voir {@link Ferme}.
 *
 * <p>L'appel n'a lieu qu'au-delà de 128 blocs et pour une créature non persistante : le test du chunk
 * ne coûte rien ailleurs.
 */
@Mixin(Mob.class)
public abstract class FermeDespawnMixin {
    /**
     * Sans joueur dans la dimension, {@code checkDespawn} ne fait rien — ni disparition instantanée,
     * ni aléatoire : un monstre coincé dans une ferme du Nether, tout le monde étant dans
     * l'Overworld, y resterait pour toujours et boucherait la salle. Pour un monstre d'une ferme (ou
     * de son pourtour), tant qu'un joueur est connecté ailleurs, on présente ce joueur
     * ({@link Ferme#ailleurs}) ; l'accroche suivante le met « très loin ». La règle de la ferme
     * s'applique alors telle quelle : pas d'un coup, parfois au hasard.
     *
     * <p>Sans personne en ligne, rien ne change : pas de joueur, pas de disparition — comme vanilla.
     */
    @WrapOperation(method = "checkDespawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getNearestPlayer(Lnet/minecraft/world/entity/Entity;D)Lnet/minecraft/world/entity/player/Player;"))
    private Player lanterne$joueurAilleurs(Level world, Entity source, double range,
            Operation<Player> original) {
        Player vanilla = original.call(world, source, range);
        Mob self = (Mob) (Object) this;
        if (vanilla == null && self.getType().getCategory() == MobCategory.MONSTER
                && world instanceof ServerLevel level
                && Ferme.nearFarm(level, ChunkPos.pack(self.blockPosition()))) {
            return Ferme.ailleurs(level);
        }
        return vanilla;
    }

    /** La distance à un joueur d'une autre dimension : « très loin ». Voir l'accroche précédente. */
    @WrapOperation(method = "checkDespawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;distanceToSqr(Lnet/minecraft/world/entity/Entity;)D"))
    private double lanterne$distanceAilleurs(Entity player, Entity self, Operation<Double> original) {
        return player instanceof Player joueur && Ferme.estLoin(joueur, self.level())
                ? Ferme.LOIN : original.call(player, self);
    }

    @WrapOperation(method = "checkDespawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;removeWhenFarAway(D)Z", ordinal = 0))
    private boolean lanterne$pasDUnCoup(Mob self, double distance, Operation<Boolean> original) {
        boolean vanilla = original.call(self, distance);
        if (vanilla && self.getType().getCategory() == MobCategory.MONSTER
                && self.level() instanceof ServerLevel level
                && Ferme.nearFarm(level, ChunkPos.pack(self.blockPosition()))) {
            Ferme.noteSpared();
            return false;
        }
        return vanilla;
    }
}
