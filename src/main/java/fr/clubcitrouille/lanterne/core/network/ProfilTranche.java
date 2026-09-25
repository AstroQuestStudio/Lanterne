package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Une tranche d'un rapport de PROFIL DE PERFORMANCE, du client vers le serveur — canal jumeau de
 * {@link BoiteNoireTranche}, même découpage, même raison (voir sa Javadoc), un seul champ de plus
 * ({@link #joueurExigeReponse()} n'existe pas ici : ce paquet ne sert qu'au transport, le consentement
 * est vérifié côté client, AVANT la mise en file — voir {@code client.profil.ProfilClient}).
 *
 * <h2>Pourquoi un canal séparé plutôt qu'un « type de rapport en plus » sur {@code BoiteNoireTranche}</h2>
 *
 * <p>Deux raisons concrètes, pas une préférence esthétique. D'abord la taille : un profil détaillé vise
 * jusqu'à ~200 Ko (voir la demande), très au-dessus du plafond de 48 Ko fixé pour un extrait de
 * plantage déjà tronqué à l'essentiel — relever ce plafond sur {@code BoiteNoireTranche} l'aurait relevé
 * aussi pour les plantages, qui n'en ont pas besoin et dont le nom de fichier reconstruit côté serveur
 * suppose un {@code crash-*.txt} ou {@code hs_err_pid*.log}. Ensuite le consentement : les deux
 * questions sont déjà deux réglages séparés (voir {@code BoiteNoireConsent}) ; leur transport l'est
 * donc aussi, pour qu'un futur changement du format de l'un ne force jamais une renégociation de canal
 * pour l'autre.
 */
public record ProfilTranche(int offset, int total, byte[] data) implements CustomPacketPayload {

    /** Taille maximale d'une tranche — identique à la Boîte noire, l'envoi reste étalé pareil. */
    public static final int MAX_CHUNK_BYTES = 8192;

    /**
     * Plafond du rapport entier — un peu au-dessus des ~200 Ko visés par un rapport JFR détaillé, pour
     * une marge de mise en forme sans se faire retoquer pile au bord. Revérifié côté serveur, voir
     * {@code core.ProfilReception} : un client modifié n'est jamais cru sur parole.
     */
    public static final int MAX_TOTAL_BYTES = 256 * 1024;

    public static final Type<ProfilTranche> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "profil_tranche"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProfilTranche> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ProfilTranche::offset,
                    ByteBufCodecs.VAR_INT, ProfilTranche::total,
                    ByteBufCodecs.byteArray(MAX_CHUNK_BYTES), ProfilTranche::data,
                    ProfilTranche::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
