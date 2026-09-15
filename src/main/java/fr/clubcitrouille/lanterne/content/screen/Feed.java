package fr.clubcitrouille.lanterne.content.screen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * La source, et tout ce qu'on peut régler autour d'elle.
 *
 * <h2>Pourquoi les réglages voyagent avec la source, et non à côté</h2>
 *
 * <p>Le volume, la portée, la luminosité et le cadrage ne décrivent pas un bloc : ils décrivent
 * <b>une séance</b>. Deux écrans qui montrent la même chose n'ont aucune raison de la montrer au même
 * volume, et le même écran qui change de source a toutes les raisons de garder les siens.
 *
 * <p>Les tenir ensemble a une conséquence pratique qui vaut la peine d'être dite : un seul pli les
 * transporte, une seule vérification les valide, et il n'existe aucun instant où un client connaît la
 * source sans connaître le volume auquel la jouer. Les états intermédiaires sont la principale source
 * de bizarreries dans ce genre de mécanisme, et la meilleure façon de les traiter est de ne pas en
 * avoir.
 *
 * <h2>Ce qui n'est PAS ici</h2>
 *
 * <p>Ni la position de lecture, ni la pause : voir {@link Clock}. La distinction n'est pas
 * décorative. Les réglages changent rarement et à la main ; l'horloge change à chaque geste de
 * lecture et se lit soixante fois par seconde. Les mêler aurait fait renvoyer tout le paquet de
 * réglages à chaque pause.
 *
 * <p>Ni le propriétaire : il appartient au bloc, pas à la séance, et il ne doit pas pouvoir changer
 * en même temps qu'un réglage — c'est très exactement la faille qu'on éviterait mal autrement.
 */
