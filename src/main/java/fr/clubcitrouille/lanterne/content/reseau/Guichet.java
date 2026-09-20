package fr.clubcitrouille.lanterne.content.reseau;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
 * Le Guichet : un terminal pour consulter le Réseau entier et se faire livrer, sans jamais bouger.
 *
 * <h2>Ce que le clic déclenche, et ce qu'il ne déclenche PAS</h2>
 *
 * <p>{@link #useWithoutItem} ouvre {@code GuichetMenu} — un menu vanilla standard, via
 * {@code player.openMenu(MenuProvider, ...)}, exactement le mécanisme que {@code client.screen.Phare}
 * documente en détail. Aucun paquet maison ne sert à OUVRIR l'écran : c'est le patron le plus simple et
 * le plus sûr, et il évite tout risque de nommer une classe cliente depuis ce bloc, qui est chargé des
 * deux côtés.
 *
 * <p>Le paquet {@code Bordereau.Stock} envoyé juste après ne sert qu'à remplir le menu qui vient de
 * s'ouvrir — un instantané, pas une seconde façon de l'ouvrir.
 */
public class Guichet extends BaseEntityBlock {
    /**
     * Ticks entre deux vérifications de changement — jamais à chaque tick serveur. Le réseau ne change
     * qu'au rythme des joueurs (poser un coffre, vider un Guichet) ; une demi-seconde de retard sur
     * l'affichage d'un chiffre qui vient de bouger ailleurs est invisible, et dix fois moins de travail
     * que vérifier à chaque tick.
     */
    private static final int RAFRAICHISSEMENT_TICKS = 10;

    public Guichet(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GuichetBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        return createTickerHelper(type, Reseaux.GUICHET_ENTITY.get(), Guichet::tick);
    }

    private static void tick(Level level, BlockPos pos, BlockState state, GuichetBlockEntity guichet) {
        if (!(level instanceof ServerLevel server) || server.getGameTime() % RAFRAICHISSEMENT_TICKS != 0L) {
            return;
        }
        guichet.republierSiChange(server);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof GuichetBlockEntity guichet)
                || !(player instanceof ServerPlayer serveurJoueur) || !(level instanceof ServerLevel server)) {
            return InteractionResult.PASS;
        }
        if (!Config.RESEAU_ACTIF.get()) {
            Reseaux.tell(serveurJoueur, "Le Réseau est désactivé sur ce serveur.");
            return InteractionResult.SUCCESS_SERVER;
        }
        // La position doit voyager avec l'ouverture : GuichetMenu la lit côté client depuis ce même
        // tampon, via son constructeur (RegistryFriendlyByteBuf) — voir GuichetMenu et Bordereau.
        serveurJoueur.openMenu(guichet, pos);
        guichet.envoyerInstantane(serveurJoueur, server);
        return InteractionResult.SUCCESS_SERVER;
    }
}
