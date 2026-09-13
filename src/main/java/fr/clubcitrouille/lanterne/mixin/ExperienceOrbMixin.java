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
    /**
     * La portée de fusion, élargie.
     *
     * <h2>Le second frein, et le seul qui reste</h2>
     *
     * <p>Une fois la condition d.identifiant retirée, deux orbes ne se joignent encore que si leurs
     * boîtes se touchent à un demi-bloc près, et seulement un tick sur vingt :
     *
     * <pre>
     * if (this.tickCount % 20 == 1) this.scanForMerges();
     * ...
     * this.level().getEntities(…, this.getBoundingBox().inflate(0.5), this::canMerge)
     * </pre>
     *
     * <p>Un tapis d.orbes tombées d.une ferme se répartit sur plusieurs blocs : à un demi-bloc de
     * portée, elles restent voisines sans jamais se rejoindre. C.est ce qui explique qu.on tombe de
     * quatre mille à deux mille, et pas plus bas.
     *
     * <h2>Jusqu.où élargir, et pourquoi pas davantage</h2>
     *
     * <p>La recherche coûte, et son coût croît avec le volume examiné. Deux blocs et demi couvrent le
     * tas laissé par un mort ou une fournée de four sans aller chercher à travers un mur — une orbe
     * qui traverserait une cloison pour rejoindre sa voisine serait un défaut visible, pas une
     * optimisation.
     *
     * <p>C.est le compromis que ce module assume : moins d.orbes, ramassées plus vite, pour
     * exactement la même expérience — et aucune ne franchit un obstacle qu.elle n.aurait pas franchi.
     */
    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(
            method = "scanForMerges",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/AABB;inflate(D)Lnet/minecraft/world/phys/AABB;"))
    private net.minecraft.world.phys.AABB lanterne$reachFurther(net.minecraft.world.phys.AABB given) {
        if (!Settings.clump()) {
            return given;
        }
        return given.inflate(4.0d);
    }

    /**
     * La fréquence du balayage de fusion.
     *
     * <p>Vanilla ne cherche des voisines qu.un tick sur vingt — {@code if (this.tickCount % 20 == 1)}.
     * C.est le troisième frein, et le dernier. Une orbe qui vient de tomber attend donc jusqu.à une
     * seconde avant même de tenter de rejoindre ses voisines, et pendant cette seconde elle tick,
     * cherche des collisions et cherche un joueur comme n.importe quelle autre.
     *
     * <p>Chercher plus souvent fait fusionner plus tôt, donc laisse moins d.orbes — mais chaque
     * balayage coûte, et le volume examiné est maintenant de quatre blocs. Les deux effets vont en
     * sens contraire et seule la mesure peut les départager.
     */
    @org.spongepowered.asm.mixin.injection.ModifyConstant(method = "tick",
            constant = @org.spongepowered.asm.mixin.injection.Constant(intValue = 20))
    private int lanterne$scanMoreOften(int original) {
        return Settings.clump() ? 5 : original;
    }

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
