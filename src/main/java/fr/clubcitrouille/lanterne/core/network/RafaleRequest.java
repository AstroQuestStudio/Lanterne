package fr.clubcitrouille.lanterne.core.network;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * « Pose — ou casse — ces cases, d'un coup. » Voir {@code core.Rafale} pour tout le raisonnement
 * (même pont explicite qu'{@code core.Increvable}) et le README pour la section « Le pont AutoMiner ».
 * Ce fichier ne porte que la forme du paquet.
 *
 * <h2>Pourquoi une seule case en écart, et pourquoi une liste plutôt qu'une palette</h2>
 *
 * <p>{@code positions} et {@code states} avancent en parallèle — {@code states.get(i)} est ce qu'on
 * veut à {@code positions.get(i)} — sauf en démolition ({@link #demolish}), où {@code states} est
 * vide : casser ne demande pas de dire ce qu'on veut, juste où. Une vraie palette (identifiants
 * partagés + indices) économiserait des octets sur un mur homogène, mais le canal est déjà compressé
 * par le protocole (voir {@code MinecraftCipherEncoder}/le seuil de compression du serveur) — les
 * états répétés compressent déjà bien, et coder une palette à la main pour regagner ce que zlib donne
 * gratuitement n'aurait acheté que de la complexité.
 *
 * <p>{@link Block#BLOCK_STATE_REGISTRY} est le même {@code IdMapper} que vanilla emploie pour
 * {@code ClientboundBlockUpdatePacket} (vérifié au {@code javap} sur le jar client 26.3 réel) — on ne
 * réinvente donc rien pour coder un {@code BlockState} au fil de l'eau, on reprend le chemin que le
 * moteur emprunte déjà pour la même donnée.
 *
 * @param positions les cases visées, en coordonnées du monde.
 * @param states    l'état voulu à chaque case, même longueur que {@code positions} — vide si
 *                  {@link #demolish}.
 * @param demolish  vrai pour casser les cases de {@code positions}, faux pour y poser {@code states}.
 */
public record RafaleRequest(List<BlockPos> positions, List<BlockState> states, boolean demolish)
        implements CustomPacketPayload {

    /**
     * Borne dure, appliquée à TOUT le monde — voir {@code Rafale.LIMIT} pour le pourquoi du chiffre.
     * Répétée ici pour que le codec refuse un paquet hors-borne avant même de le passer à la
     * logique : un client qui mentirait sur la taille ne doit pas pouvoir faire grossir un buffer
     * réseau sans limite.
     */
    public static final int LIMIT = 4096;

    public static final Type<RafaleRequest> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "rafale_demande"));

    // BlockPos.STREAM_CODEC et l'idMapper ci-dessous sont typés StreamCodec<ByteBuf, ...> — pas
    // besoin de les élargir en StreamCodec<RegistryFriendlyByteBuf, ...> à la main :
    // StreamCodec.composite accepte StreamCodec<? super B, T>, et c'est exactement ainsi que vanilla
    // compose déjà les deux (voir ClientboundBlockUpdatePacket.STREAM_CODEC, même paire de codecs).
    public static final StreamCodec<RegistryFriendlyByteBuf, RafaleRequest> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(LIMIT)), RafaleRequest::positions,
                    ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY).apply(ByteBufCodecs.list(LIMIT)),
                    RafaleRequest::states,
                    ByteBufCodecs.BOOL, RafaleRequest::demolish,
                    RafaleRequest::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
