package fr.clubcitrouille.lanterne.content.reseau;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.jspecify.annotations.Nullable;

/**
 * Le Coffre de dépôt : un coffre ordinaire à l'usage, un nœud du Réseau sans qu'on ait rien à faire.
 *
 * <h2>Pourquoi un bloc à soi, et pas simplement « posez un Aiguillage sous un coffre »</h2>
 *
 * <p>Le patron du projet a demandé deux positions FIXES et connues d'avance, pour qu'un bot minier
 * externe ({@code ClubCitrouilleAutoMiner}, un autre dépôt) puisse y déverser son butin sans avoir à
 * découvrir ou mémoriser où sont les deux cents coffres de la base. Un coffre vanilla relié par un
 * Aiguillage aurait tout aussi bien fonctionné pour un JOUEUR — mais son emplacement dépendrait alors
 * de la construction du joueur, pas d'un contrat stable que l'extérieur peut coder en dur une fois pour
 * toutes.
 *
 * <h2>Pourquoi pas {@code ChestBlock} lui-même, seulement {@code ChestBlockEntity}</h2>
 *
 * <p>{@code ChestBlock} vanilla porte toute la mécanique de coffre DOUBLE — {@code ChestType},
 * {@code chestCanConnectTo}, le combineur de deux blocs-entités en un seul conteneur de 54 cases, le
 * chat qui empêche l'ouverture. Rien de tout cela n'est nécessaire à un point de dépôt fixe, et
 * reproduire son modèle 3D à deux battants animés serait du travail pur pour un bloc que le patron n'a
 * jamais demandé de voir s'ouvrir en jeu de cette façon précise. Ce bloc est donc un cube simple,
 * rendu par un modèle de bloc-état normal ({@link #getRenderShape}) — le conteneur qu'il ouvre au clic
 * est, lui, le VRAI {@code ChestMenu} vanilla à 27 cases, hérité tel quel par {@link CoffreDepotBlockEntity}.
 */
public class CoffreDepot extends BaseEntityBlock {
    public CoffreDepot(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CoffreDepotBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * Ouvre le coffre. Vanilla laisse déjà tomber au chemin « sans objet » quand {@code useItemOn} rend
     * {@code PASS} — comme {@code ChestBlock} lui-même, aucune redéfinition de {@code useItemOn} n'est
     * nécessaire pour qu'un clic avec un objet en main ouvre quand même le coffre.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof CoffreDepotBlockEntity coffre)) {
            return InteractionResult.PASS;
        }
        player.openMenu(coffre);
        return InteractionResult.SUCCESS_SERVER;
    }
}
