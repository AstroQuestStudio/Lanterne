package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Shroud;

/**
 * Le voile étendu aux entités de bloc : un coffre derrière un mur n'est pas préparé.
 *
 * <h2>Le même angle mort que pour les créatures</h2>
 *
 * <p>{@code tryExtractRenderState} écarte déjà trois choses : l'absence de dessinateur, un type
 * invalide, et ce qui sort du champ de vision. Comme pour les entités, il n'écarte <b>pas</b> ce
 * qui est dans le champ mais derrière de la pierre.
 *
 * <p>Or le coût qui suit est le même : {@code createRenderState()} puis
 * {@code extractRenderState(...)}, c'est-à-dire la construction complète de l'état de dessin. Un
 * entrepôt de trois cents coffres vu depuis l'extérieur de son bâtiment les prépare tous les trois
 * cents, à chaque image, pour n'en montrer aucun.
 *
 * <h2>Pourquoi le point sondé est le centre du bloc</h2>
 *
 * <p>Une entité de bloc occupe un cube, et son centre en est le représentant le plus honnête : le
 * sonder depuis l'œil répond à la question « ce cube est-il derrière quelque chose ». Les coins
 * seraient plus précis et coûteraient cinq fois plus ; pour un objet qui tient exactement dans un
 * bloc — contrairement à une créature dont la boîte déborde — le centre suffit.
 *
 * <p>Le cache de {@code Shroud} est indexé par position de bloc : deux coffres côte à côte
 * partagent rarement leur réponse, mais un même coffre la conserve d'une image à l'autre, ce qui
 * est exactement ce qu'on veut d'un objet immobile.
 *
 * <p>Le complément de ce module est {@code StaticChestRendererMixin}, qui supprime le dessinateur
 * des coffres <em>visibles</em>. Les deux se cumulent : l'un retire ce qu'on ne voit pas, l'autre
 * allège ce qu'on voit.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityVeilMixin {
    @Shadow
    public Vec3 cameraPos;

    /**
     * <h2>Ce mixin a fait échouer le démarrage du client, et le contrôleur ne l'avait pas vu</h2>
     *
     * <p>La 26.2 a inséré un {@code boolean} avant le {@code Frustum} : la méthode visée prend
     * désormais cinq arguments. La cible portant sa <b>signature entière</b>, elle ne correspondait
     * plus à rien, et Mixin refusait de s'appliquer — écran d'erreur au lancement, alors que tout
     * compilait et que le serveur dédié démarrait sans broncher. Un serveur dédié ne charge aucun
     * mixin client : rien ne pouvait le signaler de ce côté.
     *
     * <p>{@code tools/verifie_mixins.py} ne relevait alors que le <em>nom</em> d'une cible
     * {@code method}, et {@code tryExtractRenderState} existe toujours. Il vérifie désormais la
     * signature quand elle est donnée — c'est très exactement l'angle mort que son propre en-tête
     * annonçait, et il aura fallu qu'il morde pour le fermer.
     *
     * <h2>Ce que le nouveau paramètre apprend, et qui vaut mieux qu'un simple raccommodage</h2>
     *
     * <p>{@code isGloballyRendered} désigne les blocs-entités que le jeu dessine <b>sans se soucier
     * de la visibilité de leur section</b> : le rayon d'une balise, une passerelle de l'End. Ils sont
     * conçus pour se voir à travers le terrain, de très loin.
     *
     * <p>Les voiler serait donc casser leur raison d'être — et le repère qu'un joueur a bâti pour
     * retrouver sa base disparaîtrait dès qu'une colline s'interpose. On les laisse passer.
     */
    @Inject(
            method = "tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;F"
                    + "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;Z"
                    + "Lnet/minecraft/client/renderer/culling/Frustum;)"
                    + "Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;",
            at = @At("HEAD"),
            cancellable = true)
    private <E extends BlockEntity, S extends BlockEntityRenderState> void lanterne$veil(
            E blockEntity, float partialTicks, ModelFeatureRenderer.CrumblingOverlay breakProgress,
            boolean globallyRendered, Frustum frustum, CallbackInfoReturnable<S> callback) {
        if (!Settings.shroud() || !Settings.veilBlockEntities()) {
            return;
        }
        // Un rayon de balise se voit a travers le terrain par construction : le voiler reviendrait a
        // supprimer le repere qu'un joueur a bati pour retrouver sa base. Voir la note ci-dessus.
        if (globallyRendered) {
            return;
        }
        // Un bloc en cours de cassage porte sa fissure : la cacher ferait disparaître le retour
        // visuel du minage sous les doigts du joueur.
        if (breakProgress != null || this.cameraPos == null || !blockEntity.hasLevel()) {
            return;
        }
        BlockPos pos = blockEntity.getBlockPos();
        if (Shroud.spotHidden(blockEntity.getLevel(), pos.getX() + 0.5d, pos.getY() + 0.5d,
                pos.getZ() + 0.5d, this.cameraPos.x, this.cameraPos.y, this.cameraPos.z)) {
            callback.setReturnValue(null);
        }
    }
}
