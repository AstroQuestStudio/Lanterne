package fr.clubcitrouille.lanterne.content.disc;

import java.io.IOException;
import java.io.InputStream;

/**
 * Le RIFF : l'entête d'un WAV, et rien de plus.
 *
 * <h2>Pourquoi celui-ci est écrit à la main et pas les autres</h2>
 *
 * <p>Un WAV n'est pas compressé : c'est une suite d'échantillons bruts précédée d'une centaine
 * d'octets qui disent combien il y en a, à quelle fréquence et sur combien de bits. Il n'y a pas de
 * décodeur à écrire — il y a un entête à lire, puis des entiers à convertir en flottants. Prendre une
 * bibliothèque pour cela aurait coûté une dépendance de plus pour cent lignes qu'on peut relire.
 *
 * <p>Le MP3, lui, est un vrai décodeur : bancs de filtres, tables de Huffman, réservoir de bits. Il
 * est délégué à JLayer, et cette asymétrie est le sujet du javadoc de {@link Press}.
 *
 * <h2>La structure, et le seul piège qu'elle contient</h2>
 *
 * <p>Un fichier RIFF est une suite de <b>morceaux</b> : quatre lettres, une taille, des données. On
 * cherche {@code fmt } puis {@code data}, et on saute tout le reste — car il y a du reste, et souvent
 * beaucoup : {@code LIST} pour les métadonnées, {@code bext} pour l'horodatage des stations de
 * travail, {@code id3 } pour les étiquettes. Lire le {@code fmt } puis supposer que les données
 * suivent immédiatement est l'erreur classique, et elle donne du bruit blanc.
 *
 * <p>L'autre piège est la taille annoncée du morceau {@code data} : un fichier écrit par un
 * enregistreur interrompu porte souvent une taille fausse — parfois {@code 0xFFFFFFFF}, parfois zéro.
 * Elle est donc bornée par ce qui reste réellement dans le fichier.
 */
public final class Riff {
    /** Échantillons entiers signés — le cas ordinaire. */
    public static final int PCM = 1;

    /** Échantillons en virgule flottante — les stations de travail en produisent. */
    public static final int FLOAT = 3;

    /** Le format est décrit par un identifiant de seize octets dont les deux premiers tranchent. */
    private static final int EXTENSIBLE = 0xFFFE;

    private Riff() {}

    /**
     * Ce que l'entête décrit.
     *
     * @param sampleRate la fréquence d'échantillonnage, en hertz.
     * @param channels le nombre de canaux tel qu'il est écrit dans le fichier.
     * @param bits le nombre de bits par échantillon.
     * @param floating vrai si les échantillons sont des flottants plutôt que des entiers.
     * @param dataBytes le nombre d'octets d'audio, borné par ce qui reste du fichier.
     */
    public record Head(int sampleRate, int channels, int bits, boolean floating, long dataBytes) {
        /** Le poids d'un échantillon multicanal, c'est-à-dire d'un instant de musique. */
        public int stride() {
            return this.channels * this.bits / 8;
        }

        public float seconds() {
            return (float) ((double) this.dataBytes / this.stride() / this.sampleRate);
        }
    }

