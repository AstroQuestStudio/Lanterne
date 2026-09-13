package fr.clubcitrouille.lanterne.client.waypoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import fr.clubcitrouille.lanterne.content.waypoint.Waypoint;

/**
 * Ce que le client sait des repères : rien de plus que ce que le serveur lui a dit.
 *
 * <h2>Aucun état à réconcilier</h2>
 *
 * <p>Le serveur envoie le carnet entier à chaque changement. Le client n'ajoute, ne retire et ne
 * modifie jamais rien de son propre chef : il <b>remplace</b>. C'est ce qui rend impossible le défaut
 * classique de ces systèmes — un repère qu'on croit partagé et qui ne l'est pas, un repère supprimé
 * qui reste affiché.
 *
 * <p>Une action de l'interface envoie donc une demande et ne touche à rien ; l'affichage ne change
 * qu'au retour du serveur. C'est un aller-retour de plus, et c'est le prix d'un carnet qui ne ment
 * jamais.
 */
public final class Marks {
    private static List<Waypoint> known = List.of();

    private Marks() {}

    /** Reçoit le carnet. Appelé sur le fil client. */
    public static void accept(List<Waypoint> fresh) {
        known = List.copyOf(fresh);
    }

    public static List<Waypoint> all() {
        return known;
    }

    /** Ceux du monde où le joueur se trouve — les seuls dont la distance ait un sens. */
    public static List<Waypoint> here() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return List.of();
        }
        var where = client.level.dimension().identifier();
        List<Waypoint> near = new ArrayList<>();
        for (Waypoint mark : known) {
            if (mark.dimension().equals(where)) {
                near.add(mark);
            }
        }
        return near;
    }

    /**
     * Les repères du monde courant, du plus proche au plus lointain.
     *
     * <p>Le tri se fait à l'affichage et non à la réception : la distance change à chaque pas, et un
     * carnet trié une fois pour toutes serait faux dès le pas suivant.
     */
    public static List<Waypoint> byDistance() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return List.of();
        }
        BlockPos from = client.player.blockPosition();
        List<Waypoint> sorted = new ArrayList<>(here());
        sorted.sort((a, b) -> Double.compare(
                a.pos().distSqr(from), b.pos().distSqr(from)));
        return Collections.unmodifiableList(sorted);
    }

    /** Distance horizontale en blocs. L'altitude fausserait la lecture dans une mine. */
    public static double flatDistance(Waypoint mark) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0d;
        }
        double dx = mark.pos().getX() + 0.5d - client.player.getX();
        double dz = mark.pos().getZ() + 0.5d - client.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * L'écart d'angle entre le regard du joueur et le repère, en degrés, dans [-180, 180].
     *
     * <p>Zéro veut dire « droit devant ». C'est ce que la boussole de l'affichage dessine, et c'est
     * plus utile qu'un azimut absolu : un joueur ne sait pas où est le nord, il sait où il regarde.
     */
    public static float bearing(Waypoint mark) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return 0f;
        }
        double dx = mark.pos().getX() + 0.5d - client.player.getX();
        double dz = mark.pos().getZ() + 0.5d - client.player.getZ();
        float toMark = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90d);
        float delta = toMark - client.player.getYRot();
        while (delta <= -180f) {
            delta += 360f;
        }
        while (delta > 180f) {
            delta -= 360f;
        }
        return delta;
    }
}
