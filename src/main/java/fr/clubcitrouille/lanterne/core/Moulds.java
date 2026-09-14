package fr.clubcitrouille.lanterne.core;

import java.util.List;

import it.unimi.dsi.fastutil.booleans.BooleanArrays;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Les moules : des milliers d'états de blocs qui gardent chacun sa copie de la même forme.
 *
 * <h2>Le poste</h2>
 *
 * <p>Chaque état de bloc qui n'est pas marqué « opacité variable » met en cache sa forme de
 * collision et son tableau de faces porteuses. Un bloc simple a deux états ; un bloc à six
 * propriétés booléennes en a soixante-quatre — et une clôture, un mur ou un tuyau de mod en ont
 * plusieurs centaines. Tous ces états ont, le plus souvent, <b>exactement la même forme</b>.
 *
 * <p>Vanilla n'en sait rien : chaque état construit la sienne et la garde. Sur un modpack fourni,
 * le mod dont ce module s'inspire chiffre l'ensemble à environ deux cents mégaoctets.
 *
 * <h2>Ce qui est fait, et ce qui ne l'est pas</h2>
 *
 * <p>On garde une forme canonique par contenu, et l'on remplace les autres par elle. Deux états qui
 * décrivent le même volume finissent par pointer le même objet.
 *
 * <p>Le mod d'origine va plus loin : il remplace aussi les <em>entrailles</em> des formes, pour
 * rattraper les mods qui gardent leur propre cache. C'est plus efficace et bien plus risqué — cela
 * suppose qu'aucune forme ne soit jamais modifiée après sa création, ce qui est vrai de vanilla mais
 * qu'aucun mod ne promet. Ici, on ne fait que remplacer des références.
 *
 * <h2>Pourquoi comparer par boîtes et non par structure interne</h2>
 *
 * <p>Comparer deux {@code VoxelShape} par leur représentation interne demande de connaître chacune
 * de leurs implémentations — tableau, tranche, ensemble de bits — et de les comparer champ à champ.
 * Le mod d'origine y consacre cinq classes.
 *
 * <p>{@code toAabbs()} rend la liste des boîtes que la forme occupe. Deux formes de même liste
 * décrivent le même volume, quelle que soit leur implémentation — c'est une propriété plus forte
 * que l'égalité structurelle, et elle tient en une ligne. Le prix est une liste construite par état
 * <b>au chargement</b>, jamais en jeu.
 *
 * <p>Repris dans l'esprit de <b>FerriteCore</b> (malte0811, MIT). Voir {@code NOTICE.md}.
 */
public final class Moulds {
    /**
     * Les formes canoniques, indexées par elles-mêmes.
     *
     * <h2>Une déduplication qui gardait ses clés n'économisait rien</h2>
     *
     * <p>La première version employait une table ordinaire dont la clé était la liste de boîtes de
     * la forme. Le banc a été catégorique : <b>10,4 Mo de mémoire retenue en PLUS</b> avec le
     * module qu'avec vanilla. Les listes de boîtes, conservées à vie comme clés, pesaient plus
     * lourd que les formes qu'on avait fusionnées.
     *
     * <p>Un ensemble à stratégie de hachage n'a pas de clé séparée : il range les formes elles-mêmes
     * et leur applique une fonction de comparaison extérieure. Rien n'est retenu qui n'existait
     * déjà. Le prix est que la liste de boîtes est recalculée à chaque comparaison — mais cela
     * n'arrive qu'au chargement des registres, jamais en jeu.
     */
    private static final ObjectOpenCustomHashSet<VoxelShape> SHAPES =
            new ObjectOpenCustomHashSet<>(new it.unimi.dsi.fastutil.Hash.Strategy<VoxelShape>() {
                @Override
                public int hashCode(VoxelShape shape) {
                    return shape == null ? 0 : shape.toAabbs().hashCode();
                }

                @Override
                public boolean equals(VoxelShape left, VoxelShape right) {
                    if (left == right) {
                        return true;
                    }
                    return left != null && right != null && left.toAabbs().equals(right.toAabbs());
                }
            });

    /** Les tableaux de faces canoniques, comparés par contenu sans clé intermédiaire. */
    private static final ObjectOpenCustomHashSet<boolean[]> FACES =
            new ObjectOpenCustomHashSet<>(BooleanArrays.HASH_STRATEGY);

    private static int shapesShared;

    private static int facesShared;

    private Moulds() {}

    /**
     * Rend la forme canonique équivalente à celle-ci.
     *
     * <p>Appelée à la construction du cache d'un état, donc une fois par état et par chargement.
     */
    public static VoxelShape canonical(VoxelShape shape) {
        if (shape == null || shape.isEmpty()) {
            return shape;
        }
        // Une forme très découpée n'a aucune chance d'avoir une jumelle, et la comparer coûterait
        // plus que la mémoire qu'on espère lui reprendre.
        List<AABB> volume = shape.toAabbs();
        if (volume.size() > 64) {
            return shape;
        }
        VoxelShape known = SHAPES.addOrGet(shape);
        if (known != shape) {
            shapesShared++;
        }
        return known;
    }

    /** Rend le tableau canonique équivalent à celui-ci. */
    public static boolean[] canonical(boolean[] faces) {
        if (faces == null) {
            return null;
        }
        boolean[] known = FACES.addOrGet(faces);
        if (known != faces) {
            facesShared++;
        }
        return known;
    }

    /** Formes partagées au lieu d'être dupliquées. */
    public static int shapesShared() {
        return shapesShared;
    }

    /** Tableaux de faces partagés. */
    public static int facesShared() {
        return facesShared;
    }

    /** Formes distinctes retenues — le dénominateur du rapport. */
    public static int distinct() {
        return SHAPES.size() + FACES.size();
    }

    public static void reset() {
        SHAPES.clear();
        FACES.clear();
        shapesShared = 0;
        facesShared = 0;
    }

    /**
     * Libère les tables de travail.
     *
     * <p>Elles ne servent qu'à reconnaître les doublons <em>pendant</em> la construction des états.
     * Une fois tous les blocs enregistrés, elles ne retiennent plus que des références déjà tenues
     * ailleurs — et leur propre structure, qui peut peser plusieurs mégaoctets. Les vider est donc
     * une économie nette, et sans conséquence : plus aucune forme ne sera construite.
     */
    public static void seal() {
        int shapes = SHAPES.size();
        int faces = FACES.size();
        SHAPES.clear();
        SHAPES.trim();
        FACES.clear();
        FACES.trim();
        fr.clubcitrouille.lanterne.Lanterne.LOG.info(
                "[MOULES] {} forme(s) et {} motif(s) de faces partagés ; {} + {} distincts retenus, "
                + "tables libérées.", shapesShared, facesShared, shapes, faces);
    }
}
