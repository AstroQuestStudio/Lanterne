package fr.clubcitrouille.lanterne.content.disc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import fr.clubcitrouille.lanterne.content.painting.Hoard;
import fr.clubcitrouille.lanterne.content.painting.Reach;

/**
 * Le tour : l'écran du graveur de vinyle.
 *
 * <h2>Le même geste que pour les tableaux, et volontairement</h2>
 *
 * <p>Un champ d'adresse, un bouton vers le sélecteur de fichiers du système, un titre, et on grave.
 * C'est exactement la disposition de l'atelier des tableaux, aux mêmes endroits, avec les mêmes
 * couleurs. Un joueur qui a accroché un tableau sait déjà se servir de celui-ci sans rien relire —
 * et c'est le seul argument qui compte pour justifier deux écrans qui se ressemblent.
 *
 * <p>Le téléchargement et le sélecteur sont littéralement les mêmes : {@link Reach} ne sait pas ce
 * qu'il rapporte, il rapporte des octets bornés. Seuls les filtres d'extension changent, et ils sont
 * passés en paramètre.
 *
 * <h2>Ce que l'écran ne fait pas</h2>
 *
 * <p>Il ne convertit rien et ne mesure rien : {@link Wheel#prepare} s'en charge sur un fil de fond,
 * parce qu'appeler {@code ffmpeg} depuis le fil de rendu figerait le jeu pendant plusieurs secondes.
 * L'écran ne fait qu'afficher où en est le travail et refuser qu'on en lance deux.
 */
public final class Lathe extends Screen {
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

    private static final int WIDTH = 420;
    private static final int HEIGHT = 216;
    private static final int HEADER = 32;

    private final BlockPos pos;

    private int panelX;
    private int panelY;

    private EditBox link;
    private EditBox title;
    private Button pull;
    private Button browse;
    private Button burn;

    /** L'empreinte du morceau préparé, vide tant qu'aucun ne l'est. */
    private String hash = "";

    /** La taille du morceau préparé, en octets. */
    private int size;

    /** Sa durée, en secondes. */
    private float seconds;

    private String note = "";
    private boolean noteIsBad;
    private boolean busy;

