package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.QuadInstance;

import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.resources.model.geometry.BakedQuad;

import fr.clubcitrouille.lanterne.client.terrain.TerrainVertexFormat;

/**
 * Remplace l'émission de quad de terrain par une émission qui remplit aussi UV1/UV3 — voir
 * {@link TerrainVertexFormat} pour ce qu'ils portent et pourquoi.
 *
 * <h2>Le point d'injection, vérifié par bytecode cette passe</h2>
 *
 * <p>{@code javap -p -c} sur {@code SectionCompiler.class} du vrai jar patché confirme ce que les
 * notes avaient déjà trouvé : {@code compile(...)} construit deux {@code BlockQuadOutput} via lambda
 * (méthodes synthétiques {@code lambda$compile$0}/{@code lambda$compile$1} — des méthodes privées
 * réelles et stables, pas de l'{@code invokedynamic} fragile à cibler). Chacune fait exactement deux
 * choses : {@code getOrBeginLayer(...)} pour récupérer le {@code BufferBuilder} de la bonne couche,
 * puis {@code BufferBuilder.putBlockBakedQuad(x, y, z, quad, instance)}. Ce sont ces deux appels à
 * {@code putBlockBakedQuad} qui sont redirigés ici — un seul point de remplacement, dans les deux
 * méthodes qui alimentent TOUTES les couches (solide, découpe, translucide) puisque
 * {@code lambda$compile$0} distribue déjà selon {@code quad.materialInfo().layer()}.
 *
 * <h2>Pourquoi {@code @Redirect} plutôt qu'agir sur {@code VertexConsumer.putBlockBakedQuad}
 * lui-même</h2>
 *
 * <p>Cette méthode {@code default} est utilisée par BIEN plus que le terrain (item en main, entités,
 * blocs-entités...) — la viser directement gonflerait le risque et le périmètre pour rien. Rediriger
 * l'appel ICI, sur le {@code BufferBuilder} concret typé statiquement à cet endroit, restreint l'effet
 * exactement aux deux méthodes qui compilent une section de terrain.
 *
 * <h2>Sûr par construction si le format n'est pas celui attendu</h2>
 *
 * <p>{@link TerrainVertexFormat#putQuad} appelle {@code setUv1}/{@code setUv3} inconditionnellement.
 * Si {@code RenderPipelinesMixin} n'a pas patché le binding 0 (mixin désactivé, ou pipeline autre que
 * les trois couches de terrain), ces deux appels deviennent des no-op silencieux — vérifié par lecture
 * de {@code BufferBuilder} (javap) : un élément absent du format fait retourner {@code -1} à
 * {@code beginElement}, et chaque setter vérifie ce cas avant d'écrire. Le pire cas sans le mixin de
 * pipeline serait donc un rendu inchangé, jamais un crash — mais les deux mixins sont conçus pour être
 * posés ensemble, et {@code tools/verifie_mixins.py} contrôle les deux indépendamment.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {

    @Redirect(method = {"lambda$compile$0", "lambda$compile$1"},
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;"
                            + "putBlockBakedQuad(FFF"
                            + "Lnet/minecraft/client/resources/model/geometry/BakedQuad;"
                            + "Lcom/mojang/blaze3d/vertex/QuadInstance;)V"))
    private void lanterne$putQuadWithSpriteBounds(BufferBuilder buffer, float x, float y, float z,
            BakedQuad quad, QuadInstance instance) {
        TerrainVertexFormat.putQuad(buffer, x, y, z, quad, instance);
    }
}
