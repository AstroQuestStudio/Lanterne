package fr.clubcitrouille.lanterne.content.disc;

import java.io.IOException;
import java.io.InputStream;

/**
 * La couche : tout ce qu'un MP3 dit de lui-même avant qu'on le décode.
 *
 * <h2>Pourquoi mesurer un MP3 demande plus de soin qu'un OGG</h2>
 *
 * <p>Un fichier Ogg porte sa durée : la dernière page contient le numéro du dernier échantillon, et
 * deux lectures de quelques kilo-octets suffisent — c'est ce que fait {@link Press#read}.
 *
 * <p>Un MP3 ne porte rien de tel. C'est une suite de <b>trames</b> mises bout à bout, chacune
 * précédée d'un entête de quatre octets qui donne son débit ; la durée est la somme des durées de
 * toutes les trames, et rien dans le fichier ne l'écrit. Trois chemins mènent quand même au résultat,
 * du moins cher au plus cher, et cette classe les essaie dans cet ordre.
 *
 * <ol>
 *   <li><b>L'entête Xing, Info ou VBRI.</b> Presque tous les encodeurs à débit variable glissent,
 *       dans la toute première trame, un petit bloc qui annonce le nombre total de trames. Une
 *       multiplication, et la durée est exacte pour le prix d'une seule trame lue.</li>
 *   <li><b>Le débit constant.</b> Sans ce bloc, si les premières trames partagent toutes le même
 *       débit, le fichier est à débit constant : la taille divisée par le débit donne la durée, et
 *       elle est exacte elle aussi.</li>
 *   <li><b>Le parcours complet.</b> Débit variable et pas d'entête Xing — cas rare, mais réel. Il
 *       faut alors additionner les trames une par une. On ne lit que les entêtes et on <em>saute</em>
 *       les corps : le coût est celui d'un parcours de fichier, pas d'un décodage.</li>
 * </ol>
 *
 * <p>Se contenter du deuxième chemin aurait été tentant et faux : sur un morceau encodé en débit
 * variable, la taille divisée par le débit de la première trame donne n'importe quoi — souvent le
 * double ou la moitié. Le jukebox se serait arrêté au milieu, ou aurait tourné dans le vide.
 *
 * <h2>Les étiquettes en tête de fichier</h2>
 *
 * <p>Un MP3 commence rarement par de l'audio : il commence par une étiquette ID3v2, qui porte le
 * titre, l'artiste et souvent la pochette de l'album — parfois plusieurs centaines de kilo-octets.
 * La sauter est obligatoire, sinon la recherche de la première trame tomberait sur des octets de
 * texte compressé et trouverait une fausse synchronisation.
 */
public final class Layer {
    /** Débits en kbit/s pour MPEG-1, par couche (I, II, III) puis par index. */
    private static final int[][] MPEG1_RATES = {
        {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, -1},
        {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, -1},
        {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, -1},
    };

    /** Débits en kbit/s pour MPEG-2 et MPEG-2.5, mêmes conventions. */
    private static final int[][] MPEG2_RATES = {
        {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, -1},
        {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1},
        {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, -1},
    };

    private static final int[] MPEG1_HZ = {44100, 48000, 32000};
    private static final int[] MPEG2_HZ = {22050, 24000, 16000};
    private static final int[] MPEG25_HZ = {11025, 12000, 8000};

    /**
     * Combien de trames on regarde avant de décréter que le débit est constant.
     *
     * <p>Une seconde et demie d'audio. Un encodeur à débit variable change de débit bien avant cela —
     * il le fait dès que la musique change de densité, c'est-à-dire tout le temps. Un seuil de deux
     * ou trois trames aurait pu tomber sur un passage homogène et se tromper.
     */
    private static final int PROBE = 64;

