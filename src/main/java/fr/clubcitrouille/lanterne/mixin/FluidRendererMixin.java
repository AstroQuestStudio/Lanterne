package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.block.FluidRenderer;

/**
 * Comble UV1/UV3 pour les quads de fluide — un crash réel trouvé par le premier vrai lancement
 * client de ce chantier, pas anticipé par la seule lecture de bytecode.
 *
 * <h2>Le crash, avec son message exact</h2>
 *
 * <p>Une fois {@code RenderPipelinesMixin}/{@code SectionCompilerMixin} posés seuls, le premier
 * chargement d'un monde réel plantait systématiquement avec :
 *
 * <pre>
 * java.lang.IllegalStateException: Missing elements in vertex:
 *   at BufferBuilder.endLastVertex(BufferBuilder.java:147)
 *   at ... FluidRenderer.vertex(FluidRenderer.java:430)
 *   at ... FluidRenderer.addFace / tesselate
 *   at ... SectionCompiler.compile
 * </pre>
 *
 * <p>Cause, vérifiée en lisant {@code FluidRenderer.vertex(VertexConsumer, float, float, float,
 * int, float, float, int)} au désassemblage : l'eau et la lave n'émettent PAS leurs sommets via
 * {@code BufferBuilder.putBlockBakedQuad} (le point intercepté par {@code SectionCompilerMixin}) —
 * elles appellent directement la surcharge groupée
 * {@code VertexConsumer.addVertex(FFFIFFIIFFF)}, un chemin entièrement différent qui alimente le
 * MÊME {@code BufferBuilder} par couche (retourné par {@code getOrBeginLayer}, donc construit avec
 * le format {@link fr.clubcitrouille.lanterne.client.terrain.TerrainVertexFormat#EXTENDED} lui aussi)
 * sans jamais toucher {@code UV3} — vérifié : aucun des appels par défaut qu'enchaîne cette surcharge
 * (position, couleur, UV0, surbrillance via {@code setOverlay} donc {@code UV1}, lumière, normal)
 * n'écrit {@code UV3} dans ce format. Un élément DÉCLARÉ mais jamais rempli fait échouer la
 * validation stricte de {@code BufferBuilder.endLastVertex} — vérifié aussi : elle liste
 * explicitement les éléments manquants et refuse le sommet plutôt que d'écrire une valeur
 * indéfinie. Exactement la panne bruyante que ce format est censé produire quand quelque chose ne
 * le remplit pas — mais elle ne s'est manifestée qu'au premier lancement client réel sur un vrai
 * monde contenant de l'eau, pas en lisant le bytecode seul.
 *
 * <h2>Le correctif : un sentinelle, pas une vraie fusion pour les fluides</h2>
 *
 * <p>Les fluides ne sont pas dans le périmètre de ce chantier — {@code SectionCompilerMixin} ne
 * fusionne (et ne fusionnera) que des quads de bloc. Il suffit donc de rendre {@code UV1}/{@code UV3}
 * inertes pour un quad de fluide, pas de leur donner de vraies bornes de sprite : {@code (0, 0)} sur
 * les deux fait tomber {@code lanterneSpriteSize} à zéro côté shader, ce qui déclenche la garde déjà
 * écrite dans {@code terrain.fsh} ({@code lanterneWrappedUv} retourne l'UV telle quelle si
 * {@code spriteSize <= 0}) — un fluide se rend donc en tout point comme avant cette passe. Écrasé
 * volontairement APRÈS l'appel groupé plutôt que de le laisser à ce que
 * {@code OverlayTexture.NO_OVERLAY} produirait une fois décomposé dans {@code UV1} : ce chemin ne
 * sert de toute façon à rien pour du terrain (aucun shader de terrain ne lit de surbrillance), autant
 * ne pas dépendre d'une valeur accidentelle pour que la garde s'active de façon fiable.
 */
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {

    @Redirect(method = "vertex",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                            + "addVertex(FFFIFFIIFFF)V"))
    private void lanterne$fluidSpriteBoundsSentinel(VertexConsumer sink, float x, float y, float z,
            int color, float u, float v, int overlay, int light, float normalX, float normalY,
            float normalZ) {
        sink.addVertex(x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
        sink.setUv1(0, 0);
        sink.setUv3(0.0f, 0.0f);
    }
}
