package fr.clubcitrouille.lanterne.content.disc;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La poste : tous les plis que le graveur échange, au même endroit.
 *
 * <h2>Pourquoi un seul fichier, contrairement aux tableaux</h2>
 *
 * <p>Les paquets des tableaux ont chacun leur fichier, parce que chacun porte une <b>décision</b> qui
 * mérite d'être expliquée longuement : pourquoi le catalogue part seul, pourquoi la demande ne porte
 * aucun pixel, pourquoi un décalage plutôt qu'un numéro de segment.
 *
 * <p>Ceux-ci n'en portent qu'une, commune aux sept : ce sont les mêmes mécanismes, dans le même ordre,
 * pour de l'audio au lieu d'images. Sept fichiers auraient répété sept fois la même explication en
 * renvoyant à celle des tableaux. Un seul les rassemble, avec une explication par pli, et le lecteur
 * voit le protocole entier d'un coup d'œil.
 *
 * <h2>Le protocole, dans l'ordre où il se déroule</h2>
 *
 * <ol>
 *   <li>Le joueur clique sur le graveur → le serveur vérifie, puis {@link Open}.</li>
 *   <li>Le joueur choisit un morceau → le client télécharge, convertit, calcule l'empreinte → {@link Burn}.</li>
 *   <li>Le serveur ne connaît pas ce morceau → {@link Want}, et le client monte les octets par
 *       {@link Gift}.</li>
 *   <li>La gravure finie, le serveur annonce son catalogue de sillons à tout le monde par
 *       {@link Roll} ; ceux qui n'ont pas les octets les réclament par {@link Wish} et les reçoivent
 *       par {@link Shard}.</li>
 * </ol>
 *
 * <p>Deux règles gouvernent tout : <b>on ne transmet que ce que l'autre n'a pas</b>, et <b>un serveur
 * ne croit jamais un client</b>. Elles sont les mêmes que pour les tableaux, et pour les mêmes
 * raisons.
 */
