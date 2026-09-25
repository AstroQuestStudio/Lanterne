package fr.clubcitrouille.lanterne.content.waypoint;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La réponse du serveur à une demande de l'écran de portail : réussite ou refus, avec la phrase à
 * afficher DANS l'écran.
 *
 * <p>Avant ce paquet, le résultat partait seulement dans le chat, invisible tant que l'écran reste
 * ouvert : un lien refusé ressemblait à un clic qui n'avait rien fait. Enregistré comme canal
 * facultatif (voir {@code Waypoints}) : un client plus ancien se connecte quand même, il ne reçoit
 * simplement pas ce retour.
 */
public record GateReply(boolean ok, String text) implements CustomPacketPayload {
    public static final int TEXT_LIMIT = 256;

    public static final Type<GateReply> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "gate_reply"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GateReply> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, GateReply::ok,
                    ByteBufCodecs.stringUtf8(TEXT_LIMIT), GateReply::text,
                    GateReply::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