    /**
     * Borne de la recherche d'une synchronisation, en octets.
     *
     * <p>Un fichier dont la trame suivante serait à plus de soixante-quatre kilo-octets n'est pas un
     * MP3 abîmé, c'est autre chose. On arrête plutôt que de balayer un fichier entier octet par
     * octet.
     */
    private static final int DRIFT = 65536;

    private Layer() {}

    /** Ce qu'un entête de trame décrit. */
    private record Frame(int rate, int channels, int bitrate, int samples, int length) {}

    /**
     * La durée, la fréquence et le nombre de canaux d'un MP3.
     *
     * @param source les octets du fichier, depuis le tout premier.
     * @param weight la taille totale du fichier — nécessaire au chemin « débit constant », qui
     *     raisonne sur elle.
     * @throws IOException si aucune trame MP3 n'est trouvable. Le message est destiné à un humain.
     */
    public static Press.Reading measure(InputStream source, long weight) throws IOException {
        Reader reader = new Reader(source);
        skipTags(reader);

        byte[] head = new byte[4];
        Frame first = seek(reader, head, 0);
        if (first == null) {
            throw new IOException("aucune trame MP3 trouvée — fichier tronqué ou mal étiqueté");
        }
        // La position juste après l'entête de quatre octets, donc le début de l'audio utile.
        long start = reader.at() - 4L;

        // Le corps de la première trame, lu en entier : c'est là que se cache l'entête Xing.
        byte[] body = new byte[first.length() - 4];
        int got = reader.fill(body, body.length);
        long declared = declaredFrames(head, body, got);
        if (declared > 0L) {
            float seconds = (float) ((double) declared * first.samples() / first.rate());
            return new Press.Reading(first.rate(), first.channels(), seconds);
        }

        double seconds = (double) first.samples() / first.rate();
        boolean variable = false;
        int seen = 1;
        while (seen < PROBE) {
            Frame next = advance(reader, head);
            if (next == null) {
                // Le fichier entier tient en moins de PROBE trames : la somme est déjà exacte.
                return new Press.Reading(first.rate(), first.channels(), (float) seconds);
            }
            seconds += (double) next.samples() / next.rate();
            variable |= next.bitrate() != first.bitrate();
            seen++;
        }

        if (!variable) {
            double bytes = weight - start;
            return new Press.Reading(first.rate(), first.channels(),
                    (float) (bytes * 8.0d / first.bitrate()));
        }

        while (true) {
            Frame next = advance(reader, head);
            if (next == null) {
                return new Press.Reading(first.rate(), first.channels(), (float) seconds);
            }
            seconds += (double) next.samples() / next.rate();
        }
    }

    // --- Étiquettes ---------------------------------------------------------

    /**
     * Saute l'étiquette ID3v2 s'il y en a une.
     *
     * <p>La taille d'une étiquette ID3v2 est écrite sur quatre octets <b>« synchsafe »</b> : sept
     * bits utiles par octet, le huitième toujours à zéro. C'est exprès — c'est ce qui garantit
     * qu'une taille ne puisse jamais ressembler à une synchronisation de trame. Lire ces quatre
     * octets comme un entier ordinaire donnerait une taille jusqu'à seize fois trop grande, et le
     * début de la musique serait sauté avec elle.
     */
    private static void skipTags(Reader reader) throws IOException {
        byte[] probe = new byte[10];
        int got = reader.fill(probe, probe.length);
        if (got < 10 || probe[0] != 'I' || probe[1] != 'D' || probe[2] != '3') {
            reader.giveBack(probe, got);
            return;
        }
        long size = ((long) (probe[6] & 0x7F) << 21)
                | ((long) (probe[7] & 0x7F) << 14)
                | ((long) (probe[8] & 0x7F) << 7)
                | (probe[9] & 0x7F);
        // Le bit 0x10 des drapeaux annonce un pied d'étiquette de dix octets de plus, que la taille
        // ne compte pas.
        long total = 10L + size + ((probe[5] & 0x10) != 0 ? 10L : 0L);
        reader.skip(total - 10L);
    }

