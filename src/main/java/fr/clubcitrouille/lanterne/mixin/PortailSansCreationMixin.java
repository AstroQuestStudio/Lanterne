package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.TeleportTransition;

/**
 * Seul un joueur crée un portail du Nether.
 *
 * <p>Quand aucun portail n'existe à l'arrivée, vanilla en construit un — pour n'importe quelle
 * entité, un creeper compris. Une ferme à portails dont l'arrivée est absente un instant (travaux,
 * chunk pas encore chargé) sème ainsi des portails dans le Nether, au plus près de la hauteur de
 * départ : banc du 27/09, trois portails parasites en y 50 à côté du puits, qui volaient ensuite une
 * partie des creepers — et qui pourraient aussi bien s'ouvrir à côté d'une base. Ici, sans portail à
 * l'arrivée, une entité qui n'est pas un joueur ne voyage simplement pas.
 */
@Mixin(NetherPortalBlock.class)
public abstract class PortailSansCreationMixin {
    @Inject(method = "getExitPortal", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/portal/PortalForcer;createPortal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction$Axis;)Ljava/util/Optional;"),
            cancellable = true)
    private void lanterne$seulUnJoueur(ServerLevel newLevel, Entity entity, BlockPos portalEntryPos,
            BlockPos approximateExitPos, boolean toNether, WorldBorder worldBorder,
            CallbackInfoReturnable<TeleportTransition> callback) {
        if (!(entity instanceof Player)) {
            callback.setReturnValue(null);
        }
    }
}
