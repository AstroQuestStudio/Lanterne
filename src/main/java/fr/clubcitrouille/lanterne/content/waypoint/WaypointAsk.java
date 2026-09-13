package fr.clubcitrouille.lanterne.content.waypoint;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Ce que le client demande au serveur depuis l'interface.
 *
 * <h2>Un seul paquet pour quatre actions, et pourquoi ce n'est pas de la paresse</h2>
 *
 * <p>Poser, retirer, partager, recolorer : quatre paquets distincts auraient demandé quatre types,
 * quatre codecs, quatre enregistrements et quatre gestionnaires, pour des charges utiles presque
 * identiques. Un verbe dans un octet suffit, et la validation reste la même dans les quatre cas.
 *
 * <p><b>Le serveur ne fait confiance à rien de ce qui arrive ici.</b> Ni au nom, ni à la position, ni
 * surtout à la propriété du repère : c'est le serveur qui lit l'identifiant du joueur dans la
 * connexion, jamais le paquet qui l'annonce. Un client modifié ne peut donc pas toucher au carnet
 * d'un autre.
 *
 * @param verb   l'action demandée
 * @param name   le repère concerné
 * @param pos    l'endroit, pour la pose seulement
 * @param colour la teinte, pour la pose et la recoloration
 */
public record WaypointAsk(Verb verb, String name, BlockPos pos, int colour)
        implements CustomPacketPayload {

    public enum Verb { PLACE, DROP, SHARE, TINT }

    public static final Type<WaypointAsk> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "waypoint_ask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WaypointAsk> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.idMapper(id -> Verb.values()[id], Verb::ordinal), WaypointAsk::verb,
                    ByteBufCodecs.stringUtf8(Waypoint.NAME_LIMIT), WaypointAsk::name,
                    BlockPos.STREAM_CODEC, WaypointAsk::pos,
                    ByteBufCodecs.INT, WaypointAsk::colour,
                    WaypointAsk::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
