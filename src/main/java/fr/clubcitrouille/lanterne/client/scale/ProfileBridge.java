package fr.clubcitrouille.lanterne.client.scale;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.option.Profile;
import net.irisshaders.iris.shaderpack.option.ProfileSet;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le seul endroit qui touche aux classes d'Iris. Isolé pour que {@link ShaderProfileAuto} — et donc
 * toute la machine « petites configs » — reste chargeable quand Iris est inerte (sans Sodium) : cette
 * classe n'est nommée que sur le chemin où {@code Rival.engaged()} a répondu qu'un pack est en service.
 */
final class ProfileBridge {
    private ProfileBridge() {}

    static void tryApply(GpuClass gpu, String gpuName) {
        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        if (pack == null) {
            return;
        }
        String packName = Iris.getCurrentPackName();
        if (packName == null || packName.isEmpty() || ShaderProfileAuto.alreadyDecided(packName)) {
            return;
        }
        ProfileSet profiles = pack.getMenuContainer().getProfiles();
        if (profiles == null || profiles.size() == 0) {
            ShaderProfileAuto.markDecided(packName, "aucun profil déclaré par le pack");
            return;
        }
        // Un pack déjà personnalisé par le joueur n'est jamais contredit.
        if (pack.getShaderPackOptions().getOptionValues().getOptionsChanged() > 0) {
            ShaderProfileAuto.markDecided(packName, "options déjà personnalisées, rien changé");
            return;
        }
        List<String> names = new ArrayList<>();
        Map<String, Profile> byName = new LinkedHashMap<>();
        profiles.forEach((name, profile) -> {
            names.add(name);
            byName.put(name, profile);
        });
        String chosen = ShaderProfileAuto.pickProfileName(gpu, names);
        if (chosen == null) {
            ShaderProfileAuto.markDecided(packName, "aucun profil ne porte un nom reconnu parmi " + names);
            return;
        }
        Profile profile = byName.get(chosen);
        ShaderProfileAuto.markDecided(packName, "profil " + chosen + " (GPU " + gpu + " : " + gpuName + ")");
        Lanterne.LOG.info("[ÉCHELLE] GPU {} ({}) : profil « {} » appliqué au shaderpack « {} » "
                + "(premier lancement, {} options).", gpu, gpuName, chosen, packName,
                profile.optionValues.size());
        Iris.queueShaderPackOptionsFromProfile(profile);
        Minecraft.getInstance().execute(() -> {
            try {
                Iris.reload();
            } catch (IOException problem) {
                Lanterne.LOG.warn("[ÉCHELLE] rechargement du pack refusé : {}", problem.toString());
            }
        });
    }
}
