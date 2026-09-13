package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import fr.clubcitrouille.lanterne.core.Churn;

/**
 * Le seul endroit par lequel un bloc change.
 *
 * <h2>Pourquoi ici et pas ailleurs</h2>
 *
 * <p>Il existe une demi-douzaine de façons d'appeler une pose de bloc — {@code Level.setBlock},
 * {@code setBlockAndUpdate}, les pistons, les explosions, la croissance des plantes, les commandes.
 * Les intercepter une par une serait long et, surtout, incomplet : il suffirait qu'un mod tiers passe
 * par un chemin oublié pour qu'un objet reste endormi sous un bloc qui a disparu.
 *
 * <p>Toutes finissent par {@code LevelChunk.setBlockState}. C'est le goulot, et c'est la seule
 * garantie de complétude qu'on puisse obtenir sans faire confiance à personne.
 *
 * <h2>Ce que l'on fait ici, et ce qu'on se garde d'y faire</h2>
 *
 * <p>Une addition dans une table d'entiers. Rien d'autre — pas de lecture d'état, pas de recherche
 * d'entités, aucune allocation. Cette méthode est appelée à chaque bloc d'une explosion et à chaque
 * pas d'un générateur de terrain ; tout ce qu'on y ajoute est payé des centaines de milliers de fois.
 *
 * <p>On s'accroche en <b>tête</b> et non en sortie : la pose peut échouer et rendre {@code null}, et
 * dans ce cas… on aura compté un changement qui n'a pas eu lieu. C'est délibéré. Compter en trop
 * réveille un dormeur pour rien ; compter en moins le laisse dormir sous un bloc disparu. Des deux
 * erreurs possibles, une seule est visible par le joueur, et ce n'est pas celle-là.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void lanterne$noteChange(BlockPos pos, BlockState state, int flags,
                                     CallbackInfoReturnable<BlockState> callback) {
        Churn.touched(pos);
    }
}
