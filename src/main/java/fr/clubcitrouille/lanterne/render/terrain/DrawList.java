package fr.clubcitrouille.lanterne.render.terrain;

import java.util.List;

/**
 * La liste de ce qu'il faut dessiner pour une passe : des plages de sections, déjà triées et déjà cullées.
 * Construite par un {@link DrawListBuilder}, consommée par le pont Iris qui la transforme en commandes
 * de dessin indirectes (une seule par tampon partagé).
 */
public interface DrawList {
    /** La passe pour laquelle cette liste a été bâtie. */
    TerrainPass pass();

    /** Les plages à dessiner, dans l'ordre de dessin. */
    List<SectionSlice> slices();

    /** Nombre total d'index de la liste : sert à dimensionner le tampon d'index séquentiel. */
    int totalIndexCount();

    /** Vrai si rien n'est à dessiner. */
    default boolean isEmpty() {
        return slices().isEmpty();
    }
}
