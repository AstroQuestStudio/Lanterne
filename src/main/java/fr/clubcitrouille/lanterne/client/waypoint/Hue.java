package fr.clubcitrouille.lanterne.client.waypoint;

import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * La teinte d'une nappe de portail, lue à sa position.
 *
 * <h2>Pourquoi la couleur n'est pas dans l'état du bloc</h2>
 *
 * <p>C'eût été la solution évidente : une propriété de 0 à 7, huit variantes dans le fichier d'états, et
 * le moteur s'occupe de tout. Elle a été écartée pour deux raisons.
 *
 * <p>La première est que la couleur d'un portail <b>appartient au portail, pas à ses blocs</b>. Deux
 * portails côte à côte peuvent partager un montant ; la nappe, elle, est décrite par une fiche unique
 * dans {@link fr.clubcitrouille.lanterne.content.waypoint.Atlas}, et c'est là que la couleur vit déjà.
 * La dupliquer dans huit états de blocs aurait créé une seconde vérité, donc un désaccord possible.
 *
 * <p>La seconde est que la palette peut changer. Une propriété d'état gravée dans un monde sauvegardé ne
 * se renomme pas ; une couleur lue dans une fiche, si.
 *
 * <p>Le prix à payer est que le maillage d'un chunk ne sait pas qu'il a vieilli quand on recolore un
 * portail. {@link Portals#accept} le lui dit.
 */
public final class Hue implements BlockTintSource {

    /**
     * La couleur hors du monde : l'inventaire, les particules de casse, l'écran de recettes.
     *
     * <p>Sans position, il n'y a pas de portail à consulter — on rend l'ambre du mod, qui est la teinte
     * par défaut d'un repère. Cette méthode n'est presque jamais appelée pour ce bloc, qui n'existe pas
     * en objet ; elle doit exister, elle ne doit surtout pas mentir.
     */
    @Override
    public int color(BlockState state) {
        return 0xFF000000 | fr.clubcitrouille.lanterne.content.waypoint.Waypoint.PALETTE[0];
    }

    @Override
    public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        return 0xFF000000 | Portals.colourAt(pos);
    }
}
