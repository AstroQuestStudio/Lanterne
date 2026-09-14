package fr.clubcitrouille.lanterne.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import fr.clubcitrouille.lanterne.core.ClientConfig;
import fr.clubcitrouille.lanterne.core.Lens;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Les cadrans : l'écran de réglages du rendu.
 *
 * <h2>Pourquoi un écran dessiné à la main plutôt que la liste d'options du jeu</h2>
 *
 * <p>{@code OptionsSubScreen} donne des boutons gris empilés et une infobulle qui s'efface dès qu'on
 * bouge la souris. Cela convient à un réglage dont on connaît déjà l'effet — le volume, la distance
 * de vue — pas à des modules dont tout l'intérêt tient au chiffre qu'ils ont rendu au banc.
 *
 * <p>Ici chaque ligne porte son état <b>en permanence</b> à droite, et la zone du bas explique celle
 * qu'on survole : ce qu'elle fait, ce qu'elle coûte, et si elle agit tout de suite ou au prochain
 * chargement. C'est la seule façon de rendre un arbitrage possible sans aller lire un fichier.
 *
 * <h2>Le compromis est toujours écrit</h2>
 *
 * <p>Aucun de ces réglages n'est gratuit. Les coffres perdent leur couvercle animé, les piles au sol
 * perdent leur indice de quantité, la Lentille perd de la netteté. Un écran qui vanterait les gains
 * sans nommer les pertes pousserait à tout activer, puis à se demander pourquoi le jeu a changé.
 * Chaque description dit donc les deux.
 */
public final class Dials extends Screen {
    private static final int SCRIM = 0xC0000000;
    private static final int PANEL = 0xF0141418;
    private static final int EDGE = 0xFF2A2A32;
    private static final int RAIL = 0xFF1C1C22;
    private static final int AMBER = 0xFFFFC857;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;
    private static final int HOVER = 0x24FFC857;
    private static final int ON = 0xFF6BCB77;
    private static final int OFF = 0xFF4A4A54;

    private static final int WIDTH = 430;
    private static final int HEADER = 40;
    private static final int ROW = 26;
    private static final int FOOTER = 48;

    private final Screen parent;
    private final List<Dial> dials = new ArrayList<>();
    private int hovered = -1;

