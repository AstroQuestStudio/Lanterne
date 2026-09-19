package fr.clubcitrouille.lanterne.mixin;

import org.lwjgl.vulkan.VkDevice;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.checkpoints.CheckpointExtension;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;

import fr.clubcitrouille.lanterne.client.Empreinte;

/**
 * Les deux bornes de vie du {@code VkPipelineCache} : chargé dès que le device existe, sauvegardé
 * juste avant qu'il disparaisse. Voir {@link Empreinte} pour tout le raisonnement — ce mixin n'est
 * que le point d'accroche, volontairement sans logique propre.
 *
 * <h2>Le constructeur, vérifié par {@code javap} sur le vrai jar patché</h2>
 *
 * <p>{@code public VulkanDevice(VulkanInstance, VulkanPhysicalDevice, FeatureSet, VkDevice, long,
 * CheckpointExtension)} — l'ordre des paramètres ci-dessous reprend exactement celui-là, faute de
 * quoi Mixin refuserait de charger ({@code @Inject} exige la signature exacte de la cible pour un
 * {@code <init>}). {@code physicalDevice} porte {@code vkPhysicalDeviceProperties()}, qui à son tour
 * porte {@code pipelineCacheUUID} — voir la section « Nommage » du Javadoc d'{@link Empreinte} pour
 * pourquoi ce champ précis identifie le fichier à charger. {@code @At("RETURN")} garantit que
 * {@code Empreinte.onDeviceReady} ne voit ce device qu'une fois entièrement construit — avant même
 * ça, {@code vkDevice} existe déjà comme variable locale, mais rien ne garantit que le reste de la
 * construction (files, encodeur de commandes) ait fini.
 *
 * <h2>{@code close()}, et pourquoi {@code @At("HEAD")} plutôt que {@code RETURN}</h2>
 *
 * <p>{@code Empreinte.onDeviceClosing} doit lire le cache AVANT que quoi que ce soit ne commence à
 * démonter le device — {@code vkDevice()} doit encore répondre. {@code RenderSystem.shutdownRenderer()}
 * (vérifié par bytecode : appelle {@code GpuDevice.close()}, lui-même {@code FrontendGpuDevice.close()}
 * qui appelle {@code backend.close()}) est le seul chemin normal vers cette méthode — la fermeture
 * normale du jeu, donc, pas un cas rare.
 */
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lanterne$chargerEmpreinte(VulkanInstance instance, VulkanPhysicalDevice physicalDevice,
            FeatureSet features, VkDevice vkDevice, long vma, CheckpointExtension checkpointExtension,
            CallbackInfo ci) {
        Empreinte.onDeviceReady(vkDevice, physicalDevice.vkPhysicalDeviceProperties());
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void lanterne$sauverEmpreinte(CallbackInfo ci) {
        Empreinte.onDeviceClosing();
    }
}
