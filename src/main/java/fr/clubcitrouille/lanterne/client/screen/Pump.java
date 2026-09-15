package fr.clubcitrouille.lanterne.client.screen;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.PointerPointer;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVInputFormat;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.swresample.SwrContext;
import org.bytedeco.ffmpeg.swscale.SwsContext;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swresample.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La pompe à images : un fil qui décode, un fil qui affiche, et une file entre les deux.
 *
 * <h2>La seule classe du mod qui nomme un type de bytedeco</h2>
 *
 * <p>Ce n'est pas une coquetterie de rangement. Les propriétés qui disent au chargeur de natifs où
 * trouver les bibliothèques doivent être posées <b>avant</b> qu'une seule classe de
 * {@code org.bytedeco} ne soit initialisée — voir {@code Fetch.point}. Concentrer toutes les
 * références dans une classe unique, instanciée depuis un seul endroit et après une seule
 * vérification, est ce qui rend cette règle tenable plutôt que seulement souhaitable.
 *
 * <h2>Deux fils, et pourquoi la frontière est là</h2>
 *
 * <p>Décoder une image de 720p coûte quelques millisecondes ; lire un paquet sur le réseau peut en
 * coûter mille. Faire l'un ou l'autre depuis le fil de rendu ferait attendre l'image du jeu qu'une
 * image de film soit prête — le défaut classique de ce genre de mod, celui qui transforme une
 * saccade de décodage en saccade de jeu.
 *
 * <p>Le fil de fond décode donc en continu et dépose ses images dans une file courte. Le fil de rendu
 * y prend celle qui correspond à l'instant que {@code Clock} commande, et ne fait rien d'autre qu'un
 * téléversement. S'il n'y a rien pour lui, il garde l'image précédente : une texture déjà envoyée se
 * redessine gratuitement, et un écran qui garde son image une vingtième de seconde de trop ne se
 * voit pas.
 *
 * <h2>Des tampons prêtés, jamais alloués</h2>
 *
 * <p>Une image de 720p en RGBA pèse 3,5 mébioctets. En allouer une par image, c'est cent mébioctets
 * par seconde à faire ramasser — sur un mod dont le journal des allocations est un sujet à lui seul.
 *
 * <p>Quatre tampons directs circulent donc en boucle entre les deux fils : le décodeur en emprunte un
 * vide, le remplit, le met dans la file ; le rendu le téléverse et le rend au vide. Il n'y a
 * <b>aucune allocation</b> une fois la lecture lancée.
 *
 * <h2>Ce qui n'a pas été vu tourner</h2>
 *
 * <p>Le décodage lui-même l'a été, hors de Minecraft : {@code tools/EssaiLav.java} ouvre un H.264,
 * le convertit en RGBA et vérifie les pixels, avec les bibliothèques trouvées sur le disque et hors
 * du chemin de classes. C'est la partie qui pouvait ne pas marcher du tout, et elle marche.
 *
 * <p>Le <b>téléversement vers la carte graphique</b>, lui, n'a pas pu être exécuté : il demande un
 * contexte graphique, donc le jeu lancé. Ce qui est écrit suit le contrat de
 * {@code CommandEncoder.writeToTexture(GpuTexture, ByteBuffer, …)} tel qu'il est en 26.2 — huit
 * arguments, la variante par sous-région ayant disparu pour {@code NativeImage} — et le tampon est
 * direct, ce que cette variante exige.
 */
public final class Pump implements Engine.Reel {
    /**
     * Tampons en circulation.
     *
     * <p>Quatre : un que le décodeur remplit, un ou deux qui attendent dans la file, un que le rendu
     * téléverse. Deux suffiraient à ne pas bloquer ; quatre absorbent une image qui arrive en retard
     * sans faire hoqueter la lecture, pour quatorze mébioctets en 720p.
     */
    private static final int BUFFERS = 4;

