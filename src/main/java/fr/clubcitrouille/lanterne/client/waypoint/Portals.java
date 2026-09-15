package fr.clubcitrouille.lanterne.client.waypoint;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import fr.clubcitrouille.lanterne.content.waypoint.Gate;

/**
 * Ce que le client sait des portails : rien de plus que ce que le serveur lui a dit.
 *
 * <h2>Même doctrine que le carnet, et une raison de plus d'y tenir</h2>
 *
 * <p>Le serveur envoie la liste entière à chaque changement ; le client remplace au lieu d'amender.
 * Pour les repères, une copie périmée donnait un affichage faux. Ici, elle donnerait un joueur qui
 * choisit une destination qui n'existe plus — et c'est le serveur qui refusera, mais après coup, ce qui
 * fait une mauvaise surprise au lieu d'une liste honnête.
 *
 * <h2>Pourquoi les sections sont redessinées à la réception</h2>
 *
 * <p>La teinte d'une nappe de portail est lue <b>à la position</b> (voir {@link Hue}) et non dans l'état
 * du bloc : elle est donc figée dans le maillage du chunk au moment où il a été construit. Recolorer un
 * portail ne change rien à ses blocs, donc ne salit aucune section, donc ne se verrait qu'au prochain
 * rechargement — parfois jamais. On marque donc explicitement sales les sections des portails connus.
 *
 * <p>C'est quelques dizaines de sections lors d'un évènement rare (un allumage, un changement de
 * couleur). Le faire à chaque tick serait absurde ; le faire ici ne coûte rien.
 */
public final class Portals {
    private static List<Gate> known = List.of();

    private Portals() {}

    /** Reçoit la liste. Appelé sur le fil client. */
    public static void accept(List<Gate> fresh) {
        known = List.copyOf(fresh);
        repaint();
    }

    public static List<Gate> all() {
        return known;
    }

    /** Le portail dont le cœur est ici — c'est ainsi que l'écran sait de quel portail on parle. */
    public static Gate atHeart(BlockPos heart) {
        var client = Minecraft.getInstance();
        if (client.level == null) {
            return null;
        }
        var where = client.level.dimension().identifier();
        for (Gate gate : known) {
            if (gate.dimension().equals(where) && gate.heart().equals(heart)) {
                return gate;
            }
        }
        return null;
    }

    public static Gate byId(java.util.UUID id) {
        for (Gate gate : known) {
            if (gate.id().equals(id)) {
                return gate;
            }
        }
        return null;
    }

    /** La teinte d'une nappe, ou l'ambre du mod si ce portail ne nous a pas été annoncé. */
    public static int colourAt(BlockPos pos) {
        var client = Minecraft.getInstance();
        if (client.level != null) {
            var where = client.level.dimension().identifier();
            for (Gate gate : known) {
                if (gate.dimension().equals(where) && gate.contains(pos)) {
                    return gate.colour();
                }
            }
        }
        // Un portail privé d'un autre joueur ne nous est jamais annoncé : il existe, on le voit, et
        // l'on ne sait rien de lui. L'ambre du mod est la réponse honnête — pas une couleur inventée.
        return fr.clubcitrouille.lanterne.content.waypoint.Waypoint.PALETTE[0];
    }

    private static void repaint() {
        var client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        var where = client.level.dimension().identifier();
        for (Gate gate : known) {
            if (!gate.dimension().equals(where)) {
                continue;
            }
            client.level.setSectionRangeDirty(
                    SectionPos.blockToSectionCoord(gate.min().getX()),
                    SectionPos.blockToSectionCoord(gate.min().getY()),
                    SectionPos.blockToSectionCoord(gate.min().getZ()),
                    SectionPos.blockToSectionCoord(gate.max().getX()),
                    SectionPos.blockToSectionCoord(gate.max().getY()),
                    SectionPos.blockToSectionCoord(gate.max().getZ()));
        }
    }
}
