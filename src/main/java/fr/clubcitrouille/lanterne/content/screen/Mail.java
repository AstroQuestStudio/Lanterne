package fr.clubcitrouille.lanterne.content.screen;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Tous les plis de la projection, au même endroit.
 *
 * <h2>Ce que ce protocole ne transporte pas, et qui définit tout le reste</h2>
 *
 * <p><b>Aucun pixel. Aucun octet de vidéo. Aucune position de lecture.</b>
 *
 * <p>C'est la conséquence directe de {@link Clock} : puisque chaque client calcule sa position depuis
 * une ancre, il n'y a rien à lui envoyer tant que personne ne touche à rien. Un serveur où dix écrans
 * jouent en permanence pour trente joueurs échange, par seconde, exactement <b>zéro</b> paquet de
 * projection.
 *
 * <p>La comparaison mérite d'être faite parce qu'elle est écrasante. Un protocole qui diffuserait la
 * position deux fois par seconde enverrait, sur ce même serveur, six cents paquets par seconde pour
 * transporter une information que l'arithmétique donne gratuitement. Et il serait <em>moins</em>
 * précis, parce que chaque paquet arrive en retard d'un temps que personne ne mesure.
 *
 * <h2>Le seul pli qui monte sans qu'on le lui demande</h2>
 *
 * <p>{@link Measure}. Un client qui a ouvert le flux connaît sa durée ; le serveur, qui n'ouvre rien,
 * ne la connaîtra jamais autrement. Elle sert à deux choses et deux seulement : replier la boucle, et
 * dessiner une barre de progression qui ait un bout.
 *
 * <p>C'est donc le seul endroit où le serveur croit un client, et il ne le croit qu'une fois, qu'en
 * l'absence de valeur connue, et qu'entre des bornes. Voir {@code Screens.onMeasure} pour les trois
 * conditions et pourquoi il en faut trois.
 */
public final class Mail {
    private Mail() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Lanterne.ID, path);
    }

    /**
     * « Ouvre les réglages de ce bloc. »
     *
     * <p>Du serveur vers le client, et jamais l'inverse. Le client ne décide pas d'ouvrir un écran
     * dont il n'a pas la clé : le verrou est vérifié <b>avant</b> l'envoi, chez celui qui le détient.
     * C'est la même règle que le graveur et les tableaux, et elle n'a pas d'exception.
     *
     * @param pos la position du bloc — pour un mur, celle du maître.
     */
    public record Open(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(id("ecran_ouvre"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> STREAM_CODEC =
                StreamCodec.composite(BlockPos.STREAM_CODEC, Open::pos, Open::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * « Voici les réglages que je veux. »
     *
     * <p>Tous ensemble, y compris ceux qui n'ont pas changé. Un pli par réglage aurait été plus
     * économe de quelques octets et aurait ouvert la porte à un état mi-ancien mi-neuf : une source
     * changée avec le volume d'avant, ou l'inverse. À ce prix-là, l'économie n'en est pas une.
     */
    public record Set(BlockPos pos, Feed feed) implements CustomPacketPayload {
        public static final Type<Set> TYPE = new Type<>(id("ecran_regle"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Set> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Set::pos,
                        Feed.STREAM_CODEC, Set::feed,
                        Set::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Ce qu'un joueur peut demander à une séance. */
    public enum Verb {
        LIRE,
        PAUSE,
        CURSEUR,
        ARRET;

        public static final StreamCodec<io.netty.buffer.ByteBuf, Verb> STREAM_CODEC =
                ByteBufCodecs.idMapper(index -> values()[index], Verb::ordinal);
    }

    /**
     * « Lis », « pause », « va à », « arrête ».
     *
     * <p>Le nombre ne sert qu'au curseur, et il est en millisecondes. Les trois autres verbes
     * l'ignorent — plutôt que quatre plis dont trois seraient vides, un seul avec un champ parfois
     * inutilisé. Quatre octets valent mieux que trois types à enregistrer, à router et à relire.
     */
    public record Command(BlockPos pos, Verb verb, long millis) implements CustomPacketPayload {
        public static final Type<Command> TYPE = new Type<>(id("ecran_commande"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Command> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Command::pos,
                        Verb.STREAM_CODEC, Command::verb,
                        ByteBufCodecs.VAR_LONG, Command::millis,
                        Command::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** « Ce flux dure tant. » Voir l'en-tête : le seul pli où le serveur croit un client. */
    public record Measure(BlockPos pos, long millis) implements CustomPacketPayload {
        public static final Type<Measure> TYPE = new Type<>(id("ecran_duree"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Measure> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Measure::pos,
                        ByteBufCodecs.VAR_LONG, Measure::millis,
                        Measure::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** « Vise plus loin », « vise plus grand ». Le projecteur seul : un mur a la taille qu'on l'a bâti. */
    public record Aim(BlockPos pos, int reach, int span) implements CustomPacketPayload {
        public static final Type<Aim> TYPE = new Type<>(id("projecteur_vise"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Aim> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Aim::pos,
                        ByteBufCodecs.VAR_INT, Aim::reach,
                        ByteBufCodecs.VAR_INT, Aim::span,
                        Aim::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Les règles du serveur, annoncées à l'arrivée.
     *
     * <h2>Pourquoi le client reçoit la liste blanche, alors que le serveur l'applique déjà</h2>
     *
     * <p>Deux raisons, et la seconde est la vraie.
     *
     * <p><b>Pour le dire.</b> Un joueur à qui l'on refuse une source doit lire <em>pourquoi</em> et
     * <em>ce qui passerait</em>, dans l'écran où il vient de la taper. Lui répondre « refusé » sans
     * la liste, c'est le condamner à essayer au hasard.
     *
     * <p><b>Pour ne pas avoir à faire confiance.</b> Un client qui s'apprête à aller chercher une
     * adresse expose son IP à cette adresse. Il n'a aucune raison de faire ce geste sur la seule foi
     * d'un serveur, qui est précisément la partie qu'il ne contrôle pas. Il revérifie donc, avec le
     * même {@link Sieve}, avant toute connexion. Un serveur malveillant ne peut alors envoyer ses
     * joueurs que là où il a lui-même annoncé qu'il les enverrait — et un joueur qui lit la liste le
     * voit venir.
     */
    public record Rules(List<String> domains, boolean filtering, boolean active,
            int rangeCap, int throwCap) implements CustomPacketPayload {
        public static final Type<Rules> TYPE = new Type<>(id("ecran_regles"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Rules> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(253).apply(ByteBufCodecs.list(512)), Rules::domains,
                        ByteBufCodecs.BOOL, Rules::filtering,
                        ByteBufCodecs.BOOL, Rules::active,
                        ByteBufCodecs.VAR_INT, Rules::rangeCap,
                        ByteBufCodecs.VAR_INT, Rules::throwCap,
                        Rules::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
