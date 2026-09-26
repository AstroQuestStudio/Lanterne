package fr.clubcitrouille.lanterne.content.reseau;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import org.jspecify.annotations.Nullable;

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
 *
 * <h2>L'envoi (26.3.142) : un dépôt au rez-de-chaussée d'une salle de tri</h2>
 *
 * <p>Facultatif, absent par défaut — un Coffre de dépôt d'avant 26.3.142 ne change pas de conduite.
 * Avec {@link #envoi} réglé, le coffre se vide chaque seconde dans le conteneur à cette position : le
 * coffre d'entrée d'une salle de tri placé onze blocs plus haut, par exemple. C'est l'unique raison
 * d'être de ce réglage : déposer au niveau de l'allée sans monter jusqu'à l'entrée.
 *
 * <ul>
 * <li><b>Sans duplication</b> : {@code Reseau#deposit} insère d'abord et rend ce que la destination a
 * RÉELLEMENT accepté ; seul ce nombre est ensuite retiré d'ici. Destination pleine = rien ne bouge.</li>
 * <li><b>Sans chargement forcé</b> : destination non chargée = rien ne bouge.</li>
 * <li><b>Borné</b> : {@link #PORTEE_ENVOI} blocs au plus, même dimension, jamais vers soi-même. Une
 * destination qui refuse (un Trieur dont le filtre ne veut pas de l'objet) le laisse ici.</li>
 * <li><b>Économe</b> : une passe par seconde, décalée par position, et rien du tout tant que le coffre
 * est vide — une lecture de 27 cases.</li>
 * </ul>
 *
 * <p>Format : {@code envoi:[I;x,y,z]} — {@code /data merge block <coffre> {envoi:[I;-2040,71,-647]}}
 * — ou en jeu, accroupi main vide : voir {@link CoffreDepot.Liaison}.
 */
public class CoffreDepotBlockEntity extends ChestBlockEntity {
    /** Distance maximale (euclidienne, en blocs) entre ce coffre et sa destination d'envoi. */
    public static final int PORTEE_ENVOI = 64;

    /** Une passe d'envoi toutes les {@code CADENCE_ENVOI} ticks. */
    static final int CADENCE_ENVOI = 20;

    private static final String ENVOI_KEY = "envoi";

    private @Nullable BlockPos envoi;

    public CoffreDepotBlockEntity(BlockPos pos, BlockState state) {
        super(Reseaux.COFFRE_DEPOT_ENTITY.get(), pos, state);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.lanterne.coffre_depot");
    }

    /** La destination d'envoi, ou {@code null} : coffre de dépôt ordinaire. */
    public @Nullable BlockPos envoi() {
        return this.envoi;
    }

    /** Règle (ou coupe, avec {@code null}) l'envoi. Rend faux si la destination est refusée. */
    public boolean setEnvoi(@Nullable BlockPos cible) {
        if (cible != null && (cible.equals(this.worldPosition)
                || cible.distSqr(this.worldPosition) > (double) PORTEE_ENVOI * PORTEE_ENVOI)) {
            return false;
        }
        this.envoi = cible == null ? null : cible.immutable();
        setChanged();
        return true;
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

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (this.envoi != null) {
            output.store(ENVOI_KEY, BlockPos.CODEC, this.envoi);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.envoi = input.read(ENVOI_KEY, BlockPos.CODEC)
                .filter(p -> !p.equals(this.worldPosition)
                        && p.distSqr(this.worldPosition) <= (double) PORTEE_ENVOI * PORTEE_ENVOI)
                .orElse(null);
    }

    /**
     * Une passe d'envoi : chaque case vers la destination, dans la limite de ce qu'elle accepte. Rend le
     * nombre d'objets envoyés. Appelé par le ticker de {@link CoffreDepot}, jamais à chaque tick.
     */
    public int envoyer(ServerLevel level) {
        BlockPos cible = this.envoi;
        if (cible == null || isEmpty() || !level.isLoaded(cible)) {
            return 0;
        }
        Container destination = HopperBlockEntity.getContainerAt(level, cible);
        if (destination == null || destination == this) {
            return 0;
        }
        int envoye = 0;
        for (int i = 0; i < getContainerSize(); i++) {
            ItemStack pile = getItem(i);
            if (pile.isEmpty()) {
                continue;
            }
            int accepte = Reseau.deposit(destination, pile.copy());
            if (accepte > 0) {
                removeItem(i, accepte);
                envoye += accepte;
            }
        }
        return envoye;
    }
}
