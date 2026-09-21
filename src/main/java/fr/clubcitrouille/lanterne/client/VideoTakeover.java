package fr.clubcitrouille.lanterne.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.ClientConfig;

/**
 * Options > Vidéo ouvre l'écran de Lanterne ({@link Dials}) au lieu de celui de vanilla ou de
 * celui de Sodium.
 *
 * <h2>Pourquoi un événement plutôt qu'un mixin</h2>
 *
 * <p>Deux écrans peuvent s'ouvrir à cet endroit : celui de vanilla, et celui de Sodium quand il est
 * installé (il remplace le premier par un mixin à lui). Un mixin de Lanterne devrait viser l'un, puis
 * l'autre, et dépendrait de l'ordre d'application. {@link ScreenEvent.Opening} passe au contraire
 * par le même point pour les deux : on regarde <em>quel écran arrive</em> et on le remplace, sans
 * rien savoir de la façon dont il a été demandé. Sodium n'est pas nommé par un type (il peut être
 * absent) : seulement par le nom de sa classe, comparé comme texte.
 *
 * <h2>La sortie de secours</h2>
 *
 * <p>Maj maintenue au clic sur « Vidéo » laisse passer l'écran d'origine, et le réglage
 * {@code ecran_video_lanterne} à faux le fait toujours. Un écran qui en remplace un autre doit
 * toujours laisser un chemin vers l'ancien : si celui-ci a un défaut, le joueur garde la main.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class VideoTakeover {
    /** Le nom de la classe de l'écran vidéo de Sodium, lu dans son jar (voir la Javadoc de classe). */
    private static final String SODIUM_VIDEO = "net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen";

    private VideoTakeover() {}

    @SubscribeEvent
    public static void onOpening(ScreenEvent.Opening event) {
        Screen arriving = event.getNewScreen();
        if (arriving == null || arriving instanceof Dials) {
            return;
        }
        boolean video = arriving instanceof VideoSettingsScreen
                || SODIUM_VIDEO.equals(arriving.getClass().getName());
        if (!video || !ClientConfig.ECRAN_VIDEO.get()) {
            return;
        }
        if (shiftHeld()) {
            return;
        }
        // Le parent est l'écran d'où l'on vient (Options) : Échap y revient, comme avec l'original.
        Screen parent = event.getCurrentScreen();
        event.setNewScreen(new Dials(parent));
    }

    private static boolean shiftHeld() {
        try {
            return com.mojang.blaze3d.platform.InputConstants.isKeyDown(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_LSHIFT)
                    || com.mojang.blaze3d.platform.InputConstants.isKeyDown(org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_RSHIFT);
        } catch (Throwable indisponible) {
            return false;
        }
    }
}
