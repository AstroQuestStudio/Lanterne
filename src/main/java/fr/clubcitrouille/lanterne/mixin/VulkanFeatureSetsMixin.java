package fr.clubcitrouille.lanterne.mixin;

import java.util.LinkedHashSet;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;

/**
 * Le point d'extension : mesh shaders et ray tracing matériel, activés SI le pilote les supporte —
 * rien de plus. Pas de renderer, pas de pipeline mesh shader ni de structure d'accélération construite
 * ici : uniquement les extensions Vulkan demandées à la création du device, prêtes pour un chantier
 * séparé qui les exploitera vraiment.
 *
 * <h2>La preuve, capturée cette même passe</h2>
 *
 * <p>{@link VulkanPhysicalDeviceMixin} journalise désormais la liste BRUTE que le pilote expose, avant
 * tout filtrage par {@link VulkanFeatureSets}. Sur un lancement réel ({@code run-carte},
 * {@code preferredGraphicsBackend:"vulkan"}), le pilote NVIDIA de la RTX 3080 Laptop GPU annonce 287
 * extensions, dont — vérifiées lettre pour lettre dans le journal capturé —
 * {@code VK_EXT_mesh_shader}, {@code VK_NV_mesh_shader}, {@code VK_KHR_ray_tracing_pipeline},
 * {@code VK_KHR_acceleration_structure}, {@code VK_KHR_ray_query} et
 * {@code VK_KHR_deferred_host_operations}. Les deux capacités visées par ce mixin sont donc bien
 * réelles sur ce matériel — pas une supposition tirée de l'ancienneté architecturale d'Ampere.
 *
 * <h2>Pourquoi un {@code FeatureSet} par extension plutôt qu'un seul bloc</h2>
 *
 * <p>{@code VulkanBackend.createDevice} (vérifié par bytecode) teste CHAQUE {@code FeatureSet} rendu
 * par {@code optionalFeatureSets()} indépendamment — {@code FeatureSet.isSupported(device, extensions)}
 * exige que TOUTES les extensions du jeu soient présentes, sinon ce jeu entier est ignoré (avec un
 * simple {@code Optional FeatureSet [...] not supported} au journal, jamais un échec de démarrage :
 * {@code optionalFeatureSets()} n'est, par construction du moteur, jamais un gardien de démarrage
 * comme {@code requiredFeatureSets()}). Regrouper des extensions indépendantes dans un seul
 * {@code FeatureSet} les lierait en tout-ou-rien ; les séparer laisse chacune s'activer pour ce
 * qu'elle est réellement :
 *
 * <ul>
 *   <li>{@code VK_EXT_mesh_shader} et {@code VK_NV_mesh_shader} sont deux extensions
 *       INDÉPENDANTES (l'ancienne, propriétaire NVIDIA, et la nouvelle, multi-fournisseurs) — un
 *       pilote pourrait n'exposer que l'une des deux. Deux {@code FeatureSet} séparés, chacun activé
 *       si sa propre extension est là, couvrent les deux cas sans supposer laquelle existe.</li>
 *   <li>{@code VK_KHR_ray_tracing_pipeline} et {@code VK_KHR_acceleration_structure} sont au
 *       contraire INTERDÉPENDANTES par la spécification Vulkan — la première ne veut rien dire sans la
 *       seconde. {@code VK_KHR_acceleration_structure} exige elle-même
 *       {@code VK_KHR_deferred_host_operations} (jamais promue au cœur de Vulkan, contrairement à
 *       {@code VK_KHR_buffer_device_address}/{@code VK_EXT_descriptor_indexing}/
 *       {@code VK_KHR_spirv_1_4}/{@code VK_KHR_multiview}, tous au cœur de la version 1.2 que ce
 *       moteur demande — vérifié : {@code VulkanInstance.<init>} pose
 *       {@code VK12.VK_API_VERSION_1_2} dans {@code VkApplicationInfo.apiVersion}). Ces trois
 *       extensions forment donc UN SEUL {@code FeatureSet} : les trois ensemble, ou aucune — jamais
 *       une activation partielle qui violerait la dépendance déclarée par la spécification.</li>
 * </ul>
 *
 * <h2>Ce que ce mixin NE fait PAS</h2>
 *
 * <p>Aucun {@code VulkanFeature} (bit de {@code VkPhysicalDeviceMeshShaderFeaturesEXT}/
 * {@code VkPhysicalDeviceRayTracingPipelineFeaturesKHR}/
 * {@code VkPhysicalDeviceAccelerationStructureFeaturesKHR}, ex. {@code meshShader},
 * {@code rayTracingPipeline}, {@code accelerationStructure}) n'est demandé ici — le troisième
 * argument du constructeur de {@link FeatureSet} reste un ensemble vide. Activer ces bits exigerait de
 * déclarer leurs {@code VulkanPNextStruct} (comme {@code VulkanFeatureSets} le fait pour
 * {@code VK10_FEATURES_STRUCT} etc.) et de les chaîner dans le {@code pNext} de création du device —
 * un vrai chantier de renderer, explicitement hors périmètre ici. Ce mixin ne fait qu'ajouter les deux
 * extensions à {@code ppEnabledExtensionNames} lors de {@code vkCreateDevice}, si le pilote les
 * expose : le strict minimum pour qu'un futur chantier les trouve déjà actives plutôt que de devoir
 * réapprendre ce détour par {@code VulkanFeatureSets}.
 *
 * <h2>Confirmation au démarrage</h2>
 *
 * <p>Aucun journal supplémentaire n'est ajouté ici : {@code VulkanBackend.createDevice} journalise déjà
 * {@code Enabling optional FeatureSet [{}]} pour chaque jeu accepté (vérifié par bytecode, voir la
 * boucle sur {@code optionalFeatureSets()}) — les noms choisis ci-dessous («&nbsp;Lanterne Mesh
 * Shader…&nbsp;», «&nbsp;Lanterne Ray Tracing…&nbsp;») rendent cette ligne directement identifiable
 * dans le journal sans dupliquer le mécanisme vanilla.
 */
@Mixin(VulkanFeatureSets.class)
public abstract class VulkanFeatureSetsMixin {

    @Unique
    private static final FeatureSet LANTERNE_MESH_SHADER_EXT = new FeatureSet(
            "Lanterne Mesh Shader (EXT)",
            Set.of("VK_EXT_mesh_shader"),
            Set.of());

    @Unique
    private static final FeatureSet LANTERNE_MESH_SHADER_NV = new FeatureSet(
            "Lanterne Mesh Shader (NV, secours)",
            Set.of("VK_NV_mesh_shader"),
            Set.of());

    @Unique
    private static final FeatureSet LANTERNE_RAY_TRACING_KHR = new FeatureSet(
            "Lanterne Ray Tracing (KHR)",
            Set.of("VK_KHR_ray_tracing_pipeline", "VK_KHR_acceleration_structure",
                    "VK_KHR_deferred_host_operations"),
            Set.of());

    @ModifyReturnValue(method = "optionalFeatureSets", at = @At("RETURN"))
    private static Set<FeatureSet> lanterne$addMeshShaderAndRayTracing(Set<FeatureSet> vanilla) {
        Set<FeatureSet> augmente = new LinkedHashSet<>(vanilla);
        augmente.add(LANTERNE_MESH_SHADER_EXT);
        augmente.add(LANTERNE_MESH_SHADER_NV);
        augmente.add(LANTERNE_RAY_TRACING_KHR);
        return augmente;
    }
}
