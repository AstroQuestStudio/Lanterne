package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Watch;

/**
 * La garde de la Lanterne, posée à l'entrée de l'apparition naturelle.
 *
 * <h2>Pourquoi à l'entrée, et pas plus loin</h2>
 *
 * <p>On pouvait refuser la créature plus tard — au moment de la placer, ou même après l'avoir créée.
 * Le résultat visible serait le même, et le coût tout autre.
 *
 * <pre>
 * public static void spawnCategoryForChunk(MobCategory c, ServerLevel level, LevelChunk chunk, …) {
 *     BlockPos start = getRandomPosWithin(level, chunk);   // tirage + carte des hauteurs
 *     if (start.getY() &gt;= level.getMinY() + 1) {
 *         spawnCategoryForPosition(…);                      // joueur le plus proche, règles, création
 *     }
 * }
 * </pre>
 *
 * <p>Refuser ici épargne tout ce qui suit. Refuser trois lignes plus bas n'épargnerait rien du tout.
 * C'est la différence entre un bloc qui <b>empêche</b> les monstres et un bloc qui les <b>tue</b> —
 * et seul le premier rend le serveur plus rapide.
 *
 * <h2>Le coût du contrôle lui-même</h2>
 *
 * <p>Cette méthode est appelée pour chaque catégorie et chaque chunk éligible, à chaque tick : le
 * test posé ici est parcouru des milliers de fois par seconde. Il commence donc par
 * {@code GUARDED.isEmpty()}, qui répond vrai sur tout serveur où personne n'a posé de Lanterne — soit
 * l'immense majorité — et sort sur une comparaison.
 *
 * <p>Un contrôle qu'on ajoute sur un chemin chaud doit se payer lui-même. Celui-ci se paie en une
 * instruction tant qu'il ne sert pas.
 */
@Mixin(NaturalSpawner.class)
public abstract class SpawnGuardMixin {
    @Inject(method = "spawnCategoryForChunk", at = @At("HEAD"), cancellable = true)
    private static void lanterne$standWatch(MobCategory category, ServerLevel level, LevelChunk chunk,
                                            NaturalSpawner.SpawnPredicate extraTest,
                                            NaturalSpawner.AfterSpawnCallback spawnCallback,
                                            CallbackInfo callback) {
        if (category != MobCategory.MONSTER || !Settings.vigil()) {
            return;
        }
        if (Watch.watched(level.dimension(), chunk.getPos())) {
            Watch.notePrevented();
            callback.cancel();
        }
    }
}
