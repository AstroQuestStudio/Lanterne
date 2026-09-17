package fr.clubcitrouille.lanterne.content.waypoint;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * La lecture d'un cadre d'obsidienne, et du cœur qu'on y a glissé.
 *
 * <h2>Pourquoi ne pas se servir de {@code PortalShape} de vanilla</h2>
 *
 * <p>{@code PortalShape} sait dire <em>si</em> un cadre est valide, et rien d'autre : ses champs sont
 * privés, sans accesseur. On ne peut lui demander ni le coin bas-gauche, ni la largeur, ni la hauteur
 * — c'est-à-dire exactement les trois choses dont un portail de repère a besoin pour savoir où déposer
 * un voyageur et quels blocs lui appartiennent.
 *
 * <p>La mesure est donc refaite ici, avec <b>les mêmes règles</b> que vanilla : deux à vingt-et-un de
 * large, trois à vingt-et-un de haut, et un cadre reconnu par {@code isPortalFrame} — l'extension de
 * NeoForge que {@code PortalShape} consulte lui aussi. Un cadre accepté par ce fichier est donc un
 * cadre que vanilla aurait accepté, et réciproquement. C'est ce qui garantit qu'un joueur n'a pas deux
 * géométries à apprendre.
 *
 * <h2>Le cœur peut remplacer n'importe quel bloc du cadre, et un seul</h2>
 *
 * <p>Imposer une position — le bas à gauche, disons — obligerait quiconque possède déjà un portail du
 * Nether à le casser et à le rebâtir plutôt qu'à échanger un bloc. Et le cadre étant symétrique,
 * n'importe quelle position imposée serait arbitraire : il n'y a pas de « devant » sur un rectangle.
 *
 * <p>Ce qui est imposé, en revanche, c'est l'<b>unicité</b> : un cœur, pas deux. Deux cœurs voudraient
 * dire deux identités pour une seule porte, donc deux destinations pour un seul pas — il n'y a aucune
 * réponse raisonnable à cette question, alors on refuse la question.
 *
 * <p>La place la plus naturelle reste le bas du cadre : c'est la rangée qu'on pose en premier, elle est
 * à hauteur de main, et le cœur y reste visible quand le portail est allumé. Rien ne l'y force, mais
 * c'est là que la construction le mène.
 *
 * <h2>Les quatre coins ne comptent pas</h2>
 *
 * <p>Un portail du Nether n'a pas besoin de ses coins ; l'algorithme de vanilla ne les regarde jamais.
 * Un cœur posé dans un coin ne serait donc pas dans le cadre : il serait <em>à côté</em>. Ce fichier ne
 * le trouve pas, et l'allumage le dit en toutes lettres plutôt que de laisser le joueur croire à une
 * panne.
 *
 * @param axis   l'axe horizontal du cadre
 * @param min    le coin de coordonnées les plus basses de l'intérieur
 * @param max    le coin de coordonnées les plus hautes de l'intérieur
 * @param heart  la position du Cœur de repère trouvé dans le cadre
 * @param inside toutes les cases intérieures, dans l'ordre où on les remplira
 */
