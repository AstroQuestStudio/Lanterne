package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.core.Bulk;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'entonnoir qui transporte par lots.
 *
 * <p>Le raisonnement est dans {@link Bulk}. Ici, seules comptent les deux limites qu'on s'impose.
 *
 * <h2>On n'intercepte que le cas des conteneurs</h2>
 *
 * <p>{@code ejectItems} sert trois destinations : un conteneur ordinaire, une capacité d'objets de
 * NeoForge — par où passent les machines des mods — et rien du tout. Le module ne prend en charge que
 * la première, et rend la main à vanilla pour les deux autres.
 *
 * <p>Ce n'est pas de la prudence de façade. La voie des capacités est <b>transactionnelle</b> : elle
 * peut annuler un transfert après coup, et un lot annulé à moitié ne se rattrape pas avec un compteur
 * d'objets. Un module qui prétendrait servir Create et Mekanism sans comprendre leur protocole de
 * transaction ne serait pas une optimisation, ce serait une perte d'objets.
 *
 * <h2>La recharge suit le lot, et c'est là que tout se joue</h2>
 *
 * <p>Déplacer seize objets sans payer seize recharges multiplierait le débit par seize. Le module ne
 * vaut donc que si la recharge suit — {@code setCooldown(8)} devient {@code setCooldown(8 × tours)} —
 * et c'est la seule ligne dont dépend l'exactitude du débit.
 *
 * <p>Depuis {@link Bulk#cooldownFor}, cette ligne divise EN PLUS par {@link Settings#bulkSpeed()} :
 * le débit n'est plus « exactement celui de vanilla » par défaut, et c'est voulu — voir la Javadoc de
 * {@code cooldownFor} pour pourquoi, et {@code Config#BULK_SPEED} pour le réglage.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBulkMixin {
    /**
     * Remet le relevé du tick à zéro.
     *
     * <p>La ceinture, en plus des bretelles : {@link Bulk#owed()} consomme déjà son relevé. Mais un
     * entonnoir peut sortir de {@code tryMoveItems} sans atteindre la recharge — une destination
     * pleine, un signal de redstone — et laisser un compte derrière lui.
     */
    @Inject(method = "pushItemsTick", at = @At("HEAD"))
    private static void lanterne$openTheLedger(Level level, BlockPos pos, BlockState state,
                                               HopperBlockEntity entity, CallbackInfo callback) {
        if (Settings.bulk()) {
            Bulk.beginTick();
        }
    }

    /**
     * La sortie vers l'avant, par lots.
     *
     * <p>La recherche du conteneur a lieu <b>une fois</b>, ici, au lieu d'une fois par objet. C'est
     * tout le module : le déplacement lui-même n'a jamais rien coûté.
     */
    @Inject(method = "ejectItems", at = @At("HEAD"), cancellable = true)
    private static void lanterne$ejectInBulk(Level level, BlockPos pos, HopperBlockEntity self,
                                             CallbackInfoReturnable<Boolean> callback) {
        if (!Settings.bulk()) {
            return;
        }
        Direction facing = self.getBlockState().getValue(HopperBlock.FACING);
        var found = HopperBlockEntity.getContainerOrHandlerAt(level, pos.relative(facing),
                facing.getOpposite());
        Container into = found.container();
        if (into == null) {
            // Capacité d'objets, ou rien : voir l'en-tête. Vanilla reprend la main entière.
            return;
        }
        Direction side = facing.getOpposite();
        if (Bulk.isFull(into, side)) {
            callback.setReturnValue(false);
            return;
        }
        callback.setReturnValue(Bulk.eject(self, into, side));
    }

    /**
     * L'aspiration depuis le dessus, par lots.
     *
     * <p>Vanilla appelle cette méthode une fois par emplacement du conteneur source, et s'arrête au
     * premier succès. On garde exactement ce parcours — même ordre d'emplacements, même arrêt — et
     * l'on prend seize objets là où il en prenait un.
     */
    @Inject(method = "tryTakeInItemFromSlot", at = @At("HEAD"), cancellable = true)
    private static void lanterne$takeInBulk(Hopper hopper, Container from, int slot, Direction side,
                                            CallbackInfoReturnable<Boolean> callback) {
        if (!Settings.bulk()) {
            return;
        }
        callback.setReturnValue(Bulk.suck(hopper, from, slot, side));
    }

    /**
     * La recharge, multipliée par le nombre de tours vanilla que le lot a remplacés.
     *
     * <p>Sans cette ligne, le module serait une duplication de débit et non une optimisation. C'est
     * elle qui rend le transfert par lots <b>exactement</b> équivalent à vanilla en moyenne, et c'est
     * elle qu'une épreuve de débit doit vérifier.
     *
     * <h2>{@code @Redirect}, et non {@code @WrapOperation}</h2>
     *
     * <p>Appelé à chaque transfert d'entonnoir réussi — un chemin chaud du tick serveur, pas un
     * évènement rare (chargement, configuration) : voir le profil réel qui a motivé la conversion du
     * même défaut sur {@code EntityCullMixin} ({@code git log} de ce fichier). Cette substitution ne
     * fait jamais qu'appeler l'original UNE fois, avec un second argument recalculé — exactement le
     * genre de site que {@code @Redirect} couvre sans passer par {@code Operation<Void>.call(Object...)}
     * (tableau alloué, boîtage, {@code invokedynamic}) : {@code @Redirect} le remplace par un appel
     * statique typé direct, comportement identique bit à bit.
     */
    @Redirect(method = "tryMoveItems",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;setCooldown(I)V"))
    private static void lanterne$payForTheLot(HopperBlockEntity entity, int ticks) {
        // Bulk.cooldownFor applique le multiplicateur de Settings#bulkSpeed en plus du paiement du
        // lot — voir sa Javadoc pour pourquoi ce n'est plus, a partir d'ici, un module a debit
        // inchange.
        entity.setCooldown(Settings.bulk() ? Bulk.cooldownFor(ticks) : ticks);
    }
}
