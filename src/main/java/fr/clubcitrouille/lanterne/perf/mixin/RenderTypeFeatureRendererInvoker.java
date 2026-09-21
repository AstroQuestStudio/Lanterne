package fr.clubcitrouille.lanterne.perf.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accès à {@code getVertexBuilder} (protégé dans le jeu 26.3) pour le module « texte_recherche_rapide ».
 * Code original Lanterne (GPL-3.0-only).
 */
@Mixin(RenderTypeFeatureRenderer.class)
public interface RenderTypeFeatureRendererInvoker {
    @Invoker("getVertexBuilder")
    VertexConsumer lanterne$getVertexBuilder(RenderType renderType);
}