    /**
     * Lit l'entête et laisse le flux au premier octet d'audio.
     *
     * @param weight la taille du fichier, ou une valeur négative si on ne la connaît pas. Elle sert à
     *     rattraper une taille de morceau {@code data} mensongère.
     * @throws IOException si ce n'est pas un WAV lisible. Le message est destiné à un humain.
     */
    public static Head read(InputStream source, long weight) throws IOException {
        byte[] head = new byte[12];
        if (source.readNBytes(head, 0, 12) < 12
                || !word(head, 0, "RIFF") || !word(head, 8, "WAVE")) {
            throw new IOException("ce n'est pas un WAV (signature « RIFF…WAVE » absente)");
        }
        long at = 12L;

        int format = -1;
        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        byte[] chunk = new byte[8];
        while (source.readNBytes(chunk, 0, 8) == 8) {
            at += 8L;
            long size = littleInt(chunk, 4) & 0xFFFFFFFFL;
            if (word(chunk, 0, "fmt ")) {
                if (size < 16L) {
                    throw new IOException("entête WAV tronqué");
                }
                byte[] body = new byte[(int) Math.min(size, 40L)];
                if (source.readNBytes(body, 0, body.length) < body.length) {
                    throw new IOException("entête WAV tronqué");
                }
                format = littleShort(body, 0);
                channels = littleShort(body, 2);
                sampleRate = littleInt(body, 4);
                bits = littleShort(body, 14);
                if (format == EXTENSIBLE && body.length >= 26) {
                    // Le vrai format est le début de l'identifiant de sous-format, deux octets plus
                    // loin que le nombre de bits valides et le masque de canaux.
                    format = littleShort(body, 24);
                }
                long rest = size - body.length + (size & 1L);
                skip(source, rest);
                at += body.length + rest;
            } else if (word(chunk, 0, "data")) {
                if (format < 0 || channels <= 0 || sampleRate <= 0 || bits <= 0) {
                    throw new IOException("WAV dont les données précèdent l'entête de format");
                }
                long left = weight >= 0L ? Math.max(0L, weight - at) : Long.MAX_VALUE;
                long bytes = Math.min(size, left);
                boolean floating = format == FLOAT;
                if (format != PCM && !floating) {
                    throw new IOException("WAV compressé (format " + format
                            + ") — Lanterne ne lit que le PCM et le flottant");
                }
                if (bits != 8 && bits != 16 && bits != 24 && bits != 32) {
                    throw new IOException("WAV en " + bits + " bits — attendu 8, 16, 24 ou 32");
                }
                if (floating && bits != 32) {
                    throw new IOException("WAV flottant en " + bits + " bits — seul le 32 est lu");
                }
                Head result = new Head(sampleRate, channels, bits, floating, bytes);
                if (result.seconds() <= 0.0f) {
                    throw new IOException("WAV sans données audio");
                }
                return result;
            } else {
                // Les morceaux sont alignés sur un nombre pair d'octets ; un morceau de taille impaire
                // est suivi d'un octet de bourrage que la taille ne compte pas. L'oublier décale tout
                // ce qui suit d'un octet, et le « data » n'est alors jamais trouvé.
                long jump = size + (size & 1L);
                skip(source, jump);
                at += jump;
            }
        }
        throw new IOException("WAV sans morceau « data »");
    }

    /**
     * Convertit un échantillon brut en flottant entre moins un et un.
     *
     * <p>Les huit bits sont le seul format <b>non signé</b> du RIFF : cent vingt-huit y est le
     * silence. C'est une bizarrerie historique, elle n'a pas d'explication, et l'ignorer donne un
     * signal entièrement décalé vers le haut — inaudible dans un haut-parleur, et saturé.
     */
    public static float sample(byte[] data, int at, int bits, boolean floating) {
        if (floating) {
            return Float.intBitsToFloat(littleInt(data, at));
        }
        return switch (bits) {
            case 8 -> ((data[at] & 0xFF) - 128) / 128.0f;
            // Le passage par le flottant que veut le moteur sonore est exactement inversé par la
            // formule de « ChunkedSampleByteBuf » : les seize bits d'origine ressortent intacts.
            case 16 -> (littleShort(data, at) << 16 >> 16) / 32767.5f + 1.0f / 65535.0f;
            case 24 -> ((data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8) | (data[at + 2] << 16))
                    / 8388608.0f;
            default -> littleInt(data, at) / 2147483648.0f;
        };
    }

    private static void skip(InputStream source, long count) throws IOException {
        long left = count;
        while (left > 0L) {
            long jumped = source.skip(left);
            if (jumped <= 0L) {
                if (source.read() < 0) {
                    return;
                }
                jumped = 1L;
            }
            left -= jumped;
        }
    }

    private static boolean word(byte[] data, int at, String expected) {
        for (int i = 0; i < 4; i++) {
            if (data[at + i] != expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int littleShort(byte[] data, int at) {
        return (data[at] & 0xFF) | ((data[at + 1] & 0xFF) << 8);
    }

    private static int littleInt(byte[] data, int at) {
        return (data[at] & 0xFF)
                | ((data[at + 1] & 0xFF) << 8)
                | ((data[at + 2] & 0xFF) << 16)
                | ((data[at + 3] & 0xFF) << 24);
    }
}
