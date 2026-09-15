package fr.clubcitrouille.lanterne.client.screen;

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

import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import fr.clubcitrouille.lanterne.client.Fit;
import fr.clubcitrouille.lanterne.content.screen.BeamerEntity;
import fr.clubcitrouille.lanterne.content.screen.Clock;
import fr.clubcitrouille.lanterne.content.screen.Feed;
import fr.clubcitrouille.lanterne.content.screen.Mail;
import fr.clubcitrouille.lanterne.content.screen.Sieve;
import fr.clubcitrouille.lanterne.content.screen.Stage;

/**
 * L'écran de réglages d'un écran ou d'un projecteur.
 *
 * <h2>Un seul écran pour les deux blocs</h2>
 *
 * <p>Ils partagent tout sauf deux nombres — la portée et l'envergure du projecteur. Deux interfaces
 * auraient dupliqué quinze contrôles pour en ajouter deux, et surtout : elles auraient divergé. La
 * ligne de visée n'apparaît donc que pour le projecteur, et le reste est commun.
 *
 * <h2>Ce que cet écran refuse de cacher</h2>
 *
 * <p>Trois choses y sont écrites en clair alors qu'aucune n'est agréable à lire, et c'est délibéré.
 *
 * <p><b>Le domaine</b> de la source, à côté du champ. Un lien de deux cents caractères ne se lit pas ;
 * le domaine, si, et c'est exactement l'information dont on a besoin pour décider si l'on veut être
 * vu en train de le demander.
 *
 * <p><b>Le verdict du tamis</b>, avant l'envoi. Taper une adresse, valider, et recevoir un refus dans
 * le fil de discussion, c'est apprendre trop tard. La ligne le dit pendant qu'on tape.
 *
 * <p><b>Le consentement</b>, avec son interrupteur, et le nom du moteur retenu. Un joueur dont
 * l'écran reste noir doit trouver la cause ici et non dans un journal — et s'il a refusé les médias
 * distants, il doit pouvoir changer d'avis sans quitter la partie.
 *
 * <h2>L'ajusteur, et les trois pièges qu'il pose</h2>
 *
 * <p>Ce panneau réclame plus de haut qu'une fenêtre en échelle automatique n'en offre — voir
 * {@code client.Fit}, dont l'en-tête raconte le défaut en entier. Trois conséquences, et en oublier
 * une donne le symptôme le plus déroutant qui soit, « les boutons du haut répondent, ceux du bas ne
 * répondent plus » :
 *
 * <ol>
 *   <li>Toute coordonnée de souris passe par {@code fit.x} et {@code fit.y}.</li>
 *   <li>Tout évènement transmis à un composant de vanilla passe par {@code fit.event} — un bouton
 *       lit l'évènement entier, pas les coordonnées qu'on lui aurait calculées.</li>
 *   <li>Les infobulles sont vidées par {@code fit.tooltips} <b>avant</b> la fermeture de l'espace
 *       réduit : une infobulle est déposée puis peinte après coup, et atterrirait loin du curseur.</li>
 * </ol>
 */
public class Console extends Screen {
    private static final int SCRIM = 0xC8000000;
    private static final int PANEL = 0xE0141418;
    private static final int EDGE = 0xFF2A2A32;
    private static final int RAIL = 0xFF1C1C22;
    private static final int AMBER = 0xFFFFC857;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;
    private static final int FAINT = 0xFF5A5A62;
    private static final int BAD = 0xFFE06C5B;
    private static final int GOOD = 0xFF7FBF6A;

    private static final int WIDTH = 320;
    private static final int HEADER = 30;

    /** Hauteur d'une ligne de réglage. Assez pour une barre de huit et deux pixels d'air. */
    private static final int ROW = 16;

    private final BlockPos pos;
    private final Fit fit = new Fit();

    private EditBox sourceBox;
    private int panelX;
    private int panelY;

    /** La barre qu'on est en train de faire glisser, ou {@code -1}. Voir {@link #bars()}. */
    private int dragging = -1;

    private Feed feed = Feed.BLANK;
    private Clock beat = Clock.STOPPED;
    private boolean projector;
    private int reach = 8;
    private int span = 5;
    private String owner = "";

