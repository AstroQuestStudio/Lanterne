package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.buffer.Unpooled;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;

import fr.clubcitrouille.lanterne.client.SouvenirClient;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Capture, côté client, les octets d'un chunk qui vient d'arriver — voir {@code core.Souvenir}.
 *
 * <h2>Une observation, jamais une interception</h2>
 *
 * <p>L'injection est en tête, non annulable : le chunk est traité exactement comme vanilla le
 * ferait, dans le même ordre, avec le même résultat. Ce mixin ne fait que regarder passer le paquet
 * et en garder une copie encodée, avant que quoi que ce soit d'autre ne le consomme.
 *
 * <p>La copie reste en attente ({@code SouvenirClient.stage}) jusqu'à ce que {@link SouvenirRevision}
 * suivant sur la même connexion vienne l'étiqueter — voir la javadoc de {@code SouvenirClient} pour ce
 * qui se passe si ce paquet n'arrive jamais.
 */
@Mixin(ClientPacketListener.class)
public abstract class SouvenirReceiveMixin {

    @Inject(
            method = "handleLevelChunkWithLight(Lnet/minecraft/network/protocol/game/"
                    + "ClientboundLevelChunkWithLightPacket;)V",
            at = @At("HEAD"))
    private void lanterne$capture(ClientboundLevelChunkWithLightPacket packet, CallbackInfo callback) {
        if (!Settings.souvenir()) {
            return;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        try {
            RegistryFriendlyByteBuf buf =
                    new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, packet);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            SouvenirClient.stage(new ChunkPos(packet.x(), packet.z()), bytes);
        } catch (RuntimeException unencodable) {
            // Capture ratée : le chunk s'affiche normalement, seulement non gardé pour la prochaine
            // session. Ne jamais laisser un problème de côté-mémoire gêner l'affichage lui-même.
        }
    }
}
