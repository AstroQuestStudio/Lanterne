package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le doublure : un vrai joueur pour le serveur, sans personne derrière l'écran.
 *
 * <h2>L'erreur de mesure qu'il corrige</h2>
 *
 * <p>La première version du laboratoire posait de simples <em>tickets de chunk</em> : assez pour que
 * le monde vive et que les entités soient simulées, et c'était le but. Une comparaison avec un mod
 * concurrent a montré que cela ne suffisait pas, et pourquoi.
 *
 * <p>Ce mod-là calcule sa dégradation à partir de {@code level.players()}. Un porteur de tickets n'y
 * figure pas : pour lui, <b>le serveur était vide</b>, et il ralentissait donc tout au maximum. Le
 * banc a conclu qu'il était deux fois meilleur que Lanterne, alors qu'il ne faisait rien du tout —
 * correctement, mais rien.
 *
 * <p>La leçon vaut au-delà du cas : <b>un banc qui ne reproduit pas les conditions réelles mesure
 * autre chose que ce qu'on croit</b>, et il le fait sans prévenir. Comparer deux mods exige que
 * l'un et l'autre voient le même monde ; ici, ils n'en voyaient pas le même.
 *
 * <h2>Ce que c'est</h2>
 *
 * <p>Un vrai {@link ServerPlayer}, inscrit dans la liste des joueurs du serveur, avec une connexion
 * qui absorbe les paquets au lieu de les transmettre. Le serveur ne fait aucune différence : il lui
 * envoie ses chunks, suit les entités autour de lui, calcule ses distances, et tout mod qui
 * interroge la liste des joueurs le voit.
 *
 * <p>C'est aussi ce qui rend possible la question que personne ne peut poser autrement :
 * <b>combien de joueurs cette machine tiendrait-elle ?</b> Cent doublures coûtent ce que coûteraient
 * cent joueurs, moins le réseau — sur une machine qui n'en héberge aucun.
 */
public final class Understudy {
    private static final List<ServerPlayer> CAST = new ArrayList<>();
    private static final List<SilentConnection> LINES = new ArrayList<>();

    /**
     * Vrai pendant l'inscription d'une doublure.
     *
     * <p>La toute première échoue autrement : à cet instant précis, aucune doublure n'est encore
     * comptée, et la vérification des charges utiles refuse donc le premier message du chargeur.
     * Le drapeau couvre exactement cette fenêtre.
     */
    private static volatile boolean entering;

    private Understudy() {}

    /**
     * Fait entrer en scène un ensemble de doublures, réparties en grille.
     *
     * <p>La grille plutôt qu'un attroupement : cent joueurs au même endroit partagent leurs chunks
     * et n'en chargent presque aucun, ce qui donnerait une mesure flatteuse et fausse. Répartis,
     * chacun charge sa région — c'est la situation coûteuse, et c'est celle qu'il faut mesurer.
     *
     * @param spacing distance entre deux doublures, en blocs
     */
    public static void enter(MinecraftServer server, ServerLevel level, int count, int spacing) {
        leave(server);

        int side = (int) Math.ceil(Math.sqrt(count));
        double origin = -(side - 1) * spacing / 2d;

        entering = true;
        try {
            for (int i = 0; i < count; i++) {
                double x = origin + (i % side) * spacing;
                double z = origin + (i / side) * spacing;
                place(server, level, i, x, z);
            }
        } finally {
            entering = false;
        }
        Lanterne.LOG.info("{} doublure(s) en scène, espacées de {} blocs.", CAST.size(), spacing);
    }

    private static void place(MinecraftServer server, ServerLevel level, int index,
                              double x, double z) {
        // Un identifiant stable, dérivé du numéro : deux exécutions du banc produisent les mêmes
        // joueurs, et donc les mêmes décalages d'étalement. Une mesure qu'on ne peut pas refaire à
        // l'identique n'est pas une mesure.
        UUID id = UUID.nameUUIDFromBytes(("lanterne-understudy-" + index).getBytes());
        GameProfile profile = new GameProfile(id, "Doublure" + index);

        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        SilentConnection line = new SilentConnection();
        line.attach();

        try {
            // Se déclarer comme un client NeoForge, et non « autre ». Sans cela, la couche réseau
            // refuse d'adresser au joueur les charges utiles des mods — « Payload placebo:
            // reload_sync_start may not be sent to the client » — et l'inscription échoue. Une
            // doublure doit ressembler à ce qu'elle double, y compris dans ce qu'elle prétend
            // comprendre.
            server.getPlayerList().placeNewPlayer(line, player,
                    new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false,
                            net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE));
        } catch (Exception refused) {
            Lanterne.LOG.warn("Doublure {} refusée : {}", index, refused.toString());
            return;
        }

        double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
        player.teleportTo(level, x, y + 1d, z, java.util.Set.of(), 0f, 0f, false);
        // Spectateur : présent pour le monde, mais ne déclenche ni apparition de monstres, ni
        // dégâts, ni faim. On mesure la charge d'un joueur, pas les aléas de sa survie.
        player.setGameMode(GameType.SPECTATOR);

        CAST.add(player);
        LINES.add(line);
    }

    /** Fait sortir toutes les doublures. */
    public static void leave(MinecraftServer server) {
        for (ServerPlayer player : CAST) {
            try {
                player.connection.disconnect(net.minecraft.network.chat.Component.literal("fin du banc"));
            } catch (Exception ignored) {
                // Une doublure qui refuse de sortir n'empêche pas le banc de finir.
            }
        }
        CAST.clear();
        LINES.clear();
    }

    public static int count() {
        return CAST.size();
    }

    /** Vrai le temps d'une inscription. */
    public static boolean entering() {
        return entering;
    }

    /** Total des paquets que le serveur a voulu envoyer aux doublures. */
    public static long packetsSent() {
        long total = 0L;
        for (SilentConnection line : LINES) {
            total += line.absorbed();
        }
        return total;
    }

    public static void resetPackets() {
        for (SilentConnection line : LINES) {
            line.resetCount();
        }
    }
}
