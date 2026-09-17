package fr.clubcitrouille.lanterne.mixin;

import java.io.DataInputStream;
import java.io.IOException;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

import fr.clubcitrouille.lanterne.core.ChunkStreamOpener;

/**
 * Sépare l'ouverture du flux de son décodage — voir {@code core.ChunkDecode} pour le raisonnement
 * complet et ce que cela permet.
 *
 * <p>{@code RegionFileStorage.read(ChunkPos)} (vérifié) combine les deux gestes en une seule méthode :
 * {@code getRegionFile} puis {@code region.getChunkDataInputStream(pos)} puis {@code NbtIo.read(...)}.
 * Cette classe n'ajoute rien à ce que vanilla fait déjà — elle ne fait qu'exposer la première moitié
 * sous un nom à part, pour que {@code IOWorkerMixin} puisse l'appeler seule, sur le fil qui garde déjà
 * {@code getRegionFile} et son cache non synchronisé.
 *
 * <p>Implémente {@link ChunkStreamOpener} pour que {@code IOWorkerMixin} — un fichier compilé à part,
 * qui ne voit donc pas cette méthode ajoutée — puisse l'appeler via un cast plutôt que par son nom.
 */
@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin implements ChunkStreamOpener {
    @Shadow
    private @Nullable RegionFile getRegionFile(ChunkPos pos, boolean create) throws IOException {
        throw new AssertionError();
    }

    /**
     * Le flux d'un chunk, sans le lire. Doit être appelé depuis le fil qui sérialise déjà les accès
     * au cache de fichiers de région de cette instance — voir le Javadoc de {@code ChunkDecode}.
     */
    @Override
    public @Nullable DataInputStream lanterne$openChunkStream(ChunkPos pos) throws IOException {
        RegionFile region = this.getRegionFile(pos, false);
        return region == null ? null : region.getChunkDataInputStream(pos);
    }
}
