package fr.clubcitrouille.lanterne.content.screen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Le calage de l'image : où elle se pose, quelle taille elle fait, dans quel sens.
 *
 * <h2>Pourquoi c'est un enregistrement à part et pas quatre champs de plus</h2>
 *
 * <p>Une raison de fond et une raison mécanique.
 *
 * <p><b>De fond</b> : ces quatre nombres décrivent la même chose — la façon dont l'image se pose sur
 * ce qu'on lui offre. Le volume et le verrou n'ont rien à voir avec eux. Les grouper fait qu'on les
 * lit ensemble, qu'on les remet à zéro ensemble, et qu'on les explique une fois.
 *
 * <p><b>Mécanique</b> : {@code StreamCodec.composite} s'arrête à douze champs. {@link Feed} en avait
 * neuf ; quatre de plus faisaient treize, et le protocole aurait cessé de compiler. Un enregistrement
 * imbriqué porte son propre codec et ne compte que pour un.
 *
 * <h2>Des seizièmes de bloc, et pourquoi pas des blocs</h2>
 *
 * <p>Un décalage réglable au bloc entier est trop grossier : sur un projecteur à huit blocs, un cran
 * d'un bloc fait sauter l'image d'un huitième de sa largeur. Réglable au centième, il devient
 * impossible à poser à la main.
 *
 * <p>Le seizième de bloc est l'unité que Minecraft emploie partout — c'est le pixel d'une texture de
 * bloc, la graduation d'un modèle, le pas d'une hitbox. Un joueur qui a déjà posé une dalle ou taillé
 * un escalier l'a dans l'œil sans savoir son nom. L'écran de réglages, lui, affiche des blocs avec une
 * décimale : on règle au seizième et on lit en blocs.
 *
 * <h2>La rotation est par quarts de tour, et c'est délibéré</h2>
 *
 * <p>Une rotation libre demanderait de faire tourner les quatre sommets autour du centre, ce qui est
 * facile, et de décider ce que devient le cadrage d'une image penchée dans un rectangle, ce qui ne
 * l'est pas : une image à trente degrés dans un mur rectangulaire laisse quatre coins vides dont
 * aucun cadrage n'a de définition.
 *
 * <p>Les quarts de tour n'ont pas ce problème — un quart de tour échange simplement largeur et
 * hauteur — et ils couvrent ce dont on a besoin : redresser un projecteur monté au plafond, ou
 * retourner une image filmée de travers.
 */
public record Sight(int shiftX, int shiftY, int zoom, int spin) {
    /** Seizièmes de bloc dans un bloc. L'unité de {@link #shiftX} et {@link #shiftY}. */
    public static final int PER_BLOCK = 16;

    /** Décalage maximal, en seizièmes : trente-deux blocs, la portée d'un projecteur. */
    public static final int SHIFT_CAP = 32 * PER_BLOCK;

    /** Centré, à sa taille, à l'endroit. */
    public static final Sight PLUMB = new Sight(0, 0, 100, 0);

    public static final Codec<Sight> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("decalage_x").forGetter(Sight::shiftX),
            Codec.INT.fieldOf("decalage_y").forGetter(Sight::shiftY),
            Codec.INT.fieldOf("zoom").forGetter(Sight::zoom),
            Codec.INT.fieldOf("rotation").forGetter(Sight::spin)
    ).apply(instance, Sight::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, Sight> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, Sight::shiftX,
                    ByteBufCodecs.VAR_INT, Sight::shiftY,
                    ByteBufCodecs.VAR_INT, Sight::zoom,
                    ByteBufCodecs.VAR_INT, Sight::spin,
                    Sight::new);

    /**
     * Les mêmes valeurs, ramenées dans leurs bornes.
     *
     * <p>Comme {@code Feed.sane}, et pour la même raison : ces nombres arrivent d'un pli, donc d'un
     * client, donc de quelqu'un en qui on n'a aucune raison d'avoir confiance. Un zoom de deux
     * milliards produit une géométrie que la carte graphique refuse ; un décalage de deux milliards
     * envoie l'image hors du monde.
     */
    public Sight sane() {
        return new Sight(
                Math.clamp(this.shiftX, -SHIFT_CAP, SHIFT_CAP),
                Math.clamp(this.shiftY, -SHIFT_CAP, SHIFT_CAP),
                Math.clamp(this.zoom, 10, 400),
                // Ramené au quart de tour le plus proche, et pas seulement borné : une valeur
                // intermédiaire venue d'un client modifié donnerait une image penchée dans un cadre
                // droit, c'est-à-dire le cas qu'on a écarté par conception.
                ((Math.floorMod(this.spin, 360) + 45) / 90 * 90) % 360);
    }

    /** Le décalage horizontal en blocs, pour le rendu. */
    public float blocksX() {
        return this.shiftX / (float) PER_BLOCK;
    }

    /** Le décalage vertical en blocs. */
    public float blocksY() {
        return this.shiftY / (float) PER_BLOCK;
    }

    /** Le facteur d'agrandissement, de 0,1 à 4. */
    public float scale() {
        return this.zoom / 100f;
    }

    /** Un quart de tour échange largeur et hauteur. */
    public boolean quarterTurned() {
        return this.spin == 90 || this.spin == 270;
    }

    public Sight withShiftX(int value) {
        return new Sight(value, this.shiftY, this.zoom, this.spin).sane();
    }

    public Sight withShiftY(int value) {
        return new Sight(this.shiftX, value, this.zoom, this.spin).sane();
    }

    public Sight withZoom(int value) {
        return new Sight(this.shiftX, this.shiftY, value, this.spin).sane();
    }

    /** Le quart de tour suivant. */
    public Sight turned() {
        return new Sight(this.shiftX, this.shiftY, this.zoom, (this.spin + 90) % 360);
    }

    /** Tout remettre d'aplomb. Un bouton, parce qu'on se perd vite à quatre réglages. */
    public Sight plumb() {
        return PLUMB;
    }

    /** « −1,5 bl » — ce qu'on écrit à côté d'une barre de réglage. */
    public static String blocks(int sixteenths) {
        return String.format(java.util.Locale.ROOT, "%.2f bl", sixteenths / (float) PER_BLOCK)
                .replace('.', ',');
    }
}
