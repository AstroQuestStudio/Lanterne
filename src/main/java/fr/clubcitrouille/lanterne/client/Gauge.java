package fr.clubcitrouille.lanterne.client;

import java.util.Arrays;
import java.util.Locale;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Lens;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Shroud;

/**
 * La jauge : ce que le mod fait, lisible pendant qu'on joue.
 *
 * <h2>Pourquoi F3 ne suffisait pas</h2>
 *
 * <p>L'écran de débogage donne une moyenne d'images par seconde recalculée une fois par seconde,
 * arrondie à l'entier. C'est assez pour savoir si le jeu tourne bien ; ce n'est pas assez pour
 * <b>comparer deux préréglages</b>, parce que la différence entre « Qualité » et « Équilibré » se
 * lit dans les à-coups autant que dans la moyenne.
 *
 * <p>Cette jauge affiche donc trois choses que F3 ne donne pas : le <b>centile le plus lent</b>, qui
 * est ce que le joueur ressent comme une saccade ; le nombre de créatures que le Voile a écartées à
 * l'image précédente, qui dit si le module sert dans <em>cette</em> situation ; et le préréglage de
 * mise à l'échelle en cours, pour savoir ce qu'on est en train de juger.
 *
 * <h2>Une fenêtre glissante, et non un compteur depuis le démarrage</h2>
 *
 * <p>Les relevés portent sur les {@link #WINDOW} dernières images. Une moyenne depuis le lancement
 * mettrait une minute à refléter un changement de réglage — autrement dit, elle serait inutilisable
 * pour la seule chose qu'on lui demande : comparer deux réglages en quelques secondes.
 */
public final class Gauge {
    /** Images retenues pour les statistiques. Deux secondes à 120 images par seconde. */
    private static final int WINDOW = 240;

    private static final int PANEL = 0xC0141418;
    private static final int AMBER = 0xFFFFC857;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;
    private static final int GOOD = 0xFF6BCB77;
    private static final int WARN = 0xFFE8A33D;
    private static final int BAD = 0xFFE05B5B;

    private static final long[] GAPS = new long[WINDOW];
    private static int filled;
    private static int cursor;
    private static long lastFrame;

    private Gauge() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(Gauge::onRegisterLayers);
    }

    private static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR,
                Identifier.fromNamespaceAndPath(Lanterne.ID, "gauge"), Gauge::draw);
    }

    /**
     * Relève l'intervalle de cette image.
     *
     * <p>Appelée depuis le point d'accroche par image, donc même quand la jauge est masquée : le
     * relevé doit être prêt <em>avant</em> qu'on l'affiche, sans quoi les deux premières secondes
     * après l'ouverture ne montreraient rien.
     */
    public static void note() {
        long now = System.nanoTime();
        if (lastFrame != 0L) {
            GAPS[cursor] = now - lastFrame;
            cursor = (cursor + 1) % WINDOW;
            if (filled < WINDOW) {
                filled++;
            }
        }
        lastFrame = now;
    }

    private static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        Minecraft client = Minecraft.getInstance();
        if (!Settings.gauge() || client.player == null || client.options.hideGui
                || client.screen != null || filled < 8) {
            return;
        }

        long[] sorted = Arrays.copyOf(GAPS, filled);
        Arrays.sort(sorted);
        double median = sorted[filled / 2] / 1e6d;
        double slowest = sorted[(int) (filled * 0.99d)] / 1e6d;
        double rate = median > 0d ? 1000d / median : 0d;

        String line1 = String.format(Locale.ROOT, "%.0f im/s", rate);
        String line2 = String.format(Locale.ROOT, "1 %% lent  %.1f ms", slowest);
        String line3 = Lens.active()
                ? "échelle  " + Lens.preset().pixelPercent() + " %"
                : "échelle  native";
        long veiled = Shroud.veiled();
        String line4 = veiled > 0 ? "voilé  " + veiled : null;

        int width = 4 + Math.max(client.font.width(line1),
                Math.max(client.font.width(line2), client.font.width(line3))) + 8;
        int height = 8 + (line4 == null ? 3 : 4) * 10;
        int x = 4;
        int y = 4;

        graphics.fill(x, y, x + width, y + height, PANEL);
        graphics.fill(x, y, x + 2, y + height, AMBER);

        // Le débit se colore, parce qu'un chiffre nu n'apprend rien à qui ne connaît pas les seuils.
        int rateColour = rate >= 100d ? GOOD : rate >= 55d ? WARN : BAD;
        graphics.text(client.font, line1, x + 6, y + 5, rateColour, false);
        graphics.text(client.font, line2, x + 6, y + 15, slowest > 50d ? BAD : DIM, false);
        graphics.text(client.font, line3, x + 6, y + 25, Lens.active() ? AMBER : DIM, false);
        if (line4 != null) {
            graphics.text(client.font, line4, x + 6, y + 35, TEXT, false);
        }
    }
}
