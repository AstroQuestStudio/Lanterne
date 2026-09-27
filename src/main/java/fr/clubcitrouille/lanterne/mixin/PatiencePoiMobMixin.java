package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

import net.minecraft.world.entity.PathfinderMob;

import fr.clubcitrouille.lanterne.core.PatiencePoi;

/** L'état de {@link PatiencePoi}, créé à la première recherche seulement. */
@Mixin(PathfinderMob.class)
public abstract class PatiencePoiMobMixin implements PatiencePoi.Porteur {
    @Unique
    private Reference2ObjectOpenHashMap<Object, long[]> lanterne$patience;

    @Override
    public Reference2ObjectOpenHashMap<Object, long[]> lanterne$patience() {
        if (this.lanterne$patience == null) {
            this.lanterne$patience = new Reference2ObjectOpenHashMap<>(2);
        }
        return this.lanterne$patience;
    }
}
