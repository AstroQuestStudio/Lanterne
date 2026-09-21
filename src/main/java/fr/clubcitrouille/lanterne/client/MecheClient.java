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
        fileActive = null;
        synchronized (FILE_QUEUE) {
            FILE_QUEUE.clear();
        }
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
                        + "(tu as " + local.version() + "). Active « synchro_auto » dans "
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

    /**
     * Le serveur vient d'annoncer la liste complète de ses mods (voir {@code MechePeerList}). Compare
     * à la liste locale et AFFICHE les différences — ne télécharge jamais rien : voir la Javadoc de
     * classe de {@code MechePeerList} pour pourquoi (mods tiers, aucune empreinte de référence à
     * vérifier).
     */
    public static void acceptPeerList(fr.clubcitrouille.lanterne.core.network.MechePeerList list) {
        String encoded = list.encoded();
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        java.util.Map<String, String> serverMods = new java.util.LinkedHashMap<>();
        for (String line : encoded.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue; // ligne malformee, ignoree plutot que de faire echouer tout le lot
            }
            serverMods.put(line.substring(0, eq), line.substring(eq + 1));
        }
        java.util.Map<String, String> localMods = new java.util.HashMap<>();
        try {
            for (var mod : net.neoforged.fml.ModList.get().getMods()) {
                localMods.put(mod.getModId(), mod.getVersion().toString());
            }
        } catch (Throwable tooEarly) {
            return;
        }

        java.util.List<String> missing = new java.util.ArrayList<>();
        java.util.List<String> different = new java.util.ArrayList<>();
        for (var entry : serverMods.entrySet()) {
            String local = localMods.get(entry.getKey());
            if (local == null) {
                missing.add(entry.getKey() + " (" + entry.getValue() + ")");
            } else if (!local.equals(entry.getValue())) {
                different.add(entry.getKey() + " : serveur " + entry.getValue() + ", toi " + local);
            }
        }
        if (missing.isEmpty() && different.isEmpty()) {
            Lanterne.LOG.info("[MÈCHE] liste de mods du serveur comparée : rien à signaler.");
            return;
        }
        Lanterne.LOG.info("[MÈCHE] mods absents : {} — versions differentes : {}", missing, different);
        // Information de diagnostic seulement : dans la discussion elle n'apportait que du bruit chez les
        // joueurs (BlueMap, par exemple, ne tourne que sur le serveur). Le journal la garde ; le message
        // n'apparait que sur demande : -Dlanterne.meche.liste=true.
        if (!Boolean.getBoolean("lanterne.meche.liste")) {
            return;
        }
        Minecraft.getInstance().execute(() -> {
            if (!missing.isEmpty()) {
                tell(Component.literal("Mods du serveur absents chez toi (" + missing.size() + ") : "
                        + String.join(", ", missing.subList(0, Math.min(5, missing.size())))
                        + (missing.size() > 5 ? "…" : ""))
                        .withStyle(ChatFormatting.YELLOW));
            }
            if (!different.isEmpty()) {
                tell(Component.literal("Versions différentes (" + different.size() + ") : "
                        + String.join(" ; ", different.subList(0, Math.min(3, different.size())))
                        + (different.size() > 3 ? "…" : ""))
                        .withStyle(ChatFormatting.YELLOW));
            }
        });
    }

    // --- Le catalogue : tous les mods clients publiés par le serveur ---------

    /** Un nom de fichier acceptable dans mods/ : jamais de séparateur de chemin. */
    private static final Pattern SAFE_JAR = Pattern.compile("[A-Za-z0-9_.+()\\-]{1,124}\\.jar");

    /** Les fichiers encore à télécharger, dans l'ordre, et celui qui arrive. */
    private static final java.util.ArrayDeque<fr.clubcitrouille.lanterne.core.network.MecheCatalogue.Entry>
            FILE_QUEUE = new java.util.ArrayDeque<>();
    private static FileDownload fileActive;
    private static int filesInstalled;
    private static int filesRemoved;
    private static volatile int catalogueFailures;
    /** Retraits différés à la fin de la synchronisation, et noms publiés — sous le verrou de FILE_QUEUE. */
    private static java.util.List<Path> pendingRemovals;
    private static Set<String> publishedNames;

    private static final class FileDownload {
        private final fr.clubcitrouille.lanterne.core.network.MecheCatalogue.Entry entry;
        private final byte[] buffer;
        private int received;

        private FileDownload(fr.clubcitrouille.lanterne.core.network.MecheCatalogue.Entry entry) {
            this.entry = entry;
            this.buffer = new byte[entry.size()];
        }
    }

    /** Ce que Mèche a posé lui-même dans mods/ : seuls ces fichiers-là seront retirés par oubli. */
    private static Path registry() {
        return FMLPaths.CONFIGDIR.get().resolve("lanterne-meche-registre.txt");
    }

    private static Set<String> readRegistry() {
        Set<String> names = new HashSet<>();
        try {
            if (Files.isRegularFile(registry())) {
                for (String line : Files.readAllLines(registry())) {
                    if (SAFE_JAR.matcher(line.strip()).matches()) {
                        names.add(line.strip());
                    }
                }
            }
        } catch (Exception unreadable) {
            Lanterne.LOG.warn("[MÈCHE] registre illisible : {}", unreadable.toString());
        }
        return names;
    }

    private static void writeRegistry(Set<String> names) {
        try {
            Files.write(registry(), new java.util.TreeSet<>(names));
        } catch (Exception unwritable) {
            Lanterne.LOG.warn("[MÈCHE] registre non écrit : {}", unwritable.toString());
        }
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    /**
     * Le serveur publie ses mods clients. Compare au dossier {@code mods/}, puis — si le joueur a
     * consenti — retire ce que le serveur ne veut plus et télécharge ce qui manque ou a changé.
     * Voir {@code Meche.catalogue()} côté serveur pour la forme du dossier publié.
     */
    public static void acceptCatalogue(fr.clubcitrouille.lanterne.core.network.MecheCatalogue catalogue) {
        boolean consent = MecheConsent.autoUpdate();
        WORKSHOP.submit(() -> planCatalogue(catalogue, consent));
    }

    private static void planCatalogue(
            fr.clubcitrouille.lanterne.core.network.MecheCatalogue catalogue, boolean consent) {
        try {
            Path mods = FMLPaths.MODSDIR.get();
            Set<String> published = new HashSet<>();
            java.util.List<fr.clubcitrouille.lanterne.core.network.MecheCatalogue.Entry> toGet =
                    new java.util.ArrayList<>();
            for (var entry : catalogue.files()) {
                if (!SAFE_JAR.matcher(entry.name()).matches()
                        || entry.sha256().length != MecheManifest.HASH_LENGTH
                        || entry.size() <= 0 || entry.size() > CEILING) {
                    Lanterne.LOG.warn("[MÈCHE] entrée de catalogue rejetée : {}", entry.name());
                    continue;
                }
                published.add(entry.name());
                Path local = mods.resolve(entry.name());
                if (!Files.isRegularFile(local) || !Arrays.equals(Meche.sha256(local), entry.sha256())) {
                    toGet.add(entry);
                }
            }

            java.util.List<Pattern> retired = new java.util.ArrayList<>();
            for (String pattern : catalogue.retired()) {
                retired.add(glob(pattern));
            }
            Set<String> registry = readRegistry();
            Meche.JarInfo self = Meche.describeOwnJar(Lanterne.ID);
            String selfName = self == null ? "" : self.path().getFileName().toString();
            java.util.List<Path> toRemove = new java.util.ArrayList<>();
            try (var stream = Files.list(mods)) {
                for (Path file : (Iterable<Path>) stream::iterator) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".jar") || published.contains(name) || name.equals(selfName)
                            || name.toLowerCase(java.util.Locale.ROOT).startsWith("lanterne")) {
                        continue;
                    }
                    boolean retiredByServer = retired.stream().anyMatch(p -> p.matcher(name).matches());
                    if (retiredByServer || registry.contains(name)) {
                        toRemove.add(file);
                    }
                }
            }

            // Pas de retour anticipé même sans rien à faire : applyRemovals cherche aussi les doublons
            // de modId laissés par une synchronisation interrompue.
            Lanterne.LOG.info("[MÈCHE] catalogue : à télécharger {} ; à retirer {}.",
                    toGet.stream().map(fr.clubcitrouille.lanterne.core.network.MecheCatalogue.Entry::name)
                            .toList(),
                    toRemove.stream().map(p -> p.getFileName().toString()).toList());
            if (!consent) {
                if (toGet.isEmpty() && toRemove.isEmpty()) {
                    return;
                }
                Minecraft.getInstance().execute(() -> tell(Component.literal("Ce serveur publie "
                        + toGet.size() + " mod(s) à installer et " + toRemove.size() + " à retirer. "
                        + "Active « synchro_auto » dans lanterne-maj-client.toml pour la synchronisation "
                        + "automatique.").withStyle(ChatFormatting.YELLOW)));
                return;
            }

            // Les retraits ne partent QU'APRÈS les téléchargements réussis : retirer d'abord un
            // ancien jar puis échouer à récupérer le neuf laisserait le joueur sans le mod, et
            // sans ses dépendants (voir applyRemovals).
            filesInstalled = 0;
            filesRemoved = 0;
            catalogueFailures = 0;
            synchronized (FILE_QUEUE) {
                FILE_QUEUE.clear();
                FILE_QUEUE.addAll(toGet);
                pendingRemovals = new java.util.ArrayList<>(toRemove);
                publishedNames = published;
            }
            nextOrFinish();
        } catch (Exception failed) {
            Lanterne.LOG.warn("[MÈCHE] synchronisation du catalogue échouée : {}", failed.toString());
        }
    }

    /** Demande le prochain fichier de la file, ou conclut s'il n'y en a plus. Fil du jeu. */
    private static void requestNextFile() {
        fr.clubcitrouille.lanterne.core.network.MecheCatalogue.Entry next;
        synchronized (FILE_QUEUE) {
            next = FILE_QUEUE.poll();
        }
        if (next == null) {
            return;
        }
        if (Minecraft.getInstance().getConnection() == null) {
            // Déconnecté entre-temps (une planification tardive peut repeupler la file après reset()).
            synchronized (FILE_QUEUE) {
                FILE_QUEUE.clear();
            }
            return;
        }
        fileActive = new FileDownload(next);
        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                new fr.clubcitrouille.lanterne.core.network.MecheFichierDemande(next.name()));
        Lanterne.LOG.info("[MÈCHE] fichier « {} » demandé ({} octets).", next.name(), next.size());
    }

    /** Une tranche d'un fichier du catalogue vient d'arriver. */
    public static void acceptFileChunk(fr.clubcitrouille.lanterne.core.network.MecheFichierTranche chunk) {
        FileDownload download = fileActive;
        if (download == null || !chunk.name().equals(download.entry.name())) {
            return;
        }
        if (chunk.size() != download.buffer.length || chunk.offset() < 0 || chunk.data().length == 0
                || chunk.offset() + chunk.data().length > download.buffer.length) {
            Lanterne.LOG.warn("[MÈCHE] tranche incohérente pour « {} », synchronisation abandonnée.",
                    download.entry.name());
            fileActive = null;
            catalogueFailures++;
            nextOrFinish(); // un fichier raté ne doit pas figer toute la file
            return;
        }
        System.arraycopy(chunk.data(), 0, download.buffer, chunk.offset(), chunk.data().length);
        download.received += chunk.data().length;
        if (download.received < download.buffer.length) {
            return;
        }
        fileActive = null;
        WORKSHOP.submit(() -> installFile(download));
    }

    private static void installFile(FileDownload download) {
        var entry = download.entry;
        if (!Arrays.equals(Meche.sha256(download.buffer), entry.sha256())) {
            Lanterne.LOG.warn("[MÈCHE] « {} » reçu abîmé : empreinte différente.", entry.name());
            Minecraft.getInstance().execute(() -> tell(Component.literal("« " + entry.name()
                    + " » corrompu en transit. Reconnecte-toi pour réessayer.")
                    .withStyle(ChatFormatting.RED)));
            catalogueFailures++;
            nextOrFinish();
            return;
        }
        Path part = null;
        try {
            Path mods = FMLPaths.MODSDIR.get();
            part = mods.resolve(entry.name() + ".part");
            Files.write(part, download.buffer);
            Files.move(part, mods.resolve(entry.name()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Set<String> registry = readRegistry();
            registry.add(entry.name());
            writeRegistry(registry);
            filesInstalled++;
            Lanterne.LOG.info("[MÈCHE] « {} » installé et vérifié.", entry.name());
        } catch (Exception writeFailed) {
            Lanterne.LOG.warn("[MÈCHE] écriture de « {} » impossible : {}", entry.name(),
                    writeFailed.toString());
            if (part != null) {
                try {
                    Files.deleteIfExists(part);
                } catch (Exception ignored) {
                    // Un .part n'est jamais chargé par NeoForge : au pire il traîne jusqu'au prochain essai.
                }
            }
            Minecraft.getInstance().execute(() -> tell(Component.literal("Écriture de « "
                    + entry.name() + " » impossible : " + writeFailed.getMessage())
                    .withStyle(ChatFormatting.RED)));
            catalogueFailures++;
        }
        nextOrFinish();
    }

    /** Demande le fichier suivant s'il en reste, sinon conclut. Utilisable depuis n'importe quel fil. */
    private static void nextOrFinish() {
        boolean more;
        synchronized (FILE_QUEUE) {
            more = !FILE_QUEUE.isEmpty();
        }
        if (more) {
            Minecraft.getInstance().execute(MecheClient::requestNextFile);
        } else {
            WORKSHOP.submit(MecheClient::applyRemovals);
        }
    }

    /**
     * Fin de synchronisation, sur le fil de fond : retire ce que le serveur a retiré, ainsi que tout
     * ancien jar qui déclare le même {@code modId} qu'un fichier publié sous un AUTRE nom (une mise
     * à jour de version : sans cela NeoForge refuserait de démarrer, deux jars pour un même mod).
     * Rien n'est retiré si un téléchargement a échoué : on ne retire jamais l'ancien avant d'avoir le
     * neuf en main.
     */
    private static void applyRemovals() {
        java.util.List<Path> removals;
        Set<String> published;
        synchronized (FILE_QUEUE) {
            removals = pendingRemovals == null ? new java.util.ArrayList<>()
                    : new java.util.ArrayList<>(pendingRemovals);
            published = publishedNames == null ? Set.of() : publishedNames;
            pendingRemovals = null;
        }
        int failures = catalogueFailures;
        if (failures == 0) {
            try {
                Path mods = FMLPaths.MODSDIR.get();
                Set<String> publishedIds = new HashSet<>();
                for (String name : published) {
                    publishedIds.addAll(modIdsOf(mods.resolve(name)));
                }
                publishedIds.remove(Lanterne.ID);
                Meche.JarInfo self = Meche.describeOwnJar(Lanterne.ID);
                String selfName = self == null ? "" : self.path().getFileName().toString();
                if (!publishedIds.isEmpty()) {
                    try (var stream = Files.list(mods)) {
                        for (Path file : (Iterable<Path>) stream::iterator) {
                            String name = file.getFileName().toString();
                            if (!name.endsWith(".jar") || published.contains(name)
                                    || name.equals(selfName) || removals.contains(file)
                                    || name.toLowerCase(java.util.Locale.ROOT).startsWith("lanterne")) {
                                continue;
                            }
                            Set<String> ids = new HashSet<>(modIdsOf(file));
                            ids.retainAll(publishedIds);
                            if (!ids.isEmpty()) {
                                Lanterne.LOG.info("[MÈCHE] « {} » double le(s) mod(s) {} publié(s) par le "
                                        + "serveur : retrait de l'ancien fichier.", name, ids);
                                removals.add(file);
                            }
                        }
                    }
                }
                addStaleDuplicates(mods, published, selfName, removals);
                Set<String> registry = readRegistry();
                for (Path file : removals) {
                    if (tryRemove(file) || scheduleDeferredRemoval(file)) {
                        filesRemoved++;
                    }
                    registry.remove(file.getFileName().toString());
                }
                writeRegistry(registry);
            } catch (Exception failed) {
                Lanterne.LOG.warn("[MÈCHE] retraits échoués : {}", failed.toString());
            }
        } else if (!removals.isEmpty()) {
            Lanterne.LOG.warn("[MÈCHE] {} téléchargement(s) raté(s) : retraits reportés à la prochaine "
                    + "connexion.", failures);
        }
        int installed = filesInstalled;
        int removed = filesRemoved;
        if (installed + removed + failures == 0) {
            Lanterne.LOG.info("[MÈCHE] catalogue du serveur : rien à changer.");
            return;
        }
        Minecraft.getInstance().execute(() -> tell(Component.literal("Synchronisation avec le serveur : "
                + installed + " mod(s) installé(s), " + removed + " retiré(s)"
                + (failures > 0 ? ", " + failures + " échec(s) (réessaie en te reconnectant)" : "")
                + ". Ferme le jeu et relance-le pour appliquer (les fichiers verrouillés se "
                + "suppriment tout seuls à la fermeture).")
                .withStyle(failures > 0 ? ChatFormatting.GOLD : ChatFormatting.GREEN)));
    }

    /**
     * Garde-fou « le plus récent gagne » : deux jars de {@code mods/} qui déclarent le même {@code modId}
     * empêchent NeoForge de démarrer (reste d'une mise à jour, copie manuelle). Le jar le plus ancien
     * est ajouté aux retraits, à une condition stricte : TOUS ses identifiants existent aussi, en
     * version plus récente, dans un autre jar (jamais on ne retire un jar qui apporte un mod
     * introuvable ailleurs), et toutes les versions sont lisibles. Le jar de Lanterne en cours
     * d'exécution et les fichiers publiés par le serveur ne sont jamais retirés ici.
     */
    private static void addStaleDuplicates(Path mods, Set<String> published, String selfName,
            java.util.List<Path> removals) {
        java.util.Map<Path, java.util.Map<String, String>> byJar = new java.util.LinkedHashMap<>();
        try (var stream = Files.list(mods)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                    var versions = modVersionsOf(file);
                    if (!versions.isEmpty()) {
                        byJar.put(file, versions);
                    }
                }
            }
        } catch (Exception unreadable) {
            return;
        }
        for (var entry : byJar.entrySet()) {
            Path file = entry.getKey();
            String name = file.getFileName().toString();
            if (name.equals(selfName) || published.contains(name) || removals.contains(file)) {
                continue;
            }
            boolean everyIdSuperseded = true;
            for (var id : entry.getValue().entrySet()) {
                boolean newerElsewhere = false;
                for (var other : byJar.entrySet()) {
                    if (other.getKey().equals(file) || removals.contains(other.getKey())) {
                        continue;
                    }
                    String otherVersion = other.getValue().get(id.getKey());
                    if (otherVersion != null && compareVersions(otherVersion, id.getValue()) > 0) {
                        newerElsewhere = true;
                        break;
                    }
                }
                if (!newerElsewhere) {
                    everyIdSuperseded = false;
                    break;
                }
            }
            if (everyIdSuperseded) {
                Lanterne.LOG.info("[MÈCHE] « {} » est une version plus ancienne d'un mod déjà présent : "
                        + "retrait (le plus récent gagne).", name);
                removals.add(file);
            }
        }
    }

    /** Comparaison de versions par segments : numérique si possible, sinon lexicographique. */
    static int compareVersions(String a, String b) {
        String[] left = a.split("[.+\\-_]");
        String[] right = b.split("[.+\\-_]");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            String x = i < left.length ? left[i] : "0";
            String y = i < right.length ? right[i] : "0";
            int result;
            if (x.matches("\\d{1,9}") && y.matches("\\d{1,9}")) {
                result = Integer.compare(Integer.parseInt(x), Integer.parseInt(y));
            } else {
                result = x.compareTo(y);
            }
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    /**
     * {@code modId -> version} pour chaque section {@code [[mods]]} ; vide si illisible OU si une version
     * n'est pas un nombre lisible (modèle {@code ${...}} non résolu) : dans le doute on ne compare pas.
     */
    private static java.util.Map<String, String> modVersionsOf(Path jar) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            var entry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) {
                return result;
            }
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    zip.getInputStream(entry), java.nio.charset.StandardCharsets.UTF_8))) {
                boolean inMods = false;
                String currentId = null;
                Pattern modId = Pattern.compile("^\\s*modId\\s*=\\s*[\"']([^\"']+)[\"']");
                Pattern version = Pattern.compile("^\\s*version\\s*=\\s*[\"']([^\"']+)[\"']");
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith("[")) {
                        inMods = trimmed.equals("[[mods]]");
                        currentId = null;
                    } else if (inMods) {
                        var idMatch = modId.matcher(line);
                        if (idMatch.find()) {
                            currentId = idMatch.group(1);
                        }
                        var versionMatch = version.matcher(line);
                        if (versionMatch.find() && currentId != null) {
                            String v = versionMatch.group(1);
                            if (!v.matches("\\d.*")) {
                                return new java.util.LinkedHashMap<>();
                            }
                            result.put(currentId, v);
                        }
                    }
                }
            }
        } catch (Exception unreadable) {
            return new java.util.LinkedHashMap<>();
        }
        return result;
    }

    /** Les {@code modId} déclarés dans les sections {@code [[mods]]} d'un jar ; vide si illisible. */
    private static Set<String> modIdsOf(Path jar) {
        Set<String> ids = new HashSet<>();
        try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
            var entry = zip.getEntry("META-INF/neoforge.mods.toml");
            if (entry == null) {
                return ids;
            }
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    zip.getInputStream(entry), java.nio.charset.StandardCharsets.UTF_8))) {
                boolean inMods = false;
                Pattern modId = Pattern.compile("^\\s*modId\\s*=\\s*[\"']([^\"']+)[\"']");
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith("[")) {
                        inMods = trimmed.equals("[[mods]]");
                    } else if (inMods) {
                        var matcher = modId.matcher(line);
                        if (matcher.find()) {
                            ids.add(matcher.group(1));
                        }
                    }
                }
            }
        } catch (Exception unreadable) {
            // Jar absent ou illisible : aucun identifiant, donc aucun retrait sur cette base.
        }
        return ids;
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
        // Un fichier .cmd plutôt qu'une ligne passée à « cmd /c » : les guillemets d'une ligne de
        // commande y sont mal transmis (le processus s'arrêtait aussitôt, vérifié), alors qu'un
        // fichier est lu tel quel. Il se supprime lui-même à la fin.
        String script = "@echo off\r\nset n=0\r\n:loop\r\n"
                + "del /f /q \"" + target + "\" 2>nul\r\n"
                + "if not exist \"" + target + "\" goto done\r\n"
                + "set /a n+=1\r\nif %n% GEQ 200 goto done\r\n"
                + "ping -n 4 127.0.0.1 >nul\r\ngoto loop\r\n"
                + ":done\r\n(goto) 2>nul & del \"%~f0\"\r\n";
        try {
            Path cleaner = Files.createTempFile("lanterne-meche-", ".cmd");
            Files.writeString(cleaner, script, java.nio.charset.Charset.forName(
                    System.getProperty("native.encoding", "windows-1252")));
            new ProcessBuilder("cmd", "/c", cleaner.toString())
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
