package fr.clubcitrouille.lanterne.core;

import java.io.DataInputStream;
import java.io.IOException;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.level.ChunkPos;

/**
 * La porte de service vers {@code RegionFileStorage.lanterne$openChunkStream}.
 *
 * <p>{@code IOWorkerMixin} tient un champ {@code RegionFileStorage storage}, dont le type réel a
 * gagné cette méthode par {@link fr.clubcitrouille.lanterne.mixin.RegionFileStorageMixin} — mais ce
 * gain n'existe qu'à l'exécution, une fois Mixin passé. Le compilateur, lui, ne voit que la classe
 * vanilla telle qu'elle est sur le classpath de compilation, sans cette méthode. Un cast vers cette
 * interface — que {@code RegionFileStorageMixin} implémente aussi — referme l'écart.
 */
public interface ChunkStreamOpener {
    @Nullable DataInputStream lanterne$openChunkStream(ChunkPos pos) throws IOException;
}
