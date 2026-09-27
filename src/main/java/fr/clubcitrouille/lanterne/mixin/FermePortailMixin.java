package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.core.Ferme;

/**
 * Pas de piglin zombifié né d'un portail dans une ferme ancrée.
 *
 * <p>{@code NetherPortalBlock.randomTick} fait naître des piglins zombifiés sur les portails de
 * l'Overworld dès qu'un joueur est à portée. Dans la Fosse des Poudres, 660 blocs de portail en
 * faisaient naître à chaque visite : autant de places du plafond de la ferme prises par des monstres
 * qui ne donnent pas de poudre. Dans un chunk « ferme », le tirage n'a simplement pas lieu.
 */
@Mixin(NetherPortalBlock.class)
public abstract class FermePortailMixin {
    @Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
    private void lanterne$pasDePiglin(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
            CallbackInfo callback) {
        if (Ferme.isFarm(level, ChunkPos.containing(pos))) {
            callback.cancel();
        }
    }
}
