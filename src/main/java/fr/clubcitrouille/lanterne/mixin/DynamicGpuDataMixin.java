package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import net.minecraft.client.renderer.DynamicGpuData;

import fr.clubcitrouille.lanterne.core.ClientConfig;

/**
 * La réserve : démarre le tampon dynamique « Dynamic Transforms UBO » avec une capacité plus
 * généreuse que les DEUX transforms que ce moteur s'accorde par défaut. Voir le commentaire complet
 * de {@link ClientConfig#RESERVE} pour tout le raisonnement ; ce mixin n'est que le point
 * d'accroche.
 *
 * <h2>Le constat, vérifié par {@code javap -p -c -constants} sur le vrai jar patché</h2>
 *
 * <p>Preuve de départ, en jeu : des lignes répétées, PLUSIEURS FOIS dans la même image, au
 * chargement d'un monde ou devant une scène dense —
 *
 * <pre>[net.minecraft.client.renderer.DynamicGpuDataStorageMapped]: Resizing Dynamic Transforms UBO,
 * capacity limit of N reached during a single frame. New capacity will be N*2</pre>
 *
 * <p>Le désassemblage de {@code DynamicGpuData.<init>()} montre exactement la construction visée
 * ici :
 *
 * <pre>ldc           #19   // String Dynamic Transforms UBO
 * getstatic     #21   // Field TRANSFORM_UBO_SIZE:I
 * sipush        128   // GpuBuffer.USAGE_UNIFORM
 * iconst_2             // <-- la capacite de depart visee par ce mixin
 * invokespecial #25   // DynamicGpuDataStorageMapped.&lt;init&gt;:(Ljava/lang/String;III)V</pre>
 *
 * <p>Ce {@code iconst_2} est le SEUL littéral entier de valeur 2 dans tout le constructeur — le
 * seul autre argument numérique du même bloc, la capacité de {@code "Terrain UBO"} juste après,
 * pousse {@code iconst_1}, une valeur différente. Un {@code @ModifyConstant} sans {@code ordinal}
 * ne peut donc viser que ce point précis, sans ambiguïté — exactement la garantie que ce dépôt
 * exige avant d'employer cette annotation (voir {@code ElastiqueMixin} pour le précédent qui a
 * motivé la règle).
 *
 * <h2>Vrai UBO, pas un SSBO déguisé — vérifié, pas supposé</h2>
 *
 * <p>{@code GpuBuffer.USAGE_UNIFORM} vaut 128. Le désassemblage de
 * {@code VulkanConst.bufferUsageToVk(int)} montre que le bit 128 ne produit jamais que le bit
 * Vulkan réel {@code VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT} (16) — jamais
 * {@code VK_BUFFER_USAGE_STORAGE_BUFFER_BIT} (32), qu'aucun chemin de cette fonction ne produit
 * pour aucun des usages connus de ce moteur. Confirmé côté shader :
 * {@code assets/minecraft/shaders/include/dynamictransforms.glsl} déclare
 * {@code layout(std140) uniform DynamicTransforms}. Remplacer ce mécanisme par un grand SSBO
 * persistant indexé exigerait d'ajouter à l'abstraction {@code GpuBuffer} un usage de stockage qui
 * n'existe nulle part aujourd'hui, et de réécrire ce glsl en {@code layout(std430) buffer} sur
 * chaque pipeline qui l'inclut — une refonte moteur, pas une extension sûre par mixin, et
 * invérifiable sans client ce soir. Écartée pour cette raison précise, documentée ici plutôt que
 * silencieusement abandonnée.
 *
 * <h2>Le vrai coût : la rafale de réallocation, pas le régime permanent</h2>
 *
 * <p>{@code DynamicGpuDataStorageMapped.resizeBuffers(int)} ne recopie JAMAIS l'ancien tampon — il
 * l'abandonne (ajouté à {@code oldBuffers}, fermé au prochain {@code endFrame()}) et en construit un
 * tout neuf, triplement mis en tampon ({@link net.minecraft.client.renderer.MappableRingBuffer},
 * trois {@code GpuDevice.createBuffer}/{@code vmaCreateBuffer} par redimensionnement). Partant d'une
 * capacité de deux, une image qui a besoin de quelques centaines de transforms enchaîne SEPT
 * doublements consécutifs (2→4→8→16→32→64→128→256) avant de se stabiliser — jusqu'à 21 créations de
 * {@code VkBuffer} jetées dans la même image, exactement ce que le journal montre. Une fois la
 * capacité stabilisée, écrire dans un bloc déjà alloué (carte/écrit/démappe via VMA sur une
 * allocation déjà créée) reste bon marché et n'est PAS ce que ce mixin change.
 *
 * <h2>Ce que ce mixin fait, et rien de plus</h2>
 *
 * <p>La logique de croissance de {@code DynamicGpuDataStorageMapped} — doublement,
 * {@code Mth.smallestEncompassingPowerOfTwo}, alignement sur
 * {@code DeviceLimits.minUniformOffsetAlignment} — reste identique au bit près. Seul le PLANCHER de
 * départ change, et seulement quand {@link ClientConfig#RESERVE} est actif : {@code "Terrain UBO"}
 * (capacité de un), {@code "Chunk Sections UBO"} et {@code "Chunk Sections Command Buffer"}
 * (capacité de deux chacun) partagent le même défaut dans la même classe, mais le journal de ce soir
 * ne nomme que {@code "Dynamic Transforms UBO"} — eux ne sont pas touchés ici.
 */
@Mixin(DynamicGpuData.class)
public abstract class DynamicGpuDataMixin {

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 2))
    private int lanterne$capaciteInitialeTransforms(int vanilla) {
        return ClientConfig.RESERVE.get() ? ClientConfig.RESERVE_CAPACITE.get() : vanilla;
    }
}
