package fr.clubcitrouille.lanterne.content.disc;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

import org.jspecify.annotations.Nullable;

/**
 * Le graveur : le bloc où un disque vierge devient un disque.
 *
 * <h2>Un bloc plutôt qu'une commande</h2>
 *
 * <p>La première version donnait les disques par {@code /disque prendre}. Cela marchait et c'était un
 * geste d'administrateur, pas un geste de joueur — le même reproche que celui fait aux tableaux, et
 * il était juste. Un bloc qu'on fabrique, qu'on pose, qu'on alimente et qui tourne en grinçant
 * pendant qu'il travaille, c'est une <b>machine</b> : elle s'explique toute seule, elle se place dans
 * une base, elle se montre aux autres joueurs.
 *
 * <h2>Pourquoi la gravure prend du temps</h2>
 *
 * <p>Elle pourrait être instantanée — techniquement, une fois les octets reçus, il ne reste qu'à
 * écrire un fichier. Trois raisons de ne pas le faire :
 *
 * <ol>
 *   <li><b>Le travail n'est pas instantané de toute façon.</b> Télécharger, convertir en Vorbis et
 *       remonter plusieurs mébioctets au serveur prend des secondes. Sans rien à l'écran, le joueur
 *       croit à un blocage ; avec un bloc qui tourne, il attend.</li>
 *   <li><b>Un retour d'information gratuit.</b> L'état {@link #WORKING} allume le bloc et fait grincer
 *       le son : on voit de loin qu'une gravure est en cours, et on voit quand elle finit.</li>
 *   <li><b>Une machine instantanée n'a pas de poids.</b> Six secondes ne gênent personne et donnent
 *       à l'objet la valeur qu'il mérite.</li>
 * </ol>
 *
 * <h2>Ce qui arrive quand ça rate</h2>
 *
 * <p>Le disque vierge <b>revient toujours</b>. Lien mort, format refusé, ffmpeg absent, serveur qui
 * n'a plus de sillon libre : dans tous les cas la gravure s'annule et le vierge reste dans le bloc,
 * récupérable au clic. Un objet perdu par un mod est impardonnable, et c'est la seule règle de ce
 * fichier qui n'admet aucune exception — voir {@link BurnerEntity#abandon}.
 */
public class Burner extends BaseEntityBlock {
    public static final MapCodec<Burner> CODEC = simpleCodec(Burner::new);

    /** Vrai pendant la gravure. Sert au rendu, au son et au signal de redstone. */
    public static final BooleanProperty WORKING = BlockStateProperties.LIT;

    /** Vrai quand un disque — vierge ou gravé — attend dans le bloc. */
    public static final BooleanProperty LOADED = BlockStateProperties.HAS_RECORD;

    public Burner(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(WORKING, false)
                .setValue(LOADED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(WORKING, LOADED);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BurnerEntity(pos, state);
    }

    /**
     * Sans cela, le bloc est invisible.
     *
     * <p>La forme de rendu par défaut d'un {@code BaseEntityBlock} est « le bloc-entité s'en
     * charge », et le nôtre ne dessine rien. Le piège avait déjà coûté une séance sur la Lanterne de
     * Veille ; il est noté ici pour qu'il ne la coûte pas une troisième fois.
     */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * Clic avec un objet : on insère un disque vierge, ou on retire ce qu'il y a.
     *
     * <p>{@code TRY_WITH_EMPTY_HAND} rend la main au chemin « sans objet » plutôt que de ne rien
     * faire : c'est ce qui permet de cliquer avec une pioche en main et d'ouvrir quand même l'écran,
     * au lieu d'avoir à vider sa main d'abord.
     */
    @Override
    protected InteractionResult useItemOn(ItemStack held, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof BurnerEntity burner)) {
            return InteractionResult.PASS;
        }
        if (burner.busy()) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (!held.is(Groove.BLANK.get()) || !burner.empty()) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        burner.load(held.split(1));
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.8F, 1.4F);
        return InteractionResult.SUCCESS;
    }

    /**
     * Clic à main nue : on ouvre l'écran, ou on récupère le disque.
     *
     * <p>L'ordre est celui de l'usage : quand un disque gravé attend, on veut le prendre ; quand un
     * vierge attend, on veut le graver. Faire l'inverse obligerait à retirer le vierge pour ouvrir
     * l'écran, ce qui n'a aucun sens.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof BurnerEntity burner)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (burner.busy()) {
            Groove.tell(player, "Gravure en cours…");
            return InteractionResult.SUCCESS;
        }
        if (burner.holdsFinished()) {
            burner.eject(player);
            return InteractionResult.SUCCESS;
        }
        if (burner.empty()) {
            Groove.tell(player, "Mets un disque vierge dedans.");
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer server) {
            server.connection.send(new Post.Open(pos));
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Le ticker n'existe que pendant la gravure.
     *
     * <p>C'est la même discipline que la Lanterne de Veille, à l'envers : elle n'a jamais de ticker,
     * celui-ci n'en a un que lorsqu'il travaille. Un graveur posé et inactif coûte donc exactement
     * <b>rien</b> par tick, et une base qui en compte dix n'en paie aucun.
     */
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level,
            BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || !state.getValue(WORKING)) {
            return null;
        }
        return createTickerHelper(type, Groove.BURNER_ENTITY.get(), BurnerEntity::tick);
    }

    /**
     * Casser le graveur rend ce qu'il contenait.
     *
     * <h2>Pourquoi ici et pas à la suppression du bloc</h2>
     *
     * <p>{@code affectNeighborsAfterRemoval} serait l'endroit « propre », et le bloc-entité y a déjà
     * disparu selon l'ordre des opérations — on y lirait un contenu vide et le disque serait perdu.
     * {@code playerWillDestroy} est appelé <b>avant</b> le retrait, quand tout est encore là.
     *
     * <p>C'est la même règle que partout dans ce paquet : un objet qu'un joueur a confié au mod lui
     * revient, y compris quand il casse la machine avec un disque dedans, y compris en pleine
     * gravure.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof BurnerEntity burner) {
            burner.spill(level, pos);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** Un graveur qui travaille éclaire faiblement : la gravure se voit de loin. */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos,
            net.minecraft.core.Direction direction) {
        return state.getValue(WORKING) ? 15 : 0;
    }
}
