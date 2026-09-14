package fr.clubcitrouille.lanterne.content.painting;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Un éclat d'image : une tranche d'octets PNG, et de quoi savoir où la remettre.
 *
 * <h2>Pourquoi découper plutôt que compresser davantage</h2>
 *
 * <p>Minecraft refuse les paquets au-delà de deux mébioctets, et sa couche de compression traite
 * chaque paquet indépendamment : un gros paquet ne se comprime pas mieux qu'une suite de petits.
 * Découper n'est donc pas un pis-aller, c'est la seule forme possible — et elle apporte en plus la
 * régulation, qui est le vrai sujet.
 *
 * <p>Un mébioctet envoyé d'un bloc gèle le fil réseau du client le temps de la décompression et fait
 * sauter une image. Le même mébioctet en quarante tranches espacées d'un tick n'est jamais visible.
 * C'est pour cela que le plafond est en <b>segments par tick</b> et non en octets par seconde : la
 * grandeur qui compte est le travail fait <em>pendant une image</em>, pas le débit moyen.
 *
 * <h2>Aucune compression supplémentaire</h2>
 *
 * <p>Le contenu est du PNG, donc déjà dégonflé par l'algorithme <i>deflate</i>. Le repasser dans le
 * dégonfleur de Minecraft ne gagne rien — quelques pour mille au mieux — et coûte deux passes de
 * compression au lieu d'une. On laisse donc faire la couche réseau, qui fait déjà le travail.
 *
 * <h2>Un décalage plutôt qu'un numéro de segment</h2>
 *
 * <p>La première version portait « segment n sur m », et le client en déduisait la position en
 * multipliant par la taille de segment <em>de ses propres réglages</em>. C'était faux : rien
 * n'oblige un serveur et son client à avoir la même valeur, et un client mieux réglé que son serveur
 * aurait réassemblé des images en charpie — de façon intermittente, puisque le dernier segment est
 * plus court que les autres.
 *
 * <p>Le décalage et la taille totale suppriment la question. Le client n'a plus besoin de connaître
 * la taille de segment du serveur, les segments peuvent arriver dans le désordre, et la fin se
 * reconnaît au compte d'octets reçus plutôt qu'au compte de paquets.
 *
 * @param hash l'empreinte de l'image entière, qui sert de clé de réassemblage.
 * @param offset la position de cette tranche dans l'image, en octets.
 * @param size la taille totale de l'image, connue dès le premier segment pour dimensionner le tampon
 *     une seule fois plutôt que de le faire grandir.
 * @param data la tranche d'octets.
 */
public record PaintingShard(String hash, int offset, int size, byte[] data)
        implements CustomPacketPayload {

    public static final Type<PaintingShard> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "tableau_eclat"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PaintingShard> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(Plate.HASH_LENGTH), PaintingShard::hash,
                    ByteBufCodecs.VAR_INT, PaintingShard::offset,
                    ByteBufCodecs.VAR_INT, PaintingShard::size,
                    ByteBufCodecs.byteArray(32768), PaintingShard::data,
                    PaintingShard::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
