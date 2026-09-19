package fr.clubcitrouille.lanterne.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.network.MecheChunk;
import fr.clubcitrouille.lanterne.core.network.MecheManifest;

/**
 * La mèche : le mod se renouvelle lui-même, plutôt que de laisser un joueur tourner avec un jar
 * périmé sans le savoir.
 *
 * <h2>Ce que ce module fait, en une phrase</h2>
 *
 * <p>À la connexion, le serveur annonce l'empreinte du jar qu'il fait tourner. Un client dont
 * l'empreinte diffère — et qui y a consenti, voir {@code client.MecheConsent} — le redemande, le
 * reçoit par tranches sur CE MÊME canal réseau, vérifie l'empreinte reçue avant d'y toucher, et
 * l'écrit dans son dossier {@code mods/} sous un nom qui ne peut pas entrer en collision avec le jar
 * en cours d'exécution. Voir {@code client.MecheClient} pour la suite : jamais de remplacement à
 * chaud, jamais d'exécution du jar reçu — seulement un message qui dit au joueur de relancer.
 *
 * <h2>Pourquoi passer par le canal de jeu plutôt que par une adresse distante</h2>
 *
 * <p>{@code content.painting.Reach} télécharge depuis une URL parce que l'image vient d'ailleurs — un
 * lien que le joueur a lui-même posé. Ici, le fichier vient du serveur auquel on est déjà connecté :
 * il n'y a donc AUCUNE raison de rouvrir une connexion vers une autre adresse. Faire transiter le jar
 * sur la connexion de jeu, exactement comme {@code content.painting.PaintingShard} fait transiter les
 * images, donne une garantie que la sécurité d'une URL ne peut pas donner par construction : ce qu'on
 * reçoit vient FORCÉMENT du serveur auquel on a choisi de se connecter, jamais d'un tiers.
 *
 * <h2>Pourquoi c'est un « ajout », pas un module de performance</h2>
 *
 * <p>Ce module ne touche à rien qui se mesure au banc — il ne change ni un tick, ni une image. Son
 * interrupteur n'obéit donc PAS au maître : voir {@link Settings#meche()}, et la même raison que
 * {@link Settings#tide()} donne pour elle-même. Un banc qui bascule le maître vingt fois par mesure ne
 * doit pas, au passage, armer et désarmer un téléchargement.
 *
 * <h2>Preuve de concept : Lanterne se met à jour lui-même</h2>
 *
 * <p>{@link #MANAGED} ne contient aujourd'hui que {@code Lanterne.ID}. Étendre à d'autres mods
 * clients (Autominer, par exemple) demanderait que ce mod porte AUSSI leur jar de référence quelque
 * part — un dossier serveur dédié, par exemple {@code config/lanterne/mods-clients/} — puisque
 * contrairement à Lanterne lui-même, le serveur ne les fait pas nécessairement tourner. C'est une
 * extension raisonnable, documentée ici pour qui la reprendra, et volontairement hors du périmètre de
 * ce soir.
 */
public final class Meche {
    /** Les mods que ce mécanisme sait offrir en mise à jour. Voir la Javadoc de classe. */
    private static final java.util.Set<String> MANAGED = java.util.Set.of(Lanterne.ID);

    /** Tranches envoyées par tick de serveur, par joueur en cours de transfert. */
    private static final int SHARDS_PER_TICK = 4;

    /**
     * Taille d'une tranche, en octets — même valeur que {@code content.painting.Studio}'s default,
     * reprise sans qu'il y ait besoin d'un réglage séparé : ce mécanisme sert un seul fichier, connu à
     * l'avance, pas un catalogue dont la taille varierait avec les réglages d'un administrateur.
     */
    private static final int SHARD_BYTES = 24576;

    /** Ce qu'on sait d'un jar de mod chargé : son chemin, sa version, son empreinte, sa taille. */
    public record JarInfo(String modId, Path path, String version, byte[] sha256, int size) {}

    /** Calculé une fois par identifiant de mod — le jar ne change pas pendant une session. */
    private static final Map<String, JarInfo> INFO_CACHE = new ConcurrentHashMap<>();

    /** Les octets du jar, mis en cache côté serveur seulement — voir {@link #ownJarBytes}. */
    private static final Map<String, byte[]> BYTES_CACHE = new ConcurrentHashMap<>();

    /** Un transfert en cours vers un joueur : un seul à la fois, comme {@code Easel.Carrier}. */
    private static final class Carrier {
        private String modId;
        private byte[] data;
        private int offset;
    }

    private static final Map<UUID, Carrier> CONVOYS = new ConcurrentHashMap<>();

    private Meche() {}

    /** Ce mécanisme gère-t-il ce mod ? Voir {@link #MANAGED}. */
    public static boolean managed(String modId) {
        return MANAGED.contains(modId);
    }

    /** Les identifiants de mod gérés — pour l'annonce à la connexion. Voir {@link #MANAGED}. */
    public static java.util.Set<String> managedIds() {
        return MANAGED;
    }

