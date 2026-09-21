package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/** Une tranche d'un fichier du {@link MecheCatalogue}, même forme que {@link MecheChunk}. */
public record MecheFichierTranche(String name, int offset, int size, byte[] data)
        implements CustomPacketPayload {

    public static final Type<MecheFichierTranche> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "meche_fichier_tranche"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MecheFichierTranche> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MecheCatalogue.NAME_LIMIT), MecheFichierTranche::name,
                    ByteBufCodecs.VAR_INT, MecheFichierTranche::offset,
                    ByteBufCodecs.VAR_INT, MecheFichierTranche::size,
                    ByteBufCodecs.byteArray(MecheChunk.MAX_CHUNK_BYTES), MecheFichierTranche::data,
                    MecheFichierTranche::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
