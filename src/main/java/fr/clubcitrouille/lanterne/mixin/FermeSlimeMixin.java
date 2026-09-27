package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;

import fr.clubcitrouille.lanterne.core.Ferme;

/**
 * Pas de slime naturel dans une ferme ancrée. Une salle d'apparition sous y 40 posée sur un chunk à
 * slimes en fait naître de petits, qui passent sous les plafonds anti-zombies et prennent des places
 * du plafond de la ferme sans rien rapporter de ce qu'elle cherche. Les apparitions de slime hors des
 * chunks « ferme », les cubes de magma et les slimes de marais ne sont pas touchés.
 */
@Mixin(Slime.class)
public abstract class FermeSlimeMixin {
    @Inject(method = "checkSlimeSpawnRules", at = @At("HEAD"), cancellable = true)
    private static void lanterne$pasDeSlime(EntityType<? extends Slime> type, LevelAccessor level,
            EntitySpawnReason reason, BlockPos pos, RandomSource random, CallbackInfoReturnable<Boolean> callback) {
        if (reason == EntitySpawnReason.NATURAL && level instanceof ServerLevel server
                && Ferme.isFarm(server, ChunkPos.containing(pos))) {
            callback.setReturnValue(false);
        }
    }
}
