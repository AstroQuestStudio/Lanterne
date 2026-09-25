package fr.clubcitrouille.lanterne.content.waypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;

import net.neoforged.neoforge.server.ServerLifecycleHooks;

import fr.clubcitrouille.lanterne.compat.carte.CarteMarker;
import fr.clubcitrouille.lanterne.compat.carte.MarkerSource;

/**
 * Les portails de repère, vus comme une source de marqueurs pour la couche carte — voir
 * {@code compat.carte.MarkerSource}. Ne référence aucun type BlueMap : c'est tout le sens de cette
 * couche d'extension, écrite pour qu'une autre carte que BlueMap — ou un autre contenu que les
 * portails — puisse s'y brancher sans que {@link Gates} ou {@link Atlas} n'aient à le savoir.
 */
final class GatesMarkerSource implements MarkerSource {
    static final GatesMarkerSource INSTANCE = new GatesMarkerSource();

    private GatesMarkerSource() {}

    @Override
    public String id() {
        return "lanterne_portails";
    }

    @Override
    public String label() {
        return "Portails";
    }

    @Override
    public boolean visibleByDefault() {
        return true;
    }

    /** Tous les portails du serveur : ils sont désormais tous publics, voir {@link Gate#visibleTo}. */
    @Override
    public List<CarteMarker> markers() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return List.of();
        }
        List<Gate> gates = Atlas.of(server).allGates();
        List<CarteMarker> markers = new ArrayList<>(gates.size());
        for (Gate gate : gates) {
            markers.add(new CarteMarker(gate.id().toString(), gate.name(), gate.dimension(),
                    gate.heart().getX() + 0.5d, gate.heart().getY() + 0.5d, gate.heart().getZ() + 0.5d,
                    gate.colour(), gate.target().map(UUID::toString)));
        }
        return markers;
    }
}
