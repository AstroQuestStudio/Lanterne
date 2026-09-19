package fr.clubcitrouille.lanterne.content.regard;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Ce que Le Regard demande au serveur : « qu'y a-t-il vraiment dans ce bloc-entité ? ».
 *
 * <h2>Pourquoi une requête réseau, et pas une lecture locale</h2>
 *
 * <p>Le client d'un joueur qui n'a jamais ouvert un coffre n'a AUCUNE idée de son contenu — vanilla
 * ne synchronise l'inventaire d'un conteneur qu'à travers le menu ouvert
 * ({@code AbstractContainerMenu}), jamais en arrière-plan. Un four va plus loin : sa progression de
 * cuisson ({@code cookingTimer}/{@code cookingTotalTime}) n'existe, côté client, NULLE PART — ni sur
 * le bloc-entité (les compteurs sont privés et jamais envoyés), ni sur l'état de bloc (qui ne porte
 * que {@code LIT}, un booléen). Deviner ces valeurs donnerait un aperçu FAUX ; ce mod ne fait pas
 * cela (voir la Javadoc de {@code content.painting.Hoard} sur le même principe, côté images).
 *
 * <p>Il faut donc demander au serveur, qui seul connaît la vérité, et se contenter de rester
 * silencieux tant qu'il n'a pas répondu.
 *
 * <h2>Le bloc est désigné par sa position, jamais deviné</h2>
 *
 * <p>Le serveur revérifie lui-même la distance et l'existence du bloc-entité avant de répondre — voir
 * {@link Regard#register} — exactement la même méfiance que {@code content.waypoint.GateAsk} envers
 * un client qui pourrait mentir sur ce qu'il regarde.
 */
public record RegardAsk(BlockPos pos) implements CustomPacketPayload {
    public static final Type<RegardAsk> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "regard_ask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RegardAsk> STREAM_CODEC = StreamCodec.of(
            (buffer, ask) -> BlockPos.STREAM_CODEC.encode(buffer, ask.pos()),
            buffer -> new RegardAsk(BlockPos.STREAM_CODEC.decode(buffer)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
