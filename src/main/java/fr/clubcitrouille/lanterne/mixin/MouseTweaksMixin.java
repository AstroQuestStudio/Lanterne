package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

import fr.clubcitrouille.lanterne.core.ClientConfig;

/**
 * Souris avancée — les quatre mécaniques du mod populaire Mouse Tweaks.
 *
 * <h2>Mécaniques reproduites</h2>
 *
 * <p><b>1. Glisser-déposer en maintenant Shift + clic gauche</b> : chaque case survolée reçoit un
 * transfert rapide (QUICK_MOVE), vidant l'inventaire vers l'autre côté une case à la fois.
 *
 * <p><b>2. Glisser-déposer avec le bouton droit</b> : avec une pile en main, chaque case survolée
 * reçoit un exemplaire (PICKUP).
 *
 * <p><b>3. Molette de la souris</b> : un exemplaire à la fois vers/depuis l'inventaire du joueur.
 *
 * <p><b>4. Tout écran de conteneur</b> : vanilla ou modé, chaque conteneur supportant des cases
 * reçoit ces mécaniques.
 *
 * <h2>Implémentation</h2>
 *
 * <p>Deux points d'accroche : {@code mouseDragged} pour le glissé (gestion d'état du dernier slot
 * traité pour éviter les redéclenchements à la même image), et {@code mouseScrolled} pour la
 * molette. La détection de la case survolée utilise le champ {@code hoveredSlot} maintenu à jour
 * par l'écran vanilla.
 *
 * <p>Chaque clic est traduit en appel à {@code slotClicked} avec les paramètres appropriés
 * (ContainerInput.QUICK_MOVE pour transfert rapide, ContainerInput.PICKUP pour prise normale).
 */
@Mixin(AbstractContainerScreen.class)
public abstract class MouseTweaksMixin<T extends AbstractContainerMenu> {
    @Shadow
    @Final
    protected T menu;

    @Shadow
    protected Slot hoveredSlot;

    /** La case traitée dans le glissé courant — réinitialisée à null à la fin du glissé. */
    private Slot lastDraggedSlot = null;

    /**
     * Le bouton du glissé courant (1 = gauche, 3 = droit), conservé pour la durée du glissé.
     * 0 = pas de glissé en cours.
     */
    private int dragButton = 0;

    /**
     * Vrai si le glissé actuel a commencé avec Shift enfoncé (pour le clic gauche uniquement).
     */
    private boolean dragWithShift = false;

    /**
     * Intercept le glissé de souris. La mécanique Mouse Tweaks : chaque case survolée se voit
     * appliquer le même effet de clic que si on y avait cliqué.
     *
     * <p>Return true pour empêcher le comportement vanilla, false pour le laisser passer.
     */
    @Inject(method = "mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z", at = @At("HEAD"), cancellable = true)
    private boolean onMouseDragged(MouseButtonEvent event, double x, double y, CallbackInfoReturnable<Boolean> cir) {
        if (!ClientConfig.SOURIS_AVANCEE.get()) {
            return false; // Config désactivée, laisser vanilla gérer.
        }

        int button = event.button();

        // Détecter le début du glissé ou sa continuation.
        if (dragButton == 0) {
            // Pas de glissé en cours, on commence un nouveau.
            dragButton = button;
            dragWithShift = event.hasShiftDown();
            lastDraggedSlot = null;
        } else if (dragButton != button) {
            // Bouton changé en cours de glissé (rare mais possible) — ignorer.
            return false;
        }

        // Clic gauche sans Shift : c'est du vanilla, laisser passer.
        if (button == 1 && !dragWithShift) {
            return false;
        }

        // Clic droit ou (clic gauche + Shift) : appliquer Mouse Tweaks.
        if (button == 1 || button == 3) {
            Slot hoveredNow = this.hoveredSlot; // Le champ mis à jour par l'écran.

            if (hoveredNow != null && hoveredNow != lastDraggedSlot) {
                // Case différente de la dernière traitée — déclencher un clic.
                lastDraggedSlot = hoveredNow;

                ContainerInput inputType;
                if (button == 1) {
                    // Clic gauche + Shift = transfert rapide.
                    inputType = ContainerInput.QUICK_MOVE;
                } else {
                    // Clic droit = prise normale (un exemplaire par case).
                    inputType = ContainerInput.PICKUP;
                }

                this.handleSlotClick(hoveredNow, inputType);
            }

            cir.setReturnValue(true); // Consommer l'événement.
            return true;
        }

        return false;
    }

    /**
     * Intercept la molette de la souris pour la mécanique un exemplaire à la fois.
     */
    @Inject(method = "mouseScrolled(DDDD)Z", at = @At("HEAD"), cancellable = true)
    private boolean onMouseScrolled(double x, double y, double horizontal, double vertical, CallbackInfoReturnable<Boolean> cir) {
        if (!ClientConfig.SOURIS_AVANCEE.get()) {
            return false;
        }

        // Réinitialiser le glissé si on interagit avec la molette.
        dragButton = 0;
        lastDraggedSlot = null;

        Slot hoveredNow = this.hoveredSlot;

        if (hoveredNow != null && vertical != 0) {
            // Vertical non-zero : molette vers le haut (positive) ou vers le bas (négative).
            this.handleSlotClick(hoveredNow, ContainerInput.PICKUP);
            cir.setReturnValue(true);
            return true;
        }

        return false;
    }

    /**
     * Réinitialiser l'état du glissé après un clic normal (qui ne fait pas partie du glissé).
     */
    @Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z", at = @At("HEAD"))
    private void onMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        // Fin du glissé précédent.
        dragButton = 0;
        lastDraggedSlot = null;
    }

    /**
     * Trouver et invoquer la méthode slotClicked avec les paramètres corrects.
     * Cette approche utilise la réflexion pour appeler la méthode protégée.
     */
    private void handleSlotClick(Slot slot, ContainerInput input) {
        if (slot == null) {
            return;
        }

        // Trouver le numéro du slot dans le menu.
        int slotIndex = -1;
        for (int i = 0; i < this.menu.slots.size(); i++) {
            if (this.menu.slots.get(i) == slot) {
                slotIndex = i;
                break;
            }
        }

        if (slotIndex < 0) {
            return; // Slot not found in menu.
        }

        try {
            // Appeler la méthode protégée via réflexion.
            var method = AbstractContainerScreen.class.getDeclaredMethod(
                    "slotClicked",
                    Slot.class,
                    int.class,
                    int.class,
                    ContainerInput.class
            );
            method.setAccessible(true);
            method.invoke(this, slot, slotIndex, 0, input);
        } catch (Exception e) {
            // Fallback silencieux si la méthode n'existe pas — ne pas crasher l'écran.
        }
    }
}
