package fr.clubcitrouille.lanterne.client.shop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import fr.clubcitrouille.lanterne.content.shop.Quote;
import fr.clubcitrouille.lanterne.content.shop.Till;
import fr.clubcitrouille.lanterne.content.shop.TradeAsk;
import fr.clubcitrouille.lanterne.content.shop.TradeEcho;

/**
 * Le comptoir : l'écran de la boutique, en trois colonnes.
 *
 * <h2>La même langue visuelle que les cadrans</h2>
 *
 * <p>Cet écran reprend trait pour trait la grammaire de {@code client.Dials} : le même noir bleuté, le
 * même ambre, une <b>barre de familles</b> à gauche, le <b>contenu</b> au centre, un <b>panneau de
 * détail</b> à droite, un liseré ambre en haut du panneau et une ligne d'aide en bas. Rien n'est
 * emprunté à l'interface de vanilla — ni bouton, ni cadre, ni champ de saisie — et ce n'est pas une
 * coquetterie : les composants de vanilla sont dessinés pour des menus d'options, avec leurs marges et
 * leur relief propres, et deux écrans du même mod qui ne se ressemblent pas donnent l'impression de
 * deux mods.
 *
 * <h2>Pourquoi tout est calculé, et rien n'est en dur</h2>
 *
 * <p>Un panneau de taille fixe est un panneau qui déborde. Entre un joueur à 1280×720 avec une échelle
 * d'interface de 3 et un joueur à 3840×2160 en échelle 2, la surface utile va du simple au quadruple.
 * Toute la géométrie est donc <b>dérivée</b> de {@code this.width} et {@code this.height} dans
 * {@link #layout()} : le panneau s'étire jusqu'à une borne haute pour ne pas devenir une affiche, se
 * resserre jusqu'à une borne basse pour rester lisible, et la grille recalcule son <b>nombre de
 * colonnes et de lignes</b> à chaque fois. Sur un écran étroit, la colonne de détail maigrit avant
 * que la grille ne perde une colonne — c'est la grille qui porte l'information, le détail ne fait que
 * la commenter.
 *
 * <p>{@code init()} est rappelé par le jeu à chaque redimensionnement de la fenêtre : la disposition
 * suit donc en direct, y compris pendant qu'on tire le coin de la fenêtre.
 *
 * <h2>Ce que l'écran promet au joueur</h2>
 *
 * <ol>
 *   <li><b>Le total est écrit avant de valider</b>, et c'est exactement ce qui sera débité. Le prix
 *       est figé pour toute la transaction côté serveur — voir {@code content.shop.Till} —, donc le
 *       nombre affiché ne peut pas bouger entre l'affichage et le clic.</li>
 *   <li><b>Ce qu'on ne peut pas faire se voit avant de l'essayer.</b> Le bouton devient sourd, et la
 *       ligne au-dessus dit pourquoi : il manque tant d'argent, tant de place, tant d'objets.</li>
 *   <li><b>Le refus du serveur est écrit en toutes lettres</b>, avec le chiffre exact. Un « échec »
 *       sans nombre oblige le joueur à deviner, et il devinera mal.</li>
 * </ol>
 *
 * <h2>L'argent n'est jamais un objet</h2>
 *
 * <p>Il n'y a pas de case de monnaie dans cet écran, et il n'y en aura pas : le solde est un nombre
 * tenu par le serveur, affiché en haut à droite. Voir {@code content.shop.Ledger} pour les trois
 * raisons pour lesquelles une pièce-objet est une mauvaise idée.
 */
public final class Counter extends Screen {
    // --- La palette, celle des cadrans -------------------------------------

    private static final int SCRIM = 0xC8000000;
    private static final int PANEL = 0xF2141418;
    private static final int SIDE = 0xFF101014;
    private static final int EDGE = 0xFF2A2A32;
    private static final int RAIL = 0xFF1C1C22;
    private static final int AMBER = 0xFFFFC857;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;
    private static final int FAINT = 0xFF5A5A62;
    private static final int HOVER = 0x20FFC857;
    private static final int PICKED = 0x2EFFC857;
    private static final int CELL = 0x12FFFFFF;
    private static final int GOOD = 0xFF6BCB77;
    private static final int BAD = 0xFFE06C75;

    // --- Les bornes de la disposition --------------------------------------

    private static final int HEADER = 34;
    private static final int FOOTER = 22;
    private static final int SEARCH = 24;
    private static final int TAB = 24;
    private static final int AISLE = 14;
    private static final int CELL_H = 26;
    /** Largeur visée d'une case. La largeur réelle divise exactement la grille. */
    private static final int CELL_WISH = 136;

    // --- Géométrie, recalculée à chaque disposition ------------------------

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int sidebar;
    private int detail;
    private int gridLeft;
    private int gridRight;
    private int gridTop;
    private int gridBottom;
    private int columns;
    private int lines;
    private int cellW;

    // --- État de l'écran ---------------------------------------------------

    private boolean selling;
    private String search = "";
    private boolean typing = true;
    private String aisle;
    private int aisleScroll;
    private int page;
    private Identifier picked;
    private int lot = 1;
    private boolean lotAll;

