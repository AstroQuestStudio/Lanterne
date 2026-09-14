package fr.clubcitrouille.lanterne.content.painting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Le cadre : l'écran où l'on remplit une toile.
 *
 * <h2>Le geste que cet écran existe pour rendre possible</h2>
 *
 * <p>La première version de ce paquet demandait au joueur de déposer ses fichiers dans un dossier
 * <em>avant</em> de lancer le jeu, puis de les retrouver dans un catalogue par une commande. Cela
 * fonctionnait et personne n'en voulait : c'est un geste d'administrateur, pas un geste de joueur.
 * Le commanditaire l'a dit sans détour, et il avait raison.
 *
 * <p>Le geste juste est celui d'un cadre vide qu'on accroche puis qu'on remplit : <b>on pose la
 * toile, on clique dessus, on choisit l'image, sans jamais quitter la partie</b>. Le dossier reste —
 * il est commode pour un serveur qui veut préparer une bibliothèque — mais il n'est plus le chemin
 * principal, il est le troisième.
 *
 * <h2>Trois voies vers la même image</h2>
 *
 * <ol>
 *   <li><b>Un lien.</b> On colle une adresse. Le <em>client</em> télécharge — jamais le serveur, et
 *       la raison tient en un mot : SSRF, expliqué dans {@link Reach}.</li>
 *   <li><b>Le disque du joueur.</b> Le sélecteur de fichiers du système s'ouvre, celui qu'il connaît
 *       déjà, avec ses favoris et son aperçu.</li>
 *   <li><b>Le dossier de la bibliothèque</b>, qui alimente le catalogue au démarrage.</li>
 * </ol>
 *
 * <p>Les trois convergent au même endroit dès la deuxième ligne : {@link Mill}. Même
 * ré-échantillonnage, même encodage, même empreinte. C'est ce qui fait que la même image obtenue par
 * deux voies différentes n'est transmise qu'une seule fois.
 *
 * <h2>L'aperçu ne coûte rien</h2>
 *
 * <p>L'image choisie est posée dans la {@link Mosaic} <b>avant même</b> d'être envoyée au serveur.
 * L'aperçu n'est donc pas une texture de plus : c'est un rectangle de la mosaïque, dessiné avec les
 * mêmes coordonnées que le tableau une fois accroché. Ce qu'on voit ici est exactement ce qu'on
 * verra sur le mur — et si le serveur refuse, la case sera simplement réutilisée par la suivante.
 *
 * <h2>Rien de long ne se fait sur le fil de rendu</h2>
 *
 * <p>Télécharger dure des secondes ; le sélecteur du système bloque jusqu'au clic de
 * l'utilisateur ; réduire une image de quatre mille pixels prend des dizaines de millisecondes. Ces
 * trois-là tournent ailleurs, et ne reviennent ici que par {@code Minecraft.execute}. L'écran reste
 * peint et la touche d'échappement répond pendant tout ce temps.
 *
 * <p>Chaque retour vérifie que <b>cet écran est encore celui qui est ouvert</b>. Sans ce test, un
 * téléchargement lancé puis abandonné écrirait son résultat dans un écran fermé — au mieux sans
 * effet, au pire en posant une image que le joueur ne voulait plus.
 */
@OnlyIn(Dist.CLIENT)
public final class Frame extends Screen {
    private static final int SCRIM = 0xC8000000;
    private static final int PANEL = 0xF2141418;
    private static final int EDGE = 0xFF2A2A32;
    private static final int WELL = 0xFF0C0C10;
    private static final int AMBER = 0xFFFFC857;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;
    private static final int FAINT = 0xFF5A5A62;
    private static final int BAD = 0xFFE06C5B;
    private static final int GOOD = 0xFF6BCB77;

    private static final int WIDTH = 620;
    private static final int HEIGHT = 312;
    private static final int HEADER = 32;

    /** Largeur de la zone d'aperçu. */
    private static final int WELL_W = 250;

    /** Hauteur de la zone d'aperçu. */
    private static final int WELL_H = 206;

    private final int entityId;
    private final PaintingRoll.Rules rules;

    private int panelX;
    private int panelY;

    private EditBox link;
    private Button apply;
    private Button pull;
    private Button browse;
    private Button frame;

    /** L'empreinte qui sera posée. Vide : la toile redevient vierge. */
    private String hash;

    /** Le nom de l'image choisie, pour l'afficher. */
    private String title = "";

    private int blocksWide;
    private int blocksHigh;

    /** L'encadrement choisi. Se change seul, sans toucher a l'image ni la retransmettre. */
    private Trim trim;

