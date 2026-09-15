package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.SleepInBed;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.core.Digue;

/**
 * Le villageois qui ne fait plus charger le chunk de son lit pour savoir s'il peut dormir.
 *
 * <h2>L'ordre des opérations, et il est décisif</h2>
 *
 * <p>{@code SleepInBed.checkExtraStartConditions} finit ainsi :
 *
 * <pre>
 * BlockState blockState = level.getBlockState(target.pos());
 * return target.pos().closerToCenterThan(body.position(), 2.0)
 *     &amp;&amp; blockState.is(BlockTags.BEDS) &amp;&amp; !blockState.getValue(BedBlock.OCCUPIED);
 * </pre>
 *
 * <p>La lecture du bloc a lieu <b>avant</b> le test de proximité. Vérifié au désassemblage du jar
 * 26.2 : {@code ServerLevel.getBlockState} est à l'offset 102, {@code closerToCenterThan} à 119. Un
 * villageois qui a un lit à trois cents blocs — parce qu'il a été transporté, qu'il suit un joueur,
 * ou que son village s'étend — fait donc <b>charger le chunk de ce lit à chaque évaluation de son
 * comportement</b>, uniquement pour apprendre ensuite qu'il est trop loin pour s'y coucher.
 *
 * <p>Sur un village de six cents habitants c'est le genre de chemin qui transforme un tick en gel.
 *
 * <h2>Pourquoi rendre de l'air plutôt qu'annuler la méthode</h2>
 *
 * <p>{@code ServerCore} injecte et force la valeur de retour à faux. C'est équivalent, et cela
 * demande une variable locale nommée — {@code @Local(name = "target")} — c'est-à-dire une dépendance
 * au nom d'une variable dans le bytecode compilé. Rendre l'état d'un bloc absent est plus économe :
 * l'air n'est pas un lit, {@code is(BlockTags.BEDS)} rend faux, et le court-circuit de l'opérateur
 * {@code &amp;&amp;} empêche le {@code getValue(OCCUPIED)} qui aurait éclaté sur de l'air.
 *
 * <h2>Ce qu'on perd</h2>
 *
 * <p>Un villageois dont le lit est hors du chargé ne commence pas à dormir. Or pour dormir il doit
 * être à deux blocs de ce lit, donc dans le chunk du lit, donc dans un chunk chargé : <b>le refus ne
 * peut jamais l'empêcher de se coucher</b>. Il ne supprime que le travail fait pour répondre non.
 */
@Mixin(SleepInBed.class)
public abstract class SleepBedMixin {
    @Redirect(
            method = "checkExtraStartConditions",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getBlockState"
                            + "(Lnet/minecraft/core/BlockPos;)"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState lanterne$bedOnlyIfLoaded(ServerLevel level, BlockPos pos) {
        if (!Digue.guards(level) || Digue.present(level, pos)) {
            return level.getBlockState(pos);
        }
        Digue.noteBed();
        return Blocks.AIR.defaultBlockState();
    }
}
