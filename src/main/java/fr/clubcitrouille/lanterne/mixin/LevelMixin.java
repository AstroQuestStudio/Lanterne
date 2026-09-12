package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Six cent mille objets par seconde pour trois nombres.
 *
 * <h2>Ce que le profileur d'allocations a désigné</h2>
 *
 * <p>Le relevé place cette méthode au troisième rang des allocateurs du serveur, à près de douze
 * pour cent du total — un gigaoctide et demi pendant vingt-cinq secondes d'épreuve :
 *
 * <pre>
 * 11,9 %  Level.getBlockRandomPos → BlockPos   (1,58 Go)
 * </pre>
 *
 * <p>Le corps de la méthode explique tout :
 *
 * <pre>
 * public BlockPos getBlockRandomPos(int xo, int yo, int zo, int yMask) {
 *     this.randValue = this.randValue * 3 + 1013904223;
 *     int val = this.randValue &gt;&gt; 2;
 *     return new BlockPos(xo + (val &amp; 15), yo + (val &gt;&gt; 16 &amp; yMask), zo + (val &gt;&gt; 8 &amp; 15));
 * }
 * </pre>
 *
 * <p>Un objet neuf, pour trois entiers, à chaque appel. Et les appels sont innombrables : le tick
 * des blocs tire trois positions par section, sur vingt-quatre sections, pour chaque chunk simulé.
 * À dix chunks de distance, cela fait <b>plus de trente mille objets par tick</b>, six cent mille
 * par seconde — tous jetés dans la milliseconde.
 *
 * <h2>La correction, et son seul risque</h2>
 *
 * <p>On rend une position <em>mutable</em> réutilisée, propre à chaque monde. Le tick d'un monde est
 * mono-fil, donc il n'y a ni partage ni course : la position est écrite, lue par l'appelant, puis
 * réécrite au tirage suivant.
 *
 * <p>Le risque tient en une phrase : <b>un appelant qui conserverait la référence au-delà de son
 * tour la verrait changer sous lui</b>. C'est la raison pour laquelle le jeu alloue — c'est
 * toujours sûr, et c'est toujours cher.
 *
 * <p>Les appelants du jeu ne conservent rien : ils lisent les coordonnées, puis dérivent d'autres
 * positions — {@code pos.above()}, {@code pos.relative(...)} — qui sont de nouveaux objets. Un mod
 * tiers qui garderait la référence obtiendrait une position fausse, et c'est pourquoi cette
 * optimisation <b>se désactive</b> par réglage. Lithium prend le même risque depuis des années sur
 * des millions d'installations, ce qui ne le supprime pas mais le borne.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
    @org.spongepowered.asm.mixin.Shadow
    private int randValue;

    /**
     * La position réutilisée de ce monde.
     *
     * <p>Un champ par monde, et non une variable de thread : le tick d'un monde s'exécute sur un
     * seul fil, et une variable de thread coûterait la recherche que ce mod a justement supprimée
     * ailleurs.
     */
    @Unique
    private final BlockPos.MutableBlockPos lanterne$scratch = new BlockPos.MutableBlockPos();

    @Inject(method = "getBlockRandomPos", at = @At("HEAD"), cancellable = true)
    private void lanterne$reusePosition(int xo, int yo, int zo, int yMask,
                                        CallbackInfoReturnable<BlockPos> callback) {
        if (!Settings.scratchPos()) {
            return;
        }
        // On reproduit le générateur du jeu à l'identique : même suite de nombres, mêmes positions
        // tirées, même comportement de jeu. Un générateur qui dériverait changerait la croissance
        // des cultures, la propagation du feu et la fonte de la neige. Seule l'allocation disparaît.
        this.randValue = this.randValue * 3 + 1013904223;
        int value = this.randValue >> 2;
        callback.setReturnValue(lanterne$scratch.set(
                xo + (value & 15),
                yo + (value >> 16 & yMask),
                zo + (value >> 8 & 15)));
    }
}
