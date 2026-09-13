package fr.clubcitrouille.lanterne.client.waypoint;

import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.waypoint.Waypoint;

/**
 * La boussole de l'affichage tête haute, et la touche qui ouvre le carnet.
 *
 * <h2>Trois repères, et pas plus</h2>
 *
 * <p>Un affichage permanent doit se lire <b>sans s'y arrêter</b>. Au-delà de trois lignes, l'œil doit
 * chercher, et une information qu'on doit chercher vaut moins qu'une information qu'on va consulter
 * exprès — ce à quoi sert le carnet complet.
 *
 * <p>Les trois plus proches du monde courant, donc : c'est ce qui change quand on marche, et c'est
 * ce qu'on veut savoir sans y penser.
 *
 * <h2>Pourquoi une flèche plutôt qu'un azimut</h2>
 *
 * <p>Un cap absolu — « 247° » — demande de savoir où l'on regarde pour servir à quelque chose. La
 * flèche est calculée <b>relativement au regard</b> : elle pointe vers le repère tel qu'on est
 * tourné, et se lit sans conversion.
 */
public final class Compass {
    /** Repères affichés en permanence. Voir l'en-tête : trois est une limite de lecture, pas de place. */
    private static final int SHOWN = 3;

    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF9A9AA2;

    public static final KeyMapping OPEN = new KeyMapping(
            "key.lanterne.waypoints",
            InputConstants.Type.KEYSYM,
            org.lwjgl.glfw.GLFW.GLFW_KEY_B,
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(Lanterne.ID, "lanterne")));

    private Compass() {}

    /**
     * Branche les deux évènements de bus de mod.
     *
     * <p>{@code @EventBusSubscriber} n'a plus d'attribut {@code bus} en 26.1 : il ne couvre que le bus
     * du jeu. Les enregistrements de touches et de couches d'interface passent par le bus du MOD, et
     * doivent donc être posés à la main — depuis un appel que seul un client exécute, faute de quoi un
     * serveur dédié chargerait des classes clientes.
     */
    public static void register(IEventBus modBus) {
        modBus.addListener(Compass::onRegisterKeys);
        modBus.addListener(Compass::onRegisterLayers);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(OPEN.getCategory());
        event.register(OPEN);
    }

    private static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR,
                Identifier.fromNamespaceAndPath(Lanterne.ID, "waypoints"), Compass::draw);
    }

    private static void draw(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                             net.minecraft.client.DeltaTracker delta) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui || client.screen != null) {
            return;
        }
        List<Waypoint> near = Marks.byDistance();
        if (near.isEmpty()) {
            return;
        }

        int x = 6;
        int y = 6;
        int shown = Math.min(SHOWN, near.size());
        for (int i = 0; i < shown; i++) {
            Waypoint mark = near.get(i);
            int line = y + i * 11;
            graphics.text(client.font, arrow(Marks.bearing(mark)), x, line,
                    0xFF000000 | mark.colour(), true);
            graphics.text(client.font, mark.name(), x + 10, line, TEXT, true);
            String far = String.format(Locale.ROOT, "%.0f", Marks.flatDistance(mark));
            graphics.text(client.font, far,
                    x + 12 + client.font.width(mark.name()), line, DIM, true);
        }
    }

    /** Huit directions relatives au regard. Voir {@code Board.arrow} pour le choix de huit. */
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
}
