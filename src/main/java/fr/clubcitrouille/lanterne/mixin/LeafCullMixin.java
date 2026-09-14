package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.client.Foliage;

/**
 * Les faces mitoyennes de deux feuilles, retirées du maillage.
 *
 * <p>Le raisonnement et le réglage à trois états sont dans {@link Foliage}.
 *
 * <h2>Pourquoi une injection et non une redéfinition</h2>
 *
 * <p>{@code skipRendering} est déclarée par {@code BlockBehaviour} et rend faux pour presque tout.
 * La redéfinir entièrement — ce que fait le mod de référence — reviendrait à décider seul du sort de
 * <b>toutes</b> les faces de feuilles, y compris celles que le jeu ou un autre mod aurait de bonnes
 * raisons de masquer déjà, par exemple contre un bloc plein.
 *
 * <p>Une injection annulable n'ajoute que le cas manquant : si le voisin est une feuille et que le
 * masquage est actif, on masque. Dans tous les autres cas, la méthode d'origine reprend la main
 * intacte. C'est aussi ce qui rend l'interrupteur exact — éteint, ce mixin ne change rien du tout.
 *
 * <h2>Ce mixin est client, sur une classe commune</h2>
 *
 * <p>{@code LeavesBlock} existe des deux côtés, mais {@code skipRendering} ne sert qu'au maillage
 * d'un chunk, donc au client. La déclaration dans la section {@code client} du fichier de mixins
 * empêche un serveur dédié de charger {@link Foliage}, qui touche {@code Minecraft}.
 */
@Mixin(LeavesBlock.class)
public abstract class LeafCullMixin {
    @Inject(method = "skipRendering", at = @At("HEAD"), cancellable = true)
    private void lanterne$hideSharedFace(BlockState state, BlockState neighbourState,
            Direction direction, CallbackInfoReturnable<Boolean> callback) {
        if (Foliage.hiddenBy(neighbourState)) {
            callback.setReturnValue(true);
        }
    }
}
