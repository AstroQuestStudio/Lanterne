package fr.clubcitrouille.lanterne.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;

import fr.clubcitrouille.lanterne.core.Calque;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le calque du routeur de bruit, gardé au lieu d'être refait six fois.
 *
 * <h2>Deux points d'accroche, et le premier ne fait que retenir</h2>
 *
 * <p>Le constructeur de {@code NoiseChunk} appelle {@code NoiseRouter.mapAll} une seule fois, range
 * le résultat dans une variable locale, en tire {@code preliminarySurfaceLevel} et le laisse partir.
 * On le retient — c'est tout ce que fait le premier détournement, et il ne change rien au calcul :
 * il rend exactement ce que vanilla allait recevoir.
 *
 * <p>Le second sert {@code cachedClimateSampler} depuis ce calque, au lieu de refaire six parcours
 * complets de l'arbre. Voir {@link Calque} pour la démonstration que les objets rendus sont
 * <b>les mêmes</b> — pas seulement équivalents — et pour ce que ce module ne fait pas.
 *
 * <h2>Le garde-fou, et pourquoi il est une comparaison de référence</h2>
 *
 * <p>{@code cachedClimateSampler} reçoit son routeur en argument. Vanilla lui passe toujours
 * {@code randomState.router()}, c'est-à-dire celui que le constructeur a décalqué — mais rien ne
 * l'impose, et un mod pourrait en passer un autre pour fabriquer un échantillonneur de climat
 * différent. On ne sert donc le calque que si les deux routeurs sont <b>le même objet</b>. Comparer
 * par égalité structurelle serait non seulement plus cher que le travail qu'on évite, mais faux :
 * deux routeurs égaux ne partagent pas les caches du calque.
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseCalqueMixin {
    /** Le routeur reçu par le constructeur, pour ne servir le calque qu'à lui. */
    @Unique
    private @Nullable NoiseRouter lanterne$source;

    /** Le routeur décalqué, que vanilla laissait partir. */
    @Unique
    private @Nullable NoiseRouter lanterne$traced;

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll("
                            + "Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)"
                            + "Lnet/minecraft/world/level/levelgen/NoiseRouter;"))
    private NoiseRouter lanterne$keep(NoiseRouter router, DensityFunction.Visitor visitor) {
        NoiseRouter traced = router.mapAll(visitor);
        this.lanterne$source = router;
        this.lanterne$traced = traced;
        return traced;
    }

    @Inject(method = "cachedClimateSampler", at = @At("HEAD"), cancellable = true)
    private void lanterne$reuse(NoiseRouter noises, List<Climate.ParameterPoint> spawnTarget,
            CallbackInfoReturnable<Climate.Sampler> callback) {
        if (!Settings.calque()) {
            return;
        }
        NoiseRouter traced = this.lanterne$traced;
        if (traced == null || noises != this.lanterne$source) {
            Calque.decline();
            return;
        }
        Calque.serve();
        if (Calque.broken()) {
            // LANTERNE_BREAK_CALQUE=1 : la température et la végétation sont échangées. Le calque est
            // toujours servi, et le monde engendré N'EST PLUS CELUI DE VANILLA — des déserts là où il
            // devait y avoir des forêts. Une épreuve qui ne peut pas échouer ne prouve rien, et la
            // panne à provoquer ici n'est pas une lenteur : c'est un terrain différent.
            callback.setReturnValue(new Climate.Sampler(
                    traced.vegetation(),
                    traced.temperature(),
                    traced.continents(),
                    traced.erosion(),
                    traced.depth(),
                    traced.ridges(),
                    spawnTarget));
            return;
        }
        callback.setReturnValue(new Climate.Sampler(
                traced.temperature(),
                traced.vegetation(),
                traced.continents(),
                traced.erosion(),
                traced.depth(),
                traced.ridges(),
                spawnTarget));
    }
}
