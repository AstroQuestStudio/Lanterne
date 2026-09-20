package fr.clubcitrouille.lanterne.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Meche;
import fr.clubcitrouille.lanterne.core.network.MecheChunk;
import fr.clubcitrouille.lanterne.core.network.MecheManifest;
import fr.clubcitrouille.lanterne.core.network.MecheRequest;

/**
 * Le côté client de {@link Meche} : reçoit le manifeste, décide s'il faut demander le jar, réassemble
 * les tranches, vérifie l'empreinte, écrit le fichier — et rien de plus. Voir la Javadoc de
 * {@link Meche} pour le mécanisme d'ensemble, et {@link MecheConsent} pour pourquoi un téléchargement
 * ne part jamais sans un « oui » explicite du joueur.
 *
 * <h2>Ce que ce fichier ne fait JAMAIS</h2>
 *
 * <ul>
 *   <li>Il n'ouvre, ne charge, ni n'exécute le jar reçu. Il l'écrit sur le disque, un point c'est
 *       tout — c'est NeoForge, au prochain lancement, qui décidera quoi en faire.</li>
 *   <li>Il ne touche JAMAIS au jar en cours d'exécution : ni suppression avant écriture complète, ni
 *       écriture sous le même nom. Le fichier neuf porte {@code <modId>-<version>.jar}, un nom que le
 *       jar actif — sous son PROPRE numéro de version — ne peut pas porter.</li>
 *   <li>Il ne contacte jamais d'adresse distante : les octets arrivent par {@link MecheChunk}, sur la
 *       connexion de jeu déjà ouverte vers le serveur auquel le joueur a choisi de se connecter.</li>
 * </ul>
 *
 * <h2>La suppression de l'ancien jar, et pourquoi elle peut échouer</h2>
 *
 * <p>Un système d'exploitation qui a un fichier ouvert pour lecture — c'est le cas du jar que la
 * machine virtuelle est en train d'exécuter — refuse souvent de le supprimer tant que le processus qui
 * le tient ne s'est pas fermé. C'est vrai sur Windows, la plateforme visée par ce mécanisme ce soir ;
 * ce n'est PAS garanti ailleurs. Ce module TENTE la suppression, et le dit honnêtement dans les deux
 * cas : réussie, un simple redémarrage suffit ; refusée, le message dit exactement quel fichier
 * supprimer à la main avant de relancer — jamais un silence qui laisserait le joueur découvrir, sans
 * prévenir, que le jeu refuse de démarrer.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class MecheClient {
    /**
     * Taille au-delà de laquelle on refuse d'allouer un tampon de réception, en octets. Même principe
     * de garde-fou que {@code content.painting.Hoard.CEILING} : un serveur — ou quelque chose qui se
     * fait passer pour lui — ne doit pas pouvoir annoncer une taille absurde et provoquer un manque de
     * mémoire chez le joueur avant même d'avoir envoyé un octet.
     */
    private static final int CEILING = 256 * 1024 * 1024;

    /** Identifiant et version : mêmes caractères qu'un nom de fichier peut porter sans risque. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.-]{1,48}");

    /** Le fil qui hache et écrit — jamais le fil réseau, jamais le fil de rendu. */
    private static final ExecutorService WORKSHOP = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Lanterne-mèche");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    /** Le téléchargement en cours, s'il y en a un. */
    private static Download active;

    /** Les mods pour lesquels on a déjà informé le joueur cette session, pour ne pas radoter. */
    private static final Set<String> TOLD = new HashSet<>();

    private static final class Download {
        private final String modId;
        private final String version;
        private final byte[] expectedHash;
        private final byte[] buffer;
        private int received;

        private Download(String modId, String version, byte[] expectedHash, int size) {
            this.modId = modId;
            this.version = version;
            this.expectedHash = expectedHash;
            this.buffer = new byte[size];
        }
    }

    private MecheClient() {}

    /** Reconnexion à un autre monde ou un autre serveur : plus rien de l'ancien ne doit traîner. */
    public static void reset() {
        active = null;
        TOLD.clear();
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    /** Le serveur vient d'annoncer l'empreinte qu'il fait tourner pour {@code manifest.modId()}. */
    public static void acceptManifest(MecheManifest manifest) {
        String modId = manifest.modId();
        String version = manifest.version();
        if (!Meche.managed(modId) || !safe(modId) || !safe(version)
                || manifest.sha256().length != MecheManifest.HASH_LENGTH) {
            Lanterne.LOG.warn("[MÈCHE] manifeste rejeté (identifiant ou version suspects).");
            return;
        }
        Meche.JarInfo local = Meche.describeOwnJar(modId);
        if (local == null) {
            return; // rien à comparer — voir la Javadoc de Meche.describeOwnJar
        }
        if (Arrays.equals(local.sha256(), manifest.sha256())) {
            Lanterne.LOG.info("[MÈCHE] « {} » à jour ({}).", modId, local.version());
            return;
        }
        Lanterne.LOG.info("[MÈCHE] « {} » : ce serveur fait tourner {}, cette machine {}.",
                modId, version, local.version());

        Path staged = FMLPaths.MODSDIR.get().resolve(fileName(modId, version));
        if (alreadyStaged(staged, manifest)) {
            Lanterne.LOG.info("[MÈCHE] « {} » {} déjà téléchargé et vérifié dans mods/.",
                    modId, version);
            WORKSHOP.submit(() -> finish(modId, version, local.path()));
            return;
        }

        if (!MecheConsent.autoUpdate()) {
            if (TOLD.add(modId)) {
                tell(Component.literal("Lanterne " + version + " est disponible sur ce serveur "
                        + "(tu as " + local.version() + "). Active « auto_maj » dans "
                        + "lanterne-maj-client.toml pour l'installer automatiquement.")
                        .withStyle(ChatFormatting.YELLOW));
            }
            return;
        }

        if (manifest.size() <= 0 || manifest.size() > CEILING) {
            Lanterne.LOG.warn("[MÈCHE] taille annoncée pour « {} » refusée : {} octets.",
                    modId, manifest.size());
            return;
        }
        active = new Download(modId, version, manifest.sha256(), manifest.size());
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new MecheRequest(modId));
        Lanterne.LOG.info("[MÈCHE] téléchargement de « {} » {} demandé.", modId, version);
    }

    /** Une tranche du jar demandé vient d'arriver. */
    public static void acceptChunk(MecheChunk chunk) {
        Download download = active;
        if (download == null || !chunk.modId().equals(download.modId)) {
            return; // téléchargement déjà terminé, abandonné, ou tranche égarée
        }
        if (chunk.size() != download.buffer.length || chunk.offset() < 0
                || chunk.data().length == 0
                || chunk.offset() + chunk.data().length > download.buffer.length) {
            Lanterne.LOG.warn("[MÈCHE] tranche incohérente pour « {} », téléchargement abandonné.",
                    download.modId);
            active = null;
            return;
        }
        System.arraycopy(chunk.data(), 0, download.buffer, chunk.offset(), chunk.data().length);
        download.received += chunk.data().length;
        if (download.received < download.buffer.length) {
            return;
        }
        active = null;
        Meche.JarInfo local = Meche.describeOwnJar(download.modId);
        Path oldPath = local == null ? null : local.path();
        WORKSHOP.submit(() -> verifyAndInstall(download, oldPath));
    }

    /**
     * Sur le fil de fond : vérifie l'empreinte reçue, écrit le jar neuf, tente de retirer l'ancien.
     *
     * <p>L'empreinte est RECALCULÉE sur les octets réellement reçus et comparée à celle annoncée par
     * le manifeste — pas à une valeur portée par les tranches elles-mêmes, qui pourraient mentir. Voir
     * la Javadoc de {@code content.painting.PaintingShard} pour la même vérification, dans l'autre
     * sens du même mécanisme.
     */
    private static void verifyAndInstall(Download download, Path oldPath) {
        byte[] actual = Meche.sha256(download.buffer);
        if (!Arrays.equals(actual, download.expectedHash)) {
            Lanterne.LOG.warn("[MÈCHE] « {} » reçu abîmé : empreinte différente.", download.modId);
            Minecraft.getInstance().execute(() -> tell(Component.literal(
                    "Téléchargement de Lanterne " + download.version + " corrompu en transit. "
                            + "Reconnecte-toi pour réessayer.").withStyle(ChatFormatting.RED)));
            return;
        }
        try {
            Path target = FMLPaths.MODSDIR.get().resolve(fileName(download.modId, download.version));
            Files.createDirectories(target.getParent());
            Files.write(target, download.buffer);
            Lanterne.LOG.info("[MÈCHE] « {} » {} écrit et vérifié : {}", download.modId,
                    download.version, target);
        } catch (Exception writeFailed) {
            Lanterne.LOG.warn("[MÈCHE] écriture de « {} » impossible : {}", download.modId,
                    writeFailed.toString());
            Minecraft.getInstance().execute(() -> tell(Component.literal(
                    "Lanterne " + download.version + " téléchargé et vérifié, mais l'écriture dans "
                            + "mods/ a échoué : " + writeFailed.getMessage())
                    .withStyle(ChatFormatting.RED)));
            return;
        }
        finish(download.modId, download.version, oldPath);
    }

    /** Tente de retirer l'ancien jar (au besoin en différé), puis dit au joueur quoi faire. */
    private static void finish(String modId, String version, Path oldPath) {
        boolean removedNow = tryRemove(oldPath);
        boolean watcherArmed = !removedNow && oldPath != null && scheduleDeferredRemoval(oldPath);
        Minecraft.getInstance().execute(() -> {
            if (removedNow) {
                tell(Component.literal("Lanterne " + version + " téléchargé, vérifié, et installé. "
                        + "Ferme le jeu et relance-le pour l'appliquer.")
                        .withStyle(ChatFormatting.GREEN));
            } else if (watcherArmed) {
                tell(Component.literal("Lanterne " + version + " téléchargé et vérifié dans mods/. "
                        + "Ferme le jeu normalement : l'ancien fichier " + oldPath.getFileName()
                        + " se supprimera tout seul quelques secondes après la fermeture, rien à "
                        + "faire à la main. Si ça ne suffit pas, supprime-le toi-même dans mods/ "
                        + "avant de relancer.").withStyle(ChatFormatting.GOLD));
            } else {
                tell(Component.literal("Lanterne " + version + " téléchargé et vérifié dans mods/. "
                        + "AVANT de relancer : ferme Minecraft, PUIS supprime toi-même l'ancien fichier "
                        + (oldPath == null ? "(introuvable)" : oldPath.getFileName().toString())
                        + " dans ton dossier mods/ — sinon le jeu refusera de démarrer (deux versions "
                        + "du même mod détectées).").withStyle(ChatFormatting.GOLD));
            }
        });
    }

    /**
     * Sur Windows, le verrou d'un jar déjà ouvert par le classloader ne tombe qu'à la fermeture
     * COMPLÈTE du processus — jamais pendant qu'il tourne. Un processus détaché, indépendant de
     * celui-ci, interroge le fichier toutes les quelques secondes et le supprime dès que le verrou
     * tombe : le joueur n'a plus qu'à fermer et relancer normalement, jamais rien à supprimer à la
     * main. Boucle plutôt qu'un délai fixe : le joueur peut fermer le jeu tout de suite comme dans
     * deux heures, et {@code ping} sert de minuterie plutôt que {@code timeout} parce que ce dernier
     * refuse de tourner sans console interactive (exactement le cas ici, l'entrée du process est un
     * pipe, pas un terminal).
     *
     * @return {@code true} si le processus de nettoyage a bien été lancé (pas s'il a réussi — ça,
     *     seul le prochain lancement de Lanterne pourra le confirmer indirectement).
     */
    private static boolean scheduleDeferredRemoval(Path oldPath) {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return false; // ailleurs, deleteIfExists() sur un fichier deja ouvert reussit deja seul
        }
        String target = oldPath.toAbsolutePath().toString();
        String script = "for /l %i in (1,1,200) do ("
                + "del /f /q \"" + target + "\" 2>nul & "
                + "if not exist \"" + target + "\" exit /b & "
                + "ping -n 4 127.0.0.1 >nul)";
        try {
            new ProcessBuilder("cmd", "/c", script)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            Lanterne.LOG.info("[MÈCHE] nettoyage différé programmé pour {} (processus détaché).",
                    oldPath);
            return true;
        } catch (Exception cannotSpawn) {
            Lanterne.LOG.warn("[MÈCHE] nettoyage différé impossible à programmer : {}",
                    cannotSpawn.toString());
            return false;
        }
    }

    /**
     * Le jar déjà posé dans {@code mods/} sous ce nom porte-t-il déjà l'empreinte annoncée ?
     *
     * <p>Couvre le cas d'une reconnexion après un téléchargement déjà mené à bien la session
     * précédente, mais dont le vieux jar n'a pas encore pu être retiré (verrouillage Windows) : sans
     * ce contrôle, chaque reconnexion retéléchargerait les mêmes octets pour rien.
     */
    private static boolean alreadyStaged(Path staged, MecheManifest manifest) {
        if (!Files.isRegularFile(staged)) {
            return false;
        }
        try {
            return Arrays.equals(Meche.sha256(staged), manifest.sha256());
        } catch (Exception unreadable) {
            return false;
        }
    }

    private static boolean tryRemove(Path oldPath) {
        if (oldPath == null) {
            return false;
        }
        try {
            Files.deleteIfExists(oldPath);
            return true;
        } catch (Exception locked) {
            Lanterne.LOG.warn("[MÈCHE] suppression de {} impossible (verrouillé tant que le jeu "
                    + "tourne) : {}", oldPath, locked.toString());
            return false;
        }
    }

    private static boolean safe(String s) {
        return s != null && !s.isEmpty() && SAFE.matcher(s).matches();
    }

    private static String fileName(String modId, String version) {
        return modId + "-" + version + ".jar";
    }

    private static void tell(Component message) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        player.sendSystemMessage(message);
    }
}
