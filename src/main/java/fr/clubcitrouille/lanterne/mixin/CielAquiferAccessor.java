package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.levelgen.Aquifer;

/**
 * Les deux champs de l'aquifère bruité sans lesquels le ciel ne se prouve pas.
 *
 * <h2>Pourquoi ces deux-là, et pas un test maison</h2>
 *
 * <p>{@code computeSubstance} a un chemin rapide, et c'est le seul par lequel on sache dire de
 * l'air sans rien échantillonner :
 *
 * <pre>
 * if (posY &gt; this.skipSamplingAboveY) return this.globalFluidPicker.computeFluid(x, y, z).at(posY);
 * </pre>
 *
 * <p>Les deux champs sont privés, et il n'existe aucun accesseur public équivalent.
 * {@code skipSamplingAboveY} est calculé dans le constructeur à partir du relief préliminaire du
 * voisinage : rien, dehors, ne permet de le reconstituer sans refaire le travail. Quant au
 * sélecteur de fluide, on aurait pu le prendre sur le générateur, qui le détient aussi — mais le
 * prendre <b>sur l'aquifère</b> garantit que c'est le même objet que celui que
 * {@code computeSubstance} interrogerait, ce qui est la seule formulation de la garantie qui ne
 * repose sur aucune hypothèse.
 *
 * <p>Deux lectures de champ par cellule, aucune allocation, aucun calcul. Voir {@code core.Ciel}
 * pour ce que l'on en déduit.
 */
@Mixin(Aquifer.NoiseBasedAquifer.class)
public interface CielAquiferAccessor {
    /**
     * La cote au-dessus de laquelle l'aquifère ne s'échantillonne plus.
     *
     * <p>C'est elle, et non le sommet du terrain, qui fixe la fraction des cellules que le module
     * peut prendre : elle vaut le point le plus haut du voisinage majoré de vingt à trente blocs.
     */
    @Accessor("skipSamplingAboveY")
    int lanterne$skipSamplingAboveY();

    /** Le sélecteur de fluide global — mer, lave, ou rien selon la dimension et l'altitude. */
    @Accessor("globalFluidPicker")
    Aquifer.FluidPicker lanterne$globalFluidPicker();
}
