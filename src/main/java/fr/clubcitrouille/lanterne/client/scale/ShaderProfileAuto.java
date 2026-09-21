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

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.client.upscale.Rival;
import fr.clubcitrouille.lanterne.core.ClientConfig;

/**
 * Les réglages « petites configs » : profil de qualité du shaderpack et lentille (rendu à résolution
 * réduite + remontée FSR), proposés selon la classe du GPU.
 *
 * <h2>Règle d'iso-qualité : jamais en silence</h2>
 *
 * <p>Ces réglages peuvent effacer des détails fins. Par défaut ({@link ScaleConfig.Mode#PROPOSER}) ils
 * sont donc seulement <b>proposés</b> : un message dans la discussion, une fois, avec la commande pour
 * l'accepter ({@code /lanterne echelle profil}, {@code /lanterne echelle lentille}) et pour ne plus en
 * entendre parler ({@code /lanterne echelle refuser}). Rien n'est modifié sans accord. Le mode
 * {@link ScaleConfig.Mode#AUTO} existe pour qui le choisit ; il applique au premier lancement, avec un
 * message. Et rien n'est proposé sur un GPU dédié courant ni sur un GPU inconnu.
 *
 * <h2>Pourquoi le profil vient du pack et pas de nous</h2>
 *
 * <p>Un pack décrit dans {@code shaders.properties} des profils nommés ({@code profile.LOW}…) : la
 * liste exacte des options de SA version légère. Nous n'inventons aucune valeur : nous choisissons un
 * profil existant et Iris l'applique, comme d'un clic dans son propre menu.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class ShaderProfileAuto {
    private static final AtomicBoolean PROFILE_DISABLED = new AtomicBoolean(false);
    private static GpuClass gpuClass;
    private static String gpuName = "";
    private static int ticks;
    private static boolean lensProposed;
    private static String proposedProfilePack = "";

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

    static boolean decided(String key) {
        try {
            Path file = markerFile();
            return Files.isRegularFile(file) && Files.readAllLines(file).contains(key);
        } catch (IOException unreadable) {
            return true; // dans le doute, on ne touche à rien
        }
    }

    static void remember(String line) {
        try {
            Path file = markerFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, line + System.lineSeparator(), StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // pire cas : on redira au prochain lancement, sans dégât
        }
    }

    private static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(message);
        }
    }

    // --- Les commandes : accepter, refuser --------------------------------------------------------

    @SubscribeEvent
    static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lanterne")
                .then(Commands.literal("echelle")
                        .then(Commands.literal("profil").executes(context -> {
                            acceptProfile();
                            return 1;
                        }))
                        .then(Commands.literal("lentille").executes(context -> {
                            acceptLens();
                            return 1;
                        }))
                        .then(Commands.literal("refuser").executes(context -> {
                            remember("lentille");
                            remember("refus");
                            say(Component.literal("Lanterne : d'accord, plus aucune proposition de "
                                    + "réglage pour petite config.").withStyle(ChatFormatting.GRAY));
                            return 1;
                        }))));
    }

    /** Active la lentille au préréglage propre à la classe du GPU. */
    static void acceptLens() {
        try {
            ClientConfig.LENS.set(true);
            ClientConfig.LENS_PRESET.set(ScaleConfig.LENTILLE_IGPU.get());
            ClientConfig.LENS_SWELL.set(ScaleConfig.LENTILLE_HOULE_IGPU.get());
            remember("lentille");
            Lanterne.LOG.info("[ÉCHELLE] lentille activée (préréglage {}, houle {}).",
                    ScaleConfig.LENTILLE_IGPU.get(), ScaleConfig.LENTILLE_HOULE_IGPU.get());
            say(Component.literal("Lanterne : lentille activée (" + ScaleConfig.LENTILLE_IGPU.get()
                    + "). Pour revenir en pleine résolution : lanterne-client.toml, « lentille = false ».")
                    .withStyle(ChatFormatting.GREEN));
        } catch (Throwable problem) {
            Lanterne.LOG.warn("[ÉCHELLE] activation de la lentille impossible : {}", problem.toString());
        }
    }

    /** Applique le profil du pack en service, choisi selon la classe du GPU. */
    static void acceptProfile() {
        try {
            ProfileBridge.apply(gpuClass(), gpuName, true);
        } catch (Throwable problem) {
            Lanterne.LOG.warn("[ÉCHELLE] profil impossible : {}", problem.toString());
        }
    }

    // --- Le tick : proposer ou appliquer ----------------------------------------------------------

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (!ScaleConfig.ready() || ++ticks % 40 != 0) { // toutes les 2 secondes suffit
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || decided("refus")) {
            return;
        }
        GpuClass gpu = gpuClass();
        if (gpu == GpuClass.UNKNOWN || gpu == GpuClass.DEDICATED) {
            return;
        }
        handleLens(gpu);
        handleProfile(gpu);
    }

    private static void handleLens(GpuClass gpu) {
        ScaleConfig.Mode mode = ScaleConfig.LENTILLE_MODE.get();
        if (gpu != GpuClass.INTEGRATED || mode == ScaleConfig.Mode.OFF || lensProposed
                || decided("lentille") || ClientConfig.LENS.get()) {
            return;
        }
        lensProposed = true;
        if (mode == ScaleConfig.Mode.AUTO) {
            acceptLens();
            return;
        }
        Lanterne.LOG.info("[ÉCHELLE] GPU intégré ({}) : lentille proposée au joueur (rien modifié).", gpuName);
        say(Component.literal("Lanterne : ton GPU est intégré (" + gpuName + "). Pour gagner des "
                + "images par seconde, tu peux activer la lentille (rendu à 77 % de la résolution, "
                + "puis remontée). Elle peut adoucir les détails fins. Accepter : /lanterne echelle lentille"
                + " — ne plus proposer : /lanterne echelle refuser").withStyle(ChatFormatting.YELLOW));
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

    private static void handleProfile(GpuClass gpu) {
        ScaleConfig.Mode mode = ScaleConfig.PROFIL_MODE.get();
        if (mode == ScaleConfig.Mode.OFF || PROFILE_DISABLED.get() || !Rival.engaged()) {
            return;
        }
        try {
            ProfileBridge.apply(gpu, gpuName, mode == ScaleConfig.Mode.AUTO);
        } catch (Throwable problem) {
            PROFILE_DISABLED.set(true); // n'essaie plus de la session
            Lanterne.LOG.warn("[ÉCHELLE] profil automatique impossible : {}", problem.toString());
        }
    }

    /** Vrai si le message de proposition de ce pack a déjà été dit cette session. */
    static boolean proposedThisSession(String pack) {
        return pack.equals(proposedProfilePack);
    }

    static void markProposed(String pack, String profile, String gpu) {
        proposedProfilePack = pack;
        Lanterne.LOG.info("[ÉCHELLE] GPU {} : profil « {} » proposé pour le shaderpack « {} » (rien modifié).",
                gpu, profile, pack);
        say(Component.literal("Lanterne : ton GPU est modeste (" + gpu + "). Le shaderpack « " + pack
                + " » propose un profil léger « " + profile + " » (ombres, reflets et nuages allégés, qui "
                + "peut effacer des détails fins). Accepter : /lanterne echelle profil — ne plus proposer :"
                + " /lanterne echelle refuser").withStyle(ChatFormatting.YELLOW));
    }

    static boolean alreadyDecided(String pack) {
        return decided("profil:" + pack);
    }

    static void markDecided(String pack, String detail) {
        remember("profil:" + pack);
        remember("# " + pack + " -> " + detail);
    }
}
