package fr.clubcitrouille.lanterne.content.waypoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Config;

/**
 * Le carnet : tous les repères du serveur, et leur persistance.
 *
 * <h2>Un seul carnet pour le serveur entier, et non un par monde</h2>
 *
 * <p>Les données de monde de vanilla sont attachées à un {@code ServerLevel}. Un repère, lui,
 * <b>désigne</b> un monde mais n'y appartient pas : celui qui note l'entrée de son portail veut le
 * retrouver depuis le Nether. Un carnet par dimension aurait rendu invisible, depuis l'Overworld, le
 * repère posé dans l'End.
 *
 * <p>Le carnet est donc rangé une fois pour toutes dans l'Overworld, et chaque repère porte le nom de
 * sa dimension. L'Overworld existe toujours et se charge en premier : c'est le seul endroit d'un
 * serveur dont on soit certain.
 */
public class Atlas extends SavedData {
    private static final String KEY = "lanterne_waypoints";

    public static final Codec<Atlas> CODEC = RecordCodecBuilder.create(spec -> spec.group(
            Waypoint.CODEC.listOf().fieldOf("waypoints").forGetter(atlas -> atlas.marks)
    ).apply(spec, Atlas::new));

    public static final SavedDataType<Atlas> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, KEY), Atlas::new, CODEC);

    private final List<Waypoint> marks;

    public Atlas() {
        this.marks = new ArrayList<>();
    }

    private Atlas(List<Waypoint> loaded) {
        this.marks = new ArrayList<>(loaded);
    }

    /** Le carnet du serveur. Toujours celui de l'Overworld — voir l'en-tête. */
    public static Atlas of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Tout ce que ce joueur a le droit de voir : les siens, et ceux que d'autres ont partagés. */
    public List<Waypoint> visibleTo(UUID who) {
        List<Waypoint> seen = new ArrayList<>();
        for (Waypoint mark : marks) {
            if (mark.ownedBy(who) || mark.shared()) {
                seen.add(mark);
            }
        }
        // Les siens d'abord, puis les partagés, chacun par ordre alphabétique : une liste dont
        // l'ordre change d'une ouverture à l'autre ne se mémorise pas, et un carnet sert justement
        // à se souvenir.
        seen.sort(Comparator
                .comparing((Waypoint mark) -> mark.ownedBy(who) ? 0 : 1)
                .thenComparing(Waypoint::name, String.CASE_INSENSITIVE_ORDER));
        return seen;
    }

    /** Ceux que ce joueur possède, partagés ou non. */
    public List<Waypoint> ownedBy(UUID who) {
        List<Waypoint> mine = new ArrayList<>();
        for (Waypoint mark : marks) {
            if (mark.ownedBy(who)) {
                mine.add(mark);
            }
        }
        return mine;
    }

    /**
     * Ajoute un repère, si le quota le permet et si le nom est libre.
     *
     * @return {@code null} si tout va bien, ou la raison du refus
     */
    public String add(Waypoint mark) {
        int quota = Config.WAYPOINT_QUOTA.get();
        if (ownedBy(mark.owner()).size() >= quota) {
            return "Tu as déjà " + quota + " repère" + (quota > 1 ? "s" : "")
                    + ". Retires-en un avant d'en poser un autre.";
        }
        if (find(mark.owner(), mark.name()) != null) {
            return "Tu as déjà un repère nommé « " + mark.name() + " ».";
        }
        marks.add(mark);
        setDirty();
        return null;
    }

    /** Retire un repère de ce joueur. */
    public boolean remove(UUID who, String name) {
        Waypoint found = find(who, name);
        if (found == null) {
            return false;
        }
        marks.remove(found);
        setDirty();
        return true;
    }

    /** Remplace un repère par une version modifiée. Le propriétaire seul peut le faire. */
    public boolean replace(UUID who, String name, java.util.function.UnaryOperator<Waypoint> change) {
        Waypoint found = find(who, name);
        if (found == null) {
            return false;
        }
        marks.set(marks.indexOf(found), change.apply(found));
        setDirty();
        return true;
    }

    public Waypoint find(UUID who, String name) {
        for (Waypoint mark : marks) {
            if (mark.ownedBy(who) && mark.name().equalsIgnoreCase(name)) {
                return mark;
            }
        }
        return null;
    }

    /** Envoie à ce joueur le carnet tel qu'il a le droit de le voir. */
    public void syncTo(ServerPlayer player) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new WaypointSync(visibleTo(player.getUUID())));
    }

    /**
     * Renvoie le carnet à <b>tous</b> les joueurs connectés.
     *
     * <p>Nécessaire dès qu'un repère est partagé ou cesse de l'être : le changement concerne des
     * carnets qui ne sont pas celui de l'auteur. N'envoyer qu'à lui laisserait les autres avec une
     * copie périmée, et un repère fantôme est pire que pas de repère.
     */
    public void syncAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            syncTo(player);
        }
    }

    public int total() {
        return marks.size();
    }
}
