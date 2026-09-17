package fr.clubcitrouille.lanterne.content.disc;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.sound.sampled.AudioFormat;

import it.unimi.dsi.fastutil.floats.FloatConsumer;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.SampleBuffer;

import net.minecraft.client.sounds.FiniteAudioStream;
import net.minecraft.client.sounds.FloatSampleSource;
import net.minecraft.client.sounds.JOrbisAudioStream;

/**
 * L'aiguille : le décodeur qui correspond aux octets qu'on lui présente.
 *
 * <h2>Où cette classe s'insère</h2>
 *
 * <p>{@code SoundBufferLibrary} construit un {@code JOrbisAudioStream} sans jamais regarder le
 * contenu du fichier. {@code mixin.SoundDecodeMixin} intercepte cette décision — pour l'espace de
 * noms de Lanterne et pour lui seul — et appelle {@link #finite}, qui renifle la signature et rend le
 * décodeur qui va avec. Pour un Ogg, c'est {@code JOrbisAudioStream} : le chemin de vanilla, à
 * l'identique, sans une ligne de différence.
 *
 * <h2>Pourquoi implémenter {@code FloatSampleSource} et pas {@code AudioStream}</h2>
 *
 * <p>{@code AudioStream} demande de rendre un {@code ByteBuffer} d'une taille approchée ; le remplir
 * correctement suppose de savoir que le tampon doit être <b>direct</b> (OpenAL lit de la mémoire
 * native), qu'il doit être retourné, et qu'un décodeur ne produit pas des trames de la taille qu'on
 * lui demande. Tout cela est déjà écrit dans {@code ChunkedSampleByteBuf}, et
 * {@code FloatSampleSource} l'expose derrière une seule méthode à fournir : « donne-moi une trame
 * d'échantillons ». C'est ce que {@code JOrbisAudioStream} fait lui-même.
 *
 * <p>Le prix est un aller-retour par le flottant pour des formats qui sont déjà entiers. Il est nul :
 * la conversion inverse de {@code ChunkedSampleByteBuf} est écrite exactement, et ce qui en ressort
 * est le bit d'origine, à l'arrondi du flottant près — quatre-vingt-seize décibels sous le signal,
 * c'est-à-dire sous le plancher de bruit de n'importe quel enregistrement.
 *
 * <h2>Ce que le mode flux garantit, et qu'il ne faut pas casser</h2>
 *
 * <p>Les disques de Lanterne déclarent {@code "stream": true}. Le moteur sonore réclame alors, par
 * source et par seconde de musique, un tampon d'une seconde — et il le réclame depuis un fil de
 * fond. Un morceau de vingt minutes n'occupe donc jamais vingt minutes de mémoire, et le décodage ne
 * touche jamais le fil de rendu. Les deux décodeurs ci-dessous respectent cette règle : ni l'un ni
 * l'autre ne lit plus loin que la trame qu'on lui demande.
 */
public final class Needle {
    private Needle() {}

