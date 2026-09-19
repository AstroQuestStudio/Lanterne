package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkPhysicalDevice;

import it.unimi.dsi.fastutil.ints.IntIntPair;

import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Rend visible en jeu ce que {@code VulkanPhysicalDevice} sait déjà, mais que rien ne
 * journalisait avant ce mixin : la famille de file Vulkan réellement choisie pour le graphisme,
 * le calcul et le transfert sur le pilote/GPU de CE client.
 *
 * <h2>Pourquoi ce mixin existe — enquête file de transfert dédiée</h2>
 *
 * <p>Objectif de l'enquête : faire transiter l'upload des tampons de sommets/indices d'un chunk
 * fraîchement maillé par une file Vulkan de TRANSFERT dédiée plutôt que par la file graphique.
 * Lecture du vrai code ({@code VulkanPhysicalDevice}, {@code VulkanDevice}, sources embarquées du
 * jar patché, jamais {@code .mcsrc}) : le moteur calcule DÉJÀ {@code transferQueueFamilyAndIndex}
 * dans ce constructeur (boucle sur {@code vkGetPhysicalDeviceQueueFamilyProperties}, préfère la
 * famille avec le moins de bits posés parmi celles qui passent
 * {@code VulkanUtils.hasAnyBit(queueFlags, 7)} — c'est-à-dire la famille la plus "pure" possible
 * côté transfert) et {@code VulkanDevice} construit bien une {@link com.mojang.renderpearl.backend.vulkan.VulkanQueue}
 * réelle dessus (repli sur la file de calcul, puis sur la file graphique, si absente) — vérifié
 * par lecture complète, PAS une supposition. Mais grep sur les sources DÉCOMPILÉES entières du
 * moteur : {@code transferQueue} n'apparaît que dans son propre accesseur et dans cette classe de
 * détection — jamais utilisée pour soumettre la moindre commande. C'est une ressource réelle,
 * jamais branchée.
 *
 * <p>Sonde Vulkan indépendante (hors jeu, {@code vkEnumeratePhysicalDevices} +
 * {@code vkGetPhysicalDeviceQueueFamilyProperties} seuls, aucune fenêtre) sur cette machine de
 * mesure : les DEUX GPU visibles (iGPU AMD Radeon Graphics ET RTX 3080 Laptop dédié) exposent
 * réellement une famille TRANSFER|SPARSE_BINDING sans GRAPHICS ni COMPUTE — donc
 * {@code transferQueueFamilyAndIndex} n'est PAS un repli silencieux sur cette machine, c'est une
 * vraie file matérielle distincte. Ce mixin journalise ce fait tel quel, par client, pour ne plus
 * jamais avoir à le redeviner : chercher {@code [LANTERNE][VK-QUEUES]} dans les logs.
 *
 * <h2>Pourquoi ce mixin ne branche PAS encore l'upload de chunk sur cette file</h2>
 *
 * <p>Lecture complète de {@code VulkanCommandEncoder} (sources embarquées, jamais {@code .mcsrc}) :
 * tout le moteur — y compris {@code SectionRenderDispatcher.uploadTerrainBuffersToGpu()}, le point
 * d'upload réel des tampons de section — passe par UN SEUL encodeur de commandes partagé, UNE
 * SEULE file de soumission ({@code submissionBuilder = device.graphicsQueue().beginSubmit()}), et
 * chaque copie ({@code writeToBuffer}/{@code copyToBuffer}, exactement ce que
 * {@code StagingBuffer.copyTo} utilise pour les tampons de chunk) est suivie d'une barrière mémoire
 * pleine ({@code ALL_COMMANDS}/{@code MEMORY_READ|WRITE}) SUR LA MÊME FILE — donc déjà
 * sérialisante avec le rendu par construction, indépendamment de la file matérielle utilisée.
 * Plus grave : chaque {@code VulkanGpuBuffer} (donc les tampons uber de section) est créé avec
 * {@code sharingMode = VK_SHARING_MODE_EXCLUSIVE} et sans liste de familles
 * ({@code VulkanGpuBuffer.Direct}, {@code bufferCreateInfo.sharingMode(0)},
 * {@code pQueueFamilyIndices(null)}) — utiliser un tel tampon depuis une seconde famille de file
 * SANS transfert de propriété explicite (barrière release/acquire appariée + sémaphore) est un
 * comportement non défini selon la spécification Vulkan, et ce mécanisme n'existe nulle part dans
 * ce moteur aujourd'hui (aucune seconde timeline, aucune barrière
 * {@code srcQueueFamilyIndex}/{@code dstQueueFamilyIndex} dans tout le paquet {@code vulkan}).
 * Router l'upload de chunk vers {@code device.transferQueue()} sans résoudre ces deux points ne
 * serait pas un ajout "à côté" sûr — ce serait soit un bug de corruption/hang selon le pilote, soit
 * un correctif cosmétique qui ne change rien tant que la barrière pleine côté file graphique n'est
 * pas elle-même revue. Vérifier concrètement qu'une telle resynchronisation croisée fonctionne
 * exigerait un client réel avec les validation layers actives — explicitement hors périmètre de
 * cette passe (aucun client lancé).
 *
 * <p>Verdict de cette passe : infrastructure matérielle réelle et confirmée (ce mixin la rend
 * visible), mais la brancher correctement demande une vraie extension de
 * {@code VulkanCommandEncoder} (seconde timeline, transfert de propriété ou passage en
 * {@code VK_SHARING_MODE_CONCURRENT} pour les tampons uber de section) — pas un correctif de
 * quelques lignes, et pas quelque chose à livrer sans validation GPU réelle.
 */
@Mixin(VulkanPhysicalDevice.class)
public abstract class VulkanQueueFamiliesMixin {

    @Shadow
    @Final
    private @Nullable IntIntPair graphicsQueueFamilyAndIndex;

    @Shadow
    @Final
    private @Nullable IntIntPair computeQueueFamilyAndIndex;

    @Shadow
    @Final
    private @Nullable IntIntPair transferQueueFamilyAndIndex;

    @Shadow
    public abstract String deviceName();

    @Shadow
    public abstract String vendorName();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lanterne$logQueueFamilies(VkPhysicalDevice vkPhysicalDevice, CallbackInfo ci) {
        String graphics = describe(this.graphicsQueueFamilyAndIndex);
        String compute = this.computeQueueFamilyAndIndex == null
                ? "aucune famille dédiée — repli sur la file graphique"
                : describe(this.computeQueueFamilyAndIndex);
        String transfer = this.transferQueueFamilyAndIndex == null
                ? "aucune famille dédiée — repli sur la file de calcul (ou graphique)"
                : describe(this.transferQueueFamilyAndIndex);
        boolean transferEstDistincte = this.transferQueueFamilyAndIndex != null
                && this.graphicsQueueFamilyAndIndex != null
                && this.transferQueueFamilyAndIndex.leftInt() != this.graphicsQueueFamilyAndIndex.leftInt();

        Lanterne.LOG.info(
                "[LANTERNE][VK-QUEUES] {} ({}) — graphique: {} ; calcul: {} ; transfert: {} ; "
                        + "transfert sur une famille matérielle distincte de la famille graphique : {}",
                this.deviceName(), this.vendorName(), graphics, compute, transfer, transferEstDistincte);
    }

    private static String describe(IntIntPair familyAndIndex) {
        return "famille=" + familyAndIndex.leftInt() + ", index=" + familyAndIndex.rightInt();
    }
}