    private final List<Identifier> shown = new ArrayList<>();
    private final List<String> aisles = new ArrayList<>();
    private final Map<Identifier, String> names = new HashMap<>();
    private final Map<Identifier, ItemStack> icons = new HashMap<>();
    private boolean stale = true;

    public Counter() {
        super(Component.literal("Boutique"));
    }

    // --- Disposition -------------------------------------------------------

    @Override
    protected void init() {
        layout();
        this.stale = true;
    }

    /**
     * Toute la géométrie, dérivée de la taille de la fenêtre.
     *
     * <p>L'ordre des décisions compte. On fixe d'abord le panneau, puis on prélève la barre de gauche
     * et la colonne de détail — les deux seules zones de largeur presque fixe —, et ce qui reste va à
     * la grille, qui s'y adapte en changeant son nombre de colonnes. L'inverse — dimensionner la
     * grille puis répartir le reste — donnerait des colonnes à moitié vides sur les écrans larges.
     */
    private void layout() {
        int margin = this.height < 300 ? 6 : 16;
        this.panelW = clamp(this.width - 2 * margin, 340, 760);
        // Le plancher de hauteur n'est pas arbitraire : c'est celui sous lequel la colonne de détail
        // ne peut plus loger son bloc d'actions ET une ligne de message. Le jeu garantit au moins 240
        // points de haut en coordonnées d'interface, quelle que soit l'échelle — il est donc toujours
        // atteignable, et ne sert que de garde-fou.
        this.panelH = clamp(this.height - 2 * margin, 220, 460);
        // Un plancher plus grand que la fenêtre donnerait une origine négative, donc un panneau qui
        // dépasse des deux côtés — c'est ce qu'a montré la vérification de la disposition sur toutes
        // les largeurs de 320 à 3840. La fenêtre a toujours le dernier mot.
        this.panelW = Math.min(this.panelW, this.width);
        this.panelH = Math.min(this.panelH, this.height);
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = (this.height - this.panelH) / 2;

        this.sidebar = this.panelW >= 600 ? 126 : this.panelW >= 460 ? 104 : 84;
        this.detail = this.panelW >= 600 ? 196 : this.panelW >= 460 ? 160 : 132;

        // La marchandise passe avant le commentaire. Si la place manque pour une case entière, on
        // amaigrit d'abord la colonne de détail, puis la barre des rayons — jamais la grille. Les
        // deux planchers, 96 et 72, sont ceux sous lesquels le texte de ces colonnes cesse d'être
        // lisible ; la vérification hors du jeu dit qu'on ne les atteint qu'en 320 points de large,
        // c'est-à-dire à la plus petite fenêtre que le jeu accepte de dessiner.
        int spare = this.panelW - this.sidebar - this.detail - 24;
        if (spare < CELL_WISH) {
            this.detail = Math.max(96, this.detail - (CELL_WISH - spare));
            spare = this.panelW - this.sidebar - this.detail - 24;
        }
        if (spare < CELL_WISH) {
            this.sidebar = Math.max(72, this.sidebar - (CELL_WISH - spare));
        }

        this.gridLeft = this.panelX + this.sidebar + 10;
        this.gridRight = this.panelX + this.panelW - this.detail - 12;
        this.gridTop = this.panelY + HEADER + SEARCH + 4;
        this.gridBottom = this.panelY + this.panelH - FOOTER - 16;

        // Le maximum n'est PAS pris avec la largeur visée : une case plus large que la grille
        // déborderait sur la colonne de détail. Mieux vaut une case étroite au nom tronqué.
        int usable = Math.max(60, this.gridRight - this.gridLeft);
        this.columns = Math.max(1, usable / CELL_WISH);
        this.cellW = usable / this.columns;
        this.lines = Math.max(1, (this.gridBottom - this.gridTop) / CELL_H);
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private int perPage() {
        return Math.max(1, this.columns * this.lines);
    }

    // --- Le filtrage -------------------------------------------------------

    /**
     * Reconstruit la liste affichée.
     *
     * <p>Appelée quand un filtre change, jamais à chaque image : {@code getHoverName} traverse les
     * composants de la pile et alloue, et le faire cent vingt fois par image pour rien serait
     * exactement le genre de gaspillage que ce mod passe son temps à supprimer ailleurs.
     */
    private void refresh() {
        this.stale = false;
        this.shown.clear();
        this.aisles.clear();

        TreeSet<String> found = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        String needle = this.search.trim().toLowerCase(Locale.ROOT);
        List<Quote> quotes = Slate.all();
        List<Quote> kept = new ArrayList<>(quotes.size());
        for (Quote quote : quotes) {
            if (this.selling && !quote.bought()) {
                continue;
            }
            found.add(quote.category());
            if (this.aisle != null && !this.aisle.equalsIgnoreCase(quote.category())) {
                continue;
            }
            if (!needle.isEmpty() && !matches(quote, needle)) {
                continue;
            }
            kept.add(quote);
        }
        kept.sort((left, right) -> {
            int byAisle = String.CASE_INSENSITIVE_ORDER.compare(left.category(), right.category());
            return byAisle != 0 ? byAisle
                    : String.CASE_INSENSITIVE_ORDER.compare(nameOf(left.item()), nameOf(right.item()));
        });
        for (Quote quote : kept) {
            this.shown.add(quote.item());
        }
        this.aisles.addAll(found);

        int pages = Math.max(1, (this.shown.size() + perPage() - 1) / perPage());
        this.page = clamp(this.page, 0, pages - 1);
        if (this.picked != null && !this.shown.contains(this.picked)) {
            this.picked = null;
        }
    }

    private boolean matches(Quote quote, String needle) {
        return nameOf(quote.item()).toLowerCase(Locale.ROOT).contains(needle)
                || quote.item().getPath().contains(needle)
                || quote.category().toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Le nom affiché d'un objet, calculé une fois et retenu. */
    private String nameOf(Identifier id) {
        return this.names.computeIfAbsent(id, key -> icon(key).getHoverName().getString());
    }

    /** Une pile d'exposition, allouée une fois par article. */
    private ItemStack icon(Identifier id) {
        return this.icons.computeIfAbsent(id, key -> {
            Item item = BuiltInRegistries.ITEM.getValue(key);
            return item == Items.AIR ? new ItemStack(Items.BARRIER) : new ItemStack(item);
        });
    }

    private Item itemOf(Identifier id) {
        return BuiltInRegistries.ITEM.getValue(id);
    }

    // --- Ce que le joueur peut faire, maintenant ---------------------------

    /** Combien d'unités le joueur possède, telles que la caisse les accepte. */
    private int held(Identifier id) {
        LocalPlayer player = this.minecraft == null ? null : this.minecraft.player;
        return player == null ? 0 : Till.held(player, itemOf(id));
    }

    /** Combien d'unités le joueur peut encore ranger. */
    private int room(Identifier id) {
        LocalPlayer player = this.minecraft == null ? null : this.minecraft.player;
        return player == null ? 0 : Till.room(player, icon(id));
    }

    /**
     * La quantité réellement demandée.
     *
     * <p>« Tout » n'est pas un nombre : c'est une <em>intention</em>, qui se recalcule à chaque image
     * parce que le solde, la place et le prix bougent. La recalculer plutôt que la figer au clic évite
     * le cas désagréable où le bouton dit « tout » et où le total correspond à l'inventaire d'il y a
     * dix secondes.
     */
    private int lot() {
        if (this.picked == null) {
            return 0;
        }
        Quote quote = Slate.find(this.picked);
        if (quote == null) {
            return 0;
        }
        int ceiling = Slate.batch();
        if (!this.lotAll) {
            return Math.min(this.lot, ceiling);
        }
        if (this.selling) {
            return Math.min(held(this.picked), ceiling);
        }
        long unit = quote.buy();
        long afford = unit <= 0L ? 0L : Slate.balance() / unit;
        return (int) Math.max(0L, Math.min(Math.min(afford, room(this.picked)), ceiling));
    }

    private long unitPrice(Quote quote) {
        return this.selling ? quote.sell() : quote.buy();
    }

    /** {@code null} si l'opération est possible, sinon la raison — celle qui s'affiche. */
    private String blocked(Quote quote, int count) {
        if (count <= 0) {
            return "Choisis une quantité.";
        }
        if (this.selling) {
            if (!quote.bought()) {
                return "La boutique ne reprend pas cet article.";
            }
            int have = held(this.picked);
            if (have < count) {
                return "Il t'en manque " + (count - have) + " (les objets enchantés, renommés ou"
                        + " abîmés ne comptent pas).";
            }
            return null;
        }
        long total = quote.buy() * (long) count;
        if (total > Slate.balance()) {
            return "Il te manque " + Slate.money(total - Slate.balance()) + ".";
        }
        int space = room(this.picked);
        if (space < count) {
            return "Pas assez de place : " + (count - space) + " unité(s) ne rentreraient pas.";
        }
        return null;
    }

    // --- Le dessin ---------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partial) {
        super.extractRenderState(graphics, mouseX, mouseY, partial);
        if (this.stale) {
            refresh();
        }

        final int x = this.panelX;
        final int y = this.panelY;
        final int right = x + this.panelW;
        final int bottom = y + this.panelH;

        graphics.fill(0, 0, this.width, this.height, SCRIM);
        graphics.fill(x - 1, y - 1, right + 1, bottom + 1, EDGE);
        graphics.fill(x, y, right, bottom, PANEL);
        graphics.fill(x, y, x + this.sidebar, bottom, SIDE);
        graphics.fill(x, y, right, y + 2, AMBER);

        drawHeader(graphics, x, y, right);
        graphics.fill(x + this.sidebar, y + 2, x + this.sidebar + 1, bottom, EDGE);
        graphics.fill(right - this.detail - 1, y + HEADER, right - this.detail, bottom - FOOTER, EDGE);
        graphics.fill(x + this.sidebar, y + HEADER - 1, right, y + HEADER, EDGE);
        graphics.fill(x + this.sidebar, bottom - FOOTER, right, bottom - FOOTER + 1, EDGE);

        drawTabs(graphics, x, y, mouseX, mouseY);
        drawAisles(graphics, x, y, bottom, mouseX, mouseY);
        drawSearch(graphics, y, mouseX, mouseY);
        drawGrid(graphics, mouseX, mouseY);
        drawPager(graphics, bottom, mouseX, mouseY);
        drawDetail(graphics, right - this.detail + 10, y + HEADER + 8, this.detail - 22,
                bottom - FOOTER, mouseX, mouseY);

        String hint = this.panelW >= 520
                ? "clic pour choisir  ·  molette pour changer de page  ·  Entrée pour valider  ·  Échap pour fermer"
                : "clic  ·  molette  ·  Entrée  ·  Échap";
        graphics.text(this.font, hint, x + this.sidebar + 12, bottom - FOOTER + 7, FAINT, false);
    }

    private void drawHeader(GuiGraphicsExtractor graphics, int x, int y, int right) {
        graphics.text(this.font, "LANTERNE", x + 12, y + 13, AMBER, false);
        String money = Slate.money(Slate.balance());
        int moneyWidth = this.font.width(money);
        graphics.text(this.font, money, right - 12 - moneyWidth, y + 13, AMBER, false);
        String label = "solde";
        graphics.text(this.font, label, right - 18 - moneyWidth - this.font.width(label), y + 13,
                FAINT, false);
    }

    private void drawTabs(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
        drawTab(graphics, x, y + HEADER + 4, "ACHETER", !this.selling, mouseX, mouseY);
        drawTab(graphics, x, y + HEADER + 4 + TAB, "VENDRE", this.selling, mouseX, mouseY);
    }

    private void drawTab(GuiGraphicsExtractor graphics, int x, int top, String label, boolean on,
            int mouseX, int mouseY) {
        int left = x + 4;
        int right = x + this.sidebar - 5;
        boolean over = inside(mouseX, mouseY, left, top, right, top + TAB - 4);
        graphics.fill(left, top, right, top + TAB - 4, on ? PICKED : over ? HOVER : RAIL);
        if (on) {
            graphics.fill(left, top, left + 2, top + TAB - 4, AMBER);
        }
        graphics.text(this.font, label, left + 10, top + 6, on ? AMBER : over ? TEXT : DIM, false);
    }

    private void drawAisles(GuiGraphicsExtractor graphics, int x, int y, int bottom, int mouseX,
            int mouseY) {
        int top = y + HEADER + 4 + 2 * TAB + 6;
        graphics.fill(x + 4, top - 4, x + this.sidebar - 5, top - 3, EDGE);
        graphics.text(this.font, "RAYONS", x + 8, top, FAINT, false);
        top += 12;

        int room = Math.max(1, (bottom - FOOTER - 4 - top) / AISLE);
        List<String> list = new ArrayList<>(this.aisles.size() + 1);
        list.add(null);
        list.addAll(this.aisles);
        this.aisleScroll = clamp(this.aisleScroll, 0, Math.max(0, list.size() - room));

        for (int seat = 0; seat < room && seat + this.aisleScroll < list.size(); seat++) {
            String name = list.get(seat + this.aisleScroll);
            int line = top + seat * AISLE;
            boolean on = name == null ? this.aisle == null : name.equalsIgnoreCase(this.aisle);
            boolean over = inside(mouseX, mouseY, x + 4, line, x + this.sidebar - 5, line + AISLE - 2);
            if (on) {
                graphics.fill(x + 4, line, x + this.sidebar - 5, line + AISLE - 2, PICKED);
            } else if (over) {
                graphics.fill(x + 4, line, x + this.sidebar - 5, line + AISLE - 2, HOVER);
            }
            String label = name == null ? "Tout" : name;
            graphics.text(this.font,
                    this.font.plainSubstrByWidth(label, this.sidebar - 18), x + 10, line + 2,
                    on ? AMBER : TEXT, false);
        }
        if (list.size() > room) {
            graphics.text(this.font, "molette", x + 8, bottom - FOOTER - 12, FAINT, false);
        }
    }

    private void drawSearch(GuiGraphicsExtractor graphics, int y, int mouseX, int mouseY) {
        int top = y + HEADER + 6;
        int left = this.gridLeft;
        int right = this.gridRight;
        boolean over = inside(mouseX, mouseY, left, top, right, top + SEARCH - 8);
        graphics.fill(left, top, right, top + SEARCH - 8, this.typing ? RAIL : PANEL);
        graphics.fill(left, top, left + 1, top + SEARCH - 8, this.typing ? AMBER : EDGE);
        graphics.fill(left, top, right, top + 1, EDGE);
        graphics.fill(left, top + SEARCH - 9, right, top + SEARCH - 8, EDGE);

        String count = this.shown.size() + " art.";
        int countWidth = this.font.width(count);
        graphics.text(this.font, count, right - 6 - countWidth, top + 4, FAINT, false);

        int room = right - left - 20 - countWidth;
        if (this.search.isEmpty()) {
            graphics.text(this.font, this.typing ? "tape pour chercher…" : "rechercher…",
                    left + 8, top + 4, over || this.typing ? DIM : FAINT, false);
        } else {
            graphics.text(this.font, this.font.plainSubstrByWidth(this.search, room, true),
                    left + 8, top + 4, TEXT, false);
        }
        if (this.typing && (System.currentTimeMillis() / 480L) % 2L == 0L) {
            int caret = left + 8 + Math.min(room,
                    this.font.width(this.font.plainSubstrByWidth(this.search, room, true)));
            graphics.fill(caret + 1, top + 3, caret + 2, top + 12, AMBER);
        }
    }

    private void drawGrid(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (this.shown.isEmpty()) {
            graphics.text(this.font,
                    Slate.empty() ? "Le catalogue est vide." : "Aucun article ne correspond.",
                    this.gridLeft + 4, this.gridTop + 6, DIM, false);
            if (!Slate.empty()) {
                graphics.text(this.font, "Essaie un autre rayon, ou vide la recherche.",
                        this.gridLeft + 4, this.gridTop + 18, FAINT, false);
            }
            return;
        }
        int first = this.page * perPage();
        for (int seat = 0; seat < perPage() && first + seat < this.shown.size(); seat++) {
            Identifier id = this.shown.get(first + seat);
            Quote quote = Slate.find(id);
            if (quote == null) {
                continue;
            }
            int column = seat % this.columns;
            int line = seat / this.columns;
            int cx = this.gridLeft + column * this.cellW;
            int cy = this.gridTop + line * CELL_H;
            drawCell(graphics, quote, id, cx, cy, mouseX, mouseY);
        }
    }

    private void drawCell(GuiGraphicsExtractor graphics, Quote quote, Identifier id, int cx, int cy,
            int mouseX, int mouseY) {
        int cw = this.cellW - 4;
        int ch = CELL_H - 3;
        boolean on = id.equals(this.picked);
        boolean over = inside(mouseX, mouseY, cx, cy, cx + cw, cy + ch);
        graphics.fill(cx, cy, cx + cw, cy + ch, on ? PICKED : over ? HOVER : CELL);
        if (on) {
            graphics.fill(cx, cy, cx + 2, cy + ch, AMBER);
        }
        graphics.item(icon(id), cx + 5, cy + 3);

        long unit = unitPrice(quote);
        String price = unit > 0L ? Slate.money(unit) : "non repris";
        int priceWidth = this.font.width(price);
        int textLeft = cx + 26;
        int room = Math.max(8, cw - 30 - 4);

        graphics.text(this.font, this.font.plainSubstrByWidth(nameOf(id), room), textLeft, cy + 3,
                on ? AMBER : TEXT, false);
        graphics.text(this.font, price, textLeft, cy + 13,
                unit > 0L ? (this.selling ? GOOD : TEXT) : FAINT, false);

        // La flèche de cote, calée à droite : elle dit d'un coup d'oeil ce que le marché a fait de
        // cet article depuis son prix d'ancrage.
        int trend = quote.trend();
        if (trend != 0 && cw > priceWidth + 60) {
            String mark = (trend > 0 ? "▲ " : "▼ ") + Math.abs(trend) / 10 + "%";
            graphics.text(this.font, mark, cx + cw - 4 - this.font.width(mark), cy + 13,
                    trend > 0 ? BAD : GOOD, false);
        }
        if (this.selling) {
            int have = held(id);
            if (have > 0 && cw > 70) {
                String count = "×" + have;
                graphics.text(this.font, count, cx + cw - 4 - this.font.width(count), cy + 3,
                        DIM, false);
            }
        }
    }

    private void drawPager(GuiGraphicsExtractor graphics, int bottom, int mouseX, int mouseY) {
        int pages = Math.max(1, (this.shown.size() + perPage() - 1) / perPage());
        int line = bottom - FOOTER - 13;
        String label = (this.page + 1) + " / " + pages;
        int mid = (this.gridLeft + this.gridRight) / 2;
        int width = this.font.width(label);
        graphics.text(this.font, label, mid - width / 2, line, pages > 1 ? DIM : FAINT, false);

        boolean overLeft = inside(mouseX, mouseY, mid - width / 2 - 20, line - 2,
                mid - width / 2 - 6, line + 10);
        boolean overRight = inside(mouseX, mouseY, mid + width / 2 + 6, line - 2,
                mid + width / 2 + 20, line + 10);
        graphics.text(this.font, "‹", mid - width / 2 - 16, line,
                this.page > 0 ? overLeft ? AMBER : DIM : FAINT, false);
        graphics.text(this.font, "›", mid + width / 2 + 10, line,
                this.page < pages - 1 ? overRight ? AMBER : DIM : FAINT, false);
    }

    /**
     * Le panneau de droite.
     *
     * <p>Il se dessine en deux temps : l'information depuis le haut, les actions depuis le <b>bas</b>.
     * C'est ce qui rend la colonne indifférente à la hauteur de la fenêtre — le bouton de validation
     * est toujours au même endroit par rapport au bas du panneau, et c'est l'information qui se fait
     * couper si la place manque, jamais l'inverse.
     */
    private void drawDetail(GuiGraphicsExtractor graphics, int x, int y, int width, int bottom,
            int mouseX, int mouseY) {
        int actionTop = bottom - 26;
        int totalTop = actionTop - 16;
        int lotTop = totalTop - 22;
        int messageTop = lotTop - 34;
        // Tout ce qui est écrit depuis le haut s'arrête ici. La zone de message et les actions sont
        // ancrées au bas et ne cèdent jamais : mieux vaut perdre une ligne de commentaire qu'un
        // bouton de validation.
        int limit = messageTop - 4;

        if (this.picked == null) {
            graphics.text(this.font, this.selling ? "VENDRE" : "ACHETER", x, y, AMBER, false);
            int line = y + 14;
            for (String part : wrap(this.selling
                    ? "Choisis un article à gauche. La boutique reprend tout ce qui est intact et sans enchantement."
                    : "Choisis un article à gauche. Le stock de la boutique est sans fond ; c'est elle qui fixe le cours.",
                    width)) {
                graphics.text(this.font, part, x, line, DIM, false);
                line += 10;
            }
            drawTicket(graphics, x, messageTop, width);
            return;
        }

        Quote quote = Slate.find(this.picked);
        if (quote == null) {
            return;
        }

        // L'objet en grand : deux fois la taille d'une case, parce que c'est le sujet de la colonne.
        // Le motif — empiler la pose, translater, mettre à l'échelle, dessiner en (0, 0) — est celui
        // qu'emploie « OverlayRecipeComponent » de vanilla en 26.1, à l'échelle près.
        // « GuiItemRenderState » recopie la matrice au moment de l'ajout : la transformation tient.
        graphics.pose().pushMatrix();
        graphics.pose().translate((float) x, (float) y);
        graphics.pose().scale(2.0F, 2.0F);
        graphics.item(icon(this.picked), 0, 0);
        graphics.pose().popMatrix();

        graphics.text(this.font, this.font.plainSubstrByWidth(nameOf(this.picked), width - 38),
                x + 38, y + 2, AMBER, false);
        graphics.text(this.font, this.font.plainSubstrByWidth(quote.category(), width - 38),
                x + 38, y + 14, FAINT, false);

        int line = y + 38;
        long unit = unitPrice(quote);
        if (line + 10 <= limit) {
            graphics.text(this.font, this.selling ? "Rachat à l'unité" : "Prix à l'unité", x, line,
                    DIM, false);
            line += 11;
        }
        if (line + 10 <= limit) {
            graphics.text(this.font, unit > 0L ? Slate.money(unit) : "non repris", x, line,
                    unit > 0L ? TEXT : FAINT, false);
            line += 15;
        }

        int trend = quote.trend();
        if (line + 10 <= limit) {
            String cote = trend == 0 ? "au prix d'ancrage"
                    : (trend > 0 ? "▲ +" : "▼ -") + Math.abs(trend) / 10 + ","
                            + Math.abs(trend) % 10 + " % sur l'ancre";
            graphics.text(this.font, cote, x, line, trend == 0 ? FAINT : trend > 0 ? BAD : GOOD,
                    false);
            line += 13;
        }
        if (line + 10 <= limit) {
            String stock = this.selling
                    ? "tu en as " + held(this.picked)
                    : "de la place pour " + room(this.picked);
            graphics.text(this.font, stock, x, line, DIM, false);
            line += 13;
        }
        if (line + 10 <= limit && !this.selling && quote.bought()) {
            graphics.text(this.font, "repris " + Slate.money(quote.sell()), x, line, FAINT, false);
        }

        drawTicket(graphics, x, messageTop, width);
        drawLots(graphics, x, lotTop, width, mouseX, mouseY);

        int count = lot();
        long total = unit * (long) count;
        String refusal = blocked(quote, count);
        graphics.text(this.font, "Total", x, totalTop, DIM, false);
        String amount = Slate.money(Math.max(0L, total));
        graphics.text(this.font, amount, x + width - this.font.width(amount), totalTop,
                refusal == null ? AMBER : BAD, false);

        drawAction(graphics, x, actionTop, width, count, refusal, mouseX, mouseY);
    }

    private void drawLots(GuiGraphicsExtractor graphics, int x, int top, int width, int mouseX,
            int mouseY) {
        String[] labels = {"×1", "×8", "×64", "tout"};
        int slot = width / 4;
        for (int i = 0; i < 4; i++) {
            int left = x + i * slot;
            int right = left + slot - 3;
            boolean on = i == 3 ? this.lotAll : !this.lotAll && this.lot == lotOf(i);
            boolean over = inside(mouseX, mouseY, left, top, right, top + 14);
            graphics.fill(left, top, right, top + 14, on ? PICKED : over ? HOVER : RAIL);
            if (on) {
                graphics.fill(left, top, right, top + 1, AMBER);
            }
            int textWidth = this.font.width(labels[i]);
            graphics.text(this.font, labels[i], left + (slot - 3 - textWidth) / 2, top + 3,
                    on ? AMBER : over ? TEXT : DIM, false);
        }
        String count = "quantité : " + lot();
        graphics.text(this.font, count, x, top - 12, DIM, false);
    }

    private static int lotOf(int index) {
        return switch (index) {
            case 0 -> 1;
            case 1 -> 8;
            default -> 64;
        };
    }

    private void drawAction(GuiGraphicsExtractor graphics, int x, int top, int width, int count,
            String refusal, int mouseX, int mouseY) {
        boolean can = refusal == null && count > 0;
        boolean over = inside(mouseX, mouseY, x, top, x + width, top + 18);
        graphics.fill(x, top, x + width, top + 18, can ? over ? PICKED : RAIL : PANEL);
        graphics.fill(x, top, x + width, top + 1, can ? AMBER : EDGE);
        graphics.fill(x, top + 17, x + width, top + 18, can ? AMBER : EDGE);
        String label = this.selling ? "VENDRE " + count : "ACHETER " + count;
        int textWidth = this.font.width(label);
        graphics.text(this.font, label, x + (width - textWidth) / 2, top + 5,
                can ? AMBER : FAINT, false);
    }

    /**
     * La ligne de retour : soit le refus prévisible, soit le dernier mot du serveur.
     *
     * <p>Le refus prévisible — « il te manque tant » — s'affiche <em>avant</em> de cliquer et vient du
     * client. Le mot du serveur s'affiche après, et il prime : c'est le seul qui fasse autorité, et
     * les deux peuvent différer si quelque chose a bougé entre-temps.
     */
    private void drawTicket(GuiGraphicsExtractor graphics, int x, int top, int width) {
        TradeEcho echo = Slate.ticket();
        if (echo != null) {
            int colour = echo.outcome() == Till.Outcome.DONE ? GOOD : BAD;
            int line = top;
            for (String part : wrap(say(echo), width)) {
                graphics.text(this.font, part, x, line, colour, false);
                line += 10;
                // Deux lignes au plus : c'est la hauteur reservee entre le bloc d'information et
                // la rangee des quantites. Une troisieme les chevaucherait sur une petite fenetre.
                if (line > top + 10) {
                    return;
                }
            }
            return;
        }
        if (this.picked == null) {
            return;
        }
        Quote quote = Slate.find(this.picked);
        if (quote == null) {
            return;
        }
        String refusal = blocked(quote, lot());
        if (refusal == null) {
            return;
        }
        int line = top;
        for (String part : wrap(refusal, width)) {
            graphics.text(this.font, part, x, line, DIM, false);
            line += 10;
            if (line > top + 10) {
                return;
            }
        }
    }

    /** La phrase du serveur, composée ici — voir {@code content.shop.TradeEcho}. */
    private String say(TradeEcho echo) {
        return switch (echo.outcome()) {
            case DONE -> (this.selling ? "Vendu " : "Acheté ") + echo.count() + " × "
                    + nameOf(echo.item()) + " pour " + Slate.money(echo.total()) + ".";
            case NO_MONEY -> "Refusé : il te manque " + Slate.money(echo.detail()) + ".";
            case NO_ROOM -> "Refusé : " + echo.detail() + " unité(s) ne rentrent pas dans ton sac.";
            case NO_ITEMS -> "Refusé : il t'en manque " + echo.detail()
                    + ". Les objets enchantés, renommés ou abîmés ne comptent pas.";
            case UNKNOWN -> "Cet article n'est plus au catalogue.";
            case NOT_BOUGHT -> "La boutique ne reprend pas cet article.";
            case FULL_PURSE -> "Ton compte est au plafond : tu ne peux plus être payé.";
            case BAD_COUNT -> "Quantité refusée : le lot maximal du serveur est " + echo.detail()
                    + ".";
            case OFF -> "La boutique est fermée sur ce serveur.";
        };
    }

    /** Coupe un paragraphe en lignes qui tiennent dans la colonne — repris de {@code Dials}. */
    private List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (this.font.width(candidate) > width && current.length() > 0) {
                out.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    private static boolean inside(int px, int py, int left, int top, int right, int bottom) {
        return px >= left && px <= right && py >= top && py <= bottom;
    }

    // --- Les entrées -------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        int x = this.panelX;
        int y = this.panelY;
        int right = x + this.panelW;
        int bottom = y + this.panelH;

        // Onglets
        for (int i = 0; i < 2; i++) {
            int top = y + HEADER + 4 + i * TAB;
            if (inside(mouseX, mouseY, x + 4, top, x + this.sidebar - 5, top + TAB - 4)) {
                boolean wanted = i == 1;
                if (wanted != this.selling) {
                    this.selling = wanted;
                    this.page = 0;
                    this.picked = null;
                    this.stale = true;
                    Slate.clearTicket();
                }
                return true;
            }
        }

        // Rayons
        int aisleTop = y + HEADER + 4 + 2 * TAB + 18;
        int room = Math.max(1, (bottom - FOOTER - 4 - aisleTop) / AISLE);
        List<String> list = new ArrayList<>(this.aisles.size() + 1);
        list.add(null);
        list.addAll(this.aisles);
        for (int seat = 0; seat < room && seat + this.aisleScroll < list.size(); seat++) {
            int line = aisleTop + seat * AISLE;
            if (inside(mouseX, mouseY, x + 4, line, x + this.sidebar - 5, line + AISLE - 2)) {
                this.aisle = list.get(seat + this.aisleScroll);
                this.page = 0;
                this.stale = true;
                return true;
            }
        }

        // Champ de recherche
        int searchTop = y + HEADER + 6;
        if (inside(mouseX, mouseY, this.gridLeft, searchTop, this.gridRight,
                searchTop + SEARCH - 8)) {
            this.typing = true;
            return true;
        }

        // Pagination
        int pages = Math.max(1, (this.shown.size() + perPage() - 1) / perPage());
        int pagerLine = bottom - FOOTER - 13;
        int mid = (this.gridLeft + this.gridRight) / 2;
        int width = this.font.width((this.page + 1) + " / " + pages);
        if (inside(mouseX, mouseY, mid - width / 2 - 20, pagerLine - 2, mid - width / 2 - 6,
                pagerLine + 10)) {
            this.page = Math.max(0, this.page - 1);
            return true;
        }
        if (inside(mouseX, mouseY, mid + width / 2 + 6, pagerLine - 2, mid + width / 2 + 20,
                pagerLine + 10)) {
            this.page = Math.min(pages - 1, this.page + 1);
            return true;
        }

        // Grille
        if (inside(mouseX, mouseY, this.gridLeft, this.gridTop, this.gridRight, this.gridBottom)) {
            int column = (mouseX - this.gridLeft) / Math.max(1, this.cellW);
            int line = (mouseY - this.gridTop) / CELL_H;
            if (column >= 0 && column < this.columns && line >= 0 && line < this.lines) {
                int index = this.page * perPage() + line * this.columns + column;
                if (index >= 0 && index < this.shown.size()) {
                    this.picked = this.shown.get(index);
                    this.typing = false;
                    Slate.clearTicket();
                    return true;
                }
            }
        }

        // Colonne de détail
        if (this.picked != null) {
            int detailX = right - this.detail + 10;
            int detailWidth = this.detail - 22;
            int actionTop = bottom - FOOTER - 26;
            int lotTop = actionTop - 38;
            int slot = detailWidth / 4;
            for (int i = 0; i < 4; i++) {
                int left = detailX + i * slot;
                if (inside(mouseX, mouseY, left, lotTop, left + slot - 3, lotTop + 14)) {
                    this.lotAll = i == 3;
                    if (!this.lotAll) {
                        this.lot = lotOf(i);
                    }
                    Slate.clearTicket();
                    return true;
                }
            }
            if (inside(mouseX, mouseY, detailX, actionTop, detailX + detailWidth, actionTop + 18)) {
                commit();
                return true;
            }
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int step = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
        if (step == 0) {
            return false;
        }
        if (mouseX < this.panelX + this.sidebar) {
            this.aisleScroll = Math.max(0, this.aisleScroll + step);
            return true;
        }
        // Le catalogue engendré compte plus de mille articles, soit une quarantaine de pages. Une
        // page par cran de molette rendrait « Tout » impraticable ; Maj en saute dix. Le rayon et la
        // recherche restent les vrais outils de navigation, mais on ne doit pas être puni de s'en
        // passer.
        int pages = Math.max(1, (this.shown.size() + perPage() - 1) / perPage());
        this.page = clamp(this.page + step * (shiftHeld() ? 10 : 1), 0, pages - 1);
        return true;
    }

    /**
     * La touche Maj est-elle enfoncée ?
     *
     * <p>{@code Screen.hasShiftDown()} n'existe plus en 26.1 : les modificateurs voyagent désormais
     * dans l'évènement d'entrée, et {@code mouseScrolled} n'en reçoit pas. On interroge donc la
     * fenêtre directement, ce que fait aussi le jeu là où il a le même besoin.
     */
    private boolean shiftHeld() {
        if (this.minecraft == null) {
            return false;
        }
        return InputConstants.isKeyDown(this.minecraft.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(this.minecraft.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            // Échap efface d'abord la recherche : sortir d'un écran parce qu'on voulait sortir d'un
            // filtre est la petite frustration classique de ce genre d'interface.
            if (!this.search.isEmpty()) {
                this.search = "";
                this.page = 0;
                this.stale = true;
                return true;
            }
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (!this.search.isEmpty()) {
                this.search = this.search.substring(0, this.search.length() - 1);
                this.page = 0;
                this.stale = true;
            }
            this.typing = true;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            commit();
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            this.selling = !this.selling;
            this.page = 0;
            this.picked = null;
            this.stale = true;
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (!event.isAllowedChatCharacter()) {
            return false;
        }
        if (this.search.length() >= 48) {
            return true;
        }
        this.search = this.search + event.codepointAsString();
        this.typing = true;
        this.page = 0;
        this.stale = true;
        return true;
    }

    /**
     * Envoie la demande au serveur.
     *
     * <p>Aucune vérification n'est <em>nécessaire</em> ici — le serveur refera tout —, mais le test
     * local évite d'envoyer un paquet qui ne peut que revenir en refus, et surtout il donne au joueur
     * la raison tout de suite plutôt qu'après un aller-retour.
     */
    private void commit() {
        if (this.picked == null || this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        Quote quote = Slate.find(this.picked);
        if (quote == null) {
            return;
        }
        int count = lot();
        if (blocked(quote, count) != null) {
            return;
        }
        ClientPacketDistributor.sendToServer(new TradeAsk(
                this.selling ? TradeAsk.Verb.SELL : TradeAsk.Verb.BUY, this.picked, count));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Slate.clearTicket();
        super.onClose();
    }
}
