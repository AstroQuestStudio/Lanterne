package fr.clubcitrouille.lanterne.content.reseau;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.jspecify.annotations.Nullable;

import fr.clubcitrouille.lanterne.core.Config;

/**
 * Le Coffre de réception : un coffre qui se sert tout seul dans le Réseau pour rester approvisionné.
 *
 * <h2>Le filtre se règle sans écran, à la main</h2>
 *
 * <p>Clic droit avec un objet en main : ajoute une pile de cet objet à la cible (cumulatif — cliquer
 * trois fois demande trois piles). Clic droit accroupi avec un objet en main : retire l'objet du
 * filtre. Clic droit à main nue : ouvre le coffre normalement, exactement comme un {@code CoffreDepot}.
 * Pas d'écran dédié — voir {@link CoffreReceptionBlockEntity} pour pourquoi une carte simple suffit.
 *
 * <h2>Un ticker, mais pas au rythme du jeu</h2>
 *
 * <p>Le seul bloc de ce module qui en a besoin : {@link CoffreReceptionBlockEntity#reapprovisionner}
 * doit s'exécuter de temps en temps, sans intervention du joueur. {@link #getTicker} en fournit un,
 * mais {@link CoffreReceptionBlockEntity} elle-même ne fait le travail que toutes les
 * {@code Config#RESEAU_INTERVALLE_REAPPRO} ticks — un module de confort n'a pas le droit de coûter à
 * chaque passage, la même règle que {@code Easel} applique à ses convois.
 */
public class CoffreReception extends BaseEntityBlock {
    public CoffreReception(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CoffreReceptionBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        return createTickerHelper(type, Reseaux.COFFRE_RECEPTION_ENTITY.get(), CoffreReception::tick);
    }

    private static void tick(Level level, BlockPos pos, BlockState state, CoffreReceptionBlockEntity coffre) {
        if (!(level instanceof ServerLevel server) || !Config.RESEAU_ACTIF.get()) {
            return;
        }
        int intervalle = Math.max(1, Config.RESEAU_INTERVALLE_REAPPRO.get());
        if (server.getGameTime() % intervalle != 0L) {
            return;
        }
        coffre.reapprovisionner(server);
    }

    /**
     * Clic avec un objet : règle le filtre. {@code TRY_WITH_EMPTY_HAND} rend la main au chemin
     * « sans objet » dès que rien ne peut être fait ici — même patron que {@code content.disc.Burner}.
     */
    @Override
    protected InteractionResult useItemOn(ItemStack held, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (held.isEmpty() || !(level.getBlockEntity(pos) instanceof CoffreReceptionBlockEntity coffre)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        String nom = held.getHoverName().getString();
        if (player.isShiftKeyDown()) {
            if (coffre.retirerFiltre(held.getItem())) {
                tell(player, "Filtre retiré : " + nom + ".");
            } else {
                tell(player, nom + " n'était pas dans le filtre.");
            }
        } else {
            int cible = coffre.ajouterFiltre(held.getItem(), held.getMaxStackSize());
            tell(player, "Filtre : " + nom + " maintenu à " + cible + ".");
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    /** Clic à main nue : ouvre le coffre, exactement comme {@code CoffreDepot}. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof CoffreReceptionBlockEntity coffre)) {
            return InteractionResult.PASS;
        }
        player.openMenu(coffre);
        return InteractionResult.SUCCESS_SERVER;
    }

    private static void tell(Player player, String text) {
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }
}