    /** Au-delà de cet écart, on repositionne le flux au lieu d'attendre. En millisecondes. */
    private static final long SEEK_AHEAD = 2_000L;

    private final String source;
    private final int wantedHeight;

    private final BlockingQueue<Shot> filled = new ArrayBlockingQueue<>(BUFFERS);
    private final BlockingQueue<ByteBuffer> empty = new ArrayBlockingQueue<>(BUFFERS);

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong durationMillis = new AtomicLong();
    private final AtomicLong shownMillis = new AtomicLong();
    private final AtomicLong wantedMillis = new AtomicLong();
    private final AtomicBoolean seekWanted = new AtomicBoolean();

    private volatile int width;
    private volatile int height;
    private volatile boolean ready;
    private volatile boolean broken;
    private volatile String trouble = "";
    private volatile float gain = 1f;

    /**
     * Le son de cette séance, ou {@code null} si la source n'en a pas.
     *
     * <p>Alimenté par le <b>même</b> fil et le <b>même</b> démultiplexeur que l'image. C'est ce qui
     * garde le son en phase sans rien avoir à synchroniser : les deux pistes sortent du même
     * {@code av_read_frame}, donc du même endroit du fichier, donc du même instant.
     *
     * <p>Un second contexte pour le son aurait voulu dire une <b>seconde connexion réseau</b> vers
     * le même hôte, deux fois la bande passante, et deux positions à recaler l'une sur l'autre.
     */
    private volatile Airwave wave;

    /** L'état du décodage sonore. Vit sur le fil de fond, et n'est lu que par lui. */
    private int audioIndex = -1;
    private AVCodecContext audioCodec;
    private SwrContext resampler;
    private AVFrame audioFrame;
    private BytePointer audioBytes;
    private PointerPointer<BytePointer> audioSlot;
    private float[] audioScratch = new float[0];
    private int audioChannels = 2;
    private int audioRate = 48_000;

    private Thread worker;

    /** Une image décodée : ses pixels, et l'instant auquel elle appartient. */
    private record Shot(ByteBuffer pixels, long millis) {}

    private Pump(String source, int wantedHeight) {
        this.source = source;
        this.wantedHeight = wantedHeight;
    }

    /**
     * Ouvre une source et lance le fil de décodage.
     *
     * <p>L'ouverture elle-même se fait sur le fil de fond : {@code avformat_open_input} sur une
     * adresse réseau est un aller-retour complet, et il n'a rien à faire sur le fil de rendu. C'est
     * pourquoi cette méthode rend tout de suite une pompe qui n'est pas encore prête, et pourquoi
     * {@link #ready()} existe.
     */
    static Pump open(String source, int wantedHeight) {
        Pump pump = new Pump(source, wantedHeight);
        pump.worker = new Thread(pump::run, "lanterne-pompe");
        pump.worker.setDaemon(true);
        pump.worker.setPriority(Thread.NORM_PRIORITY - 2);
        pump.worker.start();
        return pump;
    }

    @Override
    public boolean ready() {
        return this.ready && !this.broken;
    }

    @Override
    public boolean broken() {
        return this.broken;
    }

    /** Ce que le décodeur a répondu, en clair. Voir {@link Slate}, qui le met sur l'écran. */
    @Override
    public String trouble() {
        return this.trouble;
    }

    @Override
    public int width() {
        return this.width;
    }

    @Override
    public int height() {
        return this.height;
    }

    @Override
    public long duration() {
        return this.durationMillis.get();
    }

    /** Le son de cette séance, ou {@code null}. Voir {@link Blare} pour ce qu'on en fait. */
    public Airwave wave() {
        return this.wave;
    }

    @Override
    public long drift() {
        return this.wantedMillis.get() - this.shownMillis.get();
    }

