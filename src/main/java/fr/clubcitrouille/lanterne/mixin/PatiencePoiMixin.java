package fr.clubcitrouille.lanterne.mixin;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Stream;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import net.minecraft.world.entity.ai.village.poi.PoiType;

import fr.clubcitrouille.lanterne.core.PatiencePoi;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * La recherche de {@code AcquirePoi} sautée pendant l'attente de {@link PatiencePoi}. Le flux de
 * {@code findAllClosestFirstWithType} est paresseux : tant que {@code collect} n'est pas appelé, rien
 * n'est balayé. On rend alors l'ensemble vide que vanilla aurait obtenu, et le reste du comportement
 * (« aucun chemin », puis la prochaine échéance vanilla) se déroule tel quel.
 */
@Mixin(AcquirePoi.class)
public abstract class PatiencePoiMixin {
    @WrapOperation(method = "lambda$create$3", at = @At(value = "INVOKE",
            target = "Ljava/util/stream/Stream;collect(Ljava/util/stream/Collector;)Ljava/lang/Object;"))
    private static Object lanterne$patience(Stream<?> stream, Collector<?, ?, ?> collector, Operation<Object> original,
            @Local(argsOnly = true) PathfinderMob body, @Local(argsOnly = true) Predicate<Holder<PoiType>> poiType,
            @Local(argsOnly = true) long timestamp) {
        if (!Settings.poi()) {
            return original.call(stream, collector);
        }
        if (PatiencePoi.attendre(body, poiType, timestamp)) {
            return new HashSet<>();
        }
        Object result = original.call(stream, collector);
        PatiencePoi.resultat(body, poiType, timestamp, ((Set<?>) result).isEmpty());
        return result;
    }
}
