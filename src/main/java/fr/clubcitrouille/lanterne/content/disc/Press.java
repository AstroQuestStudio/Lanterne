package fr.clubcitrouille.lanterne.content.disc;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.painting.Studio;

/**
 * La presse : elle vérifie un fichier audio, en lit la durée, et le convertit s'il le faut.
 *
 * <h2>Pourquoi OGG/Vorbis, et pourquoi il n'y a pas de choix</h2>
 *
 * <p>Le moteur sonore de Minecraft ne sait décoder <b>qu'un seul format</b> : Vorbis dans un
 * conteneur Ogg. Ce n'est pas une préférence, c'est tout le décodeur qu'il embarque — la classe
 * {@code JOrbisAudioStream}, construite sur la bibliothèque jorbis, et rien d'autre à côté.
 * {@code SoundBufferLibrary.getStream} l'instancie sans jamais regarder le contenu du fichier :
 * donner autre chose produit un flux de bruit ou une exception, selon la chance.
 *
 * <p>Ajouter un décodeur MP3 serait possible et ce serait une mauvaise idée : il faudrait doubler le
 * chemin de lecture depuis la lecture du fichier jusqu'au tampon OpenAL, embarquer une bibliothèque
 * de plus, et surtout refaire le mode <b>flux</b> — celui qui permet de jouer un morceau de vingt
 * minutes sans le charger en mémoire. Convertir une fois vaut mieux que décoder à chaque lecture.
 *
 * <h2>La conversion est déléguée, et c'est assumé</h2>
 *
 * <p>Lanterne n'embarque pas d'encodeur Vorbis. Il n'en existe pas en Java pur qui soit à la fois
 * correct, maintenu et raisonnablement rapide ; en embarquer un natif supposerait de livrer trois
 * binaires — Windows, Linux, macOS — pour quelques mébioctets, afin d'automatiser une opération que
 * le joueur fait une fois.
 *
 * <p>La presse cherche donc {@code ffmpeg} dans le chemin du système. S'il est là, elle s'en sert ;
 * s'il n'y est pas, elle le <b>dit</b>, avec la ligne de commande exacte à taper. Un message précis
 * vaut mieux qu'une dépendance de trois mébioctets, et infiniment mieux qu'un silence.
 *
 * <h2>Lire la durée sans décoder le fichier</h2>
 *
 * <p>La durée est obligatoire : {@code JukeboxSong} en a besoin pour savoir quand arrêter le
 * disque, et une valeur fausse laisse le jukebox tourner dans le vide ou couper au milieu.
 *
 * <p>La décoder en lisant le fichier entier prendrait une seconde par morceau. Ce n'est pas
 * nécessaire : un fichier Ogg est une suite de <em>pages</em>, et chaque page porte une « position
 * granulaire » qui est, pour du Vorbis, le numéro du dernier échantillon qu'elle contient. La
 * dernière page du fichier porte donc le nombre total d'échantillons. Il suffit de la trouver — elle
 * est dans les derniers kilo-octets — et de diviser par la fréquence d'échantillonnage, qui est dans
 * la première page. Deux lectures de quelques kilo-octets, et la durée est exacte.
 */
public final class Press {
    /** Signature d'une page Ogg. */
    private static final byte[] CAPTURE = {'O', 'g', 'g', 'S'};

    /** Fenêtre de recherche de la dernière page, en octets. Une page Ogg dépasse rarement 64 Kio. */
    private static final int TAIL = 65536;

    /** Extensions que la presse accepte de convertir. */
    private static final String[] CONVERTIBLE = {".mp3", ".wav", ".flac", ".m4a", ".aac", ".opus", ".wma"};

    /** Mémorisé une fois : chercher ffmpeg coûte la création d'un processus. */
    private static Boolean ffmpegPresent;

    private Press() {}

    /** Ce que la presse a compris d'un fichier. */
    public record Reading(int sampleRate, int channels, float seconds) {}

    public static boolean isOgg(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg");
    }

