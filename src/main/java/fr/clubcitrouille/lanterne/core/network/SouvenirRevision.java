package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * « Le chunk que tu viens de recevoir est la révision N. » Voir {@code core.Souvenir}.
 *
 * <p>Envoyé juste après un chunk complet, sur la même connexion — jamais avant, et sans lien de
 * transaction avec lui au-delà de l'ordre d'arrivée garanti par une connexion unique et fiable. Le
 * client s'en sert pour savoir sous quel numéro persister les octets qu'il vient de recevoir. Si ce
 * paquet n'arrive jamais — module éteint côté serveur, serveur vanilla — le chunk reste affiché
 * normalement, simplement non gardé pour la prochaine session.
 */
public record SouvenirRevision(ChunkPos pos, long revision) implements CustomPacketPayload {

    public static final Type<SouvenirRevision> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "souvenir_revision"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SouvenirRevision> STREAM_CODEC =
            StreamCodec.composite(
                    ChunkPos.STREAM_CODEC, SouvenirRevision::pos,
                    ByteBufCodecs.VAR_LONG, SouvenirRevision::revision,
                    SouvenirRevision::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