    /**
     * Le nombre de trames annoncé par l'entête Xing, Info ou VBRI de la première trame.
     *
     * <p>Xing et Info se placent après les octets de silence réservés par le format — dix-sept ou
     * trente-deux octets selon la version et le nombre de canaux, ce qui n'a l'air d'une convention
     * arbitraire que jusqu'à ce qu'on sache qu'ils occupent la place des données de canal
     * inutilisées. VBRI, lui, se place toujours à trente-deux octets : c'est une invention de
     * Fraunhofer, plus rare, et elle ignore la convention des autres.
     *
     * @return le nombre de trames, ou zéro si aucun entête n'est là.
     */
    private static long declaredFrames(byte[] head, byte[] body, int length) {
        boolean mpeg1 = ((head[1] >> 3) & 3) == 3;
        boolean mono = ((head[3] >> 6) & 3) == 3;
        int at = mpeg1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
        // Les décalages sont comptés depuis l'entête de quatre octets, que « body » ne contient pas.
        at -= 4;
        if (at >= 0 && at + 12 <= length
                && (tag(body, at, "Xing") || tag(body, at, "Info"))) {
            int flags = bigInt(body, at + 4);
            if ((flags & 1) != 0) {
                return bigInt(body, at + 8) & 0xFFFFFFFFL;
            }
        }
        int vbri = 32 - 4;
        if (vbri + 26 <= length && tag(body, vbri, "VBRI")) {
            return bigInt(body, vbri + 14) & 0xFFFFFFFFL;
        }
        return 0L;
    }

