package fr.clubcitrouille.lanterne.mixin;

import java.util.concurrent.CompletableFuture;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;

import fr.clubcitrouille.lanterne.core.Forge;

/**
 * Le chronomètre par étape de génération.
 *
 * <p>Voir {@link Forge} pour ce qu'on cherche à établir. Ce mixin n'ajoute qu'une lecture d'horloge
 * avant et après — et il mesure le temps <b>jusqu'au retour de la méthode</b>, non jusqu'à la fin de
 * la tâche asynchrone qu'elle peut avoir lancée.
 *
 * <p>La nuance est importante et la voici sans détour : une étape qui délègue à un autre fil sera
 * sous-estimée. C'est acceptable ici parce qu'on a déjà établi, par ailleurs, que la génération
 * n'occupe qu'un fil et demi sur quinze — l'essentiel du travail se fait donc bien là où on le
 * mesure. Sur une machine à un cœur, le cas visé, il n'y a de toute façon nulle part où fuir.
 */
@Mixin(ChunkStep.class)
public abstract class ChunkStepMixin {
    @WrapMethod(method = "apply")
    private CompletableFuture<ChunkAccess> lanterne$timeStep(WorldGenContext context,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            Operation<CompletableFuture<ChunkAccess>> original) {
        long started = System.nanoTime();
        String status = ((ChunkStep) (Object) this).targetStatus().getName();
        CompletableFuture<ChunkAccess> result = original.call(context, cache, chunk);
        if (result.isDone()) {
            // Étape synchrone : le travail est fait, on le compte tout de suite.
            Forge.note(status, System.nanoTime() - started);
            return result;
        }
        // Étape asynchrone — « noise » en est une. S.arrêter au retour de la méthode ne mesurerait
        // que la soumission, et l.étape paraîtrait gratuite. On compte jusqu.à son aboutissement
        // réel, quitte à inclure un peu d.attente : sur une machine à un cœur, cette attente EST du
        // travail, simplement fait ailleurs.
        return result.whenComplete((value, failure) -> Forge.note(status, System.nanoTime() - started));
    }
}
