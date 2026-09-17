package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * « Ce chunk n'a pas changé, sers-toi de la copie que tu as déjà. » Voir {@code core.Souvenir}.
 *
 * <p>Envoyé à la place du chunk complet quand la révision annoncée par {@link SouvenirManifest}
 * correspond à la révision courante du serveur. Le client charge alors ses propres octets depuis son
 * cache de disque et les rejoue dans le même code que celui qui traite un chunk arrivé du réseau —
 * voir {@code SouvenirReceiveMixin} côté client.
 */
public record SouvenirKept(ChunkPos pos) implements CustomPacketPayload {

    public static final Type<SouvenirKept> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "souvenir_kept"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SouvenirKept> STREAM_CODEC =
            StreamCodec.composite(
                    ChunkPos.STREAM_CODEC, SouvenirKept::pos,
                    SouvenirKept::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
