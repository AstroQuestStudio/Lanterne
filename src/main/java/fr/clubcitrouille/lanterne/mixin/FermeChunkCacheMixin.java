package fr.clubcitrouille.lanterne.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;

import fr.clubcitrouille.lanterne.core.Ferme;

/**
 * Le plafond global d'une dimension sans joueur, pour ses seules fermes.
 *
 * <p>Vanilla calcule le plafond global des monstres d'une dimension à partir du nombre de chunks
 * proches d'un joueur ({@code 70 × chunks / 289}). Sans joueur dans la dimension, il vaut zéro, et
 * {@code getFilteredSpawningCategories} écarte la catégorie MONSTER avant même que le premier chunk
 * soit examiné : une ferme du Nether ne tente rien pendant que tout le monde est dans l'Overworld.
 *
 * <p>On la rétablit — et seulement elle — quand la dimension porte une Ancre de ferme, n'a aucun
 * joueur, et qu'un joueur est connecté ailleurs, avec le plafond d'un joueur seul : moins de 70
 * monstres non persistants dans toute la dimension. Les chunks tentés restent ceux de vanilla plus
 * les fermes ({@code FermeChunkMapMixin}) ; sans joueur, il n'y a que les fermes, et le plafond local
 * ({@code FermeSpawnMixin}) ne laisse de toute façon passer qu'elles, à {@code ferme_plafond} près.
 * Voir l'étage 6 de {@link Ferme}.
 *
 * <p>Une fois par tick et par dimension ; la première comparaison du test ({@code total == 0}) règle
 * le cas de tout serveur sans ferme.
 */
@Mixin(ServerChunkCache.class)
public abstract class FermeChunkCacheMixin {
    @WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/NaturalSpawner;getFilteredSpawningCategories(Lnet/minecraft/world/level/NaturalSpawner$SpawnState;ZZ)Ljava/util/List;"))
    private List<MobCategory> lanterne$monstresDeFerme(NaturalSpawner.SpawnState state, boolean spawnEnemies,
            boolean spawnPersistent, Operation<List<MobCategory>> original) {
        List<MobCategory> vanilla = original.call(state, spawnEnemies, spawnPersistent);
        if (!spawnEnemies || vanilla.contains(MobCategory.MONSTER) || !Ferme.plafondGlobalPourFerme(
                ((ServerChunkCache) (Object) this).level, state.getMobCategoryCounts().getInt(MobCategory.MONSTER))) {
            return vanilla;
        }
        List<MobCategory> categories = new ArrayList<>(vanilla);
        categories.add(MobCategory.MONSTER);
        return categories;
    }
}
