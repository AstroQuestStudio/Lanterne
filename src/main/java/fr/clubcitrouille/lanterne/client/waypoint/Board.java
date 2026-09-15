package fr.clubcitrouille.lanterne.client.waypoint;

import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import fr.clubcitrouille.lanterne.client.Fit;
import fr.clubcitrouille.lanterne.content.waypoint.Waypoint;
import fr.clubcitrouille.lanterne.content.waypoint.WaypointAsk;

/**
 * Le carnet, à l'écran.
 *
 * <h2>Ce que cette interface montre, et ce qu'elle refuse de montrer</h2>
 *
 * <p>Pas de bouton de téléportation, et il n'y en aura pas. Un repère est gratuit : lui donner le
 * pouvoir de déplacer reviendrait à offrir la distance. Il dit <em>où c'est</em> et <em>à quelle
 * distance</em> ; le chemin reste à faire.
 *
 * <p>Ce qui déplace, c'est un portail — voir {@link Gateway} et
 * {@link fr.clubcitrouille.lanterne.content.waypoint.Gate}. Il se bâtit, il coûte, et il ne relie que
 * des endroits où l'on est déjà allé. C'est une autre affaire, et un autre écran.
 *
 * <p>Chaque ligne porte une pastille de couleur, le nom, la distance <b>horizontale</b> — l'altitude
 * fausserait la lecture dans une mine — et une flèche qui pointe vers le repère <em>relativement au
 * regard du joueur</em>. Un joueur ne sait pas où est le nord ; il sait où il regarde.
 *
 * <h2>Pourquoi le dessin est fait à la main</h2>
 *
 * <p>Les composants de liste de vanilla sont conçus pour des réglages : une ligne, un libellé, un
 * bouton. Ici chaque ligne porte cinq informations et trois actions, et la lisibilité tient au fait
 * qu'elles soient alignées en colonnes. Quelques rectangles et du texte donnent un résultat plus
 * net que n'importe quel assemblage de composants — et rien à retenir pour qui lira ce fichier.
 *
 * <h2>Pourquoi les trois actions de ligne ne sont plus des boutons</h2>
 *
 * <p>Elles l'étaient, et cela imposait deux défauts qu'on ne pouvait pas corriger séparément. Le
 * premier : un carnet peut porter jusqu'à cinq repères à soi <em>et</em> trente-deux publics, soit
 * trente-sept lignes — un panneau de huit cent quatre-vingts pixels de haut, c'est-à-dire trois fois
 * l'écran. Il fallait donc une fenêtre de huit lignes et une molette. Le second : reconstruire les
 * boutons à chaque cran de molette efface la zone de saisie du nom au milieu d'une frappe.
 *
 * <p>Dessiner ces trois actions à la main et les cliquer à la main lève les deux d'un coup — c'est le
 * choix déjà fait par {@link Gateway}, pour exactement la même raison.
 */
public class Board extends Screen {
    private static final int SCRIM = 0xC8000000;
    private static final int PANEL = 0xE0141418;
    private static final int EDGE = 0xFF2A2A32;
    private static final int RAIL = 0xFF1C1C22;
    private static final int ROW = 0x30FFFFFF;
    private static final int ROW_MINE = 0x18FFC857;
    private static final int AMBER = 0xFFFFC857;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;
    private static final int FAINT = 0xFF5A5A62;
    private static final int BAD = 0xFFE06C5B;
    private static final int HOVER = 0x20FFC857;

    private static final int WIDTH = 300;
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER = 34;

    /**
     * La bande du bas : deux lignes d'explication, puis la rangée de pose.
     *
     * <p>Elle valait trente-six, et l'explication était écrite à quarante-six pixels du bas — donc
     * <b>par-dessus la dernière ligne de la liste</b>. Le défaut ne se voyait qu'avec assez de
     * repères pour que la liste descende jusque-là, ce qui est précisément le cas où l'on regarde.
     */
    private static final int FOOTER = 52;

    /** Huit lignes au plus. Au-delà, la molette — voir l'en-tête de la classe. */
    private static final int WINDOW = 8;

    private EditBox nameBox;
    private int tint = Waypoint.PALETTE[0];
    private List<Waypoint> shown = List.of();
    private int first;
    private int panelX;
    private int panelY;

