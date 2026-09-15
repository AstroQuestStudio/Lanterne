package fr.clubcitrouille.lanterne.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;

import fr.clubcitrouille.lanterne.core.Remblai;

/**
 * Sept objets pour prévenir six blocs qui ne veulent rien savoir.
 *
 * <h2>Où cet appel arrive, et combien de fois</h2>
 *
 * <p>{@code FillCommand} fait très bien son travail : il ne passe pas {@code UPDATE_NEIGHBORS}
 * pendant sa boucle, et reporte toutes les notifications après la dernière pose —
 *
 * <pre>
 * for (UpdatedPosition pos : updatePositions) {
 *     level.updateNeighboursOnBlockSet(pos.pos, pos.oldState);
 * }
 * </pre>
 *
 * <p>— mais cela reste <b>une notification par bloc modifié</b>, et
 * {@code ServerLevel.updateNeighboursOnBlockSet} appelle {@code updateNeighborsAt}, qui construit un
 * {@code MultiNeighborUpdate} puis, à chaque tour de {@code runNext}, une {@code BlockPos} neuve par
 * {@code sourcePos.relative(direction)}. Sept objets par bloc, et six répartitions virtuelles
 * jusqu'à {@code state.handleNeighborChanged(…)} → {@code getBlock().neighborChanged(…)}.
 *
 * <p>C'est aussi le chemin de toute casse ordinaire : {@code destroyBlock} pose {@code UPDATE_ALL},
 * donc {@code markAndNotifyBlock} appelle {@code updateNeighborsAt} directement. Un joueur qui mine
 * vite y passe autant qu'un {@code /fill}, un bloc à la fois.
 *
 * <h2>Ce qu'on prouve avant de couper</h2>
 *
 * <p>{@code BlockBehaviour.neighborChanged} a un corps <b>vide</b>, et {@code Block} ne la redéfinit
 * pas. Si aucun des six voisins n'appartient à une classe qui la redéfinit, les six
 * {@code executeUpdate} sont vides, donc la mise à jour multiple entière est vide.
 *
 * <p>La condition porte sur les six et pas sur un seul : la boucle de {@code MultiNeighborUpdate}
 * les parcourt tous, il suffit d'un voisin sensible pour qu'on laisse passer l'appel complet et que
 * l'ordre vanilla soit respecté à la lettre.
 *
 * <h2>L'événement de NeoForge n'est pas perdu</h2>
 *
 * <p>C'est le point à vérifier avant tout, parce qu'un mod d'optimisation qui rend muets les
 * événements des autres mods est une panne. {@code NeighborNotifyEvent} est levé par la surcharge à
 * <b>deux</b> arguments :
 *
 * <pre>
 * // ServerLevel.updateNeighborsAt(BlockPos, Block)
 * EventHooks.onNeighborNotify(this, pos, this.getBlockState(pos), EnumSet.allOf(Direction.class), false).isCanceled();
 * this.updateNeighborsAt(pos, sourceBlock, ExperimentalRedstoneUtils.initialOrientation(this, null, null));
 * </pre>
 *
 * <p>On intercepte la surcharge à <b>trois</b> arguments, qui ne lève aucun événement — ni ici, ni
 * quand elle est appelée directement par la redstone. Rien n'est donc soustrait à personne.
 *
 * <h2>Le coût ajouté</h2>
 *
 * <p>Six {@code getBlockState}, qui sont <b>exactement</b> ceux que {@code MultiNeighborUpdate.runNext}
 * ferait. Dans le cas favorable on les fait et on s'arrête ; dans le cas défavorable — au moins un
 * voisin sensible — on les aura faits pour rien, mais la boucle s'arrête au <b>premier</b> voisin
 * sensible, ce qui borne la perte à une ou deux lectures dans un voisinage de redstone.
 *
 * <p>La position de travail est un champ de ce monde et non une allocation par appel : allouer ici
 * la position qu'on écrit ce module pour ne plus allouer serait ridicule. Le tick d'un monde est
 * mono-fil, et la boucle qui s'en sert n'appelle que {@code getBlockState} — rien qui puisse y
 * rentrer à nouveau.
 *
 * <p>La cible nomme {@code CollectingNeighborUpdater} et non l'interface {@code NeighborUpdater} :
 * {@code Level.neighborUpdater} est déclaré du type concret, donc le site d'appel est un
 * {@code invokevirtual} sur la classe. Viser l'interface aurait compilé et serait passé sous le nez
 * de {@code verifieMixins}, pour échouer au démarrage.
 */
@Mixin(ServerLevel.class)
public abstract class RemblaiVoisinsMixin {
    /** La position de travail de ce monde. Voir le Javadoc de classe sur l'absence de course. */
    @Unique
    private final BlockPos.MutableBlockPos lanterne$curseurRemblai = new BlockPos.MutableBlockPos();

    @Redirect(
            method = "updateNeighborsAt(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/Block;"
                    + "Lnet/minecraft/world/level/redstone/Orientation;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/redstone/CollectingNeighborUpdater;"
                            + "updateNeighborsAtExceptFromFacing"
                            + "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;"
                            + "Lnet/minecraft/core/Direction;"
                            + "Lnet/minecraft/world/level/redstone/Orientation;)V"))
    private void lanterne$voisinageSansEffet(CollectingNeighborUpdater notificateur, BlockPos pos,
                                             Block source, @Nullable Direction ignoree,
                                             @Nullable Orientation orientation) {
        ServerLevel monde = (ServerLevel) (Object) this;
        // La preuve ne vaut que si les six voisins sont examinés. Une direction ignorée — le cas des
        // pistons et des observateurs — laisserait un voisin non vérifié : on ne coupe pas.
        if (ignoree == null && Remblai.actif()
                && !Remblai.voisinagePeutAgir(monde, pos, this.lanterne$curseurRemblai)) {
            Remblai.noteVoisinage();
            return;
        }
        notificateur.updateNeighborsAtExceptFromFacing(pos, source, ignoree, orientation);
    }
}
