package fr.clubcitrouille.lanterne.content.screen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Le mur : plusieurs blocs d'écran qui n'en font qu'un.
 *
 * <h2>Pourquoi un multibloc, alors qu'un bloc redimensionnable serait plus simple</h2>
 *
 * <p>Un bloc unique avec deux curseurs « largeur » et « hauteur » existe, se code en une heure, et
 * est mauvais. Il pousse une surface à travers des blocs qui ne lui appartiennent pas : elle traverse
 * les murs, elle ignore les fenêtres, et rien dans le monde ne dit où elle s'arrête tant qu'on ne
 * l'allume pas. On règle alors un écran dans un menu, au lieu de le bâtir.
 *
 * <p>Le multibloc dit l'inverse : <b>l'écran a la taille qu'on lui a maçonnée</b>. On en pose seize, on
 * obtient un mur de quatre sur quatre ; on en casse un, il se recompose tout seul en ce qui reste. Il
 * n'y a rien à régler, et surtout rien à comprendre — c'est la géométrie qu'on voit qui fait foi.
 *
 * <h2>Le rectangle plein, et le choix qu'il impose</h2>
 *
 * <p>Un mur est toujours un <b>rectangle entièrement rempli</b>. Une forme en L, une croix, un mur
 * troué au milieu ne forment pas un écran : ils se décomposent en plusieurs rectangles, et chacun
 * devient un écran à part.
 *
 * <p>C'est une contrainte, et c'est aussi ce qui rend le rendu trivial — quatre sommets pour toute la
 * surface, une seule texture, un seul lot de dessin, quel que soit le nombre de blocs. Une forme
 * quelconque aurait demandé un découpage par bloc et autant de fois plus de travail à la carte
 * graphique, pour un usage que personne n'a demandé : un écran de cinéma est rectangulaire.
 *
 * <h2>Tout défaire, puis tout refaire</h2>
 *
 * <p>La recomposition ne cherche pas à corriger l'assemblage existant : elle le <b>jette</b> et le
 * refait depuis les blocs présents. C'est plus lent, et c'est la seule méthode qui ne puisse pas
 * dériver.
 *
 * <p>La correction sur place demanderait de répondre à « que devient un mur de six sur six dont on
 * retire le bloc central ? » par une règle, et cette règle serait fausse dans un cas sur dix. En
 * refaisant tout, la réponse tombe d'elle-même : quatre rectangles, parce que c'est ce que les blocs
 * restants permettent.
 *
 * <p>Le coût est borné par {@link Booth#wallCap()} et n'est payé qu'au marteau — poser ou casser un
 * bloc, pas une fois par tick. Un mur qui joue ne coûte rien à ce fichier.
 */
public final class Wall {
    /**
     * Garde-fou du parcours, en blocs visités.
     *
     * <p>Quatre fois le plafond d'un mur : le parcours voit des blocs qui ne formeront pas de mur —
     * une file de panneaux en L, un alignement de panneaux solitaires — et s'arrêter au plafond du
     * mur les laisserait à moitié traités. Il faut de la marge pour que la recomposition soit
     * <em>complète</em> même quand elle ne produit rien.
     */
    private static int sweepCap() {
        return Math.min(4096, Booth.wallCap() * 4);
    }

    private Wall() {}

    /**
     * L'horizontale du mur, orientée vers la <b>droite du spectateur</b>.
     *
     * <h2>Pourquoi ce sens-là, et pas l'autre</h2>
     *
     * <p>{@code FACING} désigne le côté par lequel on regarde l'écran — un panneau posé face à soi
     * porte la direction où l'on se tient. Le spectateur regarde donc dans le sens opposé, et sa main
     * droite pointe vers {@code getCounterClockWise()}.
     *
     * <p>Le choix n'est pas neutre. L'assemblage se moquerait du sens ; le <b>rendu</b>, lui, ne s'en
     * moque pas : c'est le même axe qui sert à ranger les blocs et à faire croître la coordonnée de
     * texture. Les prendre en sens contraire donnerait une image en miroir, et une image en miroir
     * est un défaut qu'on ne voit pas tant qu'aucun texte n'apparaît dedans.
     *
     * <p>La verticale, elle, est toujours celle du monde. Un écran couché au plafond n'existe pas, et
     * c'est voulu : il aurait demandé une seconde convention d'axes pour un usage que personne ne
     * réclame.
     */
    public static Direction across(Direction facing) {
        return facing.getCounterClockWise();
    }

    /** La coordonnée d'un bloc le long de l'horizontale du mur. */
    public static int along(BlockPos pos, Direction facing) {
        Direction axis = across(facing);
        return axis.getAxis() == Direction.Axis.X
                ? pos.getX() * axis.getAxisDirection().getStep()
                : pos.getZ() * axis.getAxisDirection().getStep();
    }

    /**
     * Recompose tous les murs touchés par un changement en {@code pos}.
     *
     * <p>Appelée à la pose et au retrait d'un panneau. Le bloc en {@code pos} peut très bien ne plus
     * exister : c'est le cas au retrait, et c'est pourquoi la graine du parcours est le <b>voisinage</b>
     * et non la position elle-même.
     */
    public static void reform(LevelAccessor level, BlockPos pos, Direction facing) {
        Direction axis = across(facing);
        Direction[] steps = {axis, axis.getOpposite(), Direction.UP, Direction.DOWN};

        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> panels = new ArrayList<>();
        Deque<BlockPos> queue = new ArrayDeque<>();

        // La graine est le VOISINAGE, et pas seulement la position. Au retrait, le bloc a déjà
        // disparu quand on arrive ici : partir de lui seul ne trouverait rien, et le mur qu'il
        // laisse derrière lui resterait assemblé autour d'un trou.
        for (BlockPos start : new BlockPos[] {pos, pos.relative(axis), pos.relative(axis.getOpposite()),
                pos.above(), pos.below()}) {
            if (faces(level, start, facing) && seen.add(start)) {
                queue.add(start);
            }
        }

        int cap = sweepCap();
        while (!queue.isEmpty() && panels.size() < cap) {
            BlockPos at = queue.poll();
            panels.add(at);
            for (Direction step : steps) {
                BlockPos next = at.relative(step);
                if (faces(level, next, facing) && seen.add(next)) {
                    queue.add(next);
                }
            }
        }

        if (panels.isEmpty()) {
            return;
        }

        // Tout défaire d'abord. Assigner au fur et à mesure ferait dépendre le résultat de l'ordre
        // de découverte du parcours, c'est-à-dire du hasard des positions — deux joueurs posant le
        // même mur dans un ordre différent n'obtiendraient pas le même découpage.
        for (BlockPos panel : panels) {
            if (level.getBlockEntity(panel) instanceof PanelEntity entity) {
                entity.orphan();
            }
        }

        // Du coin bas-gauche vers le haut-droit : le découpage devient une fonction des blocs
        // présents, et de rien d'autre.
        panels.sort((left, right) -> {
            int byRow = Integer.compare(left.getY(), right.getY());
            return byRow != 0 ? byRow : Integer.compare(along(left, facing), along(right, facing));
        });

        Set<BlockPos> taken = new HashSet<>();
        Set<BlockPos> available = new HashSet<>(panels);
        for (BlockPos seed : panels) {
            if (taken.contains(seed)) {
                continue;
            }
            claim(level, seed, facing, available, taken);
        }
    }

    /**
     * Étend le plus grand rectangle plein possible depuis ce coin, et l'attribue.
     *
     * <p>On croît d'abord en largeur, puis en hauteur, et l'on s'arrête dès qu'une rangée entière
     * n'est pas disponible. Croître en largeur d'abord n'est pas neutre : à surface égale, un écran
     * large ressemble à un écran, un écran haut ressemble à une colonne. Quand les deux sont
     * possibles, on choisit celui que le joueur voulait.
     */
    private static void claim(LevelAccessor level, BlockPos seed, Direction facing,
            Set<BlockPos> available, Set<BlockPos> taken) {
        Direction axis = across(facing);
        int cap = Booth.wallCap();

        int width = 1;
        while (width < cap && available.contains(seed.relative(axis, width))
                && !taken.contains(seed.relative(axis, width))) {
            width++;
        }

        int height = 1;
        while ((width * (height + 1)) <= cap && rowFree(seed, axis, width, height, available, taken)) {
            height++;
        }

        PanelEntity head = level.getBlockEntity(seed) instanceof PanelEntity found ? found : null;
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                BlockPos at = seed.above(row).relative(axis, column);
                taken.add(at);
                if (!(level.getBlockEntity(at) instanceof PanelEntity entity)) {
                    continue;
                }
                // Un mur qui absorbe un écran qui jouait ne doit pas le faire taire. Le coin
                // bas-gauche n'est pas forcément celui qui portait la séance : sans cette reprise,
                // agrandir un écran d'un bloc vers la gauche l'éteindrait, ce que personne ne
                // rattacherait jamais au bloc qu'il vient de poser.
                if (head != null && entity != head) {
                    head.inherit(entity);
                }
                entity.joinWall(seed, width, height);
            }
        }
    }

    private static boolean rowFree(BlockPos seed, Direction axis, int width, int row,
            Set<BlockPos> available, Set<BlockPos> taken) {
        for (int column = 0; column < width; column++) {
            BlockPos at = seed.above(row).relative(axis, column);
            if (!available.contains(at) || taken.contains(at)) {
                return false;
            }
        }
        return true;
    }

    /** Y a-t-il un panneau ici, tourné dans ce sens ? */
    private static boolean faces(LevelAccessor level, BlockPos pos, Direction facing) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof Panel && state.getValue(Panel.FACING) == facing;
    }

    /**
     * Le panneau maître d'un panneau quelconque, ou {@code null}.
     *
     * <p>Un esclave n'a ni source, ni horloge, ni réglages : il renvoie à son maître pour tout. C'est
     * ce qui fait qu'un clic sur n'importe quel bloc d'un mur de seize ouvre le <b>même</b> écran de
     * réglages, ce que tout le monde attend et que personne ne pense à demander.
     */
    public static PanelEntity master(Level level, PanelEntity panel) {
        BlockPos head = panel.wallOrigin();
        if (head.equals(panel.getBlockPos())) {
            return panel;
        }
        return level.getBlockEntity(head) instanceof PanelEntity found ? found : null;
    }
}
