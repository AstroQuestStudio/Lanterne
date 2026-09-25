package fr.clubcitrouille.lanterne.client.waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.client.Fit;
import fr.clubcitrouille.lanterne.content.waypoint.Gate;
import fr.clubcitrouille.lanterne.content.waypoint.GateAsk;
import fr.clubcitrouille.lanterne.content.waypoint.GateReply;
import fr.clubcitrouille.lanterne.content.waypoint.Waypoint;

/**
 * L'écran d'un portail de repère : ce portail, où il mène, et toutes les destinations possibles.
 *
 * <h2>Le lien qui ne faisait rien — la vraie cause</h2>
 *
 * <p>L'ancien écran détectait le clic sur une ligne à la main, et commençait par
 * {@code if (event.button() != 0) return false;}. Or depuis le passage de l'entrée à SDL, le clic
 * gauche vaut {@code 1} ({@code InputConstants.MOUSE_BUTTON_LEFT = 1}, lu dans le source patché de
 * 26.3 ; vanilla le teste lui-même dans {@code AbstractWidget.isValidClickButton}). Chaque clic gauche
 * sur une destination était donc écarté avant même d'exister : aucun paquet, aucun message, rien. Le
 * survol s'allumait pourtant, puisqu'il ne dépend pas du bouton — d'où l'impression d'un clic avalé.
 *
 * <p>Les destinations sont désormais de vrais widgets ({@link Row}) : c'est vanilla qui décide qu'un
 * clic a eu lieu, avec sa propre définition du bouton gauche, et il n'y a plus de test de bouton à
 * garder juste.
 *
 * <h2>Toujours une réponse, dans l'écran</h2>
 *
 * <p>Le résultat d'une demande revient par {@link GateReply} et s'affiche dans la bande de retour
 * ({@link #accept}). Le chat, où partaient autrefois ces messages, est invisible tant qu'un écran est
 * ouvert : un refus ressemblait à un clic perdu.
 *
 * <h2>Rien d'affiché qui ne soit vrai</h2>
 *
 * <p>« Délier » n'apparaît que si le portail est lié (et devient « Nettoyer le lien » si la
 * destination a disparu) ; les réglages n'apparaissent que pour qui peut régler — le propriétaire ou un
 * opérateur. Tous les portails sont publics : chacun peut les traverser et les choisir comme
 * destination.
 */
public class Gateway extends Screen {
    private static final int SCRIM = 0xC8000000;
    private static final int PANEL = 0xE0141418;
    private static final int EDGE = 0xFF2A2A32;
    private static final int RAIL = 0xFF1C1C22;
    private static final int ROW = 0x24FFFFFF;
    private static final int ROW_TARGET = 0x3A6FCF97;
    private static final int AMBER = 0xFFFFC857;
    private static final int GOOD = 0xFF6FCF97;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;
    private static final int FAINT = 0xFF5A5A62;
    private static final int BAD = 0xFFEB5757;
    private static final int HOVER = 0x22FFC857;

    private static final int WIDTH = 340;
    private static final int ROW_HEIGHT = 22;
    private static final int WINDOW = 7;
    private static final int LIST_TOP = 100;
    private static final int HEIGHT = LIST_TOP + WINDOW * ROW_HEIGHT + 58;

    /** Durée d'affichage d'une réponse du serveur, en millisecondes. */
    private static final long REPLY_MS = 9_000L;
    /** Fenêtre de confirmation de « Oublier », en millisecondes. */
    private static final long CONFIRM_MS = 4_000L;

    private final BlockPos heart;
    private final Fit fit = new Fit();
    private Gate gate;
    private List<Gate> all = List.of();
    private List<Gate> shown = List.of();
    private int first;
    private int panelX;
    private int panelY;
    private boolean managed;

    private EditBox nameBox;
    private EditBox searchBox;
    private Button unlinkButton;
    private Button forgetButton;
    private final List<Row> rows = new ArrayList<>();

    private String feedback = "";
    private int feedbackColour = DIM;
    private long feedbackAt;
    private long forgetArmedUntil;

    public static void open(BlockPos heart) {
        Minecraft client = Minecraft.getInstance();
        if (Portals.atHeart(heart) == null) {
            if (client.player != null) {
                client.player.sendOverlayMessage(Component.literal(
                                "Ce cœur n'est pas allumé : allume son cadre au briquet d'abord.")
                        .withStyle(ChatFormatting.GRAY));
            }
            return;
        }
        client.gui.setScreen(new Gateway(heart));
    }

