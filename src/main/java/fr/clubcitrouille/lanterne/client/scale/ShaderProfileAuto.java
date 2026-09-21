package fr.clubcitrouille.lanterne.client.scale;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.client.upscale.Rival;
import fr.clubcitrouille.lanterne.core.ClientConfig;

/**
 * Le réglage automatique des petites configs : au PREMIER lancement d'un shaderpack sur un GPU qui n'a
 * pas les moyens de le faire tourner à pleine qualité, applique le profil de qualité que le pack
 * déclare lui-même, et règle la lentille (rendu à résolution réduite + remontée FSR).
 *
 * <h2>Pourquoi le profil vient du pack et pas de nous</h2>
 *
 * <p>Un pack de shaders décrit dans {@code shaders.properties} des profils nommés
 * ({@code profile.LOW}, {@code profile.POTATO}…) : la liste exacte des options qui font un rendu
 * léger POUR CE PACK. C'est ce que le pack considère comme sa version légère, et c'est ce qu'Iris
 * sait déjà appliquer d'un clic dans son propre menu. Nous ne réinventons donc aucune valeur : nous
 * choisissons un profil existant, selon la classe du GPU, et nous demandons à Iris de l'appliquer.
 *
 * <h2>Les garde-fous, qui comptent plus que le réglage</h2>
 *
 * <ul>
 *   <li><b>Une seule fois par pack.</b> La décision est écrite dans {@code config/lanterne/echelle/} ;
 *       un joueur qui remonte la qualité ensuite n'est jamais contredit.</li>
 *   <li><b>Jamais sur un pack déjà personnalisé</b> : si l'utilisateur a changé une option, on ne
 *       touche à rien.</li>
 *   <li><b>Jamais sur un GPU dédié courant</b> ni sur un GPU inconnu.</li>
 *   <li><b>Tout échec est silencieux pour le joueur</b> et écrit dans le journal.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class ShaderProfileAuto {
    private static final AtomicBoolean LENS_DONE = new AtomicBoolean(false);
    private static final AtomicBoolean PROFILE_DISABLED = new AtomicBoolean(false);
    private static GpuClass gpuClass;
    private static String gpuName = "";
    private static int ticks;

    private ShaderProfileAuto() {}

    /** La classe du GPU de cette machine, détectée une fois. */
    public static GpuClass gpuClass() {
        if (gpuClass == null) {
            try {
                GpuDevice device = RenderSystem.tryGetDevice();
                if (device == null) {
                    return GpuClass.UNKNOWN;
                }
                DeviceInfo info = device.getDeviceInfo();
                gpuName = info.name();
                gpuClass = GpuClass.classify(info.name(), info.vendorName());
                Lanterne.LOG.info("[ÉCHELLE] GPU « {} » ({}) classé {}.", info.name(), info.vendorName(),
                        gpuClass);
            } catch (Throwable unknown) {
                gpuClass = GpuClass.UNKNOWN;
            }
        }
        return gpuClass;
    }

    public static String gpuName() {
        return gpuName;
    }

    private static Path markerFile() {
        return FMLPaths.CONFIGDIR.get().resolve("lanterne").resolve("echelle").resolve("decisions.txt");
    }

    private static boolean decided(String key) {
        try {
            Path file = markerFile();
            return Files.isRegularFile(file) && Files.readAllLines(file).contains(key);
        } catch (IOException unreadable) {
            return true; // dans le doute, on ne touche à rien
        }
    }

    private static void remember(String line) {
        try {
            Path file = markerFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // pire cas : on retentera au prochain lancement, sans dégât
        }
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (!ScaleConfig.ready() || ++ticks % 40 != 0) { // toutes les 2 secondes suffit
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        GpuClass gpu = gpuClass();
        if (gpu == GpuClass.UNKNOWN || gpu == GpuClass.DEDICATED) {
            return;
        }
        applyLens(gpu);
        applyProfile(gpu);
    }

    /** La lentille : une fois, sur GPU intégré, si le joueur ne l'a pas déjà choisie. */
    private static void applyLens(GpuClass gpu) {
        if (gpu != GpuClass.INTEGRATED || !ScaleConfig.LENTILLE_AUTO.get() || LENS_DONE.get()
                || decided("lentille")) {
            return;
        }
        LENS_DONE.set(true);
        remember("lentille");
        try {
            if (!ClientConfig.LENS.get()) {
                ClientConfig.LENS.set(true);
                ClientConfig.LENS_PRESET.set(ScaleConfig.LENTILLE_IGPU.get());
                ClientConfig.LENS_SWELL.set(ScaleConfig.LENTILLE_HOULE_IGPU.get());
                Lanterne.LOG.info("[ÉCHELLE] GPU intégré : lentille activée au premier lancement "
                        + "(préréglage {}, houle {}). Modifiable dans lanterne-client.toml.",
                        ScaleConfig.LENTILLE_IGPU.get(), ScaleConfig.LENTILLE_HOULE_IGPU.get());
            }
        } catch (Throwable problem) {
            Lanterne.LOG.warn("[ÉCHELLE] réglage de la lentille impossible : {}", problem.toString());
        }
    }

    private static String normalize(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    /** Le profil du pack, choisi par nom parmi ceux qu'il déclare, ou null. */
    static String pickProfileName(GpuClass gpu, List<String> available) {
        List<String> wanted = new ArrayList<>();
        if (gpu == GpuClass.INTEGRATED) {
            wanted.add(normalize(ScaleConfig.PROFIL_IGPU.get()));
            wanted.addAll(List.of("LOW", "LITE", "LIGHT", "PERFORMANCE", "VERYLOW", "POTATO"));
        } else if (gpu == GpuClass.LOW_DEDICATED) {
            wanted.add(normalize(ScaleConfig.PROFIL_ENTREE_DE_GAMME.get()));
            wanted.addAll(List.of("MEDIUM", "BALANCED", "LOW", "LITE"));
        }
        for (String w : wanted) {
            for (String a : available) {
                if (normalize(a).equals(w)) {
                    return a;
                }
            }
        }
        return null;
    }

    /** Le profil du shaderpack : une fois par pack, sur pack non personnalisé. */
    private static void applyProfile(GpuClass gpu) {
        if (!ScaleConfig.PROFIL_AUTO.get() || PROFILE_DISABLED.get() || !Rival.engaged()) {
            return;
        }
        try {
            ProfileBridge.tryApply(gpu, gpuName);
        } catch (Throwable problem) {
            PROFILE_DISABLED.set(true); // n'essaie plus de la session
            Lanterne.LOG.warn("[ÉCHELLE] profil automatique impossible : {}", problem.toString());
        }
    }

    static boolean alreadyDecided(String pack) {
        return decided("profil:" + pack);
    }

    static void markDecided(String pack, String detail) {
        remember("profil:" + pack);
        remember("# " + pack + " -> " + detail);
    }
}
