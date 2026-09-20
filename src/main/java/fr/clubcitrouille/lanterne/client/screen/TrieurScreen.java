package fr.clubcitrouille.lanterne.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

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

        super.extractContents(graphics, mouseX, mouseY, partial);
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
