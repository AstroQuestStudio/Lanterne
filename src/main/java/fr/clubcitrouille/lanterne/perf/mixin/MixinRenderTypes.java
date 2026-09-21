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
 * Modifications (Club Citrouille / Lanterne) : paquet changé, require = 0, activation par
 * PerfMixinPlugin (module « texte_sans_tri_translucide »). Lanterne est sous GPL-3.0-only ; ce
 * fichier reste LGPL-3.0-or-later.
 */
package fr.clubcitrouille.lanterne.perf.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Module « texte_sans_tri_translucide » (EXPÉRIMENTAL : les noms lambda$static$N dépendent de la
 * version exacte du jeu ; require = 0 : sans cible, le mixin ne fait rien et ne bloque pas le jeu).
 */
@Mixin(value = RenderTypes.class, priority = 500)
public abstract class MixinRenderTypes {

    @Redirect(method = {
        "lambda$static$22" /*TEXT_POLYGON_OFFSET*/,
        "lambda$static$23" /*TEXT_INTENSITY_POLYGON_OFFSET*/,
        "lambda$static$25" /*TEXT_INTENSITY_SEE_THROUGH*/
    }, require = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/rendertype/RenderSetup$RenderSetupBuilder;sortOnUpload()Lnet/minecraft/client/renderer/rendertype/RenderSetup$RenderSetupBuilder;"))
    private static RenderSetup.RenderSetupBuilder disableTranslucencySorting(final RenderSetup.RenderSetupBuilder instance) {
        return instance;
    }

}