    public Dials(Screen parent) {
        super(Component.literal("Lanterne"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.dials.clear();
        this.dials.add(Dial.toggle("Jauge de performance",
                Settings::gauge, ClientConfig.GAUGE,
                "Affiche images/s, centile le plus lent et échelle en cours, dans un coin de l'écran.",
                "F3 donne une moyenne arrondie ; la jauge donne les à-coups, qui sont ce qu'on ressent.",
                "Effet immédiat. Fenêtre glissante de deux secondes."));
        this.dials.add(Dial.cycle("Mise à l'échelle",
                () -> lensLabel(Lens.preset()),
                () -> Lens.preset() == Lens.Preset.NATIF ? DIM : AMBER,
                step -> cycleLens(step),
                "Rend le monde plus petit que l'écran, puis l'y étale. Le coût d'une image suit le "
                        + "nombre de pixels : à 67 % de chaque dimension, c'est 45 % de pixels en moins.",
                "Prix : l'interface perd en netteté. Effet immédiat.",
                "FSR n'est pas réservé aux cartes AMD — il tourne sur n'importe quel GPU."));
        this.dials.add(Dial.toggle("Le Voile — créatures",
                Settings::shroud, ClientConfig.SHROUD,
                "Une créature qu'un mur cache n'est pas préparée pour le rendu.",
                "1 000 vaches sous un toit : 29,9 → 238,8 images/s, soit ×7,98. Effet immédiat.", ""));
        this.dials.add(Dial.toggle("Le Voile — blocs",
                Settings::veilBlockEntities, ClientConfig.VEIL_BLOCK_ENTITIES,
                "Coffres, panneaux et fourneaux cachés derrière un mur ne sont pas préparés.",
                "1 200 coffres : 71,2 → 189,0 images/s, soit ×2,65. Effet immédiat.", ""));
        this.dials.add(Dial.toggle("Le Voile — particules",
                Settings::veilParticles, ClientConfig.VEIL_PARTICLES,
                "Une particule née derrière un mur ne naît pas du tout : ni création, ni tick, ni rendu.",
                "Vanilla ne filtre que la distance, et ne cull que le dessin. Effet immédiat.", ""));
        this.dials.add(Dial.toggle("Coffres statiques",
                Settings::staticChests, ClientConfig.STATIC_CHESTS,
                "Un coffre rendu comme un bloc ordinaire, maillé une fois dans son chunk.",
                "1 200 coffres : ×1,45. Prix : le couvercle ne s'anime plus à l'ouverture.",
                "Visible au prochain chargement du monde."));
        this.dials.add(Dial.cycle("Feuilles masquées",
                () -> leafLabel(Settings.leafCulling()),
                () -> Settings.leafCulling() == ClientConfig.LeafCulling.JAMAIS ? DIM : AMBER,
                step -> cycleLeaves(step),
                "Masque les faces que deux feuilles collées se cachent l'une l'autre.",
                "12 167 feuilles : ×1,42. AUTO suit ton option « Feuilles ajourées » et ne peut "
                        + "produire aucun artefact.",
                "Visible au prochain chargement du monde."));
        this.dials.add(Dial.cycle("Objets au sol",
                () -> Settings.itemCopies() + (Settings.itemCopies() > 1 ? " exemplaires" : " exemplaire"),
                () -> Settings.itemCopies() < 4 ? AMBER : DIM,
                step -> cycleItems(step),
                "Vanilla dessine une pile au sol jusqu'à CINQ fois, légèrement décalée.",
                "1 500 piles pleines : ×2,35. Prix : une pile de 64 ressemble à un objet seul.",
                "Effet immédiat."));
        this.dials.add(Dial.toggle("Sons superposés",
                Settings::din, ClientConfig.DIN,
                "Au-delà de trois fois, un même son au même endroit n'apporte plus rien.",
                "Confort, pas performance : le son vit sur son propre fil.",
                "Dans une ferme, la bouillie redevient un son."));
    }

    // --- Les actions -------------------------------------------------------

    private static void cycleLens(int step) {
        Lens.Preset[] all = Lens.Preset.values();
        Lens.Preset next = all[Math.floorMod(Lens.preset().ordinal() + step, all.length)];
        ClientConfig.LENS.set(next != Lens.Preset.NATIF);
        ClientConfig.LENS_PRESET.set(next);
        Settings.applyFromClientConfig();
    }

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

    private static String lensLabel(Lens.Preset preset) {
        String name = switch (preset) {
            case NATIF -> "Natif";
            case ULTRA_QUALITE -> "Ultra qualité";
            case QUALITE -> "Qualité";
            case EQUILIBRE -> "Équilibré";
            case PERFORMANCE -> "Performance";
            case ULTRA_PERFORMANCE -> "Ultra perf.";
        };
        return preset == Lens.Preset.NATIF ? name : name + "  ·  " + preset.pixelPercent() + " %";
    }

    private static String leafLabel(ClientConfig.LeafCulling mode) {
        return switch (mode) {
            case AUTO -> "Auto";
            case TOUJOURS -> "Toujours";
            case JAMAIS -> "Jamais";
        };
    }

    // --- Le dessin ---------------------------------------------------------

    private int panelHeight() {
        return HEADER + this.dials.size() * ROW + FOOTER;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partial) {
        super.extractRenderState(graphics, mouseX, mouseY, partial);

        // Un voile sombre sur tout l'écran : le panneau doit se détacher du monde, pas s'y fondre.
        graphics.fill(0, 0, this.width, this.height, SCRIM);

        final int left = (this.width - WIDTH) / 2;
        final int top = Math.max(16, (this.height - panelHeight()) / 2);
        final int right = left + WIDTH;
        final int bottom = top + panelHeight();

        graphics.fill(left - 1, top - 1, right + 1, bottom + 1, EDGE);
        graphics.fill(left, top, right, bottom, PANEL);
        // L'accent ambre en haut : la signature de la citrouille, et le seul aplat de couleur vive.
        graphics.fill(left, top, right, top + 2, AMBER);

        graphics.text(this.font, "LANTERNE", left + 14, top + 14, AMBER, false);
        graphics.text(this.font, "rendu", left + 78, top + 14, DIM, false);
        String hint = "clic gauche / droit pour changer  ·  Échap pour fermer";
        graphics.text(this.font, hint, right - 14 - this.font.width(hint), top + 14, DIM, false);
        graphics.fill(left + 12, top + HEADER - 8, right - 12, top + HEADER - 7, EDGE);

        this.hovered = -1;
        for (int i = 0; i < this.dials.size(); i++) {
            int rowTop = top + HEADER + i * ROW;
            boolean over = mouseX >= left + 8 && mouseX <= right - 8
                    && mouseY >= rowTop && mouseY < rowTop + ROW - 2;
            if (over) {
                this.hovered = i;
                graphics.fill(left + 8, rowTop, right - 8, rowTop + ROW - 2, HOVER);
                graphics.fill(left + 8, rowTop, left + 10, rowTop + ROW - 2, AMBER);
            }
            this.dials.get(i).draw(graphics, this.font, left + 18, rowTop + 8, right - 18);
        }

        // La zone du bas : ce que fait l'option survolée, et ce qu'elle coûte.
        int noteTop = bottom - FOOTER + 6;
        graphics.fill(left + 12, noteTop - 6, right - 12, noteTop - 5, EDGE);
        String[] lines = this.hovered >= 0
                ? this.dials.get(this.hovered).notes()
                : new String[] {
                        "Survole un réglage pour savoir ce qu'il fait — et ce qu'il coûte.",
                        "Chaque chiffre affiché ici sort d'un banc automatique, aucun n'est saisi à la main.",
                        ""};
        for (int i = 0; i < lines.length && i < 3; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            graphics.text(this.font, trim(lines[i], WIDTH - 36), left + 18, noteTop + i * 11,
                    i == 0 ? TEXT : DIM, false);
        }
    }

    /** Coupe une ligne trop longue plutôt que de la laisser déborder du panneau. */
    private String trim(String line, int maxWidth) {
        if (this.font.width(line) <= maxWidth) {
            return line;
        }
        String cut = line;
        while (cut.length() > 4 && this.font.width(cut + "…") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    /**
     * Clic gauche pour avancer, clic droit pour reculer.
     *
     * <p>26.1 passe un {@code MouseButtonEvent} plutôt que trois nombres — la position y est déjà
     * jointe au bouton. La rangée visée est celle que le dessin a marquée comme survolée : la
     * détection n'est donc écrite qu'une fois, et ce qui s'éclaire sous la souris est exactement ce
     * qui répondra au clic.
     */
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubled) {
        int button = event.button();
        if (this.hovered >= 0 && (button == 0 || button == 1)) {
            this.dials.get(this.hovered).click(button == 0 ? 1 : -1);
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
     * ne verrait rien, et conclurait qu'elle est cassée — c'est le défaut le plus courant des écrans
     * de réglages graphiques, et il se corrige en une ligne.
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

    /**
     * Une ligne de réglage.
     *
     * <p>Deux formes seulement : un interrupteur, dont l'état se lit d'un coup d'œil à la pastille,
     * et un sélecteur, dont la valeur s'écrit en toutes lettres. Pas de curseur — sur huit réglages,
     * il n'apporterait qu'une troisième façon de cliquer.
     */
    private static final class Dial {
        private final String name;
        private final boolean binary;
        private final BooleanSupplier state;
        private final ModConfigSpec.BooleanValue backing;
        private final Supplier<String> value;
        private final IntSupplier colour;
        private final java.util.function.IntConsumer step;
        private final String[] notes;

        private Dial(String name, boolean binary, BooleanSupplier state,
                ModConfigSpec.BooleanValue backing, Supplier<String> value, IntSupplier colour,
                java.util.function.IntConsumer step, String[] notes) {
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
                String what, String cost, String extra) {
            return new Dial(name, true, state, backing, () -> "", () -> AMBER, null,
                    new String[] {what, cost, extra});
        }

        static Dial cycle(String name, Supplier<String> value, IntSupplier colour,
                java.util.function.IntConsumer step, String what, String cost, String extra) {
            return new Dial(name, false, () -> false, null, value, colour, step,
                    new String[] {what, cost, extra});
        }

        String[] notes() {
            return this.notes;
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
                // Une piste et une pastille plutôt qu'un mot : l'état d'une colonne entière se lit
                // alors sans rien déchiffrer.
                boolean on = this.state.getAsBoolean();
                int trackLeft = right - 26;
                graphics.fill(trackLeft, y - 2, trackLeft + 26, y + 9, RAIL);
                graphics.fill(on ? trackLeft + 14 : trackLeft + 2, y,
                        on ? trackLeft + 24 : trackLeft + 12, y + 7, on ? ON : OFF);
            } else {
                String text = this.value.get();
                graphics.text(font, text, right - font.width(text), y, this.colour.getAsInt(), false);
            }
        }
    }
}
