package fr.clubcitrouille.lanterne.compat.carte;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le registre neutre de la couche carte : les sources de marqueurs déclarées, et qui écouter quand
 * l'une d'elles change. Ni cette classe ni {@link MarkerSource}/{@link CarteMarker} ne référencent
 * BlueMap — voir la Javadoc de {@link MarkerSource}.
 *
 * <h2>Pourquoi un registre plutôt qu'un appel direct</h2>
 *
 * <p>{@code content.waypoint.Gates} ne doit rien savoir de BlueMap, et {@code compat.carte.CarteBlueMap}
 * ne doit rien savoir des portails. Ce registre est le point de rencontre : une source s'y inscrit une
 * fois ({@link #register}), un adaptateur de carte s'y abonne une fois ({@link #onChange}), et
 * {@link #notifyChanged} — appelé depuis {@code Atlas#syncAll}, le seul endroit où une création, une
 * liaison, une suppression ou un renommage de portail se répercute — relie les deux sans qu'aucun des
 * deux côtés n'ait besoin de connaître l'autre.
 */
public final class CarteMarkers {
    private static final List<MarkerSource> SOURCES = new CopyOnWriteArrayList<>();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private CarteMarkers() {}

    /** Déclare une source de marqueurs. S'enregistre toujours, même si aucune carte n'est présente. */
    public static void register(MarkerSource source) {
        SOURCES.add(source);
    }

    public static List<MarkerSource> sources() {
        return List.copyOf(SOURCES);
    }

    /** S'abonne aux changements — appelé une fois par adaptateur de carte, s'il y en a un d'actif. */
    public static void onChange(Runnable listener) {
        LISTENERS.add(listener);
    }

    /** Une source vient de changer : recalcule et republie. Sans effet si aucun adaptateur n'écoute. */
    public static void notifyChanged() {
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException echec) {
                Lanterne.LOG.warn("[CARTE] un adaptateur de carte a échoué à se mettre à jour.", echec);
            }
        }
    }
}