    /**
     * Le décodeur qui convient à ces octets.
     *
     * <p>Le flux est enveloppé dans un tampon avant tout : on a besoin de lire seize octets puis de
     * revenir en arrière, et un flux de ressource ne le permet pas de lui-même. Le tampon sert
     * ensuite au décodage, ce qui fait qu'il n'est pas perdu.
     *
     * @throws IOException si le format n'est pas décodable. Le message est destiné à un humain — il
     *     remonte jusqu'au journal du jeu, et c'est la seule trace qu'aura le joueur.
     */
    public static FiniteAudioStream finite(InputStream source) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(source, 65536);
        try {
            buffered.mark(64);
            byte[] head = buffered.readNBytes(16);
            buffered.reset();
            Press.Grain grain = Press.grain(head);
            return switch (grain) {
                case OGG -> new JOrbisAudioStream(buffered);
                case MP3 -> new Mpeg(buffered);
                case WAV -> new Wave(buffered);
                default -> throw new IOException(Press.refusal(grain));
            };
        } catch (IOException | RuntimeException refused) {
            // Personne ne fermera ce flux si nous ne rendons pas d'objet : l'appelant n'en aura
            // jamais la référence. Un fichier laissé ouvert à chaque disque refusé finit par épuiser
            // les descripteurs du système, et la panne qui s'ensuit n'a plus rien à voir avec le son.
            buffered.close();
            throw refused;
        }
    }

    /**
     * Un MP3, décodé par JLayer.
     *
     * <p>JLayer est embarqué dans l'archive du mod par jarJar. Sans cela il serait absent à
     * l'exécution et cette classe lèverait une {@code NoClassDefFoundError} au premier disque joué —
     * une panne qui ne se voit ni à la compilation ni au démarrage, seulement quand un joueur clique.
     *
     * <p>Une trame MP3 rend 1152 échantillons par canal, soit vingt-six millisecondes de musique. Le
     * moteur sonore en réclame une seconde à la fois, donc une quarantaine de trames par tampon.
     * L'étiquette ID3v2 éventuelle est sautée par {@code Bitstream} lui-même, à sa construction.
     *
     * <h2>Toujours ramené à un seul canal, quel que soit le MP3</h2>
     *
     * <p>Voir la javadoc de {@link Wave} pour la raison : {@code OpenAL} ne spatialise que le
     * monophonique, et presque tout MP3 d'origine est stéréo. JLayer décode toujours selon le mode
     * réel de la trame ; c'est ici, à la sortie, que les deux voies sont moyennées en une — la même
     * opération que {@link Wave} fait pour ses propres canaux surnuméraires.
     */
    private static final class Mpeg implements FloatSampleSource {
        private final InputStream source;
        private final Bitstream bitstream;
        private final Decoder decoder = new Decoder();
        private final AudioFormat format;
        private final int channels;

        /**
         * La première trame, lue d'avance et gardée.
         *
         * <p>Il faut la lire pour connaître la fréquence et le nombre de canaux, que
         * {@code getFormat} doit rendre avant qu'aucun échantillon n'ait été demandé. La décoder tout
         * de suite aurait voulu dire garder ses échantillons quelque part ; la garder, elle, ne coûte
         * rien.
         */
        private Header first;

        Mpeg(InputStream source) throws IOException {
            this.source = source;
            this.bitstream = new Bitstream(source);
            try {
                this.first = this.bitstream.readFrame();
            } catch (JavaLayerException broken) {
                throw new IOException("MP3 illisible : " + broken.getMessage());
            }
            if (this.first == null) {
                throw new IOException("MP3 sans aucune trame lisible");
            }
            this.channels = this.first.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
            // Toujours un seul canal déclaré : voir la javadoc de classe.
            this.format = new AudioFormat(this.first.frequency(), 16, 1, true, false);
        }

        @Override
        public AudioFormat getFormat() {
            return this.format;
        }

        @Override
        public boolean readChunk(FloatConsumer output) throws IOException {
            Header header = this.first;
            this.first = null;
            try {
                if (header == null) {
                    header = this.bitstream.readFrame();
                }
                if (header == null) {
                    return false;
                }
                SampleBuffer decoded = (SampleBuffer) this.decoder.decodeFrame(header, this.bitstream);
                this.bitstream.closeFrame();
                short[] samples = decoded.getBuffer();
                int length = decoded.getBufferLength();
                if (this.channels == 1) {
                    for (int at = 0; at < length; at++) {
                        output.accept(samples[at] / 32767.5f + 1.0f / 65535.0f);
                    }
                } else {
                    // Entrelacé gauche-droite : une paire d'échantillons par trame stéréo, moyennée
                    // en une seule voie — voir la javadoc de classe.
                    for (int at = 0; at + 1 < length; at += 2) {
                        float left = samples[at] / 32767.5f + 1.0f / 65535.0f;
                        float right = samples[at + 1] / 32767.5f + 1.0f / 65535.0f;
                        output.accept((left + right) * 0.5f);
                    }
                }
                return true;
            } catch (JavaLayerException broken) {
                throw new IOException("trame MP3 illisible : " + broken.getMessage());
            }
        }

        @Override
        public void close() throws IOException {
            try {
                this.bitstream.close();
            } catch (JavaLayerException ignored) {
                // Fermer un flux déjà épuisé n'est pas un incident, et le laisser remonter ferait
                // apparaître une erreur dans le journal à la fin de chaque morceau.
            } finally {
                this.source.close();
            }
        }
    }

    /**
     * Un WAV, lu directement.
     *
     * <h2>Plus de deux canaux : tout est replié sur un seul</h2>
     *
     * <p>OpenAL ne connaît que le monophonique et la stéréophonie —
     * {@code OpenAlUtil.audioFormatToOpenAl} n'a pas d'autre cas. Un fichier 5.1 doit donc être
     * ramené, et il y a trois façons de le faire dont deux sont mauvaises.
     *
     * <p><b>Garder les deux premiers canaux</b> était la première version, et elle rendait le
     * silence. Un fichier 5.1 produit à partir d'une source monophonique met tout dans le canal
     * <em>central</em>, qui est le troisième : l'avant gauche et l'avant droit y sont vides. Le test
     * l'a montré tout de suite — crête zéro, valeur efficace zéro, douze secondes de rien. C'est
     * exactement le genre de résultat à moitié juste qui ne se remarque qu'en jeu.
     *
     * <p><b>Un vrai mixage aux coefficients de l'ITU</b> — centre à −3 dB, ambiances à −3 dB, canal
     * de basses écarté — suppose de connaître l'ordre des canaux, qui n'est écrit nulle part dans un
     * WAV ordinaire. Se tromper d'ordre inverse l'avant et l'arrière.
     *
     * <p>Reste la <b>moyenne de tous les canaux</b>, sur une seule voie. Elle ne suppose rien de
     * l'ordre, elle ne perd aucune source, elle ne peut pas saturer, et elle n'est jamais muette. Ce
     * qu'elle perd est l'image stéréophonique d'un fichier surround — sur un jukebox, dans un jeu qui
     * spatialise lui-même le son autour du bloc, personne ne l'entendra.
     *
     * <h2>Deux canaux aussi, et ce n'est plus un détail</h2>
     *
     * <p>La version précédente gardait la stéréo telle quelle jusqu'à deux canaux, en ne repliant que
     * le surround. C'était faux pour la raison inverse de celle exposée ci-dessus : {@code OpenAL} ne
     * spatialise <b>que</b> les sources monophoniques — {@code AL_SOURCE_RELATIVE} mis à part, une
     * source stéréo n'a pas de position unique à laquelle appliquer une atténuation, et le moteur la
     * joue à volume constant, quelle que soit la distance déclarée sur le {@code SoundInstance}. Un
     * disque personnalisé, presque toujours issu d'un mixage stéréo, s'entendait donc aussi fort à
     * l'autre bout de la base qu'à côté du jukebox. Les disques de vanilla, eux, sont pré-encodés en
     * monophonique par Mojang, précisément pour ne jamais heurter cette limite. On applique donc la
     * même règle qu'au surround, dès deux canaux.
     */
    private static final class Wave implements FloatSampleSource {
        /** Environ une trame de la taille que le moteur sonore attend d'un décodeur. */
        private static final int CHUNK = 8192;

        private final InputStream source;
        private final Riff.Head head;
        private final AudioFormat format;
        private final byte[] block;
        private final int step;
        private final int voices;
        private long left;

        Wave(InputStream source) throws IOException {
            this.source = source;
            this.head = Riff.read(source, -1L);
            this.left = this.head.dataBytes();
            // Toujours un — voir la javadoc de classe. Le chemin de repli ci-dessous (la moyenne
            // de tous les canaux) gère aussi bien deux voies que six.
            this.voices = 1;
            this.format = new AudioFormat(this.head.sampleRate(), 16, this.voices, true, false);
            this.step = this.head.bits() / 8;
            int stride = this.head.stride();
            // Un bloc qui ne tomberait pas sur une frontière d'échantillon décalerait les canaux, et
            // la stéréo s'inverserait au premier tampon.
            this.block = new byte[Math.max(stride, CHUNK / stride * stride)];
        }

        @Override
        public AudioFormat getFormat() {
            return this.format;
        }

        @Override
        public boolean readChunk(FloatConsumer output) throws IOException {
            int want = (int) Math.min(this.block.length, this.left);
            if (want <= 0) {
                return false;
            }
            int got = this.source.readNBytes(this.block, 0, want);
            int stride = this.head.stride();
            int frames = got / stride;
            if (frames <= 0) {
                return false;
            }
            this.left -= (long) frames * stride;
            boolean floating = this.head.floating();
            int bits = this.head.bits();
            int channels = this.head.channels();
            for (int frame = 0; frame < frames; frame++) {
                int at = frame * stride;
                if (channels == this.voices) {
                    for (int voice = 0; voice < this.voices; voice++) {
                        output.accept(Riff.sample(this.block, at + voice * this.step, bits, floating));
                    }
                } else {
                    float sum = 0.0f;
                    for (int voice = 0; voice < channels; voice++) {
                        sum += Riff.sample(this.block, at + voice * this.step, bits, floating);
                    }
                    output.accept(sum / channels);
                }
            }
            return true;
        }

        @Override
        public void close() throws IOException {
            this.source.close();
        }
    }
}
