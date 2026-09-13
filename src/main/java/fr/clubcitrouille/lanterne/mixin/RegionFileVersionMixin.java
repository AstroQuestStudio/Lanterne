package fr.clubcitrouille.lanterne.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import fr.clubcitrouille.lanterne.core.Attic;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * La compression des sauvegardes, imposée en un seul point.
 *
 * <p>Le raisonnement et les chiffres sont dans {@link Attic}. Ce qui compte ici est l'endroit :
 * {@code getSelected()} est la <b>source unique</b> que toute écriture de chunk consulte, aussi bien
 * pour compresser le flux que pour annoncer l'identifiant dans l'en-tête.
 *
 * <p>Intervenir ici plutôt que sur les deux points d'usage garantit qu'ils ne pourront jamais
 * diverger — et un chunk dont l'identifiant ne correspond pas à son contenu est un chunk perdu.
 */
@Mixin(RegionFileVersion.class)
public abstract class RegionFileVersionMixin {
    @WrapMethod(method = "getSelected")
    private static RegionFileVersion lanterne$preferSpeed(Operation<RegionFileVersion> original) {
        if (Settings.save()) {
            return Attic.preferred();
        }
        return original.call();
    }
}
