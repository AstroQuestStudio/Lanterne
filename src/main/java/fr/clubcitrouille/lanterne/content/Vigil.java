package fr.clubcitrouille.lanterne.content;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * La Lanterne de Veille : aucun monstre n'apparaît dans son chunk.
 *
 * <h2>Le bloc qui manquait au mod qui porte son nom</h2>
 *
 * <p>Ce mod s'appelle Lanterne et n'avait pas de lanterne. Voici la sienne, et elle n'est pas un
 * ornement : c'est le <b>seul ajout de ce projet qui rend le serveur plus rapide</b>.
 *
 * <p>Tous les autres coûtent ou sont neutres. L'Ancre garde un chunk chargé, donc tické, pour
 * toujours. Le balai supprime ce qui existe, ce qui est un aveu d'échec déguisé en fonctionnalité. La
 * Lanterne, elle, retire du travail que personne ne réclamait — et c'est le joueur qui choisit où.
 *
 * <h2>Ce qu'elle épargne vraiment</h2>
 *
 * <p>Elle n'éteint pas les monstres après coup : elle refuse la tentative <b>avant</b> qu'elle ne
 * commence. Le tirage de position, la carte des hauteurs, la recherche du joueur le plus proche, les
 * règles de placement, la création de la créature et tout ce qu'elle aurait vécu ensuite — rien de
 * cela n'a lieu. Voir {@link fr.clubcitrouille.lanterne.core.Watch} pour le détail du chemin évité.
 *
 * <h2>Pourquoi un chunk, et pas un rayon</h2>
 *
 * <p>Un rayon en blocs serait plus intuitif, et beaucoup plus cher : il faudrait, à chaque tentative
 * d'apparition, mesurer une distance à chaque Lanterne du monde. Le chunk se teste par une seule
 * consultation de table, sur un chemin parcouru des milliers de fois par seconde.
 *
 * <p>C'est aussi la langue que l'Ancre parle déjà : <em>son chunk, et lui seul</em>. Deux blocs, une
 * seule règle à retenir — l'un garde son chunk chargé, l'autre le garde calme. Une grande base en
 * couvre plusieurs, et le compte se tient tout seul.
 *
 * <h2>Ce qu'elle ne fait pas</h2>
 *
 * <p>Elle ne touche ni aux générateurs de créatures que le joueur a bâtis, ni aux raids, ni aux
 * invocations. Ce sont des choses voulues ; les supprimer ne serait pas de l'optimisation, ce serait
 * retirer le jeu.
 */
public class Vigil extends BaseEntityBlock {
    public Vigil(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VigilBlockEntity(pos, state);
    }

    /**
     * Aucun ticker, et c'est la raison d'être du bloc-entité.
     *
     * <p>Un bloc-entité sans ticker ne coûte <b>rien</b> par tick : il n'existe que pour être
     * sérialisé avec son chunk, et pour dire au mod quand il arrive et quand il part. C'est ce qui
     * donne la persistance — une Lanterne posée garde son chunk après un redémarrage — sans payer le
     * prix habituel d'un bloc-entité.
     *
     * <p>{@code BaseEntityBlock} n'en fournit aucun par défaut ; on n'en redéfinit donc pas, et cette
     * absence est délibérée plutôt qu'omise.
     */
    @Override
    protected net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
        // Sans cela, un BaseEntityBlock est INVISIBLE : sa forme de rendu par défaut est
        // « le bloc-entité s'en charge », et le nôtre ne rend rien. Le bloc existerait, serait
        // solide, éclairerait — et l'on ne verrait que le décor derrière lui.
        return net.minecraft.world.level.block.RenderShape.MODEL;
    }
}