    /** Réponse du serveur à une demande : affichée dans l'écran s'il est ouvert, sinon au-dessus de la barre. */
    public static void accept(GateReply reply) {
        Lanterne.LOG.info("[PORTAIL] réponse du serveur ({}) : {}", reply.ok() ? "ok" : "refus",
                reply.text());
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() instanceof Gateway open) {
            open.say(reply.text(), reply.ok() ? GOOD : BAD);
        } else if (client.player != null) {
            client.player.sendOverlayMessage(Component.literal(reply.text())
                    .withStyle(reply.ok() ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
    }

    private Gateway(BlockPos heart) {
        super(Component.literal("Portail"));
        this.heart = heart;
    }

    // --- Construction ----------------------------------------------------------------------------

    @Override
    protected void init() {
        gate = Portals.atHeart(heart);
        if (gate == null) {
            onClose();
            return;
        }
        managed = mayManage();
        all = others();
        this.fit.measure(this.width, this.height, WIDTH, HEIGHT);
        this.panelX = (this.fit.viewWidth() - WIDTH) / 2;
        this.panelY = Math.max(8, (this.fit.viewHeight() - HEIGHT) / 2);
        int left = this.panelX;
        int top = this.panelY;

        if (managed) {
            // Le nom en cours de frappe survit à une reconstruction (changement d'échelle, etc.).
            String kept = nameBox == null ? gate.name() : nameBox.getValue();
            nameBox = new EditBox(this.font, left + 10, top + 60, 146, 16, Component.literal("nom"));
            nameBox.setMaxLength(Gate.NAME_LIMIT);
            nameBox.setValue(kept);
            addRenderableWidget(nameBox);
            addRenderableWidget(Button.builder(Component.literal("Renommer"), button -> rename())
                    .bounds(left + 160, top + 59, 70, 18).build());
            addRenderableWidget(Button.builder(Component.literal("◆ Teinte"), button -> recolour())
                    .bounds(left + 234, top + 59, 96, 18)
                    .tooltip(Tooltip.create(Component.literal("Change la couleur de la nappe.")))
                    .build());
        }

        String query = searchBox == null ? "" : searchBox.getValue();
        searchBox = new EditBox(this.font, left + 10, top + 80, WIDTH - 20, 16,
                Component.literal("recherche"));
        searchBox.setHint(Component.literal("Rechercher : nom, propriétaire, monde…")
                .withStyle(ChatFormatting.DARK_GRAY));
        // Valeur AVANT le répondeur : sinon setValue déclencherait une première construction de la
        // liste avant que l'écran ne l'ait vidée, et les lignes seraient posées deux fois.
        searchBox.setValue(query);
        searchBox.setResponder(text -> {
            first = 0;
            refreshRows();
        });
        addRenderableWidget(searchBox);

        int footer = top + HEIGHT - 24;
        unlinkButton = addRenderableWidget(Button.builder(Component.literal("Délier"),
                        button -> ask(GateAsk.Verb.UNLINK, null))
                .bounds(left + 10, footer, 96, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Le portail reste allumé, mais ne mène plus nulle part.")))
                .build());
        addRenderableWidget(Button.builder(Component.literal("Fermer"), button -> onClose())
                .bounds(left + 130, footer, 80, 18).build());
        forgetButton = addRenderableWidget(Button.builder(Component.literal("Oublier"),
                        button -> forget())
                .bounds(left + WIDTH - 116, footer, 106, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Efface la fiche et éteint le portail. Le cœur reste posé.")))
                .build());
        updateButtons();

        rows.clear();
        refreshRows();
    }

    private boolean mayManage() {
        var player = this.minecraft == null ? null : this.minecraft.player;
        if (player == null || gate == null) {
            return false;
        }
        return gate.ownedBy(player.getUUID()) || player.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    private List<Gate> others() {
        List<Gate> list = new ArrayList<>();
        for (Gate other : Portals.all()) {
            if (!other.id().equals(gate.id())) {
                list.add(other);
            }
        }
        // La destination actuelle en tête, puis les portails allumés, puis par nom : ce qu'on cherche
        // le plus souvent est ce qui est déjà choisi, ou ce qui marche.
        list.sort((a, b) -> {
            int ta = isTarget(a) ? 0 : 1;
            int tb = isTarget(b) ? 0 : 1;
            if (ta != tb) {
                return ta - tb;
            }
            if (a.lit() != b.lit()) {
                return a.lit() ? -1 : 1;
            }
            return a.name().compareToIgnoreCase(b.name());
        });
        return list;
    }

    private boolean isTarget(Gate other) {
        return gate != null && gate.target().isPresent() && gate.target().get().equals(other.id());
    }

    private void refreshRows() {
        for (Row row : rows) {
            removeWidget(row);
        }
        rows.clear();
        String query = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        List<Gate> filtered = new ArrayList<>();
        for (Gate other : all) {
            if (query.isEmpty()
                    || other.name().toLowerCase(Locale.ROOT).contains(query)
                    || other.ownerName().toLowerCase(Locale.ROOT).contains(query)
                    || dimensionLabel(other.dimension()).toLowerCase(Locale.ROOT).contains(query)) {
                filtered.add(other);
            }
        }
        shown = filtered;
        first = Math.max(0, Math.min(first, Math.max(0, shown.size() - WINDOW)));
        for (int seat = 0; seat < WINDOW && first + seat < shown.size(); seat++) {
            Row row = new Row(this.panelX + 6, this.panelY + LIST_TOP + seat * ROW_HEIGHT,
                    WIDTH - 14, ROW_HEIGHT - 2, shown.get(first + seat));
            rows.add(addRenderableWidget(row));
        }
    }

    private void updateButtons() {
        if (unlinkButton != null) {
            boolean linked = gate.target().isPresent();
            unlinkButton.visible = managed && linked;
            unlinkButton.setMessage(Component.literal(
                    linked && Portals.byId(gate.target().get()) == null ? "Nettoyer le lien" : "Délier"));
        }
        if (forgetButton != null) {
            forgetButton.visible = managed;
            boolean armed = System.currentTimeMillis() < forgetArmedUntil;
            forgetButton.setMessage(Component.literal(armed ? "Confirmer ?" : "Oublier")
                    .withStyle(armed ? ChatFormatting.RED : ChatFormatting.WHITE));
        }
    }

    // --- Actions ---------------------------------------------------------------------------------

    private void pick(Gate other) {
        Lanterne.LOG.info("[PORTAIL] clic sur la destination « {} » depuis « {} ».", other.name(),
                gate.name());
        if (!managed) {
            say("Ce portail est à " + gate.ownerName() + " : tu peux le traverser, pas le régler.", BAD);
            return;
        }
        if (isTarget(other)) {
            say("« " + gate.name() + " » mène déjà à « " + other.name() + " ».", DIM);
            return;
        }
        ask(GateAsk.Verb.LINK, other);
        say("Liaison vers « " + other.name() + " »…", DIM);
    }

    private void rename() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            say("Un portail a besoin d'un nom.", BAD);
            return;
        }
        if (name.equals(gate.name())) {
            say("C'est déjà son nom.", DIM);
            return;
        }
        send(new GateAsk(GateAsk.Verb.RENAME, gate.id(), Optional.empty(), name, gate.colour()));
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

    private void forget() {
        long now = System.currentTimeMillis();
        if (now >= forgetArmedUntil) {
            forgetArmedUntil = now + CONFIRM_MS;
            say("Clique encore sur « Confirmer ? » pour oublier ce portail.", AMBER);
            updateButtons();
            return;
        }
        ask(GateAsk.Verb.FORGET, null);
        onClose();
    }

    private void ask(GateAsk.Verb verb, Gate target) {
        send(new GateAsk(verb, gate.id(),
                target == null ? Optional.empty() : Optional.of(target.id()),
                gate.name(), gate.colour()));
    }

    private void send(GateAsk ask) {
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(ask);
        Lanterne.LOG.info("[PORTAIL] demande {} envoyée pour « {} »{}.", ask.verb(), gate.name(),
                ask.target().map(id -> {
                    Gate other = Portals.byId(id);
                    return " vers « " + (other == null ? id.toString() : other.name()) + " »";
                }).orElse(""));
    }

    private void say(String text, int colour) {
        this.feedback = text;
        this.feedbackColour = colour;
        this.feedbackAt = System.currentTimeMillis();
    }

    // --- Événements ------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Les coordonnées passent dans l'espace du panneau (voir Fit), puis vanilla distribue le clic
        // à ses widgets — les lignes de destination comprises. Aucun test de bouton ici.
        return super.mouseClicked(this.fit.event(event), doubleClick);
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
            int before = first;
            first = Math.max(0, Math.min(shown.size() - WINDOW, first - (int) Math.signum(scrollY)));
            if (first != before) {
                refreshRows();
            }
            return true;
        }
        return super.mouseScrolled(this.fit.x(x), this.fit.y(y), scrollX, scrollY);
    }

    @Override
    public void tick() {
        super.tick();
        Gate fresh = Portals.atHeart(heart);
        if (fresh == null || gate == null) {
            onClose();
            return;
        }
        boolean rightsChanged = false;
        gate = fresh;
        boolean nowManaged = mayManage();
        if (nowManaged != managed) {
            managed = nowManaged;
            rightsChanged = true;
        }
        List<Gate> fresher = others();
        if (rightsChanged) {
            // Les réglages apparaissent ou disparaissent : seule une reconstruction les place.
            rebuildWidgets();
            return;
        }
        if (!fresher.equals(all)) {
            all = fresher;
            refreshRows();
        }
        updateButtons();
    }

    // --- Rendu -----------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
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
        int bottom = top + HEIGHT;
        int colour = 0xFF000000 | gate.colour();

        graphics.fill(0, 0, this.fit.viewWidth(), this.fit.viewHeight(), SCRIM);
        graphics.fill(left - 1, top - 1, left + WIDTH + 1, bottom + 1, EDGE);
        graphics.fill(left, top, left + WIDTH, bottom, PANEL);
        graphics.fill(left, top, left + WIDTH, top + 3, colour);

        // En-tête : ce portail.
        graphics.text(this.font, "PORTAIL", left + 10, top + 10, colour, false);
        String owner = "posé par " + gate.ownerName();
        int ownerWidth = this.font.width(owner);
        graphics.text(this.font, this.font.plainSubstrByWidth(gate.name(), WIDTH - 86 - ownerWidth),
                left + 58, top + 10, TEXT, false);
        graphics.text(this.font, owner, left + WIDTH - 10 - ownerWidth, top + 10, DIM, false);
        graphics.text(this.font, String.format(Locale.ROOT, "%s · %d, %d, %d%s",
                        dimensionLabel(gate.dimension()), gate.heart().getX(), gate.heart().getY(),
                        gate.heart().getZ(), gate.lit() ? "" : " · éteint"),
                left + 10, top + 23, gate.lit() ? DIM : BAD, false);

        // État du lien — exactement ce que dit le serveur.
        String status;
        int statusColour;
        if (gate.target().isEmpty()) {
            status = managed ? "Ne mène nulle part — clique une destination ci-dessous."
                    : "Ne mène nulle part.";
            statusColour = AMBER;
        } else {
            Gate there = Portals.byId(gate.target().get());
            if (there == null) {
                status = "Destination disparue — choisis-en une autre, ou nettoie le lien.";
                statusColour = BAD;
            } else {
                status = "Mène à « " + there.name() + " » · " + dimensionLabel(there.dimension())
                        + " · " + distance(there) + (there.lit() ? "" : " · éteint");
                statusColour = there.lit() ? GOOD : AMBER;
            }
        }
        graphics.text(this.font, this.font.plainSubstrByWidth(status, WIDTH - 20),
                left + 10, top + 38, statusColour, false);
        graphics.fill(left + 10, top + 52, left + WIDTH - 10, top + 53, EDGE);

        if (!managed) {
            graphics.text(this.font, this.font.plainSubstrByWidth(
                            "À " + gate.ownerName() + " : tu peux le traverser, pas le régler.", WIDTH - 20),
                    left + 10, top + 64, FAINT, false);
        }

        // Liste.
        if (shown.isEmpty()) {
            boolean filtering = searchBox != null && !searchBox.getValue().isBlank();
            graphics.text(this.font, filtering ? "Aucun portail ne correspond à la recherche."
                            : "Aucune autre destination pour l'instant.",
                    left + 12, top + LIST_TOP + 6, DIM, false);
            if (!filtering) {
                graphics.text(this.font, "Allume un autre portail ailleurs : il apparaîtra ici.",
                        left + 12, top + LIST_TOP + 18, FAINT, false);
            }
        }
        if (shown.size() > WINDOW) {
            drawScrollbar(graphics, left, top);
        }

        // Qui mène ici (modèle « hub » : plusieurs portails peuvent mener au même).
        List<String> incoming = new ArrayList<>();
        for (Gate other : all) {
            if (other.target().isPresent() && other.target().get().equals(gate.id())) {
                incoming.add(other.name());
            }
        }
        int incomingY = top + LIST_TOP + WINDOW * ROW_HEIGHT + 4;
        graphics.text(this.font, this.font.plainSubstrByWidth(incoming.isEmpty()
                        ? "Aucun portail ne mène ici."
                        : "Mènent ici (" + incoming.size() + ") : " + String.join(", ", incoming),
                        WIDTH - 20),
                left + 10, incomingY, FAINT, false);

        // Retour du serveur, ou consigne.
        long age = System.currentTimeMillis() - feedbackAt;
        if (!feedback.isEmpty() && age < REPLY_MS) {
            graphics.text(this.font, this.font.plainSubstrByWidth(feedback, WIDTH - 20),
                    left + 10, incomingY + 13, feedbackColour, false);
        } else if (managed) {
            graphics.text(this.font, "Clique une destination pour y mener. Le retour s'installe "
                            + "seul si elle ne menait nulle part.",
                    left + 10, incomingY + 13, FAINT, false);
        }

        // Les composants de vanilla en dernier : ils doivent se poser SUR le panneau, pas dessous.
        super.extractRenderState(graphics, mouseX, mouseY, partial);
        this.fit.tooltips(graphics, mouseX, mouseY, partial);
        this.fit.close(graphics);
    }

    private void drawScrollbar(GuiGraphicsExtractor graphics, int left, int top) {
        int railTop = top + LIST_TOP;
        int railHeight = WINDOW * ROW_HEIGHT - 2;
        int x = left + WIDTH - 6;
        graphics.fill(x, railTop, x + 2, railTop + railHeight, RAIL);
        int thumb = Math.max(12, railHeight * WINDOW / shown.size());
        int travel = railHeight - thumb;
        int offset = travel * first / (shown.size() - WINDOW);
        graphics.fill(x, railTop + offset, x + 2, railTop + offset + thumb, AMBER);
    }

    private static String dimensionLabel(Identifier dimension) {
        return switch (dimension.toString()) {
            case "minecraft:overworld" -> "Surface";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> dimension.getPath();
        };
    }

    private String distance(Gate other) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null
                || !other.dimension().equals(client.level.dimension().identifier())) {
            return "autre monde";
        }
        double dx = other.heart().getX() + 0.5d - client.player.getX();
        double dz = other.heart().getZ() + 0.5d - client.player.getZ();
        double metres = Math.sqrt(dx * dx + dz * dz);
        return metres >= 1000d ? String.format(Locale.ROOT, "%.1f km", metres / 1000d)
                : String.format(Locale.ROOT, "%.0f m", metres);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Une destination de la liste — un vrai widget : c'est vanilla qui reconnaît le clic
     * ({@link AbstractWidget#mouseClicked}), avec sa propre définition du bouton gauche.
     */
    private final class Row extends AbstractWidget {
        private final Gate other;

        Row(int x, int y, int width, int height, Gate other) {
            super(x, y, width, height, Component.literal(other.name()));
            this.other = other;
            this.active = managed;
            if (managed && !isTarget(other)) {
                setTooltip(Tooltip.create(Component.literal(
                        "Clique pour que « " + gate.name() + " » mène à « " + other.name() + " ».")));
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            pick(other);
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                float partial) {
            int x = getX();
            int y = getY();
            boolean linked = isTarget(other);
            boolean leadsHere = other.target().isPresent() && other.target().get().equals(gate.id());
            graphics.fill(x, y, x + width, y + height, linked ? ROW_TARGET : ROW);
            if (this.active && isHovered()) {
                graphics.fill(x, y, x + width, y + height, HOVER);
                graphics.fill(x, y, x + 2, y + height, AMBER);
            }
            graphics.fill(x + 6, y + 6, x + 14, y + 14, 0xFF000000 | other.colour());

            String name = (linked ? "✓ " : "") + other.name() + (leadsHere ? "  ↩" : "");
            graphics.text(font, font.plainSubstrByWidth(name, 118), x + 20, y + 6,
                    linked ? GOOD : other.lit() ? TEXT : DIM, false);
            graphics.text(font, font.plainSubstrByWidth(other.ownerName(), 70), x + 142, y + 6,
                    DIM, false);
            graphics.text(font, font.plainSubstrByWidth(dimensionLabel(other.dimension()), 50),
                    x + 216, y + 6, DIM, false);
            String tail = other.lit() ? distance(other) : "éteint";
            String clipped = font.plainSubstrByWidth(tail, 52);
            graphics.text(font, clipped, x + width - 6 - font.width(clipped), y + 6,
                    other.lit() ? FAINT : BAD, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
