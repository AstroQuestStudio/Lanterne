package fr.clubcitrouille.lanterne.client;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.ClientConfig;

/**
 * LA SONDE : constate si CE pilote, sur CETTE machine, supporte réellement les fonctionnalités
 * Vulkan qu'exigerait un rendu de textures bindless (indexation de descripteurs,
 * {@code VK_EXT_descriptor_indexing}) — et RIEN d'autre. Ce module ne construit aucun tableau de
 * descripteurs, ne touche à aucun pipeline, aucun shader, aucun appel de dessin.
 *
 * <h2>Pourquoi un module séparé plutôt qu'un rendu bindless directement</h2>
 *
 * <p>L'enquête complète (voir le rapport du chantier qui a écrit cette classe) conclut que ce moteur
 * n'a, CE SOIR, aucun point d'application où le bindless apporterait un gain net mesurable en
 * théorie :
 *
 * <ul>
 *   <li><b>Terrain</b> : {@code RenderTypes.java} (vanilla) lie l'atlas de blocs
 *       ({@code TextureAtlas.LOCATION_BLOCKS}) comme UNE SEULE texture partagée par tout le rendu
 *       solide/découpe/translucide — rien à indexer dynamiquement ici. Et les sections de chunk sont
 *       déjà regroupées en {@code vkCmdDrawIndexedIndirect} par lots partageant un même tampon
 *       (confirmé par bytecode réel, voir {@link ClientConfig#GUET}) : le coût CPU mesuré en jeu
 *       (6,3 % dans {@code extractSectionDrawGroups}) est du regroupement, pas des rebinds de
 *       texture en trop.</li>
 *   <li><b>Entités/objets</b> : {@code RenderSetup}/{@code PreparedRenderType} (vanilla) lient la
 *       texture au {@code RenderType}, donc au pipeline lui-même — changer de texture entre deux
 *       créatures appelle déjà {@code VulkanRenderPass.setPipeline}, qui force
 *       INCONDITIONNELLEMENT un descriptor set entièrement neuf ({@code anyDescriptorDirty = true},
 *       vérifié par lecture de {@code VulkanRenderPass.java}). Le bindless ne supprimerait donc PAS
 *       cet appel — seulement la taille de ce qu'il écrit, un gain marginal.</li>
 *   <li><b>Le mécanisme actuel</b> ({@code VulkanRenderPass.pushDescriptors}, vérifié par lecture de
 *       code) utilise {@code VK_KHR_push_descriptor} — une extension REQUISE de ce moteur
 *       ({@code VulkanFeatureSets.REQUIRED_FEATURESET}), pas un {@code VkDescriptorPool} classique.
 *       Aucun {@code vkAllocateDescriptorSets}/{@code vkCreateDescriptorPool} n'existe nulle part
 *       dans ce moteur pour les descripteurs de rendu : le coût que le bindless élimine
 *       habituellement (allocation/pool/rebind) est déjà structurellement absent ici.</li>
 * </ul>
 *
 * <p>Construire quand même un rendu bindless complet exigerait de toucher
 * {@code VulkanRenderPipeline.compile} (le pipeline layout PARTAGÉ par CHAQUE pipeline du moteur,
 * vanilla et Lanterne confondus), chaque shader fragment échantillonnant une texture
 * ({@code GL_EXT_nonuniform_qualifier}, capacité SPIR-V {@code SPV_EXT_descriptor_indexing}), le
 * compilateur SPIR-V ({@code PipelineBuilder}/{@code SPIRVModule}), et un registre durée-de-vie-sûre
 * des index de texture (chargement/déchargement de ressources, rechargement de pack) — un chantier de
 * rendu profond, invérifiable en jeu par qui écrit ce module ce soir. Voir le Javadoc de
 * {@link ClientConfig#SONDE_INDEXATION} pour le verdict complet.
 *
 * <h2>Ce que CE module fait, précisément</h2>
 *
 * <p>Interroge {@code vkGetPhysicalDeviceFeatures2} avec un {@code VkPhysicalDeviceVulkan12Features}
 * chaîné (JAMAIS lu ailleurs dans ce moteur pour CES bits précis — {@code VulkanPhysicalDevice}
 * n'interroge qu'un {@code VkPhysicalDeviceFeatures2} NU, sans aucune chaîne {@code pNext}, vérifié
 * par lecture de {@code VulkanPhysicalDevice.java} ; et {@code VulkanFeatureSets.VK12_FEATURES_STRUCT}
 * n'est utilisé que pour {@code timelineSemaphore}/{@code hostQueryReset}, jamais pour
 * l'indexation de descripteurs). Journalise le résultat, une fois par carte examinée. Ne modifie
 * AUCUN champ, ne demande AUCUNE extension ni fonctionnalité à la création du device : une requête
 * séparée, jetable, sur une structure allouée sur la pile et jamais réutilisée.
 *
 * <h2>Le point d'accroche</h2>
 *
 * <p>{@link fr.clubcitrouille.lanterne.mixin.VulkanPhysicalDeviceMixin} appelle
 * {@link #logDescriptorIndexingSupport} à la même méthode {@code RETURN} que le journal des
 * extensions brutes déjà présent ce soir — un second {@code @Inject} indépendant sur le même point,
 * pas une modification du premier.
 *
 * <h2>Interrupteur</h2>
 *
 * <p>{@link ClientConfig#SONDE_INDEXATION}, ÉTEINT par défaut, lu avec le même filet que
 * {@link Empreinte#onDeviceReady} : {@code VulkanPhysicalDevice} se construit pendant la SÉLECTION du
 * périphérique, potentiellement avant même le moment où {@link Empreinte} avait déjà identifié que la
 * config n'était pas encore chargée — donc au moins aussi tôt, probablement plus tôt. Une config pas
 * encore chargée est traitée comme "éteint pour ce lancement", jamais comme un plantage.
 */