    @Override
    public void rate(float multiplier) {
        // Le rattrapage progressif n'a de sens que pour le son, qui est un flux continu. L'image,
        // elle, est tirée : on lui demande l'instant voulu, et elle donne l'image la plus proche.
        // Accélérer le décodeur ne la rapprocherait pas — voir Engine.Reel, « donne-moi l'image de
        // cet instant, et non avance ».
    }

    @Override
    public void volume(float value) {
        this.gain = value;
    }

    /**
     * Pose dans la pellicule l'image la plus proche de {@code millis}.
     *
     * <p>Sur le fil de rendu. Tout ce qu'elle fait est : prendre dans la file, téléverser, rendre le
     * tampon. Aucun décodage, aucune allocation, aucune attente — si la file est vide, on garde
     * l'image précédente et l'on rend {@code false}.
     */
    @Override
    public boolean present(long millis, Film film) {
        this.wantedMillis.set(millis);
        if (this.broken || film == null) {
            return false;
        }
        // Un saut demandé de loin : on le signale au décodeur plutôt que de vider la file en
        // espérant qu'il rattrape. Vider coûterait autant et ne dirait rien.
        if (Math.abs(millis - this.shownMillis.get()) > SEEK_AHEAD) {
            this.seekWanted.set(true);
        }

        Shot best = null;
        // On consomme tout ce qui est déjà en retard sur l'instant voulu et l'on garde le dernier :
        // c'est ce qui rattrape naturellement une file qui a pris de l'avance, sans saut visible.
        while (true) {
            Shot head = this.filled.peek();
            if (head == null || head.millis() > millis) {
                break;
            }
            this.filled.poll();
            if (best != null) {
                this.empty.offer(best.pixels());
            }
            best = head;
        }
        if (best == null) {
            // Rien d'assez ancien : au démarrage, la toute première image est en avance sur zéro.
            // La montrer vaut mieux que de laisser l'ardoise une seconde de plus.
            if (this.shownMillis.get() != 0L || this.filled.isEmpty()) {
                return false;
            }
            best = this.filled.poll();
            if (best == null) {
                return false;
            }
        }

        try {
            film.write(best.pixels(), this.width, this.height);
            this.shownMillis.set(best.millis());
            return true;
        } finally {
            best.pixels().clear();
            this.empty.offer(best.pixels());
        }
    }

    @Override
    public void close() {
        this.running.set(false);
        Airwave sound = this.wave;
        if (sound != null) {
            // Fermer le son AVANT d'interrompre le fil : c'est le seul chemin par lequel le moteur
            // sonore apprend que la séance est finie, et un fil interrompu ne le ferait plus.
            sound.close();
        }
        Thread thread = this.worker;
        if (thread != null) {
            thread.interrupt();
        }
    }

    // --- Le fil de fond -------------------------------------------------------

