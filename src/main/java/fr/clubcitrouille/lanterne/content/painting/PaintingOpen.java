package fr.clubcitrouille.lanterne.content.painting;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * « Ouvre l'écran d'édition pour cette toile. »
 *
 * <h2>Pourquoi le serveur commande l'ouverture, alors que l'écran est côté client</h2>
 *
 * <p>Le client pourrait ouvrir l'écran tout seul au moment du clic : {@code Entity.interact} est
 * appelé des deux côtés, et rien ne l'en empêcherait techniquement. Ce serait une faute de
 * conception.
 *
 * <p>La décision « ce joueur a-t-il le droit de modifier cette toile » appartient au serveur, et à
 * lui seul — il est le seul à connaître le réglage {@code edition_libre}, le seul à savoir qui a posé
 * la toile, le seul dont un client modifié ne puisse pas changer l'avis. Si le client ouvrait
 * l'écran de son propre chef, un joueur sans droits verrait une interface complète, choisirait une
 * image, attendrait son téléchargement, puis serait refusé à l'envoi. <b>Le refus doit arriver avant
 * le travail, pas après.</b>
 *
 * <p>Le paquet porte l'état actuel de la toile pour que l'écran s'ouvre déjà rempli : on voit ce
 * qu'il y a avant de décider ce qu'on met.
 *
 * @param entityId l'identifiant réseau de la toile.
 * @param hash l'image actuelle, ou une chaîne vide si la toile est vierge.
 * @param width largeur actuelle en blocs.
 * @param height hauteur actuelle en blocs.
 * @param trim l'encadrement actuel, par son identifiant. Voir {@link Trim}.
 */
public record PaintingOpen(int entityId, String hash, int width, int height, int trim)
        implements CustomPacketPayload {

    public static final Type<PaintingOpen> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "tableau_ouvre"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PaintingOpen> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PaintingOpen::entityId,
                    ByteBufCodecs.stringUtf8(Plate.HASH_LENGTH), PaintingOpen::hash,
                    ByteBufCodecs.VAR_INT, PaintingOpen::width,
                    ByteBufCodecs.VAR_INT, PaintingOpen::height,
                    ByteBufCodecs.VAR_INT, PaintingOpen::trim,
                    PaintingOpen::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