    public static boolean isConvertible(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : CONVERTIBLE) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lit les entêtes d'un fichier Ogg/Vorbis.
     *
     * @throws IOException si le fichier n'est pas un Ogg/Vorbis exploitable. Le message est destiné
     *     à un humain.
     */
    public static Reading read(Path file) throws IOException {
        long weight = Files.size(file);
        if (weight < 64L) {
            throw new IOException("fichier trop court pour être un OGG");
        }
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "r")) {
            byte[] head = new byte[Math.min(4096, (int) Math.min(weight, 4096L))];
            handle.readFully(head);
            if (!matches(head, 0)) {
                throw new IOException("ce n'est pas un conteneur OGG (signature « OggS » absente)");
            }
            // L'entête d'identification Vorbis est le tout premier paquet : 0x01 puis « vorbis ».
            int identity = indexOf(head, new byte[] {1, 'v', 'o', 'r', 'b', 'i', 's'});
            if (identity < 0) {
                throw new IOException("conteneur OGG sans flux Vorbis — Minecraft ne saurait pas le lire");
            }
            // Après la signature de sept octets : version (4), canaux (1), fréquence (4), tous en
            // petit-boutien.
            int at = identity + 7;
            if (at + 9 > head.length) {
                throw new IOException("entête Vorbis tronqué");
            }
            int channels = head[at + 4] & 0xFF;
            int sampleRate = littleInt(head, at + 5);
            if (sampleRate <= 0 || channels <= 0) {
                throw new IOException("entête Vorbis incohérent");
            }

            long granule = lastGranule(handle, weight);
            if (granule <= 0L) {
                throw new IOException("durée introuvable : aucune page finale exploitable");
            }
            float seconds = (float) granule / (float) sampleRate;
            return new Reading(sampleRate, channels, seconds);
        }
    }

    /**
     * La position granulaire de la dernière page, c'est-à-dire le nombre total d'échantillons.
     *
     * <p>On balaie la fin du fichier à rebours à la recherche de la signature. À rebours parce que
     * la dernière page est celle qui compte, et qu'une signature trouvée plus tôt donnerait une
     * durée tronquée. La fenêtre est bornée : un fichier dont la dernière page serait à plus de
     * soixante-quatre kilo-octets de la fin est pathologique, et l'échec est alors annoncé plutôt
     * que corrigé au jugé.
     */
    private static long lastGranule(RandomAccessFile handle, long weight) throws IOException {
        int window = (int) Math.min(TAIL, weight);
        byte[] tail = new byte[window];
        handle.seek(weight - window);
        handle.readFully(tail);
        for (int at = window - 27; at >= 0; at--) {
            if (!matches(tail, at)) {
                continue;
            }
            // « OggS » peut apparaître par hasard dans les données audio. Deux champs de l'entête
            // suffisent à écarter presque toutes les fausses trouvailles : la version de page vaut
            // toujours zéro, et le type d'entête est un champ de trois bits. Sans ce filtre, un
            // fichier sur quelques milliers annoncerait une durée absurde, et le jukebox
            // s'arrêterait au milieu du morceau ou tournerait des heures dans le vide.
            if (tail[at + 4] != 0 || (tail[at + 5] & 0xFF) > 7) {
                continue;
            }
            long granule = 0L;
            for (int b = 7; b >= 0; b--) {
                granule = (granule << 8) | (tail[at + 6 + b] & 0xFFL);
            }
            if (granule > 0L) {
                return granule;
            }
        }
        return 0L;
    }

    private static boolean matches(byte[] data, int at) {
        if (at + CAPTURE.length > data.length) {
            return false;
        }
        for (int i = 0; i < CAPTURE.length; i++) {
            if (data[at + i] != CAPTURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int at = 0; at + needle.length <= haystack.length; at++) {
            for (int i = 0; i < needle.length; i++) {
                if (haystack[at + i] != needle[i]) {
                    continue outer;
                }
            }
            return at;
        }
        return -1;
    }

    private static int littleInt(byte[] data, int at) {
        return (data[at] & 0xFF)
                | ((data[at + 1] & 0xFF) << 8)
                | ((data[at + 2] & 0xFF) << 16)
                | ((data[at + 3] & 0xFF) << 24);
    }

    /** Ce qu'on rapporte d'un morceau apporté par le joueur : des octets OGG, et sa durée. */
    public record Cut(byte[] ogg, float seconds) {}

    /**
     * Prend des octets quelconques et en fait un OGG/Vorbis mesuré.
     *
     * <h2>Pourquoi passer par des fichiers temporaires</h2>
     *
     * <p>{@code ffmpeg} est un programme séparé : il lit un fichier et en écrit un autre. Lui parler
     * par tubes serait possible et fragile — certains formats exigent de pouvoir revenir en arrière
     * dans le flux d'entrée, ce qu'un tube ne permet pas, et {@code ffmpeg} échoue alors avec un
     * message obscur. Deux fichiers temporaires coûtent quelques millisecondes et marchent avec tout.
     *
     * <p>Ils sont effacés dans tous les cas, y compris en cas d'échec. Un dossier temporaire qui
     * enfle à chaque tentative ratée est le genre de fuite qu'on ne remarque qu'au bout de six mois.
     *
     * @param raw les octets du fichier, tels qu'ils sont arrivés.
     * @param maxSeconds durée au-delà de laquelle on refuse.
     * @throws IOException si le format est refusé ou la conversion impossible. Le message est écrit
     *     pour un humain, et dit quoi faire.
     */
    public static Cut grind(byte[] raw, long maxBytes, int maxSeconds) throws IOException {
        if (raw.length == 0) {
            throw new IOException("fichier vide");
        }
        if (raw.length > maxBytes) {
            throw new IOException("trop lourd : " + (raw.length / 1048576L) + " Mio pour une limite de "
                    + (maxBytes / 1048576L) + " Mio");
        }

        Path work = Files.createTempFile("lanterne-", ".src");
        Path target = null;
        try {
            Files.write(work, raw);
            Path playable = work;
            if (!oggBytes(raw)) {
                if (!Studio.discConvert()) {
                    throw new IOException("ce n'est pas un OGG, et la conversion est désactivée");
                }
                if (!ffmpegAvailable()) {
                    throw new IOException("ce n'est pas un OGG/Vorbis, et ffmpeg est introuvable."
                            + " Installe ffmpeg, ou convertis le fichier toi-même");
                }
                target = Files.createTempFile("lanterne-", ".ogg");
                // Le fichier temporaire existe déjà et ffmpeg refuserait d'écrire par-dessus sans
                // le « -y » qu'il porte déjà. On le supprime quand même : certaines versions
                // s'arrêtent sur un fichier de taille nulle qu'elles prennent pour un flux cassé.
                Files.deleteIfExists(target);
                if (convert(work, target) == null) {
                    throw new IOException("ffmpeg n'a pas su convertir ce fichier");
                }
                playable = target;
            }

            Reading reading = read(playable);
            if (reading.seconds() > maxSeconds) {
                throw new IOException("trop long : " + Math.round(reading.seconds())
                        + " s pour une limite de " + maxSeconds + " s");
            }
            byte[] ogg = Files.readAllBytes(playable);
            if (ogg.length > maxBytes) {
                throw new IOException("trop lourd après conversion : " + (ogg.length / 1048576L) + " Mio");
            }
            return new Cut(ogg, reading.seconds());
        } finally {
            Files.deleteIfExists(work);
            if (target != null) {
                Files.deleteIfExists(target);
            }
        }
    }

    /** Ces octets commencent-ils par la signature d'un conteneur Ogg ? */
    public static boolean oggBytes(byte[] raw) {
        return raw.length >= 4 && raw[0] == 'O' && raw[1] == 'g' && raw[2] == 'g' && raw[3] == 'S';
    }

    // --- Conversion --------------------------------------------------------

    /** ffmpeg est-il joignable ? Testé une seule fois. */
    public static boolean ffmpegAvailable() {
        if (ffmpegPresent == null) {
            ffmpegPresent = run(List.of("ffmpeg", "-version"), 10);
        }
        return ffmpegPresent;
    }

    /**
     * Convertit un fichier quelconque en OGG/Vorbis à côté de lui.
     *
     * <p>{@code -q:a 5} vise environ 160 kbit/s en débit variable : au-dessus, la différence ne
     * s'entend plus à travers le moteur sonore de Minecraft, qui rééchantillonne et applique son
     * atténuation ; en dessous, les cymbales sifflent. {@code -vn} jette la pochette d'album qu'un
     * MP3 transporte souvent, et qui n'aurait aucun sens dans un flux audio.
     *
     * @return le fichier produit, ou {@code null} si la conversion a échoué.
     */
    public static Path convert(Path source, Path target) {
        if (!ffmpegAvailable()) {
            return null;
        }
        boolean ok = run(List.of("ffmpeg", "-y", "-loglevel", "error",
                "-i", source.toAbsolutePath().toString(),
                "-vn", "-c:a", "libvorbis", "-q:a", "5",
                target.toAbsolutePath().toString()), 600);
        if (!ok || !Files.isRegularFile(target)) {
            Lanterne.LOG.warn("[ATELIER] conversion de « {} » échouée.", source.getFileName());
            return null;
        }
        return target;
    }

    /**
     * Lance un processus et attend sa fin.
     *
     * <p>La sortie est redirigée vers le néant : ffmpeg est bavard, et recopier son journal dans
     * celui du jeu noierait tout le reste. En cas d'échec, c'est le code de retour qui parle.
     *
     * <p>Le délai n'est pas une politesse : un processus qui ne rend jamais la main bloquerait le fil
     * qui attend, et ce fil est celui qui prépare les ressources. Au-delà du délai, on tue.
     */
    private static boolean run(List<String> command, int seconds) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException missing) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        }
    }
}
