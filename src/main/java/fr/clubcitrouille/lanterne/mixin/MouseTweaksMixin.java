package fr.clubcitrouille.lanterne.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;

import fr.clubcitrouille.lanterne.core.ClientConfig;

/**
 * Souris avancée — port littéral de <a href="https://github.com/YaLTeR/MouseTweaks">Mouse Tweaks</a>
 * (BSD-3-Clause — copyright Ivan Molodetskikh, 2023 ; voir {@code NOTICE.md} pour la reproduction
 * complète de la licence, condition 1 du BSD-3-Clause). Adaptation autorisée explicitement par le
 * patron du projet, après qu'une première tentative de « transcription depuis une description de
 * l'algorithme » (sans le vrai code sous les yeux) s'est révélée boguée en jeu : un glissé qui ne
 * touchait aucune case compatible bloquait quand même TOUT le geste vanilla à la place, empêchant un
 * simple dépose dans un coffre qui ne contenait pas encore l'objet.
 *
 * <h2>Le vrai fichier source lu ligne à ligne avant d'écrire celui-ci</h2>
 *
 * <p>{@code src/main/java/yalter/mousetweaks/Main.java} du dépôt réel (récupéré via l'API GitHub,
 * commit `HEAD` au moment de la lecture) — {@code onMouseClicked}, {@code onMouseReleased},
 * {@code onMouseDrag}, {@code onMouseScrolled}, {@code findPushSlots}, {@code findPullSlot}. La
 * traduction ci-dessous suit sa structure quasiment instruction par instruction ; seuls les noms
 * d'API changent (voir plus bas), et les réglages de configuration du vrai mod (fichier
 * {@code MouseTweaks.cfg}, activation par mécanique, ordre de recherche de la molette, sens inversé)
 * sont réduits à un seul interrupteur — {@link ClientConfig#SOURIS_AVANCEE} — plutôt que reproduits un
 * par un.
 *
 * <h2>La vraie découverte du bug : ce fichier ne doit (presque) JAMAIS annuler l'évènement</h2>
 *
 * <p>{@code onMouseDrag} du vrai mod se termine TOUJOURS par {@code return false} — jamais un seul
 * {@code return true} dans toute la méthode. Le mod ne bloque jamais le glissé vanilla lui-même : il
 * se contente de jouer des clics supplémentaires en aparté, puis laisse vanilla continuer. La seule
 * fois où il empêche activement vanilla de refaire le même travail est le glissé bouton droit
 * (mécanique 1), et PAS en annulant l'évènement — en désarmant directement l'état interne de
 * quick-craft de vanilla ({@code handler.disableRMBDraggingFunctionality()}, traduit ici en
 * manipulation directe de {@code skipNextRelease}/{@code isQuickCrafting}, tous deux accessibles par
 * {@code @Shadow} puisque ce mixin est fusionné dans la classe cible). La version précédente de ce
 * fichier annulait le glissé entier ({@code cir.setReturnValue(true)}) dès qu'une des trois mécaniques
 * de glissé était active, même pour les cases où elle ne faisait rien — c'est ce qui bloquait un dépôt
 * dans un coffre vide ou contenant un objet différent. Seule {@code onMouseScrolled} annule vraiment
 * l'évènement dans le vrai mod (la molette n'a pas d'autre sens dans un écran de conteneur).
 *
 * <h2>Vérifié au {@code javap} sur le vrai jar client 26.3 avant d'écrire une ligne</h2>
 *
 * <ul>
 *   <li>{@code AbstractContainerScreen} porte {@code protected boolean isQuickCrafting},
 *       {@code private int quickCraftingButton} et {@code private boolean skipNextRelease} —
 *       les trois champs que {@code disableRMBDraggingFunctionality} manipule dans le vrai mod,
 *       tous trois {@code @Shadow}-ables (Mixin résout par descripteur bytecode, la visibilité Java
 *       source ne s'applique pas à un champ shadow).</li>
 *   <li>{@code ItemStack.isSameItem}/{@code isSameItemSameComponents} : statiques, deux arguments.</li>
 *   <li>{@code net.minecraft.world.inventory.ResultSlot} existe séparément — {@code instanceof} fiable
 *       pour {@code handler.isCraftingOutput(slot)}.</li>
 *   <li>{@code mouseReleased(MouseButtonEvent)} existe et n'était pas encore accroché par la version
 *       précédente de ce fichier — nécessaire pour réinitialiser {@code canDoLMBDrag}/
 *       {@code canDoRMBDrag} exactement comme {@code onMouseReleased} du vrai mod.</li>
 * </ul>
 */
