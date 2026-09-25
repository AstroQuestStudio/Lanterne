package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.FallbackResourceManager;

import fr.clubcitrouille.lanterne.core.Bouclier;

/**
 * Enveloppe chaque pack de ressources client non fiable dans le filtre du {@link Bouclier}, au point
 * unique par où passent les trois formes d'empilement ({@code push}, {@code push} filtré,
 * {@code pushFilterOnly}) : {@code FallbackResourceManager.pushInternal(String, PackResources,
 * PackResources.Filter)}, vérifié par {@code javap -p -c} sur le jar patché 26.3. Toutes les lectures
 * ultérieures ({@code getResource}, {@code getResourceStack}, {@code listResources},
 * {@code listResourceStacks}, métadonnées) passent par le pack enveloppé.
 */
@Mixin(FallbackResourceManager.class)
public abstract class BouclierFallbackMixin {
    @Shadow
    @Final
    private PackType type;

    @ModifyVariable(method = "pushInternal", at = @At("HEAD"), argsOnly = true)
    private PackResources lanterne$bouclier(PackResources pack) {
        return Bouclier.envelopper(this.type, pack);
    }
}
