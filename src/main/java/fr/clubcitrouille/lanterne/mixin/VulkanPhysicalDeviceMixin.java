package fr.clubcitrouille.lanterne.mixin;

import java.util.stream.Collectors;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;

import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La vraie liste d'extensions Vulkan que le pilote expose — avant tout filtrage du moteur.
 *
 * <h2>Pourquoi ce mixin existe</h2>
 *
 * <p>{@code Vigie} journalise déjà {@code deviceInfo.underlyingExtensions()} une fois Vulkan actif
 * (voir {@code Vigie.rapporte}), mais cette liste ne reflète que ce que {@code VulkanFeatureSets}
 * a DEMANDÉ au pilote ({@code requiredFeatureSets()}/{@code requiredIfExtensionsAvailableFeatureSets()}/
 * {@code optionalFeatureSets()}, vérifié par lecture du bytecode de
 * {@code com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets}) — pas ce que le pilote supporte
 * réellement. Un pilote NVIDIA récent expose typiquement plusieurs dizaines d'extensions ; le moteur
 * n'en réclame qu'une douzaine (voir {@code VulkanFeatureSets.REQUIRED_FEATURESET} et consorts). Sans
 * ce mixin, aucune ligne du jeu ne montre jamais la liste brute — en particulier pas de quoi savoir si
 * {@code VK_EXT_mesh_shader}, {@code VK_NV_mesh_shader}, {@code VK_KHR_ray_tracing_pipeline} ou
 * {@code VK_KHR_acceleration_structure} sont disponibles.
 *
 * <h2>Le point d'accroche, vérifié par bytecode réel</h2>
 *
 * <p>{@code javap -p -c -constants} sur le vrai jar patché (jamais {@code .mcsrc/}, périmé) montre que
 * {@code VulkanPhysicalDevice(VkPhysicalDevice)} appelle {@code VK12.vkEnumerateDeviceExtensionProperties}
 * deux fois (une pour compter, une pour remplir {@code vkDeviceExtensions}), puis construit ses champs
 * de propriétés/features — et RIEN de tout ça ne consulte encore {@code VulkanFeatureSets}. Le filtrage
 * par jeu de fonctionnalités n'intervient que plus tard, côté appelant
 * ({@code VulkanBackend.checkDeviceSuitability}/{@code findPhysicalDevice}, via
 * {@code FeatureSet.isSupported}). Un {@code @Inject(at = @At("RETURN"))} sur ce constructeur capture
 * donc très exactement la liste brute du pilote, avant tout filtrage — pour CHAQUE carte examinée
 * pendant la sélection (l'iGPU puis le GPU dédié sur cette machine, le dédié étant reconstruit une
 * seconde fois pour la création réelle du device : voir {@code VulkanBackend.findPhysicalDevice} qui
 * construit une instance finale après la boucle de sélection).
 *
 * <p>{@code vkDeviceExtensions} est un champ {@code private final} — inatteignable normalement depuis
 * ce paquet — d'où le {@code @Shadow} ci-dessous, exactement comme {@code ClimatMixin} le fait pour
 * {@code parameterSpace}. {@code deviceName()}/{@code vendorName()} sont publiques mais déclarées
 * directement sur {@code VulkanPhysicalDevice} (pas héritées) ; ce mixin ne les étend pas, donc elles
 * sont elles aussi {@code @Shadow}ées pour que le compilateur les résolve, comme
 * {@code FouleSectionMixin} le fait pour {@code size()}.
 *
 * <h2>Ce que ce mixin ne fait pas</h2>
 *
 * <p>Il ne change aucun comportement — uniquement un {@code @Inject} en lecture seule qui journalise.
 * Aucun risque de régression sur la sélection du device ou la création du backend.
 */
@Mixin(VulkanPhysicalDevice.class)
public abstract class VulkanPhysicalDeviceMixin {

    @Shadow
    @Final
    private VkExtensionProperties.Buffer vkDeviceExtensions;

    @Shadow
    public abstract String deviceName();

    @Shadow
    public abstract String vendorName();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lanterne$logRawDriverExtensions(VkPhysicalDevice vkPhysicalDevice, CallbackInfo ci) {
        String extensions = this.vkDeviceExtensions.stream()
                .map(VkExtensionProperties::extensionNameString)
                .sorted()
                .collect(Collectors.joining(", "));
        Lanterne.LOG.info("[LANTERNE][VK] Extensions BRUTES du pilote pour {} ({}) — {} au total, "
                        + "AVANT tout filtrage par VulkanFeatureSets : {}",
                this.deviceName(), this.vendorName(), this.vkDeviceExtensions.capacity(), extensions);
    }
}
