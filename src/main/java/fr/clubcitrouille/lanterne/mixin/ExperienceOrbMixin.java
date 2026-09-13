package fr.clubcitrouille.lanterne.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.entity.ExperienceOrb;

import fr.clubcitrouille.lanterne.core.Clump;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le frein de fusion des orbes d'expérience, retiré.
 *
 * <p>Le raisonnement est dans {@link Clump}. Ici, seule compte la garantie : on retire la condition
 * sur l'identifiant d'entité — qui n'a aucun sens de jeu et ne sert qu'à brider le coût — et l'on
 * <b>conserve</b> l'égalité de valeur, sans laquelle la fusion créerait ou détruirait de
 * l'expérience.
 */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
    @WrapMethod(method = "canMerge(Lnet/minecraft/world/entity/ExperienceOrb;II)Z")
    private static boolean lanterne$mergeMoreOften(ExperienceOrb orb, int id, int value,
            Operation<Boolean> original) {
        if (!Settings.clump()) {
            return original.call(orb, id, value);
        }
        // Les deux conditions que vanilla garde, sans la troisième : l'orbe existe, et elle porte la
        // même valeur unitaire — faute de quoi l'addition des comptes fausserait le total.
        boolean allowed = !orb.isRemoved() && orb.getValue() == value;
        if (allowed && !original.call(orb, id, value)) {
            Clump.noteFreed();
        }
        return allowed;
    }
}
