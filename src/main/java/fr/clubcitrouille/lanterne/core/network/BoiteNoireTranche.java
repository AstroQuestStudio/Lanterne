package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Une tranche d'un rapport de plantage, du client vers le serveur. Même forme, pour les mêmes
 * raisons, que {@code MecheChunk} — voir sa Javadoc pour le détail du découpage. Le sens est
 * simplement inversé : c'est le client qui pousse ici, jamais le serveur qui tire.
 *
 * @param nomFichier le nom du fichier d'origine (crash-*.txt ou hs_err_pid*.log), tronqué au besoin —
 *         sert uniquement à choisir le bon gabarit de résumé côté client ; le nom écrit côté serveur
 *         est reconstruit à partir du joueur et de l'horodatage, jamais de ce champ.
 * @param offset la position de cette tranche dans le texte envoyé, en octets.
 * @param total la taille totale du texte envoyé, connue dès la première tranche — voir
 *         {@link #MAX_TOTAL_BYTES} pour le plafond que le serveur revérifie de son côté.
 * @param data la tranche d'octets (le texte, déjà tronqué et anonymisé côté client).
 */
public record BoiteNoireTranche(String nomFichier, int offset, int total, byte[] data)
        implements CustomPacketPayload {

    /** Taille maximale d'une tranche — plus petite que celle de la mèche : on veut étaler l'envoi. */
    public static final int MAX_CHUNK_BYTES = 8192;

    /** Longueur maximale du nom de fichier annoncé. */
    public static final int NOM_LIMIT = 64;

    /**
     * Plafond du texte entier d'un rapport — environ 48 Ko, la borne demandée pour un extrait
     * « tronqué intelligemment ». Le serveur revérifie ce plafond : voir
     * {@code core.BoiteNoirePlantages}, un client modifié n'a aucune raison de faire confiance.
     */
    public static final int MAX_TOTAL_BYTES = 49_152;

    public static final Type<BoiteNoireTranche> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "boite_noire_tranche"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BoiteNoireTranche> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(NOM_LIMIT), BoiteNoireTranche::nomFichier,
                    ByteBufCodecs.VAR_INT, BoiteNoireTranche::offset,
                    ByteBufCodecs.VAR_INT, BoiteNoireTranche::total,
                    ByteBufCodecs.byteArray(MAX_CHUNK_BYTES), BoiteNoireTranche::data,
                    BoiteNoireTranche::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
