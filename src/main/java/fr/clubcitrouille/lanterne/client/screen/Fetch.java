package fr.clubcitrouille.lanterne.client.screen;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import net.minecraft.client.Minecraft;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Les binaires du décodeur : téléchargés une fois, vérifiés, et posés sur le disque.
 *
 * <h2>Le partage entre ce qu'on embarque et ce qu'on télécharge</h2>
 *
 * <p>Il suit une différence de taille d'un facteur quarante, et rien d'autre.
 *
 * <p><b>Embarqué</b> — {@code org.bytedeco:javacpp} (543 Kio) et {@code org.bytedeco:ffmpeg}
 * (289 Kio). Du <b>Java pur</b> : ce sont les liaisons, pas les bibliothèques. 832 Kio au total,
 * moins que le décodeur MP3 déjà présent dans l'archive.
 *
 * <p><b>Téléchargé</b> — les vraies bibliothèques, par plateforme : 30,97 Mio pour Windows x86-64,
 * 26,96 pour Linux x86-64, 20,62 pour macOS arm64. Embarquer les cinq aurait donné une archive de
 * plus de cent cinquante mébioctets pour un mod dont l'identité est la légèreté.
 *
 * <h2>Pourquoi il n'y a PAS de chargeur de classes maison</h2>
 *
 * <p>C'est la question qui décidait de toute l'architecture, et elle a été tranchée par une épreuve
 * plutôt que par un raisonnement — voir {@code tools/EssaiLav.java}, qui tourne <b>hors de
 * Minecraft</b>.
 *
 * <p>La solution évidente serait de télécharger les jars de natifs et de les donner à un chargeur de
 * classes enfant. Elle est mauvaise ici : nos propres classes vivent dans le chargeur de NeoForge,
 * et un enfant qui devrait à la fois charger {@code org.bytedeco.**} et notre pompe à images
 * demanderait de lire notre propre code source depuis une URL {@code union://} que
 * {@code URLClassLoader} ne sait pas ouvrir. Cela marche en développement, où le mod est un dossier,
 * et casse chez le joueur, où c'est une archive. Ce dépôt connaît cette catégorie de faute.
 *
 * <p>JavaCPP offre une porte bien plus simple, et c'est elle qui a été éprouvée :
 * <b>{@code org.bytedeco.javacpp.platform.preloadpath}</b>. On extrait les bibliothèques dans un
 * dossier, on désigne le dossier, et le chargeur de natifs les y trouve — <b>sans qu'aucun jar de
 * natifs ne soit sur le chemin de classes</b>. L'épreuve décode un H.264 de 640 × 360 vers du RGBA
 * dans ces conditions exactes.
 *
 * <h2>L'empreinte est vérifiée, et elle est inscrite ici</h2>
 *
 * <p>Maven Central ne publie que du SHA-1 à côté de ses artefacts, et se fier à une empreinte servie
 * par la même machine que le fichier ne prouve rien. Les empreintes SHA-256 ci-dessous ont donc été
 * calculées une fois, à la main, sur les fichiers réellement téléchargés, et elles sont <b>dans le
 * code</b>. Un binaire qui ne correspond pas est effacé sans être extrait.
 *
 * <p>C'est la même exigence que {@code client.upscale.Deep} pose pour la bibliothèque de NVIDIA, et
 * pour la même raison : ce sont des instructions machine qu'on s'apprête à exécuter dans le
 * processus du joueur.
 */
public final class Fetch {
    /** Version des liaisons embarquées. Les binaires téléchargés doivent être les mêmes. */
    private static final String JAVACPP = "1.5.14";
    private static final String FFMPEG = "8.1.2-1.5.14";

    private static final String BASE = "https://repo1.maven.org/maven2/org/bytedeco/";

    /**
     * Empreintes SHA-256 des archives de natifs, par plateforme.
     *
     * <p>Relevées le 15 septembre 2026 sur les fichiers téléchargés depuis Maven Central. Changer de
     * version de FFmpeg oblige à les refaire : c'est voulu, et c'est le seul garde-fou qui vaille
     * quelque chose.
     *
     * <p>{@code windows-arm64} est absent de la table parce qu'il est absent de Maven Central —
     * l'artefact rend 404. Un joueur sur Windows ARM obtient donc l'ardoise, avec la bonne raison.
     */
    private static final Map<String, String[]> SEALS = Map.of(
            "windows-x86_64", new String[] {
                    "69bb4b0322aa807199485a9bf394ffe2117bb9ef5c34762b7d6567d7053087b5",
                    "67ebc3e6940add83b1b8a9dfd337a5c67556f2b72cb3225efe91a26874445c8e"},
            "linux-x86_64", new String[] {
                    "1efab617735a44529bb85daaaefabe46c8686582989c46593c36569e9786fdaa",
                    "6d0f000c4ddede3b669aa7c5c585e9b71f69b7f621fed7ab28ec01045dc336d0"},
            "linux-arm64", new String[] {
                    "30f63f17bb05cba4bdf488b77a3d9785b2ec261d4d528cf219b6c7081d20d636",
                    "c8729978c862b0e2e5643ade1b41266c5ba8c34b791f41104e5794ad3cefd0bf"},
            "macosx-x86_64", new String[] {
                    "ce2e642c0317d08f08fc97ba44b2c18d2a22a85516fdec6b9854f3a930c5127b",
                    "b5a8f5124fb2d04412bd01eb5ca469d153d1c396fd0c67a33d3f9a1501c94c0c"},
            "macosx-arm64", new String[] {
                    "75d755656d3ddafaa51c5d2d8d340c731a0bf2d742b033a37641a905aa2a7332",
                    "af57468c0bb7b2e9c8c93547e6d599e2b4faa4117118555be92675850972420c"});

    /** Où en est la préparation des binaires. */
    public enum State {
        /** Rien sur le disque, rien en cours. */
        ABSENT,
        /** Le téléchargement tourne sur un fil de fond. */
        EN_COURS,
        /** Les bibliothèques sont là et le chemin est armé. */
        PRET,
        /** Quelque chose a échoué. {@link #trouble()} dit quoi, en français. */
        ECHEC
    }

    private static final AtomicReference<State> state = new AtomicReference<>(State.ABSENT);
    private static volatile String trouble = "";
    private static volatile int percent;

    private Fetch() {}

    public static State state() {
        return state.get();
    }

    public static String trouble() {
        return trouble;
    }

    /** Avancement du téléchargement, de 0 à 100. N'a de sens que pendant {@link State#EN_COURS}. */
    public static int percent() {
        return percent;
    }

    /**
     * Le classifieur de bytedeco pour cette machine, ou {@code null}.
     *
     * <p>Rendre {@code null} n'est pas un échec à corriger : Windows ARM et Linux 32 bits n'ont
     * simplement pas de binaires publiés. Le dire clairement vaut mieux que de télécharger trente
     * mébioctets pour découvrir ensuite qu'ils ne se chargent pas.
     */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String cpu = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "arm64";
            default -> null;
        };
        if (cpu == null) {
            return null;
        }
        String family = os.contains("win") ? "windows"
                : os.contains("mac") || os.contains("darwin") ? "macosx"
                : os.contains("linux") ? "linux" : null;
        if (family == null) {
            return null;
        }
        String key = family + "-" + cpu;
        return SEALS.containsKey(key) ? key : null;
    }

    /** Le dossier où vivent les bibliothèques de cette plateforme. */
    private static Path home() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve(Lanterne.ID).resolve("ffmpeg")
                .resolve(String.valueOf(platform()));
    }

    /**
     * Arme le chemin si les bibliothèques sont déjà là, sans rien télécharger.
     *
     * <p>Appelée au démarrage du client. Elle ne fait rien d'autre que de regarder si un dossier
     * existe et de poser trois propriétés système — assez pour qu'un joueur qui a déjà téléchargé
     * retrouve son décodeur sans redemander son consentement.
     */
    public static void arm() {
        if (state.get() == State.PRET || platform() == null) {
            return;
        }
        Path marker = home().resolve("pret.txt");
        if (Files.isRegularFile(marker)) {
            point(home());
            state.set(State.PRET);
            Lanterne.LOG.info("[PROJECTION] décodeur présent dans {}", home());
        }
    }

    /**
     * Télécharge, vérifie et installe. Idempotent, et sans effet si c'est déjà fait.
     *
     * <p>Sur un fil de fond, et jamais sur le fil de rendu : trente mébioctets sur une connexion
     * ordinaire sont plusieurs secondes, et les faire passer par le fil qui dessine gèlerait le jeu.
     *
     * <p><b>Ne s'exécute que sur consentement.</b> La garde est ici et non chez l'appelant : c'est le
     * seul endroit du mod qui ouvre une connexion sortante sans qu'un joueur ait tapé une adresse,
     * et un consentement vérifié ailleurs finirait par être oublié.
     */
    public static void ensure() {
        if (!Consent.remoteAllowed()) {
            return;
        }
        String platform = platform();
        if (platform == null) {
            state.set(State.ECHEC);
            trouble = "aucun binaire publié pour " + System.getProperty("os.name") + " "
                    + System.getProperty("os.arch");
            return;
        }
        if (!state.compareAndSet(State.ABSENT, State.EN_COURS)
                && !state.compareAndSet(State.ECHEC, State.EN_COURS)) {
            return;
        }
        Thread worker = new Thread(() -> install(platform), "lanterne-decodeur");
        worker.setDaemon(true);
        // Priorité basse : ce fil décompresse soixante-dix mébioctets, et il n'a aucune raison de
        // disputer le processeur au fil qui dessine. Le joueur continue de jouer pendant ce temps.
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static void install(String platform) {
        try {
            Path home = home();
            Files.createDirectories(home);
            String[] seals = SEALS.get(platform);
            String[] urls = {
                    BASE + "javacpp/" + JAVACPP + "/javacpp-" + JAVACPP + "-" + platform + ".jar",
                    BASE + "ffmpeg/" + FFMPEG + "/ffmpeg-" + FFMPEG + "-" + platform + ".jar"};

            percent = 0;
            for (int i = 0; i < urls.length; i++) {
                Path archive = home.resolve("archive-" + i + ".jar.part");
                download(urls[i], archive);
                String seen = sha256(archive);
                if (!seen.equalsIgnoreCase(seals[i])) {
                    Files.deleteIfExists(archive);
                    throw new IOException("empreinte inattendue pour " + urls[i]
                            + " — attendu " + seals[i] + ", reçu " + seen);
                }
                unpack(archive, home);
                Files.deleteIfExists(archive);
                percent = (i + 1) * 100 / urls.length;
            }

            point(home);
            Files.writeString(home.resolve("pret.txt"),
                    "javacpp " + JAVACPP + "\nffmpeg " + FFMPEG + "\nplateforme " + platform + "\n");
            state.set(State.PRET);
            Lanterne.LOG.info("[PROJECTION] décodeur installé dans {}", home);
        } catch (Throwable problem) {
            state.set(State.ECHEC);
            trouble = problem.getMessage() == null ? problem.toString() : problem.getMessage();
            Lanterne.LOG.warn("[PROJECTION] décodeur non installé : {}", trouble);
        }
    }

    private static void download(String url, Path to) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "Lanterne")
                .GET()
                .build();
        HttpResponse<Path> response = client.send(request,
                HttpResponse.BodyHandlers.ofFile(to, java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.WRITE));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " sur " + url);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] chunk = new byte[65536];
            int read;
            while ((read = in.read(chunk)) > 0) {
                digest.update(chunk, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Extrait les bibliothèques, à plat, et rien d'autre.
     *
     * <h2>Les exécutables sont laissés dans l'archive</h2>
     *
     * <p>Le jar de FFmpeg contient {@code ffmpeg.exe} et {@code ffprobe.exe}. On ne les écrit pas :
     * ce mod n'appelle aucun programme — c'est précisément la dépendance que le dépôt a supprimée
     * ailleurs — et déposer un exécutable dans le dossier de configuration d'un joueur serait poser
     * une surface dont on n'a aucun usage.
     *
     * <p>À plat, sans reproduire l'arborescence du jar, parce que {@code preloadpath} désigne un
     * dossier et non une racine. C'est la disposition qui a été éprouvée par {@code tools/EssaiLav.java}.
     */
    private static void unpack(Path archive, Path home) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                int slash = name.lastIndexOf('/');
                String leaf = slash < 0 ? name : name.substring(slash + 1);
                if (!library(leaf)) {
                    continue;
                }
                // Le nom est réduit à sa feuille : aucun « ../ » ne peut donc sortir du dossier,
                // et la traversée de chemin par une archive forgée n'a pas de prise.
                Path target = home.resolve(leaf);
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Est-ce une bibliothèque partagée ? Le reste de l'archive ne nous intéresse pas. */
    private static boolean library(String leaf) {
        String lower = leaf.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".dll") || lower.endsWith(".dylib")) {
            return true;
        }
        // Linux : « libavcodec.so.62 » autant que « libavcodec.so ». Un test sur le suffixe seul
        // laisserait passer les premières et rejetterait les secondes, qui sont justement celles
        // que le chargeur de natifs cherche.
        return lower.contains(".so.") || lower.endsWith(".so");
    }

    /**
     * Désigne le dossier au chargeur de natifs de JavaCPP.
     *
     * <p><b>Doit être appelé avant qu'une seule classe de {@code org.bytedeco} ne soit initialisée.</b>
     * {@code Loader} lit ces propriétés dans son initialiseur statique ; les poser après ne servirait
     * à rien, et l'échec serait un {@code UnsatisfiedLinkError} sans rapport apparent avec la cause.
     *
     * <p>C'est pourquoi {@link Pump} est la seule classe du mod à nommer un type de bytedeco, et
     * qu'elle n'est instanciée que depuis {@link Lav}, après vérification de l'état.
     */
    private static void point(Path home) {
        String path = home.toAbsolutePath().toString();
        System.setProperty("org.bytedeco.javacpp.platform.preloadpath", path);
        System.setProperty("org.bytedeco.javacpp.platform.linkpath", path);
        // Les chemins d'abord : sans cela, le chargeur préfère extraire depuis le chemin de classes,
        // où il n'y a rien, et ne regarde le disque qu'en dernier recours.
        System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");
        // Le cache d'extraction va dans notre dossier plutôt que dans le dossier personnel de
        // l'utilisateur : un joueur qui efface config/lanterne doit tout effacer, pas la moitié.
        System.setProperty("org.bytedeco.javacpp.cachedir",
                home.resolve("cache").toAbsolutePath().toString());
    }

    /** Ce qu'on affiche dans l'écran de réglages pendant l'installation. */
    public static String describe() {
        return switch (state.get()) {
            case ABSENT -> platform() == null
                    ? "aucun binaire pour cette machine"
                    : "décodeur à télécharger (~31 Mio)";
            case EN_COURS -> "téléchargement… " + percent + " %";
            case PRET -> "décodeur prêt";
            case ECHEC -> "échec : " + trouble;
        };
    }
}
