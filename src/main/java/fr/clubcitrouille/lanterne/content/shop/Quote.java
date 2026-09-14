package fr.clubcitrouille.lanterne.content.shop;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * Un article tel que le client le voit : des prix déjà calculés.
 *
 * <h2>Pourquoi le client ne reçoit pas les ancres</h2>
 *
 * <p>Il serait plus économique d'envoyer une fois le catalogue d'ancres, puis les seuls volumes, et de
 * laisser le client appliquer la formule. Ce serait aussi la porte ouverte à un client modifié qui
 * affiche les prix qu'il veut — et surtout à un écart entre ce que le joueur voit et ce que le serveur
 * facture, c'est-à-dire à un total qui change au moment de valider.
 *
 * <p>Le serveur envoie donc des prix <b>finis</b>, en centimes, exactement ceux que la caisse
 * appliquera. La formule de dérive, l'amplitude, l'élasticité ne quittent jamais le serveur. Le client
 * ne calcule qu'une chose : le total d'un lot, par une multiplication, et le serveur le refait de
 * toute façon.
 *
 * @param item     l'objet
 * @param buy      ce que le joueur paie pour une unité, en centimes
 * @param sell     ce que la boutique paie pour une unité, en centimes ; zéro = non racheté
 * @param trend    l'écart au prix d'ancrage, en pour mille — c'est la flèche de l'écran
 * @param category le rayon
 */
public record Quote(Identifier item, long buy, long sell, int trend, String category) {

    public static final StreamCodec<RegistryFriendlyByteBuf, Quote> STREAM_CODEC =
            StreamCodec.composite(
                    Identifier.STREAM_CODEC, Quote::item,
                    ByteBufCodecs.VAR_LONG, Quote::buy,
                    ByteBufCodecs.VAR_LONG, Quote::sell,
                    ByteBufCodecs.VAR_INT, Quote::trend,
                    ByteBufCodecs.stringUtf8(Offer.CATEGORY_LIMIT), Quote::category,
                    Quote::new);

    /** L'article, coté au volume du moment. */
    public static Quote of(Offer offer, long volume) {
        return new Quote(offer.item(), Drift.buy(offer, volume), Drift.sell(offer, volume),
                Drift.trend(volume), offer.category());
    }

    public boolean bought() {
        return this.sell > 0L;
    }
}
