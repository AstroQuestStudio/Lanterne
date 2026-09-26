package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;

import fr.clubcitrouille.lanterne.core.Ferme;

/**
 * Les trois refus de {@code NaturalSpawner} qu'une Ancre de ferme lève — pour ses seuls chunks, et
 * pour les seuls monstres. Voir {@link Ferme} pour la liste complète des cinq étages.
 *
 * <p>Chaque point d'accroche appelle d'abord vanilla et ne s'écarte de sa réponse que si elle est
 * « non » ET que le chunk porte une ancre de ferme : partout ailleurs, la réponse est celle du jeu, au
 * prix d'une comparaison ({@code total == 0} dans {@link Ferme#isFarm}).
 */
@Mixin(NaturalSpawner.class)
public abstract class FermeSpawnMixin {
    /**
     * Le plafond local. Vanilla ({@code LocalMobCapCalculator.canSpawn}) répond non quand aucun
     * joueur n'est près du chunk ; on le remplace par le plafond de la ferme. On s'accroche à l'appel
     * dans {@code spawnForChunk} plutôt que dans {@code SpawnState.canSpawnForCategoryLocal}
     * elle-même, parce que seul l'appelant connaît la dimension.
     *
     * <p>Si un joueur est proche, sa réponse fait foi, même négative : son plafond est plein, la ferme
     * n'a pas à le dépasser en son nom.
     */
    @WrapOperation(method = "spawnForChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/NaturalSpawner$SpawnState;canSpawnForCategoryLocal(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/world/level/ChunkPos;)Z"))
    private static boolean lanterne$plafondDeFerme(NaturalSpawner.SpawnState state, MobCategory category,
            ChunkPos chunk, Operation<Boolean> original, @Local(argsOnly = true) ServerLevel level) {
        boolean vanilla = original.call(state, category, chunk);
        if (vanilla || category != MobCategory.MONSTER || !Ferme.isFarm(level, chunk)) {
            return vanilla;
        }
        if (level.getChunkSource().chunkMap.anyPlayerCloseEnoughForSpawning(chunk)) {
            return false;
        }
        return Ferme.roomFor(level, chunk);
    }

    /**
     * « Ce type sait-il naître loin d'un joueur ? » — non pour presque tous les monstres, au-delà de
     * 128 blocs. Dans une ferme ancrée, oui.
     */
    @WrapOperation(method = "isValidSpawnPostitionForType", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/EntityType;canSpawnFarFromPlayer()Z"))
    private static boolean lanterne$naitreLoin(EntityType<?> type, Operation<Boolean> original,
            @Local(argsOnly = true) ServerLevel level, @Local(argsOnly = true) BlockPos.MutableBlockPos pos) {
        return original.call(type) || (type.getCategory() == MobCategory.MONSTER
                && Ferme.isFarm(level, ChunkPos.pack(pos)));
    }

    /**
     * « Cette créature disparaîtrait-elle aussitôt née ? » — oui à plus de 128 blocs, et vanilla ne
     * la fait donc pas naître. Dans une ferme ancrée, elle ne disparaît pas (voir
     * {@code FermeDespawnMixin}) : la question reçoit la même réponse ici.
     */
    @WrapOperation(method = "isValidPositionForMob", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;removeWhenFarAway(D)Z"))
    private static boolean lanterne$resterNe(Mob mob, double distance, Operation<Boolean> original,
            @Local(argsOnly = true) ServerLevel level) {
        return original.call(mob, distance) && !(mob.getType().getCategory() == MobCategory.MONSTER
                && Ferme.isFarm(level, ChunkPos.pack(mob.blockPosition())));
    }
}
