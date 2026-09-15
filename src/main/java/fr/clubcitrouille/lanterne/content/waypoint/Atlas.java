package fr.clubcitrouille.lanterne.content.waypoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
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
 *
 * <h2>Les portails habitent le même fichier, et ce n'est pas de l'économie</h2>
 *
 * <p>Un portail de repère ({@link Gate}) est du même bois qu'un repère : un nom, un lieu, une teinte,
 * un propriétaire, un partage. Le loger dans un second fichier aurait doublé la sauvegarde, doublé les
 * chemins de migration, et surtout ouvert la porte à ce que les deux se désaccordent — un serveur
 * restauré à moitié, un carnet d'une époque et des portails d'une autre.
 *
 * <p>Un seul fichier veut dire une seule version, une seule écriture, et l'impossibilité qu'une moitié
 * survive à l'autre. Le champ {@code gates} est <b>facultatif</b> à la lecture : un monde sauvegardé
 * avant l'existence des portails se relit sans rien perdre.
 */
public class Atlas extends SavedData {
    private static final String KEY = "lanterne_waypoints";

    /**
     * Un plafond de sécurité, et non une règle de jeu.
     *
     * <p>Les portails ne sont pas contingentés comme les repères : un repère est gratuit, donc il faut
     * en limiter le nombre ; un portail coûte dix blocs d'obsidienne et un cœur, et <b>ce prix est son
     * quota</b>. Ce nombre-ci ne sert qu'à borner ce qu'un serveur écrit sur disque et envoie sur le
     * réseau, pour qu'une boucle de construction automatisée ne puisse pas faire enfler la sauvegarde
     * sans fin. Un serveur humain ne le verra jamais.
     */
    public static final int GATE_CEILING = 512;

    public static final Codec<Atlas> CODEC = RecordCodecBuilder.create(spec -> spec.group(
            Waypoint.CODEC.listOf().fieldOf("waypoints").forGetter(atlas -> atlas.marks),
            Gate.CODEC.listOf().optionalFieldOf("gates", List.of()).forGetter(atlas -> atlas.gates)
    ).apply(spec, Atlas::new));

    public static final SavedDataType<Atlas> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(Lanterne.ID, KEY), Atlas::new, CODEC);

    private final List<Waypoint> marks;
    private final List<Gate> gates;

    public Atlas() {
        this.marks = new ArrayList<>();
        this.gates = new ArrayList<>();
    }

    private Atlas(List<Waypoint> loaded, List<Gate> loadedGates) {
        this.marks = new ArrayList<>(loaded);
        this.gates = new ArrayList<>(loadedGates);
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

    /** Envoie à ce joueur le carnet et les portails tels qu'il a le droit de les voir. */
    public void syncTo(ServerPlayer player) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new WaypointSync(visibleTo(player.getUUID())));
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new GateSync(gatesVisibleTo(player.getUUID())));
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

    // ------------------------------------------------------------------------------------------
    // Les portails
    // ------------------------------------------------------------------------------------------

    /** Le portail dont le cœur est à cette position, s'il y en a un. */
    public Gate gateAtHeart(Identifier dimension, BlockPos heart) {
        for (Gate gate : gates) {
            if (gate.dimension().equals(dimension) && gate.heart().equals(heart)) {
                return gate;
            }
        }
        return null;
    }

    /**
     * Le portail dont la nappe recouvre ce bloc.
     *
     * <p>Un parcours linéaire, et c'est voulu : un serveur compte des dizaines de portails, pas des
     * millions, et la question n'est posée qu'au moment où une entité entre dans un portail — soit
     * quelques fois par minute sur un serveur animé. Une table d'index par chunk coûterait plus à tenir
     * à jour qu'elle ne ferait gagner, et elle pourrait mentir ; cette boucle, non.
     */
    public Gate gateAtBlock(Identifier dimension, BlockPos pos) {
        for (Gate gate : gates) {
            if (gate.lit() && gate.dimension().equals(dimension) && gate.contains(pos)) {
                return gate;
            }
        }
        return null;
    }

    public Gate gate(UUID id) {
        for (Gate gate : gates) {
            if (gate.id().equals(id)) {
                return gate;
            }
        }
        return null;
    }

    /** Tous les portails que ce joueur a le droit de voir : les siens, et les publics. */
    public List<Gate> gatesVisibleTo(UUID who) {
        List<Gate> seen = new ArrayList<>();
        for (Gate gate : gates) {
            if (gate.visibleTo(who)) {
                seen.add(gate);
            }
        }
        seen.sort(Comparator
                .comparing((Gate gate) -> gate.ownedBy(who) ? 0 : 1)
                .thenComparing(Gate::name, String.CASE_INSENSITIVE_ORDER));
        return seen;
    }

    /** @return {@code null} si le portail est inscrit, ou la raison du refus */
    public String addGate(Gate gate) {
        if (gates.size() >= GATE_CEILING) {
            return "Ce serveur a atteint son nombre de portails.";
        }
        gates.add(gate);
        setDirty();
        return null;
    }

    public void replaceGate(Gate gate) {
        for (int i = 0; i < gates.size(); i++) {
            if (gates.get(i).id().equals(gate.id())) {
                gates.set(i, gate);
                setDirty();
                return;
            }
        }
    }

    /**
     * Retire un portail, et <b>coupe tous les liens qui pointaient vers lui</b>.
     *
     * <p>C'est la moitié la plus importante de cette méthode. Un lien vers un portail disparu est une
     * destination fantôme : le joueur entre, et soit rien n'arrive — et il croit le mod cassé — soit
     * quelque chose arrive, et il apparaît dans la pierre avec ce qu'il portait. Le second cas coûte
     * un inventaire, ce qui est impardonnable pour un confort.
     */
    public void removeGate(UUID id) {
        boolean removed = gates.removeIf(gate -> gate.id().equals(id));
        for (int i = 0; i < gates.size(); i++) {
            Gate gate = gates.get(i);
            if (gate.target().isPresent() && gate.target().get().equals(id)) {
                gates.set(i, gate.withTarget(java.util.Optional.empty()));
                removed = true;
            }
        }
        if (removed) {
            setDirty();
        }
    }

    public List<Gate> allGates() {
        return List.copyOf(gates);
    }

    /** Ceux que ce joueur possède, publics ou non. */
    public List<Gate> gatesOwnedBy(UUID who) {
        List<Gate> mine = new ArrayList<>();
        for (Gate gate : gates) {
            if (gate.ownedBy(who)) {
                mine.add(gate);
            }
        }
        return mine;
    }
}
