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

	private static Boolean sodiumPresent;

	/**
	 * Sodium est-il sur le chemin de classes ? Detecte par la presence d'une de ses classes plutot que
	 * par {@code ModList}, qui n'existe pas encore quand les mixins s'appliquent (il repondait « non »
	 * meme avec Sodium).
	 *
	 * <p>Sans Sodium, Iris entier reste INERTE : aucun de ses mixins ne s'applique, donc aucune de
	 * ses classes (qui importent toutes Sodium : format de sommet, frustums, ombres) n'est chargee.
	 * Le jeu tourne alors en rendu vanilla, sans shaders. Verifie par lancement client, voir
	 * RECHERCHE-VULKAN-SANS-SODIUM.md.
	 */
	public static synchronized boolean sodiumPresent() {
		if (sodiumPresent == null) {
			boolean found = false;
			try {
				found = IrisMixinPlugin.class.getClassLoader()
						.getResource("net/caffeinemc/mods/sodium/client/SodiumClientMod.class") != null;
			} catch (Throwable ignored) {
			}
			sodiumPresent = found;
			System.out.println("[Lanterne/Iris] Sodium " + (found ? "present : Iris actif"
					: irisVanillaMode() ? "absent : mode Iris vanilla (experimental, -Dlanterne.irisvanilla=true)"
					: "absent : Iris inerte (rendu vanilla, pas de shaders)"));
		}
		return sodiumPresent;
	}

	/**
	 * Mode « Iris vanilla » (experimental) : {@code -Dlanterne.irisvanilla=true}. Sans Sodium, Iris ne
	 * reste plus inerte : ses mixins s'appliquent, sauf ceux qui dependent de Sodium.
	 */
	public static boolean irisVanillaMode() {
		return Boolean.getBoolean("lanterne.irisvanilla");
	}

	private static final java.util.Map<String, Boolean> SODIUM_FREE = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Le mixin est-il exempt de toute reference a Sodium ? On cherche la chaine {@code net/caffeinemc}
	 * dans le bytecode du mixin (constantes de classe et de descripteurs) : un mixin qui la contient
	 * ferait echouer le chargement sans Sodium, il est donc ecarte (et le dit une fois dans le log).
	 * Les mixins cibles de classes Sodium sont ecartes de la meme facon.
	 */
	private static boolean sodiumFree(String mixinClassName) {
		return SODIUM_FREE.computeIfAbsent(mixinClassName, name -> {
			if (name.contains(".compat.sodium.")) {
				return false;
			}
			String resource = name.replace('.', '/') + ".class";
			try (java.io.InputStream in = IrisMixinPlugin.class.getClassLoader().getResourceAsStream(resource)) {
				if (in == null) {
					return false;
				}
				byte[] bytes = in.readAllBytes();
				byte[] needle = "net/caffeinemc".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
				outer:
				for (int i = 0; i <= bytes.length - needle.length; i++) {
					for (int j = 0; j < needle.length; j++) {
						if (bytes[i + j] != needle[j]) {
							continue outer;
						}
					}
					System.out.println("[Lanterne/Iris] mixin ecarte (depend de Sodium) : " + name);
					return false;
				}
				return true;
			} catch (Throwable unreadable) {
				return false;
			}
		});
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!sodiumPresent()) {
			if (!irisVanillaMode() || !sodiumFree(mixinClassName)) {
				return false;
			}
		}
		if (mixinClassName.contains("VKOnly")) return usingVulkan;
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
