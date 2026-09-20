package net.irisshaders.iris.platform;

import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.api.v0.IrisProgram;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.minecraft.client.KeyMapping;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.NeoForgeRenderPipelines;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Point d'entree ORIGINAL d'Iris (@Mod("iris") retire) : ce code n'est pas encore un vrai mod
 * embarque dans Lanterne (mixins iris.*.json non copies, rien n'appelle ce constructeur), mais sa
 * seule presence dans le jar avec l'annotation @Mod faisait planter NeoForge au demarrage -
 * ModLoadingException "contains mod entrypoint class ... for mod with id iris, which does not
 * exist or is not in the same file" (FML scanne les classes @Mod independamment de
 * neoforge.mods.toml). Retirer l'annotation neutralise completement cette classe : code mort
 * dans le jar, jamais instancie, jamais scanne par FML. A revoir quand l'integration reelle sera
 * branchee (voir MIGRATION-IRIS.md).
 */
public class IrisForgeMod {
	public static List<KeyMapping> KEYLIST = new ArrayList<>();

	public IrisForgeMod(IEventBus bus, ModContainer modContainer) {
		bus.addListener(this::registerKeys);
		modContainer.registerExtensionPoint(IConfigScreenFactory.class, (game, screen) -> new ShaderPackScreen(screen));
		// ENTITY_SMOOTH_CUTOUT_CULL et ENTITY_TRANSLUCENT_CULL ont disparu de NeoForgeRenderPipelines
		// en 26.3.0.7-beta (absents du jar, vérifié via javap) : NeoForge a retiré ces deux variantes
		// de pipeline. Seul ENTITY_UNLIT_TRANSLUCENT subsiste encore côté NeoForge.
		IrisApi.getInstance().assignPipeline(NeoForgeRenderPipelines.ENTITY_UNLIT_TRANSLUCENT, IrisProgram.ENTITIES_TRANSLUCENT);
	}

	public void registerKeys(RegisterKeyMappingsEvent event) {
		KEYLIST.forEach(event::register);
		KEYLIST.clear();
	}
}
