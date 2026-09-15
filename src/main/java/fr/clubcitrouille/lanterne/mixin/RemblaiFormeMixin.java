package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;

import fr.clubcitrouille.lanterne.core.Remblai;

/**
 * Six recalculs de forme par bloc posé, dont la plupart ne changent rien — et qui allouent.
 *
 * <h2>Le chemin, en entier</h2>
 *
 * <p>{@code Level.markAndNotifyBlock} appelle {@code blockState.updateNeighbourShapes(…)} dès lors
 * que {@code UPDATE_KNOWN_SHAPE} n'est pas posé — ce qui est le cas de {@code /fill}, de
 * {@code /clone}, de {@code /setblock} et de toute casse ordinaire. Cette méthode fait six appels,
 * un par direction, qui aboutissent tous ici :
 *
 * <pre>
 * // Level.neighborShapeChanged
 * this.neighborUpdater.shapeUpdate(direction, neighborState, pos, neighborPos, updateFlags, updateLimit);
 * </pre>
 *
 * <p>Et {@code CollectingNeighborUpdater.shapeUpdate} commence par allouer :
 *
 * <pre>
 * this.addAndRun(pos, new ShapeUpdate(direction, neighborState, pos.immutable(), neighborPos.immutable(), …));
 * </pre>
 *
 * <p>{@code pos} arrive d'une {@code MutableBlockPos} — celle que {@code updateNeighbourShapes}
 * déplace de direction en direction — donc {@code immutable()} en fabrique une copie neuve. Deux
 * objets par direction, douze par bloc posé. Suit l'empilement dans la pile du notificateur, son
 * dépilement, puis enfin le travail :
 *
 * <pre>
 * // NeighborUpdater.executeShapeUpdate
 * BlockState currentState = level.getBlockState(pos);
 * BlockState newState = currentState.updateShape(…);
 * Block.updateOrDestroy(currentState, newState, level, pos, …);
 * </pre>
 *
 * <h2>Ce qu'on prouve avant de couper</h2>
 *
 * <p>{@code Block.updateOrDestroy} a pour première ligne {@code if (newState != blockState)}. Si
 * {@code updateShape} rend l'état qu'on lui donne, la méthode entière ne fait rien. Or
 * l'implémentation de {@code BlockBehaviour.updateShape} est exactement {@code return state;}, et
 * {@code Block} ne la redéfinit pas.
 *
 * <p>Donc : <b>si la classe du bloc en {@code pos} ne redéfinit pas {@code updateShape}, l'appel
 * entier est vide.</b> C'est une propriété de la classe du bloc, pas de la situation : elle ne
 * dépend ni de l'ordre des poses, ni de l'état des autres blocs, ni du moment. Rien n'est différé ;
 * on refuse d'entrer dans un corps dont on a prouvé qu'il est vide.
 *
 * <p>Au passage, le cas litigieux de vanilla est couvert sans rien faire de particulier : le garde
 * {@code (updateFlags & 128) == 0 || !currentState.is(Blocks.REDSTONE_WIRE)} de
 * {@code executeShapeUpdate} ne concerne que la poudre de redstone, dont le bloc redéfinit
 * {@code updateShape} — elle n'est donc jamais coupée ici.
 *
 * <h2>Le coût ajouté, et pourquoi il est nul</h2>
 *
 * <p>Ce détournement lit {@code level.getBlockState(pos)}. C'est <b>la même lecture</b> que
 * {@code executeShapeUpdate} ferait deux allocations et un aller-retour de pile plus loin. On ne
 * lit donc pas une fois de plus : on lit plus tôt, et on s'arrête si cela suffit.
 *
 * <p>Un {@code @Redirect} plutôt qu'un {@code @Inject} : sur un chemin parcouru six fois par bloc
 * posé, l'objet {@code CallbackInfo} qu'un {@code @Inject} annulable alloue à chaque passage
 * <em>remplacerait</em> une partie des allocations qu'on vient supprimer.
 *
 * <p>La cible nomme {@code CollectingNeighborUpdater} et non l'interface {@code NeighborUpdater},
 * parce que le champ {@code Level.neighborUpdater} est déclaré du type <b>concret</b> — le site
 * d'appel est donc un {@code invokevirtual} sur la classe, pas un {@code invokeinterface}. Viser
 * l'interface compilerait, passerait {@code verifieMixins} — qui vérifie que la méthode existe dans
 * la classe nommée, pas que le site d'appel la nomme — et échouerait au démarrage.
 */
@Mixin(Level.class)
public abstract class RemblaiFormeMixin {
    @Redirect(
            method = "neighborShapeChanged",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/redstone/CollectingNeighborUpdater;shapeUpdate"
                            + "(Lnet/minecraft/core/Direction;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;II)V"))
    private void lanterne$formeSansEffet(CollectingNeighborUpdater notificateur, Direction direction,
                                         BlockState etatVoisin, BlockPos pos, BlockPos posVoisin,
                                         int drapeaux, int plafond) {
        Level monde = (Level) (Object) this;
        if (Remblai.actif() && !Remblai.formePeutAgir(monde, pos)) {
            Remblai.noteForme();
            return;
        }
        notificateur.shapeUpdate(direction, etatVoisin, pos, posVoisin, drapeaux, plafond);
    }
}
