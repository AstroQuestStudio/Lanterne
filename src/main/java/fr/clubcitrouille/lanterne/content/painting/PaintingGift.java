package fr.clubcitrouille.lanterne.content.painting;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Une tranche d'image qui monte : du client vers le serveur.
 *
 * <h2>La même forme que {@link PaintingShard}, et un tout autre niveau de méfiance</h2>
 *
 * <p>Le découpage, le décalage, la taille totale : tout est identique au paquet descendant, et pour
 * les mêmes raisons — un mébioctet d'un bloc gèle le fil réseau, le même en quarante tranches ne se
 * voit pas.
 *
 * <p>Ce qui change entièrement, c'est ce qu'on en croit. Un client fait confiance à son serveur ; un
 * serveur ne fait <b>jamais</b> confiance à un client. Chaque tranche reçue est donc bornée : la
 * taille annoncée doit rester sous le plafond, elle ne doit pas changer d'une tranche à l'autre, et
 * une tranche qui déborderait du tampon fait abandonner tout le réassemblage plutôt qu'agrandir quoi
 * que ce soit.
 *
 * <p>À la fin, l'empreinte est <b>recalculée</b>. Sans ce contrôle, un client pourrait faire passer
 * n'importe quel contenu sous l'empreinte d'une image que tout le monde a déjà — et l'installer dans
 * le cache disque de chaque joueur du serveur, où il resterait pour toujours puisque le cache tient
 * pour bon ce qu'il contient déjà.
 *
 * @param hash l'empreinte annoncée, qui sera vérifiée à l'arrivée.
 * @param offset la position de cette tranche, en octets.
 * @param size la taille totale de l'image.
 * @param data la tranche.
 */
public record PaintingGift(String hash, int offset, int size, byte[] data)
        implements CustomPacketPayload {

    public static final Type<PaintingGift> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "tableau_don"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PaintingGift> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(Plate.HASH_LENGTH), PaintingGift::hash,
                    ByteBufCodecs.VAR_INT, PaintingGift::offset,
                    ByteBufCodecs.VAR_INT, PaintingGift::size,
                    ByteBufCodecs.byteArray(32768), PaintingGift::data,
                    PaintingGift::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
