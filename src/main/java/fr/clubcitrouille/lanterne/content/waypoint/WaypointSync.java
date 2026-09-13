package fr.clubcitrouille.lanterne.content.waypoint;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le carnet envoyé au client, en entier.
 *
 * <h2>Pourquoi tout renvoyer plutôt que la différence</h2>
 *
 * <p>Un carnet compte cinq repères par joueur, plus les partagés : quelques dizaines au maximum, soit
 * moins d'un kilo-octet. Envoyer la liste entière à chaque changement coûte moins que de tenir un
 * journal de différences des deux côtés — et surtout, cela rend <b>impossible</b> la désynchronisation
 * silencieuse, qui est le défaut classique de ce genre de système : un repère qu'on croit partagé et
 * qui ne l'est pas, un repère supprimé qui reste affiché.
 *
 * <p>Le client n'a donc aucun état à réconcilier. Il remplace ce qu'il a par ce qu'il reçoit.
 */
public record WaypointSync(List<Waypoint> marks) implements CustomPacketPayload {

    public static final Type<WaypointSync> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "waypoints"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WaypointSync> STREAM_CODEC =
            StreamCodec.composite(
                    Waypoint.STREAM_CODEC.apply(ByteBufCodecs.list(256)), WaypointSync::marks,
                    WaypointSync::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
