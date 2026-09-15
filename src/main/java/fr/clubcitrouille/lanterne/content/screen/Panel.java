package fr.clubcitrouille.lanterne.content.screen;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

import org.jspecify.annotations.Nullable;

/**
 * Le bloc d'écran : un morceau de surface, qui n'a de sens qu'avec ses voisins.
 *
 * <h2>Un bloc plein, et non un panneau mince</h2>
 *
 * <p>La première idée est un panneau collé au mur, comme un tableau. Elle est mauvaise pour une raison
 * qui ne se voit qu'à l'usage : un mur d'écran se bâtit, et bâtir demande de pouvoir <b>poser un bloc
 * contre le précédent</b>. Un panneau mince n'offre pas de face sur laquelle poser le suivant ; on se
 * retrouve à maçonner un mur de pierre derrière, à poser les panneaux dessus, puis à devoir casser la
 * pierre — ou à la garder.
 *
 * <p>Un cube plein s'empile comme n'importe quel bloc. On en pose seize comme on pose seize blocs de
 * pierre, et le mur est fait. C'est aussi ce qui permet de tenir au plafond, en porte-à-faux, et de
 * servir de mur porteur : un écran est un bâtiment, pas une décoration.
 *
 * <h2>Ce qui se devine sans documentation</h2>
 *
 * <p>Il se pose face à soi, comme un four. Il porte une face visiblement différente des cinq autres,
 * donc on sait de quel côté regarder avant même de l'allumer. Il s'assemble tout seul dès qu'on en
 * pose deux côte à côte, et rien ne demande de valider l'assemblage. On clique dessus pour le régler,
 * comme sur toute machine.
 *
 * <p>Et il ne se pose <b>pas</b> orienté vers le haut ou vers le bas, alors que rien ne l'interdirait
 * techniquement. Voir {@link Wall#across} : un écran horizontal aurait demandé une seconde convention
 * d'axes pour un usage que personne ne réclame, et l'absence d'un choix est parfois le meilleur choix
 * d'interface qu'on puisse faire.
 */
public class Panel extends BaseEntityBlock {
    public static final MapCodec<Panel> CODEC = simpleCodec(Panel::new);

    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public Panel(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PanelEntity(pos, state);
    }

    /**
     * Aucun ticker, et ce n'est pas un oubli.
     *
     * <p>{@code BaseEntityBlock} n'en fournit pas par défaut ; ne pas en redéfinir est donc une
     * décision plutôt qu'une omission, et elle est expliquée en entier dans {@link PanelEntity}. Un
     * mur de deux cent cinquante-six blocs coûte zéro milliseconde par tick au serveur.
     */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        // Sans cela, un BaseEntityBlock est INVISIBLE : sa forme de rendu par défaut délègue tout au
        // bloc-entité, et le nôtre ne dessine que l'image — pas la caisse. Le bloc serait solide,
        // occulterait la lumière, et l'on ne verrait que le décor derrière lui.
        return RenderShape.MODEL;
    }

    /**
     * Pose : on note le poseur, puis on recompose le mur.
     *
     * <p>Dans cet ordre. Recomposer d'abord ferait du bloc neuf le maître d'un mur sans propriétaire,
     * et le verrou « le poseur » n'aurait alors personne à reconnaître — c'est-à-dire qu'il
     * laisserait tout le monde passer, ce qui est exactement l'inverse de ce qu'il promet.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by,
            ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (level.isClientSide()) {
            return;
        }
        if (by instanceof Player player && level.getBlockEntity(pos) instanceof PanelEntity panel) {
            panel.claim(player);
        }
        Wall.reform(level, pos, state.getValue(FACING));
    }

    /**
     * Retrait : le mur se recompose autour du trou.
     *
     * <p>{@code affectNeighborsAfterRemoval} passe <b>après</b> la disparition du bloc, et c'est
     * exactement ce qu'il faut : la recomposition doit voir le monde tel qu'il est, pas tel qu'il
     * était. Voir {@link Wall#reform}, dont la graine est le voisinage pour cette raison précise.
     */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
            boolean movedByPiston) {
        Wall.reform(level, pos, state.getValue(FACING));
    }

    /**
     * Un clic ouvre les réglages — du <b>mur</b>, pas du bloc.
     *
     * <p>L'écran n'est jamais ouvert de l'initiative du client : le serveur vérifie d'abord le
     * verrou, puis envoie l'ordre d'ouvrir. C'est la même règle que le graveur et les tableaux, et
     * elle vaut ici autant qu'ailleurs — un refus doit arriver avant le travail, et un client modifié
     * ne doit pas pouvoir s'ouvrir un écran dont il n'a pas la clé.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof PanelEntity panel)
                || !(player instanceof ServerPlayer server)) {
            return InteractionResult.PASS;
        }
        Screens.openConsole(server, panel);
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
