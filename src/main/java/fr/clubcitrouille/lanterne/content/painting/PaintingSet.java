package fr.clubcitrouille.lanterne.content.painting;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * « Mets cette image, de cette taille, sur cette toile. »
 *
 * <h2>Ce paquet ne porte aucun pixel, et c'est voulu</h2>
 *
 * <p>Le joueur vient peut-être de télécharger huit mébioctets d'image. Il serait naturel de les
 * joindre ici. Ce serait un gâchis dans le cas le plus fréquent : <b>le serveur connaît souvent déjà
 * l'image</b> — quelqu'un l'a posée avant, ou elle vient du dossier de la bibliothèque.
 *
 * <p>On annonce donc l'empreinte, et le serveur répond ce qu'il veut : rien, s'il l'a déjà — la toile
 * change instantanément — ou {@link PaintingWant}, s'il ne l'a pas, et l'envoi commence alors.
 * Coller deux fois la même adresse ne transmet les octets qu'une seule fois.
 *
 * @param entityId la toile visée.
 * @param hash l'image voulue, ou une chaîne vide pour rendre la toile vierge.
 * @param width largeur voulue, en blocs.
 * @param height hauteur voulue, en blocs.
 * @param trim l'encadrement voulu. Il se change seul, sans toucher a l'image : rouvrir l'atelier
 *     pour passer du bois a l'or ne retransmet rien.
 */
public record PaintingSet(int entityId, String hash, int width, int height, int trim)
        implements CustomPacketPayload {

    public static final Type<PaintingSet> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "tableau_pose"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PaintingSet> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PaintingSet::entityId,
                    ByteBufCodecs.stringUtf8(Plate.HASH_LENGTH), PaintingSet::hash,
                    ByteBufCodecs.VAR_INT, PaintingSet::width,
                    ByteBufCodecs.VAR_INT, PaintingSet::height,
                    ByteBufCodecs.VAR_INT, PaintingSet::trim,
                    PaintingSet::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
