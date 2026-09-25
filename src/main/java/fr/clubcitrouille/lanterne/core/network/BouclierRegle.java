package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * « Sur ce serveur, le bouclier est obligatoire » — envoyé une fois à la connexion. Voir
 * {@code core.Bouclier} pour ce qu'il protège, et {@code client.BouclierClient} pour ce que le client
 * en fait.
 *
 * @param obligatoire vrai si le serveur impose le bouclier.
 */
public record BouclierRegle(boolean obligatoire) implements CustomPacketPayload {
    public static final Type<BouclierRegle> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "bouclier_regle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BouclierRegle> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, BouclierRegle::obligatoire, BouclierRegle::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
