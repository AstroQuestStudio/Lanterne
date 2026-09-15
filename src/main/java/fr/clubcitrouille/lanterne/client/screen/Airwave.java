package fr.clubcitrouille.lanterne.client.screen;

import javax.sound.sampled.AudioFormat;

import it.unimi.dsi.fastutil.floats.FloatConsumer;

import net.minecraft.client.sounds.FloatSampleSource;

/**
 * Le son d'un écran : un anneau d'échantillons entre le décodeur et OpenAL.
 *
 * <h2>Le chemin existait déjà dans ce dépôt, et c'est celui-ci</h2>
 *
 * <p>{@code content.disc.Needle} a établi qu'on peut faire jouer à Minecraft autre chose que du
 * Vorbis en implémentant {@code FloatSampleSource} : une seule méthode à fournir, « donne-moi une
 * trame d'échantillons », et {@code ChunkedSampleByteBuf} s'occupe du tampon direct, du retournement
 * et de la conversion vers les entiers qu'attend OpenAL. Cette classe est le même geste, pour une
 * source qui n'est pas un fichier mais un flux vivant.
 *
 * <h2>Ce qui change par rapport à un disque, et qui commande toute la classe</h2>
 *
 * <p>Un disque a une fin, et le moteur sonore a le droit d'attendre ses octets : ils sont sur le
 * disque dur. Un écran n'a pas de fin, et ses octets arrivent du réseau, parfois en retard.
 *
 * <p>La conséquence est que <b>{@link #readChunk} ne doit jamais bloquer et ne doit jamais rendre
 * {@code false} par manque de données</b>. Bloquer retiendrait un fil de la réserve d'entrées-sorties
 * du jeu ; rendre {@code false} voudrait dire « le morceau est fini », et le moteur sonore fermerait
 * la source pour de bon — un hoquet de réseau d'un dixième de seconde couperait le son de la séance
 * jusqu'à ce qu'on relance la lecture à la main.
 *
 * <p>Un manque se comble donc par du <b>silence</b>. C'est ce que fait n'importe quel lecteur en
 * direct, et c'est audible comme un blanc, pas comme une panne.
 *
 * <h2>Un anneau, et pas une file d'objets</h2>
 *
 * <p>Deux secondes de stéréophonie à 48 kHz font 192 000 flottants, soit 768 kibioctets alloués une
 * fois pour toutes. Une file de tableaux aurait alloué à chaque trame — plusieurs mébioctets par
 * seconde à faire ramasser, sur un mod dont le journal des allocations est un sujet à lui seul.
 *
 * <p>L'anneau <b>écrase le plus ancien</b> quand il déborde plutôt que de faire attendre le
 * décodeur. Un décodeur bloqué sur le son cesserait de produire des images, et l'on perdrait
 * l'image pour sauver un son que personne n'entendra de toute façon — le débordement ne se produit
 * que si le moteur sonore ne lit plus, c'est-à-dire si le son est coupé.
 */
public final class Airwave implements FloatSampleSource {
    /** Secondes d'avance gardées. Au-delà, on écrase : voir l'en-tête. */
    private static final double DEPTH = 2d;

    /** Flottants rendus au plus par appel. Le contrat de {@code FloatSampleSource}. */
    private static final int CHUNK = EXPECTED_MAX_FRAME_SIZE;

    private final AudioFormat format;
    private final float[] ring;
    private final Object lock = new Object();

    private int read;
    private int write;
    private int held;
    private volatile boolean ended;

    Airwave(int sampleRate, int channels) {
        // Seize bits signés en petit-boutien : c'est ce que produit ChunkedSampleByteBuf à partir
        // des flottants, et c'est ce qu'OpenAL attend. La même déclaration que Needle.Mpeg.
        this.format = new AudioFormat(sampleRate, 16, channels, true, false);
        this.ring = new float[Math.max(CHUNK * 2,
                (int) (sampleRate * channels * DEPTH))];
    }

    @Override
    public AudioFormat getFormat() {
        return this.format;
    }

    /**
     * Dépose des échantillons entrelacés. Appelé par le fil de décodage.
     *
     * <p>Aucune allocation : les flottants sont recopiés dans l'anneau, et le tableau d'origine
     * appartient toujours à l'appelant.
     */
    void offer(float[] samples, int count) {
        synchronized (this.lock) {
            for (int i = 0; i < count; i++) {
                this.ring[this.write] = samples[i];
                this.write = (this.write + 1) % this.ring.length;
                if (this.held < this.ring.length) {
                    this.held++;
                } else {
                    // Plein : on avance aussi la lecture. L'échantillon le plus ancien est perdu,
                    // ce qui est exactement ce qu'on veut — le plus récent est celui qui compte.
                    this.read = (this.read + 1) % this.ring.length;
                }
            }
        }
    }

    /** Vide l'anneau. À appeler après un saut : ces échantillons sont d'un autre endroit du film. */
    void flush() {
        synchronized (this.lock) {
            this.read = 0;
            this.write = 0;
            this.held = 0;
        }
    }

    /** Combien de millisecondes d'avance sont en réserve. Sert à ne pas décoder pour rien. */
    int buffered() {
        synchronized (this.lock) {
            return (int) (this.held * 1000L
                    / Math.max(1, this.format.getSampleRate() * this.format.getChannels()));
        }
    }

    /**
     * Rend une trame au moteur sonore.
     *
     * <p>Toujours {@code true} tant que la séance dure, même sans données : voir l'en-tête. La
     * quantité rendue est constante, ce qui évite de faire varier la latence du moteur sonore selon
     * l'état du réseau.
     */
    @Override
    public boolean readChunk(FloatConsumer output) {
        if (this.ended) {
            return false;
        }
        synchronized (this.lock) {
            int take = Math.min(CHUNK, this.held);
            for (int i = 0; i < take; i++) {
                output.accept(this.ring[this.read]);
                this.read = (this.read + 1) % this.ring.length;
            }
            this.held -= take;
            // Le complément en silence. Sans lui, un tampon court ferait accélérer la lecture du
            // moteur sonore, qui rappellerait aussitôt — et l'on tournerait à vide en consommant
            // un fil d'entrées-sorties.
            for (int i = take; i < CHUNK; i++) {
                output.accept(0f);
            }
        }
        return true;
    }

    /**
     * Ferme la source.
     *
     * <p>C'est le seul chemin par lequel {@link #readChunk} rend {@code false}, et donc le seul par
     * lequel le moteur sonore apprend que la séance est finie. Appelé quand la bobine se ferme —
     * écran hors de vue, source changée, joueur parti.
     */
    @Override
    public void close() {
        this.ended = true;
        flush();
    }
}
