package fr.clubcitrouille.lanterne.content.shop;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La réponse de la caisse : ce qui s'est passé, et le marché tel qu'il est maintenant.
 *
 * <h2>Pourquoi un code et non une phrase</h2>
 *
 * <p>Le serveur pourrait envoyer « Il te manque 42,50 ¤ » tout écrit. Il envoie un code et un nombre,
 * et c'est le client qui compose la phrase. Deux raisons : le texte reste du côté où il s'affiche,
 * donc traduisible et modifiable sans toucher au serveur ; et un code est un octet là où une phrase
 * est cinquante, sur un paquet qui part à chaque clic.
 *
 * <h2>Pourquoi le prix repart avec la réponse</h2>
 *
 * <p>Parce que la transaction vient précisément de le faire bouger. Sans cela, le client afficherait
 * l'ancien prix jusqu'à la prochaine ouverture de l'écran, et le joueur verrait son total changer sous
 * ses yeux au deuxième achat sans comprendre pourquoi. Renvoyer les trois nombres de l'article touché
 * coûte vingt octets et supprime la question.
 *
 * @param outcome ce qui s'est passé
 * @param detail  un nombre dont le sens dépend de {@code outcome} — voir {@link Till.Outcome}
 * @param count   les unités réellement échangées
 * @param total   les centimes réellement mouvementés
 * @param balance le solde après l'opération, en centimes
 * @param item    l'article concerné
 * @param buy     son prix d'achat, après l'opération
 * @param sell    son prix de rachat, après l'opération
 * @param trend   son écart à l'ancre, en pour mille, après l'opération
 */
public record TradeEcho(Till.Outcome outcome, long detail, int count, long total, long balance,
                        Identifier item, long buy, long sell, int trend)
        implements CustomPacketPayload {

    private static final Till.Outcome[] OUTCOMES = Till.Outcome.values();

    public static final Type<TradeEcho> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "shop_echo"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TradeEcho> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.idMapper(id -> OUTCOMES[Math.floorMod(id, OUTCOMES.length)],
                            Till.Outcome::ordinal), TradeEcho::outcome,
                    ByteBufCodecs.VAR_LONG, TradeEcho::detail,
                    ByteBufCodecs.VAR_INT, TradeEcho::count,
                    ByteBufCodecs.VAR_LONG, TradeEcho::total,
                    ByteBufCodecs.VAR_LONG, TradeEcho::balance,
                    Identifier.STREAM_CODEC, TradeEcho::item,
                    ByteBufCodecs.VAR_LONG, TradeEcho::buy,
                    ByteBufCodecs.VAR_LONG, TradeEcho::sell,
                    ByteBufCodecs.VAR_INT, TradeEcho::trend,
                    TradeEcho::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
