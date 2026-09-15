package fr.clubcitrouille.lanterne.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;

import fr.clubcitrouille.lanterne.lab.Filon;

/**
 * Le chronomètre fin de l'étape du bruit, posé sur les trois boucles de {@code NoiseChunk}.
 *
 * <p>Voir {@link Filon} pour ce que cette ventilation cherche à établir, et pour la raison de ne
 * mesurer que <b>deux</b> des trois boucles en déduisant la troisième par soustraction.
 *
 * <p>Ce mixin ne change rien au calcul : il lit l'horloge. {@code LANTERNE_FILON} absent,
 * {@code Filon.ON} est une constante fausse et le corps de ces méthodes disparaît à la compilation
 * à la volée.
 *
 * <h2>Pourquoi le décalque est mesuré ici et non dans {@code NoiseCalqueMixin}</h2>
 *
 * <p>Le constructeur de {@code NoiseChunk} décalque deux arbres : le routeur entier
 * ({@code NoiseRouter.mapAll}, ligne 143) puis la densité finale ({@code DensityFunction.mapAll},
 * ligne 159). Le premier appel est déjà détourné par {@code NoiseCalqueMixin} — deux détournements
 * sur la même instruction se disputeraient la place. Le second ne l'est pas, et c'est celui qu'on
 * prend ici ; {@code NoiseCalqueMixin} verse le premier dans le même poste.
 *
 * <h2>Le poste « climat » vaut zéro quand le calque fonctionne, et c'est exact</h2>
 *
 * <p>{@code NoiseCalqueMixin} annule {@code cachedClimateSampler} depuis sa tête. Un injecteur posé
 * sur le retour ne s'exécute alors jamais, et le poste {@code CLIMAT} affiche zéro passage. Ce n'est
 * pas une mesure manquée : c'est la mesure que le module a supprimé ce travail. Avec
 * {@code LANTERNE_MODULES} sans {@code calque}, le même poste montre ce que vanilla y dépense.
 */
@Mixin(NoiseChunk.class)
public abstract class FilonNoiseMixin {
    @Inject(method = "fillSlice", at = @At("HEAD"))
    private void lanterne$sliceIn(boolean slice0, int cellX, CallbackInfo callback) {
        Filon.enter(Filon.TRANCHES);
    }

    @Inject(method = "fillSlice", at = @At("RETURN"))
    private void lanterne$sliceOut(boolean slice0, int cellX, CallbackInfo callback) {
        Filon.exit(Filon.TRANCHES);
    }

    @Inject(method = "selectCellYZ", at = @At("HEAD"))
    private void lanterne$cellIn(int cellYIndex, int cellZIndex, CallbackInfo callback) {
        Filon.enter(Filon.CELLULES);
    }

    @Inject(method = "selectCellYZ", at = @At("RETURN"))
    private void lanterne$cellOut(int cellYIndex, int cellZIndex, CallbackInfo callback) {
        Filon.exit(Filon.CELLULES);
    }

    @Inject(method = "cachedClimateSampler", at = @At("HEAD"))
    private void lanterne$climateIn(NoiseRouter noises, List<Climate.ParameterPoint> spawnTarget,
            CallbackInfoReturnable<Climate.Sampler> callback) {
        Filon.enter(Filon.CLIMAT);
    }

    @Inject(method = "cachedClimateSampler", at = @At("RETURN"))
    private void lanterne$climateOut(NoiseRouter noises, List<Climate.ParameterPoint> spawnTarget,
            CallbackInfoReturnable<Climate.Sampler> callback) {
        Filon.exit(Filon.CLIMAT);
    }

    /**
     * Le second décalque du constructeur, celui que le module {@code calque} ne supprime pas.
     *
     * <p>C'est {@code DensityFunctions.cacheAllInCell(add(finalDensity, beardifier)).mapAll(wrap)}
     * de la ligne 159. Il n'y a qu'un seul appel à {@code DensityFunction.mapAll} dans ce
     * constructeur, la cible est donc sans ambiguïté.
     */
    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/DensityFunction;mapAll("
                            + "Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)"
                            + "Lnet/minecraft/world/level/levelgen/DensityFunction;"))
    private DensityFunction lanterne$timeSecondTrace(DensityFunction function,
            DensityFunction.Visitor visitor) {
        if (!Filon.ON) {
            return function.mapAll(visitor);
        }
        long started = System.nanoTime();
        DensityFunction traced = function.mapAll(visitor);
        Filon.add(Filon.DECALQUE, System.nanoTime() - started);
        return traced;
    }
}
