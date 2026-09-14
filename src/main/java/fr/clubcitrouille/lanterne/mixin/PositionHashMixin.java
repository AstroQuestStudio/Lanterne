package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.core.Vec3i;

import fr.clubcitrouille.lanterne.core.Mix;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le nombre de renvoi des positions, brassé.
 *
 * <p>Le raisonnement et les comptes de collision sont dans {@link Mix}.
 *
 * <h2>Pourquoi une réécriture et non une injection</h2>
 *
 * <p>Ce projet préfère partout l'injection à la réécriture : elle laisse le code d'origine en place
 * et cohabite avec les autres mods. Ici, c'est le mauvais choix, et pour une raison que ce projet
 * a déjà payée trois fois.
 *
 * <p>{@code hashCode()} est appelé des millions de fois par seconde et tient en une ligne. Une
 * injection y fabriquerait un {@code CallbackInfoReturnable} à chaque appel et, surtout,
 * <b>empêcherait le compilateur à la volée d'incorporer la méthode</b> — c'est exactement le piège
 * qui a fait perdre à ce mod un module de bordure de monde, lequel coûtait quatre millisecondes
 * pour trois millions neuf cent mille recherches économisées. Sur une méthode aussi courte et aussi
 * chaude, le point d'injection coûterait plus cher que le travail supprimé.
 *
 * <p>La réécriture garde une méthode d'une ligne, incorporable, et l'interrupteur n'y ajoute qu'une
 * lecture de champ statique — que le compilateur à la volée élimine de lui-même dès qu'il constate
 * que la valeur ne change pas.
 *
 * <h2>Pourquoi {@code Vec3i} et non {@code BlockPos}</h2>
 *
 * <p>{@code BlockPos} hérite de {@code Vec3i} et n'a pas son propre nombre de renvoi. Ne toucher
 * qu'à la sous-classe créerait une situation où deux objets égaux — un {@code BlockPos} et un
 * {@code Vec3i} de mêmes coordonnées — rendraient des nombres différents, ce qui viole le contrat
 * de {@code hashCode} et casserait toute table où les deux se côtoient. Certains mods s'en servent
 * de cette façon.
 *
 * <p>Repris de <b>EfficientHashing</b> (ZZZank, LGPL-3.0), y compris ce raisonnement, que son
 * auteur documente.
 */
@Mixin(Vec3i.class)
public abstract class PositionHashMixin {
    @Shadow
    private int x;

    @Shadow
    private int y;

    @Shadow
    private int z;

    /**
     * @author Lanterne, d'après ZZZank (EfficientHashing)
     * @reason Le nombre de renvoi de vanilla fait entrer 93 % des positions en collision. Une
     *     injection retirerait l'incorporation d'une méthode d'une ligne appelée des millions de
     *     fois par seconde ; voir le commentaire de classe.
     */
    @Overwrite
    public int hashCode() {
        return Settings.mix()
                ? Mix.position(this.x, this.y, this.z)
                : (this.y + this.z * 31) * 31 + this.x;
    }
}
