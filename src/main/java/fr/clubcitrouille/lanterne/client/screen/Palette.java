package fr.clubcitrouille.lanterne.client.screen;

import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import fr.clubcitrouille.lanterne.client.Fit;
import fr.clubcitrouille.lanterne.client.upscale.Nuancier;
import fr.clubcitrouille.lanterne.client.upscale.Upscale;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'écran de sélection d'un nuancier — la version graphique de {@code /lanterne nuancier}.
 *
 * <h2>Pourquoi un écran alors que la commande fait déjà le travail</h2>
 *
 * <p>{@code /lanterne nuancier} existe déjà (voir {@link Nuancier#onRegisterClientCommands}) et
 * n'a besoin de rien de plus pour fonctionner : elle liste, active, désactive. Mais taper une
 * commande suppose de savoir qu'elle existe — et « je sais pas où voir » ne se résout pas avec une
 * ligne de chat qu'il faut deviner. Cet écran ne redéveloppe rien : il appelle
 * {@link Nuancier#listNames()} pour la liste et {@link Upscale#setNuancier(String)} pour le
 * changement, exactement comme le fait {@code Nuancier.choisir(String)} en coulisse.
 *
 * <h2>Pourquoi il reste ouvert après un clic</h2>
 *
 * <p>{@link Upscale#setNuancier(String)} pose le champ ET le fichier client dans le même geste —
 * voir son Javadoc : aucun redémarrage n'est nécessaire, la prochaine image dessinée montre déjà le
 * changement. Fermer l'écran au clic obligerait à le rouvrir pour essayer un autre nuancier ; le
 * garder ouvert, avec un mot de confirmation en haut et le bouton du nuancier choisi qui se
 * réétiquette « (actif) », permet d'essayer plusieurs nuanciers de suite sans naviguer deux fois.
 *
 * <h2>D'où on arrive ici</h2>
 *
 * <p>Depuis {@code client.Dials} — l'écran de réglages du mod, lui-même atteignable depuis
 * Options → Graphismes → 🎃 Lanterne — rendu (voir {@code mixin.VideoOptionsMixin}) ou depuis
 * Sodium quand il a remplacé l'écran vidéo (voir {@code client.sodium.Graft}). La ligne « Nuanciers »
 * de la famille « L'image » ouvre cet écran-ci en passant l'écran de réglages comme parent, pour
 * qu'Échap y ramène plutôt que de fermer tous les menus d'un coup.
 */
public class Palette extends Screen {
    private static final int SCRIM = 0xC8000000;
    private static final int PANEL = 0xE0141418;
    private static final int EDGE = 0xFF2A2A32;
    private static final int AMBER = 0xFFFFC857;
    private static final int DIM = 0xFF8A8A92;
    private static final int GOOD = 0xFF7FBF6A;
    private static final int BAD = 0xFFE06C5B;

    private static final int WIDTH = 280;
    private static final int HEADER = 26;
    private static final int STATUS = 14;
    /** Hauteur d'une ligne de la liste — un bouton par nuancier. */
    private static final int ROW = 20;
    /** Lignes visibles à la fois. Au-delà, la molette (voir {@link #mouseScrolled}). */
    private static final int VISIBLE = 6;
    private static final int FOOTER = 30;
    private static final int BOTTOM_PAD = 8;

    private final Screen parent;
    private final Fit fit = new Fit();

    private int panelX;
    private int panelY;
    private int scroll;

    /** Le dernier mot de l'écran, et l'instant où il a été dit. Même geste que {@code Dials.say}. */
    private String flash = "";
    private long flashAt;

    public Palette(Screen parent) {
        super(Component.literal("Nuanciers"));
        this.parent = parent;
    }

    private static List<String> names() {
        return Nuancier.listNames();
    }

    private int rowsShown(List<String> noms) {
        return Math.max(1, Math.min(VISIBLE, noms.size()));
    }

    private int panelHeight() {
        return HEADER + STATUS + rowsShown(names()) * ROW + FOOTER + BOTTOM_PAD;
    }

    @Override
    protected void init() {
        List<String> noms = names();
        this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, noms.size() - VISIBLE)));

        this.fit.measure(this.width, this.height, WIDTH, panelHeight());
        this.panelX = (this.fit.viewWidth() - WIDTH) / 2;
        this.panelY = Math.max(8, (this.fit.viewHeight() - panelHeight()) / 2);

        int left = this.panelX;
        int top = this.panelY;
        int listTop = top + HEADER + STATUS;

        String actif = Upscale.nuancierActif();
        for (int seat = 0; seat < VISIBLE && this.scroll + seat < noms.size(); seat++) {
            int index = this.scroll + seat;
            String nom = noms.get(index);
            boolean estActif = nom.equals(actif);
            int rowTop = listTop + seat * ROW;
            addRenderableWidget(Button.builder(
                            Component.literal((estActif ? "» " : "   ") + nom
                                    + (estActif ? "  (actif)" : "")),
                            button -> choose(nom))
                    .bounds(left + 10, rowTop, WIDTH - 20, ROW - 3)
                    .tooltip(Tooltip.create(Component.literal(estActif
                            ? "Déjà actif."
                            : "Clique pour activer — s'applique immédiatement, sans redémarrage.")))
                    .build());
        }

        int bottom = top + panelHeight();
        int halfWidth = (WIDTH - 24) / 2;
        addRenderableWidget(Button.builder(Component.literal("Désactiver"), button -> choose(""))
                .bounds(left + 10, bottom - FOOTER + 4, halfWidth, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Revient à la chaîne intégrée au mod — équivaut à \"/lanterne nuancier off\".")))
                .build());
        addRenderableWidget(Button.builder(Component.literal("Retour"), button -> onClose())
                .bounds(left + 14 + halfWidth, bottom - FOOTER + 4, halfWidth, 18)
                .build());
    }

    /** Même geste que {@code Nuancier.choisir(String)} — voir son Javadoc pour ce qui se passe. */
    private void choose(String nom) {
        String propre = nom == null ? "" : nom.trim();
        if (!propre.isEmpty() && !names().contains(propre)) {
            return;
        }
        Upscale.setNuancier(propre);
        this.flash = propre.isEmpty() ? "Nuancier désactivé — la chaîne intégrée reprend la main."
                : "Nuancier actif : " + propre + ".";
        this.flashAt = System.currentTimeMillis();
        rebuildWidgets();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Simule le clic qu'un joueur ferait sur ce nom, sans coordonnées de souris.
     *
     * <p>Réservé à {@link PaletteSelfCheck} (gated par {@code LANTERNE_PALETTE_TEST=1}, jamais actif
     * pour un joueur normal) : une vérification automatique n'a pas de fenêtre à viser au pixel près.
     * Exerce exactement le chemin qu'un vrai clic emprunte — {@link #choose(String)}, le même appelé
     * par {@code onPress} de chaque bouton de la liste — donc un test qui passe ici a réellement
     * exercé la logique de clic, pas une copie de celle-ci.
     */
    void testClick(String nom) {
        choose(nom);
    }

    // --- Le dessin ---------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partial) {
        this.fit.open(graphics);
        mouseX = (int) this.fit.x(mouseX);
        mouseY = (int) this.fit.y(mouseY);

        int left = this.panelX;
        int top = this.panelY;
        int bottom = top + panelHeight();

        graphics.fill(0, 0, this.fit.viewWidth(), this.fit.viewHeight(), SCRIM);
        graphics.fill(left - 1, top - 1, left + WIDTH + 1, bottom + 1, EDGE);
        graphics.fill(left, top, left + WIDTH, bottom, PANEL);
        graphics.fill(left, top, left + WIDTH, top + 2, AMBER);

        graphics.text(this.font, "NUANCIERS", left + 10, top + 9, AMBER, false);

        List<String> noms = names();
        if (noms.size() > VISIBLE) {
            String pos = (this.scroll + 1) + "–" + Math.min(noms.size(), this.scroll + VISIBLE)
                    + " / " + noms.size();
            graphics.text(this.font, pos, left + WIDTH - 10 - this.font.width(pos), top + 9, DIM,
                    false);
        }

        drawStatus(graphics, left, top + HEADER);

        if (noms.isEmpty()) {
            graphics.text(this.font, this.font.plainSubstrByWidth(
                            "Aucun trouvé sous config/lanterne/shaderpacks/.", WIDTH - 20),
                    left + 10, top + HEADER + STATUS + 4, DIM, false);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partial);
        this.fit.tooltips(graphics, mouseX, mouseY, partial);
        this.fit.close(graphics);
    }

    /** La ligne d'état : le dernier mot de l'écran, sinon l'avertissement lentille si pertinent. */
    private void drawStatus(GuiGraphicsExtractor graphics, int left, int y) {
        boolean fresh = !this.flash.isEmpty() && System.currentTimeMillis() - this.flashAt < 4000L;
        if (fresh) {
            graphics.text(this.font, this.font.plainSubstrByWidth(this.flash, WIDTH - 20),
                    left + 10, y, GOOD, false);
            return;
        }
        if (!Settings.lens()) {
            graphics.text(this.font, this.font.plainSubstrByWidth(
                            "La lentille est éteinte : un nuancier choisi ici ne s'appliquera qu'une"
                                    + " fois \"lentille\" allumée.", WIDTH - 20),
                    left + 10, y, BAD, false);
        }
    }

    // --- Les entrées, ramenées dans l'espace du panneau réduit -------------
    //
    // Même geste que client.screen.Console : voir son Javadoc pour les trois pièges de l'ajusteur.

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(this.fit.event(event), doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return super.mouseDragged(this.fit.event(event), dragX / this.fit.factor(),
                dragY / this.fit.factor());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return super.mouseReleased(this.fit.event(event));
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(this.fit.x(mouseX), this.fit.y(mouseY));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        List<String> noms = names();
        if (noms.size() > VISIBLE && scrollY != 0d) {
            this.scroll = Math.max(0, Math.min(noms.size() - VISIBLE,
                    this.scroll - (int) Math.signum(scrollY)));
            rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(this.fit.x(mouseX), this.fit.y(mouseY), scrollX, scrollY);
    }
}
