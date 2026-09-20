package fr.clubcitrouille.lanterne.content.reseau;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;

/**
 * Le menu du Guichet : un terminal de consultation, jamais un coffre.
 *
 * <h2>Pourquoi zéro case</h2>
 *
 * <p>Un Guichet ne stocke rien et ne laisse rien glisser d'un inventaire à un autre : on y CONSULTE le
 * réseau, et on y DEMANDE une livraison qui part directement dans le coffre de collecte, jamais dans
 * la main du joueur (voir le README, section Le Réseau, pour pourquoi c'est un choix explicite du
 * patron). {@link #quickMoveStack} n'a donc jamais rien à faire, et ce menu ne déclare aucun
 * emplacement, pas même ceux de l'inventaire du joueur.
 *
 * <h2>{@link #objets} vit ici, pas dans l'écran</h2>
 *
 * <p>Le paquet {@code Bordereau.Stock} met à jour CE champ, pas un champ de {@code GuichetScreen} :
 * un menu existe dès l'ouverture, avant même que l'écran ne soit construit côté client, et le paquet
 * envoyé juste après {@code player.openMenu(...)} doit trouver quelque chose à qui parler — voir
 * {@code client.screen.GuichetScreen#register}.
 */
public class GuichetMenu extends AbstractContainerMenu {
    private final BlockPos pos;
    private final ContainerLevelAccess access;
    private List<Bordereau.Entree> objets = List.of();

    /** Constructeur client : lu depuis le tampon que {@code player.openMenu(MenuProvider, BlockPos)} écrit. */
    public GuichetMenu(int windowId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(windowId, inventory, BlockPos.STREAM_CODEC.decode(buffer));
    }

    public GuichetMenu(int windowId, Inventory inventory, BlockPos pos) {
        super(Reseaux.GUICHET_MENU.get(), windowId);
        this.pos = pos;
        this.access = ContainerLevelAccess.create(inventory.player.level(), pos);
    }

    public BlockPos pos() {
        return this.pos;
    }

    /** Le dernier instantané reçu du serveur. Jamais {@code null} — une liste vide au pire. */
    public List<Bordereau.Entree> objets() {
        return this.objets;
    }

    /** Appelé côté client seulement, par {@code client.screen.GuichetScreen#register}. */
    public void setObjets(List<Bordereau.Entree> objets) {
        this.objets = objets;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(this.access, player, Reseaux.GUICHET.get());
    }
}
