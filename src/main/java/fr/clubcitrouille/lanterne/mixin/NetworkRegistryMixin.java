package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import fr.clubcitrouille.lanterne.lab.Understudy;

/**
 * Laisser entrer les doublures, et elles seules.
 *
 * <h2>Le mur qu'elles rencontrent</h2>
 *
 * <p>NeoForge vérifie, avant chaque envoi, que le client a bien annoncé comprendre la charge utile
 * qu'on lui destine :
 *
 * <pre>
 * public static void checkPacket(Packet&lt;?&gt; packet, ServerCommonPacketListener listener) {
 *     ...
 *     if (hasChannel(listener, id)) return;
 *     throw new UnsupportedOperationException("Payload %s may not be sent to the client!");
 * }
 * </pre>
 *
 * <p>La règle est saine : elle empêche d'envoyer à un client vanilla des données qu'il ne saurait
 * pas lire. Mais une doublure n'a jamais négocié de protocole — elle n'a pas de client derrière —
 * et l'inscription échouait donc au premier message d'un mod. Retirer les mods fautifs ne
 * suffisait pas : {@code neoforge:recipe_content} vient du chargeur lui-même.
 *
 * <h2>Pourquoi c'est sans danger</h2>
 *
 * <p>La vérification n'est levée que <b>tant qu'une doublure est en scène</b>, c'est-à-dire pendant
 * un banc, sur un serveur d'essai. Dès qu'il n'y en a plus — et il n'y en a aucune en production,
 * jamais, puisqu'on ne les crée que depuis le laboratoire — le contrôle reprend mot pour mot.
 *
 * <p>La condition est volontairement globale plutôt que liée à chaque connexion. Distinguer les
 * doublures des vrais joueurs supposerait de remonter du <em>listener</em> vers sa connexion, à
 * travers deux couches privées — beaucoup de fragilité pour une nuance qui n'a d'effet que dans un
 * cas de figure qui n'existe pas : un banc automatique et un joueur humain sur le même serveur.
 */
@Mixin(NetworkRegistry.class)
public abstract class NetworkRegistryMixin {
    @Inject(method = "checkPacket", at = @At("HEAD"), cancellable = true, remap = false)
    private static void lanterne$allowUnderstudies(Packet<?> packet,
                                                   ServerCommonPacketListener listener,
                                                   CallbackInfo callback) {
        if (Understudy.count() > 0 || Understudy.entering()) {
            callback.cancel();
        }
    }
}
