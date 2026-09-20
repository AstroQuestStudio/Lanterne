package fr.clubcitrouille.lanterne.content.trieur;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import fr.clubcitrouille.lanterne.content.Contents;

/**
 * Le menu du Trieur : les cinq cases du hopper, plus neuf cases de filtre, plus un bouton de mode.
 *
 * <h2>Deux constructeurs, comme {@code HopperMenu} vanilla</h2>
 *
 * <p>Le serveur ouvre ce menu avec le vrai {@link TrieurBlockEntity} — {@link #TrieurMenu(int,
 * Inventory, TrieurBlockEntity)}. Le client, lui, reconstruit un menu à la réception du paquet
 * d'ouverture SANS connaître le bloc-entité : {@link MenuType#create} n'appelle jamais que le
 * constructeur à deux arguments (vérifié au {@code javap} sur {@code IContainerFactory}, sa méthode
 * {@code create(int, Inventory)} par défaut invoque la version à trois arguments avec un tampon
 * réseau {@code null}). Ce client-là reçoit donc des conteneurs vierges — exactement ce que fait
 * {@code HopperMenu(int, Inventory)} vanilla avec son propre {@code SimpleContainer(5)} — et les
 * emplacements se remplissent ensuite tout seuls par la synchronisation normale des cases.
 *
 * <h2>Le mode, par {@code DataSlot}</h2>
 *
 * <p>Aucun paquet réseau maison : {@link DataSlot} est le mécanisme déjà éprouvé par vanilla pour
 * exposer un entier serveur au client (le temps de combustion d'un four, par exemple). Le bouton de
 * bascule, lui, passe par {@link AbstractContainerMenu#clickMenuButton}, le même mécanisme vanilla
 * que la table d'enchantement utilise pour choisir un emplacement — vérifié au {@code javap} sur
 * {@code EnchantmentMenu} et sur l'écran qui l'appelle, {@code MultiPlayerGameMode
 * .handleInventoryButtonClick(int, int)}, présent tel quel dans ce moteur.
 */
public class TrieurMenu extends AbstractContainerMenu {
    public static final int HOPPER_SLOTS = HopperBlockEntity.HOPPER_CONTAINER_SIZE;
    public static final int FILTER_SLOTS = TrieurBlockEntity.FILTER_SLOTS;

    /** Seul bouton de ce menu : bascule liste blanche / liste noire. */
    public static final int BUTTON_BASCULE = 0;

    private final Container hopper;
    private final Container filtre;
    private final DataSlot mode;

    /** Reconstruction côté client : voir la javadoc de classe. */
    public TrieurMenu(int id, Inventory inventory) {
        this(id, inventory, new SimpleContainer(HOPPER_SLOTS), new SimpleContainer(FILTER_SLOTS),
                DataSlot.standalone());
    }

    /** Ouverture côté serveur, sur le vrai bloc-entité. */
    public TrieurMenu(int id, Inventory inventory, TrieurBlockEntity entity) {
        this(id, inventory, entity, entity.filtre(), modeSlotFor(entity));
    }

    private TrieurMenu(int id, Inventory inventory, Container hopper, Container filtre, DataSlot mode) {
        super(Contents.TRIEUR_MENU.get(), id);
        checkContainerSize(hopper, HOPPER_SLOTS);
        checkContainerSize(filtre, FILTER_SLOTS);
        this.hopper = hopper;
        this.filtre = filtre;
        this.mode = this.addDataSlot(mode);

        for (int i = 0; i < HOPPER_SLOTS; i++) {
            this.addSlot(new Slot(hopper, i, 44 + i * 18, 18));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int index = row * 3 + col;
                this.addSlot(new FiltreSlot(filtre, index, 44 + col * 18, 44 + row * 18));
            }
        }
        this.addStandardInventorySlots(inventory, 8, 118);

        Player player = inventory.player;
        hopper.startOpen(player);
        filtre.startOpen(player);
    }

    private static DataSlot modeSlotFor(TrieurBlockEntity entity) {
        return new DataSlot() {
            @Override
            public int get() {
                return entity.whitelist() ? 1 : 0;
            }

            @Override
            public void set(int value) {
                entity.setWhitelist(value != 0);
            }
        };
    }

    public boolean whitelist() {
        return this.mode.get() != 0;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id != BUTTON_BASCULE) {
            return false;
        }
        this.mode.set(this.whitelist() ? 0 : 1);
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack moved = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return moved;
        }
        ItemStack held = slot.getItem();
        moved = held.copy();
        int firstPlayerSlot = HOPPER_SLOTS + FILTER_SLOTS;
        if (index < firstPlayerSlot) {
            // Depuis le hopper ou le filtre : vers l'inventaire du joueur, jamais l'inverse d'un
            // simple clic — un gabarit qu'on retire du filtre doit pouvoir atterrir n'importe où.
            if (!this.moveItemStackTo(held, firstPlayerSlot, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(held, 0, HOPPER_SLOTS, false)) {
            // Depuis l'inventaire du joueur : uniquement vers les cases de transfert du hopper.
            // Le filtre ne se remplit jamais par un clic-glissement — seulement à la main, case par
            // case, pour qu'on choisisse vraiment ce qu'on y met.
            return ItemStack.EMPTY;
        }
        if (held.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return moved;
    }

    @Override
    public boolean stillValid(Player player) {
        return this.hopper.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.hopper.stopOpen(player);
        this.filtre.stopOpen(player);
    }

    /**
     * Une case de filtre : au plus un exemplaire, jamais un tas.
     *
     * <p>{@link #set} ramène systématiquement ce qu'on y pose à une unité, quel que soit le chemin
     * emprunté pour l'y déposer — clic simple, échange, ou tout autre appel de bas niveau du menu.
     * C'est plus sûr que de compter sur {@code getMaxStackSize} seul : cette dernière borne
     * l'INSERTION dans les calculs vanilla, mais ne garantit rien sur ce que {@link #set} reçoit
     * directement. Retirer l'unique exemplaire vide la case, donc efface l'entrée du filtre — aucun
     * code séparé n'est nécessaire pour cela, {@link TrieurBlockEntity#matchesAnyTemplate} ignore déjà
     * les cases vides.
     */
    private static final class FiltreSlot extends Slot {
        FiltreSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return true;
        }

        @Override
        public void set(ItemStack stack) {
            super.set(stack.isEmpty() ? stack : stack.copyWithCount(1));
        }
    }
}
