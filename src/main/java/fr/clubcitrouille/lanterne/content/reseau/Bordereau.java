package fr.clubcitrouille.lanterne.content.reseau;

import java.util.List;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le bordereau : les deux plis du Guichet, et rien d'autre.
 *
 * <h2>Pourquoi le Guichet ne porte jamais sa position dans ces plis</h2>
 *
 * <p>{@link Demande} ne transporte que l'objet et la quantité — jamais un {@code BlockPos}. Le serveur
 * retrouve TOUJOURS le Guichet visé par lui-même, via le menu actuellement ouvert par ce joueur
 * ({@code player.containerMenu}), exactement comme {@code ServerboundSetBeaconPacket} vanilla ne
 * transporte ni la position de la balise ni son niveau : la position d'un menu ouvert n'est jamais une
 * information qu'un client a besoin d'annoncer, seulement une information que le serveur, qui a ouvert
 * ce menu lui-même, connaît déjà avec certitude.
 *
 * <h2>Pourquoi {@link Stock} porte l'objet directement, pas son identifiant textuel</h2>
 *
 * <p>{@code ByteBufCodecs.registry(Registries.ITEM)} encode un {@code Item} par son identifiant
 * d'enregistrement (un entier synchronisé à la connexion), pas par une chaîne — plus court sur le fil,
 * et ce que ce moteur offre déjà pour tout type qui vient d'un registre.
 */
public final class Bordereau {
    private Bordereau() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Lanterne.ID, path);
    }

    /** Une ligne du stock affiché : un objet, et son total sur tout le réseau. */
    public record Entree(Item item, int quantite) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entree> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.registry(Registries.ITEM), Entree::item,
                ByteBufCodecs.VAR_INT, Entree::quantite,
                Entree::new);
    }

    /**
     * « Voici ce que le réseau contient. » Du serveur vers le client — une fois à l'ouverture du
     * Guichet, et republié seulement quand {@code GuichetBlockEntity} constate un changement. Voir sa
     * Javadoc pour la cadence exacte.
     */
    public record Stock(List<Entree> objets) implements CustomPacketPayload {
        public static final Type<Stock> TYPE = new Type<>(id("guichet_stock"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Stock> STREAM_CODEC = StreamCodec.composite(
                Entree.STREAM_CODEC.apply(ByteBufCodecs.list(1024)), Stock::objets,
                Stock::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * « Livre-moi cet objet, dans le coffre de collecte à côté du Guichet ouvert. » Du client vers le
     * serveur, déclenché par un clic sur une ligne de {@link Stock}.
     */
    public record Demande(Item item, int quantite) implements CustomPacketPayload {
        public static final Type<Demande> TYPE = new Type<>(id("guichet_demande"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Demande> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.registry(Registries.ITEM), Demande::item,
                ByteBufCodecs.VAR_INT, Demande::quantite,
                Demande::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
