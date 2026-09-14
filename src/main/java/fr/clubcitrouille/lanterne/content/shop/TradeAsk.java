package fr.clubcitrouille.lanterne.content.shop;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Ce que le client demande à la boutique.
 *
 * <h2>Un seul paquet pour trois actions</h2>
 *
 * <p>Ouvrir, acheter, vendre. Trois paquets auraient demandé trois types, trois codecs, trois
 * enregistrements et trois gestionnaires pour des charges utiles identiques à un octet près. Même
 * choix, et même justification, que {@code content.waypoint.WaypointAsk}.
 *
 * <h2>Ce qu'on ne croit pas</h2>
 *
 * <p><b>Rien.</b> Ni l'objet — il doit exister au catalogue du serveur —, ni la quantité — elle est
 * bornée par le lot maximal du serveur —, ni évidemment le prix, qui n'est même pas dans le paquet.
 * Un client modifié peut demander dix mille diamants ; il obtiendra un refus, calculé sur le solde que
 * le serveur lit dans <em>son</em> grand livre.
 *
 * <p>Le verbe est décodé modulo le nombre de verbes plutôt que par un accès direct au tableau : un
 * octet hors bornes lèverait sinon une exception sur le fil réseau, ce qui coûte la connexion du
 * joueur pour un paquet malformé. Refuser proprement vaut mieux que tomber.
 *
 * @param verb  l'action demandée
 * @param item  l'objet concerné ; ignoré pour {@link Verb#OPEN}
 * @param count le nombre d'unités ; ignoré pour {@link Verb#OPEN}
 */
public record TradeAsk(Verb verb, Identifier item, int count) implements CustomPacketPayload {

    public enum Verb { OPEN, BUY, SELL }

    private static final Verb[] VERBS = Verb.values();

    public static final Type<TradeAsk> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "shop_ask"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TradeAsk> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.idMapper(id -> VERBS[Math.floorMod(id, VERBS.length)],
                            Verb::ordinal), TradeAsk::verb,
                    Identifier.STREAM_CODEC, TradeAsk::item,
                    ByteBufCodecs.VAR_INT, TradeAsk::count,
                    TradeAsk::new);

    /** La demande d'ouverture : l'objet ne sert pas, mais le codec en veut un. */
    public static TradeAsk open() {
        return new TradeAsk(Verb.OPEN, Identifier.fromNamespaceAndPath(Lanterne.ID, "aucun"), 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
