package fr.clubcitrouille.lanterne.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La jonction silencieuse entre un mod client de vol et l'autorisation que vanilla exige pour lui.
 *
 * <h2>La demande, et pourquoi {@code /lanterne vol} seul n'y répond pas</h2>
 *
 * <p>{@code /lanterne vol} (voir {@code report.LanterneCommand}) lève {@code Abilities.mayfly} pour
 * un joueur, ce qui donne au double-saut la physique exacte du créatif — WASD à l'horizontale,
 * Espace/Maj à la verticale, sans dérive — parce que c'est littéralement le même code client que la
 * création, juste débridé en survie. Le patron du serveur utilise <b>ClubCitrouilleAutoMiner</b>
 * (dépôt séparé, 100 % client) pour voler manuellement quand ce n'est pas le bot qui pilote, et il ne
 * veut pas retaper une commande à chaque connexion : la jonction doit être automatique et discrète.
 *
 * <h2>Ce qui a été cherché au javap, et ce qui a été trouvé</h2>
 *
 * <p>NeoForge 26.3 (jar universel, {@code net/neoforged/neoforge/network/}) expose une vraie API
 * stable pour interroger, <b>côté serveur et par connexion</b>, ce qui a été négocié avec un client :
 *
 * <ul>
 *   <li>{@code ChannelAttributes} accroche trois attributs Netty à chaque
 *       {@code net.minecraft.network.Connection} — {@code CONNECTION_TYPE} (NeoForge ou vanilla),
 *       {@code COMMON_CHANNELS} et {@code ADHOC_CHANNELS} (les canaux de paquets personnalisés
 *       négociés, par protocole de connexion).</li>
 *   <li>{@code NetworkRegistry.hasChannel(Connection, ConnectionProtocol, Identifier)} en est la
 *       façade publique : elle répond « ce canal a-t-il été négocié avec ce client ? ».</li>
 *   <li>Ce mécanisme — {@code ModdedNetworkPayload}/{@code ModdedNetworkComponent}, échangés en
 *       phase de configuration — est exactement ce qui produit les messages « The following mods
 *       have version differences that were not resolved » déjà vus dans les journaux : une
 *       comparaison des <b>canaux réseau enregistrés</b>, mod par mod, pas une liste brute de tous
 *       les mods installés.</li>
 * </ul>
 *
 * <p><b>Et c'est précisément ce qui rend cette API aveugle à AutoMiner.</b> Son
 * {@code neoforge.mods.toml} porte {@code clientSideOnly = "true"} et le dit en toutes lettres dans
 * sa description : « aucun paquet propre au mod ». Une recherche dans tout son code source ne trouve
 * aucun {@code RegisterPayloadHandlersEvent}, aucun {@code PayloadRegistrar}, aucun canal enregistré
 * — le mod ne participe tout simplement jamais à la négociation que {@code ChannelAttributes}
 * observe. {@code NetworkRegistry.hasChannel(...)} appelé avec n'importe quel identifiant plausible
 * pour AutoMiner rendrait donc {@code false} pour tout le monde, y compris pour un client qui l'a
 * réellement installé — non par défaut de l'API, mais parce que le mod qu'on cherche à voir n'émet
 * rien qu'elle puisse observer. {@code net.neoforged.fml.ModList.get().isLoaded(...)}, vérifié pour
 * mémoire, ne dit d'ailleurs rien de plus : il ne renseigne que les mods du <em>processus local</em>
 * — le serveur — jamais ceux du client distant.
 *
 * <p>Il ne restait donc, pour ce mod précis, aucune API propre à interroger : pas une question de
 * fiabilité douteuse qu'il aurait fallu risquer, mais une absence de signal, confirmée en lisant le
 * code source d'AutoMiner lui-même. Bricoler une détection quand même — deviner un identifiant de
 * canal, sonder autre chose — aurait été le hack fragile que la consigne interdit. Cette classe prend
 * donc le plan de repli : elle rend {@code /lanterne vol} <b>persistant</b>.
 *
 * <h2>Le mécanisme retenu</h2>
 *
 * <p>Un opérateur qui lève {@code mayfly} pour un joueur via {@code /lanterne vol <joueur>} inscrit
 * ce joueur ici. À chaque connexion suivante, {@link #onLogin} relit ce fichier et réapplique
 * {@code mayfly} <b>sans un mot dans le tchat</b> — « discret » est le mot du patron, et une
 * annonce à chaque connexion serait le contraire de discret. Le résultat pratique est identique à
 * une détection automatique du mod : une fois activé, plus jamais besoin d'y repenser.
 *
 * <h2>Pourquoi un fichier sous {@code config/lanterne/}, et pas le {@code .toml}</h2>
 *
 * <p>Ce n'est pas un réglage global du serveur, c'est une donnée <b>par joueur</b> — comme le
 * catalogue de tableaux d'{@code Easel} ou le carnet de repères d'{@code Atlas}, mais sans les
 * complications des deux : pas de contenu binaire à mettre en cache comme le premier, pas besoin de
 * survivre à une restauration de sauvegarde de monde comme le second — {@code mayfly} n'est pas une
 * donnée de partie, c'est une autorisation de connexion. Un {@code Properties} plat, au même endroit
 * que les autres fichiers du mod, suffit et reste lisible à la main par un opérateur pressé.
 */
public final class Envol {
    private static final String FILE_COMMENT =
            "Lanterne - joueurs autorises a voler en survie (mayfly), reappliques a chaque connexion. "
            + "Ecrit par /lanterne vol ; supprimer une ligne ici retire l'autorisation au prochain "
            + "demarrage.";

    /** Verrou unique : les connexions et les commandes tournent toutes sur le fil du serveur, mais
     * un chargement paresseux mérite d'être protégé contre un double déclenchement accidentel. */
    private static final Object LOCK = new Object();

    /** {@code null} tant que rien n'a encore demandé le fichier — chargé à la première utilisation. */
    private static Set<UUID> granted;

    private Envol() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(Envol.class);
    }

    /** Ce joueur a-t-il une autorisation de vol qui doit survivre à la reconnexion ? */
    public static boolean allowed(UUID id) {
        synchronized (LOCK) {
            return loaded().contains(id);
        }
    }

    /**
     * Inscrit ou retire ce joueur du fichier, et réécrit le fichier si l'état a changé.
     *
     * <p>Appelée par {@code /lanterne vol} : le geste manuel d'un opérateur devient, à partir de cet
     * appel, une autorisation qui n'a plus besoin d'être retapée.
     */
    public static void setAllowed(UUID id, boolean allow) {
        synchronized (LOCK) {
            Set<UUID> ids = loaded();
            boolean changed = allow ? ids.add(id) : ids.remove(id);
            if (changed) {
                writeFile(ids);
            }
        }
    }

    /**
     * Réapplique {@code mayfly} à la connexion, sans un mot dans le tchat.
     *
     * <p>Le test {@code abilities.mayfly} évite d'appeler {@code onUpdateAbilities()} pour rien
     * quand l'autorisation était déjà accordée par ailleurs (le créatif, par exemple) — un paquet de
     * moins à chaque connexion d'un joueur qui n'en avait pas besoin.
     */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        reapply(player);
    }

    /**
     * Un changement de mode de jeu remet {@code mayfly} à sa valeur par défaut du nouveau mode
     * (vrai en créatif/spectateur, faux sinon) — {@code GameType.updatePlayerAbilities}, appelé sans
     * condition par vanilla à chaque bascule, y compris {@code /gamemode survival} tapée par le
     * joueur lui-même. C'est exactement ce qui s'est produit en test réel le 20/09/2026 : un joueur
     * autorisé par {@code /lanterne vol}, qui volait encore en survie après être repassé en survie
     * depuis le créatif, s'est fait éjecter par l'anti-triche vanilla (« kicked for floating too
     * long! ») quelques secondes après le changement de mode — {@link #onLogin} seul ne rattrape
     * qu'une reconnexion, jamais un changement de mode en cours de partie.
     *
     * <p>L'ordre exact entre ce que NeoForge poste ici et l'écriture de {@code abilities.mayfly} par
     * vanilla n'a pas été vérifié au bytecode dans cet environnement (pas de source décompilée sous
     * la main pour {@code ServerPlayer.setGameMode}) — donc plutôt qu'un pari sur cet ordre, cette
     * méthode réapplique {@code mayfly} de toute façon si besoin, sans condition sur l'ordre. Le
     * filet de sécurité réel reste {@link #onServerTick} ci-dessous, qui rattrape n'importe quel
     * chemin de remise à zéro que ce gestionnaire n'aurait pas anticipé, largement sous la seconde.
     */
    @SubscribeEvent
    public static void onGameModeChange(PlayerEvent.PlayerChangeGameModeEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        reapply(player);
    }

    /**
     * Filet de sécurité : une fois par seconde, réapplique {@code mayfly} à tout joueur en ligne
     * autorisé dont l'autorisation aurait été perdue par un chemin non anticipé ci-dessus (respawn,
     * un autre mod, une commande vanilla non couverte). Une seconde reste largement sous les ~4
     * secondes de vol continu que l'anti-triche vanilla tolère avant d'éjecter — voir la Javadoc de
     * {@link #onGameModeChange}. Le coût est négligeable : cette boucle ne touche que les joueurs
     * déjà dans {@link #granted}, un ensemble de confiance, jamais toute la liste des joueurs.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0) {
            return;
        }
        Set<UUID> ids;
        synchronized (LOCK) {
            ids = loaded();
        }
        if (ids.isEmpty()) {
            return;
        }
        for (UUID id : ids) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(id);
            if (player != null) {
                reapply(player);
            }
        }
    }

    private static void reapply(ServerPlayer player) {
        if (!allowed(player.getUUID())) {
            return;
        }
        var abilities = player.getAbilities();
        if (abilities.mayfly) {
            return;
        }
        abilities.mayfly = true;
        player.onUpdateAbilities();
    }

    // --- Fichier -------------------------------------------------------------------------------

    private static Path file() {
        return Path.of("").toAbsolutePath()
                .resolve("config").resolve("lanterne").resolve("vol.properties");
    }

    private static Set<UUID> loaded() {
        if (granted == null) {
            granted = readFile();
        }
        return granted;
    }

    private static Set<UUID> readFile() {
        Set<UUID> ids = new HashSet<>();
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return ids;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        } catch (IOException problem) {
            Lanterne.LOG.warn("[VOL] fichier d'autorisations illisible : {}", problem.getMessage());
            return ids;
        }
        for (String key : properties.stringPropertyNames()) {
            if (!"true".equalsIgnoreCase(properties.getProperty(key))) {
                continue; // une ligne mise a "false" a la main reste lisible sans etre reprise
            }
            try {
                ids.add(UUID.fromString(key));
            } catch (IllegalArgumentException malformed) {
                Lanterne.LOG.warn("[VOL] identifiant illisible ignoré : {}", key);
            }
        }
        return ids;
    }

    private static void writeFile(Set<UUID> ids) {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException problem) {
            Lanterne.LOG.warn("[VOL] dossier {} impossible à créer : {}",
                    path.getParent(), problem.getMessage());
            return;
        }
        Properties properties = new Properties();
        for (UUID id : ids) {
            properties.setProperty(id.toString(), "true");
        }
        try (OutputStream out = Files.newOutputStream(path)) {
            properties.store(out, FILE_COMMENT);
        } catch (IOException problem) {
            Lanterne.LOG.warn("[VOL] fichier d'autorisations non écrit : {}", problem.getMessage());
        }
    }
}
