package fr.clubcitrouille.lanterne.content.painting;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le rôle d'appel : ce que le serveur possède, annoncé au client sans un seul pixel.
 *
 * <h2>Le seul paquet qui parte tout seul</h2>
 *
 * <p>Il est envoyé une fois, à la connexion. Tous les autres échanges de ce paquet sont
 * <b>demandés</b> par le client : c'est lui qui sait ce qu'il a déjà sur son disque, et c'est donc
 * lui qui décide ce qu'il lui manque. Le serveur ne pousse jamais une image de sa propre initiative
 * — c'est la garantie qu'une image connue ne repart jamais deux fois.
 *
 * <p>Une entrée pèse une soixantaine d'octets. Un catalogue de soixante-quatre tableaux tient donc
 * dans un paquet unique de quatre kibioctets, ce qui est sans commune mesure avec le coût d'une
 * seule image.
 *
 * <h2>Il porte aussi la loi du serveur</h2>
 *
 * <p>Depuis que le joueur peut fournir une image sans quitter le jeu, le client a besoin de savoir
 * <b>à quoi il a droit</b> : combien de pixels, combien de blocs, les liens sont-ils permis, quels
 * domaines. Sans cela, l'écran d'édition proposerait un curseur jusqu'à seize blocs sur un serveur
 * qui en refuse plus de quatre, et le joueur découvrirait la limite au moment du refus — après avoir
 * choisi son image et attendu son téléchargement.
 *
 * <p>Ces règles arrivent donc avec le catalogue, dans le même paquet, parce qu'elles changent en
 * même temps que lui et que deux paquets pour une même vérité finissent toujours par se
 * contredire.
 */
public record PaintingRoll(List<Plate> plates, Rules rules) implements CustomPacketPayload {

    public static final Type<PaintingRoll> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, "tableaux_role"));

    /**
     * Ce que le serveur autorise.
     *
     * <h2>Une politique annoncée, et une barrière : ce ne sont pas les mêmes</h2>
     *
     * <p>{@link #maxSide}, {@link #maxBlocks} et {@link #maxSourceKib} sont des <b>barrières</b> : le
     * serveur les vérifie sur les octets qu'il reçoit, et aucun client ne les contourne.
     *
     * <p>{@link #links} et {@link #domains} sont une <b>politique</b>. Le téléchargement a lieu chez
     * le client — c'est indispensable, voir {@code Studio#links()} — donc le serveur ne voit jamais
     * l'adresse et ne peut rien y vérifier. Ce que ces deux champs empêchent, c'est qu'un joueur
     * ordinaire colle une adresse imprévue ; ce qu'ils n'empêchent pas, c'est qu'un client modifié
     * l'ignore. Dire l'un sans l'autre serait mentir sur ce que le mod protège.
     *
     * @param maxSide côté maximal d'une image, en pixels.
     * @param maxBlocks côté maximal d'une toile, en blocs.
     * @param maxSourceKib poids maximal d'un fichier source, en kibioctets.
     * @param links les adresses sont-elles acceptées.
     * @param domains les domaines permis, vide si tous.
     */
    public record Rules(int maxSide, int maxBlocks, int maxSourceKib, boolean links,
            List<String> domains) {

        public static final Rules OPEN = new Rules(1024, 8, 8192, true, List.of());

        public static final StreamCodec<RegistryFriendlyByteBuf, Rules> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Rules::maxSide,
                        ByteBufCodecs.VAR_INT, Rules::maxBlocks,
                        ByteBufCodecs.VAR_INT, Rules::maxSourceKib,
                        ByteBufCodecs.BOOL, Rules::links,
                        ByteBufCodecs.stringUtf8(253).apply(ByteBufCodecs.list(64)), Rules::domains,
                        Rules::new);

        /**
         * Cette adresse est-elle permise ?
         *
         * <p>La comparaison porte sur la <b>fin</b> de l'hôte, précédée d'un point : autoriser
         * {@code imgur.com} autorise {@code i.imgur.com} mais <b>pas</b> {@code imgur.com.piege.net},
         * qui se terminerait pourtant par la même chaîne si l'on comparait sans le point. C'est
         * l'erreur classique de ce genre de liste, et elle annule entièrement la protection.
         */
        public boolean allows(String host) {
            if (this.domains.isEmpty()) {
                return true;
            }
            String lower = host.toLowerCase(java.util.Locale.ROOT);
            for (String domain : this.domains) {
                String allowed = domain.toLowerCase(java.util.Locale.ROOT).trim();
                if (allowed.isEmpty()) {
                    continue;
                }
                if (lower.equals(allowed) || lower.endsWith("." + allowed)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, PaintingRoll> STREAM_CODEC =
            StreamCodec.composite(
                    Plate.STREAM_CODEC.apply(ByteBufCodecs.list(1024)), PaintingRoll::plates,
                    Rules.STREAM_CODEC, PaintingRoll::rules,
                    PaintingRoll::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