    /**
     * Le jar de {@code modId}, tel qu'il tourne sur CETTE machine — serveur ou client, le code est
     * identique des deux côtés.
     *
     * <p>{@link ModList#getModFileById(String)} plutôt qu'un chemin construit : voir la Javadoc de
     * {@code compat.RecettesEmbarquees.jar()}, qui a déjà tiré cette leçon — le jar peut s'appeler
     * n'importe comment, vivre dans un dossier {@code mods} déplacé, et en développement n'être qu'un
     * dossier de classes compilées plutôt qu'un jar.
     *
     * @return {@code null} si le mod n'est pas chargé ou si son fichier est illisible — jamais une
     *     exception : un défaut de lecture ne doit casser ni un login, ni un démarrage.
     */
    public static JarInfo describeOwnJar(String modId) {
        JarInfo cached = INFO_CACHE.get(modId);
        if (cached != null) {
            return cached;
        }
        try {
            IModFileInfo fileInfo = ModList.get().getModFileById(modId);
            if (fileInfo == null) {
                return null;
            }
            var file = fileInfo.getFile();
            Path path = file.getFilePath();
            if (path == null || !Files.isRegularFile(path)) {
                // En développement, le « jar » est un dossier de classes : pas de fichier unique à
                // hacher, donc pas de mécanisme de mise à jour possible. Ce n'est pas une panne.
                return null;
            }
            String version = file.getModInfos().isEmpty() ? ""
                    : file.getModInfos().get(0).getVersion().toString();
            long size = Files.size(path);
            if (size <= 0 || size > Integer.MAX_VALUE) {
                return null;
            }
            byte[] sha256 = sha256(path);
            JarInfo info = new JarInfo(modId, path, version, sha256, (int) size);
            INFO_CACHE.put(modId, info);
            return info;
        } catch (Throwable unreadable) {
            Lanterne.LOG.warn("[MÈCHE] jar de « {} » illisible : {}", modId, unreadable.toString());
            return null;
        }
    }

    /**
     * L'empreinte SHA-256 d'un fichier, lu par blocs plutôt que chargé entier en mémoire.
     *
     * <p>Publique : {@code client.MecheClient} s'en sert pour vérifier un jar déjà posé dans
     * {@code mods/} avant de redemander un téléchargement qu'une reconnexion précédente avait déjà
     * mené à bien — voir sa Javadoc.
     */
    public static byte[] sha256(Path file) throws java.io.IOException {
        MessageDigest digest = sha256Digest();
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    /** L'empreinte SHA-256 d'un tampon déjà en mémoire — le jar reçu et réassemblé, par exemple. */
    public static byte[] sha256(byte[] data) {
        return sha256Digest().digest(data);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            // SHA-256 est exigé de toute machine virtuelle Java depuis la version 1.4 — voir
            // content.painting.Mill.fingerprint, qui fait le même choix pour la même raison.
            throw new IllegalStateException("SHA-256 absent de cette machine virtuelle", impossible);
        }
    }

    // --- Côté serveur : annoncer, puis servir ------------------------------

    /** Le manifeste à envoyer à un joueur qui se connecte, ou {@code null} si rien à annoncer. */
    public static MecheManifest manifestFor(String modId) {
        JarInfo info = describeOwnJar(modId);
        if (info == null) {
            return null;
        }
        return new MecheManifest(modId, info.version(), info.sha256(), info.size());
    }

    /**
     * Les octets du jar de référence, mis en cache après la première demande — voir la Javadoc de
     * classe : ce n'est jamais plus qu'un seul fichier, connu et fixe pour toute la session.
     */
    private static byte[] ownJarBytes(String modId) {
        byte[] cached = BYTES_CACHE.get(modId);
        if (cached != null) {
            return cached;
        }
        JarInfo info = describeOwnJar(modId);
        if (info == null) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(info.path());
            BYTES_CACHE.put(modId, bytes);
            return bytes;
        } catch (Exception unreadable) {
            Lanterne.LOG.warn("[MÈCHE] lecture de « {} » impossible : {}", modId,
                    unreadable.toString());
            return null;
        }
    }

    /**
     * Un client demande le jar de {@code modId}. Voir la Javadoc de {@code MecheRequest} pour ce qu'on
     * n'y croit pas.
     */
    public static void onRequest(ServerPlayer player, String modId) {
        if (!Settings.meche() || !managed(modId) || CONVOYS.containsKey(player.getUUID())) {
            return;
        }
        byte[] data = ownJarBytes(modId);
        if (data == null) {
            return;
        }
        Carrier carrier = new Carrier();
        carrier.modId = modId;
        carrier.data = data;
        carrier.offset = 0;
        CONVOYS.put(player.getUUID(), carrier);
        Lanterne.LOG.info("[MÈCHE] {} demande « {} » ({} octets).", player.getGameProfile().name(),
                modId, data.length);
    }

    /**
     * Pousse quelques tranches par joueur en cours de transfert, une fois par tick — même cadence que
     * {@code content.painting.Easel.onServerTick}, et pour la même raison : le travail fait PENDANT UN
     * TICK est ce qui compte, pas le débit moyen.
     */
    public static void pump(MinecraftServer server) {
        if (CONVOYS.isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Carrier carrier = CONVOYS.get(player.getUUID());
            if (carrier == null) {
                continue;
            }
            for (int sent = 0; sent < SHARDS_PER_TICK; sent++) {
                int length = Math.min(SHARD_BYTES, carrier.data.length - carrier.offset);
                byte[] slice = new byte[length];
                System.arraycopy(carrier.data, carrier.offset, slice, 0, length);
                player.connection.send(new MecheChunk(
                        carrier.modId, carrier.offset, carrier.data.length, slice));
                carrier.offset += length;
                if (carrier.offset >= carrier.data.length) {
                    CONVOYS.remove(player.getUUID());
                    Lanterne.LOG.info("[MÈCHE] envoi de « {} » terminé pour {}.", carrier.modId,
                            player.getGameProfile().name());
                    break;
                }
            }
        }
    }

    public static void forget(UUID playerId) {
        CONVOYS.remove(playerId);
    }

    public static void clearAll() {
        CONVOYS.clear();
    }
}
