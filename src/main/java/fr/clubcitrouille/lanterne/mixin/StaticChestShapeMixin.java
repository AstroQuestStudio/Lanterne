package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le coffre déclaré modèle, pour que le mailleur de chunk daigne le regarder.
 *
 * <p>Le raisonnement est dans {@code StaticChestRendererMixin}. Ce fichier n'en est que la seconde
 * moitié, et l'une ne vaut rien sans l'autre.
 *
 * <h2>Une méthode ajoutée, et non interceptée</h2>
 *
 * <p>{@code getRenderShape} n'est pas déclarée par {@code AbstractChestBlock} : elle est héritée de
 * {@code BlockBehaviour}, où elle rend {@code INVISIBLE} pour tout bloc porteur d'une entité de
 * bloc. Il n'y a donc rien à intercepter ici. Déclarer la méthode dans un mixin l'<b>ajoute</b> à
 * la classe cible, ce qui redéfinit celle du parent pour tous les coffres — normaux, piégés, de
 * l'Ender, et les variantes de cuivre, qui en héritent toutes.
 *
 * <p>C'est la raison pour laquelle cette classe n'a ni annotation d'injection ni {@code @Shadow} :
 * elle n'en a pas besoin.
 *
 * <p>Repris de <b>Faster Block Entities</b> (kvxd, GPL-3.0-or-later). Voir {@code NOTICE.md}.
 */
@Mixin(AbstractChestBlock.class)
public abstract class StaticChestShapeMixin {
    protected RenderShape getRenderShape(BlockState state) {
        // Éteint : on rend INVISIBLE, exactement ce que faisait le parent. Le modèle livré par ce
        // mod n'est alors jamais maillé, et le dessinateur d'entité reprend la main seul. Rien
        // n'est dessiné deux fois.
        return Settings.staticChests() ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }
}
