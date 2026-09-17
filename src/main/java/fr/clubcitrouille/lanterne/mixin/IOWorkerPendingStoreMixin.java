package fr.clubcitrouille.lanterne.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.nbt.CompoundTag;

import fr.clubcitrouille.lanterne.core.PendingCopy;

/**
 * Ouvre {@code IOWorker$PendingStore} — {@code private static}, invisible par son nom depuis
 * n'importe quel autre fichier. Voir le Javadoc de {@link PendingCopy} pour pourquoi ce détour par
 * une cible en chaîne est nécessaire, et pas seulement une commodité de style.
 *
 * <p>La copie reproduit exactement {@code PendingStore.copyData()} (vérifié) : {@code null} si la
 * donnée en attente vaut déjà {@code null}, sa copie sinon — jamais l'objet lui-même, pour les mêmes
 * raisons que vanilla ne le fait pas : le consommateur ne doit pas pouvoir muter une écriture encore
 * en file.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.storage.IOWorker$PendingStore")
public abstract class IOWorkerPendingStoreMixin implements PendingCopy {
    @Shadow
    private @Nullable CompoundTag data;

    @Override
    public CompoundTag lanterne$copy() {
        return this.data == null ? null : this.data.copy();
    }
}
