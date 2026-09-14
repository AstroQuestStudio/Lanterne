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
 * <h2>Le cours et la part — deux nombres, et il en faut deux</h2>
 *
 * <p>Depuis les puits ({@link Toll}), deux joueurs devant le même article ne voient plus le même
 * prix de rachat : celui qui vient d'en vendre dix mille touche moins que celui qui n'y a jamais
 * touché. On aurait pu n'envoyer qu'un seul nombre, le prix personnel, et s'en tenir là. Ce serait
 * plus court et ce serait moins bon : le joueur verrait son prix baisser sans savoir si c'est
 * <em>le marché</em> qui a bougé ou <em>lui</em> qui a trop vendu, et ce sont deux situations qui
 * appellent deux conduites opposées — attendre, ou aller vendre autre chose.
 *
 * <p>Ce paquet porte donc {@link #sell}, le <b>cours du marché</b>, le même pour tout le monde, et
 * {@link #keep}, la <b>part</b> que ce joueur-ci en touche. Leur produit, {@link #mine()}, est le
 * prix que la caisse appliquera. L'arithmétique du produit est <b>la même des deux côtés</b> —
 * {@link Toll#net} est appelée par la caisse et par l'écran — donc il n'y a pas de place pour un
 * écart d'arrondi entre ce qui est affiché et ce qui est débité.
 *
 * @param item     l'objet
 * @param buy      ce que le joueur paie pour une unité, en centimes
 * @param sell     le COURS DU MARCHÉ au rachat, en centimes ; zéro = non racheté
 * @param trend    l'écart au prix d'ancrage, en pour mille — c'est la flèche de l'écran
 * @param category le rayon
 * @param keep     la part du cours que CE joueur touche, en pour mille ; 1000 = tout
 */
public record Quote(Identifier item, long buy, long sell, int trend, String category, int keep) {

    public static final StreamCodec<RegistryFriendlyByteBuf, Quote> STREAM_CODEC =
            StreamCodec.composite(
                    Identifier.STREAM_CODEC, Quote::item,
                    ByteBufCodecs.VAR_LONG, Quote::buy,
                    ByteBufCodecs.VAR_LONG, Quote::sell,
                    ByteBufCodecs.VAR_INT, Quote::trend,
                    ByteBufCodecs.stringUtf8(Offer.CATEGORY_LIMIT), Quote::category,
                    ByteBufCodecs.VAR_INT, Quote::keep,
                    Quote::new);

    /** L'article, coté au volume du moment, et vu par un joueur dont la part est connue. */
    public static Quote of(Offer offer, long volume, int keep) {
        return new Quote(offer.item(), Drift.buy(offer, volume), Drift.sell(offer, volume),
                Drift.trend(volume), offer.category(), keep);
    }

    /** Ce que la boutique paiera réellement à CE joueur, en centimes. */
    public long mine() {
        return Toll.net(this.sell, this.keep);
    }

    /** Vrai si une retenue s'applique à ce joueur sur cet article. */
    public boolean withheld() {
        return this.sell > 0L && this.keep < Toll.FULL;
    }

    /** La retenue, en pour cent, telle que l'écran l'écrit. */
    public int withheldPercent() {
        return (Toll.FULL - this.keep) / 10;
    }

    public boolean bought() {
        return this.sell > 0L;
    }
}
