package fr.clubcitrouille.lanterne.client.shop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.lwjgl.sdl.SDLScancode;

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
    /**
     * L'orangé des prix amputés par une franchise.
     *
     * <p>Ni le vert d'un bon prix, ni le rouge d'un refus : un prix retenu n'est pas une erreur, et
     * le peindre en rouge ferait croire à une panne. Il n'est pas non plus le plein tarif, et le
     * peindre en vert serait mentir par omission. L'orangé est la troisième chose, et il tient dans
     * la palette des cadrans parce qu'il est un ambre assombri.
     */
    private static final int WORN = 0xFFE0964A;

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

    // --- La géométrie partagée ---------------------------------------------
    //
    // Ces six méthodes existent parce que le dessin et le clic calculaient les MÊMES rectangles,
    // chacun de son côté, à partir de constantes recopiées. Trois d'entre eux ne se ressemblaient
    // déjà plus tout à fait — la rangée des quantités était écrite « actionTop - 16 - 22 » ici et
    // « actionTop - 38 » là, ce qui est vrai aujourd'hui et faux au premier changement. Une zone
    // cliquable qui ne recouvre pas ce qu'on voit est le pire défaut d'une interface dessinée à la
    // main, parce qu'il ne se voit pas : le bouton est là, il ne répond simplement pas.

    private int detailX() {
        return this.panelX + this.panelW - this.detail + 10;
    }

    private int detailW() {
        return this.detail - 22;
    }

    /** Le bas de la zone utile de la colonne de détail. */
    private int detailBottom() {
        return this.panelY + this.panelH - FOOTER;
    }

    private int actionTop() {
        return detailBottom() - 26;
    }

    private int lotTop() {
        return actionTop() - 38;
    }

    /**
     * La colonne de détail est-elle trop étroite pour les libellés complets ?
     *
     * <p>Le seuil vient d'une mesure, pas d'un jugement : {@code tools/mesure_police.py} lit la
     * police du jeu dans le jar et rend la largeur exacte de chaque chaîne. La plus longue de la
     * colonne, « de la place pour 1234 », fait 112 points. En dessous de 116, il faut les versions
     * courtes.
     *
     * <p>C'est ce que l'ancienne vérification de disposition — 968 275 combinaisons — ne pouvait pas
     * voir : elle contrôlait la <em>position</em> des blocs, jamais la <em>largeur du texte</em>. À
     * 340 points de large, « ▲ +30,5 % sur l'ancre » réclame 107 points dans une colonne qui en
     * offre 74, et débordait donc de vingt et un points hors du panneau, sur le voile noir.
     */
    private boolean tight() {
        return detailW() < 116;
    }

    /** Une chaîne coupée à la largeur disponible. Le filet de sécurité de toute la colonne. */
    private String fit(String text, int width) {
        return this.font.plainSubstrByWidth(text, Math.max(8, width));
    }

    /**
     * La gouttière entre deux boutons de quantité.
     *
     * <p>Mesurée : « tout » fait vingt points dans la police du jeu. À la colonne la plus étroite —
     * 96 points, soit 74 utiles —, un créneau en fait dix-huit, et trois points de gouttière
     * volaient les deux dernières lettres. On rend donc la gouttière quand la place manque, puis on
     * change de mot : « max » fait dix-huit points et tient exactement. Un bouton collé à son voisin
     * se voit ; un mot tronqué ne se comprend pas.
     */
    private int lotGutter() {
        int slot = detailW() / 4;
        return slot >= this.font.width("tout") + 6 ? 3 : slot >= this.font.width("tout") + 1 ? 1 : 0;
    }

    /** Le rectangle d'un bouton de quantité : {@code {gauche, droite}}. */
    private int[] lotBox(int index) {
        int slot = detailW() / 4;
        int left = detailX() + index * slot;
        return new int[] {left, left + slot - lotGutter()};
    }

    /** Les étiquettes des quantités, dans la version qui tient dans la place disponible. */
    private String[] lotLabels() {
        int slot = detailW() / 4;
        if (slot >= this.font.width("tout") + 6) {
            return new String[] {"×1", "×8", "×64", "tout"};
        }
        return slot - lotGutter() >= this.font.width("tout")
                ? new String[] {"1", "8", "64", "tout"}
                : new String[] {"1", "8", "64", "max"};
    }

    private int pagerLine() {
        return this.panelY + this.panelH - FOOTER - 13;
    }

    private int searchTop() {
        return this.panelY + HEADER + 6;
    }

    /** Le haut de la première ligne de rayon. */
    private int aisleTop() {
        return this.panelY + HEADER + 4 + 2 * TAB + 18;
    }

    /** Combien de rayons tiennent dans la colonne de gauche. */
    private int aisleRoom() {
        return Math.max(1, (this.panelY + this.panelH - FOOTER - 4 - aisleTop()) / AISLE);
    }

    /** Les rayons affichés, « Tout » compris — {@code null} est « Tout ». */
    private List<String> aisleList() {
        List<String> list = new ArrayList<>(this.aisles.size() + 1);
        list.add(null);
        list.addAll(this.aisles);
        return list;
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

    /**
     * Le prix d'une unité, tel qu'il sera vraiment appliqué à <em>ce</em> joueur.
     *
     * <p>À la vente, ce n'est pas {@code quote.sell()} — le cours du marché — mais
     * {@code quote.mine()}, le cours diminué de ce que les franchises de ce joueur retiennent. Les
     * deux nombres voyagent ensemble dans le paquet précisément pour que l'écran puisse montrer le
     * second sans cesser de connaître le premier : le joueur doit pouvoir distinguer « le marché a
     * baissé » de « j'ai trop vendu », parce que les deux appellent des conduites opposées.
     */
    private long unitPrice(Quote quote) {
        return this.selling ? quote.mine() : quote.buy();
    }

    /**
     * La couleur d'une cote, selon ce qu'elle veut dire <b>pour le joueur, dans l'onglet courant</b>.
     *
     * <p>La première version peignait toute hausse en rouge et toute baisse en vert, sur les deux
     * onglets. C'est juste quand on achète et exactement faux quand on vend : un cours qui monte est
     * une bonne nouvelle pour celui qui apporte sa marchandise. L'écran disait donc « attention » au
     * moment précis où il aurait dû dire « c'est le moment ».
     */
    private int trendColour(int trend) {
        if (trend == 0) {
            return FAINT;
        }
        return (this.selling ? trend > 0 : trend < 0) ? GOOD : BAD;
    }

    /** {@code null} si l'opération est possible, sinon la raison — celle qui s'affiche. */
    private String blocked(Quote quote, int count) {
        if (count <= 0) {
            // « Tout » qui vaut zéro n'est pas un oubli de l'utilisateur : c'est un refus déguisé.
            // Répondre « choisis une quantité » à quelqu'un qui vient de cliquer sur « tout » est
            // la réponse la plus agaçante qu'une interface puisse faire.
            if (!this.lotAll) {
                return "Choisis une quantité.";
            }
            if (this.selling) {
                return "Tu n'as rien de cet article à vendre — ou rien d'assez intact.";
            }
            long unit = quote.buy();
            if (unit > Slate.balance()) {
                return "Il te manque " + Slate.money(unit - Slate.balance())
                        + " pour en acheter un seul.";
            }
            return "Ton sac est plein : pas la place d'une seule unité.";
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
        drawDetail(graphics, detailX(), y + HEADER + 8, detailW(), mouseX, mouseY);

        // L'aide dit ce qui ne se devine pas : que Tab change d'onglet, et que la molette ne fait
        // pas la même chose selon la colonne qu'elle survole. Le reste — cliquer, valider — se
        // devine, et l'écrire volerait la place de ce qui ne se devine pas.
        String hint = this.panelW >= 600
                ? "Tab : acheter / vendre   ·   molette : page, ou quantité à droite"
                        + "   ·   Entrée : valider   ·   Échap : fermer"
                : this.panelW >= 460
                ? "Tab : onglet   ·   molette : page ou quantité   ·   Entrée   ·   Échap"
                : "Tab  ·  molette  ·  Entrée  ·  Échap";
        graphics.text(this.font,
                this.font.plainSubstrByWidth(hint, right - (x + this.sidebar) - 18),
                x + this.sidebar + 12, bottom - FOOTER + 7, FAINT, false);
    }

    /**
     * L'éclat qui suit une transaction : {@code +1} réussie, {@code -1} refusée, {@code 0} rien.
     *
     * <p>Sans lui, deux achats identiques à la suite écrivent deux fois le même message, au même
     * endroit, de la même couleur — et le second est <b>rigoureusement invisible</b>. Le joueur
     * reclique en croyant que rien n'est parti, et achète deux fois. C'est le défaut le plus courant
     * des écrans de boutique, il ne coûte qu'un horodatage, et il ne se voit qu'en s'en servant :
     * aucune vérification de disposition ne l'aurait trouvé.
     *
     * <p>Six cents millisecondes : assez pour être vu, assez court pour ne pas rester dans l'œil
     * quand on enchaîne les achats.
     */
    private int flash() {
        TradeEcho echo = Slate.ticket();
        if (echo == null || Slate.ticketAge() > 600L) {
            return 0;
        }
        return echo.outcome() == Till.Outcome.DONE ? 1 : -1;
    }

    private void drawHeader(GuiGraphicsExtractor graphics, int x, int y, int right) {
        graphics.text(this.font, "LANTERNE", x + 12, y + 13, AMBER, false);
        // Le solde est le premier endroit où l'œil va vérifier qu'il s'est passé quelque chose.
        int beat = flash();
        String money = Slate.money(Slate.balance());
        int moneyWidth = this.font.width(money);
        graphics.text(this.font, money, right - 12 - moneyWidth, y + 13,
                beat > 0 ? GOOD : beat < 0 ? BAD : AMBER, false);
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
        int top = aisleTop();
        graphics.fill(x + 4, top - 16, x + this.sidebar - 5, top - 15, EDGE);
        graphics.text(this.font, "RAYONS", x + 8, top - 12, FAINT, false);

        int room = aisleRoom();
        List<String> list = aisleList();
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

        // Le plancher n'est pas décoratif : sur une grille très étroite, cette soustraction devient
        // négative, et « plainSubstrByWidth » d'une largeur négative rend la chaîne vide — le champ
        // de recherche paraissait alors ne rien enregistrer.
        int room = Math.max(8, right - left - 20 - countWidth);
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
        boolean worn = this.selling && quote.withheld();
        String price = unit > 0L ? Slate.money(unit) : "non repris";
        int priceWidth = this.font.width(price);
        int textLeft = cx + 26;
        int room = Math.max(8, cw - 30 - 4);

        graphics.text(this.font, this.font.plainSubstrByWidth(nameOf(id), room), textLeft, cy + 3,
                on ? AMBER : TEXT, false);
        // Un prix en orangé n'est pas le plein tarif : la franchise de ce joueur est entamée sur
        // cet article. Une couleur suffit à le signaler dans une case de vingt-six points de haut ;
        // le pourcentage exact est dans la colonne de détail, où il y a la place de l'expliquer.
        graphics.text(this.font, price, textLeft, cy + 13,
                unit <= 0L ? FAINT : worn ? WORN : this.selling ? GOOD : TEXT, false);

        // La flèche de cote, calée à droite : elle dit d'un coup d'oeil ce que le marché a fait de
        // cet article depuis son prix d'ancrage.
        int trend = quote.trend();
        if (trend != 0 && cw > priceWidth + 60) {
            String mark = (trend > 0 ? "▲ " : "▼ ") + Math.abs(trend) / 10 + "%";
            graphics.text(this.font, mark, cx + cw - 4 - this.font.width(mark), cy + 13,
                    trendColour(trend), false);
        }
        // Ce qu'on possède déjà, sur LES DEUX onglets. La première version ne le montrait qu'à la
        // vente ; savoir qu'on a déjà trois cents pavés dans son sac est au moins aussi utile au
        // moment d'en acheter.
        int have = held(id);
        if (have > 0 && cw > 70) {
            String count = "×" + have;
            graphics.text(this.font, count, cx + cw - 4 - this.font.width(count), cy + 3,
                    DIM, false);
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
    private void drawDetail(GuiGraphicsExtractor graphics, int x, int y, int width,
            int mouseX, int mouseY) {
        int actionTop = actionTop();
        int totalTop = actionTop - 16;
        int lotTop = lotTop();
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
        boolean worn = this.selling && quote.withheld();
        boolean tight = tight();
        if (line + 10 <= limit) {
            String label = this.selling ? tight ? "Rachat" : "Rachat à l'unité"
                    : tight ? "Prix" : "Prix à l'unité";
            graphics.text(this.font, fit(label, width), x, line, DIM, false);
            line += 11;
        }
        if (line + 10 <= limit) {
            graphics.text(this.font, fit(unit > 0L ? Slate.money(unit) : "non repris", width),
                    x, line, unit <= 0L ? FAINT : worn ? WORN : TEXT, false);
            line += 15;
        }

        // La franchise, et seulement quand elle est entamée. C'est la règle de cet écran : on
        // n'écrit pas une ligne pour dire qu'il ne se passe rien. Un joueur ordinaire ne verra
        // jamais ces deux lignes de sa vie, et c'est exactement le but du dispositif.
        // Le cours du marché n'est écrit que si la colonne est assez large pour le porter en
        // entier. Couper un montant est pire que ne pas l'écrire : « 1 234,5 » se lit comme un
        // nombre, et c'en est un autre. À l'étroit, il reste « ta part », qui est l'information
        // sur laquelle on agit, et « /boutique quota » pour le détail complet.
        if (worn && !tight && line + 10 <= limit) {
            graphics.text(this.font, fit("cours " + Slate.money(quote.sell()), width), x, line,
                    FAINT, false);
            line += 11;
        }
        if (worn && line + 10 <= limit) {
            graphics.text(this.font,
                    fit("ta part : " + (100 - quote.withheldPercent()) + " %", width),
                    x, line, WORN, false);
            line += 13;
        }

        int trend = quote.trend();
        if (line + 10 <= limit) {
            String arrow = (trend > 0 ? "▲ +" : "▼ -") + Math.abs(trend) / 10 + ","
                    + Math.abs(trend) % 10 + " %";
            String cote = trend == 0 ? tight ? "à l'ancre" : "au prix d'ancrage"
                    : tight ? arrow : arrow + " sur l'ancre";
            graphics.text(this.font, fit(cote, width), x, line, trendColour(trend), false);
            line += 13;
        }
        if (line + 10 <= limit) {
            String stock = this.selling
                    ? "tu en as " + held(this.picked)
                    : (tight ? "place : " : "de la place pour ") + room(this.picked);
            graphics.text(this.font, fit(stock, width), x, line, DIM, false);
            line += 13;
        }
        // Même raison que pour le cours : un montant tronqué ment.
        if (line + 10 <= limit && !tight && !this.selling && quote.bought()) {
            graphics.text(this.font, fit("repris " + Slate.money(quote.mine()), width), x, line,
                    FAINT, false);
        }

        drawTicket(graphics, x, messageTop, width);
        drawLots(graphics, x, lotTop, width, mouseX, mouseY);

        int count = lot();
        long total = unit * (long) count;
        String refusal = blocked(quote, count);
        // Le montant est calé à droite, l'étiquette à gauche. Sur une colonne de 74 points, « Total »
        // et « 1 234 567 ¤ » réclament 85 points à eux deux : ils se chevauchaient. C'est
        // l'étiquette qui cède, parce que c'est le nombre qu'on est venu lire.
        String amount = Slate.money(Math.max(0L, total));
        int amountWidth = this.font.width(amount);
        if (this.font.width("Total") + 6 + amountWidth <= width) {
            graphics.text(this.font, "Total", x, totalTop, DIM, false);
        }
        graphics.text(this.font, fit(amount, width), x + width - Math.min(amountWidth, width),
                totalTop, refusal == null ? AMBER : BAD, false);

        drawAction(graphics, x, actionTop, width, count, refusal, mouseX, mouseY);
    }

    private void drawLots(GuiGraphicsExtractor graphics, int x, int top, int width, int mouseX,
            int mouseY) {
        String[] labels = lotLabels();
        for (int i = 0; i < 4; i++) {
            int[] box = lotBox(i);
            boolean on = i == 3 ? this.lotAll : !this.lotAll && this.lot == lotOf(i);
            boolean over = inside(mouseX, mouseY, box[0], top, box[1], top + 14);
            graphics.fill(box[0], top, box[1], top + 14, on ? PICKED : over ? HOVER : RAIL);
            if (on) {
                graphics.fill(box[0], top, box[1], top + 1, AMBER);
            }
            int textWidth = this.font.width(labels[i]);
            graphics.text(this.font, labels[i], box[0] + (box[1] - box[0] - textWidth) / 2, top + 3,
                    on ? AMBER : over ? TEXT : DIM, false);
        }
        String count = "quantité : " + lot();
        graphics.text(this.font, this.font.plainSubstrByWidth(count, width), x, top - 12, DIM,
                false);
    }

    private static int lotOf(int index) {
        return switch (index) {
            case 0 -> 1;
            case 1 -> 8;
            default -> 64;
        };
    }

    /**
     * Fait tourner la quantité d'un cran. Sert à la molette au-dessus de la colonne de droite.
     *
     * <p>La molette y faisait tourner les <em>pages</em> de la grille, ce qui est déroutant : on
     * survole le bloc des quantités, on tourne la molette, et c'est la liste d'à côté qui bouge. Le
     * geste naturel au-dessus d'un réglage est de régler ce réglage.
     */
    private void cycleLot(int step) {
        int at = this.lotAll ? 3 : this.lot >= 64 ? 2 : this.lot >= 8 ? 1 : 0;
        int next = Math.max(0, Math.min(3, at + step));
        this.lotAll = next == 3;
        if (!this.lotAll) {
            this.lot = lotOf(next);
        }
        Slate.clearTicket();
    }

    private void drawAction(GuiGraphicsExtractor graphics, int x, int top, int width, int count,
            String refusal, int mouseX, int mouseY) {
        boolean can = refusal == null && count > 0;
        boolean over = inside(mouseX, mouseY, x, top, x + width, top + 18);
        // L'éclat de confirmation. Le liseré du bouton vire au vert ou au rouge pendant six cents
        // millisecondes : c'est là que le regard vient de cliquer, donc là qu'il est encore.
        int beat = flash();
        int edge = beat > 0 ? GOOD : beat < 0 ? BAD : can ? AMBER : EDGE;
        graphics.fill(x, top, x + width, top + 18,
                beat > 0 ? PICKED : can ? over ? PICKED : RAIL : PANEL);
        graphics.fill(x, top, x + width, top + 1, edge);
        graphics.fill(x, top + 17, x + width, top + 18, edge);
        // Le verbe seul quand la place manque : la quantité est déjà écrite juste au-dessus, sur la
        // ligne « quantité : N ». Mieux vaut un mot entier qu'un « ACHETER 230 » amputé de son
        // dernier chiffre, qui ne dit pas seulement moins — il dit faux.
        String full = (this.selling ? "VENDRE " : "ACHETER ") + count;
        String label = this.font.width(full) + 6 <= width ? full
                : this.selling ? "VENDRE" : "ACHETER";
        String clipped = fit(label, width - 6);
        int textWidth = this.font.width(clipped);
        graphics.text(this.font, clipped, x + (width - textWidth) / 2, top + 5,
                beat > 0 ? GOOD : can ? AMBER : FAINT, false);
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
            // Rien ne bloque : la place est libre pour expliquer la seule chose que le joueur ne
            // peut pas deviner — pourquoi son prix est en orangé. Les trois longueurs sont mesurées
            // pour tenir en deux lignes dans leur colonne ; une troisième chevaucherait la rangée
            // des quantités sur une petite fenêtre.
            if (!this.selling || !quote.withheld()) {
                return;
            }
            refusal = width >= 150 ? "Tu en as trop vendu récemment. Ta part remonte d'elle-même."
                    : width >= 110 ? "Trop vendu. Ta part remonte seule."
                    : "Vends autre chose.";
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
        int aisleTop = aisleTop();
        int room = aisleRoom();
        List<String> list = aisleList();
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
        int searchTop = searchTop();
        if (inside(mouseX, mouseY, this.gridLeft, searchTop, this.gridRight,
                searchTop + SEARCH - 8)) {
            this.typing = true;
            return true;
        }

        // Pagination
        int pages = Math.max(1, (this.shown.size() + perPage() - 1) / perPage());
        int pagerLine = pagerLine();
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
            int lotTop = lotTop();
            for (int i = 0; i < 4; i++) {
                int[] box = lotBox(i);
                if (inside(mouseX, mouseY, box[0], lotTop, box[1], lotTop + 14)) {
                    this.lotAll = i == 3;
                    if (!this.lotAll) {
                        this.lot = lotOf(i);
                    }
                    Slate.clearTicket();
                    return true;
                }
            }
            int actionTop = actionTop();
            if (inside(mouseX, mouseY, detailX(), actionTop, detailX() + detailW(),
                    actionTop + 18)) {
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
        // Au-dessus de la colonne de droite, la molette règle la QUANTITÉ. C'est le geste attendu :
        // on survole un réglage, on le règle. La première version y faisait tourner les pages de la
        // grille d'à côté, ce qui donne l'impression d'avoir raté sa cible.
        if (this.picked != null && mouseX > this.panelX + this.panelW - this.detail) {
            cycleLot(-step);
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
        return InputConstants.isKeyDown(SDLScancode.SDL_SCANCODE_LSHIFT)
                || InputConstants.isKeyDown(SDLScancode.SDL_SCANCODE_RSHIFT);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == SDLScancode.SDL_SCANCODE_ESCAPE) {
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
        if (key == SDLScancode.SDL_SCANCODE_BACKSPACE) {
            if (!this.search.isEmpty()) {
                this.search = this.search.substring(0, this.search.length() - 1);
                this.page = 0;
                this.stale = true;
            }
            this.typing = true;
            return true;
        }
        if (key == SDLScancode.SDL_SCANCODE_RETURN || key == SDLScancode.SDL_SCANCODE_KP_ENTER) {
            commit();
            return true;
        }
        if (key == SDLScancode.SDL_SCANCODE_TAB) {
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
