package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Une tranche du jar demandé. Même forme, et pour les mêmes raisons, que
 * {@code content.painting.PaintingShard} — voir sa Javadoc pour le détail du découpage en tranches et
 * pourquoi un décalage plutôt qu'un numéro de segment.
 *
 * <p>{@code modId} est répété dans chaque tranche plutôt que déduit de la seule demande en cours : ce
 * mécanisme n'autorise aujourd'hui qu'un transfert à la fois par joueur, mais une tranche
 * auto-descriptive coûte quelques octets et évite qu'une extension future à plusieurs mods gérés en
 * parallèle ne redécouvre ce même choix.
 *
 * @param modId le mod concerné.
 * @param offset la position de cette tranche dans le jar, en octets.
 * @param size la taille totale du jar, connue dès la première tranche.
 * @param data la tranche d'octets.
 */
public record MecheChunk(String modId, int offset, int size, byte[] data)
        implements CustomPacketPayload {

    /** Taille maximale d'une tranche — même plafond que {@code content.painting.PaintingShard}. */
    public static final int MAX_CHUNK_BYTES = 32768;

    public static final Type<MecheChunk> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "meche_eclat"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MecheChunk> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MecheManifest.MOD_ID_LIMIT), MecheChunk::modId,
                    ByteBufCodecs.VAR_INT, MecheChunk::offset,
                    ByteBufCodecs.VAR_INT, MecheChunk::size,
                    ByteBufCodecs.byteArray(MAX_CHUNK_BYTES), MecheChunk::data,
                    MecheChunk::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
