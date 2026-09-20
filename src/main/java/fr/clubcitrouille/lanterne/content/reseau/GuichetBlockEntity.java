package fr.clubcitrouille.lanterne.content.reseau;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.core.Reseau;

/**
 * Le bloc-entité du Guichet : ni coffre, ni nœud du réseau — seulement une fenêtre sur lui.
 *
 * <h2>Le Guichet ne s'enregistre JAMAIS dans {@code core.Reseau}</h2>
 *
 * <p>Contrairement à l'Aiguillage et aux deux coffres, ce bloc n'a rien à annoncer : il ne contient
 * aucun objet, et n'est le voisin de personne au sens du réseau. Il ne fait que LIRE
 * {@code Reseau.aggregate} et DÉCLENCHER {@code Reseau.withdraw} sur demande — voir
 * {@link Guichet#useWithoutItem} et {@code Reseaux#onDemande}.
 */
public class GuichetBlockEntity extends BlockEntity implements MenuProvider {
    /** Le dernier instantané envoyé, résumé en un entier — voir {@link #republierSiChange}. */
    private int dernierHash;

    public GuichetBlockEntity(BlockPos pos, BlockState state) {
        super(Reseaux.GUICHET_ENTITY.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.lanterne.guichet");
    }

    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player player) {
        return new GuichetMenu(windowId, inventory, this.worldPosition);
    }

    /**
     * Le coffre de collecte : le premier conteneur trouvé parmi les six voisins, ou {@code null} s'il
     * n'y en a aucun — voir {@link Guichet} pour le message que le joueur reçoit dans ce cas.
     */
    public Container coffreAdjacent() {
        if (this.level == null) {
            return null;
        }
        for (Direction face : Direction.values()) {
            if (this.level.getBlockEntity(this.worldPosition.relative(face)) instanceof Container conteneur) {
                return conteneur;
            }
        }
        return null;
    }

    private List<Bordereau.Entree> capture(ServerLevel level) {
        Map<Item, Integer> agrege = Reseau.aggregate(level);
        List<Bordereau.Entree> entrees = new ArrayList<>(agrege.size());
        agrege.forEach((item, quantite) -> entrees.add(new Bordereau.Entree(item, quantite)));
        return entrees;
    }

    /** Envoyé une fois, à l'ouverture du menu — voir {@link Guichet#useWithoutItem}. */
    public void envoyerInstantane(ServerPlayer joueur, ServerLevel level) {
        List<Bordereau.Entree> entrees = capture(level);
        this.dernierHash = entrees.hashCode();
        joueur.connection.send(new Bordereau.Stock(entrees));
    }

    /**
     * Republie l'état du réseau à tout joueur qui a CE Guichet ouvert, mais seulement si quelque chose
     * a changé depuis le dernier envoi — voir {@link Reseaux} pour la cadence à laquelle cette méthode
     * est appelée, volontairement plus lente qu'un tick serveur.
     */
    public void republierSiChange(ServerLevel level) {
        List<Bordereau.Entree> entrees = capture(level);
        int hash = entrees.hashCode();
        if (hash == this.dernierHash) {
            return;
        }
        this.dernierHash = hash;
        Bordereau.Stock paquet = new Bordereau.Stock(entrees);
        for (ServerPlayer joueur : level.players()) {
            if (joueur.containerMenu instanceof GuichetMenu menu && menu.pos().equals(this.worldPosition)) {
                joueur.connection.send(paquet);
            }
        }
    }
}
