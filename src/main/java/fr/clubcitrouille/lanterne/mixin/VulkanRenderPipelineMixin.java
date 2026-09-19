package fr.clubcitrouille.lanterne.mixin;

import java.nio.LongBuffer;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;

import fr.clubcitrouille.lanterne.client.Empreinte;

/**
 * Fait passer {@link Empreinte#handle()} là où ce moteur passait {@code VK_NULL_HANDLE} — pour TOUS
 * les pipelines qu'il compile, vanilla comme Lanterne. Voir le Javadoc d'{@link Empreinte} pour le
 * raisonnement complet ; ce mixin n'est que le point d'accroche.
 *
 * <h2>Le point d'accroche, vérifié par {@code javap -p -c -constants} sur le vrai jar patché</h2>
 *
 * <p>{@code VulkanRenderPipeline.compile(VulkanDevice, BackendRenderPipeline.CreateInfo)} appelle
 * {@code VK12.vkCreateGraphicsPipelines(VkDevice, long, VkGraphicsPipelineCreateInfo.Buffer,
 * VkAllocationCallbacks, LongBuffer)} exactement TROIS fois — une par variante de test de profondeur
 * (avec profondeur+stencil, avec profondeur seule, sans profondeur) — et pousse {@code lconst_0}
 * comme second argument (le {@code VkPipelineCache}) aux trois offsets de bytecode 1861, 1936 et
 * 2028, juste avant de charger le {@code VkGraphicsPipelineCreateInfo.Buffer}. Un seul
 * {@code @Redirect}, sans {@code ordinal}, s'applique par construction de Mixin aux TROIS occurrences
 * — exactement ce qu'il faut : les trois branches doivent toutes profiter du même cache, pas une
 * seule.
 *
 * <p>Vérifié aussi : {@code VulkanRenderPipeline.compile} est le SEUL appelant de
 * {@code vkCreateGraphicsPipelines} dans tout le jar patché — vanilla comme Lanterne compilent tous
 * leurs pipelines par ce chemin unique (voir {@link RenderPipelinesMixin} pour vanilla,
 * {@code Gbuffer}/{@code Accumulate} pour Lanterne — tous finissent par
 * {@code GpuDevice.compilePipeline}, qui délègue à cette même méthode statique). Ce mixin couvre donc
 * la totalité des pipelines de ce moteur, pas seulement ceux de l'Échelle.
 *
 * <h2>Pourquoi un {@code @Redirect} et pas un {@code @ModifyConstant}</h2>
 *
 * <p>{@code @ModifyConstant} viserait la constante {@code long 0} elle-même — fragile si une autre
 * constante longue nulle existait ailleurs dans la même méthode pour une tout autre raison (compter,
 * par exemple), auquel cas {@code @ModifyConstant} sans {@code ordinal} la changerait AUSSI, en
 * silence. {@code @Redirect} vise l'APPEL {@code vkCreateGraphicsPipelines} lui-même, qui n'a par
 * construction que cet unique second argument : aucune ambiguïté possible, et le comportement de
 * l'appel (soumettre les pipelines, remplir {@code pPipelines}) reste identique au bit près — seul le
 * {@code VkPipelineCache} passé change.
 *
 * <h2>Synchronisation, exigée par la spécification Vulkan</h2>
 *
 * <p>Voir la section « Synchronisation » du Javadoc d'{@link Empreinte}. {@link Empreinte#LOCK} est
 * acquis pour la durée de l'appel natif ET de {@link Empreinte#noteCompile()} qui le suit, pour que
 * {@code vkCreateGraphicsPipelines} (ici) et {@code vkGetPipelineCacheData}/{@code
 * vkDestroyPipelineCache} (dans {@link Empreinte}, sur {@code Util.backgroundExecutor()} ou à la
 * fermeture du device) ne s'exécutent jamais en même temps sur le même {@code VkPipelineCache}.
 */
@Mixin(VulkanRenderPipeline.class)
public abstract class VulkanRenderPipelineMixin {

    @Redirect(method = "compile",
            at = @At(value = "INVOKE",
                    target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;"
                            + "JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;"
                            + "Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
    private static int lanterne$avecEmpreinte(VkDevice vkDevice, long pipelineCacheJamaisUtilisee,
            VkGraphicsPipelineCreateInfo.Buffer createInfos, VkAllocationCallbacks allocator,
            LongBuffer pPipelines) {
        synchronized (Empreinte.LOCK) {
            long cache = Empreinte.handle();
            int result = VK12.vkCreateGraphicsPipelines(vkDevice, cache, createInfos, allocator, pPipelines);
            if (result == VK10.VK_SUCCESS && cache != VK10.VK_NULL_HANDLE) {
                Empreinte.noteCompile();
            }
            return result;
        }
    }
}
