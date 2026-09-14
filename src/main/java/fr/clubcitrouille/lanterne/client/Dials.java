package fr.clubcitrouille.lanterne.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import fr.clubcitrouille.lanterne.core.ClientConfig;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Les cadrans : l'écran de réglages du rendu, en trois colonnes.
 *
 * <h2>Pourquoi trois colonnes et non une liste</h2>
 *
 * <p>La première version empilait huit réglages dans un panneau unique, avec une zone d'explication
 * au bas. Deux défauts sautaient aux yeux dès qu'on s'en servait : on ne savait pas ce qui
 * appartenait à quoi, et l'explication changeait sous la souris au moindre mouvement.
 *
 * <p>La disposition retenue est celle qui a fait ses preuves sur l'autre mod de ce projet : une
 * <b>barre de familles</b> à gauche, la <b>liste des réglages</b> de la famille choisie au centre,
 * et un <b>panneau de détail</b> à droite. Chaque zone répond à une question différente — où
 * suis-je, que puis-je régler, qu'est-ce que cela fait — et aucune ne bouge quand ce n'est pas la
 * sienne.
 *
 * <h2>Le compromis est toujours écrit</h2>
 *
 * <p>Aucun de ces réglages n'est gratuit. Les coffres perdent leur couvercle animé, les piles au sol
 * perdent leur indice de quantité. Le panneau de détail dit donc trois choses dans cet ordre : ce
 * que le module fait, <b>ce qu'il coûte</b>, et quand l'effet est visible. Un écran qui ne vanterait
 * que les gains pousserait à tout activer, puis à se demander pourquoi le jeu a changé.
 */
public final class Dials extends Screen {
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
    private static final int ON = 0xFF6BCB77;
    private static final int OFF = 0xFF44444E;

    private static final int WIDTH = 560;
    private static final int HEIGHT = 292;
    private static final int HEADER = 34;
    private static final int FOOTER = 24;
    private static final int SIDEBAR = 132;
    private static final int DETAIL = 184;
    private static final int ROW = 24;
    private static final int TAB = 30;

    private final Screen parent;
    private final List<Family> families = new ArrayList<>();
    private int picked;
    private int hovered = -1;

    private int panelX;
    private int panelY;

