package fr.clubcitrouille.lanterne.core.network;

import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Les trois charges utiles de la Mèche en PHASE DE CONFIGURATION — voir {@code core.MecheConfig} pour
 * le mécanisme, et pourquoi il faut passer avant la synchronisation des registres de NeoForge.
 *
 * <p>Codecs sur {@link FriendlyByteBuf} et non {@code RegistryFriendlyByteBuf} : en configuration,
 * les registres du client ne sont pas encore synchronisés — c'est précisément ce qui va suivre —, et
 * NeoForge n'accepte pour ce protocole que des codecs qui n'en dépendent pas.
 *
 * <h2>Un format qui ne doit jamais casser</h2>
 *
 * <p>Même règle que {@link MecheManifest} : l'annonce est le seul message qui doit rester lisible
 * par un client EN RETARD. Toute évolution se fait par un canal NOUVEAU (autre identifiant), jamais
 * en changeant celui-ci.
 */
public final class MecheConfigPaquets {
    /** Version des trois canaux. Optionnels : un client qui ne les connaît pas n'est jamais refusé. */
    public static final String PROTOCOLE = "1";

    /** Plafond d'une tranche : sous le seuil de découpage de NeoForge, bien sous le Mo vanilla. */
    public static final int TRANCHE_MAX = 32768;

    /** Plafond du nombre de clés annoncées — large marge au-dessus de la cinquantaine actuelle. */
    public static final int CLES_MAX = 4096;

    private MecheConfigPaquets() {}

    /**
     * « Voici le jar que je fais tourner. » Serveur vers client, au début de la configuration.
     *
     * @param version la version du jar du serveur (affichage seul — l'empreinte décide).
     * @param sha256 l'empreinte SHA-256 du jar du serveur.
     * @param taille sa taille en octets.
     * @param cles les clés de registre synchronisées de l'espace {@code lanterne}, sous la forme
     *     {@code registre clé} : le client y lit s'il pourra entrer SANS mise à jour.
     */
    public record Annonce(String version, byte[] sha256, int taille, List<String> cles)
            implements CustomPacketPayload {
        public static final Type<Annonce> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(Lanterne.ID, "meche_config_annonce"));
        public static final StreamCodec<FriendlyByteBuf, Annonce> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(MecheManifest.VERSION_LIMIT), Annonce::version,
                ByteBufCodecs.byteArray(MecheManifest.HASH_LENGTH), Annonce::sha256,
                ByteBufCodecs.VAR_INT, Annonce::taille,
                ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list(CLES_MAX)), Annonce::cles,
                Annonce::new);

        @Override
        public Type<Annonce> type() {
            return TYPE;
        }
    }

    /**
     * Réponse du client. {@link #CONTINUER} : la connexion peut suivre son cours ; {@link #DEMANDE} :
     * envoie-moi le jar. Un client qui ne peut ni entrer ni se mettre à jour se déconnecte lui-même
     * avec un message clair — il n'y a pas de code pour ça.
     */
    public record Reponse(int code) implements CustomPacketPayload {
        public static final int CONTINUER = 0;
        public static final int DEMANDE = 1;

        public static final Type<Reponse> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(Lanterne.ID, "meche_config_reponse"));
        public static final StreamCodec<FriendlyByteBuf, Reponse> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Reponse::code, Reponse::new);

        @Override
        public Type<Reponse> type() {
            return TYPE;
        }
    }

    /** Une tranche du jar, serveur vers client, pendant la configuration. */
    public record Tranche(int offset, int taille, byte[] data) implements CustomPacketPayload {
        public static final Type<Tranche> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(Lanterne.ID, "meche_config_tranche"));
        public static final StreamCodec<FriendlyByteBuf, Tranche> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Tranche::offset,
                ByteBufCodecs.VAR_INT, Tranche::taille,
                ByteBufCodecs.byteArray(TRANCHE_MAX), Tranche::data,
                Tranche::new);

        @Override
        public Type<Tranche> type() {
            return TYPE;
        }
    }
}
