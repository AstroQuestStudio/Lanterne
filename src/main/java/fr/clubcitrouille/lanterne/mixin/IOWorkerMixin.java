package fr.clubcitrouille.lanterne.mixin;

import java.io.DataInputStream;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.ChunkDecode;
import fr.clubcitrouille.lanterne.core.ChunkStreamOpener;
import fr.clubcitrouille.lanterne.core.PendingCopy;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Sort le décodage NBT du fil unique de lecture — voir {@code core.ChunkDecode} pour la preuve que
 * seule l'ouverture du flux avait besoin d'y rester.
 *
 * <h2>Ce que remplace exactement ce mixin</h2>
 *
 * <p>{@code loadAsync} (vérifié) :
 *
 * <pre>
 * public CompletableFuture&lt;Optional&lt;CompoundTag&gt;&gt; loadAsync(ChunkPos pos) {
 *     return this.submitThrowingTask(() -&gt; {
 *         PendingStore pendingStore = this.pendingWrites.get(pos);
 *         if (pendingStore != null) return Optional.ofNullable(pendingStore.copyData());
 *         CompoundTag data = this.storage.read(pos);   // ouverture ET décodage, sur le même fil
 *         return Optional.ofNullable(data);
 *     });
 * }
 * </pre>
 *
 * <p>{@code submitThrowingTask} lie la fin de la tâche à la complétion du résultat public : tant que
 * {@code task.get()} ne rend pas la main, {@code AbstractConsecutiveExecutor} ne dépile pas la tâche
 * suivante (vérifié, {@code pollTask} appelle {@code Util.runNamed(runnable, name)} de façon
 * strictement bloquante). Décoder à l'intérieur de cette tâche revient donc à décoder sur le fil
 * unique, quel que soit le bassin qui l'héberge.
 *
 * <p>Ce mixin ne change qu'une chose à cette forme : au lieu de rendre directement la valeur décodée,
 * la tâche programmée sur {@code consecutiveExecutor} rend un {@code CompletableFuture} qu'elle vient
 * de lancer sans l'attendre. La tâche revient donc dès que le flux est ouvert — un appel système déjà
 * exécuté — et l'exécuteur consécutif peut dépiler la lecture suivante pendant que le décodage
 * précédent tourne encore ailleurs. Le résultat public est aplati d'un cran ({@code thenCompose}) pour
 * que l'appelant continue de recevoir exactement le même type qu'avant.
 *
 * <h2>Pourquoi {@code @WrapMethod} plutôt qu'un {@code @Overwrite}</h2>
 *
 * <p>{@code @WrapMethod} laisse vanilla décider seul quand {@link Settings#decode()} est éteint —
 * {@code original.call(pos)} rejoue son comportement exact, sans qu'une divergence future entre cette
 * classe et la vraie {@code IOWorker} puisse jamais s'introduire silencieusement sur ce chemin. Seul le
 * chemin activé est de la responsabilité de ce fichier.
 */
@Mixin(IOWorker.class)
public abstract class IOWorkerMixin {
    @Shadow
    @Final
    private PriorityConsecutiveExecutor consecutiveExecutor;

    @Shadow
    @Final
    private RegionFileStorage storage;

    /**
     * Type brut plutôt que {@code SequencedMap<ChunkPos, IOWorker.PendingStore>} : {@code PendingStore}
     * est une classe imbriquée {@code private static}, invisible par son nom depuis ce fichier. Le
     * descripteur bytecode d'un type paramétré ne porte pas ses paramètres de type — {@code SequencedMap}
     * seul suffit à faire correspondre ce champ à celui de vanilla.
     */
    @Shadow
    @Final
    private SequencedMap<ChunkPos, ?> pendingWrites;

    @Shadow
    @Final
    private AtomicBoolean shutdownRequested;

    @Shadow
    private void tellStorePending() {
        throw new AssertionError();
    }

    @WrapMethod(method = "loadAsync")
    private CompletableFuture<Optional<CompoundTag>> lanterne$parallelDecode(
            ChunkPos pos, Operation<CompletableFuture<Optional<CompoundTag>>> original) {
        if (!Settings.decode()) {
            return original.call(pos);
        }

        // Même priorité que vanilla (FOREGROUND, vérifié premier de l'énumération — ordinal 0) : ce
        // n'est pas une valeur devinée, c'est celle que submitThrowingTask utilise déjà.
        CompletableFuture<CompletableFuture<Optional<CompoundTag>>> scheduled =
                this.consecutiveExecutor.scheduleWithResult(0, future -> {
                    if (!this.shutdownRequested.get()) {
                        future.complete(this.lanterne$openOrDecode(pos));
                    }
                    // Même geste que vanilla : appelé même si le résultat n'a pas été complété, pour
                    // que l'écriture suivante en file soit tentée quoi qu'il arrive.
                    this.tellStorePending();
                });
        return scheduled.thenCompose(Function.identity());
    }

    /**
     * Le geste synchronisé, sur le fil de l'IOWorker : soit une copie déjà en mémoire, soit
     * l'ouverture du flux disque. Ne décode jamais elle-même — {@link ChunkDecode#decode} le fait, sur
     * son propre bassin, une fois cette méthode revenue.
     */
    private CompletableFuture<Optional<CompoundTag>> lanterne$openOrDecode(ChunkPos pos) {
        Object pendingStore = this.pendingWrites.get(pos);
        if (pendingStore != null) {
            CompoundTag copy = ((PendingCopy) pendingStore).lanterne$copy();
            return CompletableFuture.completedFuture(Optional.ofNullable(copy));
        }

        try {
            // RegionFileStorage est final : le compilateur ne peut pas savoir que
            // RegionFileStorageMixin lui ajoute cette interface au chargement. Le détour par Object
            // est le contournement standard pour un cast vérifié seulement à l'exécution.
            DataInputStream stream =
                    ((ChunkStreamOpener) (Object) this.storage).lanterne$openChunkStream(pos);
            return stream == null
                    ? CompletableFuture.completedFuture(Optional.empty())
                    : ChunkDecode.decode(stream);
        } catch (Exception e) {
            Lanterne.LOG.warn("Failed to read chunk {}", pos, e);
            return CompletableFuture.failedFuture(e);
        }
    }
}
