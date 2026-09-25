package fr.clubcitrouille.lanterne.compat.carte;

import fr.clubcitrouille.lanterne.report.Compagnons;

/**
 * Le point d'entrée public de la couche carte — appelé une fois depuis {@code Lanterne}, des deux
 * côtés, sans condition.
 *
 * <h2>Pourquoi cette classe ne nomme aucun type BlueMap</h2>
 *
 * <p>Même raison, et même piège évité, que {@code lab.Cadence} vis-à-vis de {@code lab.CadenceBlueMap} —
 * voir sa Javadoc de classe pour le crash réel du 21/09/2026 que cette séparation prévient. Le vrai
 * travail vit dans {@code CarteBlueMap}, chargée UNIQUEMENT si {@link Compagnons#present} confirme que
 * le mod {@code bluemap} est présent — jamais avant, jamais « au cas où ».
 */
public final class Carte {
    private Carte() {}

    /** Branche l'adaptateur BlueMap si (et seulement si) BlueMap est installé sur ce serveur. */
    public static void register() {
        if (Compagnons.present(Compagnons.BLUEMAP)) {
            CarteBlueMap.install();
        }
    }
}
