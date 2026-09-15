package fr.clubcitrouille.lanterne.content.waypoint;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Les portails visibles, envoyés au client en entier.
 *
 * <h2>Pourquoi un paquet séparé de celui du carnet</h2>
 *
 * <p>Les deux listes ne changent pas au même rythme ni pour les mêmes raisons. Un repère se pose et se
 * retire à la volée ; un portail change quand on l'allume, qu'on le relie ou qu'on le casse — bien plus
 * rarement. Les fondre en un seul paquet aurait fait renvoyer tous les portails du serveur chaque fois
 * qu'un joueur renomme un repère.
 *
 * <p>Pour le reste, la doctrine est celle de {@link WaypointSync} : on renvoie <b>tout</b> plutôt que la
 * différence. Le client n'a aucun état à réconcilier, il remplace. C'est ce qui rend impossible la
 * désynchronisation silencieuse — un portail qu'on croit public et qui ne l'est plus, un portail détruit
 * qui reste proposé comme destination.
 *
 * <p>Et ici, contrairement au carnet, la désynchronisation ne coûterait pas un affichage faux : elle
 * coûterait un joueur envoyé vers un portail qui n'existe plus.
 */
public record GateSync(List<Gate> gates) implements CustomPacketPayload {

    public static final Type<GateSync> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "gates"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GateSync> STREAM_CODEC =
            StreamCodec.composite(
                    Gate.STREAM_CODEC.apply(ByteBufCodecs.list(Atlas.GATE_CEILING)), GateSync::gates,
                    GateSync::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