    /** Ce que l'écran a à répondre au dernier geste. Vide tant qu'il n'a rien à dire. */
    private String note = "";
    private boolean noteIsBad;
    private long noteAt;

    /** L'ajusteur : la liste pleine réclame plus de haut qu'une fenêtre courte n'en offre. */
    private final Fit fit = new Fit();

    public Board() {
        super(Component.literal("Repères"));
    }

    @Override
    protected void init() {
        shown = Marks.byDistance();
        first = Math.max(0, Math.min(first, Math.max(0, shown.size() - WINDOW)));

        this.fit.measure(this.width, this.height, WIDTH, panelHeight());
        // La place disponible APRÈS réduction : centrer sur la largeur réelle décalerait le panneau
        // de tout le facteur de réduction.
        this.panelX = (this.fit.viewWidth() - WIDTH) / 2;
        this.panelY = Math.max(10, (this.fit.viewHeight() - panelHeight()) / 2);

        int left = this.panelX;
        int bottom = this.panelY + panelHeight();

        // Le nom déjà tapé survit à une reconstruction : le jeu rappelle init() au moindre
        // redimensionnement de la fenêtre, et perdre sa frappe pour avoir tiré un coin est
        // exactement le genre de détail qui fait fermer un écran.
        String kept = nameBox == null ? "" : nameBox.getValue();
        nameBox = new EditBox(this.font, left + 10, bottom - 26, 150, 16,
                Component.literal("nom"));
        nameBox.setMaxLength(Waypoint.NAME_LIMIT);
        nameBox.setHint(Component.literal("nom du repère").withStyle(ChatFormatting.DARK_GRAY));
        nameBox.setValue(kept);
        addRenderableWidget(nameBox);

        addRenderableWidget(Button.builder(Component.literal("Poser ici"), button -> place())
                .bounds(left + 168, bottom - 27, 70, 18).build());

        addRenderableWidget(Button.builder(Component.literal("◆"), button -> nextTint())
                .bounds(left + 244, bottom - 27, 20, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal("Couleur du prochain repère")))
                .build());
    }

    @Override
    protected void setInitialFocus() {
        this.setInitialFocus(nameBox);
    }

    /** Lignes réellement dessinées. Une au minimum : c'est elle qui porte le message du vide. */
    private int rows() {
        return Math.max(1, Math.min(shown.size(), WINDOW));
    }

    private int panelHeight() {
        return HEADER + rows() * ROW_HEIGHT + FOOTER;
    }

    private boolean isMine(Waypoint mark) {
        return this.minecraft != null && this.minecraft.player != null
                && mark.ownedBy(this.minecraft.player.getUUID());
    }

    private void place() {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            // Un bouton qui ne fait rien sans dire pourquoi passe pour cassé. Il l'est.
            say("Donne un nom à ce repère avant de le poser.", true);
            return;
        }
        for (Waypoint mark : shown) {
            if (isMine(mark) && mark.name().equalsIgnoreCase(name)) {
                say("Tu as déjà un repère de ce nom.", true);
                return;
            }
        }
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new WaypointAsk(
                WaypointAsk.Verb.PLACE, name, this.minecraft.player.blockPosition(), tint));
        nameBox.setValue("");
        say("« " + name + " » posé ici.", false);
    }

    private void ask(WaypointAsk.Verb verb, Waypoint mark) {
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new WaypointAsk(verb, mark.name(), mark.pos(), mark.colour()));
    }

    private void recolour(Waypoint mark) {
        int next = 0;
        for (int i = 0; i < Waypoint.PALETTE.length; i++) {
            if (Waypoint.PALETTE[i] == mark.colour()) {
                next = (i + 1) % Waypoint.PALETTE.length;
                break;
            }
        }
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new WaypointAsk(
                WaypointAsk.Verb.TINT, mark.name(), mark.pos(), Waypoint.PALETTE[next]));
    }

    private void nextTint() {
        for (int i = 0; i < Waypoint.PALETTE.length; i++) {
            if (Waypoint.PALETTE[i] == tint) {
                tint = Waypoint.PALETTE[(i + 1) % Waypoint.PALETTE.length];
                return;
            }
        }
        tint = Waypoint.PALETTE[0];
    }

    private void say(String text, boolean bad) {
        this.note = text;
        this.noteIsBad = bad;
        this.noteAt = System.currentTimeMillis();
    }

    /**
     * Le message s'efface au bout de six secondes.
     *
     * <p>Assez pour être lu ; assez court pour que la ligne qu'il recouvre — la teinte du prochain
     * repère — revienne d'elle-même, sans qu'on ait à se demander comment la retrouver.
     */
    private boolean speaking() {
        return !this.note.isEmpty() && System.currentTimeMillis() - this.noteAt < 6000L;
    }

    /**
     * Relit le carnet à chaque tick.
     *
     * <p>Le serveur renvoie le carnet entier après chaque action — voir {@link Marks}. Sans cette
     * relecture, l'écran montrerait l'état d'avant le clic jusqu'à sa fermeture, c'est-à-dire qu'il
     * mentirait pendant exactement le temps où l'on a le plus besoin qu'il dise vrai.
     *
     * <p>La reconstruction n'a lieu que si la <b>hauteur</b> du panneau change : elle seule déplace
     * la zone de saisie et les boutons. Un repère renommé ou reteint ne la déclenche pas, et c'est
     * volontaire — reconstruire, c'est perdre le focus du champ.
     */
    @Override
    public void tick() {
        super.tick();
        int wasRows = rows();
        shown = Marks.byDistance();
        first = Math.max(0, Math.min(first, Math.max(0, shown.size() - WINDOW)));
        if (rows() != wasRows) {
            rebuildWidgets();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partial) {
        this.fit.open(graphics);
        // La souris arrive dans l'espace de l'écran ; tout ce qui suit raisonne dans celui du
        // panneau. En oublier une seule donne le symptôme le plus déroutant qui soit : les boutons
        // du haut répondent, ceux du bas ne répondent plus.
        mouseX = (int) this.fit.x(mouseX);
        mouseY = (int) this.fit.y(mouseY);

        int left = this.panelX;
        int top = this.panelY;
        int bottom = top + panelHeight();

        graphics.fill(0, 0, this.fit.viewWidth(), this.fit.viewHeight(), SCRIM);
        graphics.fill(left - 1, top - 1, left + WIDTH + 1, bottom + 1, EDGE);
        graphics.fill(left, top, left + WIDTH, bottom, PANEL);
        graphics.fill(left, top, left + WIDTH, top + 2, AMBER);
        graphics.fill(left, top + HEADER - 6, left + WIDTH, top + HEADER - 5, EDGE);

        graphics.text(this.font, "REPÈRES", left + 10, top + 10, AMBER, false);
        String quota = shown.isEmpty() ? "aucun"
                : shown.size() > WINDOW
                        ? (first + 1) + "–" + Math.min(shown.size(), first + WINDOW)
                                + " sur " + shown.size()
                        : shown.size() + (shown.size() > 1 ? " repères" : " repère");
        graphics.text(this.font, quota,
                left + WIDTH - 10 - this.font.width(quota), top + 10, DIM, false);

        if (shown.isEmpty()) {
            graphics.text(this.font, "Aucun repère dans ce monde.",
                    left + 10, top + HEADER + 5, DIM, false);
            graphics.text(this.font, "Nomme un endroit en bas, puis pose-le.",
                    left + 10, top + HEADER + 16, FAINT, false);
        }

        for (int seat = 0; seat < rows() && first + seat < shown.size(); seat++) {
            drawRow(graphics, shown.get(first + seat), left, top + HEADER + seat * ROW_HEIGHT,
                    mouseX, mouseY);
        }

        // La molette n'existe que si elle sert : l'écrire toujours apprendrait au joueur à ne plus
        // le lire, et le jour où la liste défile vraiment il ne le verrait pas.
        if (shown.size() > WINDOW) {
            drawScrollbar(graphics, left, top);
        }

        // Mesurée dans la police du jeu : la phrase complète faisait 315 points pour 280 de place,
        // et débordait donc du panneau par la droite, sur le voile. Voir tools/mesure_police.py.
        graphics.text(this.font, "Un repère dit où. Pour y aller vite, bâtis un portail.",
                left + 10, bottom - FOOTER + 4, FAINT, false);
        if (speaking()) {
            graphics.text(this.font, this.font.plainSubstrByWidth(this.note, WIDTH - 20),
                    left + 10, bottom - FOOTER + 16, this.noteIsBad ? BAD : AMBER, false);
        } else {
            // Rien à répondre : la place sert alors à montrer la teinte que « ◆ » a choisie. Un
            // bouton qui change une couleur sans la montrer nulle part se règle à l'aveugle.
            graphics.fill(left + 10, bottom - FOOTER + 16, left + 18, bottom - FOOTER + 24,
                    0xFF000000 | this.tint);
            graphics.text(this.font, "teinte du prochain repère", left + 24,
                    bottom - FOOTER + 16, FAINT, false);
        }

        // Les composants de vanilla en dernier : ils doivent se poser SUR le panneau, pas dessous.
        // Ils y étaient dessous, et le voile du panneau les assombrissait sans qu'on sache pourquoi.
        super.extractRenderState(graphics, mouseX, mouseY, partial);
        this.fit.tooltips(graphics, mouseX, mouseY, partial);
        this.fit.close(graphics);
    }

    private void drawRow(GuiGraphicsExtractor graphics, Waypoint mark, int left, int rowTop,
                         int mouseX, int mouseY) {
        boolean mine = isMine(mark);
        graphics.fill(left + 4, rowTop, left + WIDTH - 4, rowTop + ROW_HEIGHT - 2,
                mine ? ROW_MINE : ROW);
        // La pastille : quatre pixels de couleur valent mieux qu'un nom de couleur écrit.
        graphics.fill(left + 10, rowTop + 6, left + 18, rowTop + 14, 0xFF000000 | mark.colour());

        // Un nom peut faire vingt-quatre caractères, la colonne en tient cent trente pixels : sans
        // cette coupe, il passait par-dessus la distance et l'on ne lisait plus ni l'un ni l'autre.
        graphics.text(this.font, this.font.plainSubstrByWidth(mark.name(), 130),
                left + 24, rowTop + 7, TEXT, false);

        String far = String.format(Locale.ROOT, "%.0f m", Marks.flatDistance(mark));
        graphics.text(this.font, far, left + 156, rowTop + 7, DIM, false);
        graphics.text(this.font, arrow(Marks.bearing(mark)),
                left + 192, rowTop + 7, 0xFF000000 | mark.colour(), false);

        if (!mine) {
            String owner = this.font.plainSubstrByWidth(mark.ownerName(), 82);
            graphics.text(this.font, owner,
                    left + WIDTH - 10 - this.font.width(owner), rowTop + 7, FAINT, false);
            return;
        }

        drawAction(graphics, shareBox(left, rowTop), mark.shared() ? "public" : "privé",
                mark.shared() ? AMBER : DIM, mouseX, mouseY);
        drawAction(graphics, dropBox(left, rowTop), "✕", BAD, mouseX, mouseY);
        drawAction(graphics, tintBox(left, rowTop), "◆", 0xFF000000 | mark.colour(),
                mouseX, mouseY);
    }

    /**
     * Une action de ligne : un rectangle, un mot centré, et un état de survol.
     *
     * <p>Le survol n'est pas décoratif. Trois cibles de seize pixels serrées dans le coin d'une
     * ligne de vingt-deux ne se distinguent pas à l'œil ; c'est l'éclaircissement qui dit laquelle
     * on est sur le point de déclencher — et « ✕ » efface un repère.
     */
    private void drawAction(GuiGraphicsExtractor graphics, int[] box, String label, int colour,
                            int mouseX, int mouseY) {
        boolean over = inside(mouseX, mouseY, box[0], box[1], box[2], box[3]);
        graphics.fill(box[0], box[1], box[2], box[3], over ? HOVER : RAIL);
        int width = this.font.width(label);
        graphics.text(this.font, label, box[0] + (box[2] - box[0] - width) / 2, box[1] + 4,
                over ? AMBER : colour, false);
    }

    // --- Les rectangles, écrits UNE fois --------------------------------------
    //
    // Le dessin et le clic calculaient les mêmes rectangles chacun de son côté. Une zone cliquable
    // qui ne recouvre pas ce qu'on voit est le pire défaut d'une interface dessinée à la main,
    // parce qu'il ne se voit pas : le bouton est là, il ne répond simplement pas.

    private static int[] shareBox(int left, int rowTop) {
        return new int[] {left + WIDTH - 96, rowTop + 2, left + WIDTH - 50, rowTop + 18};
    }

    private static int[] dropBox(int left, int rowTop) {
        return new int[] {left + WIDTH - 44, rowTop + 2, left + WIDTH - 28, rowTop + 18};
    }

    private static int[] tintBox(int left, int rowTop) {
        return new int[] {left + WIDTH - 24, rowTop + 2, left + WIDTH - 8, rowTop + 18};
    }

    /** Le curseur de défilement : dire qu'il reste des lignes, et combien. */
    private void drawScrollbar(GuiGraphicsExtractor graphics, int left, int top) {
        int railTop = top + HEADER;
        int railHeight = rows() * ROW_HEIGHT - 2;
        int x = left + WIDTH - 3;
        graphics.fill(x, railTop, x + 2, railTop + railHeight, RAIL);
        int thumb = Math.max(12, railHeight * WINDOW / shown.size());
        int travel = railHeight - thumb;
        int offset = shown.size() == WINDOW ? 0 : travel * first / (shown.size() - WINDOW);
        graphics.fill(x, railTop + offset, x + 2, railTop + offset + thumb, AMBER);
    }

    private static boolean inside(int px, int py, int left, int top, int right, int bottom) {
        return px >= left && px <= right && py >= top && py <= bottom;
    }

    /**
     * Une flèche sur huit directions, relative au regard.
     *
     * <p>Huit suffit : à seize, deux flèches voisines ne se distinguent plus à la taille d'un
     * caractère, et l'on aurait de la précision illisible plutôt que de l'information.
     */
    private static String arrow(float bearing) {
        int sector = Math.round((bearing + 180f) / 45f) % 8;
        return switch (sector) {
            case 0 -> "▼";
            case 1 -> "◤";
            case 2 -> "◀";
            case 3 -> "◣";
            case 4 -> "▲";
            case 5 -> "◢";
            case 6 -> "▶";
            default -> "◥";
        };
    }

    // --- Les entrées ----------------------------------------------------------
    //
    // Toutes ramènent la souris dans l'espace du panneau avant quoi que ce soit d'autre, y compris
    // celles qui ne servent qu'à les transmettre aux composants de vanilla : ceux-ci sont POSÉS
    // dans cet espace, il faut donc les CLIQUER dans cet espace.

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        MouseButtonEvent local = this.fit.event(event);
        if (super.mouseClicked(local, doubleClick)) {
            return true;
        }
        if (local.button() != 0) {
            return false;
        }
        int mouseX = (int) local.x();
        int mouseY = (int) local.y();
        for (int seat = 0; seat < rows() && first + seat < shown.size(); seat++) {
            Waypoint mark = shown.get(first + seat);
            if (!isMine(mark)) {
                continue;
            }
            int rowTop = this.panelY + HEADER + seat * ROW_HEIGHT;
            if (inside(mouseX, mouseY, shareBox(this.panelX, rowTop))) {
                ask(WaypointAsk.Verb.SHARE, mark);
                say(mark.shared() ? "« " + mark.name() + " » redevient privé."
                        : "« " + mark.name() + " » devient public.", false);
                return true;
            }
            if (inside(mouseX, mouseY, dropBox(this.panelX, rowTop))) {
                ask(WaypointAsk.Verb.DROP, mark);
                say("« " + mark.name() + " » effacé.", false);
                return true;
            }
            if (inside(mouseX, mouseY, tintBox(this.panelX, rowTop))) {
                recolour(mark);
                return true;
            }
        }
        return false;
    }

    private static boolean inside(int px, int py, int[] box) {
        return inside(px, py, box[0], box[1], box[2], box[3]);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return super.mouseReleased(this.fit.event(event));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return super.mouseDragged(this.fit.event(event),
                dragX / this.fit.factor(), dragY / this.fit.factor());
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(this.fit.x(mouseX), this.fit.y(mouseY));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (shown.size() > WINDOW && scrollY != 0d) {
            first = Math.max(0, Math.min(shown.size() - WINDOW,
                    first - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(this.fit.x(mouseX), this.fit.y(mouseY), scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
