package fr.clubcitrouille.lanterne.client.waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import fr.clubcitrouille.lanterne.client.Fit;
import fr.clubcitrouille.lanterne.content.waypoint.Gate;
import fr.clubcitrouille.lanterne.content.waypoint.GateAsk;
import fr.clubcitrouille.lanterne.content.waypoint.Waypoint;

/**
 * L'écran d'un portail : le nommer, le teindre, l'ouvrir aux autres, et dire où il mène.
 *
 * <h2>Le même écran que le carnet, et c'est une décision</h2>
 *
 * <p>Mêmes couleurs, mêmes pastilles, mêmes flèches, même dessin à la main plutôt que des composants
 * empilés — voir {@link Board}, dont ce fichier est le frère. Un joueur qui a appris à lire le carnet
 * sait lire celui-ci sans qu'on lui dise rien, et c'est tout ce qu'on demande à une interface.
 *
 * <p>La seule différence de fond tient en une ligne : ici, <b>cliquer une ligne relie</b>. Le carnet
 * n'a jamais eu d'action qui déplace ; celui-ci n'a presque que cela.
 *
 * <h2>Ce que montre la liste</h2>
 *
 * <p>Tous les portails que le serveur nous a annoncés — les nôtres, et les publics des autres — sauf
 * celui qu'on est en train de configurer, parce qu'un portail ne mène pas à lui-même. Ceux d'un autre
 * monde sont montrés aussi : c'est justement là que la distance coûtait le plus cher, et une liste qui
 * cacherait le Nether cacherait l'essentiel du réseau.
 *
 * <p>Un portail éteint reste affiché, et grisé. Le cacher laisserait croire qu'il a disparu ; le
 * proposer comme s'il marchait enverrait quelqu'un dans un cadre vide.
 */
public class Gateway extends Screen {
    private static final int SCRIM = 0xC8000000;
    private static final int PANEL = 0xE0141418;
    private static final int EDGE = 0xFF2A2A32;
    private static final int RAIL = 0xFF1C1C22;
    private static final int ROW = 0x30FFFFFF;
    private static final int ROW_MINE = 0x18FFC857;
    private static final int ROW_TARGET = 0x306FCF97;
    private static final int AMBER = 0xFFFFC857;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;
    private static final int FAINT = 0xFF5A5A62;
    private static final int BAD = 0xFFEB5757;
    private static final int HOVER = 0x20FFC857;

    private static final int WIDTH = 320;
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER = 46;

    /**
     * La bande du bas : deux lignes d'explication, puis les deux rangées de réglage.
     *
     * <p>Elle valait soixante-deux, ce qui ne laissait qu'<b>une</b> ligne à un texte qui en fait
     * deux. Il était donc écrit en entier sur une seule, et débordait du panneau par la droite, sur
     * le voile noir — un défaut qui ne se voit pas en relisant le code, puisque la chaîne y tient
     * très bien.
     */
    private static final int FOOTER = 74;

    /** Huit lignes au maximum. Au-delà, l'écran dépasserait d'une fenêtre courte, et la molette sert. */
    private static final int WINDOW = 8;

    private final BlockPos heart;
    private Gate gate;
    private List<Gate> shown = List.of();
    private int first;
    private EditBox nameBox;

    private int panelX;
    private int panelY;

    /** L'ajusteur : 320 × 296 ne tient pas dans les 480 × 270 d'une échelle automatique. */
    private final Fit fit = new Fit();

    /** Ouvre l'écran du portail dont le cœur est ici. Appelé depuis {@code Heart.useItemOn}. */
    public static void open(BlockPos heart) {
        Minecraft client = Minecraft.getInstance();
        Gate gate = Portals.atHeart(heart);
        if (gate == null) {
            // Deux causes, un seul message : ou le cadre n'a jamais brûlé, ou son propriétaire le
            // garde privé — et dans ce second cas le serveur ne nous en a rien dit, ce qui est
            // exactement ce qu'on lui demande. Nommer les deux évite de faire croire à une panne.
            if (client.player != null) {
                client.player.sendOverlayMessage(Component.literal(
                                "Rien à régler ici : ce cœur n'est pas allumé, ou son portail est privé.")
                        .withStyle(ChatFormatting.GRAY));
            }
            return;
        }
        client.gui.setScreen(new Gateway(heart));
    }

    private Gateway(BlockPos heart) {
        super(Component.literal("Portail"));
        this.heart = heart;
    }