    public Console(BlockPos pos) {
        super(Component.literal("Projection"));
        this.pos = pos;
    }

    private Stage stage() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && client.level.getBlockEntity(this.pos) instanceof Stage found
                ? found : null;
    }

    /**
     * Relit le bloc.
     *
     * <p>Le serveur renvoie l'état entier après chaque geste — c'est le paquet de bloc-entité qui
     * s'en charge. Sans cette relecture, l'écran montrerait l'état d'avant le clic jusqu'à sa
     * fermeture, c'est-à-dire qu'il mentirait pendant exactement le temps où l'on a le plus besoin
     * qu'il dise vrai. La leçon vient de {@code client.waypoint.Board}.
     */
    private void reread() {
        Stage stage = stage();
        if (stage == null) {
            onClose();
            return;
        }
        this.feed = stage.feed();
        this.beat = stage.clock();
        this.owner = stage.ownerName();
        this.projector = stage.projector();
        if (stage instanceof BeamerEntity beamer) {
            this.reach = beamer.reach();
            this.span = beamer.span();
        }
    }

    @Override
    protected void init() {
        reread();
        this.fit.measure(this.width, this.height, WIDTH, panelHeight());
        this.panelX = (this.fit.viewWidth() - WIDTH) / 2;
        this.panelY = Math.max(8, (this.fit.viewHeight() - panelHeight()) / 2);

        int left = this.panelX;
        int top = this.panelY;

        String kept = this.sourceBox == null ? this.feed.source() : this.sourceBox.getValue();
        this.sourceBox = new EditBox(this.font, left + 10, top + HEADER, WIDTH - 84, 16,
                Component.literal("source"));
        this.sourceBox.setMaxLength(Feed.SOURCE_LIMIT);
        this.sourceBox.setHint(Component.literal("https://…").withStyle(ChatFormatting.DARK_GRAY));
        this.sourceBox.setValue(kept);
        addRenderableWidget(this.sourceBox);

        addRenderableWidget(Button.builder(Component.literal("Poser"), button -> push())
                .bounds(left + WIDTH - 70, top + HEADER - 1, 60, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Envoie cette source au serveur, qui la vérifiera.")))
                .build());

        int base = top + HEADER + 24;
        addRenderableWidget(Button.builder(
                        Component.literal(this.beat.paused() ? "▶ Lire" : "⏸ Pause"),
                        button -> command(this.beat.paused() ? Mail.Verb.LIRE : Mail.Verb.PAUSE))
                .bounds(left + 10, base, 64, 18).build());
        addRenderableWidget(Button.builder(Component.literal("⏹"), button ->
                        command(Mail.Verb.ARRET))
                .bounds(left + 78, base, 22, 18)
                .tooltip(Tooltip.create(Component.literal("Revenir au début et arrêter")))
                .build());

        addRenderableWidget(Button.builder(Component.literal(
                        this.feed.loop() ? "Boucle ✔" : "Boucle ✘"), button ->
                        send(new Feed(this.feed.source(), !this.feed.loop(), this.feed.autoplay(),
                                this.feed.volume(), this.feed.range(), this.feed.brightness(),
                                this.feed.shape(), this.feed.lock())))
                .bounds(left + 106, base, 64, 18).build());
        addRenderableWidget(Button.builder(Component.literal(
                        this.feed.autoplay() ? "Auto ✔" : "Auto ✘"), button ->
                        send(new Feed(this.feed.source(), this.feed.loop(), !this.feed.autoplay(),
                                this.feed.volume(), this.feed.range(), this.feed.brightness(),
                                this.feed.shape(), this.feed.lock())))
                .bounds(left + 174, base, 58, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Démarrer tout seul quand la source change")))
                .build());
        addRenderableWidget(Button.builder(Component.literal(this.feed.shape().label()), button ->
                        send(new Feed(this.feed.source(), this.feed.loop(), this.feed.autoplay(),
                                this.feed.volume(), this.feed.range(), this.feed.brightness(),
                                this.feed.shape().next(), this.feed.lock())))
                .bounds(left + 236, base, 74, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Étiré : remplit, déforme.\nEntier : tout, avec des bandes.\n"
                                + "Rempli : remplit, rogne.")))
                .build());

        int bottom = top + panelHeight();
        addRenderableWidget(Button.builder(Component.literal("Qui règle : " + this.feed.lock().label()),
                        button -> send(new Feed(this.feed.source(), this.feed.loop(),
                                this.feed.autoplay(), this.feed.volume(), this.feed.range(),
                                this.feed.brightness(), this.feed.shape(), this.feed.lock().next())))
                .bounds(left + 10, bottom - 46, 150, 18).build());

        addRenderableWidget(Button.builder(Component.literal(Consent.remoteAllowed()
                        ? "Médias distants : oui" : "Médias distants : NON"), button -> {
                            Consent.allowRemote(!Consent.remoteAllowed());
                            rebuildWidgets();
                        })
                .bounds(left + 166, bottom - 46, 144, 18)
                .tooltip(Tooltip.create(Component.literal(
                        "Quand c'est « oui », cette machine se connecte aux adresses posées sur les"
                                + " écrans, et l'hôte distant apprend ton adresse IP.")))
                .build());
    }

    @Override
    protected void setInitialFocus() {
        this.setInitialFocus(this.sourceBox);
    }

    private int panelHeight() {
        // Quatre barres pour un écran, six pour un projecteur, plus les bandes fixes.
        return HEADER + 24 + 22 + 14 + bars().length * ROW + 56;
    }

    @Override
    public void tick() {
        super.tick();
        boolean wasPaused = this.beat.paused();
        boolean wasProjector = this.projector;
        Feed before = this.feed;
        reread();
        // On ne reconstruit que si un libellé de bouton a changé : reconstruire, c'est perdre le
        // focus du champ de saisie au milieu d'une frappe.
        if (wasPaused != this.beat.paused() || wasProjector != this.projector
                || before.loop() != this.feed.loop() || before.autoplay() != this.feed.autoplay()
                || before.shape() != this.feed.shape() || before.lock() != this.feed.lock()) {
            rebuildWidgets();
        }
    }

    // --- Les barres ------------------------------------------------------------
    //
    // Dessinées et cliquées à la main, comme les actions de ligne du carnet, et pour la même raison :
    // un composant de vanilla par valeur aurait fallu être reconstruit à chaque cran, ce qui efface
    // la zone de saisie au milieu d'une frappe.

    /** Une barre : son libellé, sa valeur, ses bornes, et où l'envoyer. */
    private record Bar(String label, int value, int min, int max, String unit) {}

    private Bar[] bars() {
        if (this.projector) {
            return new Bar[] {
                    new Bar("Volume", this.feed.volume(), 0, 100, "%"),
                    new Bar("Portée sonore", this.feed.range(), 1, Gaze.rangeCap(), " blocs"),
                    new Bar("Luminosité", this.feed.brightness(), 0, 15, ""),
                    new Bar("Distance de projection", this.reach, 2, Gaze.throwCap(), " blocs"),
                    new Bar("Largeur de l'image", this.span, 1, 32, " blocs")};
        }
        return new Bar[] {
                new Bar("Volume", this.feed.volume(), 0, 100, "%"),
                new Bar("Portée sonore", this.feed.range(), 1, Gaze.rangeCap(), " blocs"),
                new Bar("Luminosité", this.feed.brightness(), 0, 15, "")};
    }

    private int barsTop() {
        return this.panelY + HEADER + 24 + 22 + 14;
    }

    private void setBar(int index, int value) {
        Bar[] all = bars();
        if (index < 0 || index >= all.length) {
            return;
        }
        int clamped = Math.clamp(value, all[index].min(), all[index].max());
        switch (index) {
            case 0 -> send(new Feed(this.feed.source(), this.feed.loop(), this.feed.autoplay(),
                    clamped, this.feed.range(), this.feed.brightness(), this.feed.shape(),
                    this.feed.lock()));
            case 1 -> send(new Feed(this.feed.source(), this.feed.loop(), this.feed.autoplay(),
                    this.feed.volume(), clamped, this.feed.brightness(), this.feed.shape(),
                    this.feed.lock()));
            case 2 -> send(new Feed(this.feed.source(), this.feed.loop(), this.feed.autoplay(),
                    this.feed.volume(), this.feed.range(), clamped, this.feed.shape(),
                    this.feed.lock()));
            case 3 -> {
                this.reach = clamped;
                ClientPacketDistributor.sendToServer(new Mail.Aim(this.pos, clamped, this.span));
            }
            case 4 -> {
                this.span = clamped;
                ClientPacketDistributor.sendToServer(new Mail.Aim(this.pos, this.reach, clamped));
            }
            default -> { }
        }
    }

    // --- Les envois ------------------------------------------------------------

    private void push() {
        String wanted = this.sourceBox.getValue().trim();
        send(this.feed.withSource(wanted));
    }

    private void send(Feed wanted) {
        this.feed = wanted.sane();
        ClientPacketDistributor.sendToServer(new Mail.Set(this.pos, this.feed));
    }

    private void command(Mail.Verb verb) {
        ClientPacketDistributor.sendToServer(new Mail.Command(this.pos, verb, 0L));
    }

    // --- Le dessin -------------------------------------------------------------

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

        graphics.text(this.font, this.projector ? "PROJECTEUR" : "ÉCRAN", left + 10, top + 9,
                AMBER, false);
        String by = this.owner.isBlank() ? "" : "posé par " + this.owner;
        graphics.text(this.font, by, left + WIDTH - 10 - this.font.width(by), top + 9, FAINT, false);

        drawVerdict(graphics, left, top + HEADER + 20);
        drawScrubber(graphics, left, top + HEADER + 24 + 22, mouseX, mouseY);
        drawBars(graphics, left, mouseX, mouseY);
        drawEngine(graphics, left, bottom);

        super.extractRenderState(graphics, mouseX, mouseY, partial);
        this.fit.tooltips(graphics, mouseX, mouseY, partial);
        this.fit.close(graphics);
    }

    /** Ce que le tamis dira de ce qui est tapé — avant l'envoi, pas après. */
    private void drawVerdict(GuiGraphicsExtractor graphics, int left, int y) {
        String typed = this.sourceBox == null ? "" : this.sourceBox.getValue().trim();
        if (typed.isEmpty()) {
            graphics.text(this.font, Gaze.filtering()
                            ? "Liste blanche active — " + Gaze.domains().size() + " domaine(s) autorisé(s)."
                            : "Liste blanche désactivée : tout lien public est accepté.",
                    left + 10, y, FAINT, false);
            return;
        }
        Sieve.Verdict verdict = Gaze.admits(typed);
        String note = verdict.ok()
                ? "✔ " + Sieve.domain(typed) + " — accepté"
                : "✘ " + verdict.label();
        graphics.text(this.font, this.font.plainSubstrByWidth(note, WIDTH - 20), left + 10, y,
                verdict.ok() ? GOOD : BAD, false);
    }

    /**
     * La barre de progression, et le curseur qu'on y déplace.
     *
     * <p>Elle n'existe que si la durée est connue. Un direct — ou un flux dont le moteur n'a pas
     * encore lu les entêtes — n'a pas de fin, et une barre sans fin ne dit rien ; on écrit alors le
     * temps écoulé, qui est la seule chose vraie qu'on ait.
     */
    private void drawScrubber(GuiGraphicsExtractor graphics, int left, int y, int mouseX,
            int mouseY) {
        long total = this.beat.durationMillis();
        long now = this.beat.position(clientTime(), 0f);
        int barLeft = left + 10;
        int barRight = left + WIDTH - 70;

        graphics.fill(barLeft, y + 3, barRight, y + 7, RAIL);
        if (total > 0L) {
            int filled = barLeft + (int) ((barRight - barLeft) * Math.min(1d,
                    (double) now / total));
            graphics.fill(barLeft, y + 3, filled, y + 7, AMBER);
            graphics.fill(filled - 1, y, filled + 1, y + 10, TEXT);
        }
        String clockface = total > 0L
                ? Clock.clockface(now) + " / " + Clock.clockface(total)
                : Clock.clockface(now);
        graphics.text(this.font, clockface, left + WIDTH - 64, y + 1,
                total > 0L ? DIM : FAINT, false);
    }

    private void drawBars(GuiGraphicsExtractor graphics, int left, int mouseX, int mouseY) {
        Bar[] all = bars();
        int y = barsTop();
        for (int index = 0; index < all.length; index++) {
            Bar bar = all[index];
            int rowY = y + index * ROW;
            graphics.text(this.font, bar.label(), left + 10, rowY + 2, DIM, false);

            int barLeft = left + 150;
            int barRight = left + WIDTH - 54;
            boolean over = mouseX >= barLeft - 2 && mouseX <= barRight + 2
                    && mouseY >= rowY - 2 && mouseY <= rowY + 12;
            graphics.fill(barLeft, rowY + 4, barRight, rowY + 8, RAIL);
            float share = (bar.value() - bar.min()) / (float) Math.max(1, bar.max() - bar.min());
            int knob = barLeft + Math.round((barRight - barLeft) * share);
            graphics.fill(barLeft, rowY + 4, knob, rowY + 8, AMBER);
            graphics.fill(knob - 2, rowY + 1, knob + 2, rowY + 11,
                    over || this.dragging == index ? TEXT : DIM);

            String value = bar.value() + bar.unit();
            graphics.text(this.font, value, left + WIDTH - 48, rowY + 2, TEXT, false);
        }
    }

    /** Le moteur retenu, et ce qui lui manque. Jamais « bientôt » — voir {@code Engine}. */
    private void drawEngine(GuiGraphicsExtractor graphics, int left, int bottom) {
        Engine best = Engine.CANDIDATES.get(0);
        String note = "Décodeur : " + best.label() + " — " + best.verdict().label();
        graphics.text(this.font, this.font.plainSubstrByWidth(note, WIDTH - 20),
                left + 10, bottom - 22, best.verdict() == Engine.Verdict.PRET ? GOOD : BAD, false);
    }

    private long clientTime() {
        Minecraft client = Minecraft.getInstance();
        return client.level == null ? 0L : client.level.getGameTime();
    }

    // --- Les entrées -----------------------------------------------------------

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

        int scrubY = this.panelY + HEADER + 24 + 22;
        int barLeft = this.panelX + 10;
        int barRight = this.panelX + WIDTH - 70;
        if (this.beat.durationMillis() > 0L && mouseY >= scrubY - 2 && mouseY <= scrubY + 12
                && mouseX >= barLeft && mouseX <= barRight) {
            double share = (mouseX - barLeft) / (double) (barRight - barLeft);
            ClientPacketDistributor.sendToServer(new Mail.Command(this.pos, Mail.Verb.CURSEUR,
                    Math.round(share * this.beat.durationMillis())));
            return true;
        }

        Bar[] all = bars();
        for (int index = 0; index < all.length; index++) {
            int rowY = barsTop() + index * ROW;
            if (mouseY >= rowY - 2 && mouseY <= rowY + 12) {
                this.dragging = index;
                slide(index, mouseX);
                return true;
            }
        }
        return false;
    }

    /** Une barre suit la souris tant qu'on ne relâche pas. Sans cela, il faudrait viser au pixel. */
    private void slide(int index, int mouseX) {
        Bar[] all = bars();
        if (index < 0 || index >= all.length) {
            return;
        }
        int barLeft = this.panelX + 150;
        int barRight = this.panelX + WIDTH - 54;
        double share = Math.clamp((mouseX - barLeft) / (double) (barRight - barLeft), 0d, 1d);
        Bar bar = all[index];
        setBar(index, bar.min() + (int) Math.round(share * (bar.max() - bar.min())));
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        MouseButtonEvent local = this.fit.event(event);
        if (this.dragging >= 0) {
            slide(this.dragging, (int) local.x());
            return true;
        }
        return super.mouseDragged(local, dragX / this.fit.factor(), dragY / this.fit.factor());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        this.dragging = -1;
        return super.mouseReleased(this.fit.event(event));
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        super.mouseMoved(this.fit.x(mouseX), this.fit.y(mouseY));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return super.mouseScrolled(this.fit.x(mouseX), this.fit.y(mouseY), scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