    public Dials(Screen parent) {
        super(Component.literal("Lanterne"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.panelX = (this.width - WIDTH) / 2;
        this.panelY = Math.max(10, (this.height - HEIGHT) / 2);
        if (!this.families.isEmpty()) {
            return;
        }

        Family veil = new Family("Le Voile", "ce qu'un mur cache n'est pas dessiné");
        veil.add(Dial.toggle("Créatures", Settings::shroud, ClientConfig.SHROUD,
                "Une créature qu'un mur cache n'est pas préparée pour le rendu.",
                "1 000 vaches sous un toit : 29,9 → 238,8 im/s, soit ×7,98.",
                "Effet immédiat."));
        veil.add(Dial.toggle("Blocs-entités", Settings::veilBlockEntities,
                ClientConfig.VEIL_BLOCK_ENTITIES,
                "Coffres, panneaux et fourneaux derrière un mur ne sont pas préparés.",
                "1 200 coffres : 71,2 → 189,0 im/s, soit ×2,65.",
                "Effet immédiat."));
        veil.add(Dial.toggle("Particules", Settings::veilParticles, ClientConfig.VEIL_PARTICLES,
                "Une particule née derrière un mur ne naît pas : ni création, ni tick, ni rendu.",
                "Vanilla ne filtre que la distance, et n'écarte que le dessin.",
                "Effet immédiat."));
        this.families.add(veil);

        Family world = new Family("Le monde", "coffres, feuilles, objets au sol");
        world.add(Dial.toggle("Coffres statiques", Settings::staticChests,
                ClientConfig.STATIC_CHESTS,
                "Un coffre rendu comme un bloc ordinaire, maillé une fois dans son chunk.",
                "1 200 coffres : ×1,45. Prix : le couvercle ne s'anime plus.",
                "Visible au prochain chargement."));
        world.add(Dial.cycle("Feuilles masquées", () -> leafLabel(Settings.leafCulling()),
                () -> Settings.leafCulling() == ClientConfig.LeafCulling.JAMAIS ? DIM : AMBER,
                Dials::cycleLeaves,
                "Masque les faces que deux feuilles collées se cachent l'une l'autre.",
                "12 167 feuilles : ×1,42. AUTO suit ton option « Feuilles ajourées ».",
                "Visible au prochain chargement."));
        world.add(Dial.cycle("Objets au sol", () -> Settings.itemCopies() + " exempl.",
                () -> Settings.itemCopies() < 4 ? AMBER : DIM,
                Dials::cycleItems,
                "Vanilla dessine une pile au sol jusqu'à CINQ fois, légèrement décalée.",
                "1 500 piles pleines : ×2,35. Prix : une pile de 64 paraît seule.",
                "Effet immédiat."));
        this.families.add(world);

        Family image = new Family("L'image", "échelle de rendu et mesure");
        image.add(Dial.cycle("Mise à l'échelle", () -> "en chantier", () -> FAINT, step -> { },
                "Rendre le monde plus petit que l'écran, puis l'y étaler.",
                "La première version le dessinait dans un coin : blitToScreen n'étire pas.",
                "Repris sur une cible de scène séparée — l'interface restera nette."));
        image.add(Dial.toggle("Jauge de performance", Settings::gauge, ClientConfig.GAUGE,
                "Images/s, centile le plus lent et créatures voilées, dans un coin de l'écran.",
                "F3 donne une moyenne arrondie ; la jauge donne les à-coups.",
                "Effet immédiat. Fenêtre glissante de deux secondes."));
        this.families.add(image);

        Family sound = new Family("Les sons", "ce que l'oreille ne distingue pas");
        sound.add(Dial.toggle("Sons superposés", Settings::din, ClientConfig.DIN,
                "Au-delà de trois fois, un même son au même endroit n'apporte plus rien.",
                "Confort, pas performance : le son vit sur son propre fil.",
                "Dans une ferme, la bouillie redevient un son."));
        this.families.add(sound);
    }

    // --- Les actions -------------------------------------------------------

    private static void cycleLeaves(int step) {
        ClientConfig.LeafCulling[] all = ClientConfig.LeafCulling.values();
        int from = Settings.leafCulling().ordinal();
        ClientConfig.LEAF_CULLING.set(all[Math.floorMod(from + step, all.length)]);
        Settings.applyFromClientConfig();
    }

    private static void cycleItems(int step) {
        int next = Settings.itemCopies() + step;
        if (next < 1) {
            next = 4;
        } else if (next > 4) {
            next = 1;
        }
        ClientConfig.ITEM_COPIES.set(next);
        Settings.applyFromClientConfig();
    }

    private static String leafLabel(ClientConfig.LeafCulling mode) {
        return switch (mode) {
            case AUTO -> "Auto";
            case TOUJOURS -> "Toujours";
            case JAMAIS -> "Jamais";
        };
    }

    // --- Le dessin ---------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partial) {
        super.extractRenderState(graphics, mouseX, mouseY, partial);

        final int x = this.panelX;
        final int y = this.panelY;
        final int right = x + WIDTH;
        final int bottom = y + HEIGHT;

        graphics.fill(0, 0, this.width, this.height, SCRIM);
        graphics.fill(x - 1, y - 1, right + 1, bottom + 1, EDGE);
        graphics.fill(x, y, right, bottom, PANEL);
        graphics.fill(x, y, x + SIDEBAR, bottom, SIDE);
        graphics.fill(x, y, right, y + 2, AMBER);

        graphics.text(this.font, "LANTERNE", x + 12, y + 13, AMBER, false);
        String tag = "rendu";
        graphics.text(this.font, tag, right - 12 - this.font.width(tag), y + 13, FAINT, false);

        graphics.fill(x + SIDEBAR, y + 2, x + SIDEBAR + 1, bottom, EDGE);
        graphics.fill(right - DETAIL - 1, y + HEADER, right - DETAIL, bottom - FOOTER, EDGE);
        graphics.fill(x + SIDEBAR, y + HEADER - 1, right, y + HEADER, EDGE);
        graphics.fill(x + SIDEBAR, bottom - FOOTER, right, bottom - FOOTER + 1, EDGE);

        drawFamilies(graphics, x, y, mouseX, mouseY);
        drawRows(graphics, x, y, right, mouseX, mouseY);
        drawDetail(graphics, right - DETAIL + 10, y + HEADER + 10, DETAIL - 22);

        String hint = "clic gauche / droit pour changer   ·   Échap pour fermer";
        graphics.text(this.font, hint, x + SIDEBAR + 12, bottom - FOOTER + 8, FAINT, false);
    }

    private void drawFamilies(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
        for (int i = 0; i < this.families.size(); i++) {
            int top = y + HEADER + 4 + i * TAB;
            boolean over = inside(mouseX, mouseY, x + 4, top, x + SIDEBAR - 5, top + TAB - 4);
            if (i == this.picked) {
                graphics.fill(x + 4, top, x + SIDEBAR - 5, top + TAB - 4, PICKED);
                graphics.fill(x + 4, top, x + 6, top + TAB - 4, AMBER);
            } else if (over) {
                graphics.fill(x + 4, top, x + SIDEBAR - 5, top + TAB - 4, HOVER);
            }
            Family family = this.families.get(i);
            graphics.text(this.font, family.name, x + 14, top + 4,
                    i == this.picked ? AMBER : TEXT, false);
            graphics.text(this.font, family.dials.size() + " réglages", x + 14, top + 15,
                    FAINT, false);
        }
    }

    private void drawRows(GuiGraphicsExtractor graphics, int x, int y, int right,
            int mouseX, int mouseY) {
        List<Dial> rows = this.families.get(this.picked).dials;
        int listLeft = x + SIDEBAR + 10;
        int listRight = right - DETAIL - 12;
        this.hovered = -1;
        for (int i = 0; i < rows.size(); i++) {
            int top = y + HEADER + 8 + i * ROW;
            if (inside(mouseX, mouseY, listLeft, top, listRight, top + ROW - 2)) {
                this.hovered = i;
                graphics.fill(listLeft, top, listRight, top + ROW - 2, HOVER);
            }
            rows.get(i).draw(graphics, this.font, listLeft + 8, top + 7, listRight - 8);
        }
    }

    private void drawDetail(GuiGraphicsExtractor graphics, int x, int y, int width) {
        List<Dial> rows = this.families.get(this.picked).dials;
        if (this.hovered < 0) {
            Family family = this.families.get(this.picked);
            graphics.text(this.font, family.name, x, y, AMBER, false);
            int line = y + 14;
            for (String part : wrap(family.subtitle, width)) {
                graphics.text(this.font, part, x, line, DIM, false);
                line += 10;
            }
            graphics.text(this.font, "Survole un réglage.", x, line + 8, FAINT, false);
            return;
        }
        Dial dial = rows.get(this.hovered);
        graphics.text(this.font, dial.name, x, y, AMBER, false);
        int line = y + 16;
        int[] colours = {TEXT, DIM, FAINT};
        for (int i = 0; i < dial.notes.length; i++) {
            if (dial.notes[i].isEmpty()) {
                continue;
            }
            for (String part : wrap(dial.notes[i], width)) {
                graphics.text(this.font, part, x, line, colours[Math.min(i, 2)], false);
                line += 10;
            }
            line += 4;
        }
    }

    /** Coupe un paragraphe en lignes qui tiennent dans la colonne de détail. */
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

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        int button = event.button();
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();

        for (int i = 0; i < this.families.size(); i++) {
            int top = this.panelY + HEADER + 4 + i * TAB;
            if (inside(mouseX, mouseY, this.panelX + 4, top,
                    this.panelX + SIDEBAR - 5, top + TAB - 4)) {
                this.picked = i;
                this.hovered = -1;
                return true;
            }
        }
        if (this.hovered >= 0 && (button == 0 || button == 1)) {
            this.families.get(this.picked).dials.get(this.hovered).click(button == 0 ? 1 : -1);
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * À la fermeture, on reconstruit la géométrie.
     *
     * <p>Les coffres et les feuilles décident de la <em>manière de mailler</em> un chunk ; leur
     * changement ne se voit qu'après reconstruction. Sans cet appel, un joueur basculerait l'option,
     * ne verrait rien, et conclurait qu'elle est cassée.
     */
    @Override
    public void onClose() {
        if (this.minecraft != null) {
            if (this.minecraft.levelRenderer != null && this.minecraft.level != null) {
                this.minecraft.levelRenderer.allChanged();
            }
            fr.clubcitrouille.lanterne.core.Shroud.forget();
            this.minecraft.setScreen(this.parent);
        }
    }

    /** Une famille de réglages — une entrée de la barre de gauche. */
    private static final class Family {
        private final String name;
        private final String subtitle;
        private final List<Dial> dials = new ArrayList<>();

        Family(String name, String subtitle) {
            this.name = name;
            this.subtitle = subtitle;
        }

        void add(Dial dial) {
            this.dials.add(dial);
        }
    }

    /**
     * Une ligne de réglage.
     *
     * <p>Deux formes seulement : un interrupteur, dont l'état se lit d'un coup d'œil à la pastille,
     * et un sélecteur, dont la valeur s'écrit en toutes lettres.
     */
    private static final class Dial {
        private final String name;
        private final boolean binary;
        private final BooleanSupplier state;
        private final ModConfigSpec.BooleanValue backing;
        private final Supplier<String> value;
        private final IntSupplier colour;
        private final IntConsumer step;
        private final String[] notes;

        private Dial(String name, boolean binary, BooleanSupplier state,
                ModConfigSpec.BooleanValue backing, Supplier<String> value, IntSupplier colour,
                IntConsumer step, String[] notes) {
            this.name = name;
            this.binary = binary;
            this.state = state;
            this.backing = backing;
            this.value = value;
            this.colour = colour;
            this.step = step;
            this.notes = notes;
        }

        static Dial toggle(String name, BooleanSupplier state, ModConfigSpec.BooleanValue backing,
                String what, String cost, String when) {
            return new Dial(name, true, state, backing, () -> "", () -> AMBER, null,
                    new String[] {what, cost, when});
        }

        static Dial cycle(String name, Supplier<String> value, IntSupplier colour, IntConsumer step,
                String what, String cost, String when) {
            return new Dial(name, false, () -> false, null, value, colour, step,
                    new String[] {what, cost, when});
        }

        void click(int direction) {
            if (this.binary) {
                this.backing.set(!this.state.getAsBoolean());
                Settings.applyFromClientConfig();
            } else {
                this.step.accept(direction);
            }
        }

        void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int right) {
            graphics.text(font, this.name, x, y, TEXT, false);
            if (this.binary) {
                boolean on = this.state.getAsBoolean();
                int track = right - 24;
                graphics.fill(track, y - 2, track + 24, y + 9, RAIL);
                graphics.fill(on ? track + 13 : track + 2, y,
                        on ? track + 22 : track + 11, y + 7, on ? ON : OFF);
            } else {
                String text = this.value.get();
                graphics.text(font, text, right - font.width(text), y, this.colour.getAsInt(), false);
            }
        }
    }
}
