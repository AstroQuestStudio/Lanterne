package fr.clubcitrouille.lanterne.client.waypoint;

import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import fr.clubcitrouille.lanterne.content.waypoint.Waypoint;
import fr.clubcitrouille.lanterne.content.waypoint.WaypointAsk;

/**
 * Le carnet, à l'écran.
 *
 * <h2>Ce que cette interface montre, et ce qu'elle refuse de montrer</h2>
 *
 * <p>Pas de bouton de téléportation. C'est le point de départ du système entier : un repère dit
 * <em>où c'est</em> et <em>à quelle distance</em>, le chemin reste à faire.
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
 */
public class Board extends Screen {
    private static final int PANEL = 0xE0141418;
    private static final int EDGE = 0xFF2A2A32;
    private static final int ROW = 0x30FFFFFF;
    private static final int ROW_MINE = 0x18FFC857;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;

    private static final int WIDTH = 300;
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER = 34;

    private EditBox nameBox;
    private int tint = Waypoint.PALETTE[0];
    private List<Waypoint> shown = List.of();

    public Board() {
        super(Component.literal("Repères"));
    }

    @Override
    protected void init() {
        shown = Marks.byDistance();
        int left = (this.width - WIDTH) / 2;
        int top = Math.max(20, (this.height - panelHeight()) / 2);

        nameBox = new EditBox(this.font, left + 10, top + panelHeight() - 26, 150, 16,
                Component.literal("nom"));
        nameBox.setMaxLength(Waypoint.NAME_LIMIT);
        nameBox.setHint(Component.literal("nom du repère").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(nameBox);

        addRenderableWidget(Button.builder(Component.literal("Poser ici"), button -> place())
                .bounds(left + 168, top + panelHeight() - 27, 70, 18).build());

        addRenderableWidget(Button.builder(Component.literal("◆"), button -> nextTint())
                .bounds(left + 244, top + panelHeight() - 27, 20, 18)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.literal("Couleur du prochain repère")))
                .build());

        // Une action par ligne, posée en bouton invisible : cliquer la ligne bascule le partage,
        // la croix la retire. Des boutons empilés seraient illisibles à cette largeur.
        for (int i = 0; i < shown.size(); i++) {
            Waypoint mark = shown.get(i);
            int rowTop = top + HEADER + i * ROW_HEIGHT;
            boolean mine = isMine(mark);
            if (!mine) {
                continue;
            }
            addRenderableWidget(Button.builder(
                            Component.literal(mark.shared() ? "public" : "privé"),
                            button -> ask(WaypointAsk.Verb.SHARE, mark))
                    .bounds(left + WIDTH - 96, rowTop + 2, 46, 16).build());
            addRenderableWidget(Button.builder(Component.literal("✕"),
                            button -> ask(WaypointAsk.Verb.DROP, mark))
                    .bounds(left + WIDTH - 44, rowTop + 2, 16, 16).build());
            addRenderableWidget(Button.builder(Component.literal("◆"),
                            button -> recolour(mark))
                    .bounds(left + WIDTH - 24, rowTop + 2, 16, 16).build());
        }
    }

    private int panelHeight() {
        return HEADER + Math.max(1, shown.size()) * ROW_HEIGHT + 36;
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
            return;
        }
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new WaypointAsk(
                WaypointAsk.Verb.PLACE, name, this.minecraft.player.blockPosition(), tint));
        nameBox.setValue("");
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

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partial) {
        // En 26.1, Renderable ne dessine plus : il EXTRAIT un état que le moteur rendra ensuite.
        // Le nom a changé, la place dans le cycle aussi ; ce qu'on écrit ici, non.
        super.extractRenderState(graphics, mouseX, mouseY, partial);

        int left = (this.width - WIDTH) / 2;
        int top = Math.max(20, (this.height - panelHeight()) / 2);
        int bottom = top + panelHeight();

        graphics.fill(left - 1, top - 1, left + WIDTH + 1, bottom + 1, EDGE);
        graphics.fill(left, top, left + WIDTH, bottom, PANEL);
        graphics.fill(left, top + HEADER - 6, left + WIDTH, top + HEADER - 5, EDGE);

        graphics.text(this.font, "REPÈRES", left + 10, top + 10, 0xFFFFC857, false);
        String quota = shown.size() + " affiché" + (shown.size() > 1 ? "s" : "");
        graphics.text(this.font, quota,
                left + WIDTH - 10 - this.font.width(quota), top + 10, DIM, false);

        if (shown.isEmpty()) {
            graphics.text(this.font, "Aucun repère dans ce monde.",
                    left + 10, top + HEADER + 6, DIM, false);
        }

        for (int i = 0; i < shown.size(); i++) {
            Waypoint mark = shown.get(i);
            int rowTop = top + HEADER + i * ROW_HEIGHT;
            boolean mine = isMine(mark);
            graphics.fill(left + 4, rowTop, left + WIDTH - 4, rowTop + ROW_HEIGHT - 2,
                    mine ? ROW_MINE : ROW);
            // La pastille : quatre pixels de couleur valent mieux qu'un nom de couleur écrit.
            graphics.fill(left + 10, rowTop + 6, left + 18, rowTop + 14, 0xFF000000 | mark.colour());

            graphics.text(this.font, mark.name(), left + 24, rowTop + 7, TEXT, false);

            String far = String.format(Locale.ROOT, "%.0f m", Marks.flatDistance(mark));
            graphics.text(this.font, far, left + 160, rowTop + 7, DIM, false);
            graphics.text(this.font, arrow(Marks.bearing(mark)),
                    left + 196, rowTop + 7, 0xFF000000 | mark.colour(), false);

            if (!mine) {
                graphics.text(this.font, mark.ownerName(), left + 214, rowTop + 7, DIM, false);
            }
        }

        graphics.text(this.font, "Aucune téléportation : un repère dit où, pas comment.",
                left + 10, bottom - 46, DIM, false);
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
