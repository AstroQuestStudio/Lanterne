package fr.clubcitrouille.lanterne.mixin;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import fr.clubcitrouille.lanterne.core.Colonne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le cache de colonnes, posé à l'endroit exact où le bruit se calcule.
 *
 * <p>{@code fillSlice} remplit, pour une colonne de cellules donnée, un tableau par interpolateur.
 * C'est l'unique endroit où le bruit interpolé du terrain est évalué — tout le reste n'est que de
 * l'interpolation entre ces valeurs-là. Intercepter ici, c'est donc intercepter tout, et une seule
 * fois.
 *
 * <p>Voir {@link Colonne} pour le compte (36 % du bruit interpolé recalculé entre chunks voisins) et
 * pour l'argument d'exactitude.
 *
 * <h2>Pourquoi le rang, et pourquoi il est stable</h2>
 *
 * <p>Un {@code NoiseChunk} porte plusieurs interpolateurs — un par fonction marquée
 * {@code interpolated} dans le routeur. Deux chunks du même monde les construisent dans le
 * <b>même ordre</b>, parce que {@code mapAll} visite l'arbre dans un ordre déterministe. Le rang
 * dans la liste est donc une désignation stable de « quelle fonction », sans avoir à comparer des
 * arbres.
 *
 * <p>La recherche est linéaire sur une liste de quelques éléments, une fois par colonne et par
 * fonction — c'est-à-dire une poignée de comparaisons de références en regard d'une évaluation de
 * bruit qui coûte des dizaines de microsecondes.
 */
@Mixin(NoiseChunk.class)
public abstract class ColonneMixin {
    @Shadow @Final private List<NoiseChunk.NoiseInterpolator> interpolators;
    @Shadow @Final private int cellWidth;
    @Shadow private int cellStartBlockX;
    @Shadow private int cellStartBlockZ;

    /**
     * Le rang du monde auquel ce {@code NoiseChunk} appartient, ou -1 s'il est inconnu.
     *
     * <p>Relevé une fois à la construction. Sans lui, la cellule (12, 7) du Nether se confondrait
     * avec celle du surmonde — et le terrain sortirait par plaques d'une autre dimension.
     */
    @Unique
    private int lanterne$world = -1;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void lanterne$noteWorld(int cellCountXZ, RandomState randomState, int firstBlockX,
            int firstBlockZ, NoiseSettings noiseSettings, DensityFunctions.BeardifierOrMarker beardifier,
            NoiseGeneratorSettings generatorSettings, Aquifer.FluidPicker fluidPicker, Blender blender,
            CallbackInfo callback) {
        // L'etat aleatoire identifie le bruit : un par monde et par graine. C'est exactement la
        // granularite qu'il faut — deux mondes de meme graine mais de dimensions differentes en ont
        // deux distincts.
        this.lanterne$world = Colonne.ownerOf(randomState);
    }

    @WrapOperation(
            method = "fillSlice",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;"
                            + "fillArray([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V"))
    private void lanterne$reuseColumn(NoiseChunk.NoiseInterpolator filler, double[] out,
            DensityFunction.ContextProvider provider, Operation<Void> original) {
        if (!Settings.colonne() || this.lanterne$world < 0) {
            original.call(filler, out, provider);
            return;
        }

        int rank = this.interpolators.indexOf(filler);
        if (rank < 0 || rank > 255) {
            // Plus de deux cent cinquante-six fonctions interpolees : la cle ne saurait plus les
            // distinguer. On ne sert pas, plutot que de servir la mauvaise.
            original.call(filler, out, provider);
            return;
        }

        int cellX = Math.floorDiv(this.cellStartBlockX, this.cellWidth);
        int cellZ = Math.floorDiv(this.cellStartBlockZ, this.cellWidth);
        long key = Colonne.key(this.lanterne$world, cellX, cellZ, rank);

        double[] kept = Colonne.serve(key, out.length);
        if (kept != null) {
            System.arraycopy(kept, 0, out, 0, out.length);
            if (Colonne.audit()) {
                // On recalcule ce qu'on vient de servir et on compare TOUT le tableau. C'est cher,
                // et c'est le but : cette epreuve ne tourne que lorsqu'on la demande.
                double[] check = new double[out.length];
                original.call(filler, check, provider);
                Colonne.confront(key, out, check);
            }
            return;
        }

        original.call(filler, out, provider);
        Colonne.keep(key, out);
    }
}
