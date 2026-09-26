package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;

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
