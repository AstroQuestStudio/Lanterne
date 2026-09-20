package fr.clubcitrouille.lanterne.content.reseau;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.core.Config;
import fr.clubcitrouille.lanterne.core.Reseau;

/**
 * Le bloc-entité du Coffre de dépôt : un coffre vanilla, qui s'annonce lui-même au Réseau.
 *
 * <p>Voir la Javadoc de classe de {@link CoffreDepot} pour pourquoi c'est une sous-classe de
 * {@code ChestBlockEntity} plutôt qu'une réimplémentation — et pourquoi, à la différence de l'Aiguillage,
 * ce choix-là ne heurte AUCUN piège moteur : {@code ChestBlockEntity} porte un constructeur protégé qui
 * accepte un {@code BlockEntityType} à soi, contrairement à {@code HopperBlockEntity}.
 *
 * <p>Aucun {@code Aiguillage} n'est nécessaire dessous : ce bloc EST déjà un nœud, exactement comme un
 * {@code CoffreReception}. Un bot minier externe (le {@code ClubCitrouilleAutoMiner}) n'a besoin de
 * connaître qu'une seule position fixe pour y déverser son butin — le reste du réseau s'en occupe.
 */
public class CoffreDepotBlockEntity extends ChestBlockEntity {
    public CoffreDepotBlockEntity(BlockPos pos, BlockState state) {
        super(Reseaux.COFFRE_DEPOT_ENTITY.get(), pos, state);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.lanterne.coffre_depot");
    }

    /** Rejoint le réseau à la pose comme au chargement du chunk — même hook que {@code VigilBlockEntity}. */
    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level instanceof ServerLevel server && Config.RESEAU_ACTIF.get()) {
            Reseau.register(server, this.worldPosition);
        }
    }

    /** Quitte le réseau à la casse du bloc, ou au déchargement de son chunk. */
    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel server) {
            Reseau.unregister(server, this.worldPosition);
        }
        super.setRemoved();
    }
}
