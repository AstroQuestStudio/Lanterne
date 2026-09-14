package fr.clubcitrouille.lanterne.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.client.renderer.entity.ItemEntityRenderer;

import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Une pile au sol dessinée une fois, et non quatre.
 *
 * <h2>Le coût, et il est exactement quadruple</h2>
 *
 * <p>Vanilla dessine une pile d'objets posée au sol en plusieurs exemplaires légèrement décalés,
 * pour donner à voir qu'il y en a plusieurs : {@code ItemClusterRenderState.count} vaut un pour une
 * unité, et jusqu'à <b>quatre</b> pour une pile pleine. Chacun de ces exemplaires est un
 * {@code item.submit(...)} complet — même modèle, même texture, même travail, à quelques
 * centimètres près.
 *
 * <p>Sur une ferme qui crache des piles pleines, le sol jonché coûte donc quatre fois ce qu'il
 * paraît. Et c'est précisément la situation où l'on a le plus besoin d'images.
 *
 * <h2>Ce qu'on perd, dit franchement</h2>
 *
 * <p>Une pile de soixante-quatre ressemblera à un objet seul. C'est un renseignement réel qui
 * disparaît — on ne distingue plus d'un coup d'œil un tas d'une unité — et c'est pour cette raison
 * que le réglage existe et monte jusqu'à quatre : qui tient à l'indice visuel le remet à quatre et
 * retrouve vanilla à l'identique.
 *
 * <h2>Pourquoi ce point d'accroche et pas le rendu à plat</h2>
 *
 * <p>Le mod de référence sur ce sujet remplace le modèle tridimensionnel par une image plate face à
 * la caméra. L'idée est bonne et le gain plus large, mais son code vise une interface de rendu qui
 * n'existe plus : 26.1 a remplacé {@code BakedModel} et {@code ItemRenderer.render} par
 * l'extraction d'états et {@code SubmitNodeCollector}. Le porter ne serait pas une reprise, ce
 * serait une réécriture du rendu d'objets — un chantier à lui seul, et qui se heurterait à chaque
 * mod ajoutant ses propres modèles.
 *
 * <p>Plafonner le nombre d'exemplaires n'a aucun de ces défauts : une seule valeur change, le rendu
 * reste celui du jeu, et l'économie est exactement proportionnelle à ce qu'on retire.
 */
@Mixin(ItemEntityRenderer.class)
public abstract class LooseItemMixin {
    @ModifyExpressionValue(
            method = "submitMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
                    + "Lnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;"
                    + "Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/phys/AABB;)V",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;"
                            + "count:I",
                    opcode = org.objectweb.asm.Opcodes.GETFIELD))
    private static int lanterne$oneIsEnough(int count) {
        // Zéro doit rester zéro : c'est la marque d'une pile vide, et vanilla s'en sert pour ne rien
        // dessiner du tout. Le plafond ne s'applique qu'à ce qui aurait été dessiné.
        return count == 0 ? 0 : Math.min(count, Settings.itemCopies());
    }
}
