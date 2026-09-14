package fr.clubcitrouille.lanterne.content.painting;

import net.minecraft.core.Direction;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * L'état de rendu d'une toile : tout ce dont le dessin a besoin, et rien de plus.
 *
 * <h2>Pourquoi cet objet existe en 26.1</h2>
 *
 * <p>Le moteur de rendu sépare désormais deux temps qui étaient confondus : l'<b>extraction</b>, qui
 * lit le monde, et la <b>soumission</b>, qui écrit des sommets. Entre les deux, un objet comme
 * celui-ci — figé, sans référence au monde ni à l'entité. C'est ce qui permet au moteur de
 * réordonner, de regrouper et éventuellement de paralléliser le dessin sans jamais relire un chunk
 * depuis un fil de rendu.
 *
 * <p>Conséquence directe pour nous : <b>tout ce qui coûte doit être fait à l'extraction</b>. La
 * recherche de la case dans la mosaïque, les niveaux de lumière, les proportions — tout est ici, et
 * la soumission ne fait plus qu'additionner des nombres déjà connus.
 *
 * <h2>Les objets sont réutilisés</h2>
 *
 * <p>Le moteur garde un état par entité et le remplit à nouveau à chaque image, plutôt que d'en
 * allouer un. C'est pourquoi {@link #light} est un tableau que l'on redimensionne seulement quand la
 * taille change : réallouer à chaque image ferait exactement ce que ce découpage cherche à éviter.
 */
@OnlyIn(Dist.CLIENT)
public class CanvasState extends EntityRenderState {
    /** Le mur auquel la toile est accrochée. */
    public Direction direction = Direction.SOUTH;

    public int width = 1;
    public int height = 1;

    /** L'encadrement. Décide de la bordure, du dos et du décalage de la face. Voir {@link Trim}. */
    public Trim trim = Trim.BOIS;

    /**
     * La case de l'image dans la mosaïque, ou {@code null} si l'image n'est pas encore arrivée.
     *
     * <p>Nul est un état normal, pas une erreur : une toile dont l'image est encore en transit se
     * dessine dans la couleur de son dos. Elle occupe sa place, on comprend qu'un tableau s'y
     * trouve, et l'image s'y pose d'elle-même quelques secondes plus tard sans rien à recharger.
     */
    public Mosaic.Cell cell;

    /** La case du dos et des tranches, toujours présente. */
    public Mosaic.Cell back;

    /**
     * La lumière, un niveau par bloc couvert.
     *
     * <p>Un seul niveau pour tout le tableau serait plus simple et se verrait : une fresque de huit
     * blocs qui traverse une pièce éclairée et un couloir sombre serait uniformément claire ou
     * uniformément noire. Vanilla fait le même choix pour ses propres tableaux, et pour la même
     * raison.
     */
    public int[] light = new int[1];
}