    /** Le dernier message affiché sous les boutons. */
    private String note = "";

    /** Vrai si {@link #note} doit s'afficher en rouge. */
    private boolean noteIsBad;

    /** Vrai pendant qu'une course est en cours : on n'en lance pas deux. */
    private boolean busy;

    public Frame(int entityId, String hash, int blocksWide, int blocksHigh, int trim,
            PaintingRoll.Rules rules) {
        super(Component.literal("Atelier"));
        this.entityId = entityId;
        this.hash = hash == null ? "" : hash;
        this.rules = rules;
        this.blocksWide = Math.clamp(blocksWide, 1, rules.maxBlocks());
        this.blocksHigh = Math.clamp(blocksHigh, 1, rules.maxBlocks());
        this.trim = Trim.byId(trim);
        Plate known = Easel.known(this.hash);
        this.title = known != null ? known.name() : "";
    }

    @Override
    protected void init() {
        this.panelX = (this.width - WIDTH) / 2;
        this.panelY = (this.height - HEIGHT) / 2;
        int right = this.panelX + 14 + WELL_W + 14;
        int columnWidth = WIDTH - (right - this.panelX) - 14;
        int y = this.panelY + HEADER + 22;

        this.link = new EditBox(this.font, right, y, columnWidth, 18,
                Component.literal("Adresse de l'image"));
        this.link.setMaxLength(512);
        this.link.setHint(Component.literal("https://…/mon-image.png"));
        this.addRenderableWidget(this.link);

        int half = (columnWidth - 6) / 2;
        this.pull = this.addRenderableWidget(Button
                .builder(Component.literal("Charger le lien"), button -> this.onPull())
                .bounds(right, y + 24, half, 18).build());
        this.browse = this.addRenderableWidget(Button
                .builder(Component.literal("Depuis mon PC"), button -> this.onBrowse())
                .bounds(right + half + 6, y + 24, columnWidth - half - 6, 18).build());

        int sizeY = y + 70;
        this.addRenderableWidget(Button.builder(Component.literal("-"),
                        button -> this.stretch(-1, 0))
                .bounds(right, sizeY, 20, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("+"),
                        button -> this.stretch(1, 0))
                .bounds(right + 84, sizeY, 20, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("-"),
                        button -> this.stretch(0, -1))
                .bounds(right + 130, sizeY, 20, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("+"),
                        button -> this.stretch(0, 1))
                .bounds(right + 214, sizeY, 20, 18).build());

        int trimY = sizeY + 26;
        this.frame = this.addRenderableWidget(Button
                .builder(Component.literal("Cadre : " + this.trim.label()), button -> this.cycle())
                .bounds(right, trimY, columnWidth, 18).build());

        int footY = this.panelY + HEIGHT - 26;
        this.apply = this.addRenderableWidget(Button
                .builder(Component.literal("Accrocher"), button -> this.onApply())
                .bounds(right, footY, 110, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Vider"), button -> this.onClear())
                .bounds(right + 116, footY, 70, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Fermer"), button -> this.onClose())
                .bounds(right + 192, footY, 70, 18).build());

        this.refresh();
    }

    @Override
    protected void setInitialFocus() {
        this.setInitialFocus(this.link);
    }

    /**
     * Remet les boutons dans l'état que l'état interne commande.
     *
     * <p>Un seul endroit décide de ce qui est cliquable, et il est rappelé après chaque changement.
     * La version qui activait et désactivait les boutons au fil des évènements avait déjà laissé
     * « Charger le lien » cliquable pendant un téléchargement — le genre de défaut qui ne se voit
     * qu'en cliquant deux fois vite.
     */
    private void refresh() {
        boolean linksOk = this.rules.links() && !this.busy;
        this.pull.active = linksOk;
        this.browse.active = !this.busy;
        this.apply.active = !this.busy;
        if (!this.rules.links()) {
            this.link.setEditable(false);
        }
    }

    /**
     * Passe a l'encadrement suivant.
     *
     * <p>Le changement est purement local jusqu'a « Accrocher » : aucun paquet ne part, aucune image
     * n'est retransmise. Changer de cadre sur une toile deja posee ne coute donc rien de plus qu'un
     * entier dans le paquet de pose.
     */
    private void cycle() {
        this.trim = this.trim.next();
        this.frame.setMessage(Component.literal("Cadre : " + this.trim.label()));
    }

    private void stretch(int dw, int dh) {
        this.blocksWide = Math.clamp(this.blocksWide + dw, 1, this.rules.maxBlocks());
        this.blocksHigh = Math.clamp(this.blocksHigh + dh, 1, this.rules.maxBlocks());
    }

    // --- Les trois actions -------------------------------------------------

    private void onPull() {
        if (this.busy) {
            return;
        }
        this.busy = true;
        this.say("Préparation…", false);
        this.refresh();
        Reach.pull(this.link.getValue(), this.rules, Reach.IMAGES, this.ear());
    }

    private void onBrowse() {
        if (this.busy) {
            return;
        }
        this.busy = true;
        this.say("Fenêtre du système ouverte…", false);
        this.refresh();
        Reach.browse(this.rules, Reach.IMAGES, this.ear());
    }

    private void onClear() {
        this.hash = "";
        this.title = "";
        this.say("Toile vidée — accroche pour valider.", false);
    }

    private void onApply() {
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new PaintingSet(this.entityId, this.hash, this.blocksWide, this.blocksHigh,
                        this.trim.id()));
        this.onClose();
    }

    /**
     * Le retour des courses, ramené sur le fil du client.
     *
     * <p>Les trois méthodes sont appelées depuis un autre fil. Toutes trois passent donc par
     * {@code execute}, et toutes trois vérifient que cet écran est encore à l'affiche : un
     * téléchargement dure plus longtemps qu'il n'en faut au joueur pour appuyer sur échappement.
     */
    private Reach.Ear ear() {
        return new Reach.Ear() {
            @Override
            public void progress(String step) {
                Frame.this.later(() -> {
                    if (step.isEmpty()) {
                        Frame.this.busy = false;
                        Frame.this.say("", false);
                        Frame.this.refresh();
                    } else {
                        Frame.this.say(step, false);
                    }
                });
            }

            @Override
            public void failed(String reason) {
                Frame.this.later(() -> {
                    Frame.this.busy = false;
                    Frame.this.say(reason, true);
                    Frame.this.refresh();
                });
            }

            @Override
            public void done(Reach.Haul haul) {
                // On ne revient pas encore au fil du client : réduire l'image est le morceau le plus
                // coûteux de toute la chaîne, et le faire ici ferait sauter des images alors que le
                // joueur regarde justement l'écran.
                Frame.this.later(() -> Frame.this.say("Préparation de l'image…", false));
                Hoard.prepare(haul.raw(), haul.name(), Frame.this.rules.maxSide(),
                        plate -> Frame.this.later(() -> Frame.this.accept(plate)),
                        reason -> Frame.this.later(() -> {
                            Frame.this.busy = false;
                            Frame.this.say(reason, true);
                            Frame.this.refresh();
                        }));
            }
        };
    }

    /** L'image est prête et posée dans la mosaïque : l'aperçu peut l'afficher. */
    private void accept(Plate plate) {
        this.busy = false;
        this.hash = plate.hash();
        this.title = plate.name();
        // La taille proposée suit les proportions de l'image. Le joueur peut la changer ensuite ;
        // ce qu'on veut éviter, c'est qu'il doive le faire pour que ce ne soit pas étiré.
        int[] fit = plate.fittingBlocks(this.rules.maxBlocks());
        this.blocksWide = fit[0];
        this.blocksHigh = fit[1];
        this.say("Prête — " + plate.pixelWidth() + "×" + plate.pixelHeight() + " px.", false);
        this.refresh();
    }

    private void later(Runnable work) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.screen == this) {
                work.run();
            }
        });
    }

    private void say(String text, boolean bad) {
        this.note = text;
        this.noteIsBad = bad;
    }

    // --- Le dessin ---------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partial) {
        final int x = this.panelX;
        final int y = this.panelY;
        final int right = x + WIDTH;
        final int bottom = y + HEIGHT;

        graphics.fill(0, 0, this.width, this.height, SCRIM);
        graphics.fill(x - 1, y - 1, right + 1, bottom + 1, EDGE);
        graphics.fill(x, y, right, bottom, PANEL);
        graphics.fill(x, y, right, y + 2, AMBER);
        graphics.text(this.font, "LANTERNE", x + 12, y + 12, AMBER, false);
        graphics.text(this.font, "ATELIER — accroche une image", x + 78, y + 12, DIM, false);
        graphics.fill(x, y + HEADER - 1, right, y + HEADER, EDGE);

        this.drawWell(graphics, x + 14, y + HEADER + 10);

        int column = x + 14 + WELL_W + 14;
        graphics.text(this.font, this.rules.links() ? "DEPUIS UN LIEN"
                : "LIENS REFUSÉS PAR CE SERVEUR", column, y + HEADER + 10,
                this.rules.links() ? FAINT : BAD, false);

        int sizeY = y + HEADER + 22 + 70;
        graphics.text(this.font, "TAILLE SUR LE MUR", column, sizeY - 14, FAINT, false);
        this.drawCount(graphics, column + 24, sizeY + 5, this.blocksWide + " large");
        this.drawCount(graphics, column + 154, sizeY + 5, this.blocksHigh + " haut");

        int noteY = this.panelY + HEIGHT - 46;
        if (!this.note.isEmpty()) {
            graphics.text(this.font, this.font.plainSubstrByWidth(this.note, WIDTH - (column - x) - 14),
                    column, noteY, this.noteIsBad ? BAD : GOOD, false);
        } else {
            graphics.text(this.font, "Limite : " + this.rules.maxSide() + " px, "
                    + this.rules.maxBlocks() + " blocs, "
                    + this.rules.maxSourceKib() + " Kio", column, noteY, FAINT, false);
        }

        // Les widgets en dernier : ils doivent se poser SUR le panneau, pas dessous.
        super.extractRenderState(graphics, mouseX, mouseY, partial);
    }

    /**
     * L'aperçu, dans les proportions qu'aura le tableau.
     *
     * <h2>Le cadre montre la taille en blocs, pas la forme de l'image</h2>
     *
     * <p>C'est délibéré, et c'est ce qui rend les boutons de taille utiles : si l'aperçu suivait les
     * proportions de l'image, augmenter la largeur ne changerait rien à l'écran et le joueur
     * découvrirait l'étirement une fois le tableau accroché. En montrant le rectangle du mur avec
     * l'image dedans, la déformation se voit <b>avant</b> de valider.
     */
    private void drawWell(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + WELL_W + 1, y + WELL_H + 1, EDGE);
        graphics.fill(x, y, x + WELL_W, y + WELL_H, WELL);

        // Le rectangle que le tableau occupera, mis à l'échelle pour tenir dans la zone.
        double scale = Math.min((WELL_W - 24.0d) / this.blocksWide, (WELL_H - 24.0d) / this.blocksHigh);
        int w = (int) Math.round(this.blocksWide * scale);
        int h = (int) Math.round(this.blocksHigh * scale);
        int left = x + (WELL_W - w) / 2;
        int top = y + (WELL_H - h) / 2;

        Mosaic.Cell cell = this.hash.isEmpty() ? null : Mosaic.cell(this.hash);
        if (cell != null) {
            graphics.blit(Mosaic.ID, left, top, left + w, top + h,
                    cell.u0(), cell.u1(), cell.v0(), cell.v1());
        } else {
            graphics.fill(left, top, left + w, top + h, 0xFF23232B);
            String empty = this.hash.isEmpty() ? "toile vierge" : "image en route…";
            graphics.text(this.font, empty,
                    left + (w - this.font.width(empty)) / 2, top + h / 2 - 4, FAINT, false);
        }
        // Le cadre, dessiné comme il le sera sur le mur : par-dessus le bord de l'image, à la
        // largeur qu'il aura une fois mis à l'échelle. C'est ce qui permet de juger un cadre épais
        // sur un petit tableau sans avoir à l'accrocher pour voir.
        if (this.trim.framed()) {
            int band = Math.max(2, (int) Math.round(this.trim.width() * scale));
            int c = 0xFF000000 | (this.trim.colour() & 0xFFFFFF);
            graphics.fill(left, top, left + w, top + band, c);
            graphics.fill(left, top + h - band, left + w, top + h, c);
            graphics.fill(left, top + band, left + band, top + h - band, c);
            graphics.fill(left + w - band, top + band, left + w, top + h - band, c);
        }
        // Le liseré, pour que l'aperçu se lise comme un objet posé et non comme un trou.
        graphics.fill(left - 1, top - 1, left + w + 1, top, EDGE);
        graphics.fill(left - 1, top + h, left + w + 1, top + h + 1, EDGE);
        graphics.fill(left - 1, top, left, top + h, EDGE);
        graphics.fill(left + w, top, left + w + 1, top + h, EDGE);

        String label = this.title.isEmpty() ? "aucune image" : this.title;
        graphics.text(this.font, this.font.plainSubstrByWidth(label, WELL_W),
                x, y + WELL_H + 6, this.title.isEmpty() ? FAINT : TEXT, false);
    }

    private void drawCount(GuiGraphicsExtractor graphics, int x, int y, String text) {
        graphics.text(this.font, text, x + (60 - this.font.width(text)) / 2, y, TEXT, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
