// -*- coding: utf-8 -*-
// Epreuve autonome du decodeur video, HORS Minecraft.
//
// Elle repond a une seule question, et c'est celle qui decide de toute l'architecture :
// les bibliotheques natives de FFmpeg peuvent-elles etre trouvees sur le DISQUE, hors du
// chemin de classes, par « org.bytedeco.javacpp.platform.preloadpath » ?
//
// Si oui, le mod embarque les liaisons Java (832 Kio) et telecharge les binaires (31 Mio)
// dans config/lanterne/, sans chargeur de classes maison. Si non, il faut un chargeur
// enfant, qui est exactement le genre de mecanique qui marche en developpement et casse
// chez le joueur.
//
//   javac -cp javacpp.jar;ffmpeg.jar -d out EssaiLav.java
//   java  -cp out;javacpp.jar;ffmpeg.jar EssaiLav <dossier-natifs> <fichier.mp4>
//
// Le chemin de classes ne contient PAS les jars de natifs : c'est tout l'interet.

import java.io.File;
import java.nio.ByteBuffer;

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
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.ffmpeg.swresample.SwrContext;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avformat.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;
import static org.bytedeco.ffmpeg.global.swresample.*;

public final class EssaiLav {
    public static void main(String[] args) throws Exception {
        String natives = args[0];
        String file = args[1];

        // AVANT toute classe de bytedeco. Loader lit ces proprietes a son initialisation ;
        // les poser apres ne servirait a rien, et l'erreur serait un UnsatisfiedLinkError
        // sans rapport apparent avec la cause.
        System.setProperty("org.bytedeco.javacpp.platform.preloadpath", natives);
        System.setProperty("org.bytedeco.javacpp.platform.linkpath", natives);
        System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");
        System.setProperty("org.bytedeco.javacpp.cachedir",
                new File(natives, "cache").getAbsolutePath());

        av_log_set_level(AV_LOG_ERROR);

        AVFormatContext fmt = new AVFormatContext(null);
        int rc = avformat_open_input(fmt, file, (AVInputFormat) null, (AVDictionary) null);
        check(rc, "avformat_open_input");
        check(avformat_find_stream_info(fmt, (AVDictionary) null), "find_stream_info");

        int video = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, (AVCodec) null, 0);
        check(video, "find_best_stream(video)");
        int audio = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, (AVCodec) null, 0);

        AVStream stream = fmt.streams(video);
        AVCodecParameters par = stream.codecpar();
        AVCodec decoder = avcodec_find_decoder(par.codec_id());
        if (decoder == null) {
            throw new IllegalStateException("aucun decodeur pour codec_id " + par.codec_id());
        }
        AVCodecContext ctx = avcodec_alloc_context3(decoder);
        check(avcodec_parameters_to_context(ctx, par), "parameters_to_context");
        ctx.thread_count(0);
        check(avcodec_open2(ctx, decoder, (AVDictionary) null), "avcodec_open2");

        int sw = ctx.width();
        int sh = ctx.height();
        // On decode a 360p de haut, comme le ferait le mode « basse » : on verifie du meme
        // coup que le redimensionnement de swscale marche.
        int dh = 360;
        int dw = Math.max(2, (int) Math.round((double) sw / sh * dh) & ~1);

        SwsContext sws = sws_getContext(sw, sh, ctx.pix_fmt(), dw, dh, AV_PIX_FMT_RGBA,
                SWS_BILINEAR, null, null, (DoublePointer) null);
        if (sws == null) {
            throw new IllegalStateException("sws_getContext a rendu nul");
        }

        AVFrame frame = av_frame_alloc();
        AVFrame rgba = av_frame_alloc();
        rgba.format(AV_PIX_FMT_RGBA);
        rgba.width(dw);
        rgba.height(dh);
        // Alignement 1 : on veut linesize == largeur * 4, pour pouvoir envoyer le tampon
        // d'un bloc vers la carte graphique sans recopie ligne a ligne.
        check(av_frame_get_buffer(rgba, 1), "av_frame_get_buffer");

        AVPacket packet = av_packet_alloc();
        int decoded = 0;
        long firstPts = Long.MIN_VALUE;
        long lastPts = 0;
        int nonBlack = 0;

        double timeBase = av_q2d(stream.time_base());

        while (av_read_frame(fmt, packet) >= 0 && decoded < 40) {
            if (packet.stream_index() == video) {
                if (avcodec_send_packet(ctx, packet) >= 0) {
                    while (avcodec_receive_frame(ctx, frame) >= 0) {
                        sws_scale(sws, frame.data(), frame.linesize(), 0, sh,
                                rgba.data(), rgba.linesize());
                        decoded++;
                        long pts = frame.best_effort_timestamp();
                        if (firstPts == Long.MIN_VALUE) {
                            firstPts = pts;
                            int line = rgba.linesize(0);
                            if (line != dw * 4) {
                                System.out.println("ATTENTION linesize=" + line
                                        + " alors que largeur*4=" + (dw * 4));
                            }
                            BytePointer data = rgba.data(0);
                            ByteBuffer pixels = data.capacity((long) line * dh).asByteBuffer();
                            for (int i = 0; i + 3 < Math.min(pixels.remaining(), 4096); i += 4) {
                                int r = pixels.get(i) & 0xFF;
                                int g = pixels.get(i + 1) & 0xFF;
                                int b = pixels.get(i + 2) & 0xFF;
                                int a = pixels.get(i + 3) & 0xFF;
                                if (a != 255) {
                                    System.out.println("ATTENTION alpha=" + a + " au pixel " + (i / 4));
                                    break;
                                }
                                if (r + g + b > 24) {
                                    nonBlack++;
                                }
                            }
                        }
                        lastPts = pts;
                    }
                }
            }
            av_packet_unref(packet);
        }

        System.out.println("conteneur     : " + fmt.iformat().name().getString());
        System.out.println("video         : " + sw + "x" + sh + " -> " + dw + "x" + dh
                + " RGBA, codec " + decoder.name().getString());
        System.out.println("duree         : " + (fmt.duration() / 1000) + " ms");
        System.out.println("flux audio    : " + (audio >= 0
                ? avcodec_find_decoder(fmt.streams(audio).codecpar().codec_id())
                        .name().getString()
                : "aucun"));
        System.out.println("images        : " + decoded);
        System.out.println("pts premiere  : " + Math.round(firstPts * timeBase * 1000) + " ms");
        System.out.println("pts derniere  : " + Math.round(lastPts * timeBase * 1000) + " ms");
        System.out.println("pixels clairs : " + nonBlack + " sur 1024 testes");
        boolean audioOk = audio < 0 || testAudio(fmt, audio);
        System.out.println(decoded > 0 && nonBlack > 0 && audioOk
                ? "RESULTAT: OK" : "RESULTAT: ECHEC");

        av_packet_free(packet);
        av_frame_free(rgba);
        av_frame_free(frame);
        sws_freeContext(sws);
        avcodec_free_context(ctx);
        avformat_close_input(fmt);
    }

    /** Le son : meme demultiplexeur, reechantillonnage vers du flottant entrelace. */
    private static boolean testAudio(AVFormatContext fmt, int index) {
        AVStream stream = fmt.streams(index);
        AVCodecParameters par = stream.codecpar();
        AVCodec decoder = avcodec_find_decoder(par.codec_id());
        if (decoder == null) {
            System.out.println("audio        : aucun decodeur");
            return false;
        }
        AVCodecContext ctx = avcodec_alloc_context3(decoder);
        avcodec_parameters_to_context(ctx, par);
        if (avcodec_open2(ctx, decoder, (AVDictionary) null) < 0) {
            System.out.println("audio        : decodeur refuse");
            return false;
        }
        int rate = ctx.sample_rate();
        SwrContext swr = swr_alloc();
        av_opt_set_chlayout(swr, "in_chlayout", ctx.ch_layout(), 0);
        av_opt_set_int(swr, "in_sample_rate", rate, 0);
        av_opt_set_sample_fmt(swr, "in_sample_fmt", ctx.sample_fmt(), 0);
        org.bytedeco.ffmpeg.avutil.AVChannelLayout out =
                new org.bytedeco.ffmpeg.avutil.AVChannelLayout();
        av_channel_layout_default(out, 2);
        av_opt_set_chlayout(swr, "out_chlayout", out, 0);
        av_opt_set_int(swr, "out_sample_rate", rate, 0);
        av_opt_set_sample_fmt(swr, "out_sample_fmt", AV_SAMPLE_FMT_FLT, 0);
        if (swr_init(swr) < 0) {
            System.out.println("audio        : reechantillonneur refuse");
            return false;
        }

        avformat_seek_file(fmt, -1, Long.MIN_VALUE, 0, Long.MAX_VALUE, 0);
        AVPacket packet = av_packet_alloc();
        AVFrame frame = av_frame_alloc();
        long samples = 0;
        double peak = 0;
        int guard = 0;
        while (av_read_frame(fmt, packet) >= 0 && samples < 48000 && guard++ < 4000) {
            if (packet.stream_index() == index && avcodec_send_packet(ctx, packet) >= 0) {
                while (avcodec_receive_frame(ctx, frame) >= 0) {
                    int max = (int) av_rescale_rnd(
                            swr_get_delay(swr, rate) + frame.nb_samples(), rate, rate, AV_ROUND_UP);
                    BytePointer buffer = new BytePointer((long) max * 2 * 4);
                    org.bytedeco.javacpp.PointerPointer<BytePointer> outp =
                            new org.bytedeco.javacpp.PointerPointer<>(1);
                    outp.put(0, buffer);
                    int got = swr_convert(swr, outp, max, frame.data(), frame.nb_samples());
                    if (got > 0) {
                        java.nio.FloatBuffer floats =
                                buffer.capacity((long) got * 2 * 4).asByteBuffer()
                                        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
                        for (int i = 0; i < floats.limit(); i++) {
                            peak = Math.max(peak, Math.abs(floats.get(i)));
                        }
                        samples += got;
                    }
                    buffer.deallocate();
                }
            }
            av_packet_unref(packet);
        }
        System.out.println("audio        : " + decoder.name().getString() + ", " + rate
                + " Hz, " + samples + " echantillons reechantillonnes, crete "
                + String.format(java.util.Locale.ROOT, "%.3f", peak));
        av_packet_free(packet);
        av_frame_free(frame);
        swr_free(swr);
        avcodec_free_context(ctx);
        return samples > 0 && peak > 0.01;
    }

    private static void check(int rc, String what) {
        if (rc < 0) {
            BytePointer message = new BytePointer(512);
            av_strerror(rc, message, 512);
            throw new IllegalStateException(what + " : " + message.getString() + " (" + rc + ")");
        }
    }
}
