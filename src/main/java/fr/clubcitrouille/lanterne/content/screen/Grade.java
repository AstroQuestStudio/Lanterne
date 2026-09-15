package fr.clubcitrouille.lanterne.content.screen;

import com.mojang.serialization.Codec;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * La qualité : le seul réglage dont le coût soit une loi du carré.
 *
 * <h2>Pourquoi c'est le levier principal, et de loin</h2>
 *
 * <p>Tous les autres réglages de dépense sont linéaires. Diviser la cadence par deux divise le
 * travail par deux ; fermer un écran sur quatre l'allège d'un quart. La <b>résolution</b>, elle,
 * compte en pixels, et les pixels vont comme le carré de la hauteur.
 *
 * <pre>
 * 1080p -> 1920 × 1080 = 2 073 600 pixels par image
 *  720p -> 1280 ×  720 =   921 600   (2,25 fois moins)
 *  360p ->  640 ×  360 =   230 400   (9 fois moins)
 * </pre>
 *
 * <p>Passer de 1080p à 360p ne fait pas gagner « un peu » : il fait gagner un facteur <b>neuf</b>, sur
 * le décodage, sur la conversion de couleur et sur le téléversement à la fois. Aucun autre réglage
 * de ce mod n'offre cela, et c'est pourquoi celui-ci existe.
 *
 * <h2>Deux plafonds, et le plus bas gagne</h2>
 *
 * <p>Un écran porte le sien — c'est le choix de celui qui l'a posé, et il vaut pour tout le monde.
 * Chaque client porte le sien aussi, dans {@code client.screen.Consent}. <b>C'est le minimum des deux
 * qui s'applique.</b>
 *
 * <p>La raison est qu'ils répondent à deux questions différentes. L'écran dit « de quelle qualité ce
 * mur mérite-t-il d'être » ; le client dit « qu'est-ce que ma machine encaisse ». Un joueur sur un
 * portable de 2015 doit pouvoir plafonner à 360p sans que l'administrateur ait à trancher pour lui,
 * et sans dégrader ce que voient les autres.
 *
 * <h2>Le mode adaptatif, et ce qu'il regarde</h2>
 *
 * <p>{@link #AUTO} n'est pas « la meilleure qualité possible » : c'est <b>la qualité que l'œil peut
 * réellement distinguer d'ici</b>. Elle se déduit de la taille apparente du mur — sa largeur en blocs
 * divisée par la distance. Voir {@link #resolve}.
 *
 * <p>Un mur de seize blocs vu de six blocs occupe presque tout l'écran : il mérite le maximum. Le
 * même mur vu de quatre-vingts blocs occupe un dixième de la largeur de l'écran, soit environ deux
 * cents pixels — lui envoyer du 1080p, c'est décoder deux millions de pixels pour en afficher
 * quarante mille. C'est le même raisonnement que celui du recensement dans {@code core.Census} : la
 * précision qu'on perd est de la précision que personne ne peut voir.
 */
public enum Grade implements StringRepresentable {
    /** La qualité suit la taille apparente du mur. Le défaut, et le bon choix dans presque tous les cas. */
    AUTO("auto", "Auto", 0),
    /** 360p — neuf fois moins de pixels que le 1080p. Pour les petites machines et les écrans lointains. */
    BASSE("basse", "360p", 360),
    /** 720p — le compromis habituel. Lisible sur un mur de huit blocs vu de près. */
    MOYENNE("moyenne", "720p", 720),
    /** 1080p — pour un mur de cinéma qu'on regarde assis devant. */
    HAUTE("haute", "1080p", 1080),
    /** Aucun plafond : la résolution du flux, quelle qu'elle soit. À employer en connaissance de cause. */
    SOURCE("source", "Source", Integer.MAX_VALUE);

    public static final Codec<Grade> CODEC = StringRepresentable.fromEnum(Grade::values);
    public static final StreamCodec<io.netty.buffer.ByteBuf, Grade> STREAM_CODEC =
            ByteBufCodecs.idMapper(index -> values()[index], Grade::ordinal);

    private final String name;
    private final String label;
    private final int height;

    Grade(String name, String label, int height) {
        this.name = name;
        this.label = label;
        this.height = height;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    public String label() {
        return this.label;
    }

    /** Hauteur maximale en pixels, ou {@code 0} pour {@link #AUTO}, qui n'en a pas de fixe. */
    public int height() {
        return this.height;
    }

    public Grade next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /**
     * La hauteur de décodage à demander, en pixels.
     *
     * <p>Rend le plus petit de ce que l'écran demande, de ce que ce client tolère, et — en mode
     * automatique — de ce que l'œil peut distinguer à cette distance.
     *
     * @param wallBlocks largeur du mur en blocs, ou l'envergure de l'image projetée
     * @param distance distance du joueur, en blocs
     * @param clientCeiling le plafond de cette machine, jamais {@link #AUTO}
     */
    public int resolve(float wallBlocks, double distance, Grade clientCeiling) {
        int wanted = this == AUTO ? apparent(wallBlocks, distance) : this.height;
        int ceiling = clientCeiling == AUTO ? apparent(wallBlocks, distance) : clientCeiling.height();
        return Math.max(BASSE.height, Math.min(wanted, ceiling));
    }

    /**
     * Ce que l'œil peut distinguer d'ici, en hauteur de pixels.
     *
     * <h2>Le calcul, et pourquoi il est volontairement grossier</h2>
     *
     * <p>Un bloc vu à la distance <i>d</i> occupe un angle proportionnel à {@code 1/d}. Un mur de
     * <i>w</i> blocs occupe donc une fraction de l'écran proportionnelle à {@code w/d}. En prenant
     * un champ de vision ordinaire et une fenêtre de mille pixels de large, un mur qui occupe toute
     * la largeur — {@code w/d} voisin de un — mérite environ mille pixels ; un mur qui en occupe le
     * dixième en mérite cent.
     *
     * <p>On ne cherche pas mieux, et c'est délibéré. Le résultat est immédiatement rabattu sur trois
     * paliers : la seule décision qui compte est « lequel des trois », et une formule exacte
     * — champ de vision réel, résolution réelle, rapport d'image — donnerait la même réponse trois
     * fois sur trois tout en obligeant à relire les options du jeu à chaque image.
     *
     * <p>Les paliers, et non une valeur continue, pour une raison de coût : changer de résolution
     * oblige à réallouer la pellicule et à rouvrir la conversion de couleur. Une valeur continue le
     * ferait à chaque pas du joueur. Trois paliers, avec l'hystérésis que donne leur écartement, le
     * font deux ou trois fois dans une partie.
     */
    private static int apparent(float wallBlocks, double distance) {
        double share = wallBlocks / Math.max(1d, distance);
        if (share >= 0.9d) {
            return HAUTE.height;
        }
        return share >= 0.3d ? MOYENNE.height : BASSE.height;
    }
}
