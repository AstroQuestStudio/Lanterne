package fr.clubcitrouille.lanterne.compat.carte;

import java.util.List;

/**
 * Une source de points à montrer sur une carte web — aujourd'hui les portails ({@code Gates}), demain
 * autre chose. Voir {@code Carte} pour la couche d'extension qui consomme ces sources, et
 * {@code CarteBlueMap} pour le seul endroit du mod qui traduit ceci en marqueurs BlueMap.
 *
 * <p>Aucune implémentation de cette interface, ni {@link CarteMarker}, ni {@link CarteMarkers}, ne
 * nomme un seul type de {@code de.bluecolored.bluemap.api.*} : c'est ce qui permet à ce mécanisme de
 * fonctionner (silencieusement, sans rien afficher) sur un serveur qui n'a pas BlueMap, ou qui utilise
 * un tout autre logiciel de carte.
 */
public interface MarkerSource {
    /** Identifiant stable de cette source — sert de clé d'ensemble de marqueurs sur la carte. */
    String id();

    /** Le nom de l'ensemble, tel qu'affiché dans la liste des calques de la carte. */
    String label();

    /** Cet ensemble doit-il être coché par défaut à l'ouverture de la carte ? */
    default boolean visibleByDefault() {
        return true;
    }

    /**
     * L'état actuel, recalculé à chaque appel — jamais mis en cache ici : c'est
     * {@code CarteMarkers#notifyChanged} qui dit QUAND relire, cette méthode dit seulement QUOI lire.
     */
    List<CarteMarker> markers();
}
