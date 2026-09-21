package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/** Un client demande un fichier du {@link MecheCatalogue}, par son nom. Jamais un chemin : le
 * serveur ne sert que ce qu'il a lui-même listé. */
public record MecheFichierDemande(String name) implements CustomPacketPayload {

    public static final Type<MecheFichierDemande> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "meche_fichier_demande"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MecheFichierDemande> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MecheCatalogue.NAME_LIMIT), MecheFichierDemande::name,
                    MecheFichierDemande::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
