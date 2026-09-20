package fr.clubcitrouille.lanterne.content.reseau;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.core.Config;
import fr.clubcitrouille.lanterne.core.Reseau;

/**
 * Le bloc-entité de l'Aiguillage : il ne stocke rien, ne tique jamais, et ne fait qu'annoncer.
 *
 * <p>Voir la Javadoc de classe de {@link Aiguillage} pour pourquoi ce bloc n'est PAS un hopper qui
 * fonctionne, malgré ce que son nom et sa forme suggèrent.
 */
public class AiguillageBlockEntity extends BlockEntity {
    /**
     * Les positions RÉELLEMENT annoncées au réseau par CE bloc, capturées une fois à {@link #onLoad()}
     * et reprises telles quelles à {@link #setRemoved()} — jamais recalculées à la casse.
     *
     * <p>Un voisinage peut changer pendant la vie du bloc : un second coffre posé à côté après coup, ou
     * le premier cassé puis remplacé par autre chose. Désenregistrer ce que le voisinage donnerait
     * MAINTENANT plutôt que ce qui a été annoncé alors retirerait du réseau un nœud annoncé par
     * quelqu'un d'autre — voir la Javadoc de {@code core.Reseau} sur le compte de références qui rend
     * cette précision nécessaire.
     */
    private List<BlockPos> annonces = List.of();

    public AiguillageBlockEntity(BlockPos pos, BlockState state) {
        super(Reseaux.AIGUILLAGE_ENTITY.get(), pos, state);
    }

    /**
     * Prend son poste. Appelé à la pose comme au chargement du chunk — voir {@code VigilBlockEntity}
     * pour le même hook déjà éprouvé dans ce mod.
     *
     * <p>Balaie les six faces et annonce TOUS les conteneurs trouvés, pas seulement le premier : un
     * Aiguillage posé au coin de deux coffres relie les deux d'un coup, sans qu'il y ait de graphe à
     * tenir pour ça — chaque position annoncée est indépendante des autres dans {@link Reseau}.
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (!(this.level instanceof ServerLevel server) || !Config.RESEAU_ACTIF.get()) {
            return;
        }
        List<BlockPos> trouves = new ArrayList<>();
        for (Direction face : Direction.values()) {
            BlockPos voisin = this.worldPosition.relative(face);
            if (server.getBlockEntity(voisin) instanceof Container) {
                trouves.add(voisin.immutable());
            }
        }
        this.annonces = trouves;
        for (BlockPos pos : this.annonces) {
            Reseau.register(server, pos);
        }
    }

    /** Quitte son poste — à la casse du bloc, ou au déchargement de son chunk. */
    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel server) {
            for (BlockPos pos : this.annonces) {
                Reseau.unregister(server, pos);
            }
        }
        this.annonces = List.of();
        super.setRemoved();
    }
}