public final class Sonde {

    private Sonde() {}

    /**
     * Journalise le support réel des quatre bits nécessaires à un futur rendu de textures bindless.
     * Appelé une fois par {@code VkPhysicalDevice} examiné pendant la sélection — voir le Javadoc de
     * classe. N'échoue jamais bruyamment : une exception inattendue n'interrompt ni la sélection du
     * device, ni le reste de la construction de {@code VulkanPhysicalDevice}.
     */
    public static void logDescriptorIndexingSupport(VkPhysicalDevice vkPhysicalDevice, String deviceName, String vendorName) {
        boolean actif;
        try {
            actif = ClientConfig.SONDE_INDEXATION.get();
        } catch (IllegalStateException configPasEncoreChargee) {
            return;
        }
        if (!actif) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceVulkan12Features vk12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            features2.pNext(vk12);
            VK12.vkGetPhysicalDeviceFeatures2(vkPhysicalDevice, features2);

            boolean nonUniformIndexing = vk12.shaderSampledImageArrayNonUniformIndexing();
            boolean partiallyBound = vk12.descriptorBindingPartiallyBound();
            boolean runtimeArray = vk12.runtimeDescriptorArray();
            boolean updateAfterBind = vk12.descriptorBindingSampledImageUpdateAfterBind();
            boolean tousPresents = nonUniformIndexing && partiallyBound && runtimeArray && updateAfterBind;

            Lanterne.LOG.info(
                "[LANTERNE][SONDE] Indexation de descripteurs (textures bindless) sur {} ({}) — "
                    + "shaderSampledImageArrayNonUniformIndexing={}, descriptorBindingPartiallyBound={}, "
                    + "runtimeDescriptorArray={}, descriptorBindingSampledImageUpdateAfterBind={} — {}.",
                deviceName,
                vendorName,
                nonUniformIndexing,
                partiallyBound,
                runtimeArray,
                updateAfterBind,
                tousPresents
                    ? "les QUATRE bits necessaires a un futur rendu bindless sont supportes"
                    : "au moins un bit manque — un futur rendu bindless ne pourrait PAS compter dessus sur cette carte"
            );
        } catch (Throwable problem) {
            Lanterne.LOG.warn(
                "[LANTERNE][SONDE] vkGetPhysicalDeviceFeatures2 (indexation de descripteurs) a leve une "
                    + "exception inattendue pour {} — diagnostic ignore pour ce peripherique.",
                deviceName,
                problem
            );
        }
    }
}