    public Lathe(BlockPos pos) {
        super(Component.literal("Graveur"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        this.panelX = (this.width - WIDTH) / 2;
        this.panelY = (this.height - HEIGHT) / 2;
        int left = this.panelX + 14;
        int inner = WIDTH - 28;
        int y = this.panelY + HEADER + 20;

        this.link = new EditBox(this.font, left, y, inner, 18,
                Component.literal("Adresse du morceau"));
        this.link.setMaxLength(512);
        this.link.setHint(Component.literal("https://…/ma-musique.ogg"));
        this.addRenderableWidget(this.link);

        int half = (inner - 6) / 2;
        this.pull = this.addRenderableWidget(Button
                .builder(Component.literal("Charger le lien"), button -> this.onPull())
                .bounds(left, y + 24, half, 18).build());
        this.browse = this.addRenderableWidget(Button
                .builder(Component.literal("Depuis mon PC"), button -> this.onBrowse())
                .bounds(left + half + 6, y + 24, inner - half - 6, 18).build());

        this.title = new EditBox(this.font, left, y + 68, inner, 18,
                Component.literal("Titre du disque"));
        this.title.setMaxLength(Wax.NAME_LIMIT);
        this.title.setHint(Component.literal("Le nom qui s'affichera sur l'objet"));
        this.addRenderableWidget(this.title);

        int footY = this.panelY + HEIGHT - 26;
        this.burn = this.addRenderableWidget(Button
                .builder(Component.literal("Graver"), button -> this.onBurn())
                .bounds(left, footY, 120, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Fermer"), button -> this.onClose())
                .bounds(left + inner - 70, footY, 70, 18).build());

        this.refresh();
    }

    @Override
    protected void setInitialFocus() {
        this.setInitialFocus(this.link);
    }

    private void refresh() {
        boolean linksOk = Hoard.rules().links() && !this.busy;
        this.pull.active = linksOk;
        this.browse.active = !this.busy;
        // On ne peut graver qu'un morceau préparé : le bouton reste éteint tant qu'il n'y en a pas,
        // ce qui évite d'avoir à expliquer un refus après coup.
        this.burn.active = !this.busy && !this.hash.isEmpty();
        if (!Hoard.rules().links()) {
            this.link.setEditable(false);
        }
    }

    private void onPull() {
        if (this.busy) {
            return;
        }
        this.busy = true;
        this.say("Connexion…", false);
        this.refresh();
        Reach.pull(this.link.getValue(), Hoard.rules(), Reach.AUDIO, this.ear());
    }

    private void onBrowse() {
        if (this.busy) {
            return;
        }
        this.busy = true;
        this.say("Fenêtre du système ouverte…", false);
        this.refresh();
        Reach.browse(Hoard.rules(), Reach.AUDIO, this.ear());
    }

    /**
     * Lance la gravure.
     *
     * <p>Le titre par défaut est le nom du fichier, déjà posé dans le champ par le retour de
     * {@link Reach}. Un joueur qui ne veut pas le changer n'a rien à taper — c'est le cas le plus
     * fréquent, et un champ obligatoire l'aurait ralenti pour rien.
     */
    private void onBurn() {
        String name = this.title.getValue().trim();
        if (name.isEmpty()) {
            this.say("Donne un titre à ce disque.", true);
            return;
        }
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new Post.Burn(this.pos, name, this.hash, this.size, this.seconds));
        this.onClose();
    }

    private Reach.Ear ear() {
        return new Reach.Ear() {
            @Override
            public void progress(String step) {
                Lathe.this.later(() -> {
                    if (step.isEmpty()) {
                        Lathe.this.busy = false;
                        Lathe.this.say("", false);
                        Lathe.this.refresh();
                    } else {
                        Lathe.this.say(step, false);
                    }
                });
            }

            @Override
            public void failed(String reason) {
                Lathe.this.later(() -> {
                    Lathe.this.busy = false;
                    Lathe.this.say(reason, true);
                    Lathe.this.refresh();
                });
            }

            @Override
            public void done(Reach.Haul haul) {
                Lathe.this.later(() -> Lathe.this.say("Conversion en Vorbis…", false));
                Wheel.prepare(haul.raw(),
                        vinyl -> Lathe.this.later(() -> Lathe.this.accept(vinyl, haul.name())),
                        reason -> Lathe.this.later(() -> {
                            Lathe.this.busy = false;
                            Lathe.this.say(reason, true);
                            Lathe.this.refresh();
                        }));
            }
        };
    }

    private void accept(Post.Vinyl vinyl, String name) {
        this.busy = false;
        this.hash = vinyl.hash();
        this.seconds = vinyl.seconds();
        this.size = (int) Math.max(1L, sizeOnDisk());
        if (this.title.getValue().isBlank()) {
            this.title.setValue(Wax.prettify(name));
        }
        this.say("Prêt — " + format(this.seconds) + ".", false);
        this.refresh();
    }

    /** Le poids du fichier préparé, relu dans le cache : c'est lui qui sera transmis. */
    private long sizeOnDisk() {
        try {
            return java.nio.file.Files.size(Groove.cachedFile(this.hash));
        } catch (java.io.IOException unreadable) {
            return 1L;
        }
    }

    private static String format(float seconds) {
        int total = Math.round(seconds);
        return (total / 60) + " min " + String.format(java.util.Locale.ROOT, "%02d", total % 60) + " s";
    }

    private void later(Runnable work) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.gui.screen() == this) {
                work.run();
            }
        });
    }

    private void say(String text, boolean bad) {
        this.note = text;
        this.noteIsBad = bad;
    }

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
        graphics.text(this.font, "GRAVEUR DE VINYLE", x + 78, y + 12, DIM, false);
        graphics.fill(x, y + HEADER - 1, right, y + HEADER, EDGE);

        int left = x + 14;
        graphics.text(this.font, Hoard.rules().links() ? "DEPUIS UN LIEN"
                : "LIENS REFUSÉS PAR CE SERVEUR", left, y + HEADER + 8,
                Hoard.rules().links() ? FAINT : BAD, false);
        graphics.text(this.font, "TITRE", left, y + HEADER + 76, FAINT, false);

        // Le bandeau d'état : la seule chose qui bouge pendant qu'on attend.
        int noteY = this.panelY + HEIGHT - 48;
        graphics.fill(left, noteY - 4, right - 14, noteY + 12, WELL);
        String text = this.note.isEmpty()
                ? "OGG, ou tout format si ffmpeg est installé · max "
                        + (fr.clubcitrouille.lanterne.content.painting.Studio.discMaxBytes() / 1048576L)
                        + " Mio"
                : this.note;
        graphics.text(this.font, this.font.plainSubstrByWidth(text, WIDTH - 32), left + 2, noteY,
                this.note.isEmpty() ? FAINT : (this.noteIsBad ? BAD : GOOD), false);

        super.extractRenderState(graphics, mouseX, mouseY, partial);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
