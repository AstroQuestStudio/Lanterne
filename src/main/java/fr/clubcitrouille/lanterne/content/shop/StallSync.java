package fr.clubcitrouille.lanterne.content.shop;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le catalogue et le solde, envoyés au client en entier.
 *
 * <h2>Pourquoi tout renvoyer plutôt que la différence</h2>
 *
 * <p>Même raisonnement que {@code content.waypoint.WaypointSync}, et il vaut encore plus ici. Cent
 * vingt articles font sept kilo-octets ; le paquet ne part qu'à la connexion, à l'ouverture de
 * l'écran, et après un rechargement du catalogue — trois fois par session, pas trois fois par
 * seconde. Tenir un journal de différences des deux côtés coûterait plus cher en code et en bogues
 * qu'il ne fait gagner en octets.
 *
 * <p>Surtout, cela rend <b>impossible</b> la désynchronisation silencieuse : le client n'a aucun état
 * à réconcilier, il remplace ce qu'il a par ce qu'il reçoit. Un prix affiché qui n'est plus celui du
 * serveur est le pire défaut qu'une boutique puisse avoir — on clique en croyant payer un prix, on en
 * paie un autre.
 *
 * <h2>Le drapeau {@code open}</h2>
 *
 * <p>Il évite un paquet de plus. Le serveur ne peut pas « ouvrir un écran » à distance : il envoie ce
 * qu'il faut pour l'ouvrir, et dit s'il faut le faire. C'est ce qui permet à la commande
 * {@code /boutique} d'ouvrir l'interface <b>avec des prix frais par construction</b> — le client ne
 * peut pas afficher un catalogue périmé, puisqu'il vient de le recevoir.
 *
 * @param open    vrai si le client doit ouvrir l'écran de la boutique
 * @param symbol  le symbole de la monnaie, décidé par le serveur seul
 * @param balance le solde du destinataire, en centimes
 * @param batch   le lot maximal du serveur — l'écran en a besoin pour son bouton « tout »
 * @param quotes  le catalogue coté
 */
public record StallSync(boolean open, String symbol, long balance, int batch, List<Quote> quotes)
        implements CustomPacketPayload {

    public static final Type<StallSync> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "shop_stall"));

    /**
     * Nombre maximal d'articles qu'un paquet peut porter.
     *
     * <p>Ce n'est pas une précaution de style : {@code ByteBufCodecs.list} <b>lève une exception à
     * l'écriture</b> au-delà de son plafond, et non à la lecture. Un catalogue trop gros ferait donc
     * échouer la synchronisation de chaque joueur à chaque connexion, sans message utile.
     *
     * <p>Le vanilla complet en compte 1 352. Seize mille laissent la place à un très gros modpack et
     * restent sous la limite d'un mébioctet par charge utile que le protocole impose de toute façon —
     * un article pèse une cinquantaine d'octets, seize mille en font huit cents kibioctets.
     * {@code Bazaar.sync} tronque et le dit plutôt que de laisser passer l'exception.
     */
    public static final int CEILING = 16384;

    public static final StreamCodec<RegistryFriendlyByteBuf, StallSync> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, StallSync::open,
                    ByteBufCodecs.stringUtf8(8), StallSync::symbol,
                    ByteBufCodecs.VAR_LONG, StallSync::balance,
                    ByteBufCodecs.VAR_INT, StallSync::batch,
                    Quote.STREAM_CODEC.apply(ByteBufCodecs.list(CEILING)), StallSync::quotes,
                    StallSync::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
