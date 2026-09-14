package fr.clubcitrouille.lanterne.content.painting;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * « Je n'ai pas celle-ci. » — le seul mot que le client dise au serveur à propos des images.
 *
 * <h2>Un paquet minuscule qui commande un gros transfert : il faut donc s'en méfier</h2>
 *
 * <p>Trente-deux caractères suffisent à déclencher l'envoi d'un mébioctet. Un client modifié
 * pourrait en émettre mille par seconde. Les garde-fous sont donc du côté du serveur et nulle part
 * ailleurs — voir {@code Easel#onWish} : l'empreinte est vérifiée comme bien formée, elle doit
 * correspondre à une image que le serveur possède réellement, et une demande déjà en cours pour le
 * même joueur est ignorée plutôt que mise en file.
 *
 * <p>Le débit, lui, est plafonné à la source : le serveur n'envoie jamais plus de
 * {@code Studio#shardsPerTick()} segments par tick et par joueur, quoi qu'on lui demande. Un client
 * pressé n'obtient donc rien de plus qu'un client patient.
 */
public record PaintingWish(String hash) implements CustomPacketPayload {

    public static final Type<PaintingWish> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "tableau_voeu"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PaintingWish> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(Plate.HASH_LENGTH), PaintingWish::hash,
                    PaintingWish::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