    private void run() {
        AVFormatContext format = null;
        AVCodecContext codec = null;
        SwsContext scaler = null;
        AVFrame frame = null;
        AVFrame rgba = null;
        AVPacket packet = null;
        try {
            av_log_set_level(AV_LOG_FATAL);

            AVDictionary options = new AVDictionary(null);
            // Défense en profondeur. Le tamis a déjà refusé tout ce qui n'est pas http(s), mais le
            // tamis est du code qu'on peut se tromper d'appeler ; ceci est une barrière posée dans
            // la bibliothèque elle-même. Sans « file », un flux ne peut pas lire le disque du
            // joueur, même si une liste de lecture l'y invite.
            av_dict_set(options, "protocol_whitelist", "http,https,tcp,tls,crypto,hls,applehttp", 0);
            av_dict_set(options, "user_agent", "Lanterne", 0);
            // Cinq secondes en microsecondes : un hôte qui ne répond pas ne doit pas retenir un fil
            // et une connexion pour toujours.
            av_dict_set(options, "rw_timeout", "5000000", 0);
            av_dict_set(options, "reconnect", "1", 0);

            format = new AVFormatContext(null);
            int rc = avformat_open_input(format, this.source, (AVInputFormat) null, options);
            av_dict_free(options);
            if (rc < 0) {
                throw new IllegalStateException(error(rc));
            }
            if (avformat_find_stream_info(format, (AVDictionary) null) < 0) {
                throw new IllegalStateException("flux illisible");
            }

            int index = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, (AVCodec) null, 0);
            if (index < 0) {
                throw new IllegalStateException("aucune piste vidéo");
            }
            AVStream stream = format.streams(index);
            AVCodecParameters parameters = stream.codecpar();
            AVCodec decoder = avcodec_find_decoder(parameters.codec_id());
            if (decoder == null) {
                throw new IllegalStateException("codec non pris en charge");
            }
            codec = avcodec_alloc_context3(decoder);
            avcodec_parameters_to_context(codec, parameters);
            // Zéro : le décodeur choisit lui-même son nombre de fils. Sur les codecs modernes il
            // découpe par tranches et par images, et il le fait mieux qu'un nombre écrit ici.
            codec.thread_count(0);
            if (avcodec_open2(codec, decoder, (AVDictionary) null) < 0) {
                throw new IllegalStateException("décodeur refusé");
            }

            int sourceWidth = codec.width();
            int sourceHeight = codec.height();
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                throw new IllegalStateException("dimensions inconnues");
            }
            // Le plafond de qualité s'applique ici, et c'est le seul endroit qui compte : tout ce
            // qui suit — conversion, tampons, téléversement — coûte le carré de cette hauteur.
            int targetHeight = Math.min(this.wantedHeight, sourceHeight);
            // Pair, des deux côtés : swscale travaille par blocs, et une dimension impaire lui fait
            // rendre des images décalées d'un pixel sur les formats à sous-échantillonnage.
            targetHeight = Math.max(2, targetHeight & ~1);
            int targetWidth = Math.max(2,
                    (int) Math.round((double) sourceWidth / sourceHeight * targetHeight) & ~1);
            this.width = targetWidth;
            this.height = targetHeight;

            scaler = sws_getContext(sourceWidth, sourceHeight, codec.pix_fmt(),
                    targetWidth, targetHeight, AV_PIX_FMT_RGBA, SWS_BILINEAR,
                    null, null, (DoublePointer) null);
            if (scaler == null) {
                throw new IllegalStateException("conversion de couleur impossible");
            }

            frame = av_frame_alloc();
            rgba = av_frame_alloc();
            rgba.format(AV_PIX_FMT_RGBA);
            rgba.width(targetWidth);
            rgba.height(targetHeight);
            // Alignement 1 : on veut « linesize == largeur × 4 », pour envoyer le tampon d'un seul
            // bloc vers la carte graphique. Un alignement supérieur ajouterait un remplissage en
            // fin de ligne, et il faudrait recopier ligne à ligne.
            if (av_frame_get_buffer(rgba, 1) < 0) {
                throw new IllegalStateException("tampon de conversion refusé");
            }

            int bytes = targetWidth * targetHeight * 4;
            for (int i = 0; i < BUFFERS; i++) {
                this.empty.offer(ByteBuffer.allocateDirect(bytes));
            }
            if (format.duration() > 0L) {
                this.durationMillis.set(format.duration() / 1000L);
            }

            // Le son, sur le même contexte. Un échec ici n'arrête rien : une vidéo muette reste
            // une vidéo, et refuser de l'afficher parce que sa piste sonore est exotique serait
            // dégrader l'essentiel pour l'accessoire.
            openAudio(format);

            packet = av_packet_alloc();
            double timeBase = av_q2d(stream.time_base());
            this.ready = true;

