package fr.clubcitrouille.lanterne.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import fr.clubcitrouille.lanterne.core.MecheCompat;

/**
 * Retient l'hôte que le joueur a tapé pour se connecter : c'est l'adresse qui marche DE CHEZ LUI,
 * celle qu'on reprend pour le lien de téléchargement HTTP de la Mèche (voir {@code core.MecheHttp}).
 */
@Mixin(ServerHandshakePacketListenerImpl.class)
public abstract class MecheHoteMixin {
    @Shadow
    @Final
    private Connection connection;

    @Inject(method = "handleIntention", at = @At("HEAD"))
    private void lanterne$noterHote(ClientIntentionPacket packet, CallbackInfo ci) {
        MecheCompat.noterHote(this.connection, packet.hostName());
    }
}
