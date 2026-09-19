package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import fr.clubcitrouille.lanterne.core.Leurre;

/**
 * La seule prise d'invalidation dont l'anti x-ray a besoin : un changement de bloc.
 *
 * <p>Contrairement à {@code Emballage} (voir {@code EmballageChunkMixin}, cinq prises), ce module ne
 * cache que la donnée de BLOC — ni la lumière, ni les entités de bloc, ni les biomes n'entrent dans
 * le critère d'exposition ({@link BlockState#isSolidRender()}). Une seule prise suffit donc : {@code
 * LevelChunk.setBlockState(BlockPos, BlockState, int)}, en tête, avant que quoi que ce soit ne
 * change — même précaution qu'{@code EmballageChunkMixin} contre la sortie anticipée documentée dans
 * sa propre Javadoc (une pose annulée par la mise à jour des voisins ne doit pas laisser un cache
 * périmé, et agir en tête le garantit sans avoir à relire ce flot de contrôle).
 *
 * <p>Le raisonnement complet — pourquoi une section, et pourquoi sa voisine directe seulement —
 * vit dans {@link Leurre#invalidate}.
 */
@Mixin(LevelChunk.class)
public abstract class LeurreInvalidationMixin {
    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;I)"
                    + "Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"))
    private void lanterne$invalidateVeil(BlockPos position, BlockState etat, int drapeaux,
            CallbackInfoReturnable<BlockState> callback) {
        Leurre.invalidate((LevelChunk) (Object) this, position);
    }
}
