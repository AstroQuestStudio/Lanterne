package fr.clubcitrouille.lanterne.client.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.reseau.Bordereau;
import fr.clubcitrouille.lanterne.content.reseau.GuichetMenu;
import fr.clubcitrouille.lanterne.content.reseau.Reseaux;

/**
 * L'écran du Guichet : le réseau entier, filtré par un nom, livré par un clic.
 *
 * <p>Suit le patron de {@link Phare} — lire sa Javadoc de classe pour le piège moteur déjà découvert
 * ({@code extractContents}, jamais {@code extractBackground}) et pour le choix de vrais
 * {@code AbstractWidget} plutôt qu'une détection de clic à la main, tous deux repris ici sans
 * changement.
 *
 * <h2>Un menu vanilla, un écran neuf — mais un {@code MenuType} tout neuf aussi, à la différence de Phare</h2>
 *
 * <p>Phare remplace l'écran d'un {@code MenuType} vanilla déjà pris ; {@code GuichetMenu} est un type
 * neuf, que personne n'a enregistré avant nous — {@link #onRegisterScreens} peut donc appeler
 * {@code event.register(...)} directement, sans le contournement par réflexion que Phare doit faire
 * pour écraser {@code MenuType.BEACON}.
 *
 * <h2>La liste vit dans {@code GuichetMenu}, pas ici</h2>
 *
 * <p>{@link #register} branche {@code Reseaux.clientSide} pour écrire chaque instantané reçu dans
 * {@code GuichetMenu#setObjets} — jamais dans un champ de cet écran. Le paquet peut arriver AVANT que
 * cet écran ne soit construit (l'ordre exact d'arrivée entre le paquet d'ouverture vanilla et notre
 * propre instantané n'est pas garanti), alors que le menu, lui, existe dès l'ouverture — voir
 * {@code GuichetMenu} pour le détail.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public class GuichetScreen extends AbstractContainerScreen<GuichetMenu> {
    private static final int PANEL = 0xE0141418;
    private static final int EDGE = 0xFF2A2A32;
    private static final int AMBER = 0xFFFFC857;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;
    private static final int FAINT = 0xFF5A5A62;

    private static final int IMAGE_WIDTH = 238;
    private static final int IMAGE_HEIGHT = 232;
    private static final int ROWS_PER_PAGE = 7;
    private static final int ROW_HEIGHT = 20;
    private static final int LIST_TOP = 42;

    /** La demande par clic : une pile pleine de cet objet, ou tout ce qu'il reste s'il y en a moins. */
    private static final int DEMANDE_PAR_CLIC = 64;

    private EditBox recherche;
    private Button precedente;
    private Button suivante;
    private final List<StockRow> lignes = new ArrayList<>();

    private List<Bordereau.Entree> filtrees = List.of();
    private int page;
    private int dernierHash;

    public GuichetScreen(GuichetMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, IMAGE_WIDTH, IMAGE_HEIGHT);
        this.inventoryLabelY = IMAGE_HEIGHT + 4; // hors-champ : ce menu ne montre aucun emplacement.
    }

    /**
     * Enregistre l'écran neuf pour son {@code MenuType} — voir la Javadoc de classe pour pourquoi
     * ceci n'a pas besoin du contournement par réflexion de {@code Phare#onRegisterScreens}.
     */
    @SubscribeEvent
    public static void onRegisterScreens(RegisterMenuScreensEvent event) {
        event.register(Reseaux.GUICHET_MENU.get(), GuichetScreen::new);
    }

    /**
     * Branche la réception des instantanés — voir la Javadoc de classe. Appelé depuis le constructeur
     * de {@code Lanterne}, derrière la garde de distribution cliente, exactement comme
     * {@code Projection.register}.
     */
    public static void register(IEventBus modBus) {
        Reseaux.clientSide(payload -> {
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.containerMenu instanceof GuichetMenu menu) {
                menu.setObjets(payload.objets());
            }
        });
    }

    @Override
    protected void init() {
        super.init();
        this.recherche = new EditBox(this.font, this.leftPos + 9, this.topPos + 20,
                IMAGE_WIDTH - 18, 14, Component.literal("Recherche"));
        this.recherche.setHint(Component.literal("Rechercher…"));
        this.recherche.setMaxLength(64);
        this.recherche.setResponder(texte -> {
            this.page = 0;
            rafraichirLignes();
        });
        this.addRenderableWidget(this.recherche);

        int basY = this.topPos + LIST_TOP + ROWS_PER_PAGE * ROW_HEIGHT + 6;
        this.precedente = this.addRenderableWidget(Button.builder(Component.literal("<"),
                button -> changerPage(-1)).bounds(this.leftPos + 9, basY, 20, 16).build());
        this.suivante = this.addRenderableWidget(Button.builder(Component.literal(">"),
                button -> changerPage(1)).bounds(this.leftPos + IMAGE_WIDTH - 29, basY, 20, 16).build());

        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            StockRow ligne = new StockRow(this.leftPos + 9, this.topPos + LIST_TOP + i * ROW_HEIGHT,
                    IMAGE_WIDTH - 18);
            this.lignes.add(ligne);
            this.addRenderableWidget(ligne);
        }
        this.dernierHash = -1;
        rafraichirLignes();
    }

    private void changerPage(int delta) {
        this.page = Math.max(0, this.page + delta);
        rafraichirLignes();
    }

    /**
     * Recalcule intégralement la page visible depuis {@code menu.objets()} et le texte de recherche.
     *
     * <p>Sans état intermédiaire à faire dériver : chaque appel repart des données du menu, filtre,
     * trie par nom, borne la page, et ne fait vivre que les {@link StockRow} de la page courante.
     */
    private void rafraichirLignes() {
        String requete = this.recherche == null ? "" : this.recherche.getValue().trim().toLowerCase(Locale.ROOT);
        List<Bordereau.Entree> source = this.menu.objets();
        List<Bordereau.Entree> resultat = new ArrayList<>();
        for (Bordereau.Entree entree : source) {
            if (requete.isEmpty() || nomAffiche(entree.item()).toLowerCase(Locale.ROOT).contains(requete)) {
                resultat.add(entree);
            }
        }
        resultat.sort(Comparator.comparing(entree -> nomAffiche(entree.item())));
        this.filtrees = resultat;

        int pages = Math.max(1, (this.filtrees.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        this.page = Math.clamp(this.page, 0, pages - 1);

        int debut = this.page * ROWS_PER_PAGE;
        for (int i = 0; i < ROWS_PER_PAGE; i++) {
            int index = debut + i;
            this.lignes.get(i).montrer(index < this.filtrees.size() ? this.filtrees.get(index) : null);
        }
        this.precedente.active = this.page > 0;
        this.suivante.active = this.page < pages - 1;
    }

    private static String nomAffiche(Item item) {
        return item.getDefaultInstance().getHoverName().getString();
    }

    /**
     * Resynchronise sur ce que le serveur vient d'envoyer — appelée à chaque tick de conteneur comme
     * {@code Phare#containerTick}, mais ici pour détecter un instantané changé plutôt qu'un choix
     * confirmé. Le hash évite de refaire tri et filtre à chaque frame quand rien n'a bougé.
     */
    @Override
    public void containerTick() {
        super.containerTick();
        int hash = this.menu.objets().hashCode();
        if (hash != this.dernierHash) {
            this.dernierHash = hash;
            rafraichirLignes();
        }
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        int left = this.leftPos;
        int top = this.topPos;

        graphics.fill(left - 1, top - 1, left + IMAGE_WIDTH + 1, top + IMAGE_HEIGHT + 1, EDGE);
        graphics.fill(left, top, left + IMAGE_WIDTH, top + IMAGE_HEIGHT, PANEL);
        graphics.fill(left, top, left + IMAGE_WIDTH, top + 2, AMBER);

        graphics.text(this.font, "GUICHET", left + 9, top + 9, AMBER, false);
        String compte = this.filtrees.size() + " type(s)";
        graphics.text(this.font, compte, left + IMAGE_WIDTH - 9 - this.font.width(compte), top + 9, DIM, false);

        if (this.filtrees.isEmpty()) {
            graphics.text(this.font, "Rien à livrer — le réseau est vide, ou le filtre ne trouve rien.",
                    left + 9, top + LIST_TOP + 2, FAINT, false);
        }

        int pages = Math.max(1, (this.filtrees.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        String pagination = (this.page + 1) + " / " + pages;
        int basY = top + LIST_TOP + ROWS_PER_PAGE * ROW_HEIGHT + 6;
        graphics.text(this.font, pagination, left + IMAGE_WIDTH / 2 - this.font.width(pagination) / 2,
                basY + 4, DIM, false);

        super.extractContents(graphics, mouseX, mouseY, partial);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // Aucune étiquette d'inventaire : ce menu ne montre aucun emplacement, voir la Javadoc de classe.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Une ligne du stock : icône, nom, quantité — un vrai {@code AbstractWidget}, cliquable, comme
     * {@code Phare.EffectIcon}.
     */
    private final class StockRow extends AbstractWidget {
        private Bordereau.Entree entree;

        StockRow(int x, int y, int width) {
            super(x, y, width, ROW_HEIGHT - 2, Component.empty());
        }

        void montrer(Bordereau.Entree entree) {
            this.entree = entree;
            this.visible = entree != null;
            this.active = entree != null;
            this.setMessage(entree == null ? Component.empty() : entree.item().getDefaultInstance().getHoverName());
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (this.entree == null || GuichetScreen.this.minecraft == null
                    || GuichetScreen.this.minecraft.getConnection() == null) {
                return;
            }
            int quantite = Math.min(DEMANDE_PAR_CLIC, this.entree.quantite());
            GuichetScreen.this.minecraft.getConnection().send(
                    new Bordereau.Demande(this.entree.item(), quantite));
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                float partial) {
            if (this.entree == null) {
                return;
            }
            int x = this.getX();
            int y = this.getY();
            if (this.isHovered) {
                graphics.fill(x - 2, y - 2, x + this.getWidth() + 2, y + this.getHeight() + 2, EDGE);
            }
            ItemStack apercu = this.entree.item().getDefaultInstance();
            graphics.item(apercu, x, y);
            String nom = apercu.getHoverName().getString();
            int largeurQuantite = GuichetScreen.this.font.width(String.valueOf(this.entree.quantite()));
            int largeurNomMax = this.getWidth() - 20 - largeurQuantite - 6;
            while (GuichetScreen.this.font.width(nom) > largeurNomMax && nom.length() > 1) {
                nom = nom.substring(0, nom.length() - 1);
            }
            graphics.text(GuichetScreen.this.font, nom, x + 20, y + 4, TEXT, false);
            graphics.text(GuichetScreen.this.font, String.valueOf(this.entree.quantite()),
                    x + this.getWidth() - largeurQuantite, y + 4, AMBER, false);
            if (this.isHovered) {
                graphics.setTooltipForNextFrame(GuichetScreen.this.font,
                        this.entree.item().getDefaultInstance().getHoverName().copy()
                                .append(" — clic : demander jusqu'à " + DEMANDE_PAR_CLIC),
                        mouseX, mouseY);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, this.getMessage());
        }
    }
}
