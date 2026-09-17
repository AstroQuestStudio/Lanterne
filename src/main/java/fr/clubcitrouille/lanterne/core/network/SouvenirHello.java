package fr.clubcitrouille.lanterne.core.network;

import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * L'identité du monde, envoyée une fois à la connexion. Voir {@code core.Souvenir}.
 *
 * <p>Jamais la seed, jamais rien qui en dérive — un UUID tiré au hasard à la création du monde. Voir
 * {@link fr.clubcitrouille.lanterne.core.Souvenir#worldId} pour où et comment il est produit.
 */
public record SouvenirHello(UUID worldId) implements CustomPacketPayload {

    public static final Type<SouvenirHello> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "souvenir_hello"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SouvenirHello> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, SouvenirHello::worldId,
                    SouvenirHello::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
