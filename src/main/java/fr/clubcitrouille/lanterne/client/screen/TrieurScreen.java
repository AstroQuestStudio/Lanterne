package fr.clubcitrouille.lanterne.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.Contents;
import fr.clubcitrouille.lanterne.content.trieur.TrieurMenu;

/**
 * L'écran du Trieur : même famille visuelle que {@link Phare} — panneau sombre, liseré ambré — pour
 * la même raison qu'elle : un joueur qui ouvre un bloc de ce mod doit reconnaître un mod de ce mod.
 *
 * <h2>Le piège que {@link Phare} a déjà payé, à ne pas repayer ici</h2>
 *
 * <p>Le vrai point d'accroche du fond est {@link #extractContents}, jamais une méthode qui
 * s'appellerait {@code extractBackground} ou {@code renderBg} : voir la javadoc de classe de
 * {@link Phare} pour la preuve complète, prise au {@code javap} sur le jar client 26.3 réel. Le
 * panneau se peint ici en coordonnées ABSOLUES, avant l'appel à {@code super.extractContents}, pour
 * la même raison — cette dernière peint d'abord les widgets (notre bouton), puis les cases, et un
 * fond peint après les recouvrirait.
 *
 * <h2>Un seul widget cliquable, le reste est vanilla</h2>
 *
 * <p>Les quatorze cases réelles (cinq du hopper, neuf du filtre) se dessinent par le mécanisme
 * standard de {@code AbstractContainerScreen} — {@link TrieurMenu} les a déclarées, rien de plus à
 * faire ici. Seul le bouton de bascule liste blanche / liste noire est un vrai {@code AbstractWidget}
 * (un {@link Button}, comme le « Confirmer » de {@link Phare}) : survol, clic et infobulle passent
 * par le mécanisme déjà éprouvé plutôt que par une détection de clic à la main.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public class TrieurScreen extends AbstractContainerScreen<TrieurMenu> {
    private static final int PANEL = 0xE0141418;
    private static final int EDGE = 0xFF2A2A32;
    private static final int AMBER = 0xFFFFC857;
    private static final int DIM = 0xFF8A8A92;

    /**
     * Le liseré plus sombre du creux d'une case — {@link #EDGE} sert de fond de case (déjà plus
     * clair que {@link #PANEL}, c'est pour ça qu'il dessinait le cadre du panneau) et cette teinte
     * quasi noire l'encadre, pour que chaque case se lise comme un creux plutôt que comme une bosse.
     */
    private static final int SLOT_EDGE = 0xFF0A0A0D;

    private static final int IMAGE_WIDTH = 176;
    private static final int IMAGE_HEIGHT = 200;

    private Button toggle;

    public TrieurScreen(TrieurMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, IMAGE_WIDTH, IMAGE_HEIGHT);
        this.inventoryLabelY = 108;
    }

    /**
     * Contrairement à {@code Phare.onRegisterScreens}, {@code Contents.TRIEUR_MENU} est un {@code
     * MenuType} NEUF, encore inconnu de {@code MenuScreens} au moment où cet évènement se déclenche :
     * {@code register()} n'a donc aucune raison de lever — sa garde ({@code javap} sur {@code
     * RegisterMenuScreensEvent}) ne refuse qu'une clé déjà prise, jamais une clé neuve.
     */
    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(Contents.TRIEUR_MENU.get(), TrieurScreen::new);
    }

    @Override
    protected void init() {
        super.init();
        this.toggle = this.addRenderableWidget(Button.builder(modeLabel(), button -> onToggle())
                .bounds(this.leftPos + 106, this.topPos + 53, 62, 20)
                .build());
    }

    @Override
    public void containerTick() {
        super.containerTick();
        this.toggle.setMessage(modeLabel());
    }

    private Component modeLabel() {
        return Component.literal(this.menu.whitelist() ? "Liste blanche" : "Liste noire");
    }

    /**
     * Envoie le clic au serveur par le canal de bouton de menu déjà présent dans le jeu — voir la
     * javadoc de {@link TrieurMenu} pour la preuve que ce canal existe bien dans ce moteur. Le
     * résultat revient ensuite par le {@code DataSlot} du menu ; {@link #containerTick} le reflète.
     */
    private void onToggle() {
        if (this.minecraft != null && this.minecraft.gameMode != null) {
            this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, TrieurMenu.BUTTON_BASCULE);
        }
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        int left = this.leftPos;
        int top = this.topPos;

        graphics.fill(left - 1, top - 1, left + IMAGE_WIDTH + 1, top + IMAGE_HEIGHT + 1, EDGE);
        graphics.fill(left, top, left + IMAGE_WIDTH, top + IMAGE_HEIGHT, PANEL);
        graphics.fill(left, top, left + IMAGE_WIDTH, top + 2, AMBER);

        graphics.text(this.font, "TRIEUR", left + 10, top + 9, AMBER, false);
        graphics.text(this.font, "Filtre (glisser un objet, un exemplaire par case)",
                left + 8, top + 33, DIM, false);

        drawSlotFrames(graphics);
        super.extractContents(graphics, mouseX, mouseY, partial);
    }

    /**
     * Le cadre en creux de chaque case du menu — hopper, filtre ET inventaire du joueur — sans quoi
     * les objets flottent sur le fond uni du panneau sans aucune limite visible. C'est exactement le
     * retour du joueur sur la capture d'écran qui a motivé ce correctif : une pépite de charbon, des
     * icônes d'inventaire et des lingots qui semblaient flotter au hasard, faute de case visible
     * autour d'eux.
     *
     * <p>Peint ICI, avant {@code super.extractContents}, en coordonnées ABSOLUES comme le reste de
     * cette méthode — {@code Slot.x}/{@code Slot.y} (des {@code int final}, vérifiés au {@code javap}
     * sur le jar client 26.3 réel) sont des coordonnées LOCALES au panneau que vanilla ne traduit
     * qu'À L'INTÉRIEUR de {@code super.extractContents} (poussée de matrice puis
     * {@code translate(leftPos, topPos)}, avant {@code extractSlots}) : il faut donc rajouter
     * {@code leftPos}/{@code topPos} à la main ici, exactement comme pour le panneau et le bandeau
     * au-dessus, sous peine de peindre les cadres dans le coin supérieur gauche de l'écran au lieu de
     * sous les cases.
     */
    private void drawSlotFrames(GuiGraphicsExtractor graphics) {
        int left = this.leftPos;
        int top = this.topPos;
        for (Slot slot : this.menu.slots) {
            int x = left + slot.x;
            int y = top + slot.y;
            graphics.fill(x - 1, y - 1, x + 17, y + 17, SLOT_EDGE);
            graphics.fill(x, y, x + 16, y + 16, EDGE);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // Le titre vanilla est déjà remplacé par "TRIEUR" ci-dessus ; seule l'étiquette de
        // l'inventaire du joueur reste à peindre — même choix que Phare, même raison.
        graphics.text(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY,
                DIM, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
