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
    private static final int PANEL = 0xE0141418;
    private static final int EDGE = 0xFF2A2A32;
    private static final int ROW = 0x30FFFFFF;
    private static final int ROW_MINE = 0x18FFC857;
    private static final int ROW_TARGET = 0x306FCF97;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;

    private static final int WIDTH = 320;
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER = 46;
    private static final int FOOTER = 62;

    /** Huit lignes au maximum. Au-delà, l'écran dépasserait d'une fenêtre courte, et la molette sert. */
    private static final int WINDOW = 8;

    private final BlockPos heart;
    private Gate gate;
    private List<Gate> shown = List.of();
    private int first;
    private EditBox nameBox;

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

        int left = (this.width - WIDTH) / 2;
        int top = Math.max(16, (this.height - panelHeight()) / 2);
        int bottom = top + panelHeight();

        if (!mine()) {
            return;
        }

        nameBox = new EditBox(this.font, left + 10, bottom - 47, 150, 16,
                Component.literal("nom"));
        nameBox.setMaxLength(Gate.NAME_LIMIT);
        nameBox.setValue(gate.name());
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
                .bounds(left + 10, bottom - 25, 60, 18).build());

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
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (gate == null || !mine() || event.button() != 0) {
            return false;
        }
        int left = (this.width - WIDTH) / 2;
        int top = Math.max(16, (this.height - panelHeight()) / 2);
        for (int i = 0; i < rows() && first + i < shown.size(); i++) {
            int rowTop = top + HEADER + i * ROW_HEIGHT;
            if (event.x() >= left + 4 && event.x() <= left + WIDTH - 4
                    && event.y() >= rowTop && event.y() <= rowTop + ROW_HEIGHT - 2) {
                ask(GateAsk.Verb.LINK, shown.get(first + i));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (shown.size() > WINDOW) {
            first = Math.max(0, Math.min(shown.size() - WINDOW, first - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(x, y, scrollX, scrollY);
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
            return;
        }
        super.extractRenderState(graphics, mouseX, mouseY, partial);

        int left = (this.width - WIDTH) / 2;
        int top = Math.max(16, (this.height - panelHeight()) / 2);
        int bottom = top + panelHeight();

        graphics.fill(left - 1, top - 1, left + WIDTH + 1, bottom + 1, EDGE);
        graphics.fill(left, top, left + WIDTH, bottom, PANEL);
        graphics.fill(left, top + HEADER - 6, left + WIDTH, top + HEADER - 5, EDGE);

        graphics.text(this.font, "PORTAIL", left + 10, top + 8, 0xFF000000 | gate.colour(), false);
        graphics.text(this.font, gate.name(), left + 66, top + 8, TEXT, false);
        if (!mine()) {
            String owner = "à " + gate.ownerName();
            graphics.text(this.font, owner, left + WIDTH - 10 - this.font.width(owner),
                    top + 8, DIM, false);
        }

        String lead = gate.target()
                .map(id -> {
                    Gate other = Portals.byId(id);
                    return other == null ? "mène vers un portail que tu ne vois pas"
                            : "mène à « " + other.name() + " »";
                })
                .orElse("ne mène nulle part");
        graphics.text(this.font, lead, left + 10, top + 24, DIM, false);

        if (shown.isEmpty()) {
            graphics.text(this.font, "Aucune autre destination connue.",
                    left + 10, top + HEADER + 6, DIM, false);
        }

        for (int i = 0; i < rows() && first + i < shown.size(); i++) {
            Gate other = shown.get(first + i);
            int rowTop = top + HEADER + i * ROW_HEIGHT;
            boolean linked = gate.target().isPresent() && gate.target().get().equals(other.id());
            boolean ours = this.minecraft != null && this.minecraft.player != null
                    && other.ownedBy(this.minecraft.player.getUUID());
            graphics.fill(left + 4, rowTop, left + WIDTH - 4, rowTop + ROW_HEIGHT - 2,
                    linked ? ROW_TARGET : ours ? ROW_MINE : ROW);
            graphics.fill(left + 10, rowTop + 6, left + 18, rowTop + 14,
                    0xFF000000 | other.colour());

            graphics.text(this.font, linked ? "→ " + other.name() : other.name(),
                    left + 24, rowTop + 7, other.lit() ? TEXT : DIM, false);
            graphics.text(this.font, other.dimension().getPath(), left + 172, rowTop + 7, DIM, false);
            graphics.text(this.font, where(other), left + 236, rowTop + 7, DIM, false);
            if (!other.lit()) {
                graphics.text(this.font, "éteint", left + WIDTH - 44, rowTop + 7, 0xFFEB5757, false);
            } else if (!ours) {
                graphics.text(this.font, other.ownerName(), left + WIDTH - 10
                        - this.font.width(other.ownerName()), rowTop + 7, DIM, false);
            }
        }

        graphics.text(this.font, mine()
                        ? "Clique une ligne pour y mener. Le retour s'installe tout seul si la "
                        + "destination ne menait nulle part."
                        : "Ce portail ne t'appartient pas : tu peux le traverser, pas le régler.",
                left + 10, bottom - FOOTER + 2, DIM, false);
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
