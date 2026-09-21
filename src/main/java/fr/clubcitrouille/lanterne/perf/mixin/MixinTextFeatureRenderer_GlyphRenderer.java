/*
 * Adapté de ImmediatelyFast - https://github.com/RaphiMC/ImmediatelyFast (branche 26.3, commit 3e7986d)
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Modifications (Club Citrouille / Lanterne) : paquet changé, champs renommés, require = 0,
 * activation par PerfMixinPlugin (module « texte_recherche_rapide »). Lanterne est sous
 * GPL-3.0-only ; ce fichier reste LGPL-3.0-or-later.
 */
package fr.clubcitrouille.lanterne.perf.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Module « texte_recherche_rapide » : réduit les appels à getVertexBuilder pendant le rendu du texte. */
@Mixin(targets = "net.minecraft.client.renderer.feature.TextFeatureRenderer$GlyphRenderer")
public abstract class MixinTextFeatureRenderer_GlyphRenderer {

    @Unique
    private RenderType lanterne$lastRenderType;

    @Unique
    private VertexConsumer lanterne$lastVertexConsumer;

    @Redirect(method = "acceptRenderable", require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/feature/TextFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer reduceGetVertexBuilderCalls(final TextFeatureRenderer instance, final RenderType renderType) {
        if (this.lanterne$lastRenderType != renderType) {
            this.lanterne$lastRenderType = renderType;
            this.lanterne$lastVertexConsumer = ((RenderTypeFeatureRendererInvoker) (Object) instance).lanterne$getVertexBuilder(renderType);
        }
        return this.lanterne$lastVertexConsumer;
    }

}