@Mixin(AbstractContainerScreen.class)
public abstract class MouseTweaksMixin<T extends AbstractContainerMenu> {
    @Shadow
    @Final
    protected T menu;

    @Shadow
    protected Slot hoveredSlot;

    @Shadow
    protected boolean isQuickCrafting;

    @Shadow
    private int quickCraftingButton;

    @Shadow
    private boolean skipNextRelease;

    /** {@code Main.oldSelectedSlot} — la case sous la souris au dernier évènement traité. */
    private Slot lastSelectedSlot;

    /** {@code Main.canDoLMBDrag} — vrai si le curseur était VIDE au moment du clic gauche initial. */
    private boolean canDoLmbDrag;

    /** {@code Main.canDoRMBDrag} — vrai si le curseur portait un objet au clic droit initial. */
    private boolean canDoRmbDrag;

    /** {@code Main.rmbTweakLeftOriginalSlot} — la case d'origine du glissé droit a-t-elle reçu son clic ? */
    private boolean rmbTweakLeftOriginalSlot;

    /** {@code Main.accumulatedScrollDelta} — reliquat fractionnaire de molette entre deux appels. */
    private double accumulatedScrollDelta;

    /**
     * {@code Main.onMouseClicked} — jamais annulé. Enregistre la case sous la souris et détermine si
     * les glissés LMB/RMB pourront démarrer, selon l'état du curseur À CET INSTANT PRÉCIS (avant que
     * vanilla ne traite lui-même ce clic).
     */
    @Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z", at = @At("HEAD"))
    private void lanterne$onMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (!ClientConfig.SOURIS_AVANCEE.get()) {
            return;
        }
        this.lastSelectedSlot = this.hoveredSlot;
        ItemStack carried = this.menu.getCarried();
        int button = event.button();
        if (button == 0) {
            this.canDoLmbDrag = carried.isEmpty();
        } else if (button == 1) {
            this.canDoRmbDrag = !carried.isEmpty();
            this.rmbTweakLeftOriginalSlot = false;
        }
    }

    /** {@code Main.onMouseReleased} — referme les deux glissés, jamais annulé. */
    @Inject(method = "mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z", at = @At("HEAD"))
    private void lanterne$onMouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        int button = event.button();
        if (button == 0) {
            this.canDoLmbDrag = false;
        } else if (button == 1) {
            this.canDoRmbDrag = false;
        }
    }

    /**
     * {@code Main.onMouseDrag} — jamais annulé (voir la Javadoc de classe). Rejoue des clics en aparté
     * quand la souris entre dans une nouvelle case, puis laisse vanilla traiter l'évènement normalement.
     */
    @Inject(method = "mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z", at = @At("HEAD"))
    private void lanterne$onMouseDragged(MouseButtonEvent event, double x, double y, CallbackInfoReturnable<Boolean> cir) {
        if (!ClientConfig.SOURIS_AVANCEE.get()) {
            return;
        }

        Slot selectedSlot = this.hoveredSlot;
        if (selectedSlot == this.lastSelectedSlot) {
            return; // La souris n'a pas changé de case : rien à faire (comme le vrai mod).
        }

        ItemStack stackOnMouse = this.menu.getCarried();
        int button = event.button();

        // Au premier vrai départ de la case d'origine pendant un glissé droit : désarmer le
        // quick-craft vanilla (pas annuler l'évènement — voir la Javadoc de classe), puis cliquer la
        // case d'origine elle-même.
        if (this.canDoRmbDrag && button == 1 && !this.rmbTweakLeftOriginalSlot) {
            this.rmbTweakLeftOriginalSlot = true;
            this.skipNextRelease = true;
            if (this.isQuickCrafting && this.quickCraftingButton == 1) {
                this.isQuickCrafting = false;
            }
            rmbTweakMaybeClickSlot(this.lastSelectedSlot, stackOnMouse);
        }

        this.lastSelectedSlot = selectedSlot;

        if (selectedSlot == null) {
            return;
        }

        if (button == 0) {
            if (!this.canDoLmbDrag) {
                return;
            }
            ItemStack selectedSlotStack = selectedSlot.getItem();
            if (selectedSlotStack.isEmpty()) {
                return; // Les mécaniques LMB ne font rien sur une case vide.
            }
            boolean shiftIsDown = event.hasShiftDown();

            if (stackOnMouse.isEmpty()) {
                // Glissé Shift+gauche SANS objet en main : shift-clic direct.
                if (!shiftIsDown) {
                    return;
                }
                handleSlotClick(selectedSlot, 0, ContainerInput.QUICK_MOVE);
            } else {
                // Glissé (Shift+)gauche AVEC objet en main : uniquement des cases compatibles.
                if (!areStacksCompatible(selectedSlotStack, stackOnMouse)) {
                    return;
                }
                if (shiftIsDown) {
                    handleSlotClick(selectedSlot, 0, ContainerInput.QUICK_MOVE);
                } else {
                    if (stackOnMouse.getCount() + selectedSlotStack.getCount() > stackOnMouse.getMaxStackSize()) {
                        return; // Le reliquat serait coincé sans moyen de le reprendre : on renonce.
                    }
                    handleSlotClick(selectedSlot, 0, ContainerInput.PICKUP);
                    if (!(selectedSlot instanceof ResultSlot)) {
                        handleSlotClick(selectedSlot, 0, ContainerInput.PICKUP);
                    }
                }
            }
        } else if (button == 1) {
            if (!this.canDoRmbDrag) {
                return;
            }
            rmbTweakMaybeClickSlot(selectedSlot, stackOnMouse);
        }
    }

    /** {@code Main.rmbTweakMaybeClickSlot} — un vrai clic droit vanilla, refusé si incompatible/plein. */
    private void rmbTweakMaybeClickSlot(Slot slot, ItemStack stackOnMouse) {
        if (slot == null || stackOnMouse.isEmpty() || slot instanceof ResultSlot) {
            return;
        }
        if (!(stackOnMouse.getItem() instanceof BundleItem)) {
            ItemStack selectedSlotStack = slot.getItem();
            if (!areStacksCompatible(selectedSlotStack, stackOnMouse)) {
                return;
            }
            if (selectedSlotStack.getCount() == slot.getMaxStackSize(selectedSlotStack)) {
                return;
            }
        }
        handleSlotClick(slot, 1, ContainerInput.PICKUP);
    }

    /**
     * {@code Main.onMouseScrolled} — toujours annulé au-dessus d'une case valide (la molette n'a pas
     * d'autre sens dans un écran de conteneur), même quand rien ne bouge, pour ne jamais laisser un
     * autre mod réagir au même scroll de façon surprenante.
     */
    @Inject(method = "mouseScrolled(DDDD)Z", at = @At("HEAD"), cancellable = true)
    private void lanterne$onMouseScrolled(double x, double y, double horizontal, double vertical, CallbackInfoReturnable<Boolean> cir) {
        if (!ClientConfig.SOURIS_AVANCEE.get()) {
            return;
        }
        Slot selectedSlot = this.hoveredSlot;
        if (selectedSlot == null) {
            return;
        }

        if (this.accumulatedScrollDelta != 0 && Math.signum(vertical) != Math.signum(this.accumulatedScrollDelta)) {
            this.accumulatedScrollDelta = 0;
        }
        this.accumulatedScrollDelta += vertical;
        int delta = (int) this.accumulatedScrollDelta;
        this.accumulatedScrollDelta -= delta;

        if (delta == 0) {
            cir.setReturnValue(true);
            return;
        }

        int numItemsToMove = Math.abs(delta);
        boolean pushItems = delta < 0;

        ItemStack selectedSlotStack = selectedSlot.getItem();
        if (selectedSlotStack.isEmpty()) {
            cir.setReturnValue(true);
            return;
        }

        ItemStack stackOnMouse = this.menu.getCarried();

        if (selectedSlot instanceof ResultSlot) {
            handleScrollOnCraftingOutput(selectedSlot, selectedSlotStack, stackOnMouse, numItemsToMove, pushItems);
            cir.setReturnValue(true);
            return;
        }

        // Impossible d'interagir proprement si le curseur porte déjà un objet du MÊME type que la
        // case survolée — le vrai mod renonce plutôt que d'inventer un comportement ambigu.
        if (!stackOnMouse.isEmpty() && areStacksCompatible(selectedSlotStack, stackOnMouse)) {
            cir.setReturnValue(true);
            return;
        }

        if (pushItems) {
            handleWheelPush(selectedSlot, selectedSlotStack, stackOnMouse, numItemsToMove);
        } else {
            handleWheelPull(selectedSlot, selectedSlotStack, numItemsToMove);
        }
        cir.setReturnValue(true);
    }

    private void handleScrollOnCraftingOutput(Slot selectedSlot, ItemStack selectedSlotStack,
            ItemStack stackOnMouse, int numItemsToMove, boolean pushItems) {
        if (!areStacksCompatible(selectedSlotStack, stackOnMouse)) {
            return;
        }
        if (stackOnMouse.isEmpty()) {
            if (!pushItems) {
                return; // Impossible de tirer VERS une sortie de craft.
            }
            while (numItemsToMove-- > 0) {
                List<Slot> targets = findPushSlots(selectedSlot, selectedSlotStack.getCount(), true);
                if (targets == null) {
                    break; // Distribution impossible en entier : ne pas même ramasser le lot.
                }
                handleSlotClick(selectedSlot, 0, ContainerInput.PICKUP);
                for (int i = 0; i < targets.size(); i++) {
                    Slot slot = targets.get(i);
                    if (i == targets.size() - 1) {
                        handleSlotClick(slot, 0, ContainerInput.PICKUP);
                    } else {
                        int clickTimes = slot.getMaxStackSize(slot.getItem()) - slot.getItem().getCount();
                        while (clickTimes-- > 0) {
                            handleSlotClick(slot, 1, ContainerInput.PICKUP);
                        }
                    }
                }
            }
        } else {
            while (numItemsToMove-- > 0) {
                handleSlotClick(selectedSlot, 0, ContainerInput.PICKUP);
            }
        }
    }

    /** PUSH — pousse hors de la case survolée vers l'autre camp du menu. */
    private void handleWheelPush(Slot selectedSlot, ItemStack selectedSlotStack, ItemStack stackOnMouse, int numItemsToMove) {
        if (!stackOnMouse.isEmpty() && !selectedSlot.mayPlace(stackOnMouse)) {
            return;
        }
        numItemsToMove = Math.min(numItemsToMove, selectedSlotStack.getCount());

        List<Slot> targets = findPushSlots(selectedSlot, numItemsToMove, false);
        if (targets.isEmpty()) {
            return;
        }

        boolean hadItemOnMouse = !stackOnMouse.isEmpty();

        int pickUpButton = 1;
        if (stackOnMouse.isEmpty() && selectedSlotStack.getCount() <= numItemsToMove) {
            pickUpButton = 0;
        }
        handleSlotClick(selectedSlot, pickUpButton, ContainerInput.PICKUP);

        ItemStack pickedUpStack = this.menu.getCarried();
        numItemsToMove = Math.min(numItemsToMove, pickedUpStack.getCount());

        for (Slot slot : targets) {
            int clickTimes = slot.getMaxStackSize(pickedUpStack) - slot.getItem().getCount();
            clickTimes = Math.min(clickTimes, numItemsToMove);
            numItemsToMove -= clickTimes;
            while (clickTimes-- > 0) {
                handleSlotClick(slot, 1, ContainerInput.PICKUP);
            }
        }

        boolean hasLeftoverItems = !this.menu.getCarried().isEmpty();
        if (hadItemOnMouse || hasLeftoverItems) {
            int putDownButton = 0;
            if (hadItemOnMouse && hasLeftoverItems) {
                putDownButton = 1;
            }
            handleSlotClick(selectedSlot, putDownButton, ContainerInput.PICKUP);
        }
    }

    /** PULL — tire depuis l'autre camp du menu vers la case survolée. */
    private void handleWheelPull(Slot selectedSlot, ItemStack selectedSlotStack, int numItemsToMove) {
        int maxItemsToMove = selectedSlot.getMaxStackSize(selectedSlotStack) - selectedSlotStack.getCount();
        numItemsToMove = Math.min(numItemsToMove, maxItemsToMove);

        while (numItemsToMove > 0) {
            Slot targetSlot = findPullSlot(selectedSlot);
            if (targetSlot == null) {
                break;
            }
            ItemStack targetSlotStack = targetSlot.getItem();
            int numItemsInTargetSlot = targetSlotStack.getCount();
            ItemStack stackOnMouse = this.menu.getCarried();

            if (targetSlot instanceof ResultSlot) {
                if (maxItemsToMove < numItemsInTargetSlot) {
                    break;
                }
                maxItemsToMove -= numItemsInTargetSlot;
                numItemsToMove = Math.min(numItemsToMove - 1, maxItemsToMove);
                if (!stackOnMouse.isEmpty() && !selectedSlot.mayPlace(stackOnMouse)) {
                    break;
                }
                handleSlotClick(selectedSlot, 0, ContainerInput.PICKUP);
                handleSlotClick(targetSlot, 0, ContainerInput.PICKUP);
                handleSlotClick(selectedSlot, 0, ContainerInput.PICKUP);
                continue;
            }

            boolean hadItemOnMouse = !stackOnMouse.isEmpty();
            if (hadItemOnMouse && !targetSlot.mayPlace(stackOnMouse)) {
                break;
            }

            int pickUpButton = 1;
            if (stackOnMouse.isEmpty() && targetSlotStack.getCount() == 1) {
                pickUpButton = 0;
            }
            handleSlotClick(targetSlot, pickUpButton, ContainerInput.PICKUP);

            int numPickedUp = this.menu.getCarried().getCount();
            int numToMoveFromTarget = Math.min(numPickedUp, numItemsToMove);
            if (numToMoveFromTarget == numPickedUp) {
                handleSlotClick(selectedSlot, 0, ContainerInput.PICKUP);
            } else {
                for (int i = 0; i < numToMoveFromTarget; i++) {
                    handleSlotClick(selectedSlot, 1, ContainerInput.PICKUP);
                }
            }
            maxItemsToMove -= numToMoveFromTarget;
            numItemsToMove -= numToMoveFromTarget;

            boolean hasLeftoverItems = !this.menu.getCarried().isEmpty();
            if (hadItemOnMouse || hasLeftoverItems) {
                int putDownButton = 0;
                if (hadItemOnMouse && hasLeftoverItems) {
                    putDownButton = 1;
                }
                handleSlotClick(targetSlot, putDownButton, ContainerInput.PICKUP);
            }
        }
    }

    /** {@code Main.findPullSlot} — première case compatible dans l'autre camp, ordre croissant. */
    private Slot findPullSlot(Slot selectedSlot) {
        ItemStack selectedSlotStack = selectedSlot.getItem();
        boolean findInPlayerInventory = selectedSlot.container != playerInventory();

        for (Slot slot : this.menu.slots) {
            boolean slotInPlayerInventory = slot.container == playerInventory();
            if (findInPlayerInventory != slotInPlayerInventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !areStacksCompatible(selectedSlotStack, stack)) {
                continue;
            }
            return slot;
        }
        return null;
    }

    /** {@code Main.findPushSlots} — cases non-vides compatibles d'abord, cases vides ensuite. */
    private List<Slot> findPushSlots(Slot selectedSlot, int itemCount, boolean mustDistributeAll) {
        ItemStack selectedSlotStack = selectedSlot.getItem();
        boolean findInPlayerInventory = selectedSlot.container != playerInventory();

        List<Slot> result = new ArrayList<>();
        List<Slot> goodEmptySlots = new ArrayList<>();

        for (Slot slot : this.menu.slots) {
            if (itemCount <= 0) {
                break;
            }
            boolean slotInPlayerInventory = slot.container == playerInventory();
            if (findInPlayerInventory != slotInPlayerInventory || slot instanceof ResultSlot) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                if (slot.mayPlace(selectedSlotStack)) {
                    goodEmptySlots.add(slot);
                }
            } else if (areStacksCompatible(selectedSlotStack, stack) && stack.getCount() < slot.getMaxStackSize(stack)) {
                result.add(slot);
                itemCount -= Math.min(itemCount, slot.getMaxStackSize(stack) - stack.getCount());
            }
        }
        for (Slot slot : goodEmptySlots) {
            if (itemCount <= 0) {
                break;
            }
            result.add(slot);
            itemCount -= Math.min(itemCount, slot.getMaxStackSize(selectedSlotStack));
        }

        if (mustDistributeAll && itemCount > 0) {
            return null;
        }
        return result;
    }

    private static Inventory playerInventory() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null ? player.getInventory() : null;
    }

    /** {@code Main.areStacksCompatible} — vide compatible avec tout, sinon même Item + composants. */
    private static boolean areStacksCompatible(ItemStack a, ItemStack b) {
        return a.isEmpty() || b.isEmpty() || (ItemStack.isSameItem(a, b) && ItemStack.isSameItemSameComponents(a, b));
    }

    /**
     * {@code handler.clickSlot} — appel direct à {@code slotClicked}, jamais de réflexion (voir la
     * précédente version de ce fichier pour pourquoi : un échec de réflexion enveloppé dans un
     * {@code catch} muet laissait toute la fonctionnalité inerte sans jamais le dire).
     */
    private void handleSlotClick(Slot slot, int button, ContainerInput input) {
        int slotIndex = this.menu.slots.indexOf(slot);
        if (slotIndex < 0) {
            return;
        }
        this.slotClicked(slot, slotIndex, button, input);
    }

    @Shadow
    protected abstract void slotClicked(Slot slot, int slotIndex, int button, ContainerInput input);
}
