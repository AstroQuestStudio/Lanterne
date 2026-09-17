package fr.clubcitrouille.lanterne.core.network;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Ce que le client prétend déjà connaître, envoyé une fois à la connexion. Voir
 * {@code core.Souvenir}.
 *
 * <p>Un seul envoi, pas un aller-retour par chunk : attendre une confirmation avant de décider
 * d'envoyer chaque chunk ajouterait une latence que ce module a précisément pour but de retirer.
 * Le client annonce donc d'un coup tout ce qu'il a en mémoire de disque pour cette dimension, borné à
 * {@link #CAP} entrées — largement au-dessus de ce qu'une distance de vue raisonnable représente.
 *
 * <p>Le serveur ne fait confiance à rien de ce qui arrive ici au-delà de son usage prévu : une
 * révision qui ne correspond pas à la sienne se traduit simplement par un envoi complet, jamais par
 * une décision de jeu. Voir la javadoc de {@code Souvenir} sur ce que ce module n'est pas.
 *
 * @param dimension l'identifiant de la dimension concernée
 * @param entries   les couples (position de chunk empaquetée, révision connue)
 */
public record SouvenirManifest(Identifier dimension, List<Entry> entries)
        implements CustomPacketPayload {

    /** Cap sur le nombre d'entrées : une distance de vue de soixante-quatre en compte 16 641. */
    private static final int CAP = 20000;

    public record Entry(long chunkPos, long revision) {
        public static final StreamCodec<io.netty.buffer.ByteBuf, Entry> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_LONG, Entry::chunkPos,
                        ByteBufCodecs.VAR_LONG, Entry::revision,
                        Entry::new);
    }

    public static final Type<SouvenirManifest> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "souvenir_manifest"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SouvenirManifest> STREAM_CODEC =
            StreamCodec.composite(
                    Identifier.STREAM_CODEC, SouvenirManifest::dimension,
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list(CAP)), SouvenirManifest::entries,
                    SouvenirManifest::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** La clef d'une entrée : mêmes coordonnées de section que {@link ChunkPos#pack()}. */
    public static long pack(ChunkPos pos) {
        return pos.pack();
    }
}
