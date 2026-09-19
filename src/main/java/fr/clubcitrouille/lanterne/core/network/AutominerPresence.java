package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Un jeton d'identité, jamais un message. Voir {@code core.Leurre} pour tout le raisonnement —
 * pourquoi ce canal existe, ce qu'il prouve et ce qu'il ne prouve pas — et {@link LeurreNet} pour son
 * branchement. Ce fichier ne porte que la forme du paquet.
 *
 * <h2>Pourquoi zéro champ</h2>
 *
 * <p>Ce paquet n'est <b>jamais envoyé</b>. Sa seule fonction est d'exister dans la table des canaux
 * que {@code PayloadRegistrar} négocie à la connexion — voir {@code NetworkRegistry.hasChannel},
 * vérifiée par lecture de bytecode dans la Javadoc de {@code LeurreNet}. Un client qui a réellement
 * chargé AutoMiner déclare ce canal pendant la négociation ; un client qui ne l'a pas ne le déclare
 * pas. Rien ne transite jamais dessus, donc rien n'a besoin d'être encodé.
 */
public record AutominerPresence() implements CustomPacketPayload {

    public static final Type<AutominerPresence> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Lanterne.ID, "autominer_presence"));

    /** Codec trivial pour un paquet sans donnée — {@code StreamCodec.unit} ne lit ni n'écrit rien. */
    public static final StreamCodec<RegistryFriendlyByteBuf, AutominerPresence> STREAM_CODEC =
            StreamCodec.unit(new AutominerPresence());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
