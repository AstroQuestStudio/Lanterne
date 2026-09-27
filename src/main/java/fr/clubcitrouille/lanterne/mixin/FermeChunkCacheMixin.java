package fr.clubcitrouille.lanterne.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gamerules.GameRules;

import fr.clubcitrouille.lanterne.core.Ferme;

/**
 * Le plafond global des monstres ne s'applique plus aux fermes.
 *
 * <p>Vanilla décide une fois par tick et par dimension quelles catégories sont tentées
 * ({@code getFilteredSpawningCategories}) : MONSTER disparaît dès que la dimension compte
 * {@code 70 × chunks proches d'un joueur / 289} monstres. Une ferme éloignée partageait donc le
 * plafond des joueurs de sa dimension — la nuit, les grottes autour d'eux le remplissaient et la
 * ferme ne tentait rien (26.3.149 : 965 tentatives en douze heures pour quatre chunks) — et, sans
 * joueur dans la dimension, le plafond valait zéro.
 *
 * <p>On rend MONSTER à chaque chunk « ferme » sans joueur proche, chunk par chunk : les autres
 * chunks gardent la liste de vanilla. La ferme reste bornée par son propre plafond local
 * ({@code ferme_plafond}, {@link Ferme#roomFor}), et ses monstres ne comptent plus dans celui des
 * joueurs ({@code FermeSpawnMixin}). {@code spawnEnemies} et la règle {@code spawn_mobs} restent
 * respectés : vanilla les lit avant de filtrer, on les relit ici.
 */
@Mixin(ServerChunkCache.class)
public abstract class FermeChunkCacheMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Shadow
    private boolean spawnEnemies;

    @WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerChunkCache;tickSpawningChunk(Lnet/minecraft/world/level/chunk/LevelChunk;Ljava/util/List;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;)V"))
    private void lanterne$monstresDeFerme(ServerChunkCache self, LevelChunk chunk, List<MobCategory> categories,
            NaturalSpawner.SpawnState state, Operation<Void> original) {
        if (this.spawnEnemies && !categories.contains(MobCategory.MONSTER)
                && Ferme.monstresPourFerme(this.level, chunk.getPos())
                && this.level.getGameRules().get(GameRules.SPAWN_MOBS)) {
            List<MobCategory> ferme = new ArrayList<>(categories);
            ferme.add(MobCategory.MONSTER);
            categories = ferme;
        }
        original.call(self, chunk, categories, state);
    }
}
