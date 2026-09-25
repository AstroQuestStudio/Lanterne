package fr.clubcitrouille.lanterne.compat.carte;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.flowpowered.math.vector.Vector3d;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.LineMarker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Line;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Tout le code touchant réellement les types BlueMap, isolé ici et nulle part ailleurs — voir la
 * Javadoc de {@code lab.CadenceBlueMap} pour pourquoi c'est une nécessité et pas une préférence de
 * style : charger CETTE classe suffit à faire planter le démarrage sur un serveur sans BlueMap, donc
 * {@link Carte} ne le fait qu'après avoir vérifié la présence du mod.
 *
 * <h2>Une reconstruction complète, pas une mise à jour incrémentale</h2>
 *
 * <p>À chaque appel de {@link #refreshAll}, l'ensemble de marqueurs d'une source est entièrement
 * remplacé — {@code map.getMarkerSets().put(id, nouvelEnsemble)} — plutôt que d'ajouter, retirer ou
 * modifier des entrées une à une. Un portail oublié ({@code Gates#forget}) n'a alors rien de spécial à
 * faire : il n'apparaît simplement plus dans {@code source.markers()}, et le prochain ensemble ne le
 * contient plus. Le nombre de portails d'un serveur (quelques centaines au plus, voir
 * {@code Atlas#GATE_CEILING}) rend ce coût négligeable face à la simplicité que ça donne.
 */
final class CarteBlueMap {
    private CarteBlueMap() {}

    static void install() {
        // onEnable couvre le démarrage ET tout rechargement/redémarrage de BlueMap — voir la Javadoc
        // de CadenceBlueMap#startOnReady pour la preuve que c'est le seul point d'accroche fiable.
        BlueMapAPI.onEnable(CarteBlueMap::refreshAll);
        BlueMapAPI.onDisable(api ->
                Lanterne.LOG.debug("[CARTE] BlueMap désactivé : ses marqueurs disparaissent avec lui."));
        // La création, la liaison, la suppression ou le renommage d'un portail passe par
        // Atlas#syncAll, qui appelle CarteMarkers#notifyChanged — voir sa Javadoc pour ce choix de
        // point d'accroche unique.
        CarteMarkers.onChange(CarteBlueMap::refreshNow);
    }

    private static void refreshNow() {
        BlueMapAPI.getInstance().ifPresent(CarteBlueMap::refreshAll);
    }

    private static void refreshAll(BlueMapAPI api) {
        for (MarkerSource source : CarteMarkers.sources()) {
            refreshSource(api, source);
        }
    }

    private static void refreshSource(BlueMapAPI api, MarkerSource source) {
        List<CarteMarker> markers = source.markers();
        Map<String, CarteMarker> byId = new HashMap<>();
        for (CarteMarker marker : markers) {
            byId.put(marker.id(), marker);
        }
        for (BlueMapMap map : api.getMaps()) {
            MarkerSet set = MarkerSet.builder()
                    .label(source.label())
                    .toggleable(true)
                    .defaultHidden(!source.visibleByDefault())
                    .build();
            for (CarteMarker marker : markers) {
                if (!sameWorld(api, map, marker)) {
                    continue;
                }
                addToMap(set, marker, byId);
            }
            map.getMarkerSets().put(source.id(), set);
        }
    }

    /** Ce point appartient-il au monde de cette carte ? Même résolution que {@code CadenceBlueMap}. */
    private static boolean sameWorld(BlueMapAPI api, BlueMapMap map, CarteMarker marker) {
        return api.getWorld(marker.dimension())
                .map(world -> world.getMaps().contains(map))
                .orElse(false);
    }

    private static void addToMap(MarkerSet set, CarteMarker marker, Map<String, CarteMarker> byId) {
        Vector3d position = new Vector3d(marker.x(), marker.y(), marker.z());
        set.put("poi_" + marker.id(), POIMarker.builder()
                .label(marker.label())
                .position(position)
                .defaultIcon()
                .build());
        // Un trait vers la destination liée, seulement si elle existe encore et vit dans le MÊME
        // monde : une carte BlueMap ne peut pas dessiner un trait qui traverserait deux dimensions,
        // c'est le sens exact de « si l'API le permet simplement ».
        marker.linkedMarkerId().ifPresent(targetId -> {
            CarteMarker target = byId.get(targetId);
            if (target == null || !target.dimension().equals(marker.dimension())) {
                return;
            }
            Line line = Line.builder()
                    .addPoint(position)
                    .addPoint(new Vector3d(target.x(), target.y(), target.z()))
                    .build();
            set.put("lien_" + marker.id() + "_" + targetId, LineMarker.builder()
                    .label(marker.label() + " → " + target.label())
                    .line(line)
                    .lineColor(new Color(marker.colour(), 0.8f))
                    .lineWidth(2)
                    .build());
        });
    }
}
