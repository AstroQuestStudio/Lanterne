package fr.clubcitrouille.lanterne.content.reseau;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import fr.clubcitrouille.lanterne.core.Config;
import fr.clubcitrouille.lanterne.core.Reseau;

/**
 * Le bloc-entité du Coffre de réception : un coffre vanilla, un nœud du Réseau, et un filtre à
 * maintenir en stock.
 *
 * <h2>Le filtre n'est qu'une carte, et c'est délibéré</h2>
 *
 * <p>Un module frère de ce mod ({@code Trieur}, sur une autre branche) construit un filtre par gabarits
 * pour trier visuellement des objets. Ce bloc n'a besoin de rien de tel : {@link #filtre} ne sert qu'à
 * une question — « combien de CET objet est-ce que je veux voir ici en permanence ? » — et une simple
 * {@code Map<Item, Integer>} y répond sans écran dédié, configurée entièrement au clic droit avec un
 * objet en main. Voir {@link CoffreReception} pour l'interaction.
 *
 * <h2>Pourquoi {@link #reapprovisionner} ne se vide jamais dans lui-même</h2>
 *
 * <p>{@code this} est passé comme {@code destination} à {@code Reseau.withdraw}, et {@code this} est
 * potentiellement AUSSI l'un des nœuds que {@code withdraw} parcourrait comme source — il s'est
 * annoncé lui-même à {@link #onLoad}, exactement comme {@link CoffreDepotBlockEntity}. Nul besoin
 * d'exclure sa position explicitement : {@code Reseau.withdraw} écarte déjà toute source qui EST la
 * destination, par égalité de référence. Voir la Javadoc de {@code core.Reseau#withdraw}.
 */
public class CoffreReceptionBlockEntity extends ChestBlockEntity {
    private static final Codec<Map<Item, Integer>> FILTRE_CODEC =
            Codec.unboundedMap(BuiltInRegistries.ITEM.byNameCodec(), Codec.INT);

    /** Objet -> quantité à maintenir en stock dans ce coffre. Jamais négatif, jamais implicite. */
    private final Map<Item, Integer> filtre = new LinkedHashMap<>();

    public CoffreReceptionBlockEntity(BlockPos pos, BlockState state) {
        super(Reseaux.COFFRE_RECEPTION_ENTITY.get(), pos, state);
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.lanterne.coffre_reception");
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level instanceof ServerLevel server && Config.RESEAU_ACTIF.get()) {
            Reseau.register(server, this.worldPosition);
        }
    }

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
        if (!this.filtre.isEmpty()) {
            output.store("filtre", FILTRE_CODEC, this.filtre);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.filtre.clear();
        input.read("filtre", FILTRE_CODEC).ifPresent(this.filtre::putAll);
    }

    /** Augmente la cible de {@code pas}, en crée une nouvelle au besoin, et rend la cible finale. */
    public int ajouterFiltre(Item item, int pas) {
        int cible = this.filtre.merge(item, Math.max(1, pas), Integer::sum);
        setChanged();
        return cible;
    }

    /** Retire complètement une entrée. Rend faux si elle n'existait pas déjà. */
    public boolean retirerFiltre(Item item) {
        boolean existait = this.filtre.remove(item) != null;
        if (existait) {
            setChanged();
        }
        return existait;
    }

    /** Copie défensive : voir {@code CoffreReception} pour son unique appelant. */
    public Map<Item, Integer> filtre() {
        return Map.copyOf(this.filtre);
    }

    /**
     * Le réapprovisionnement lent. Appelé depuis {@link CoffreReception#getTicker}, au rythme réglé par
     * {@code Config#RESEAU_INTERVALLE_REAPPRO} — jamais à chaque tick, voir sa Javadoc pour pourquoi.
     */
    public void reapprovisionner(ServerLevel level) {
        for (Map.Entry<Item, Integer> entree : this.filtre.entrySet()) {
            Item item = entree.getKey();
            int cible = entree.getValue();
            int present = countItem(item);
            if (present < cible) {
                Reseau.withdraw(level, item, cible - present, this);
            }
        }
    }
}
