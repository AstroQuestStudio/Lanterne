package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Les feuilles d'un arbre abattu tombent en quelques instants, et non en une minute.
 *
 * <h2>Ce que vanilla fait, et pourquoi c'est si lent</h2>
 *
 * <p>Une feuille détachée de son tronc passe à {@code DISTANCE = 7}. À partir de là, elle attend un
 * <b>tick aléatoire</b> : {@code isRandomlyTicking} devient vrai, et {@code randomTick} la fait
 * tomber. Or le tick aléatoire tire trois positions par section et par tick, sur quatre mille
 * quatre-vingt-seize — soit une espérance d'environ mille trois cent soixante ticks, plus d'une
 * minute, pour chaque feuille prise séparément.
 *
 * <p>C'est ce qui donne, après chaque arbre coupé, une couronne de feuillage suspendue dans le vide
 * qui s'effrite pendant plusieurs minutes. Et pendant tout ce temps, chacun de ces blocs reste
 * candidat au tick aléatoire : le coût n'est pas seulement visuel.
 *
 * <h2>Ce que ce module change, et ce qu'il ne change surtout pas</h2>
 *
 * <p>Il ne touche ni à {@code PERSISTENT} ni à {@code DISTANCE}. Une feuille <b>posée à la main</b>
 * reçoit {@code PERSISTENT = true} dès sa pose ({@code LeavesBlock.getStateForPlacement}, ligne
 * 180) : elle n'a jamais été concernée par la décomposition, ni avant ce module ni après. Un toit
 * de feuillage dans une construction ne risque rien — c'est une garantie de vanilla, pas une
 * précaution ajoutée ici.
 *
 * <p>Ce qui change est le <b>calendrier</b>. Au lieu d'attendre le hasard, une feuille qui vient de
 * passer à la distance sept se replanifie un tick à brève échéance, et tombe à ce tick-là. La
 * cascade reste progressive — les feuilles proches du tronc se détachent avant les autres, parce
 * que leur distance est recalculée d'abord — mais elle se compte en secondes.
 *
 * <h2>Pourquoi deux points d'accroche et non un</h2>
 *
 * <p>{@code tick} est appelé pour <em>recalculer la distance</em>, pas pour décomposer. Au premier
 * passage, la feuille détachée y apprend qu'elle est à sept ; elle ne peut pas tomber dans le même
 * appel, sous peine de ne jamais laisser vanilla propager la distance à ses voisines, et donc de
 * casser la cascade.
 *
 * <p>On replanifie donc à la sortie, et l'on décompose à l'entrée du tick suivant — quand l'état
 * <em>reçu</em> est déjà à sept. Ce second passage est aussi ce qui rend le module sûr : entre les
 * deux, un joueur a pu poser une bûche à côté, la distance a été recalculée, et la feuille n'est
 * plus condamnée.
 */
@Mixin(LeavesBlock.class)
public abstract class LeafDecayMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void lanterne$fall(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
            CallbackInfo callback) {
        if (!Settings.decay() || !lanterne$doomed(state)) {
            return;
        }
        // L'état reçu est déjà condamné : c'est notre second passage, celui qui exécute.
        Block.dropResources(state, level, pos);
        level.removeBlock(pos, false);
        callback.cancel();
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void lanterne$schedule(BlockState state, ServerLevel level, BlockPos pos,
            RandomSource random, CallbackInfo callback) {
        if (!Settings.decay()) {
            return;
        }
        // Vanilla vient d'écrire la distance recalculée : on relit le monde plutôt que l'argument.
        BlockState settled = level.getBlockState(pos);
        if (lanterne$doomed(settled)) {
            level.scheduleTick(pos, settled.getBlock(), Settings.decayDelay());
        }
    }

    private static boolean lanterne$doomed(BlockState state) {
        return state.hasProperty(LeavesBlock.DISTANCE)
                && state.hasProperty(LeavesBlock.PERSISTENT)
                && state.getValue(LeavesBlock.DISTANCE) == LeavesBlock.DECAY_DISTANCE
                && !state.getValue(LeavesBlock.PERSISTENT);
    }
}