public record Feed(
        String source,
        boolean loop,
        boolean autoplay,
        int volume,
        int range,
        int brightness,
        Shape shape,
        Lock lock) {

    /** Longueur maximale d'une source. Voir {@link Sieve} pour ce qui est accepté dedans. */
    public static final int SOURCE_LIMIT = 512;

    /**
     * Comment l'image occupe la surface qu'on lui donne.
     *
     * <p>Un mur de blocs a la forme qu'on lui a bâtie ; une vidéo a celle qu'elle a. Les deux
     * coïncident rarement, et il n'existe pas de bonne réponse unique — seulement trois mauvaises
     * réponses dont on choisit celle qui dérange le moins.
     */
    public enum Shape implements StringRepresentable {
        /** L'image est déformée pour remplir. Le moins cher, le plus laid. */
        ETIRE("etire", "Étiré"),
        /** L'image entière, avec des bandes. Rien n'est perdu, rien n'est déformé. */
        ENTIER("entier", "Entier"),
        /** La surface entière, image rognée. Un mur de cinéma sans bande noire. */
        REMPLI("rempli", "Rempli");

        public static final Codec<Shape> CODEC = StringRepresentable.fromEnum(Shape::values);
        public static final StreamCodec<io.netty.buffer.ByteBuf, Shape> STREAM_CODEC =
                ByteBufCodecs.idMapper(index -> values()[index], Shape::ordinal);

        private final String name;
        private final String label;

        Shape(String name, String label) {
            this.name = name;
            this.label = label;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        public String label() {
            return this.label;
        }

        public Shape next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /**
     * Qui a le droit de changer la source.
     *
     * <h2>Un écran public est une tribune</h2>
     *
     * <p>Sur un serveur, un écran posé dans un lieu de passage montre ce qu'on lui donne à tout le
     * monde qui passe. La question « qui décide de ce qui s'affiche » n'est donc pas un réglage de
     * confort : c'est la même question que « qui peut écrire sur ce panneau », et elle a déjà fait
     * fermer des serveurs.
     *
     * <p>Le réglage par défaut est le plus restrictif. Un écran s'ouvre par un geste délibéré de son
     * poseur, jamais par omission.
     */
    public enum Lock implements StringRepresentable {
        /** Le poseur, et les opérateurs. Le défaut. */
        POSEUR("poseur", "Le poseur"),
        /** Les opérateurs seuls — même le poseur ne touche plus à rien. Pour un écran d'annonce. */
        OPERATEUR("operateur", "Les opérateurs"),
        /** N'importe qui. À poser en connaissance de cause. */
        TOUS("tous", "Tout le monde");

        public static final Codec<Lock> CODEC = StringRepresentable.fromEnum(Lock::values);
        public static final StreamCodec<io.netty.buffer.ByteBuf, Lock> STREAM_CODEC =
                ByteBufCodecs.idMapper(index -> values()[index], Lock::ordinal);

        private final String name;
        private final String label;

        Lock(String name, String label) {
            this.name = name;
            this.label = label;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        public String label() {
            return this.label;
        }

        public Lock next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Portée sonore maximale, en blocs. Au-delà, un écran s'entendrait d'un autre quartier. */
    public static final int RANGE_CAP = 64;

    /** Un écran neuf : muet de source, à mi-volume, réservé à son poseur. */
    public static final Feed BLANK =
            new Feed("", false, false, 70, 16, 15, Shape.ENTIER, Lock.POSEUR);

    public static final Codec<Feed> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("source").forGetter(Feed::source),
            Codec.BOOL.fieldOf("boucle").forGetter(Feed::loop),
            Codec.BOOL.fieldOf("auto").forGetter(Feed::autoplay),
            Codec.INT.fieldOf("volume").forGetter(Feed::volume),
            Codec.INT.fieldOf("portee").forGetter(Feed::range),
            Codec.INT.fieldOf("luminosite").forGetter(Feed::brightness),
            Shape.CODEC.fieldOf("cadrage").forGetter(Feed::shape),
            Lock.CODEC.fieldOf("verrou").forGetter(Feed::lock)
    ).apply(instance, Feed::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, Feed> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(SOURCE_LIMIT), Feed::source,
                    ByteBufCodecs.BOOL, Feed::loop,
                    ByteBufCodecs.BOOL, Feed::autoplay,
                    ByteBufCodecs.VAR_INT, Feed::volume,
                    ByteBufCodecs.VAR_INT, Feed::range,
                    ByteBufCodecs.VAR_INT, Feed::brightness,
                    Shape.STREAM_CODEC, Feed::shape,
                    Lock.STREAM_CODEC, Feed::lock,
                    Feed::new);

    /**
     * Les mêmes réglages, ramenés dans leurs bornes.
     *
     * <h2>À appliquer sur ce qui vient d'un client, sans exception</h2>
     *
     * <p>Un enregistrement construit depuis un pli porte les nombres que l'émetteur a bien voulu
     * écrire. Un volume de deux milliards fait tourner le moteur sonore dans le vide ; une portée de
     * deux milliards fait jouer un écran pour le serveur entier ; une source de quatre mébioctets
     * remplit le disque de celui qui la sauvegarde.
     *
     * <p>Le codec de flux borne déjà la <em>longueur</em> de la source, parce que c'est lui qui alloue
     * le tableau. Il ne borne rien d'autre : les nombres passent tels quels, et c'est ici qu'ils
     * s'arrêtent.
     */
    public Feed sane() {
        String cleaned = this.source == null ? "" : this.source.trim();
        if (cleaned.length() > SOURCE_LIMIT) {
            cleaned = cleaned.substring(0, SOURCE_LIMIT);
        }
        return new Feed(
                cleaned,
                this.loop,
                this.autoplay,
                Math.clamp(this.volume, 0, 100),
                Math.clamp(this.range, 1, RANGE_CAP),
                Math.clamp(this.brightness, 0, 15),
                this.shape == null ? Shape.ENTIER : this.shape,
                this.lock == null ? Lock.POSEUR : this.lock);
    }

    /** Y a-t-il seulement quelque chose à montrer ? */
    public boolean idle() {
        return this.source == null || this.source.isBlank();
    }

    public Feed withSource(String value) {
        return new Feed(value, this.loop, this.autoplay, this.volume, this.range, this.brightness,
                this.shape, this.lock).sane();
    }

    /**
     * Le facteur de volume à appliquer, une fois la distance prise en compte.
     *
     * <p>Une décroissance linéaire jusqu'à la portée, et rien au-delà. Le moteur sonore de vanilla
     * décroît en carré inverse, ce qui est juste physiquement et mauvais ici : un écran de cinéma
     * s'entend du fond de la salle, et une décroissance physique le rendrait inaudible à dix blocs
     * pour être assourdissant à un.
     *
     * @param distance distance du joueur au centre de l'écran, en blocs
     */
    public float loudness(double distance) {
        if (this.volume <= 0 || distance >= this.range) {
            return 0f;
        }
        return (float) (this.volume / 100d * (1d - distance / this.range));
    }
}
