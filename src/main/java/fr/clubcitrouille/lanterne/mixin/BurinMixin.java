package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;

import fr.clubcitrouille.lanterne.core.Burin;

/**
 * Les deux refus que le réseau provoque, et qui sont réparables.
 *
 * <p>Tout le raisonnement est dans {@link Burin} : ce fichier ne fait que le poser aux deux endroits
 * de {@code ServerPlayerGameMode} où vanilla décide. On n'y met donc aucune règle, seulement les
 * deux prises.
 *
 * <h2>La première prise : la constante 1,0 de la portée</h2>
 *
 * <p>Vanilla écrit {@code this.player.isWithinBlockInteractionRange(pos, 1.0)}. On redirige l'appel
 * plutôt que de modifier la constante : le redirect porte la signature exacte de la méthode visée,
 * que {@code tools/verifie_mixins.py} sait vérifier, alors qu'un {@code @ModifyConstant} ne se
 * vérifie qu'à l'exécution — et le jour où Mojang ajoute un second nombre à virgule dans cette
 * méthode, il s'appliquerait silencieusement aux deux.
 *
 * <h2>La seconde prise : la place unique du rattrapage</h2>
 *
 * <p>On se place en <b>tête</b> de {@code handleBlockBreakAction}, avant que vanilla n'ait rien
 * décidé. Si une action de cassage arrive sur une case alors que le serveur en retient une
 * <b>autre</b> en destruction différée, on rend la place. Vanilla trouvera donc son
 * {@code hasDelayedDestroy} libre, et son propre {@code if (!this.hasDelayedDestroy)} fera le reste
 * — on n'a rien réécrit de sa logique, on a seulement cessé de lui laisser un reste à la main.
 *
 * <p>Rendre la place n'abandonne jamais une case qui aurait dû tomber : une case dont la
 * progression avait atteint un aurait déjà été détruite par le {@code tick()} du tour précédent.
 * Celle qu'on relâche est, par construction, une case que le joueur a quittée avant qu'elle ne soit
 * mûre — et que vanilla aurait fait tomber dans son dos.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class BurinMixin {

    @Shadow
    private boolean hasDelayedDestroy;

    @Shadow
    private BlockPos delayedDestroyPos;

    /**
     * La marge de portée, à la place de la constante de vanilla.
     *
     * <p>Le paramètre {@code vanillaBuffer} est la constante {@code 1.0} qu'on remplace. On ne le
     * lit pas : {@link Burin#VANILLA_BUFFER} porte la même valeur et la documente. Il est là parce
     * que la signature du redirect l'impose.
     */
    @Redirect(
            method = "handleBlockBreakAction",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;isWithinBlockInteractionRange(Lnet/minecraft/core/BlockPos;D)Z"))
    private boolean lanterne$reachWithLatency(ServerPlayer player, BlockPos pos, double vanillaBuffer) {
        return player.isWithinBlockInteractionRange(pos, Burin.buffer(player));
    }

    @Inject(method = "handleBlockBreakAction", at = @At("HEAD"))
    private void lanterne$freeDelayedSlot(BlockPos pos, ServerboundPlayerActionPacket.Action action,
                                          Direction direction, int maxY, int sequence,
                                          CallbackInfo callback) {
        if (!this.hasDelayedDestroy || this.delayedDestroyPos == null
                || this.delayedDestroyPos.equals(pos)) {
            return;
        }
        // L'abandon de cassage ne déplace personne : le joueur lâche son bouton, il ne se met pas à
        // travailler ailleurs. Lui rendre la place reviendrait à vider le filet chaque fois que le
        // réticule quitte un bloc une fraction de seconde — c'est-à-dire tout le temps.
        if (action == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) {
            return;
        }
        if (Burin.shouldHandOver()) {
            this.hasDelayedDestroy = false;
        }
    }
}