public record Frame(Direction.Axis axis, BlockPos min, BlockPos max, BlockPos heart,
                    List<BlockPos> inside) {

    private static final int MIN_WIDTH = 2;
    private static final int MAX_WIDTH = 21;
    private static final int MIN_HEIGHT = 3;
    private static final int MAX_HEIGHT = 21;

    /**
     * La raison pour laquelle un cadre a été refusé.
     *
     * <p>Un allumage qui échoue sans rien dire est la pire des pannes : le joueur a dépensé dix blocs
     * d'obsidienne et un cœur, et il ne sait pas ce qui manque. Chaque refus porte donc une phrase.
     */
    public enum Refusal {
        NO_FRAME("Pas de cadre de portail complet ici. Le cœur doit remplacer un bloc du cadre "
                + "lui-même — les quatre coins n'en font pas partie."),
        NO_HEART("Ce cadre n'a pas de Cœur de repère. Remplace un de ses blocs par un cœur."),
        TWO_HEARTS("Ce cadre porte plusieurs cœurs. Un portail n'a qu'une identité : n'en garde qu'un.");

        public final String said;

        Refusal(String said) {
            this.said = said;
        }
    }

    /** Ce qu'une lecture de cadre rend : un cadre, ou la raison de son refus. */
    public record Reading(Frame frame, Refusal refusal) {
        public static Reading of(Frame frame) {
            return new Reading(frame, null);
        }

        public static Reading no(Refusal refusal) {
            return new Reading(null, refusal);
        }

        public boolean ok() {
            return frame != null;
        }
    }

    /**
     * Cherche un cadre autour d'un cœur, en essayant les deux axes et les six voisins.
     *
     * <p>Le cœur est <em>dans</em> le cadre, pas dedans : il faut donc partir d'une case intérieure, et
     * l'on ne sait pas laquelle. Six voisins, deux axes : douze essais de quelques dizaines de lectures
     * de blocs, pour un geste que le joueur fait une fois dans la vie de son portail.
     */
    public static Reading aroundHeart(BlockGetter level, BlockPos heart) {
        Refusal worst = Refusal.NO_FRAME;
        for (Direction side : Direction.values()) {
            BlockPos start = heart.relative(side);
            if (!isHole(level.getBlockState(start))) {
                continue;
            }
            for (Direction.Axis axis : new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z}) {
                Reading reading = read(level, start, axis);
                if (reading.ok() && reading.frame().heart().equals(heart)) {
                    return reading;
                }
                if (reading.refusal() == Refusal.TWO_HEARTS) {
                    worst = Refusal.TWO_HEARTS;
                } else if (reading.refusal() == Refusal.NO_HEART && worst == Refusal.NO_FRAME) {
                    worst = Refusal.NO_HEART;
                }
            }
        }
        return Reading.no(worst);
    }

    /**
     * Cherche un cadre depuis une case intérieure, en essayant l'axe donné puis l'autre.
     *
     * <p>C'est l'ordre de {@code PortalShape.findPortalShape} : on privilégie un axe, on se rabat sur
     * le second. Reprendre l'ordre de vanilla évite qu'un même cadre soit lu différemment selon que le
     * feu vienne d'un briquet ou de nous.
     */
    public static Reading fromInside(BlockGetter level, BlockPos start, Direction.Axis preferred) {
        Reading first = read(level, start, preferred);
        if (first.ok()) {
            return first;
        }
        Direction.Axis other = preferred == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        Reading second = read(level, start, other);
        return second.ok() || first.refusal() == Refusal.NO_FRAME ? second : first;
    }

    private static Reading read(BlockGetter level, BlockPos start, Direction.Axis axis) {
        // Même convention que vanilla : sur l'axe X le cadre se lit vers l'ouest, sur l'axe Z vers le
        // sud. L'important n'est pas le sens choisi mais qu'il soit le même partout.
        Direction right = axis == Direction.Axis.X ? Direction.WEST : Direction.SOUTH;
        Direction left = right.getOpposite();

        BlockPos floor = start;
        int drop = 0;
        while (drop++ <= MAX_HEIGHT && isHole(level.getBlockState(floor.below()))) {
            floor = floor.below();
        }
        if (!isFrame(level, floor.below())) {
            return Reading.no(Refusal.NO_FRAME);
        }

        BlockPos bottomLeft = floor;
        int steps = 0;
        while (steps++ <= MAX_WIDTH && isHole(level.getBlockState(bottomLeft.relative(left)))
                && isFrame(level, bottomLeft.relative(left).below())) {
            bottomLeft = bottomLeft.relative(left);
        }
        if (!isFrame(level, bottomLeft.relative(left))) {
            return Reading.no(Refusal.NO_FRAME);
        }

        int width = 0;
        while (width <= MAX_WIDTH && isHole(level.getBlockState(bottomLeft.relative(right, width)))
                && isFrame(level, bottomLeft.relative(right, width).below())) {
            width++;
        }
        if (width < MIN_WIDTH || width > MAX_WIDTH
                || !isFrame(level, bottomLeft.relative(right, width))) {
            return Reading.no(Refusal.NO_FRAME);
        }

        int height = 0;
        while (height < MAX_HEIGHT && rowIsHollow(level, bottomLeft, right, width, height)) {
            height++;
        }
        if (height < MIN_HEIGHT) {
            return Reading.no(Refusal.NO_FRAME);
        }
        for (int i = 0; i < width; i++) {
            if (!isFrame(level, bottomLeft.above(height).relative(right, i))) {
                return Reading.no(Refusal.NO_FRAME);
            }
        }

        BlockPos heart = null;
        for (BlockPos ring : ring(bottomLeft, right, width, height)) {
            if (level.getBlockState(ring).is(Gates.HEART.get())) {
                if (heart != null) {
                    return Reading.no(Refusal.TWO_HEARTS);
                }
                heart = ring.immutable();
            }
        }
        if (heart == null) {
            return Reading.no(Refusal.NO_HEART);
        }

        List<BlockPos> inside = new ArrayList<>(width * height);
        for (int row = 0; row < height; row++) {
            for (int i = 0; i < width; i++) {
                inside.add(bottomLeft.above(row).relative(right, i).immutable());
            }
        }
        BlockPos far = bottomLeft.above(height - 1).relative(right, width - 1);
        BlockPos min = new BlockPos(
                Math.min(bottomLeft.getX(), far.getX()),
                Math.min(bottomLeft.getY(), far.getY()),
                Math.min(bottomLeft.getZ(), far.getZ()));
        BlockPos max = new BlockPos(
                Math.max(bottomLeft.getX(), far.getX()),
                Math.max(bottomLeft.getY(), far.getY()),
                Math.max(bottomLeft.getZ(), far.getZ()));
        return Reading.of(new Frame(axis, min, max, heart, List.copyOf(inside)));
    }

    /** Une rangée est creuse si ses deux montants tiennent et que rien ne la bouche. */
    private static boolean rowIsHollow(BlockGetter level, BlockPos bottomLeft, Direction right,
                                       int width, int row) {
        BlockPos base = bottomLeft.above(row);
        if (!isFrame(level, base.relative(right, -1)) || !isFrame(level, base.relative(right, width))) {
            return false;
        }
        for (int i = 0; i < width; i++) {
            if (!isHole(level.getBlockState(base.relative(right, i)))) {
                return false;
            }
        }
        return true;
    }

    /** Les blocs du cadre proprement dit : deux montants, un linteau, un seuil. Sans les coins. */
    private static List<BlockPos> ring(BlockPos bottomLeft, Direction right, int width, int height) {
        List<BlockPos> ring = new ArrayList<>(2 * width + 2 * height);
        for (int i = 0; i < width; i++) {
            ring.add(bottomLeft.relative(right, i).below());
            ring.add(bottomLeft.above(height).relative(right, i));
        }
        for (int row = 0; row < height; row++) {
            ring.add(bottomLeft.above(row).relative(right, -1));
            ring.add(bottomLeft.above(row).relative(right, width));
        }
        return ring;
    }

    /**
     * Ce qui peut occuper l'intérieur d'un cadre éteint ou allumé.
     *
     * <p>Le feu en fait partie : c'est lui qui vient d'être posé quand on allume au briquet, et le
     * refuser reviendrait à ne jamais reconnaître un cadre au moment précis où on le lit. Le portail du
     * Nether aussi, pour qu'un joueur puisse convertir un portail existant en y glissant un cœur.
     */
    private static boolean isHole(BlockState state) {
        return state.isAir() || state.is(BlockTags.FIRE) || state.is(Blocks.NETHER_PORTAL)
                || state.is(Gates.GATE.get());
    }

    /**
     * Même test que {@code PortalShape.FRAME} en 26.3 : le cadre se reconnaît désormais au tag
     * {@code minecraft:nether_portal_frame} — voir {@code Heart} et le tag de bloc qui y ajoute le
     * cœur de repère — et non plus à une méthode {@code isPortalFrame} par bloc, qui n'existe plus.
     */
    private static boolean isFrame(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.NETHER_PORTAL_FRAME);
    }
}
