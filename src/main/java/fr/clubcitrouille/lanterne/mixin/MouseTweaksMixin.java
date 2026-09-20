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
import net.minecraft.world.item.ItemStack;

import fr.clubcitrouille.lanterne.core.ClientConfig;

/**
 * Souris avancée — transcription fidèle des quatre mécaniques du mod populaire
 * <a href="https://github.com/YaLTeR/MouseTweaks">Mouse Tweaks</a> (BSD-3-Clause ; adaptation autorisée
 * par le patron). L'algorithme ci-dessous vient d'une lecture du vrai portage NeoForge du mod par un
 * agent dédié — ce n'est pas une réinvention, c'est une traduction dans les API de ce moteur précis.
 *
 * <h2>Les quatre mécaniques</h2>
 *
 * <p><b>1. Glissé bouton droit ({@link #handleRmbDragSlot})</b> : ne s'active que si le curseur porte
 * déjà un objet au moment du clic initial. Au premier changement de case, la case d'ORIGINE du glissé
 * est elle-même cliquée avant la nouvelle case survolée — un vrai clic droit vanilla rejoué sur chaque
 * case, refusé si la case est incompatible ou déjà pleine.
 *
 * <p><b>2. Clic gauche avec objet en main, sans Shift ({@link #handlePickupDragSlot})</b> : fusionne
 * la case survolée dans le curseur (PICKUP) si elle contient le même objet — mais seulement si la
 * fusion tient dans la pile max du curseur ; sinon, rien ne se passe pour cette case plutôt que de
 * laisser un reliquat coincé.
 *
 * <p><b>3. Clic gauche + Shift ({@link #handleShiftDragSlot})</b> : un shift-clic (QUICK_MOVE) par
 * case survolée. La plus simple des quatre — aucune vérification de compatibilité, le shift-clic
 * vanilla gère déjà ça tout seul.
 *
 * <p><b>4. Molette ({@link #onMouseScrolled})</b> : POUSSE (molette vers le bas) ou TIRE (vers le
 * haut) un nombre d'objets accumulé entre appels. Voir la Javadoc de {@link #onMouseScrolled},
 * {@link #handleWheelPush} et {@link #handleWheelPull} pour le détail — c'est la mécanique la plus
 * dense des quatre, et la seule qui manipule le curseur en plusieurs clics successifs.
 *
 * <h2>Vérifié au {@code javap} sur {@code neoformruntime/artifacts/minecraft_26.3_client.jar} avant
 * d'écrire une ligne de ce fichier</h2>
 *
 * <ul>
 *   <li>{@code ItemStack.isSameItem(ItemStack, ItemStack)} et
 *       {@code ItemStack.isSameItemSameComponents(ItemStack, ItemStack)} : statiques, deux arguments
 *       — pas des méthodes d'instance.</li>
 *   <li>{@code ItemStack.getMaxStackSize()} : n'apparaît PAS directement dans {@code ItemStack}, c'est
 *       une méthode par défaut de l'interface {@code ItemInstance} qu'elle implémente — appelable
 *       normalement, juste absente d'un {@code javap} qui ne suit pas les interfaces.</li>
 *   <li>{@code Player.getInventory()} retourne {@code Inventory}, qui {@code implements Container} —
 *       exactement le type de {@code Slot.container} (champ public final), ce qui rend
 *       {@code slot.container == joueur.getInventory()} directement comparable sans cast.</li>
 *   <li>{@code net.minecraft.world.inventory.ResultSlot extends Slot} existe bien séparément dans ce
 *       moteur — {@code instanceof} dessus est donc fiable.</li>
 *   <li>{@code AbstractContainerScreen} ne déclare ni {@code Player} ni {@code Inventory} accessibles
 *       directement en {@code @Shadow} ; le joueur local vient de
 *       {@code Minecraft.getInstance().player} ({@code LocalPlayer extends AbstractClientPlayer
 *       extends Player}), comme n'importe quel autre écran client.</li>
 * </ul>
 *
 * <h2>Trois endroits où l'énoncé laissait un choix, tranché ici et documenté (pas des simplifications
 * de l'algorithme — juste des angles morts du texte source résolus une fois pour toutes)</h2>
 *
 * <ol>
 *   <li>Ordre de recherche d'une case source pour TIRER (mécanique 4/PULL) : l'énoncé autorise
 *       explicitement les deux sens. {@link #findPullSource} balaie {@code menu.slots} dans l'ordre
 *       croissant.</li>
 *   <li>« Restitue le reliquat sur la case source (droit si le curseur porte un type différent de ce
 *       qu'il y avait, gauche sinon) » : la comparaison se fait entre le reliquat sur le curseur et le
 *       contenu ACTUEL (après le ramassage) de la case source — une case source vidée compte comme
 *       « pas différent » (clic gauche, dépose tout proprement) ; une case source qui contiendrait
 *       encore un objet distinct du reliquat prend un clic droit (dépose un par un, jamais un swap
 *       brutal de piles incompatibles). Voir {@link #restituteLeftoverOnSource}.</li>
 *   <li>Garde-fou « curseur déjà incompatible pendant un scroll » (mécanique 4) : comparé contre le
 *       contenu de la case SURVOLÉE quand elle en a un ; si la case survolée est vide, il n'y a rien de
 *       défini à comparer et la vérification est ignorée pour ce tick — {@code mayPlace} et les
 *       recherches de case cible/source, elles, restent des garde-fous actifs en aval.</li>
 * </ol>
 *
 * <p>Aucune mécanique n'a dû être appauvrie par rapport à l'énoncé fourni — contrairement à la version
 * précédente de ce fichier, qui remplaçait la molette « un par un » par un simple QUICK_MOVE faute
 * d'avoir le vrai algorithme sous la main. Ici, la molette pousse et tire bien objet par objet.
 *
 * <h2>Implémentation</h2>
 *
 * <p>{@code mouseDragged} porte les mécaniques 1 à 3 (glissé), {@code mouseScrolled} porte la
 * mécanique 4. Les deux détectent un changement de case via {@code hoveredSlot} (maintenu par l'écran
 * vanilla) comparé à la dernière case traitée — rien ne mémorise les cases déjà visitées plus tôt dans
 * le même geste, un retour en arrière les retraite normalement, exactement comme le texte source
 * l'exige.
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
     * Le bouton du glissé courant, conservé pour sa durée. {@code -1} = pas de glissé en cours.
     *
     * <h2>0 est un bouton valide — jamais une valeur "rien"</h2>
     *
     * <p>Vérifié dans ce même dépôt, {@code client.screen.Phare} (le clic gauche de l'écran de
     * balise) teste déjà {@code event.button() == 0} pour le clic gauche — c'est la convention GLFW
     * standard, inchangée dans ce moteur : 0 = gauche, 1 = droit, 2 = milieu. Un premier jet de cette
     * classe utilisait 0 comme sentinelle "pas de glissé", ce qui confondait "aucun glissé" avec "un
     * glissé au clic gauche" — la moitié des gestes ne se déclenchait jamais. {@code -1} n'est le
     * numéro d'aucun bouton réel.</p>
     */
    private int dragButton = -1;

    /** Vrai si le glissé actuel a commencé avec Shift enfoncé (pour le clic gauche uniquement). */
    private boolean dragWithShift = false;

    /**
     * Vrai si {@code menu.getCarried()} n'était pas vide au moment du clic initial du glissé. Gate les
     * mécaniques 1 (RMB) et 2 (LMB sans Shift), toutes deux titrées « avec objet en main » dans
     * l'énoncé — évalué UNE fois au début du glissé, jamais recalculé en route, car ces deux
     * mécaniques manipulent elles-mêmes le curseur (le voir se vider en cours de route est normal et
     * ne doit pas faire basculer vers un autre comportement).
     */
    private boolean dragCarriedAtStart = false;

    /** La case sous le curseur au tout début du glissé courant — utilisée par la mécanique 1 (RMB). */
    private Slot rmbOriginSlot = null;

    /** Vrai une fois que la case d'origine du glissé RMB a reçu son clic (voir {@link #handleRmbDragSlot}). */
    private boolean rmbOriginClicked = false;

    /**
     * Accumulateur de molette, persistant entre appels de {@link #onMouseScrolled} — voir sa Javadoc.
     */
    private double wheelAccumulated = 0.0;

    /**
     * Intercepte le glissé de souris — mécaniques 1 (RMB), 2 (LMB avec objet, sans Shift) et
     * 3 (LMB + Shift). Un simple clic gauche sans Shift et sans objet en main n'est PAS une mécanique
     * Mouse Tweaks : on laisse vanilla gérer ce cas (retour false, rien consommé).
     */
    @Inject(method = "mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z", at = @At("HEAD"), cancellable = true)
    private boolean onMouseDragged(MouseButtonEvent event, double x, double y, CallbackInfoReturnable<Boolean> cir) {
        if (!ClientConfig.SOURIS_AVANCEE.get()) {
            return false; // Config désactivée, laisser vanilla gérer.
        }

        int button = event.button();

        if (dragButton == -1) {
            // Nouveau glissé : capturer tout l'état "au moment du clic initial" une bonne fois.
            dragButton = button;
            dragWithShift = event.hasShiftDown();
            lastDraggedSlot = null;
            dragCarriedAtStart = !menu.getCarried().isEmpty();
            rmbOriginSlot = this.hoveredSlot;
            rmbOriginClicked = false;
        } else if (dragButton != button) {
            // Bouton changé en cours de glissé (rare mais possible) — ignorer.
            return false;
        }

        boolean leftShiftDrag = button == 0 && dragWithShift;                       // mécanique 3
        boolean leftPickupDrag = button == 0 && !dragWithShift && dragCarriedAtStart; // mécanique 2
        boolean rightDistributeDrag = button == 1 && dragCarriedAtStart;            // mécanique 1
        if (!leftShiftDrag && !leftPickupDrag && !rightDistributeDrag) {
            return false;
        }

        Slot hoveredNow = this.hoveredSlot;
        if (hoveredNow != null && hoveredNow != lastDraggedSlot) {
            if (leftShiftDrag) {
                handleShiftDragSlot(hoveredNow);
            } else if (leftPickupDrag) {
                handlePickupDragSlot(hoveredNow);
            } else {
                handleRmbDragSlot(hoveredNow);
            }
            lastDraggedSlot = hoveredNow;
        }

        cir.setReturnValue(true);
        return true;
    }

    /**
     * Mécanique 1 — glissé bouton droit. Au premier vrai départ de la case d'origine, celle-ci reçoit
     * elle aussi son clic droit (pas seulement les cases suivantes) ; ensuite chaque case nouvellement
     * survolée reçoit le même traitement, y compris en cas de retour sur une case déjà traitée plus
     * tôt dans le glissé (aucune mémoire de "déjà visitée").
     */
    private void handleRmbDragSlot(Slot hoveredNow) {
        if (!rmbOriginClicked && rmbOriginSlot != null && hoveredNow != rmbOriginSlot) {
            clickRmbSlotIfAllowed(rmbOriginSlot);
            rmbOriginClicked = true;
        }
        clickRmbSlotIfAllowed(hoveredNow);
        if (hoveredNow == rmbOriginSlot) {
            rmbOriginClicked = true;
        }
    }

    /** Un vrai clic droit vanilla (PICKUP, bouton=1) — refusé si la case est incompatible ou pleine. */
    private void clickRmbSlotIfAllowed(Slot slot) {
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return; // Plus rien à distribuer.
        }
        if (slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            if (!sameItemAndComponents(carried, slotStack) || slotStack.getCount() >= slot.getMaxStackSize(slotStack)) {
                return; // Incompatible, ou déjà à sa taille max : refuse.
            }
        }
        handleSlotClick(slot, 1, ContainerInput.PICKUP);
    }

    /**
     * Mécanique 2 — clic gauche avec objet en main, sans Shift. Fusionne toute la case survolée dans
     * le curseur si elle contient le même objet (même Item, mêmes composants). Un dépassement de la
     * pile max du curseur abandonne totalement cette case plutôt que de laisser un reliquat coincé.
     * Un seul clic, jamais un second même sur une {@link ResultSlot} — la refaire cliquer avancerait
     * un lot de fabrication supplémentaire non désiré.
     */
    private void handlePickupDragSlot(Slot slot) {
        if (!slot.hasItem()) {
            return;
        }
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return;
        }
        ItemStack slotStack = slot.getItem();
        if (!sameItemAndComponents(carried, slotStack)) {
            return;
        }
        if (carried.getCount() + slotStack.getCount() > carried.getMaxStackSize()) {
            return; // Abandon total pour cette case.
        }
        handleSlotClick(slot, 0, ContainerInput.PICKUP);
    }

    /** Mécanique 3 — clic gauche + Shift. Un shift-clic par case survolée, sans autre condition. */
    private void handleShiftDragSlot(Slot slot) {
        handleSlotClick(slot, 0, ContainerInput.QUICK_MOVE);
    }

    /**
     * Intercepte la molette — mécanique 4, la plus dense des quatre.
     *
     * <h2>Accumulation</h2>
     *
     * <p>{@code vertical} est accumulé dans {@link #wheelAccumulated} entre appels ; si son signe
     * change par rapport à l'accumulé courant, celui-ci est remis à zéro avant d'ajouter le nouveau
     * delta. La partie entière (tronquée vers zéro) de l'accumulé est le nombre d'objets à déplacer ce
     * tick ; le reste fractionnaire est conservé pour le prochain appel — gère nativement les petits
     * deltas fractionnaires de certaines souris/trackpads sans rien perdre.
     *
     * <h2>Sens</h2>
     *
     * <p>{@code vertical < 0} (molette vers le bas) POUSSE hors de la case survolée vers l'autre camp
     * du menu ; {@code vertical > 0} TIRE depuis l'autre camp vers la case survolée. Le camp d'une case
     * est déterminé par {@code slot.container == joueur.getInventory()} (voir {@link #sameCamp}).
     *
     * <h2>Case de sortie de craft survolée directement</h2>
     *
     * <p>Interdiction absolue d'y pousser (l'évènement est tout de même consommé, rien ne se passe).
     * En tirer répète un simple clic gauche (chaque clic prend le lot entier produit, jamais un
     * exemplaire) — c'est le seul chemin de {@link ResultSlot} qui ne passe pas par
     * {@link #handleWheelPush}/{@link #handleWheelPull}.
     */
    @Inject(method = "mouseScrolled(DDDD)Z", at = @At("HEAD"), cancellable = true)
    private boolean onMouseScrolled(double x, double y, double horizontal, double vertical, CallbackInfoReturnable<Boolean> cir) {
        if (!ClientConfig.SOURIS_AVANCEE.get()) {
            return false;
        }

        // Réinitialiser le glissé si on interagit avec la molette.
        dragButton = -1;
        lastDraggedSlot = null;

        Slot hoveredNow = this.hoveredSlot;
        if (hoveredNow == null || vertical == 0) {
            return false;
        }

        if (wheelAccumulated != 0 && Math.signum(wheelAccumulated) != Math.signum(vertical)) {
            wheelAccumulated = 0; // Changement de sens : on ne mélange pas un reliquat vers le bas avec un scroll vers le haut.
        }
        wheelAccumulated += vertical;
        int units = (int) wheelAccumulated; // Troncature vers zéro.
        wheelAccumulated -= units; // Le reste fractionnaire attend le prochain scroll.

        if (units == 0) {
            cir.setReturnValue(true);
            return true; // Rien à déplacer ce tick, mais on garde la main sur l'évènement.
        }

        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty() && hoveredNow.hasItem() && !sameItemAndComponents(carried, hoveredNow.getItem())) {
            // Garde-fou : curseur déjà occupé par un type incompatible — abandon, mais consommation.
            cir.setReturnValue(true);
            return true;
        }

        if (hoveredNow instanceof ResultSlot) {
            if (units < 0) {
                cir.setReturnValue(true);
                return true; // Jamais de push dans une sortie de craft.
            }
            for (int i = 0; i < units; i++) {
                handleSlotClick(hoveredNow, 0, ContainerInput.PICKUP); // Un lot entier par clic.
            }
        } else if (units < 0) {
            handleWheelPush(hoveredNow, -units);
        } else {
            handleWheelPull(hoveredNow, units);
        }

        cir.setReturnValue(true);
        return true;
    }

    /**
     * PUSH — pousse {@code want} objets (borné par ce que contient réellement {@code hovered}) hors de
     * la case survolée vers l'autre camp. Ramasse en UNE fois (jamais de ramassages répétés dans le
     * même tick), puis dépose un par un (clic droit répété) sur les cases cibles trouvées par
     * {@link #findPushTargets}, et restitue le reliquat sur la case source à la fin.
     */
    private void handleWheelPush(Slot hovered, int want) {
        ItemStack sourceStack = hovered.getItem();
        if (sourceStack.isEmpty()) {
            return;
        }
        want = Math.min(want, sourceStack.getCount());
        if (want <= 0) {
            return;
        }

        List<Slot> targets = findPushTargets(hovered, sourceStack);
        if (targets.isEmpty()) {
            return; // Nulle part où pousser : ne pas même entamer le ramassage.
        }

        boolean grabAll = shouldGrabAll(menu.getCarried(), sourceStack.getCount(), want);
        handleSlotClick(hovered, grabAll ? 0 : 1, ContainerInput.PICKUP);
        if (menu.getCarried().isEmpty()) {
            return; // Rien ramassé (cas limite) : abandonner proprement.
        }

        int placed = 0;
        for (Slot target : targets) {
            if (placed >= want || menu.getCarried().isEmpty()) {
                break;
            }
            while (placed < want && !menu.getCarried().isEmpty() && !targetIsFull(target)) {
                int beforeCount = menu.getCarried().getCount();
                handleSlotClick(target, 1, ContainerInput.PICKUP);
                int deposited = beforeCount - menu.getCarried().getCount(); // Toujours relu, jamais calculé.
                if (deposited <= 0) {
                    break; // La case cible refuse : passer à la suivante.
                }
                placed += deposited;
            }
        }

        restituteLeftoverOnSource(hovered);
    }

    /**
     * PULL — tire jusqu'à {@code want} objets depuis l'autre camp vers la case survolée, une case
     * source à la fois, tant qu'il reste de la place dans la case survolée. Une source qui s'avère
     * être une {@link ResultSlot} interrompt la boucle : un seul clic gauche entier, jamais objet par
     * objet, et rien d'autre ce tick.
     */
    private void handleWheelPull(Slot hovered, int want) {
        int pulled = 0;
        while (pulled < want && hoveredHasRoom(hovered)) {
            Slot source = findPullSource(hovered);
            if (source == null) {
                break; // Plus rien de compatible en face.
            }

            if (source instanceof ResultSlot) {
                handleSlotClick(source, 0, ContainerInput.PICKUP);
                return; // Un seul clic gauche par tick de molette pour une sortie de craft.
            }

            int need = want - pulled;
            int sourceCount = source.getItem().getCount();
            boolean grabAll = shouldGrabAll(menu.getCarried(), sourceCount, need);
            handleSlotClick(source, grabAll ? 0 : 1, ContainerInput.PICKUP);
            if (menu.getCarried().isEmpty()) {
                break; // Rien ramassé : éviter une boucle infinie.
            }

            int hoveredBefore = stackCount(hovered);
            int pickedCount = menu.getCarried().getCount();
            if (pickedCount <= need) {
                handleSlotClick(hovered, 0, ContainerInput.PICKUP); // Tout déposer d'un coup.
            } else {
                for (int i = 0; i < need && !menu.getCarried().isEmpty(); i++) {
                    handleSlotClick(hovered, 1, ContainerInput.PICKUP);
                }
            }
            int gained = stackCount(hovered) - hoveredBefore; // Toujours relu, jamais calculé.

            restituteLeftoverOnSource(source);

            if (gained <= 0) {
                break; // Aucun progrès : éviter une boucle infinie.
            }
            pulled += gained;
        }
    }

    /**
     * Dernier clic sur la case source pour lui rendre le reliquat du curseur, si {@code getCarried()}
     * n'est pas vide après un push/pull. Voir le point 2 de la Javadoc de classe pour l'interprétation
     * du choix droit/gauche.
     */
    private void restituteLeftoverOnSource(Slot source) {
        ItemStack leftover = menu.getCarried();
        if (leftover.isEmpty()) {
            return;
        }
        ItemStack sourceNow = source.getItem();
        boolean sameType = sourceNow.isEmpty() || sameItemAndComponents(sourceNow, leftover);
        handleSlotClick(source, sameType ? 0 : 1, ContainerInput.PICKUP);
    }

    /**
     * Cases cibles pour un PUSH, dans l'autre camp que {@code hovered} : d'abord les cases non-vides
     * déjà compatibles et non pleines, dans l'ordre du menu, puis en second recours les cases vides
     * qui acceptent le type ({@link Slot#mayPlace}). Une {@link ResultSlot} n'est jamais une cible —
     * son {@code mayPlace} refuse déjà tout en vanilla, exclue ici explicitement pour que ce soit lu
     * sans ambiguïté plutôt que déduit d'un comportement vanilla implicite.
     */
    private List<Slot> findPushTargets(Slot hovered, ItemStack sourceStack) {
        List<Slot> targets = new ArrayList<>();
        for (Slot candidate : this.menu.slots) {
            if (candidate == hovered || candidate instanceof ResultSlot || sameCamp(candidate, hovered)) {
                continue;
            }
            if (candidate.hasItem() && sameItemAndComponents(candidate.getItem(), sourceStack) && !targetIsFull(candidate)) {
                targets.add(candidate);
            }
        }
        for (Slot candidate : this.menu.slots) {
            if (candidate == hovered || candidate instanceof ResultSlot || sameCamp(candidate, hovered)) {
                continue;
            }
            if (!candidate.hasItem() && candidate.mayPlace(sourceStack)) {
                targets.add(candidate);
            }
        }
        return targets;
    }

    /**
     * Première case source compatible pour un PULL, dans l'autre camp que {@code hovered}, dans
     * l'ordre croissant de {@code menu.slots} (l'énoncé autorise les deux sens ; choix documenté en
     * classe). Compatible = même objet que ce qu'il y a déjà dans {@code hovered} si elle n'est pas
     * vide, sinon n'importe quel objet que {@code hovered} accepte ({@link Slot#mayPlace}).
     */
    private Slot findPullSource(Slot hovered) {
        ItemStack hoveredStack = hovered.getItem();
        for (Slot candidate : this.menu.slots) {
            if (candidate == hovered || !candidate.hasItem() || sameCamp(candidate, hovered)) {
                continue;
            }
            ItemStack candidateStack = candidate.getItem();
            boolean compatible = hoveredStack.isEmpty()
                    ? hovered.mayPlace(candidateStack)
                    : sameItemAndComponents(candidateStack, hoveredStack);
            if (compatible) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Vrai si {@code a} et {@code b} appartiennent au même camp du menu — {@code slot.container} valant
     * ou non l'inventaire du joueur local ({@code Player.getInventory()}).
     */
    private boolean sameCamp(Slot a, Slot b) {
        Inventory playerInventory = playerInventory();
        return (a.container == playerInventory) == (b.container == playerInventory);
    }

    /** L'inventaire du joueur local, ou {@code null} si aucun joueur (ne devrait pas arriver ici). */
    private static Inventory playerInventory() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null ? player.getInventory() : null;
    }

    /** Règle partagée PUSH.2/PULL.2 : clic gauche (tout) si le curseur est vide et que ça suffit, sinon droit (moitié). */
    private static boolean shouldGrabAll(ItemStack carried, int sourceCount, int need) {
        return carried.isEmpty() && sourceCount <= need;
    }

    private static boolean targetIsFull(Slot target) {
        ItemStack stack = target.getItem();
        return !stack.isEmpty() && stack.getCount() >= target.getMaxStackSize(stack);
    }

    private static boolean hoveredHasRoom(Slot hovered) {
        ItemStack stack = hovered.getItem();
        return stack.isEmpty() || stack.getCount() < hovered.getMaxStackSize(stack);
    }

    private static int stackCount(Slot slot) {
        return slot.getItem().getCount();
    }

    /** Même Item ET mêmes composants — les deux vérifications, jamais une seule (voir mécanique 2). */
    private static boolean sameItemAndComponents(ItemStack a, ItemStack b) {
        return ItemStack.isSameItem(a, b) && ItemStack.isSameItemSameComponents(a, b);
    }

    /**
     * Réinitialiser l'état du glissé après un clic normal (qui ne fait pas partie du glissé).
     */
    @Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z", at = @At("HEAD"))
    private void onMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        dragButton = -1;
        lastDraggedSlot = null;
        rmbOriginSlot = null;
        rmbOriginClicked = false;
        dragCarriedAtStart = false;
    }

    /**
     * Retrouve le numéro de la case dans le menu et déclenche le clic.
     *
     * <h2>Appel direct, jamais de réflexion</h2>
     *
     * <p>Cette classe EST un mixin sur {@code AbstractContainerScreen} : Mixin fusionne son
     * bytecode dans la classe cible elle-même, {@code slotClicked} (protégée) est donc un appel
     * direct depuis ici, exactement comme n'importe quelle autre méthode héritée — au même titre que
     * {@link #menu}/{@link #hoveredSlot} ci-dessus, lus par {@code @Shadow} sans réflexion non plus.
     * Une version précédente passait par {@code Class.getDeclaredMethod} + {@code setAccessible},
     * enveloppée dans un {@code catch (Exception e) {}} muet — si l'appel avait échoué pour
     * n'importe quelle raison, rien ne l'aurait jamais dit, et toute la fonctionnalité serait restée
     * silencieusement inerte.
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