    private static boolean tag(byte[] data, int at, String word) {
        for (int i = 0; i < 4; i++) {
            if (data[at + i] != word.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int bigInt(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24)
                | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8)
                | (data[at + 3] & 0xFF);
    }

    // --- Trames -------------------------------------------------------------

    /**
     * Cherche le premier entête de trame valide, en glissant d'un octet à la fois.
     *
     * <p>Un octet 0xFF suivi de trois bits à un ne suffit pas : cette suite apparaît régulièrement
     * dans du texte compressé ou dans une pochette. C'est {@link #parse} qui tranche, en exigeant que
     * la version, la couche, le débit et la fréquence soient tous des valeurs déclarées — quatre
     * conditions qu'un octet de hasard ne remplit presque jamais.
     */
    private static Frame seek(Reader reader, byte[] head, int filled) throws IOException {
        for (int moved = 0; moved < DRIFT; moved++) {
            while (filled < 4) {
                int value = reader.next();
                if (value < 0) {
                    return null;
                }
                head[filled++] = (byte) value;
            }
            Frame frame = parse(head);
            if (frame != null) {
                return frame;
            }
            head[0] = head[1];
            head[1] = head[2];
            head[2] = head[3];
            filled = 3;
        }
        return null;
    }

    /** Saute le corps de la trame courante et lit l'entête de la suivante. */
    private static Frame advance(Reader reader, byte[] head) throws IOException {
        int got = reader.fill(head, 4);
        if (got < 4) {
            return null;
        }
        Frame frame = parse(head);
        if (frame == null) {
            // Une trame abîmée n'est pas la fin du morceau : on se resynchronise.
            frame = seek(reader, head, 4);
            if (frame == null) {
                return null;
            }
        }
        reader.skip(frame.length() - 4L);
        return frame;
    }

    /**
     * Décode un entête de quatre octets, ou rend {@code null} si ce n'en est pas un.
     *
     * <p>La longueur d'une trame se déduit du débit et de la fréquence, et c'est ce qui permet de
     * sauter de trame en trame sans jamais décoder : on connaît la position de la suivante avant
     * d'avoir lu un seul octet de son contenu.
     */
    private static Frame parse(byte[] head) {
        if ((head[0] & 0xFF) != 0xFF || (head[1] & 0xE0) != 0xE0) {
            return null;
        }
        int version = (head[1] >> 3) & 3;
        int layer = (head[1] >> 1) & 3;
        if (version == 1 || layer == 0) {
            return null;
        }
        int rateIndex = (head[2] >> 2) & 3;
        int bitrateIndex = (head[2] >> 4) & 0xF;
        if (rateIndex == 3 || bitrateIndex == 0 || bitrateIndex == 15) {
            return null;
        }

        boolean mpeg1 = version == 3;
        int[] hertz = mpeg1 ? MPEG1_HZ : (version == 2 ? MPEG2_HZ : MPEG25_HZ);
        int rate = hertz[rateIndex];
        // Couche 3 → index 2, couche 2 → index 1, couche 1 → index 0.
        int band = 3 - layer;
        int bitrate = (mpeg1 ? MPEG1_RATES : MPEG2_RATES)[band][bitrateIndex] * 1000;
        if (bitrate <= 0) {
            return null;
        }

        int samples = band == 0 ? 384 : (band == 1 ? 1152 : (mpeg1 ? 1152 : 576));
        int padding = (head[2] >> 1) & 1;
        int length = band == 0
                ? (12 * bitrate / rate + padding) * 4
                : samples / 8 * bitrate / rate + padding;
        if (length < 4) {
            return null;
        }
        int channels = ((head[3] >> 6) & 3) == 3 ? 1 : 2;
        return new Frame(rate, channels, bitrate, samples, length);
    }

    // --- Lecture ------------------------------------------------------------

    /**
     * Un flux qu'on peut faire reculer de quelques octets.
     *
     * <p>Le besoin est précis : pour savoir s'il y a une étiquette ID3v2, il faut lire dix octets ;
     * s'il n'y en a pas, ces dix octets sont le début de la musique et il faut pouvoir les rendre.
     * {@code PushbackInputStream} ferait la même chose, au prix d'une enveloppe de plus autour du
     * flux et sans le compteur de position dont le chemin « débit constant » a besoin.
     */
    private static final class Reader {
        private final InputStream source;
        private final byte[] spare = new byte[16];
        private int head;
        private int tail;
        private long at;

        Reader(InputStream source) {
            this.source = source;
        }

        /** Le nombre d'octets lus depuis le début du fichier. */
        long at() {
            return this.at;
        }

        int next() throws IOException {
            if (this.head < this.tail) {
                this.at++;
                return this.spare[this.head++] & 0xFF;
            }
            int value = this.source.read();
            if (value >= 0) {
                this.at++;
            }
            return value;
        }

        /** Lit exactement {@code count} octets si possible, et rend combien il en a eu. */
        int fill(byte[] into, int count) throws IOException {
            int done = 0;
            while (done < count && this.head < this.tail) {
                into[done++] = this.spare[this.head++];
            }
            while (done < count) {
                int got = this.source.read(into, done, count - done);
                if (got < 0) {
                    break;
                }
                done += got;
            }
            this.at += done;
            return done;
        }

        void giveBack(byte[] data, int count) {
            System.arraycopy(data, 0, this.spare, 0, count);
            this.head = 0;
            this.tail = count;
            this.at -= count;
        }

        /**
         * Avance de {@code count} octets.
         *
         * <p>{@code InputStream.skip} a le droit de sauter moins que demandé, y compris zéro, sans
         * que ce soit la fin du flux — un tampon vide suffit. Une boucle est donc obligatoire, et
         * l'oublier donnerait un décalage silencieux qui ferait perdre la synchronisation.
         */
        void skip(long count) throws IOException {
            long left = count;
            while (left > 0L && this.head < this.tail) {
                this.head++;
                this.at++;
                left--;
            }
            while (left > 0L) {
                long jumped = this.source.skip(left);
                if (jumped <= 0L) {
                    if (this.source.read() < 0) {
                        return;
                    }
                    jumped = 1L;
                }
                this.at += jumped;
                left -= jumped;
            }
        }
    }
}
