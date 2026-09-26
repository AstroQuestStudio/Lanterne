package fr.clubcitrouille.lanterne.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import fr.clubcitrouille.lanterne.core.Ferme;

/**
 * Le premier étage : faire entrer les chunks « ferme » dans le tour d'apparition.
 *
 * <p>{@code collectSpawningChunks} ne parcourt que les chunks du voisinage des joueurs. Le drapeau
 * {@code forceNaturalSpawning} de notre ticket ne sert, chez vanilla, qu'à repêcher un chunk déjà dans
 * ce voisinage. On ajoute donc, en fin de collecte, chaque chunk « ferme » qui tique et qui n'y est
 * pas déjà — quelques chunks, un {@code contains} sur la liste chacun : rien à l'échelle d'un tick.
 */
@Mixin(ChunkMap.class)
public abstract class FermeChunkMapMixin {
    @Shadow
    @Final
    ServerLevel level;

    @Inject(method = "collectSpawningChunks", at = @At("TAIL"))
    private void lanterne$fermes(List<LevelChunk> spawning, CallbackInfo callback) {
        ChunkMap self = (ChunkMap) (Object) this;
        Ferme.forEach(this.level, packed -> {
            ChunkHolder holder = self.getVisibleChunkIfPresent(packed);
            LevelChunk chunk = holder == null ? null : holder.getTickingChunk();
            if (chunk != null && !spawning.contains(chunk)) {
                spawning.add(chunk);
            }
        });
    }
}
