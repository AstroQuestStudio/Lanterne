package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;

import fr.clubcitrouille.lanterne.core.Ecluse;

/**
 * L'écluse, posée à l'entrée de la livraison.
 *
 * <p>Le point d'accroche est le début de {@code sendNextChunks}, c'est-à-dire l'endroit exact où
 * vanilla décide d'emballer un lot. Annuler ici ne laisse rien à moitié fait : l'appel n'a encore
 * rien alloué, rien retiré de la file d'attente, rien envoyé. Le prochain tick reprendra dans le
 * même état, à ceci près qu'il aura de la place.
 *
 * <p>Voir {@link Ecluse} pour ce que la mesure a établi et pour le garde-fou contre la famine.
 */
@Mixin(PlayerChunkSender.class)
public abstract class ChunkSenderMixin {
    @Inject(method = "sendNextChunks", at = @At("HEAD"), cancellable = true)
    private void lanterne$holdWhenTickIsFull(ServerPlayer player, CallbackInfo callback) {
        if (!Ecluse.mayDeliver()) {
            callback.cancel();
        }
    }
}
