package net.irisshaders.iris.mixin;

import com.google.common.base.Splitter;
import com.google.common.io.Files;
import fr.clubcitrouille.lanterne.report.Compagnons;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class IrisMixinPlugin implements IMixinConfigPlugin {
    private static final Splitter OPTION_SPLITTER = Splitter.on(':').limit(2);

    public static boolean usingVulkan;

    static {
        BufferedReader reader = null;
        boolean check = true;
        try {
            reader = Files.newReader(IrisPlatformHelpers.getInstance().getGameDir().resolve("options.txt").toFile(), StandardCharsets.UTF_8);
        } catch (FileNotFoundException e) {
            usingVulkan = false;
            check = false;
        }

        if (check) {
            Map<String, String> options = new HashMap<>();

            try {
                reader.lines().forEach(line -> {
                    try {
                        Iterator<String> iterator = OPTION_SPLITTER.split(line).iterator();
                        options.put((String) iterator.next(), (String) iterator.next());
                    } catch (Exception var3) {
                    }
                });
            } catch (Throwable var6) {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Throwable var5) {
                        var6.addSuppressed(var5);
                    }
                }

                throw var6;
            }

            if (options.get("preferredGraphicsBackend") != null) {
                usingVulkan = options.get("preferredGraphicsBackend").toLowerCase(Locale.ROOT).contains("vulkan");
            } else {
                usingVulkan = false;
            }
        }
    }
	@Override
	public void onLoad(String mixinPackage) {

	}

	@Override
	public String getRefMapperConfig() {
		return "iris.refmap.json";
	}

    /**
     * Ces 4 mixins ciblent des classes VANILLA (donc toujours chargées) mais importent des types
     * Sodium dans leurs champs/paramètres — voir RECHERCHE-VULKAN-SANS-SODIUM.md §"mixins vanilla
     * qui importent Sodium". Sans Sodium installé, appliquer un de ces mixins ferait résoudre un
     * type absent au moment où la JVM vérifie la bytecode du mixin, donc NoClassDefFoundError même
     * si aucune méthode réellement appelée ne référence Sodium. NON TESTÉ VISUELLEMENT (pas de client
     * jetable fonctionnel au moment de ce correctif) : compilation seule vérifiée.
     */
    private static final Set<String> VANILLA_MIXINS_IMPORTANT_SODIUM = Set.of(
        "net.irisshaders.iris.mixin.MixinLevelRenderer",
        "net.irisshaders.iris.mixin.MixinWindow",
        "net.irisshaders.iris.mixin.MixinRenderPipeline",
        "net.irisshaders.iris.mixin.MixinDebugScreenEntriesList"
    );

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains("VKOnly")) return usingVulkan;
        if (VANILLA_MIXINS_IMPORTANT_SODIUM.contains(mixinClassName) && !Compagnons.present(Compagnons.SODIUM)) {
            return false;
        }
		return !usingVulkan;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

	}

	@Override
	public List<String> getMixins() {
		return List.of();
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		//if (targetClassName.contains("LevelRenderer")) {
		//	targetClass.methods.forEach(m -> System.out.println(m.name + m.desc));
		//}
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

	}
}
