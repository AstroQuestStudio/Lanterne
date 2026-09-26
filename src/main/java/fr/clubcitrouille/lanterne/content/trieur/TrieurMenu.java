package fr.clubcitrouille.lanterne.content.trieur;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import fr.clubcitrouille.lanterne.content.Contents;

/**
 * Le menu du Trieur : les cinq cases du hopper, plus dix-huit cases de filtre, plus un bouton de mode.
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
 *
 * <h2>Les motifs, et l'étiquette qu'on ne rend jamais</h2>
 *
 * <p>Un motif tapé dans l'écran arrive par {@link TrieurCablage.AjoutMotif} et se pose, par {@link
 * #ajouterMotif}, dans la première case libre sous la forme d'une étiquette VIRTUELLE ({@link
 * Motif#etiquette}, marquée {@link Motif#MARQUE}). Cette étiquette n'a jamais été payée : la rendre
 * au joueur ferait du champ texte une fabrique d'étiquettes. Tous les chemins par lesquels une case
 * peut se vider vers le joueur sont donc refermés pour elle — clic ({@link #clicked}), clic
 * majuscule ({@link #quickMoveStack}), touche de barre rapide et main secondaire ({@code SWAP}, dans
 * {@link #clicked}), double clic qui ramasse tout ({@link #canTakeItemForPickAll}), jeter ({@code
 * THROW}) — et tous font la même chose : la case se vide, rien n'atterrit nulle part. Une VRAIE
 * étiquette renommée à l'enclume, posée à la main, reste un gabarit ordinaire au sens de ce menu :
 * le joueur la reprend comme n'importe quel objet posé dans le filtre.
 */
public class TrieurMenu extends AbstractContainerMenu {
    public static final int HOPPER_SLOTS = HopperBlockEntity.HOPPER_CONTAINER_SIZE;
    public static final int FILTER_SLOTS = TrieurBlockEntity.FILTER_SLOTS;

    /** Seul bouton de ce menu : bascule liste blanche / liste noire. */
    public static final int BUTTON_BASCULE = 0;

    /**
     * Hauteur de l'inventaire du joueur dans l'écran. 118 jusqu'à 26.3.140 ; descendu pour laisser la
     * place au champ de motif et à son aperçu (voir {@code client.screen.TrieurScreen}).
     */
    public static final int INVENTORY_Y = 134;

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
            this.addSlot(new Slot(hopper, i, 8 + i * 18, 18));
        }
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 9; col++) {
                int index = row * 9 + col;
                this.addSlot(new FiltreSlot(filtre, index, 8 + col * 18, 44 + row * 18));
            }
        }
        this.addStandardInventorySlots(inventory, 8, INVENTORY_Y);

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

    /**
     * Pose un motif dans la première case de filtre libre. Rend {@code false} si le filtre est plein ;
     * un motif déjà présent (même texte, casse et accents compris) n'est pas dupliqué.
     */
    public boolean ajouterMotif(String motif) {
        Slot libre = null;
        for (int i = HOPPER_SLOTS; i < HOPPER_SLOTS + FILTER_SLOTS; i++) {
            Slot slot = this.slots.get(i);
            ItemStack contenu = slot.getItem();
            if (contenu.isEmpty()) {
                if (libre == null) {
                    libre = slot;
                }
            } else if (Motif.estMotif(contenu) && Motif.texte(contenu).equals(motif)) {
                return true;
            }
        }
        if (libre == null) {
            return false;
        }
        libre.set(Motif.etiquette(motif));
        this.broadcastChanges();
        return true;
    }

    private static boolean caseVirtuelle(Slot slot) {
        return slot instanceof FiltreSlot && Motif.estVirtuel(slot.getItem());
    }

    /**
     * Un clic, quel qu'il soit, sur une case qui porte un motif tapé : la case se vide, rien ne va
     * dans la main ni dans l'inventaire. Si le joueur tenait un objet, il le pose à la place (un
     * exemplaire, comme sur n'importe quelle case de filtre). Le numéro de bouton n'est volontairement
     * PAS testé — voir le piège SDL de 26.3 où le clic gauche ne vaut plus 0 : ce chemin ne dépend
     * d'aucun numéro. Exécuté à l'identique côté client (prédiction) et serveur (vérité).
     */
    @Override
    public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
        if (slotIndex >= HOPPER_SLOTS && slotIndex < HOPPER_SLOTS + FILTER_SLOTS
                && input != ContainerInput.QUICK_CRAFT && input != ContainerInput.CLONE
                && caseVirtuelle(this.slots.get(slotIndex))) {
            Slot slot = this.slots.get(slotIndex);
            ItemStack tenu = this.getCarried();
            if (input == ContainerInput.PICKUP && !tenu.isEmpty()) {
                slot.set(tenu.copyWithCount(1));
                tenu.shrink(1);
            } else {
                slot.set(ItemStack.EMPTY);
            }
            slot.setChanged();
            return;
        }
        super.clicked(slotIndex, buttonNum, input, player);
    }

    /** Le double clic « tout ramasser » ne vide jamais une case de motif tapé dans la main. */
    @Override
    public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
        return !caseVirtuelle(target) && super.canTakeItemForPickAll(carried, target);
    }

    /** Un glisser-déposer ne recouvre pas un motif tapé (il serait perdu sans avoir été cliqué). */
    @Override
    public boolean canDragTo(Slot slot) {
        return !caseVirtuelle(slot) && super.canDragTo(slot);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack moved = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return moved;
        }
        if (caseVirtuelle(slot)) {
            // Voir la javadoc de classe : un motif tapé se retire, il ne se reprend pas.
            slot.set(ItemStack.EMPTY);
            return ItemStack.EMPTY;
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
     * code séparé n'est nécessaire pour cela, le filtre compilé ({@code TrieurBlockEntity.Crible})
     * ignore déjà les cases vides.
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

        /** Une étiquette de motif tapé ne se prend pas : voir la javadoc de {@link TrieurMenu}. */
        @Override
        public boolean mayPickup(Player player) {
            return !Motif.estVirtuel(this.getItem());
        }
    }
}