    @Override
    protected void init() {
        gate = Portals.atHeart(heart);
        if (gate == null) {
            onClose();
            return;
        }
        shown = reachable();
        first = Math.max(0, Math.min(first, Math.max(0, shown.size() - WINDOW)));

        this.fit.measure(this.width, this.height, WIDTH, panelHeight());
        this.panelX = (this.fit.viewWidth() - WIDTH) / 2;
        this.panelY = Math.max(8, (this.fit.viewHeight() - panelHeight()) / 2);

        int left = this.panelX;
        int bottom = this.panelY + panelHeight();

        if (!mine()) {
            return;
        }

        // Le nom en cours de frappe survit à une reconstruction : le tick en déclenche une dès que
        // la liste change de taille, et perdre sa saisie parce qu'un voisin a allumé son portail
        // serait le genre de hasard qu'on ne relie jamais à sa cause.
        String kept = nameBox == null ? gate.name() : nameBox.getValue();
        nameBox = new EditBox(this.font, left + 10, bottom - 47, 150, 16,
                Component.literal("nom"));
        nameBox.setMaxLength(Gate.NAME_LIMIT);
        nameBox.setValue(kept);
        addRenderableWidget(nameBox);

        addRenderableWidget(Button.builder(Component.literal("Renommer"), button -> rename())
                .bounds(left + 168, bottom - 48, 70, 18).build());

        addRenderableWidget(Button.builder(Component.literal("◆"), button -> recolour())
                .bounds(left + 244, bottom - 48, 20, 18)
                .tooltip(Tooltip.create(Component.literal("Teinte du portail")))
                .build());

        addRenderableWidget(Button.builder(
                        Component.literal(gate.shared() ? "public" : "privé"),
                        button -> ask(GateAsk.Verb.SHARE, null))
                .bounds(left + 270, bottom - 48, 44, 18)
                .tooltip(Tooltip.create(Component.literal(gate.shared()
                        ? "Tout le monde peut le voir, le relier et le traverser."
                        : "Toi seul peux le voir, le relier et le traverser.")))
                .build());

        addRenderableWidget(Button.builder(Component.literal("Délier"),
                        button -> ask(GateAsk.Verb.UNLINK, null))
                .bounds(left + 10, bottom - 25, 60, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Le portail reste allumé, mais ne mène plus nulle part.")))
                .build());

        addRenderableWidget(Button.builder(Component.literal("Oublier ce portail"),
                        button -> ask(GateAsk.Verb.FORGET, null))
                .bounds(left + WIDTH - 130, bottom - 25, 120, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Efface la fiche et éteint le portail. Le cœur reste posé.")))
                .build());
    }

    /** Toutes les destinations proposables : ce qu'on connaît, moins soi-même. */
    private List<Gate> reachable() {
        List<Gate> list = new ArrayList<>();
        for (Gate other : Portals.all()) {
            if (!other.id().equals(gate.id())) {
                list.add(other);
            }
        }
        return list;
    }

    private boolean mine() {
        return this.minecraft != null && this.minecraft.player != null
                && gate.ownedBy(this.minecraft.player.getUUID());
    }

    private int rows() {
        return Math.max(1, Math.min(shown.size(), WINDOW));
    }

    private int panelHeight() {
        return HEADER + rows() * ROW_HEIGHT + FOOTER;
    }

    private void rename() {
        String name = nameBox.getValue().trim();
        if (!name.isEmpty()) {
            send(new GateAsk(GateAsk.Verb.RENAME, gate.id(), Optional.empty(), name, gate.colour()));
        }
    }

    private void recolour() {
        int next = 0;
        for (int i = 0; i < Waypoint.PALETTE.length; i++) {
            if (Waypoint.PALETTE[i] == gate.colour()) {
                next = (i + 1) % Waypoint.PALETTE.length;
                break;
            }
        }
        send(new GateAsk(GateAsk.Verb.TINT, gate.id(), Optional.empty(), gate.name(),
                Waypoint.PALETTE[next]));
    }

    private void ask(GateAsk.Verb verb, Gate target) {
        send(new GateAsk(verb, gate.id(),
                target == null ? Optional.empty() : Optional.of(target.id()),
                gate.name(), gate.colour()));
        if (verb == GateAsk.Verb.FORGET) {
            onClose();
        }
    }

    private void send(GateAsk ask) {
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(ask);
    }

    /**
     * Cliquer une ligne relie le portail à cette destination.
     *
     * <p>Le clic est traité ici plutôt que par des boutons invisibles comme dans {@link Board} : la
     * liste défile, et des boutons qu'il faudrait reconstruire à chaque cran de molette auraient
     * réinitialisé la zone de saisie du nom au milieu d'une frappe.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        MouseButtonEvent local = this.fit.event(event);
        if (super.mouseClicked(local, doubleClick)) {
            return true;
        }
        if (gate == null || !mine() || local.button() != 0) {
            return false;
        }
        for (int seat = 0; seat < rows() && first + seat < shown.size(); seat++) {
            int rowTop = this.panelY + HEADER + seat * ROW_HEIGHT;
            if (local.x() >= this.panelX + 4 && local.x() <= this.panelX + WIDTH - 4
                    && local.y() >= rowTop && local.y() <= rowTop + ROW_HEIGHT - 2) {
                ask(GateAsk.Verb.LINK, shown.get(first + seat));
                return true;
            }
        }
        return false;
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
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (shown.size() > WINDOW && scrollY != 0d) {
            first = Math.max(0, Math.min(shown.size() - WINDOW, first - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(this.fit.x(x), this.fit.y(y), scrollX, scrollY);
    }

    /**
     * Relit la fiche à chaque tick, et reconstruit les boutons si sa forme a changé.
     *
     * <p>Le serveur renvoie la liste entière après chaque action ; sans cette relecture, l'écran
     * montrerait l'état d'avant le clic jusqu'à sa fermeture — c'est-à-dire qu'il mentirait pendant
     * exactement le temps où l'on a le plus besoin qu'il dise vrai.
     *
     * <p>La reconstruction est faite ici et non au dessin : {@code rebuildWidgets} vide la liste des
     * enfants, et la vider au milieu du parcours qui les dessine serait une modification concurrente
     * pure et simple.
     */
    @Override
    public void tick() {
        super.tick();
        Gate fresh = Portals.atHeart(heart);
        if (fresh == null || gate == null) {
            onClose();
            return;
        }
        boolean shapeChanged = fresh.shared() != gate.shared()
                || shown.size() != reachable().size();
        gate = fresh;
        shown = reachable();
        first = Math.max(0, Math.min(first, Math.max(0, shown.size() - WINDOW)));
        if (shapeChanged) {
            rebuildWidgets();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partial) {
        if (gate == null) {
            // Rien n'est ouvert : rien n'est refermé. Une pile de transformations déséquilibrée
            // déforme l'interface entière du jeu, longtemps après cet écran.
            return;
        }
        this.fit.open(graphics);
        mouseX = (int) this.fit.x(mouseX);
        mouseY = (int) this.fit.y(mouseY);

        int left = this.panelX;
        int top = this.panelY;
        int bottom = top + panelHeight();

        graphics.fill(0, 0, this.fit.viewWidth(), this.fit.viewHeight(), SCRIM);
        graphics.fill(left - 1, top - 1, left + WIDTH + 1, bottom + 1, EDGE);
        graphics.fill(left, top, left + WIDTH, bottom, PANEL);
        graphics.fill(left, top, left + WIDTH, top + 2, 0xFF000000 | gate.colour());
        graphics.fill(left, top + HEADER - 6, left + WIDTH, top + HEADER - 5, EDGE);

        graphics.text(this.font, "PORTAIL", left + 10, top + 10, 0xFF000000 | gate.colour(), false);
        String owner = mine() ? "" : "à " + gate.ownerName();
        int nameRoom = WIDTH - 76 - (owner.isEmpty() ? 10 : this.font.width(owner) + 16);
        graphics.text(this.font, this.font.plainSubstrByWidth(gate.name(), nameRoom),
                left + 66, top + 10, TEXT, false);
        if (!owner.isEmpty()) {
            graphics.text(this.font, owner, left + WIDTH - 10 - this.font.width(owner),
                    top + 10, DIM, false);
        }

        String lead = gate.target()
                .map(id -> {
                    Gate other = Portals.byId(id);
                    return other == null ? "mène vers un portail que tu ne vois pas"
                            : "mène à « " + other.name() + " »";
                })
                .orElse("ne mène nulle part");
        graphics.text(this.font, this.font.plainSubstrByWidth(lead, WIDTH - 20),
                left + 10, top + 25, gate.target().isPresent() ? DIM : FAINT, false);

        if (shown.isEmpty()) {
            graphics.text(this.font, "Aucune autre destination connue.",
                    left + 10, top + HEADER + 4, DIM, false);
            graphics.text(this.font, "Allume un second cœur ailleurs, il apparaîtra ici.",
                    left + 10, top + HEADER + 15, FAINT, false);
        }

        for (int seat = 0; seat < rows() && first + seat < shown.size(); seat++) {
            drawRow(graphics, shown.get(first + seat), left, top + HEADER + seat * ROW_HEIGHT,
                    mouseX, mouseY);
        }

        if (shown.size() > WINDOW) {
            drawScrollbar(graphics, left, top);
        }

        int line = bottom - FOOTER + 2;
        for (String part : wrap(mine()
                ? "Clique une ligne pour y mener. Le retour s'installe tout seul si la destination "
                        + "ne menait nulle part."
                : "Ce portail ne t'appartient pas : tu peux le traverser, pas le régler.",
                WIDTH - 20)) {
            graphics.text(this.font, part, left + 10, line, FAINT, false);
            line += 10;
        }

        // Les composants de vanilla en dernier : ils doivent se poser SUR le panneau, pas dessous.
        super.extractRenderState(graphics, mouseX, mouseY, partial);
        this.fit.tooltips(graphics, mouseX, mouseY, partial);
        this.fit.close(graphics);
    }

    private void drawRow(GuiGraphicsExtractor graphics, Gate other, int left, int rowTop,
                         int mouseX, int mouseY) {
        boolean linked = gate.target().isPresent() && gate.target().get().equals(other.id());
        boolean ours = this.minecraft != null && this.minecraft.player != null
                && other.ownedBy(this.minecraft.player.getUUID());
        // Le survol n'est allumé que si le clic fait quelque chose. Éclairer une ligne inerte
        // promet une action qui n'arrivera pas, et c'est pire que de ne rien promettre.
        boolean over = mine()
                && inside(mouseX, mouseY, left + 4, rowTop, left + WIDTH - 4,
                        rowTop + ROW_HEIGHT - 2);

        graphics.fill(left + 4, rowTop, left + WIDTH - 4, rowTop + ROW_HEIGHT - 2,
                linked ? ROW_TARGET : ours ? ROW_MINE : ROW);
        if (over) {
            graphics.fill(left + 4, rowTop, left + WIDTH - 4, rowTop + ROW_HEIGHT - 2, HOVER);
            graphics.fill(left + 4, rowTop, left + 6, rowTop + ROW_HEIGHT - 2, AMBER);
        }
        graphics.fill(left + 10, rowTop + 6, left + 18, rowTop + 14, 0xFF000000 | other.colour());

        String name = linked ? "→ " + other.name() : other.name();
        graphics.text(this.font, this.font.plainSubstrByWidth(name, 128),
                left + 24, rowTop + 7, other.lit() ? TEXT : DIM, false);
        graphics.text(this.font,
                this.font.plainSubstrByWidth(other.dimension().getPath(), 56),
                left + 156, rowTop + 7, DIM, false);
        graphics.text(this.font, where(other), left + 216, rowTop + 7, DIM, false);

        // À droite, une seule chose : la panne si elle existe, le propriétaire sinon. Les deux
        // s'écrivaient au même endroit à quelques pixels près, et se chevauchaient.
        String tail = !other.lit() ? "éteint" : ours ? "" : other.ownerName();
        if (!tail.isEmpty()) {
            String clipped = this.font.plainSubstrByWidth(tail, 52);
            graphics.text(this.font, clipped, left + WIDTH - 10 - this.font.width(clipped),
                    rowTop + 7, other.lit() ? FAINT : BAD, false);
        }
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

    /** Coupe un paragraphe en lignes qui tiennent dans le panneau. */
    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (this.font.width(candidate) > width && current.length() > 0) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static boolean inside(int px, int py, int left, int top, int right, int bottom) {
        return px >= left && px <= right && py >= top && py <= bottom;
    }

    /** La distance horizontale, quand elle a un sens — c'est-à-dire dans le même monde. */
    private String where(Gate other) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null
                || !other.dimension().equals(client.level.dimension().identifier())) {
            return "—";
        }
        double dx = other.heart().getX() + 0.5d - client.player.getX();
        double dz = other.heart().getZ() + 0.5d - client.player.getZ();
        return String.format(Locale.ROOT, "%.0f m", Math.sqrt(dx * dx + dz * dz));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
