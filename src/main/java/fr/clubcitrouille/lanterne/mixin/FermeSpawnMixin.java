package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.player.Player;

import fr.clubcitrouille.lanterne.core.Ferme;

/**
 * Les refus de {@code NaturalSpawner} qu'une Ancre de ferme lève — pour ses seuls chunks, et
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
        Ferme.finTentative();
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
     * Le plafond de la ferme, tenu monstre par monstre. {@link Ferme#roomFor} n'est consulté qu'une
     * fois par chunk et par tick, et une tentative fait naître jusqu'à trois groupes : sans ce
     * décompte, une salle à 38 recevait un groupe entier et montait à 43. Chaque monstre qui passe
     * tous les tests de vanilla prend ici une des places que {@code roomFor} a comptées libres ; plus
     * de place, il ne naît pas. Hors d'une tentative décidée par la ferme, rien n'est compté.
     */
    @WrapOperation(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/NaturalSpawner;isValidPositionForMob(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Mob;D)Z"))
    private static boolean lanterne$placeDansLaFerme(ServerLevel level, Mob mob, double distance,
            Operation<Boolean> original) {
        return original.call(level, mob, distance) && Ferme.prendrePlace();
    }

    /** Fin de la tentative du chunk : le décompte ne déborde ni sur le chunk suivant, ni ailleurs. */
    @Inject(method = "spawnForChunk", at = @At("TAIL"))
    private static void lanterne$finTentative(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState state,
            java.util.List<MobCategory> categories, CallbackInfo callback) {
        Ferme.finTentative();
    }

    /**
     * « Qui est le joueur le plus proche ? » — personne, dans une dimension sans joueur, et vanilla
     * abandonne alors la tentative. Pour un monstre dans un chunk « ferme », tant qu'un joueur est
     * connecté ailleurs : ce joueur-là ({@link Ferme#ailleurs}). Il ne sert qu'à la distance, que
     * l'accroche suivante remplace par « très loin ».
     */
    @WrapOperation(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getNearestPlayer(DDDDZ)Lnet/minecraft/world/entity/player/Player;"))
    private static Player lanterne$joueurAilleurs(ServerLevel level, double x, double y, double z,
            double range, boolean creative, Operation<Player> original,
            @Local(argsOnly = true) MobCategory category, @Local(argsOnly = true) ChunkAccess chunk) {
        Player vanilla = original.call(level, x, y, z, range, creative);
        if (vanilla != null || category != MobCategory.MONSTER || !Ferme.isFarm(level, chunk.getPos())) {
            return vanilla;
        }
        Player ailleurs = Ferme.ailleurs(level);
        if (ailleurs != null) {
            Ferme.noteAilleurs();
        }
        return ailleurs;
    }

    /**
     * La distance à ce joueur d'une autre dimension : « très loin » ({@link Ferme#LOIN}), et non
     * l'écart entre deux coordonnées qui n'ont rien à voir — un joueur à (0, 0) dans l'Overworld n'est
     * pas « à 20 blocs » d'une ferme en (2, 5) dans le Nether, et vanilla la refuserait (moins de 24
     * blocs). Un joueur de la dimension garde sa vraie distance.
     */
    @WrapOperation(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/player/Player;distanceToSqr(DDD)D"))
    private static double lanterne$distanceAilleurs(Player player, double x, double y, double z,
            Operation<Double> original, @Local(argsOnly = true) ServerLevel level) {
        return player.level() == level ? original.call(player, x, y, z) : Ferme.LOIN;
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
