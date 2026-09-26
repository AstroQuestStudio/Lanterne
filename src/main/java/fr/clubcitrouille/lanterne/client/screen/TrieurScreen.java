package fr.clubcitrouille.lanterne.client.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.Contents;
import fr.clubcitrouille.lanterne.content.trieur.Motif;
import fr.clubcitrouille.lanterne.content.trieur.TrieurCablage;
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
 * la même raison — cette dernière peint d'abord les widgets (nos boutons, le champ), puis les cases,
 * et un fond peint après les recouvrirait.
 *
 * <h2>Des widgets vanilla, jamais un clic géré à la main</h2>
 *
 * <p>Les vingt-trois cases réelles (cinq du hopper, dix-huit du filtre) se dessinent par le mécanisme
 * standard de {@code AbstractContainerScreen}. Le bouton de bascule, le champ de motif et le bouton
 * « + » sont de vrais {@code AbstractWidget} ({@link Button}, {@link EditBox}) : survol, clic et
 * infobulle passent par le mécanisme éprouvé. C'est aussi ce qui met cet écran à l'abri du piège
 * SDL de 26.3 (le clic gauche vaut 1, plus 0) : aucun test de numéro de bouton n'est écrit ici — le
 * retrait d'un motif est décidé côté menu ({@code TrieurMenu.clicked}), sans numéro de bouton non plus.
 *
 * <h2>Le champ de motif et son aperçu en direct</h2>
 *
 * <p>Sous le filtre : un champ texte, un bouton « + » (ou Entrée) qui envoie {@link
 * TrieurCablage.AjoutMotif}, et une ligne d'aperçu recalculée à chaque frappe — combien d'objets du
 * registre correspondent, et les premiers d'entre eux en icônes. L'aperçu utilise {@link Motif}, le
 * MÊME code que le serveur : ce qu'on voit est ce qui sera trié. Parcourir le registre (quelques
 * milliers d'objets) à chaque frappe ne coûte rien de visible ; ce n'est jamais fait par image.
 *
 * <p>Un serveur d'avant 26.3.141 ne connaît pas le paquet : le champ reste visible mais le bouton
 * est grisé, avec une infobulle qui le dit ({@code hasChannel}).
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
    /** 200 jusqu'à 26.3.140 ; +16 pour le champ de motif et son aperçu. */
    private static final int IMAGE_HEIGHT = TrieurMenu.INVENTORY_Y + 82;

    // Géométrie de la zone de motif, en coordonnées locales au panneau.
    private static final int CHAMP_Y = 85;
    private static final int CHAMP_LARGEUR = 138;
    private static final int APERCU_Y = 103;
    private static final int APERCU_ICONES = 7;

    private Button toggle;
    private EditBox champ;
    private Button ajout;

    /** Aperçu du texte en cours de frappe : total, et les premiers objets correspondants. */
    private int apercuTotal;
    /** Le texte du champ ne forme aucun motif (vide, ou seulement des espaces) : on affiche l'aide. */
    private boolean apercuVide = true;
    private final List<ItemStack> apercu = new ArrayList<>();

    /** Mémo de l'infobulle d'une case motif : son texte et son nombre de correspondances. */
    private String memoTexte = "";
    private int memoTotal;

    public TrieurScreen(TrieurMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, IMAGE_WIDTH, IMAGE_HEIGHT);
        this.inventoryLabelY = TrieurMenu.INVENTORY_Y - 10;
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
        String ancien = this.champ == null ? "" : this.champ.getValue();
        this.toggle = this.addRenderableWidget(Button.builder(modeLabel(), button -> onToggle())
                .bounds(this.leftPos + 106, this.topPos + 15, 62, 16)
                .build());
        this.champ = new EditBox(this.font, this.leftPos + 8, this.topPos + CHAMP_Y, CHAMP_LARGEUR, 14,
                Component.translatable("lanterne.trieur.motif.champ"));
        this.champ.setMaxLength(Motif.LONGUEUR_MAX);
        this.champ.setHint(Component.translatable("lanterne.trieur.motif.indice").withStyle(ChatFormatting.DARK_GRAY));
        this.champ.setResponder(this::recalculerApercu);
        this.champ.setValue(ancien);
        this.addRenderableWidget(this.champ);
        this.ajout = this.addRenderableWidget(Button.builder(Component.literal("+"), button -> ajouterMotif())
                .bounds(this.leftPos + 8 + CHAMP_LARGEUR + 4, this.topPos + CHAMP_Y - 1, IMAGE_WIDTH - 16 - CHAMP_LARGEUR - 4, 16)
                .build());
        if (!serveurConnaitLesMotifs()) {
            this.ajout.active = false;
            this.ajout.setTooltip(Tooltip.create(Component.translatable("lanterne.trieur.motif.serveur_ancien")));
        } else {
            this.ajout.setTooltip(Tooltip.create(Component.translatable("lanterne.trieur.motif.ajouter")));
        }
        recalculerApercu(this.champ.getValue());
    }

    private boolean serveurConnaitLesMotifs() {
        return this.minecraft != null && this.minecraft.getConnection() != null
                && this.minecraft.getConnection().hasChannel(TrieurCablage.AjoutMotif.TYPE);
    }

    @Override
    public void containerTick() {
        super.containerTick();
        this.toggle.setMessage(modeLabel());
    }

    private Component modeLabel() {
        return Component.translatable(this.menu.whitelist()
                ? "lanterne.trieur.liste_blanche" : "lanterne.trieur.liste_noire");
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

    /** « + » ou Entrée : envoie le motif, vide le champ. Le serveur choisit la case et répond par la synchro. */
    private void ajouterMotif() {
        String motif = Motif.nettoyer(this.champ.getValue());
        if (motif.isEmpty() || !serveurConnaitLesMotifs()) {
            return;
        }
        ClientPacketDistributor.sendToServer(new TrieurCablage.AjoutMotif(this.menu.containerId, motif));
        this.champ.setValue("");
    }

    private void recalculerApercu(String texte) {
        this.apercu.clear();
        this.apercuTotal = 0;
        Motif motif = Motif.compiler(texte);
        this.apercuVide = motif.estVide();
        if (this.apercuVide) {
            return;
        }
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR || !motif.correspond(item.builtInRegistryHolder(), null)) {
                continue;
            }
            this.apercuTotal++;
            if (this.apercu.size() < APERCU_ICONES) {
                this.apercu.add(new ItemStack(item));
            }
        }
    }

    /**
     * Le champ garde le clavier tant qu'il a le focus : sans cette garde, taper « e » dans le champ
     * fermerait l'écran (touche d'inventaire), et un chiffre échangerait une case avec la barre rapide.
     * Même règle que l'enclume vanilla ({@code AnvilScreen.keyPressed}).
     */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            return super.keyPressed(event);
        }
        if (this.champ.isFocused()) {
            // isConfirmation, jamais une constante GLFW : en 26.3 les codes de touche viennent de SDL.
            if (event.isConfirmation()) {
                ajouterMotif();
                return true;
            }
            if (this.champ.keyPressed(event) || this.champ.canConsumeInput()) {
                return true;
            }
        }
        return super.keyPressed(event);
    }

    /**
     * Un clic hors du champ lui retire le focus : sinon, après avoir tapé un motif puis cliqué une case,
     * les touches de barre rapide et d'inventaire resteraient avalées par le champ. Aucun numéro de
     * bouton testé (piège SDL de 26.3) : n'importe quel clic hors du champ suffit.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.champ != null && this.getFocused() == this.champ && !this.champ.isMouseOver(event.x(), event.y())) {
            this.setFocused(null);
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        int left = this.leftPos;
        int top = this.topPos;

        graphics.fill(left - 1, top - 1, left + IMAGE_WIDTH + 1, top + IMAGE_HEIGHT + 1, EDGE);
        graphics.fill(left, top, left + IMAGE_WIDTH, top + IMAGE_HEIGHT, PANEL);
        graphics.fill(left, top, left + IMAGE_WIDTH, top + 2, AMBER);

        graphics.text(this.font, "TRIEUR", left + 10, top + 9, AMBER, false);
        graphics.text(this.font, Component.translatable("lanterne.trieur.filtre.aide"),
                left + 8, top + 33, DIM, false);

        drawSlotFrames(graphics);
        extractApercu(graphics, mouseX, mouseY);
        super.extractContents(graphics, mouseX, mouseY, partial);
    }

    /** La ligne d'aperçu sous le champ : icônes des premiers objets, total à droite, ou l'aide si vide. */
    private void extractApercu(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = this.leftPos + 8;
        int y = this.topPos + APERCU_Y;
        if (this.apercuVide) {
            graphics.text(this.font, Component.translatable("lanterne.trieur.motif.syntaxe"), x, y + 4, DIM, false);
            return;
        }
        if (this.apercuTotal == 0) {
            graphics.text(this.font, Component.translatable("lanterne.trieur.motif.aucun"), x, y + 4, DIM, false);
            return;
        }
        for (int i = 0; i < this.apercu.size(); i++) {
            int ix = x + i * 18;
            graphics.item(this.apercu.get(i), ix, y);
            if (mouseX >= ix && mouseX < ix + 16 && mouseY >= y && mouseY < y + 16) {
                graphics.setTooltipForNextFrame(this.font, this.apercu.get(i), mouseX, mouseY);
            }
        }
        Component total = Component.translatable("lanterne.trieur.motif.total", this.apercuTotal);
        graphics.text(this.font, total, this.leftPos + IMAGE_WIDTH - 8 - this.font.width(total), y + 4,
                AMBER, false);
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
     * sous les cases. Une case de filtre qui porte un motif reçoit un fond ambré sombre : on voit d'un
     * coup d'œil quelles cases sont des familles et lesquelles des objets.
     */
    private void drawSlotFrames(GuiGraphicsExtractor graphics) {
        int left = this.leftPos;
        int top = this.topPos;
        for (Slot slot : this.menu.slots) {
            int x = left + slot.x;
            int y = top + slot.y;
            graphics.fill(x - 1, y - 1, x + 17, y + 17, SLOT_EDGE);
            graphics.fill(x, y, x + 16, y + 16, estCaseMotif(slot) ? 0xFF4A3A14 : EDGE);
        }
    }

    private static boolean estCaseMotif(Slot slot) {
        return slot.index >= TrieurMenu.HOPPER_SLOTS
                && slot.index < TrieurMenu.HOPPER_SLOTS + TrieurMenu.FILTER_SLOTS
                && Motif.estMotif(slot.getItem());
    }

    /**
     * Par-dessus l'icône d'étiquette d'une case motif : les premières lettres du motif, en demi-taille,
     * pour lire le filtre sans survoler chaque case. Appelé DANS la matrice de {@code
     * super.extractContents} : coordonnées locales, comme {@code Slot.x}.
     */
    @Override
    protected void extractSlot(GuiGraphicsExtractor graphics, Slot slot, int mouseX, int mouseY) {
        super.extractSlot(graphics, slot, mouseX, mouseY);
        if (!estCaseMotif(slot)) {
            return;
        }
        String texte = Motif.texte(slot.getItem());
        String court = texte.length() > 6 ? texte.substring(0, 6) : texte;
        graphics.pose().pushMatrix();
        graphics.pose().translate(slot.x, slot.y + 11);
        graphics.pose().scale(0.5f, 0.5f);
        graphics.fill(-1, -1, this.font.width(court) + 1, 9, 0xC0000000);
        graphics.text(this.font, court, 0, 0, AMBER, false);
        graphics.pose().popMatrix();
    }

    /** L'infobulle d'une case motif : le motif en entier, son nombre d'objets, et ce que fait un clic. */
    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        if (this.hoveredSlot == null || !estCaseMotif(this.hoveredSlot)) {
            return super.getTooltipFromContainerItem(stack);
        }
        String texte = Motif.texte(stack);
        if (!texte.equals(this.memoTexte)) {
            this.memoTexte = texte;
            this.memoTotal = compter(Motif.compiler(texte));
        }
        List<Component> lignes = new ArrayList<>();
        lignes.add(Component.translatable("lanterne.trieur.motif.infobulle", texte).withStyle(ChatFormatting.GOLD));
        lignes.add(Component.translatable("lanterne.trieur.motif.total", this.memoTotal).withStyle(ChatFormatting.GRAY));
        lignes.add(Component.translatable(Motif.estVirtuel(stack)
                ? "lanterne.trieur.motif.retirer" : "lanterne.trieur.motif.reprendre").withStyle(ChatFormatting.DARK_GRAY));
        return lignes;
    }

    private static int compter(Motif motif) {
        int total = 0;
        if (!motif.estVide()) {
            for (Item item : BuiltInRegistries.ITEM) {
                if (item != Items.AIR && motif.correspond(item.builtInRegistryHolder(), null)) {
                    total++;
                }
            }
        }
        return total;
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
