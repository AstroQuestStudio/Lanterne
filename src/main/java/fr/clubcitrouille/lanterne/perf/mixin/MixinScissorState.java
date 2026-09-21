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
 * Modifications (Club Citrouille / Lanterne) : paquet changé, activation par PerfMixinPlugin
 * (module « batching_groupes »). Lanterne est sous GPL-3.0-only ; ce fichier reste LGPL-3.0-or-later.
 */
package fr.clubcitrouille.lanterne.perf.mixin;

import java.util.Objects;

import com.mojang.blaze3d.systems.ScissorState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Module « batching_groupes » : donne à ScissorState un equals/hashCode fonctionnels, dont dépend le
 * cache de RenderTypeFeatureRenderer$Group.
 */
@Mixin(ScissorState.class)
public abstract class MixinScissorState {

    @Shadow
    private boolean enabled;

    @Shadow
    private int x;

    @Shadow
    private int y;

    @Shadow
    private int width;

    @Shadow
    private int height;

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final MixinScissorState that = (MixinScissorState) o;
        return enabled == that.enabled && x == that.x && y == that.y && width == that.width && height == that.height;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, x, y, width, height);
    }

}
