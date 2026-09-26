package fr.clubcitrouille.lanterne.content.reseau;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import org.jspecify.annotations.Nullable;

import fr.clubcitrouille.lanterne.core.Config;
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
 *
 * <h2>Depuis 26.3.142 : la salle de tri voisine en plus</h2>
 *
 * <p>Le stock d'un Guichet = les nœuds du Réseau, PLUS les coffres de la salle de tri relevée autour
 * de lui par {@link SalleDeTri} (portée {@code reseau_portee_salle}). Rien à relier à la main : poser
 * le Guichet près de la salle suffit. Le relevé n'est pas sauvegardé — il se refait en quelques
 * millisecondes à l'ouverture — et n'est jamais refait en tâche de fond.
 */
public class GuichetBlockEntity extends BlockEntity implements MenuProvider {
    /**
     * Durée de vie d'un relevé de salle ({@link SalleDeTri}), en ticks. Un coffre posé ou cassé dans
     * la salle apparaît au plus dix secondes plus tard — et un relevé n'est de toute façon refait que
     * si quelqu'un regarde ou demande.
     */
    static final int FRAICHEUR_TICKS = 200;

    /** Borne du paquet {@code Bordereau.Stock} ({@code ByteBufCodecs.list(1024)}) : au-delà, l'encodage lève. */
    private static final int LIGNES_MAX = 1024;

    /** Le dernier instantané envoyé, résumé en un entier — voir {@link #republierSiChange}. */
    private int dernierHash;

    /** Le relevé de salle en cours, jamais sauvegardé. */
    private SalleDeTri.Releve salle = SalleDeTri.Releve.VIDE;
    private long salleTick = Long.MIN_VALUE;

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

    /** Le relevé de la salle de tri voisine, refait s'il a plus de {@link #FRAICHEUR_TICKS} ticks. */
    public SalleDeTri.Releve salle(ServerLevel level) {
        long maintenant = level.getGameTime();
        if (this.salleTick == Long.MIN_VALUE || maintenant - this.salleTick > FRAICHEUR_TICKS
                || maintenant < this.salleTick) {
            this.salle = SalleDeTri.relever(level, this.worldPosition, Config.RESEAU_PORTEE_SALLE.get());
            this.salleTick = maintenant;
        }
        return this.salle;
    }

    /**
     * Le coffre de collecte, et les positions qu'il occupe.
     *
     * @param conteneur le conteneur où livrer — un coffre double vanilla est pris ENTIER (54 cases)
     * @param positions ses moitiés, jamais puisées pendant la livraison (voir {@code Reseau#withdraw})
     */
    public record Collecte(Container conteneur, Set<BlockPos> positions) {}

    /**
     * Le premier conteneur trouvé parmi les six voisins, ou {@code null}. Un Coffre de dépôt qui
     * renvoie vers le tri ({@link CoffreDepotBlockEntity#envoi}) est sauté : y livrer renverrait
     * aussitôt la commande d'où elle vient.
     */
    public @Nullable Collecte collecte() {
        if (this.level == null) {
            return null;
        }
        for (Direction face : Direction.values()) {
            BlockPos voisin = this.worldPosition.relative(face);
            if (!this.level.isLoaded(voisin)
                    || !(this.level.getBlockEntity(voisin) instanceof Container conteneur)) {
                continue;
            }
            if (conteneur instanceof CoffreDepotBlockEntity depot && depot.envoi() != null) {
                continue;
            }
            BlockState etat = this.level.getBlockState(voisin);
            if (etat.getBlock() instanceof ChestBlock coffre && etat.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                Container entier = ChestBlock.getContainer(coffre, etat, this.level, voisin, true);
                if (entier != null) {
                    return new Collecte(entier, Set.of(voisin.immutable(),
                            ChestBlock.getConnectedBlockPos(voisin, etat).immutable()));
                }
            }
            return new Collecte(conteneur, Set.of(voisin.immutable()));
        }
        return null;
    }

    /**
     * Le premier conteneur voisin, sans rien allouer : la seule question que pose la lueur cliente
     * ({@code client.reseau.GuichetRenderer}), à chaque image. La livraison passe par {@link #collecte}.
     */
    public @Nullable Container coffreAdjacent() {
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

    /** Le stock visible d'ici : le Réseau classique, plus la salle de tri voisine. */
    public Map<Item, Integer> stock(ServerLevel level) {
        return Reseau.aggregate(level, salle(level).stockages());
    }

    /**
     * Livre jusqu'à {@code quantite} {@code item} dans le coffre de collecte. Rend le nombre livré, ou
     * -1 sans coffre de collecte. La garantie anti-duplication est celle de {@code Reseau#withdraw},
     * qui fait tout le travail : insérer d'abord, retirer ensuite exactement ce qui a été accepté.
     */
    public int livrer(ServerLevel level, Item item, int quantite) {
        Collecte collecte = collecte();
        if (collecte == null) {
            return -1;
        }
        int obtenu = Reseau.withdraw(level, item, quantite, collecte.conteneur(),
                salle(level).stockages(), collecte.positions());
        if (obtenu > 0) {
            republierSiChange(level);
        }
        return obtenu;
    }

    private List<Bordereau.Entree> capture(ServerLevel level) {
        Map<Item, Integer> agrege = stock(level);
        List<Bordereau.Entree> entrees = new ArrayList<>(agrege.size());
        agrege.forEach((item, quantite) -> entrees.add(new Bordereau.Entree(item, quantite)));
        if (entrees.size() > LIGNES_MAX) {
            // Les plus gros stocks d'abord : une salle à plus de mille types perd sa queue, pas sa tête.
            entrees.sort(Comparator.comparingInt(Bordereau.Entree::quantite).reversed());
            return new ArrayList<>(entrees.subList(0, LIGNES_MAX));
        }
        return entrees;
    }

    /** Envoyé une fois, à l'ouverture du menu — voir {@link Guichet#useWithoutItem}. */
    public void envoyerInstantane(ServerPlayer joueur, ServerLevel level) {
        this.salleTick = Long.MIN_VALUE; // ouvrir le Guichet = relever la salle telle qu'elle est
        List<Bordereau.Entree> entrees = capture(level);
        this.dernierHash = entrees.hashCode();
        joueur.connection.send(new Bordereau.Stock(entrees));
    }

    /**
     * Republie l'état du réseau à tout joueur qui a CE Guichet ouvert, mais seulement si quelque chose
     * a changé depuis le dernier envoi — voir {@link Guichet} pour la cadence d'appel.
     *
     * <p>Depuis 26.3.142 : RIEN n'est calculé tant que personne n'a ce Guichet ouvert. Avant, chaque
     * Guichet posé refaisait l'agrégat complet toutes les dix ticks pour personne — gratuit sur trois
     * coffres, pas sur une salle de mille deux cents.
     */
    public void republierSiChange(ServerLevel level) {
        List<ServerPlayer> lecteurs = null;
        for (ServerPlayer joueur : level.players()) {
            if (joueur.containerMenu instanceof GuichetMenu menu && menu.pos().equals(this.worldPosition)) {
                if (lecteurs == null) {
                    lecteurs = new ArrayList<>(2);
                }
                lecteurs.add(joueur);
            }
        }
        if (lecteurs == null) {
            return;
        }
        List<Bordereau.Entree> entrees = capture(level);
        int hash = entrees.hashCode();
        if (hash == this.dernierHash) {
            return;
        }
        this.dernierHash = hash;
        Bordereau.Stock paquet = new Bordereau.Stock(entrees);
        for (ServerPlayer joueur : lecteurs) {
            joueur.connection.send(paquet);
        }
    }
}
