package fr.clubcitrouille.lanterne.client.ponder;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import fr.clubcitrouille.lanterne.client.Fit;

/**
 * La salle : l'écran du guide.
 *
 * <h2>Trois zones, et aucune ne bouge quand ce n'est pas la sienne</h2>
 *
 * <p>La liste des chapitres à gauche, le plateau au centre, la légende et la barre de lecture en bas.
 * C'est la disposition de {@code client/Dials.java}, et c'est volontaire : un joueur qui a appris à
 * lire un écran du mod doit savoir lire les autres sans qu'on le lui dise.
 *
 * <h2>Le temps est lu à la montre, pas au tick</h2>
 *
 * <p>L'horloge de la scène avance en lisant {@code System.nanoTime}, et non les ticks du jeu. Trois
 * raisons, dont la dernière est décisive :
 *
 * <ul>
 *   <li>l'animation reste fluide quelle que soit la cadence du serveur, y compris sur un serveur qui
 *       peine — et c'est précisément le serveur sur lequel ce mod est censé tourner&nbsp;;</li>
 *   <li>elle continue si le jeu est en pause&nbsp;;</li>
 *   <li>et surtout : une scène étant une <b>fonction du temps</b> — voir {@link Scene} — l'horloge
 *       n'a aucun état à préserver. On peut la reculer, la tirer à la main, la faire sauter : il n'y a
 *       rien derrière elle qui puisse se désaccorder.</li>
 * </ul>
 *
 * <p>C'est ce qui permet à la barre du bas d'accepter le clic <b>n'importe où</b>. Chez Ponder, dont
 * ce paquet s'inspire, elle ne l'accepte que sur des repères posés à la main par l'auteur, parce que
 * chaque saut y coûte un rejeu complet de la scène.
 *
 * <p>L'horloge avance dans {@link #extractRenderState}, ce qui n'est pas un endroit très propre pour
 * faire avancer quoi que ce soit. C'est le seul crochet appelé une fois par image, et le faire dans
 * {@code tick()} plafonnerait l'animation à vingt images par seconde.
 */
public final class Theatre extends Screen {
    private static final int SCRIM = 0xC8000000;
    private static final int PANEL = 0xF2141418;
    private static final int SIDE = 0xFF101014;
    private static final int STAGE = 0xFF0C0C10;
    private static final int EDGE = 0xFF2A2A32;
    private static final int AMBER = 0xFFFFC857;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;
    private static final int FAINT = 0xFF5A5A62;
    private static final int HOVER = 0x20FFC857;
    private static final int PICKED = 0x2EFFC857;
    private static final int GROOVE = 0xFF23232B;

    private static final int WIDTH = 560;
    private static final int HEIGHT = 300;
    private static final int HEADER = 34;
    private static final int SIDEBAR = 132;
    /**
     * Le bas du panneau : trois lignes de légende, un blanc, la barre de lecture.
     *
     * <p>Trois lignes et non deux : la phrase la plus longue du guide en demande trois à cette
     * largeur, et une légende tronquée au milieu d'une explication est pire qu'une légende absente.
     * Le compte a été fait à la main plutôt que deviné, et c'est lui qui fixe ce nombre.
     */
    private static final int FOOTER = 72;
    private static final int ROW = 28;
    private static final int LINES = 3;

    /** Un tick dure cinquante millions de nanosecondes. */
    private static final double TICK = 5.0e7d;
    /** Plafond du saut d'horloge entre deux images : une image longue ne doit pas sauter la scène. */
    private static final float LEAP = 4f;

    private final Screen parent;
    private final Fit fit = new Fit();
    /** Un seul plateau pour toute la vie de l'écran : il ne porte qu'une caméra, qu'on repose. */
    private final Stage stage = new Stage();

    private int panelX;
    private int panelY;

    private int chapter;
    private float clock;
    private boolean playing = true;
    private boolean scrubbing;
    private long lastNanos;

    private Theatre(Screen parent) {
        super(Component.translatable("lanterne.guide.titre"));
        this.parent = parent;
    }

    /**
     * Ouvre le guide.
     *
     * @param parent l'écran auquel revenir, ou {@code null} pour revenir au jeu
     */
    public static void open(Screen parent) {
        if (!Guide.ready()) {
            return;
        }
        Minecraft.getInstance().gui.setScreen(new Theatre(parent));
    }

