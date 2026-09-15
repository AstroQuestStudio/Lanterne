package fr.clubcitrouille.lanterne.content.waypoint;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Ce que l'écran d'un portail demande au serveur.
 *
 * <h2>Le portail est désigné par son identifiant, jamais par sa position</h2>
 *
 * <p>Une position serait une invitation : un client modifié enverrait les coordonnées du portail d'un
 * autre joueur et le relierait chez lui. Un identifiant ne vaut pas mieux en soi — il se devine aussi
 * mal qu'il se falsifie bien — mais le serveur, lui, <b>vérifie la propriété</b> avant d'obéir, et c'est
 * la seule ligne de défense qui compte. Le paquet n'annonce pas qui parle : le serveur le lit dans la
 * connexion.
 *
 * @param verb   l'action demandée
 * @param gate   le portail concerné
 * @param target la destination, pour {@code LINK} seulement
 * @param name   le nouveau nom, pour {@code RENAME} seulement
 * @param colour la teinte, pour {@code TINT} seulement
 */
public record GateAsk(Verb verb, UUID gate, Optional<UUID> target, String name, int colour)
        implements CustomPacketPayload {

    public enum Verb { LINK, UNLINK, SHARE, RENAME, TINT, FORGET }

    public static final Type<GateAsk> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "gate_ask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GateAsk> STREAM_CODEC = StreamCodec.of(
            (buffer, ask) -> {
                buffer.writeByte(ask.verb().ordinal());
                UUIDUtil.STREAM_CODEC.encode(buffer, ask.gate());
                buffer.writeBoolean(ask.target().isPresent());
                ask.target().ifPresent(other -> UUIDUtil.STREAM_CODEC.encode(buffer, other));
                ByteBufCodecs.stringUtf8(Gate.NAME_LIMIT).encode(buffer, ask.name());
                buffer.writeInt(ask.colour());
            },
            buffer -> {
                int ordinal = buffer.readByte();
                // Un client peut envoyer n'importe quel octet ; un tableau indexé sans borne lèverait
                // une exception sur le fil réseau, ce qui coupe la connexion au lieu de refuser le
                // paquet. On ramène donc l'inconnu sur une action inoffensive.
                Verb verb = ordinal >= 0 && ordinal < Verb.values().length
                        ? Verb.values()[ordinal] : Verb.UNLINK;
                UUID gate = UUIDUtil.STREAM_CODEC.decode(buffer);
                Optional<UUID> target = buffer.readBoolean()
                        ? Optional.of(UUIDUtil.STREAM_CODEC.decode(buffer))
                        : Optional.empty();
                String name = ByteBufCodecs.stringUtf8(Gate.NAME_LIMIT).decode(buffer);
                int colour = buffer.readInt();
                return new GateAsk(verb, gate, target, name, colour);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
