package fr.clubcitrouille.lanterne.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;

import fr.clubcitrouille.lanterne.core.Digue;

/**
 * L'écouteur d'événements qui ne fait plus générer le chunk qu'il quitte.
 *
 * <h2>Le piège du quatrième argument</h2>
 *
 * <p>{@code DynamicGameEventListener.ifChunkExists} appelle déjà
 * {@code level.getChunk(x, z, ChunkStatus.FULL, false)} — dernier argument <b>faux</b>. On pourrait
 * croire l'affaire réglée : le nom même de la méthode dit « si le chunk existe ». Elle ne l'est pas,
 * et c'est le point le plus contre-intuitif de tout ce module.
 *
 * <p>{@code ServerChunkCache.getChunk} lu ligne à ligne en 26.2 : le drapeau {@code loadOrGenerate}
 * ne commande que la <em>pose du ticket</em>. En dessous, le code est le même dans les deux cas :
 *
 * <pre>
 * CompletableFuture&lt;…&gt; f = this.getChunkFutureMainThread(x, z, targetStatus, loadOrGenerate);
 * this.mainThreadProcessor.managedBlock(f::isDone);      // ← ici, quel que soit le drapeau
 * </pre>
 *
 * <p>Et {@code getChunkFutureMainThread} rend {@code scheduleChunkGenerationTask(FULL, chunkMap)} dès
 * que le porteur existe avec le bon niveau de ticket. Autrement dit : pour un chunk <b>dont le ticket
 * est posé mais dont la génération n'est pas finie</b> — exactement la situation d'un joueur qui
 * avance dans du terrain neuf — {@code getChunk(…, false)} <b>génère le chunk et attend</b>.
 *
 * <p>C'est cette fenêtre qui produit le gel, et c'est pourquoi {@code ServerCore} écrit en commentaire
 * <em>« Doing so can cause the server to freeze indefinitely »</em> sur ce mixin précisément.
 * {@code getChunkNow} est le seul appel qui ne la traverse pas.
 *
 * <h2>Ce qu'on perd</h2>
 *
 * <p>Presque rien, et c'est rare. L'appel sert à désinscrire l'écouteur de la section qu'une entité
 * vient de quitter, et à l'inscrire dans celle où elle arrive. Une section non chargée n'a pas de
 * registre d'écouteurs à tenir à jour : il sera reconstruit quand le chunk reviendra. Le cas visé —
 * une entité qui se déplace d'un coup vers une section lointaine — est celui d'une téléportation ou
 * d'un portail.
 */
@Mixin(DynamicGameEventListener.class)
public abstract class GameEventChunkMixin {
    @Redirect(
            method = "ifChunkExists",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/LevelReader;getChunk"
                            + "(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)"
                            + "Lnet/minecraft/world/level/chunk/ChunkAccess;"))
    private static @Nullable ChunkAccess lanterne$onlyIfLoaded(LevelReader level, int chunkX,
                                                               int chunkZ, ChunkStatus status,
                                                               boolean loadOrGenerate) {
        if (!Digue.guards(level)) {
            return level.getChunk(chunkX, chunkZ, status, loadOrGenerate);
        }
        ChunkAccess chunk = Digue.now(level, chunkX, chunkZ);
        if (chunk == null) {
            Digue.noteEvent();
        }
        return chunk;
    }
}
