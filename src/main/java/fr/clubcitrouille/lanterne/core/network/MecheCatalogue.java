package fr.clubcitrouille.lanterne.core.network;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le catalogue des mods CLIENTS que ce serveur publie : ce que chaque client doit avoir dans son
 * {@code mods/}, et ce qu'il doit ne plus y avoir. Envoyé à la connexion, à côté de
 * {@link MecheManifest} (qui ne parle que du jar de Lanterne lui-même).
 *
 * <p>Le serveur ne demande pas au client de lui faire confiance sur un nom : chaque entrée porte
 * l'empreinte SHA-256 du fichier, recalculée par le client sur les octets reçus.
 */
public record MecheCatalogue(List<Entry> files, List<String> retired) implements CustomPacketPayload {

    public static final int NAME_LIMIT = 128;
    public static final int MAX_FILES = 256;

    /** Un fichier publié : son nom dans {@code mods/}, son empreinte, sa taille. */
    public record Entry(String name, byte[] sha256, int size) {}

    private static final StreamCodec<RegistryFriendlyByteBuf, Entry> ENTRY_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(NAME_LIMIT), Entry::name,
                    ByteBufCodecs.byteArray(MecheManifest.HASH_LENGTH), Entry::sha256,
                    ByteBufCodecs.VAR_INT, Entry::size,
                    Entry::new);

    public static final Type<MecheCatalogue> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "meche_catalogue"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MecheCatalogue> STREAM_CODEC =
            StreamCodec.composite(
                    ENTRY_CODEC.apply(ByteBufCodecs.list(MAX_FILES)), MecheCatalogue::files,
                    ByteBufCodecs.stringUtf8(NAME_LIMIT).apply(ByteBufCodecs.list(MAX_FILES)),
                    MecheCatalogue::retired,
                    MecheCatalogue::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
