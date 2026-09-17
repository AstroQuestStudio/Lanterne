package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import net.neoforged.neoforge.network.PacketDistributor;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Souvenir;
import fr.clubcitrouille.lanterne.core.network.SouvenirKept;
import fr.clubcitrouille.lanterne.core.network.SouvenirRevision;

/**
 * Le point d'accroche de {@link Souvenir} côté envoi : la même méthode que celle où
 * {@code EmballageEnvoiMixin} substitue déjà le paquet mis en cache — mais un cran plus haut.
 *
 * <h2>Deux prises sur la même méthode, deux étages différents</h2>
 *
 * <p>{@code EmballageEnvoiMixin} redirige le <em>constructeur</em> du paquet, à l'intérieur de
 * {@code sendChunk} : il décide <b>comment</b> fabriquer les octets. Ce mixin-ci agit en <b>tête</b>
 * de la méthode entière : il décide <b>s'il faut envoyer quoi que ce soit</b>. Les deux coexistent
 * sans se gêner — si celui-ci ne coupe pas l'envoi, {@code EmballageEnvoiMixin} continue de faire
 * exactement ce qu'il faisait déjà.
 *
 * <h2>Pourquoi une injection en tête, annulable, plutôt qu'un redirect</h2>
 *
 * <p>Annuler la méthode entière avant qu'elle ne touche à quoi que ce soit est le geste le plus sûr :
 * rien de ce que {@code sendChunk} ferait normalement — construire le paquet, l'empaqueter avec la
 * lumière auxiliaire, le journal de débogage — ne s'exécute quand on a décidé de ne rien envoyer.
 */
@Mixin(PlayerChunkSender.class)
public abstract class SouvenirSendMixin {

    @Inject(
            method = "sendChunk(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"
                    + "Lnet/minecraft/server/level/ServerLevel;"
                    + "Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void lanterne$maybeSkip(ServerGamePacketListenerImpl connection, ServerLevel level,
            LevelChunk chunk, CallbackInfo callback) {
        if (!Settings.souvenir()) {
            return;
        }
        ServerPlayer player = connection.getPlayer();
        ChunkPos pos = chunk.getPos();
        long revision = Souvenir.revisionFor(level, pos);
        long claimed = Souvenir.claimed(player.getUUID(), level.dimension().identifier(), pos.pack());
        if (claimed != 0L && claimed == revision) {
            Souvenir.noteSkipped();
            PacketDistributor.sendToPlayer(player, new SouvenirKept(pos));
            callback.cancel();
        }
    }

    /**
     * Étiquette la révision d'un chunk envoyé en entier — que ce soit parce que la manifeste ne
     * correspondait pas, ou qu'aucune manifeste n'a jamais été reçue pour lui.
     *
     * <p>Ne s'exécute que si {@link #lanterne$maybeSkip} n'a pas annulé la méthode : {@code @At("RETURN")}
     * ne se déclenche jamais quand le rappel précédent a coupé court.
     */
    @Inject(
            method = "sendChunk(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"
                    + "Lnet/minecraft/server/level/ServerLevel;"
                    + "Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            at = @At("RETURN"))
    private static void lanterne$tagRevision(ServerGamePacketListenerImpl connection, ServerLevel level,
            LevelChunk chunk, CallbackInfo callback) {
        if (!Settings.souvenir()) {
            return;
        }
        ServerPlayer player = connection.getPlayer();
        ChunkPos pos = chunk.getPos();
        long revision = Souvenir.revisionFor(level, pos);
        Souvenir.noteServed();
        PacketDistributor.sendToPlayer(player, new SouvenirRevision(pos, revision));
    }
}
