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
        publish();
        Lanterne.LOG.info("{} doublure(s) en scène, espacées de {} blocs · dans le monde : {}",
                CAST.size(), spacing, level.players().size());
    }

    /**
     * Transmet les positions au recensement.
     *
     * <p>Redondant avec {@code level.players()} quand tout va bien, et c'est délibéré : trois
     * épreuves de conformité ont été perdues parce que les doublures n'étaient pas là où on les
     * croyait. Un instrument de mesure ne doit pas dépendre d'un chemin qu'on n'a pas vérifié.
     */
    public static void publish() {
        double[] flat = new double[CAST.size() * 2];
        for (int i = 0; i < CAST.size(); i++) {
            flat[i * 2] = CAST.get(i).getX();
            flat[i * 2 + 1] = CAST.get(i).getZ();
        }
        fr.clubcitrouille.lanterne.core.Census.setProbes(flat, CAST.size());
    }

    private static void place(MinecraftServer server, ServerLevel level, int index,
                              double x, double z) {
        // Un identifiant stable, dérivé du numéro : deux exécutions du banc produisent les mêmes
        // joueurs, et donc les mêmes décalages d'étalement. Une mesure qu'on ne peut pas refaire à
        // l'identique n'est pas une mesure.
        UUID id = UUID.nameUUIDFromBytes(("lanterne-understudy-" + index).getBytes());
        GameProfile profile = new GameProfile(id, "Doublure" + index);

        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());

        // <h2>Se placer avant d'entrer, et non après</h2>
        //
        // La première version inscrivait la doublure puis la téléportait. Le serveur la comptait
        // bien — « joueurs : 1 » — mais {@code level.players()} restait <b>vide</b> : la
        // téléportation l'avait retirée de son monde sans l'y remettre, et elle n'existait plus que
        // dans la liste globale.
        //
        // Conséquence : le recensement ne voyait aucun joueur, aucun chunk n'était classé, et
        // <b>toutes</b> les entités tombaient à la cadence maximale — y compris celles collées à
        // elle. L'épreuve de conformité l'a révélé d'une ligne : une vache lâchée à zéro bloc ne
        // tombait pas du tout.
        //
        // On pose donc la position sur le joueur avant de l'inscrire. Le serveur l'ajoute alors au
        // monde là où elle se trouve déjà, et plus rien ne l'en déloge.
        double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
        player.snapTo(x, y + 1d, z, 0f, 0f);

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

        // <h2>Inscrire au serveur ne suffit pas à entrer dans le monde</h2>
        //
        // {@code placeNewPlayer} ajoute bien le joueur à la liste globale — le serveur en comptait
        // un — mais {@code level.players()} restait vide. Le rattachement au monde passe par
        // {@code ServerLevel.addNewPlayer}, et le chemin qui l'emprunte suppose une négociation de
        // protocole qu'une doublure ne fait jamais.
        //
        // Le symptôme était spectaculaire et muet à la fois : aucun chunk recensé, toutes les
        // entités à la cadence maximale, et une vache lâchée à zéro bloc du joueur qui ne tombait
        // pas. Trois épreuves de conformité pour le voir, une ligne pour le corriger.
        if (!level.players().contains(player)) {
            level.addNewPlayer(player);
        }

        // <h2>Surtout pas spectateur</h2>
        //
        // La première version les mettait en spectateur, pour qu'ils ne déclenchent ni monstres ni
        // dégâts. C'était une faute, et elle a faussé toutes les mesures de vitesse du projet.
        //
        // Le recensement ignore les spectateurs — à juste titre : un spectateur ne justifie pas
        // qu'on simule un pays entier. Mais alors aucun chunk n'était recensé, toutes les entités
        // se retrouvaient à distance « inconnue », et le mod les ralentissait toutes au maximum,
        // y compris celles collées au joueur.
        //
        // L'épreuve de conformité l'a révélé sans ambiguïté : une vache lâchée à ZÉRO bloc du
        // joueur mettait mille deux cents ticks à tomber au lieu de trente-trois. Dans la zone
        // franche, celle qui ne doit jamais être dégradée.
        //
        // C'est exactement l'erreur reprochée au mod concurrent quelques heures plus tôt : mesurer
        // un mod dans des conditions où il ne voit pas ce qu'il verrait en vrai. Une doublure doit
        // être un joueur ordinaire, sans quoi elle ne double rien.
        player.setGameMode(GameType.SURVIVAL);
        // Invulnérable : on mesure la charge d'un joueur, pas les aléas de sa survie. Cela ne
        // change rien à ce que le serveur calcule pour lui.
        player.setInvulnerable(true);

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
        fr.clubcitrouille.lanterne.core.Census.setProbes(new double[0], 0);
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
