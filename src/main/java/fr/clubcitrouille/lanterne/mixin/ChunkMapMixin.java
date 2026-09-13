package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkMap;

import fr.clubcitrouille.lanterne.core.Relay;

/**
 * Un compteur, et rien d'autre — pour savoir ce que coûte vraiment la coordination.
 *
 * <h2>Ce qu'on cherche à établir</h2>
 *
 * <p>La génération plafonne à dix-sept chunks par seconde avec un fil et demi occupé sur quinze.
 * Trois hypothèses ont été éliminées par la mesure : le nombre de demandes en vol, le sommeil du fil
 * serveur, et la largeur du répartiteur. La quatrième est dans {@code ChunkMap.runGenerationTask} :
 *
 * <pre>
 * this.worldgenTaskDispatcher.submit(() -&gt; {
 *     CompletableFuture&lt;?&gt; future = task.runUntilWait();
 *     if (future != null) {
 *         future.thenRun(() -&gt; this.runGenerationTask(task));   // re-soumission complète
 *     }
 * }, …);
 * </pre>
 *
 * <p>Une tâche de génération programme une <b>couche</b> de chunks — le cône de dépendances, qui peut
 * atteindre dix-sept chunks de côté — puis attend. Dès qu'elle attend, elle repasse par la file de
 * priorité, par l'exécuteur consécutif, et par un réveil de fil. Puis elle recommence, couche après
 * couche, douze fois.
 *
 * <p>Si ce cycle se produit quelques centaines de fois pour deux cent cinquante-six chunks, il n'y a
 * rien à gagner là. S'il s'en produit des centaines de milliers, alors {@code Unsafe.unpark} en tête
 * du profil n'est pas un symptôme mais <b>la maladie</b>, et la corriger vaut le détour.
 *
 * <p>Ce mixin ne change rigoureusement rien au comportement : il incrémente deux entiers. C'est la
 * règle du projet — on mesure d'abord, et neuf modules ont déjà été retirés pour avoir été écrits
 * dans l'autre ordre.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Inject(method = "runGenerationTask", at = @At("HEAD"))
    private void lanterne$countSubmission(ChunkGenerationTask task, CallbackInfo unused) {
        Relay.noteSubmission();
    }

    @Inject(method = "runGenerationTasks", at = @At("HEAD"))
    private void lanterne$countTickDrain(CallbackInfo unused) {
        Relay.noteDrain();
    }
}