    /** Ouvre le guide sur un sujet nommé, ou sur le premier si le nom est inconnu. */
    public static void open(Screen parent, String subject) {
        if (!Guide.ready()) {
            return;
        }
        Theatre screen = new Theatre(parent);
        List<Scene> all = Guide.all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).key().equalsIgnoreCase(subject)) {
                screen.chapter = i;
                break;
            }
        }
        Minecraft.getInstance().gui.setScreen(screen);
    }

    /** Ouvre le guide directement sur une scène. Le chemin de {@link Hint}, depuis un objet survolé. */
    public static void open(Screen parent, Scene scene) {
        if (!Guide.ready()) {
            return;
        }
        Theatre screen = new Theatre(parent);
        screen.chapter = Math.max(0, Guide.all().indexOf(scene));
        Minecraft.getInstance().gui.setScreen(screen);
    }

    /** Les sujets connus, pour la complétion de la commande. */
    public static List<String> subjects() {
        return Guide.all().stream().map(Scene::key).toList();
    }

    @Override
    protected void init() {
        this.fit.measure(this.width, this.height, WIDTH, HEIGHT);
        this.panelX = (this.fit.viewWidth() - WIDTH) / 2;
        this.panelY = Math.max(6, (this.fit.viewHeight() - HEIGHT) / 2);
    }

    /**
     * L'écran n'a-t-il plus rien à montrer ?
     *
     * <p>À demander avant toute entrée. Le dessin se retire de lui-même quand le catalogue se vide,
     * mais un clic ou une touche peut arriver dans l'intervalle, et {@link #scene()} lirait alors
     * hors d'une liste vide.
     */
    private boolean vacant() {
        return Guide.all().isEmpty();
    }

    /**
     * La scène courante.
     *
     * <p>Le numéro de chapitre est ramené dans les bornes à chaque lecture plutôt que gardé juste par
     * construction. C'est bon marché, et cela couvre le cas qu'on n'écrirait jamais exprès : le
     * catalogue est lié au monde chargé — voir {@link Guide#all()} — donc il peut <em>changer de
     * taille sous l'écran</em> si le monde change pendant qu'il est ouvert.
     *
     * <p>N'est appelée que lorsque le catalogue n'est pas vide ; {@link #extractRenderState} ferme
     * l'écran avant, et {@link #vacant()} garde les entrées.
     */
    private Scene scene() {
        List<Scene> all = Guide.all();
        this.chapter = Math.max(0, Math.min(this.chapter, all.size() - 1));
        return all.get(this.chapter);
    }

    // --- Le temps ------------------------------------------------------------

    private void advance() {
        long nanos = System.nanoTime();
        if (this.lastNanos == 0L) {
            this.lastNanos = nanos;
            return;
        }
        float step = (float) ((nanos - this.lastNanos) / TICK);
        this.lastNanos = nanos;
        if (!this.playing || this.scrubbing) {
            return;
        }
        this.clock += Math.min(step, LEAP);
        if (this.clock >= scene().length()) {
            this.clock = scene().length();
            this.playing = false;
        }
    }

    private void seek(float t) {
        this.clock = Math.max(0f, Math.min(scene().length(), t));
    }

    /** Le repère de chapitre le plus proche dans la direction demandée. */
    private void step(int way) {
        int[] marks = scene().marks();
        if (way < 0) {
            float best = 0f;
            for (int m : marks) {
                // Une marge : reculer juste après un repère doit ramener au repère précédent, sans
                // quoi le bouton paraît ne rien faire pendant les premières secondes d'un chapitre.
                if (m < this.clock - 6f) {
                    best = m;
                }
            }
            seek(best);
        } else {
            float best = scene().length();
            for (int m : marks) {
                if (m > this.clock + 1f) {
                    best = m;
                    break;
                }
            }
            seek(best);
        }
        this.playing = true;
    }

    private void toggle() {
        if (!this.playing && this.clock >= scene().length()) {
            this.clock = 0f;
        }
        this.playing = !this.playing;
    }

    // --- Le dessin -----------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partial) {
        super.extractRenderState(graphics, mouseX, mouseY, partial);
        // Le catalogue n'existe que dans un monde chargé. Quitter sa partie en laissant le guide
        // ouvert le viderait sous l'écran : on se retire plutôt que de dessiner du vide.
        if (Guide.all().isEmpty()) {
            onClose();
            return;
        }
        advance();

        this.fit.open(graphics);
        // La souris arrive dans l'espace de l'écran, tout ce qui suit raisonne dans celui du panneau.
        int mx = (int) this.fit.x(mouseX);
        int my = (int) this.fit.y(mouseY);

        final int x = this.panelX;
        final int y = this.panelY;
        final int right = x + WIDTH;
        final int bottom = y + HEIGHT;

        graphics.fill(0, 0, this.fit.viewWidth(), this.fit.viewHeight(), SCRIM);
        graphics.fill(x - 1, y - 1, right + 1, bottom + 1, EDGE);
        graphics.fill(x, y, right, bottom, PANEL);
        graphics.fill(x, y, x + SIDEBAR, bottom, SIDE);
        graphics.fill(x, y, right, y + 2, AMBER);

        graphics.text(this.font, "LANTERNE", x + 12, y + 13, AMBER, false);
        String tag = Component.translatable("lanterne.guide.enseigne").getString();
        graphics.text(this.font, tag, right - 12 - this.font.width(tag), y + 13, FAINT, false);

        graphics.fill(x + SIDEBAR, y + 2, x + SIDEBAR + 1, bottom, EDGE);
        graphics.fill(x + SIDEBAR, y + HEADER - 1, right, y + HEADER, EDGE);
        graphics.fill(x + SIDEBAR, bottom - FOOTER, right, bottom - FOOTER + 1, EDGE);

        drawChapters(graphics, x, y, mx, my);
        drawKeys(graphics, x, bottom);
        drawStage(graphics, x + SIDEBAR + 1, y + HEADER, right, bottom - FOOTER);
        drawCaption(graphics, x + SIDEBAR + 12, bottom - FOOTER + 8, WIDTH - SIDEBAR - 24);
        drawTransport(graphics, mx, my);

        this.fit.close(graphics);
    }

    private void drawChapters(GuiGraphicsExtractor graphics, int x, int y, int mx, int my) {
        List<Scene> all = Guide.all();
        for (int i = 0; i < all.size(); i++) {
            int top = y + HEADER + 4 + i * ROW;
            boolean over = inside(mx, my, x + 4, top, x + SIDEBAR - 5, top + ROW - 4);
            if (i == this.chapter) {
                graphics.fill(x + 4, top, x + SIDEBAR - 5, top + ROW - 4, PICKED);
                graphics.fill(x + 4, top, x + 6, top + ROW - 4, AMBER);
            } else if (over) {
                graphics.fill(x + 4, top, x + SIDEBAR - 5, top + ROW - 4, HOVER);
            }
            Scene s = all.get(i);
            graphics.fakeItem(s.icon(), x + 12, top + 4);
            String title = Component.translatable("lanterne.guide." + s.key() + ".titre").getString();
            graphics.text(this.font, this.font.plainSubstrByWidth(title, SIDEBAR - 46),
                    x + 34, top + 8, i == this.chapter ? AMBER : over ? TEXT : DIM, false);
        }
    }

    /**
     * Les raccourcis, au pied de la colonne de gauche.
     *
     * <p>Ils étaient d'abord au bas du panneau, sur la même ligne que la légende. C'est là qu'ils
     * dérangeaient le plus : la légende est ce qu'on lit, les raccourcis sont ce qu'on cherche une
     * fois, et les mettre côte à côte fait qu'on relit les seconds chaque fois qu'on lit la première.
     * La colonne de gauche, elle, est vide sous la liste des chapitres.
     */
    private void drawKeys(GuiGraphicsExtractor graphics, int x, int bottom) {
        List<FormattedCharSequence> lines =
                this.font.split(Component.translatable("lanterne.guide.aide"), SIDEBAR - 24);
        int top = bottom - 10 - lines.size() * 10;
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(this.font, lines.get(i), x + 12, top + i * 10, FAINT, false);
        }
    }

    /**
     * Le plateau.
     *
     * <p>Les ciseaux ne sont pas un ornement : un plan rapproché sort largement du hublot, et sans
     * eux le décor déborderait sur la liste des chapitres et sur la légende.
     */
    private void drawStage(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
        graphics.fill(x0, y0, x1, y1, STAGE);
        graphics.enableScissor(x0, y0, x1, y1);

        Scene scene = scene();
        // Le décor est posé au tiers gauche : la colonne de droite appartient aux étiquettes, et un
        // décor centré les ferait écrire par-dessus lui. En hauteur, au milieu exactement — le hublot
        // est bien plus large que haut, et c'est la hauteur qui manque.
        this.stage.centre(x0 + (x1 - x0) * 0.32f, y0 + (y1 - y0) * 0.50f);
        scene.place(this.stage, this.clock);
        scene.draw(graphics, this.stage, this.clock);

        drawTags(graphics, scene, this.stage, x0, x1);
        graphics.disableScissor();
    }

    /**
     * Les étiquettes accrochées au monde.
     *
     * <p>L'ordonnée suit le point visé, l'abscisse est figée. C'est la trouvaille de Ponder, et elle
     * est juste : une colonne de texte qui se déplacerait avec la caméra serait illisible pendant
     * qu'elle bouge, c'est-à-dire exactement quand on en a besoin.
     */
    private void drawTags(GuiGraphicsExtractor graphics, Scene scene, Stage stage, int x0, int x1) {
        int deskX = x0 + (int) ((x1 - x0) * 0.56f);
        int room = x1 - deskX - 10;
        if (room < 40) {
            return;
        }
        for (Scene.Tag t : scene.tagList()) {
            float fade = Scene.window(this.clock, t.from(), t.to());
            if (fade <= 0.02f) {
                continue;
            }
            int anchorX = Math.round(stage.screenX(t.x(), t.y(), t.z()));
            int anchorY = Math.round(stage.screenY(t.x(), t.y(), t.z()));

            graphics.fill(Math.min(anchorX, deskX), anchorY, Math.max(anchorX, deskX), anchorY + 1,
                    Scene.tint(0xFFC857, fade * 0.8f));
            graphics.fill(anchorX - 2, anchorY - 2, anchorX + 2, anchorY + 2,
                    Scene.tint(0xFFC857, fade));

            List<FormattedCharSequence> lines =
                    this.font.split(Component.translatable(t.key()), room);
            int top = anchorY - lines.size() * 5;
            graphics.fill(deskX - 3, top - 4, deskX + room + 3, top + lines.size() * 10 + 2,
                    Scene.tint(0x101014, fade * 0.88f));
            graphics.fill(deskX - 3, top - 4, deskX - 2, top + lines.size() * 10 + 2,
                    Scene.tint(0xFFC857, fade));
            for (int i = 0; i < lines.size(); i++) {
                graphics.text(this.font, lines.get(i), deskX + 2, top + i * 10,
                        Scene.tint(0xE8E8E8, fade), false);
            }
        }
    }

    private void drawCaption(GuiGraphicsExtractor graphics, int x, int y, int room) {
        String key = scene().word(this.clock);
        if (key == null) {
            return;
        }
        // Un fondu court depuis le repère de chapitre courant : une légende qui se substitue d'un
        // coup à la précédente se lit comme une faute d'affichage.
        float since = this.clock;
        for (int m : scene().marks()) {
            if (m <= this.clock) {
                since = this.clock - m;
            }
        }
        float fade = Ease.out(Ease.over(since, 0f, 6f));
        List<FormattedCharSequence> lines = this.font.split(Component.translatable(key), room);
        for (int i = 0; i < Math.min(LINES, lines.size()); i++) {
            graphics.text(this.font, lines.get(i), x, y + i * 10,
                    Scene.tint(0xE8E8E8, fade), false);
        }
    }

    // --- La barre de lecture -------------------------------------------------

    private int transportY() {
        return this.panelY + HEIGHT - 24;
    }

    private int trackX0() {
        return this.panelX + SIDEBAR + 90;
    }

    private int trackX1() {
        return this.panelX + WIDTH - 14;
    }

    /** Les quatre boutons, de gauche à droite : reprendre, chapitre précédent, lire, chapitre suivant. */
    private int buttonX(int index) {
        return this.panelX + SIDEBAR + 12 + index * 19;
    }

    private void drawTransport(GuiGraphicsExtractor graphics, int mx, int my) {
        int y = transportY();
        Scene scene = scene();

        for (int i = 0; i < 4; i++) {
            int bx = buttonX(i);
            boolean over = inside(mx, my, bx - 3, y - 3, bx + 15, y + 15);
            if (over) {
                graphics.fill(bx - 3, y - 3, bx + 15, y + 15, HOVER);
            }
            int ink = over ? AMBER : TEXT;
            switch (i) {
                case 0 -> {
                    arrow(graphics, bx, y + 2, 5, 8, false, ink);
                    arrow(graphics, bx + 6, y + 2, 5, 8, false, ink);
                }
                case 1 -> {
                    graphics.fill(bx, y + 2, bx + 2, y + 10, ink);
                    arrow(graphics, bx + 3, y + 2, 7, 8, false, ink);
                }
                case 2 -> {
                    if (this.playing) {
                        graphics.fill(bx + 1, y + 2, bx + 4, y + 10, ink);
                        graphics.fill(bx + 6, y + 2, bx + 9, y + 10, ink);
                    } else {
                        arrow(graphics, bx + 1, y + 2, 8, 8, true, ink);
                    }
                }
                default -> {
                    arrow(graphics, bx, y + 2, 7, 8, true, ink);
                    graphics.fill(bx + 8, y + 2, bx + 10, y + 10, ink);
                }
            }
        }

        int tx0 = trackX0();
        int tx1 = trackX1();
        int ty = y + 5;
        graphics.fill(tx0, ty, tx1, ty + 3, GROOVE);

        float share = scene.length() == 0 ? 0f : this.clock / scene.length();
        int head = tx0 + Math.round((tx1 - tx0) * share);
        graphics.fill(tx0, ty, head, ty + 3, AMBER);

        for (int m : scene.marks()) {
            int px = tx0 + Math.round((tx1 - tx0) * (m / (float) Math.max(1, scene.length())));
            graphics.fill(px, ty - 3, px + 1, ty + 6, px <= head ? AMBER : FAINT);
        }
        graphics.fill(head - 1, ty - 4, head + 2, ty + 7, TEXT);
    }

    /**
     * Un triangle plein, dessiné en colonnes d'un pixel.
     *
     * <p>Les caractères de lecture — ▶, ❚❚ — ne sont pas garantis dans toutes les polices de
     * ressources, et un bouton qui se transforme en carré blanc chez un joueur qui a changé de pack
     * est un défaut qu'on ne verrait jamais en développement.
     */
    private static void arrow(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
            boolean right, int colour) {
        for (int i = 0; i < w; i++) {
            int half = Math.max(1, Math.round(h * 0.5f * (1f - (float) i / w)));
            int cx = right ? x + i : x + w - 1 - i;
            graphics.fill(cx, y + h / 2 - half, cx + 1, y + h / 2 + half, colour);
        }
    }

    // --- Les entrées ---------------------------------------------------------

    private static boolean inside(int mx, int my, int x0, int y0, int x1, int y1) {
        return mx >= x0 && mx <= x1 && my >= y0 && my <= y1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (vacant()) {
            return false;
        }
        int mx = (int) this.fit.x(event.x());
        int my = (int) this.fit.y(event.y());
        if (event.button() != 0) {
            return super.mouseClicked(event, doubled);
        }

        List<Scene> all = Guide.all();
        for (int i = 0; i < all.size(); i++) {
            int top = this.panelY + HEADER + 4 + i * ROW;
            if (inside(mx, my, this.panelX + 4, top, this.panelX + SIDEBAR - 5, top + ROW - 4)) {
                this.chapter = i;
                this.clock = 0f;
                this.playing = true;
                return true;
            }
        }

        int y = transportY();
        for (int i = 0; i < 4; i++) {
            int bx = buttonX(i);
            if (inside(mx, my, bx - 3, y - 3, bx + 15, y + 15)) {
                switch (i) {
                    case 0 -> {
                        this.clock = 0f;
                        this.playing = true;
                    }
                    case 1 -> step(-1);
                    case 2 -> toggle();
                    default -> step(1);
                }
                return true;
            }
        }

        if (inside(mx, my, trackX0() - 3, y - 2, trackX1() + 3, y + 14)) {
            this.scrubbing = true;
            grab(mx);
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (this.scrubbing && !vacant()) {
            grab((int) this.fit.x(event.x()));
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.scrubbing = false;
        return super.mouseReleased(event);
    }

    private void grab(int mx) {
        int tx0 = trackX0();
        int tx1 = trackX1();
        seek((mx - tx0) / (float) Math.max(1, tx1 - tx0) * scene().length());
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (vacant()) {
            return false;
        }
        // La molette parcourt le temps, et non la liste : c'est le geste qu'on fait sans y penser
        // devant une animation, et la liste tient de toute façon en entier à l'écran.
        seek(this.clock - (float) scrollY * 10f);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (vacant()) {
            return super.keyPressed(event);
        }
        switch (event.key()) {
            case InputConstants.KEY_SPACE -> {
                toggle();
                return true;
            }
            case InputConstants.KEY_LEFT -> {
                step(-1);
                return true;
            }
            case InputConstants.KEY_RIGHT -> {
                step(1);
                return true;
            }
            case InputConstants.KEY_R -> {
                this.clock = 0f;
                this.playing = true;
                return true;
            }
            default -> {
                return super.keyPressed(event);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}
