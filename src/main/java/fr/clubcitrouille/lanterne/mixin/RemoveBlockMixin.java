package fr.clubcitrouille.lanterne.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.entity.ai.goal.RemoveBlockGoal;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import fr.clubcitrouille.lanterne.core.Digue;

/**
 * La bête qui cherche un bloc à casser ne fait plus générer le terrain pour le trouver.
 *
 * <p>{@code RemoveBlockGoal.isValidTarget} (ligne 138) appelle
 * {@code level.getChunk(x, z, ChunkStatus.FULL, false)} — même faux ami que dans
 * {@code GameEventChunkMixin} : le drapeau faux n'empêche pas l'attente quand le porteur existe déjà.
 *
 * <p>Le poste est petit et il faut le dire : {@code isValidTarget} est appelé depuis la recherche de
 * bloc de {@code MoveToBlockGoal}, dans une boîte de vingt-quatre blocs autour de la bête — donc
 * presque toujours dans des chunks chargés. Ce mixin n'est ici que parce qu'il est la même ligne que
 * le précédent, avec le même garde-fou : il ferme une porte, il n'ouvre pas un gisement.
 *
 * <p><b>Ce qu'on perd</b> : un zombie ne casse pas l'œuf de tortue posé dans un chunk qui n'est pas
 * encore là. Il le cassera au passage suivant.
 */
@Mixin(RemoveBlockGoal.class)
public abstract class RemoveBlockMixin {
    @Redirect(
            method = "isValidTarget",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/LevelReader;getChunk"
                            + "(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                            + "Lnet/minecraft/world/level/chunk/ChunkAccess;"))
    private @Nullable ChunkAccess lanterne$onlyIfLoaded(LevelReader level, int chunkX, int chunkZ,
                                                        ChunkStatus status, boolean loadOrGenerate) {
        if (!Digue.guards(level)) {
            return level.getChunk(chunkX, chunkZ, status, loadOrGenerate);
        }
        ChunkAccess chunk = Digue.now(level, chunkX, chunkZ);
        if (chunk == null) {
            Digue.noteBlock();
        }
        return chunk;
    }
}
