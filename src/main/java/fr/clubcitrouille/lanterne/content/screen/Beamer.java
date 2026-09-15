package fr.clubcitrouille.lanterne.content.screen;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

import org.jspecify.annotations.Nullable;

/**
 * Le projecteur : une image posée à distance, et un cône pour dire d'où elle vient.
 *
 * <h2>Pourquoi les deux blocs, et pas seulement l'écran</h2>
 *
 * <p>Un mur d'écran se bâtit dans la matière : il occupe seize blocs, il faut les avoir, et l'image
 * s'arrête au bord du mur. C'est parfait dans une salle qu'on construit pour ça, et impraticable
 * partout ailleurs — on ne maçonne pas un mur de vingt-cinq blocs pour montrer quelque chose deux
 * minutes.
 *
 * <p>Le projecteur est l'inverse exact : un seul bloc, et l'image est <em>immatérielle</em>. Elle
 * flotte à la distance qu'on veut, de la taille qu'on veut, sans rien occuper. Elle ne s'arrête à
 * aucun mur — parce qu'elle n'en a pas.
 *
 * <p>Les deux sont donc complémentaires plutôt que redondants, et le choix se fait sur une question
 * simple : est-ce que je veux que cet écran soit un bâtiment ?
 *
 * <h2>Le cône est la moitié du bloc</h2>
 *
 * <p>Une image qui flotte sans rien qui l'explique est un objet inquiétant : on ne sait pas d'où elle
 * vient, ni ce qu'il faut casser pour l'éteindre. Le faisceau visible répond aux deux questions d'un
 * coup d'œil, et il est la seule chose qui rattache l'image à un bloc qu'on puisse toucher.
 *
 * <p>Il s'éteint quand l'écran s'éteint, ce qui donne au bloc l'état allumé / éteint que tout le
 * monde attend d'une machine. Voir {@link #LIT}.
 */
public class Beamer extends BaseEntityBlock {
    public static final MapCodec<Beamer> CODEC = simpleCodec(Beamer::new);

    /**
     * Les six directions, et non les quatre horizontales.
     *
     * <p>Un projecteur au plafond qui vise le sol est l'usage le plus évident qui soit, et un
     * projecteur qui ne saurait viser qu'à l'horizontale l'interdirait sans raison. L'écran, lui,
     * reste horizontal — voir {@link Wall#across}, où la contrainte a une cause et non une habitude.
     */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    /** Le faisceau est-il allumé ? Change la lumière émise et le modèle. */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public Beamer(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LIT, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    /**
     * Il vise là où l'on regarde.
     *
     * <p>{@code getNearestLookingDirection} rend l'axe du regard, et {@code getOpposite} le retourne
     * vers l'avant : on pose le bloc <em>devant soi</em> et il projette dans le sens où l'on marchait.
     * L'inverse — projeter vers soi — est ce que ferait un four, et un four ne projette rien.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getNearestLookingDirection().getOpposite())
                .setValue(LIT, false);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BeamerEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by,
            ItemStack stack) {
        super.setPlacedBy(level, pos, state, by, stack);
        if (!level.isClientSide() && by instanceof Player player
                && level.getBlockEntity(pos) instanceof BeamerEntity beamer) {
            beamer.claim(player);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof BeamerEntity beamer)
                || !(player instanceof ServerPlayer server)) {
            return InteractionResult.PASS;
        }
        Screens.openConsole(server, beamer);
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
