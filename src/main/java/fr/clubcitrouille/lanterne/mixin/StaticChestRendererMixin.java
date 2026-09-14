package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Les coffres rendus comme des blocs ordinaires, et non comme des entités animées.
 *
 * <h2>Ce qu'un coffre coûte, et pourquoi</h2>
 *
 * <p>Un bloc ordinaire est maillé <b>une fois</b> dans la géométrie de son chunk, puis dessiné avec
 * lui, gratuitement, tant que le chunk ne change pas. Un coffre ne l'est pas : il porte une
 * {@code BlockEntity} et son propre dessinateur, réexécuté <b>à chaque image</b> pour animer son
 * couvercle — même fermé, même à quarante blocs, même dans un entrepôt de trois cents coffres dont
 * aucun ne s'ouvrira jamais.
 *
 * <p>Faire de ce coffre un modèle le fait retomber dans le maillage du chunk. Le couvercle cesse de
 * s'animer ; en échange, son dessin cesse d'exister.
 *
 * <h2>Pourquoi il faut deux mixins et pas un</h2>
 *
 * <p>Rendre {@code null} ici empêche le dessinateur d'entité de tourner, mais ne fait pas
 * apparaître le modèle : tant que le bloc se déclare {@code INVISIBLE}, le mailleur de chunk
 * l'ignore, et le coffre disparaît purement et simplement. Il faut donc aussi que le bloc se
 * déclare {@code MODEL} — c'est ce que fait {@code StaticChestShapeMixin} — et qu'un modèle existe,
 * ce que fournissent les ressources.
 *
 * <p>La réciproque est vraie et c'est ce qui rend l'interrupteur sûr : quand le module est éteint,
 * le bloc reste {@code INVISIBLE}, donc le modèle n'est pas maillé, donc rien n'est dessiné deux
 * fois. Le jeu revient exactement à son comportement d'origine, sans que les ressources posées par
 * ce mod y changent quoi que ce soit.
 *
 * <h2>Un changement d'avis ne se voit qu'après reconstruction</h2>
 *
 * <p>Le maillage d'un chunk est calculé une fois puis conservé. Basculer cet interrupteur en cours
 * de partie ne change donc rien à ce qui est déjà à l'écran tant que les chunks ne sont pas
 * reconstruits. Le banc d'images le fait explicitement à chaque bascule ; un joueur, lui, verra
 * l'effet au prochain chargement.
 *
 * <p>Repris de <b>Faster Block Entities</b> (kvxd, GPL-3.0-or-later), dont les ressources de modèle
 * viennent elles-mêmes de FastChest (FakeDomi, MIT). Voir {@code NOTICE.md}.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class StaticChestRendererMixin {
    @Inject(
            method = "getRenderer(Lnet/minecraft/world/level/block/entity/BlockEntity;)"
                    + "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;",
            at = @At("HEAD"),
            cancellable = true)
    private <E extends BlockEntity, S extends BlockEntityRenderState> void lanterne$noLidAnimation(
            E blockEntity, CallbackInfoReturnable<BlockEntityRenderer<E, S>> callback) {
        if (Settings.staticChests() && lanterne$isChest(blockEntity)) {
            callback.setReturnValue(null);
        }
    }

    private static boolean lanterne$isChest(BlockEntity blockEntity) {
        // Les boîtes de shulker sont volontairement exclues : leur couvercle qui s'ouvre est un
        // retour d'information utile, pas une décoration. Les coffres en cuivre, eux, héritent de
        // ChestBlockEntity et sont donc couverts sans avoir à les nommer.
        return (blockEntity instanceof ChestBlockEntity
                || blockEntity instanceof EnderChestBlockEntity)
                && !(blockEntity instanceof ShulkerBoxBlockEntity);
    }
}
