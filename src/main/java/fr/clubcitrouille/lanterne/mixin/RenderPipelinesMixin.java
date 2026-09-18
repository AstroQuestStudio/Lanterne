package fr.clubcitrouille.lanterne.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.renderer.RenderPipelines;

import fr.clubcitrouille.lanterne.client.terrain.TerrainVertexFormat;

/**
 * Étend le format de sommet du binding 0 des pipelines de terrain — et seulement eux.
 *
 * <h2>Le point d'injection, vérifié par bytecode cette passe (pas repris tel quel des notes)</h2>
 *
 * <p>{@code javap -p -c -constants} sur {@code net/minecraft/client/renderer/RenderPipelines.class}
 * du vrai jar patché (jamais {@code .mcsrc}, périmé et toujours sur {@code com.mojang.blaze3d} pour
 * ce paquet) confirme ce que la passe précédente avait compté à la main sur le désassemblage :
 * {@code RenderPipeline$Builder.withFragmentShader(String)} est appelé un peu plus de cinquante fois
 * dans {@code <clinit>}, et les deux tout premiers appels — {@code ordinal = 0} et {@code ordinal = 1}
 * — sont exactement ceux de {@code TERRAIN_SNIPPET} (offset 485 du bytecode) et
 * {@code MULTIDRAW_TERRAIN_SNIPPET} (offset 535), tous deux avec la chaîne {@code "core/terrain"} en
 * argument. Confirmé cette fois par un vrai désassemblage relu ligne à ligne, pas seulement compté.
 *
 * <p>Chaîner {@code .withVertexBinding(0, ...)} sur la valeur de retour de {@code withFragmentShader}
 * — plutôt que d'intercepter l'écriture du champ statique {@code TERRAIN_SNIPPET} après coup — est
 * nécessaire : {@code buildSnippet()} fige un {@code Snippet} immuable, et {@code withVertexBinding}
 * est une méthode du {@code Builder}, pas du {@code Snippet}. Intercepter après {@code buildSnippet()}
 * serait donc trop tard.
 *
 * <h2>Pourquoi remplacer le binding 0 plutôt qu'ajouter un binding 2</h2>
 *
 * <p>Voir le Javadoc de {@link TerrainVertexFormat} pour le raisonnement complet. En bref : un binding
 * 2 séparé aurait exigé de refaire la plomberie d'upload/dessin de section sur plusieurs classes
 * (aucune ne gère aujourd'hui plus d'un {@code MeshData} par couche). Remplacer le binding 0 par un
 * format un peu plus large réutilise exactement le même chemin d'upload/dessin — zéro classe
 * supplémentaire à toucher en dehors de {@code SectionCompiler}.
 *
 * <h2>Ce que ça ne touche PAS</h2>
 *
 * <p>{@code GENERIC_BLOCKS_SNIPPET} (qui pose {@code DefaultVertexFormat.BLOCK} au binding 0) est
 * construit et figé AVANT ce point, dans un appel séparé à {@code buildSnippet()}. Les deux mixins
 * ci-dessous s'appliquent chacun à une construction de {@code Builder} fraîche et propre à
 * {@code TERRAIN_SNIPPET}/{@code MULTIDRAW_TERRAIN_SNIPPET} — vérifié par lecture du bytecode que
 * chaque pipeline terrain démarre d'un nouveau {@code RenderPipeline.builder(...)}, jamais d'un
 * builder partagé avec {@code BLOCK_SNIPPET} (blocs tenus en main) ou les entités. Le binding 0 de
 * {@code DefaultVertexFormat.BLOCK} reste donc identique partout ailleurs dans le jeu.
 *
 * <h2>Les pipelines OIT, cassés puis réparés par un vrai cycle de vérification</h2>
 *
 * <p>Les pipelines de transparence indépendante de l'ordre ({@code OIT_TERRAIN}/
 * {@code OIT_TERRAIN_MULTIDRAW}, et leurs variantes {@code oit_accumulate_*}) ne descendent PAS de
 * {@code TERRAIN_SNIPPET}/{@code MULTIDRAW_TERRAIN_SNIPPET} — vérifié par bytecode : ils construisent
 * leur propre {@code Builder} à partir de {@code GENERIC_BLOCKS_SNIPPET} directement, avec leurs
 * propres appels (non couverts par ce mixin) à {@code withFragmentShader("core/terrain")}.
 *
 * <p><b>Un premier essai qui déclarait {@code UV1}/{@code UV3} dans {@code terrain.vsh}
 * inconditionnellement a cassé exactement ces pipelines au premier lancement client réel</b> —
 * {@code ShaderCompileException: vertex shader (minecraft:core/terrain) attrib (UV3) does not have a
 * matching vertex buffer element}, levée à la compilation de
 * {@code minecraft:pipeline/oit_accumulate_terrain_multidraw} pendant le chargement des ressources,
 * AVANT même qu'aucun monde ne soit rejoint — donc indépendamment de la valeur de
 * {@code improvedTransparency}, contrairement à ce qu'une première lecture (sans client réel) avait
 * supposé. Exactement le genre d'erreur que ce chantier ne peut voir qu'en lançant un client pour de
 * vrai — vérifié cette fois, pas supposé.
 *
 * <p>Corrigé en rendant les deux nouveaux attributs CONDITIONNELS côté shader
 * ({@code #ifdef LANTERNE_TERRAIN_EXT}, voir {@code terrain.vsh}/{@code terrain.fsh}), et en posant ce
 * define UNIQUEMENT sur les deux constructions patchées ici via
 * {@code withShaderDefine(TerrainVertexFormat.SHADER_DEFINE)}. Les pipelines OIT compilent donc le
 * même fichier {@code core/terrain} SANS ces attributs — exactement leur comportement d'avant cette
 * passe, format {@code BLOCK} d'origine inclus — pendant que les six pipelines terrain ordinaires les
 * ont. Aucune classe OIT n'a besoin d'être touchée : la garde est entièrement côté define de shader.
 */
@Mixin(RenderPipelines.class)
public abstract class RenderPipelinesMixin {

    @ModifyExpressionValue(method = "<clinit>",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;"
                            + "withFragmentShader(Ljava/lang/String;)"
                            + "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;",
                    ordinal = 0))
    private static RenderPipeline.Builder lanterne$terrainVertexFormat(RenderPipeline.Builder builder) {
        return builder.withVertexBinding(0, TerrainVertexFormat.EXTENDED)
                .withShaderDefine(TerrainVertexFormat.SHADER_DEFINE);
    }

    @ModifyExpressionValue(method = "<clinit>",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;"
                            + "withFragmentShader(Ljava/lang/String;)"
                            + "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline$Builder;",
                    ordinal = 1))
    private static RenderPipeline.Builder lanterne$multidrawTerrainVertexFormat(
            RenderPipeline.Builder builder) {
        return builder.withVertexBinding(0, TerrainVertexFormat.EXTENDED)
                .withShaderDefine(TerrainVertexFormat.SHADER_DEFINE);
    }
}
