package fr.clubcitrouille.lanterne.content.disc;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import fr.clubcitrouille.lanterne.content.painting.Studio;

/**
 * La presse : elle reconnaît un fichier audio, en lit la durée, et le garde tel quel.
 *
 * <h2>On ne convertit plus : on décode</h2>
 *
 * <p>La version précédente de ce fichier raisonnait ainsi : le moteur sonore de Minecraft ne sait
 * décoder que du Vorbis, donc il faut convertir, donc il faut {@code ffmpeg}. La première prémisse
 * est vraie du <b>chemin de vanilla</b> ; les deux conclusions ne l'étaient pas, et le prix en a été
 * lourd — {@code ffmpeg} n'est installé chez presque personne, et la fonction ne marchait donc chez
 * presque personne.
 *
 * <p>Le chemin de vanilla se détourne. {@code SoundBufferLibrary} construit un {@code
 * JOrbisAudioStream} sans jamais regarder le contenu du fichier : c'est une décision prise à
 * l'aveugle, et il suffit de la prendre à sa place. Un mixin côté client — {@code
 * mixin.SoundDecodeMixin} — intercepte la construction du flux et rend, pour nos ressources et pour
 * elles seules, le décodeur qui correspond aux octets réellement présents. Voir {@link Needle}.
 *
 * <p>La presse ne touche donc plus aux octets du joueur. Un MP3 est stocké tel qu'il est arrivé,
 * étiqueté MP3 ; il est même <b>plus léger</b> qu'un Vorbis ré-encodé à partir de lui, et il n'a pas
 * subi la double perte d'une transcodification.
 *
 * <h2>Ce que coûte un décodage en Java pur</h2>
 *
 * <p>C'était l'objection sérieuse, et elle ne tient pas au calcul. Un décodeur MP3 en Java pur tourne
 * à plusieurs dizaines de fois le temps réel sur une machine de 2010 : décoder une seconde de musique
 * coûte quelques dizaines de millisecondes de <em>fil de fond</em>. Or le moteur sonore demande, par
 * source et par seconde, exactement une seconde de musique — {@code Channel.attachBufferStream}
 * dimensionne ses tampons ainsi — et il la demande depuis {@code Util.nonCriticalIoPool}, jamais
 * depuis le fil de rendu. Le budget est dépassé d'un facteur cinquante ; il n'y a pas de sujet.
 *
 * <p>Ce qui aurait coûté, en revanche, c'est l'ancienne solution : {@code ffmpeg} décodait <b>et</b>
 * ré-encodait le morceau entier avant la première note, ce qui prenait plusieurs secondes et un
 * fichier temporaire.
 *
 * <h2>Ce que la presse sait lire, et ce qu'elle refuse en le disant</h2>
 *
 * <ul>
 *   <li><b>OGG/Vorbis</b> — le chemin de vanilla, inchangé. Le mixin s'écarte.</li>
 *   <li><b>MP3</b> — JLayer, embarqué dans l'archive du mod par jarJar.</li>
 *   <li><b>WAV</b> — {@link Riff}, écrit ici : l'entête RIFF est trivial et n'a pas de décodeur.</li>
 *   <li><b>FLAC</b> — <b>refusé</b>, avec un message qui dit quoi faire. Écrire un décodeur FLAC à la
 *       main donnerait quelque chose d'à moitié juste, et un décodeur à moitié juste est pire qu'un
 *       refus : il produit du bruit au lieu d'une phrase.</li>
 *   <li><b>Le reste</b> — m4a, aac, opus, wma : refusé de la même façon, sauf si {@code ffmpeg} se
 *       trouve être installé, auquel cas {@link Detour} s'en sert. C'est une porte de sortie, pas une
 *       dépendance : aucun des formats courants n'y passe.</li>
 * </ul>
 *
 * <h2>Lire la durée sans décoder le fichier</h2>
 *
 * <p>La durée est obligatoire : {@code JukeboxSong} en a besoin pour savoir quand arrêter le disque,
 * et une valeur fausse laisse le jukebox tourner dans le vide ou couper au milieu. Elle doit de plus
 * être lisible <b>côté serveur</b>, qui n'a aucun décodeur — d'où trois lecteurs d'entêtes en Java
 * pur, sans la moindre classe cliente : celui-ci pour l'Ogg, {@link Layer} pour le MP3, {@link Riff}
 * pour le WAV.
 *
 * <p>Pour l'Ogg : un fichier est une suite de <em>pages</em>, et chaque page porte une « position
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

    /** De quoi reconnaître n'importe lequel des formats connus. */
    private static final int SNIFF = 16;

    /** Extensions qu'un joueur a le droit de déposer ou de choisir. */
    private static final String[] AUDIO =
            {".ogg", ".mp3", ".wav", ".flac", ".m4a", ".aac", ".opus", ".wma"};

    private Press() {}

    /**
     * L'étiquette d'un format : ce qu'on a reconnu dans les octets.
     *
     * <p>L'extension n'est pas décorative. Les morceaux vivent dans un cache nommé par l'empreinte de
     * leur contenu — {@code Groove.cachedFile} — et un fichier {@code a1b2c3.ogg} qui contiendrait du
     * MP3 serait un mensonge sur le disque du joueur, de ceux qui coûtent une demi-journée à
     * quelqu'un dans deux ans. L'étiquette voyage donc avec les octets, dans le nom du fichier.
     */
    public enum Grain {
        OGG(".ogg", "OGG/Vorbis"),
        MP3(".mp3", "MP3"),
        WAV(".wav", "WAV"),
        FLAC(".flac", "FLAC"),
        OTHER("", "format inconnu");

        private final String extension;
        private final String label;

        Grain(String extension, String label) {
            this.extension = extension;
            this.label = label;
        }

        public String extension() {
            return this.extension;
        }

        public String label() {
            return this.label;
        }

        /** Le mod sait-il jouer ce format sans aide extérieure ? */
        public boolean playable() {
            return this == OGG || this == MP3 || this == WAV;
        }
    }

    /** Les formats qu'un fichier du cache peut porter. Sert à retrouver un morceau par empreinte. */
    public static final Grain[] KEPT = {Grain.OGG, Grain.MP3, Grain.WAV};

    /** Ce que la presse a compris d'un fichier. */
    public record Reading(int sampleRate, int channels, float seconds) {}

    /**
     * Ce qu'on rapporte d'un morceau apporté par le joueur.
     *
     * @param data les octets à garder — ceux de la source, sauf détour par {@code ffmpeg}.
     * @param grain leur format.
     * @param seconds la durée mesurée sur ces octets-là.
     * @param converted vrai si {@link Detour} est passé par là. C'est la seule situation où les
     *     octets gardés diffèrent de ceux qu'on a reçus, et donc la seule où il faille se souvenir de
     *     la correspondance. Voir {@code Wheel.remember}.
     */
    public record Cut(byte[] data, Grain grain, float seconds, boolean converted) {}

    // --- Reconnaissance ----------------------------------------------------

    /**
     * Le format que ces premiers octets annoncent.
     *
     * <p>On lit la signature et jamais l'extension. Un fichier renommé {@code .ogg} qui contiendrait
     * du MP3 doit se jouer quand même — c'est la situation la plus courante du monde chez un joueur
     * qui a téléchargé sa musique — et un fichier {@code .mp3} qui contiendrait du texte doit être
     * refusé tout de suite, pas trois écrans plus loin.
     *
     * <p>Le MP3 est le seul à ne pas avoir de signature propre : il commence soit par une étiquette
     * ID3v2, soit directement par une synchronisation de trame, c'est-à-dire onze bits à un. Les deux
     * sont acceptés ; {@link Layer} confirmera ou infirmera en cherchant une vraie trame.
     */
    public static Grain grain(byte[] head) {
        if (head.length >= 4) {
            if (head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') {
                return Grain.OGG;
            }
            if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
                return Grain.FLAC;
            }
            if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                return Grain.MP3;
            }
            if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) {
                return Grain.MP3;
            }
        }
        if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') {
            return Grain.WAV;
        }
        return Grain.OTHER;
    }

    /** Le format d'un fichier, lu dans ses premiers octets. */
    public static Grain grain(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(SNIFF);
            return grain(head);
        } catch (IOException unreadable) {
            return Grain.OTHER;
        }
    }

    /** Ce nom de fichier ressemble-t-il à celui d'un morceau ? */
    public static boolean audioName(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : AUDIO) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    // --- Mesure -------------------------------------------------------------

    /**
     * Lit les entêtes d'un fichier audio et en tire la durée.
     *
     * @throws IOException si le fichier n'est pas exploitable. Le message est destiné à un humain.
     */
    public static Reading read(Path file) throws IOException {
        long weight = Files.size(file);
        if (weight < 64L) {
            throw new IOException("fichier trop court pour porter de la musique");
        }
        Grain grain = grain(file);
        if (grain == Grain.OGG) {
            try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "r")) {
                byte[] head = new byte[(int) Math.min(weight, 4096L)];
                handle.readFully(head);
                int window = (int) Math.min(TAIL, weight);
                byte[] tail = new byte[window];
                handle.seek(weight - window);
                handle.readFully(tail);
                return ogg(head, tail);
            }
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 65536)) {
            return read(in, grain, weight);
        }
    }

    /** La même mesure, sur des octets déjà en mémoire. */
    public static Reading read(byte[] raw) throws IOException {
        if (raw.length < 64) {
            throw new IOException("fichier trop court pour porter de la musique");
        }
        Grain grain = grain(raw);
        if (grain == Grain.OGG) {
            byte[] head = Arrays.copyOf(raw, Math.min(raw.length, 4096));
            int window = Math.min(TAIL, raw.length);
            byte[] tail = Arrays.copyOfRange(raw, raw.length - window, raw.length);
            return ogg(head, tail);
        }
        return read(new ByteArrayInputStream(raw), grain, raw.length);
    }

    private static Reading read(InputStream source, Grain grain, long weight) throws IOException {
        return switch (grain) {
            case MP3 -> Layer.measure(source, weight);
            case WAV -> {
                Riff.Head head = Riff.read(source, weight);
                yield new Reading(head.sampleRate(), head.channels(), head.seconds());
            }
            case FLAC -> throw new IOException(refusal(Grain.FLAC));
            default -> throw new IOException(refusal(grain));
        };
    }

    /** Le message qu'on donne au joueur quand on ne sait pas lire son fichier. */
    public static String refusal(Grain grain) {
        String what = grain == Grain.FLAC
                ? "le FLAC n'est pas décodé par Lanterne"
                : "format audio non reconnu";
        return what + ". Formats lus : OGG, MP3, WAV"
                + (Detour.available() ? " — ou n'importe lequel, via le ffmpeg installé ici" : "");
    }

    /**
     * Les entêtes d'un Ogg/Vorbis, à partir de son début et de sa fin.
     *
     * <p>Deux tranches suffisent, et c'est tout l'intérêt : la fréquence est dans la première page,
     * le nombre total d'échantillons dans la dernière, et rien de ce qu'il y a entre les deux ne sert
     * à mesurer un morceau.
     */
    private static Reading ogg(byte[] head, byte[] tail) throws IOException {
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

        long granule = lastGranule(tail);
        if (granule <= 0L) {
            throw new IOException("durée introuvable : aucune page finale exploitable");
        }
        return new Reading(sampleRate, channels, (float) granule / (float) sampleRate);
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
    private static long lastGranule(byte[] tail) {
        for (int at = tail.length - 27; at >= 0; at--) {
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

    // --- Ce que le joueur apporte ------------------------------------------

    /**
     * Prend des octets quelconques et en fait un morceau étiqueté et mesuré.
     *
     * <p>Rien n'est réencodé. La seule exception est le détour par {@code ffmpeg}, qui ne sert qu'aux
     * formats dont personne ici n'a de décodeur, et seulement s'il se trouve installé.
     *
     * @param raw les octets du fichier, tels qu'ils sont arrivés.
     * @param maxBytes poids au-delà duquel on refuse.
     * @param maxSeconds durée au-delà de laquelle on refuse.
     * @throws IOException si le format est refusé ou le fichier illisible. Le message est écrit pour
     *     un humain, et dit quoi faire.
     */
    public static Cut grind(byte[] raw, long maxBytes, int maxSeconds) throws IOException {
        if (raw.length == 0) {
            throw new IOException("fichier vide");
        }
        if (raw.length > maxBytes) {
            throw new IOException("trop lourd : " + (raw.length / 1048576L) + " Mio pour une limite de "
                    + (maxBytes / 1048576L) + " Mio");
        }

        Grain grain = grain(raw);
        byte[] kept = raw;
        boolean converted = false;
        if (!grain.playable()) {
            if (!Studio.discDecode()) {
                throw new IOException("ce format n'est pas lu, et l'ouverture aux autres formats est"
                        + " désactivée sur ce serveur");
            }
            if (!Detour.available()) {
                throw new IOException(refusal(grain));
            }
            kept = Detour.toVorbis(raw);
            grain = Grain.OGG;
            converted = true;
            if (kept.length > maxBytes) {
                throw new IOException("trop lourd après conversion : " + (kept.length / 1048576L)
                        + " Mio");
            }
        }

        Reading reading = read(kept);
        if (reading.seconds() > maxSeconds) {
            throw new IOException("trop long : " + Math.round(reading.seconds())
                    + " s pour une limite de " + maxSeconds + " s");
        }
        return new Cut(kept, grain, reading.seconds(), converted);
    }
}
