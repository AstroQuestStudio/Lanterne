package fr.clubcitrouille.lanterne.content.waypoint;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jspecify.annotations.Nullable;

/**
 * La nappe d'un portail de repère : ce qu'on traverse.
 *
 * <h2>Presque rien de neuf, et c'est délibéré</h2>
 *
 * <p>Tout le mécanisme de traversée existe déjà dans vanilla et il est bon : l'interface {@link Portal}
 * demande une destination, {@code PortalProcessor} compte les ticks passés dedans, {@code Entity}
 * s'occupe du délai, de la monture, des passagers et du changement de monde. Réécrire cela aurait
 * produit un second chemin de téléportation à maintenir, qui aurait divergé au premier changement de
 * version.
 *
 * <p>Ce fichier ne fournit donc que trois choses : une forme, un temps d'attente, et la réponse à la
 * question « où mène ce bloc ? ». Le reste est celui du portail du Nether, mot pour mot.
 *
 * <h2>La boucle de retour immédiat, et qui s'en charge</h2>
 *
 * <p>Arriver <em>dans</em> le portail de destination devrait renvoyer aussitôt d'où l'on vient. C'est le
 * compteur de {@code Entity.portalCooldown} qui l'empêche : il est armé juste avant la téléportation, et
 * tant qu'une entité reste dans un portail, {@code setAsInsidePortal} le <b>réarme</b> au lieu de
 * compter. Il faut donc sortir du portail pour que le compteur s'écoule — exactement comme au Nether.
 * Rien à écrire ici, mais tout à comprendre avant de croire qu'il manque quelque chose.
 */
public class GateBlock extends Block implements Portal {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    private static final Map<Direction.Axis, VoxelShape> SHAPES =
            Shapes.rotateHorizontalAxis(Block.column(4.0d, 16.0d, 0.0d, 16.0d));

    /**
     * Une seconde d'attente pour un joueur, et rien pour le reste.
     *
     * <p>Le portail du Nether en impose quatre. C'est le bon chiffre pour un voyage qu'on fait dix fois
     * dans une partie ; c'est une punition pour une porte qu'on franchit dix fois par soirée. Une
     * seconde suffit à ce que reculer d'un pas annule le départ, et c'est la seule raison d'être de ce
     * délai — laisser changer d'avis, pas faire patienter.
     */
    private static final int PLAYER_WAIT = 20;

    public GateBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(AXIS, Direction.Axis.X));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return SHAPES.get(state.getValue(AXIS));
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity,
                                InsideBlockEffectApplier effects, boolean precise) {
        if (entity.canUsePortal(false)) {
            entity.setAsInsidePortal(this, pos);
        }
    }

    @Override
    public int getPortalTransitionTime(ServerLevel level, Entity entity) {
        return entity instanceof Player ? PLAYER_WAIT : 0;
    }

    @Override
    public @Nullable TeleportTransition getPortalDestination(ServerLevel level, Entity entity,
                                                             BlockPos entry) {
        return Gates.destination(level, entity, entry);
    }

    @Override
    public Portal.Transition getLocalTransition() {
        return Portal.Transition.CONFUSION;
    }

    /**
     * Le cadre a bougé : la nappe disparaît si elle n'est plus enfermée.
     *
     * <p>Même logique que {@code NetherPortalBlock}, avec la vérification de {@link Frame} à la place de
     * celle de {@code PortalShape} — il faut en plus qu'un cœur soit encore là, faute de quoi la nappe
     * appartiendrait à un portail dont plus personne ne sait le nom.
     */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks,
                                     BlockPos pos, Direction toNeighbour, BlockPos neighbourPos,
                                     BlockState neighbourState, RandomSource random) {
        Direction.Axis changed = toNeighbour.getAxis();
        Direction.Axis axis = state.getValue(AXIS);
        boolean sideways = axis != changed && changed.isHorizontal();
        if (!sideways && !neighbourState.is(this)
                && !Frame.fromInside(level, pos, axis).ok()) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, toNeighbour, neighbourPos, neighbourState,
                random);
    }

    /**
     * Un bloc de nappe qui s'en va éteint tout le portail.
     *
     * <p>L'appel se répète pour chacun des blocs retirés ; {@link Gates#douse} s'arrête au premier
     * passage — la fiche n'est plus « allumée », donc les appels suivants ne trouvent plus rien. C'est
     * ce qui évite la récursion sans avoir à tenir un drapeau quelque part.
     */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
                                               boolean movedByPiston) {
        Gates.douse(level, pos);
    }

    /** On ne ramasse pas un portail, pas même en créatif. Il n'existe qu'allumé. */
    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state,
                                          boolean includeData) {
        return ItemStack.EMPTY;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return switch (rotation) {
            case COUNTERCLOCKWISE_90, CLOCKWISE_90 -> state.setValue(AXIS,
                    state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
            default -> state;
        };
    }

    /**
     * Le bruit et les grains, côté client seulement.
     *
     * <p>Moins de particules que le portail du Nether, et plus lentes : celui-ci se pose dans une base,
     * souvent par paire, et le nuage permanent de vanilla serait insupportable dans une pièce. La
     * couleur, elle, vient de la texture teintée — voir {@code client.waypoint.Hue}.
     */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(160) == 0) {
            level.playLocalSound(pos.getX() + 0.5d, pos.getY() + 0.5d, pos.getZ() + 0.5d,
                    SoundEvents.PORTAL_AMBIENT, SoundSource.BLOCKS, 0.3F,
                    random.nextFloat() * 0.4F + 0.8F, false);
        }
        if (random.nextInt(3) != 0) {
            return;
        }
        double x = pos.getX() + random.nextDouble();
        double y = pos.getY() + random.nextDouble();
        double z = pos.getZ() + random.nextDouble();
        level.addParticle(ParticleTypes.REVERSE_PORTAL, x, y, z,
                0.0d, random.nextDouble() * 0.2d, 0.0d);
    }
}
