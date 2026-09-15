package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.core.Digue;

/**
 * Le second chargement de l'abeille, celui que {@code ServerCore} laisse passer.
 *
 * <h2>Le point d'accroche que le mod d'origine n'a pas vu</h2>
 *
 * <p>{@code ServerCore} protège {@code getBeehiveBlockEntity}, évalué une fois sur vingt ticks. Mais
 * {@code Bee$BeeGoToHiveGoal.canBeeUse()} finit par ceci (ligne 801 de {@code Bee.java}) :
 *
 * <pre>
 * &amp;&amp; Bee.this.level().getBlockState(Bee.this.hivePos).is(BlockTags.BEEHIVES);
 * </pre>
 *
 * <p>{@code Level.getBlockState} passe par {@code getChunkAt}, donc par le chargement bloquant — et
 * un but de créature est réévalué <b>plusieurs fois par seconde</b>, pas une fois par seconde. Ce
 * chemin-là est donc le plus chaud des deux, et protéger l'autre sans celui-ci revient à fermer une
 * porte sur deux.
 *
 * <p>(Vérifié au désassemblage du jar 26.2 : {@code invokevirtual Level.getBlockState} à l'offset 75
 * de {@code canBeeUse}, un seul site d'appel.)
 *
 * <h2>Ce qu'on perd, et pourquoi ce n'est rien</h2>
 *
 * <p>L'abeille ne <em>démarre pas</em> son voyage de retour tant que le chunk de la ruche est absent.
 * Elle continue de butiner, et repart dès qu'il revient. Surtout, le refus ne touche à rien de
 * persistant : les deux méthodes qui effacent {@code hivePos} — {@code dropHive} et
 * {@code dropAndBlacklistHive} — sont dans {@code tick()}, donc <b>après</b> ce test, et un but qui
 * ne démarre pas ne les atteint jamais.
 */
@Mixin(Bee.BeeGoToHiveGoal.class)
public abstract class BeeHomingMixin {
    @Redirect(
            method = "canBeeUse",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockState"
                            + "(Lnet/minecraft/core/BlockPos;)"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState lanterne$homeOnlyIfLoaded(Level level, BlockPos pos) {
        if (!Digue.guards(level) || Digue.present(level, pos)) {
            return level.getBlockState(pos);
        }
        Digue.noteBee();
        return Blocks.AIR.defaultBlockState();
    }
}
