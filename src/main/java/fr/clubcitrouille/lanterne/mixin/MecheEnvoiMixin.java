package fr.clubcitrouille.lanterne.mixin;

import io.netty.channel.ChannelFutureListener;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fr.clubcitrouille.lanterne.core.MecheCompat;

/**
 * Le passage obligé de tout paquet envoyé à un client par le serveur (configuration comme jeu), pour
 * les seules connexions en compatibilité — voir {@link MecheCompat} : étiquettes traduites dans la
 * numérotation du client pendant la configuration, puis, en jeu, tout paquet retenu sauf la liste
 * blanche de l'antichambre. Pour toute autre connexion : un test dans une table vide, rien d'autre.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class MecheEnvoiMixin {
    @Shadow
    @Final
    protected Connection connection;

    @Shadow
    public abstract void send(Packet<?> packet, @Nullable ChannelFutureListener listener);

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
            at = @At("HEAD"), cancellable = true)
    private void lanterne$compatibilite(Packet<?> packet, @Nullable ChannelFutureListener listener,
            CallbackInfo ci) {
        if (!MecheCompat.enCompatibilite(this.connection)) {
            return;
        }
        if (packet instanceof ClientboundUpdateTagsPacket etiquettes) {
            Packet<?> traduit = MecheCompat.traduireEtiquettes(this.connection, etiquettes);
            if (traduit != null) {
                ci.cancel();
                this.send(traduit, listener);
            }
            return;
        }
        if ((Object) this instanceof ServerGamePacketListenerImpl
                && MecheCompat.retenir(this.connection, packet)) {
            ci.cancel();
        }
    }
}