            pumpLoop(format, codec, scaler, frame, rgba, packet, index, timeBase,
                    sourceHeight, targetWidth, targetHeight);
        } catch (Throwable problem) {
            this.trouble = problem.getMessage() == null
                    ? problem.getClass().getSimpleName() : problem.getMessage();
            this.broken = true;
            // La source n'est PAS journalisée : c'est une adresse que le joueur a tapée, elle est
            // déjà dans le monde, et la répéter à chaque échec remplit le journal d'une information
            // qu'on n'a pas besoin de dupliquer.
            Lanterne.LOG.warn("[PROJECTION] lecture impossible : {}", this.trouble);
        } finally {
            // L'ordre compte : le contexte de codec tient des références vers le format. L'inverse
            // laisserait une poignée pendante, et une poignée pendante dans une bibliothèque native
            // ne se manifeste pas par une exception mais par un plantage plus tard, ailleurs.
            closeAudio();
            if (packet != null) {
                av_packet_free(packet);
            }
            if (rgba != null) {
                av_frame_free(rgba);
            }
            if (frame != null) {
                av_frame_free(frame);
            }
            if (scaler != null) {
                sws_freeContext(scaler);
            }
            if (codec != null) {
                avcodec_free_context(codec);
            }
            if (format != null) {
                avformat_close_input(format);
            }
            this.ready = false;
        }
    }

    private void pumpLoop(AVFormatContext format, AVCodecContext codec, SwsContext scaler,
            AVFrame frame, AVFrame rgba, AVPacket packet, int index, double timeBase,
            int sourceHeight, int targetWidth, int targetHeight) throws InterruptedException {
        while (this.running.get()) {
            if (this.seekWanted.compareAndSet(true, false)) {
                long target = (long) (this.wantedMillis.get() / 1000d / timeBase);
                if (av_seek_frame(format, index, target, AVSEEK_FLAG_BACKWARD) >= 0) {
                    avcodec_flush_buffers(codec);
                    drain();
                    // Le son aussi, et pour la même raison : les échantillons en réserve
                    // appartiennent à l'endroit d'où l'on vient. Les garder ferait entendre la
                    // seconde précédente par-dessus l'image de la nouvelle position.
                    if (this.audioCodec != null) {
                        avcodec_flush_buffers(this.audioCodec);
                    }
                    Airwave sink = this.wave;
                    if (sink != null) {
                        sink.flush();
                    }
                }
            }
            if (av_read_frame(format, packet) < 0) {
                // Fin du flux. On ne relance pas : la boucle est décidée par l'horloge partagée,
                // qui replie la position sur la durée — et c'est elle qui demandera un saut au
                // début. Relancer ici ferait deux boucles concurrentes, décalées l'une de l'autre.
                this.durationMillis.compareAndSet(0L, this.shownMillis.get());
                Thread.sleep(80L);
                this.seekWanted.set(true);
                continue;
            }
            try {
                if (packet.stream_index() == this.audioIndex) {
                    pumpAudio(packet);
                    continue;
                }
                if (packet.stream_index() != index) {
                    continue;
                }
                if (avcodec_send_packet(codec, packet) < 0) {
                    continue;
                }
                while (avcodec_receive_frame(codec, frame) >= 0) {
                    // Le tampon vide est attendu : c'est ce qui régule le décodeur sur le rendu.
                    // Sans cette attente, un fil de décodage libre remplirait la mémoire à la
                    // vitesse du processeur pour une vidéo que personne ne regarde assez vite.
                    ByteBuffer slot = this.empty.poll(500,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (slot == null) {
                        if (!this.running.get()) {
                            return;
                        }
                        continue;
                    }
                    sws_scale(scaler, frame.data(), frame.linesize(), 0, sourceHeight,
                            rgba.data(), rgba.linesize());
                    int line = rgba.linesize(0);
                    BytePointer pixels = rgba.data(0);
                    ByteBuffer native0 = pixels.capacity((long) line * targetHeight).asByteBuffer();
                    slot.clear();
                    if (line == targetWidth * 4) {
                        slot.put(native0);
                    } else {
                        // Remplissage en fin de ligne : on recopie ligne à ligne. Ne devrait pas
                        // arriver avec un alignement de 1, mais swscale reste libre de choisir, et
                        // une image décalée d'un octet par ligne donne une diagonale colorée qu'on
                        // mettrait longtemps à attribuer à la bonne cause.
                        for (int row = 0; row < targetHeight; row++) {
                            native0.limit(row * line + targetWidth * 4).position(row * line);
                            slot.put(native0);
                        }
                    }
                    slot.flip();
                    long stamp = Math.round(frame.best_effort_timestamp() * timeBase * 1000d);
                    if (!this.filled.offer(new Shot(slot, stamp))) {
                        this.empty.offer(slot);
                    }
                }
            } finally {
                av_packet_unref(packet);
            }
        }
    }

    /**
     * Ouvre la piste sonore, si elle existe et si on sait la lire.
     *
     * <p>Rien ici ne lève : une vidéo sans son est une vidéo. Le seul effet d'un échec est que
     * {@link #wave} reste nul, et {@code Gaze} n'essaie alors pas de jouer quoi que ce soit.
     *
     * <h2>On garde la fréquence de la source</h2>
     *
     * <p>Rééchantillonner vers 48 kHz serait du travail en plus et de la qualité en moins sur un
     * fichier déjà en 44,1. OpenAL accepte la fréquence qu'on lui déclare, et {@code Airwave} la
     * rend dans son {@code AudioFormat} — il n'y a donc personne à convaincre.
     *
     * <p>Le format de sortie, lui, est imposé : flottant <b>entrelacé</b>, parce que c'est ce que
     * {@code FloatSampleSource} consomme. Le format d'entrée peut être planaire — l'AAC l'est —
     * et c'est précisément ce que le rééchantillonneur est là pour absorber.
     */
    private void openAudio(AVFormatContext format) {
        try {
            int index = av_find_best_stream(format, AVMEDIA_TYPE_AUDIO, -1, -1, (AVCodec) null, 0);
            if (index < 0) {
                return;
            }
            AVCodecParameters parameters = format.streams(index).codecpar();
            AVCodec decoder = avcodec_find_decoder(parameters.codec_id());
            if (decoder == null) {
                return;
            }
            AVCodecContext context = avcodec_alloc_context3(decoder);
            avcodec_parameters_to_context(context, parameters);
            if (avcodec_open2(context, decoder, (AVDictionary) null) < 0) {
                avcodec_free_context(context);
                return;
            }

            this.audioRate = context.sample_rate();
            // Deux canaux au plus : OpenAL ne connaît que le monophonique et la stéréophonie —
            // c'est la même contrainte que Needle.Wave a rencontrée pour les fichiers 5.1, et le
            // rééchantillonneur fait ici le repli que Needle devait écrire à la main.
            this.audioChannels = Math.min(2, Math.max(1, parameters.ch_layout().nb_channels()));

            SwrContext swr = swr_alloc();
            AVChannelLayout out = new AVChannelLayout();
            av_channel_layout_default(out, this.audioChannels);
            av_opt_set_chlayout(swr, "in_chlayout", context.ch_layout(), 0);
            av_opt_set_int(swr, "in_sample_rate", this.audioRate, 0);
            av_opt_set_sample_fmt(swr, "in_sample_fmt", context.sample_fmt(), 0);
            av_opt_set_chlayout(swr, "out_chlayout", out, 0);
            av_opt_set_int(swr, "out_sample_rate", this.audioRate, 0);
            av_opt_set_sample_fmt(swr, "out_sample_fmt", AV_SAMPLE_FMT_FLT, 0);
            if (swr_init(swr) < 0) {
                swr_free(swr);
                avcodec_free_context(context);
                return;
            }

            this.audioIndex = index;
            this.audioCodec = context;
            this.resampler = swr;
            this.audioFrame = av_frame_alloc();
            this.wave = new Airwave(this.audioRate, this.audioChannels);
        } catch (Throwable mute) {
            Lanterne.LOG.debug("[PROJECTION] piste sonore ignorée", mute);
            this.wave = null;
        }
    }

    /**
     * Décode un paquet sonore et verse ses échantillons dans l'anneau.
     *
     * <p>Les tampons sont réalloués <b>seulement</b> quand une trame plus longue que la précédente
     * se présente, ce qui arrive une fois ou deux au début puis plus jamais : les trames d'un codec
     * donné ont toutes la même taille. Allouer à chaque trame aurait fait quarante allocations par
     * seconde pour rien.
     */
    private void pumpAudio(AVPacket packet) {
        Airwave sink = this.wave;
        if (sink == null || this.audioCodec == null) {
            return;
        }
        if (avcodec_send_packet(this.audioCodec, packet) < 0) {
            return;
        }
        while (avcodec_receive_frame(this.audioCodec, this.audioFrame) >= 0) {
            int room = (int) av_rescale_rnd(
                    swr_get_delay(this.resampler, this.audioRate) + this.audioFrame.nb_samples(),
                    this.audioRate, this.audioRate, AV_ROUND_UP);
            int floats = room * this.audioChannels;
            if (this.audioScratch.length < floats) {
                this.audioScratch = new float[floats];
                if (this.audioBytes != null) {
                    this.audioBytes.deallocate();
                }
                this.audioBytes = new BytePointer((long) floats * 4L);
                this.audioSlot = new PointerPointer<>(1);
                this.audioSlot.put(0, this.audioBytes);
            }
            int got = swr_convert(this.resampler, this.audioSlot, room,
                    this.audioFrame.data(), this.audioFrame.nb_samples());
            if (got <= 0) {
                continue;
            }
            int produced = got * this.audioChannels;
            this.audioBytes.capacity((long) produced * 4L).asByteBuffer()
                    .order(java.nio.ByteOrder.nativeOrder())
                    .asFloatBuffer().get(this.audioScratch, 0, produced);
            sink.offer(this.audioScratch, produced);
        }
    }

    private void closeAudio() {
        if (this.audioFrame != null) {
            av_frame_free(this.audioFrame);
            this.audioFrame = null;
        }
        if (this.resampler != null) {
            swr_free(this.resampler);
            this.resampler = null;
        }
        if (this.audioCodec != null) {
            avcodec_free_context(this.audioCodec);
            this.audioCodec = null;
        }
        if (this.audioBytes != null) {
            this.audioBytes.deallocate();
            this.audioBytes = null;
        }
        Airwave sound = this.wave;
        if (sound != null) {
            sound.close();
        }
    }

    /** Vide la file après un saut : ces images appartiennent à un autre endroit du film. */
    private void drain() {
        Shot stale;
        while ((stale = this.filled.poll()) != null) {
            stale.pixels().clear();
            this.empty.offer(stale.pixels());
        }
    }

    /**
     * Le message de FFmpeg, et rien d'autre.
     *
     * <h2>Ce que « getString » faisait à la place</h2>
     *
     * <p>{@code av_strerror} écrit une chaîne terminée par un zéro dans un tampon de 512 octets
     * qu'il ne remplit pas. {@code BytePointer.getString()} a lu la <b>capacité entière</b>, et le
     * journal du joueur a reçu « Invalid data found when processing input » suivi de quatre cents
     * octets de mémoire non initialisée — dont, au passage, l'adresse qu'il venait de taper.
     *
     * <p>On s'arrête donc au premier zéro, à la main. C'est trois lignes, et cela rend un journal
     * lisible au lieu d'un journal qu'on renonce à lire.
     */
    private static String error(int code) {
        byte[] raw = new byte[512];
        BytePointer message = new BytePointer(raw.length);
        try {
            av_strerror(code, message, raw.length);
            message.get(raw);
            int end = 0;
            while (end < raw.length && raw[end] != 0) {
                end++;
            }
            return new String(raw, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            message.deallocate();
        }
    }
}