public final class Post {
    private Post() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Lanterne.ID, path);
    }

    /**
     * Un sillon occupé, tel que le client le connaît.
     *
     * <p>Pas d'octets ici — un titre, une durée, une empreinte. Le catalogue entier de soixante-quatre
     * sillons tient dans quelques kilo-octets, alors qu'un seul morceau en pèse des milliers.
     *
     * @param slot le numéro du sillon, qui est aussi son nom de registre.
     * @param hash l'empreinte du morceau, qui est le nom de son fichier dans le cache.
     * @param title le titre lisible, celui que porte l'objet.
     * @param seconds la durée réelle du morceau — non pas celle que le sillon déclare.
     */
    public record Vinyl(int slot, String hash, String title, float seconds) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Vinyl> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Vinyl::slot,
                        ByteBufCodecs.stringUtf8(32), Vinyl::hash,
                        ByteBufCodecs.stringUtf8(Wax.NAME_LIMIT), Vinyl::title,
                        ByteBufCodecs.FLOAT, Vinyl::seconds,
                        Vinyl::new);
    }

    /**
     * « Ouvre le graveur. »
     *
     * <p>Émis par le serveur après avoir vérifié qu'un disque vierge est bien dedans et que ce joueur
     * peut s'en servir. Comme pour les tableaux, l'écran n'est jamais ouvert de l'initiative du
     * client : un refus doit arriver avant le travail, pas après le téléchargement.
     */
    public record Open(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(id("graveur_ouvre"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> STREAM_CODEC =
                StreamCodec.composite(BlockPos.STREAM_CODEC, Open::pos, Open::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * « Grave ce morceau ici. »
     *
     * <p>Aucun octet, comme pour les tableaux : le serveur possède peut-être déjà ce morceau — deux
     * joueurs qui gravent la même musique ne la transmettent qu'une fois.
     *
     * <p>La durée vient du client, qui l'a lue dans les entêtes du fichier. Le serveur ne la croit
     * pas sur parole : il la relit lui-même sur les octets reçus avant de choisir un sillon. Elle
     * n'est ici que pour que le serveur sache <em>quel</em> sillon réserver avant même l'envoi, et
     * puisse refuser tout de suite s'il n'en a plus d'assez long.
     */
    public record Burn(BlockPos pos, String title, String hash, int size, float seconds)
            implements CustomPacketPayload {
        public static final Type<Burn> TYPE = new Type<>(id("graveur_grave"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Burn> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Burn::pos,
                        ByteBufCodecs.stringUtf8(Wax.NAME_LIMIT), Burn::title,
                        ByteBufCodecs.stringUtf8(32), Burn::hash,
                        ByteBufCodecs.VAR_INT, Burn::size,
                        ByteBufCodecs.FLOAT, Burn::seconds,
                        Burn::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** « Je n'ai pas ce morceau — envoie-le. » Du serveur vers celui qui grave. */
    public record Want(String hash) implements CustomPacketPayload {
        public static final Type<Want> TYPE = new Type<>(id("disque_reclame"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Want> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.stringUtf8(32), Want::hash, Want::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Une tranche de morceau qui monte vers le serveur. Vérifiée à l'arrivée par son empreinte. */
    public record Gift(String hash, int offset, int size, byte[] data) implements CustomPacketPayload {
        public static final Type<Gift> TYPE = new Type<>(id("disque_don"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Gift> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(32), Gift::hash,
                        ByteBufCodecs.VAR_INT, Gift::offset,
                        ByteBufCodecs.VAR_INT, Gift::size,
                        ByteBufCodecs.byteArray(32768), Gift::data,
                        Gift::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Le catalogue des sillons occupés, annoncé à la connexion et après chaque gravure. */
    public record Roll(List<Vinyl> cuts) implements CustomPacketPayload {
        public static final Type<Roll> TYPE = new Type<>(id("disques_role"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Roll> STREAM_CODEC =
                StreamCodec.composite(
                        Vinyl.STREAM_CODEC.apply(ByteBufCodecs.list(512)), Roll::cuts, Roll::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** « Je n'ai pas ce morceau. » Du client vers le serveur, après le catalogue. */
    public record Wish(String hash) implements CustomPacketPayload {
        public static final Type<Wish> TYPE = new Type<>(id("disque_voeu"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Wish> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.stringUtf8(32), Wish::hash, Wish::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Une tranche de morceau qui descend vers un client. */
    public record Shard(String hash, int offset, int size, byte[] data) implements CustomPacketPayload {
        public static final Type<Shard> TYPE = new Type<>(id("disque_eclat"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Shard> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(32), Shard::hash,
                        ByteBufCodecs.VAR_INT, Shard::offset,
                        ByteBufCodecs.VAR_INT, Shard::size,
                        ByteBufCodecs.byteArray(32768), Shard::data,
                        Shard::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Graver un lien YouTube. Aucun octet de musique ne voyage : le disque porte l'identifiant de la
     * vidéo, et chaque client la joue dans le lecteur intégré officiel (voir {@code client.screen.Juke}).
     * La durée et le titre viennent de la sonde du client ; le serveur borne la durée comme pour un
     * fichier, et c'est elle qui choisit le sillon.
     */
    public record Tube(BlockPos pos, String videoId, String title, float seconds)
            implements CustomPacketPayload {
        public static final Type<Tube> TYPE = new Type<>(id("graveur_youtube"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Tube> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Tube::pos,
                        ByteBufCodecs.stringUtf8(16), Tube::videoId,
                        ByteBufCodecs.stringUtf8(Wax.NAME_LIMIT), Tube::title,
                        ByteBufCodecs.FLOAT, Tube::seconds,
                        Tube::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Où en est le morceau d'un jukebox, pour un joueur qui le découvre en cours de lecture. Envoyé
     * juste avant l'évènement 1010 que rejoue {@link Retrack}, pour les seuls disques YouTube : un
     * fichier vanilla repart du début quoi qu'on fasse, un lecteur intégré sait reprendre au milieu.
     */
    public record Tempo(BlockPos pos, long ticks) implements CustomPacketPayload {
        public static final Type<Tempo> TYPE = new Type<>(id("disque_tempo"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Tempo> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Tempo::pos,
                        ByteBufCodecs.VAR_LONG, Tempo::ticks,
                        Tempo::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Le préfixe d'empreinte d'un disque YouTube : {@code yt-<identifiant de vidéo>}. */
    public static final String TUBE_PREFIX = "yt-";

    /** Ce sillon porte-t-il un disque YouTube ? */
    public static boolean tube(String hash) {
        return hash != null && hash.startsWith(TUBE_PREFIX);
    }

    /** Un identifiant de vidéo YouTube plausible : onze caractères de l'alphabet d'URL. */
    public static boolean plausibleVideoId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{11}");
    }
}
