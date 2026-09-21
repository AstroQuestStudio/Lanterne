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
 * PerfMixinPlugin (module « batching_groupes »). Lanterne est sous GPL-3.0-only ; ce fichier reste
 * LGPL-3.0-or-later.
 */
package fr.clubcitrouille.lanterne.perf.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Module « batching_groupes » (avec MixinScissorState) : autorise le réordonnancement des groupes. */
@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public abstract class MixinRenderTypeFeatureRenderer_Group {

    @ModifyVariable(method = "<init>", at = @At("HEAD"), name = "canReorder", argsOnly = true, require = 0)
    private static boolean forceReorderability(final boolean canReorder) {
        return true;
    }

}
