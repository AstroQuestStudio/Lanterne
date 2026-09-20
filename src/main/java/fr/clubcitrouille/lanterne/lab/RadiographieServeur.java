package fr.clubcitrouille.lanterne.lab;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * L'équivalent {@link Radiographie} côté serveur dédié : MSPT (temps de tick) au lieu de FPS,
 * méthodes chaudes du thread serveur au lieu du thread de rendu.
 *
 * <p>Même technique d'échantillonnage que {@link Radiographie} ({@link ThreadMXBean#getThreadInfo}
 * sur un fil séparé, sans suspendre le thread serveur) — dupliquée ici plutôt que partagée, car
 * {@link Radiographie} garde son arbre d'appel ({@code Node}) privé et le séparer proprement aurait
 * demandé de toucher un fichier qu'un autre chantier modifiait au même moment cette nuit-là. Version
 * volontairement plus simple : pas de détection de "creux" ni d'histogramme détaillé, juste MSPT
 * moyen/min/max et les méthodes les plus coûteuses en temps propre — déjà largement suffisant pour
 * savoir si le serveur souffre, ce qui manquait totalement avant ce fichier.
 *
 * <p>Manuel uniquement (pas de démarrage automatique comme {@link Radiographie}) : voir
 * {@code /lanterne profil-serveur demarrer|arreter} dans {@code report.RadiographieServeurCommand}.
 */
public final class RadiographieServeur {
    private RadiographieServeur() {}

    private static final ThreadMXBean BEAN = ManagementFactory.getThreadMXBean();
    private static final int MAX_DEPTH = 128;
    private static final long INTERVAL_MS = 15L;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile Thread samplerThread;
    private static volatile long serverThreadId = -1L;

    private static final Object lock = new Object();
    private static final Map<String, Long> selfByMethod = new HashMap<>();
    private static int totalSamples;

    private static long sessionStartNanos;
    private static long ticksSeen;
    private static double mspt;
    private static double msptMin;
    private static double msptMax;
    private static long lastTickNanos;

    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(RadiographieServeur.class);
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!running.get()) {
            return;
        }
        long now = System.nanoTime();
        ticksSeen++;
        if (lastTickNanos != 0L) {
            double thisTickMs = (now - lastTickNanos) / 1e6d;
            if (thisTickMs < msptMin) {
                msptMin = thisTickMs;
            }
            if (thisTickMs > msptMax) {
                msptMax = thisTickMs;
            }
        }
        lastTickNanos = now;
    }

    public static synchronized boolean start(MinecraftServer server) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        sessionStartNanos = System.nanoTime();
        ticksSeen = 0;
        msptMin = Double.MAX_VALUE;
        msptMax = 0;
        lastTickNanos = 0L;
        synchronized (lock) {
            selfByMethod.clear();
        }
        totalSamples = 0;
        serverThreadId = server.getRunningThread().threadId();

        samplerThread = new Thread(RadiographieServeur::loop, "lanterne-radiographie-serveur");
        samplerThread.setDaemon(true);
        samplerThread.start();
        return true;
    }

    public static synchronized Path stop(MinecraftServer server, String why) {
        if (!running.compareAndSet(true, false)) {
            return null;
        }
        Thread thread = samplerThread;
        samplerThread = null;
        if (thread != null) {
            try {
                thread.join(1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            return writeReport(server, why);
        } catch (IOException problem) {
            return null;
        }
    }

    private static void loop() {
        while (running.get()) {
            try {
                ThreadInfo info = BEAN.getThreadInfo(serverThreadId, MAX_DEPTH);
                if (info != null) {
                    record(info.getStackTrace());
                }
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ignored) {
                // Un relevé raté ne doit jamais arreter tout l'echantillonnage.
            }
        }
    }

    private static void record(StackTraceElement[] frames) {
        if (frames.length == 0) {
            return;
        }
        StackTraceElement leaf = frames[0];
        String key = leaf.getClassName() + "." + leaf.getMethodName();
        synchronized (lock) {
            totalSamples++;
            selfByMethod.merge(key, 1L, Long::sum);
        }
    }

    private static Path writeReport(MinecraftServer server, String why) throws IOException {
        double sessionSec = Math.max(0.001d, (System.nanoTime() - sessionStartNanos) / 1e9d);
        double avgMspt = ticksSeen > 0 ? (sessionSec * 1000.0d) / ticksSeen : 0;

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path dir = server.getServerDirectory().resolve("config").resolve("lanterne");
        Files.createDirectories(dir);
        Path out = dir.resolve("profil-serveur-" + timestamp + ".txt");

        StringBuilder sb = new StringBuilder();
        sb.append("RADIOGRAPHIE LANTERNE — rapport de session SERVEUR\n");
        sb.append("=".repeat(60)).append('\n');
        sb.append("Fin de session : ").append(why).append('\n');
        sb.append(String.format(Locale.ROOT, "Durée observée  : %.1f s (%d tick(s))%n", sessionSec, ticksSeen));
        sb.append(String.format(Locale.ROOT, "Échantillons    : %d (un toutes les %d ms)%n", totalSamples, INTERVAL_MS));
        sb.append('\n');

        sb.append("RÉSUMÉ\n").append("-".repeat(60)).append('\n');
        sb.append(String.format(Locale.ROOT, "MSPT moyen : %.2f ms (%.1f ticks/s en moyenne)%n",
                avgMspt, ticksSeen / sessionSec));
        if (msptMin <= msptMax) {
            sb.append(String.format(Locale.ROOT, "MSPT min/max (tick à tick) : %.2f / %.2f ms%n", msptMin, msptMax));
        }
        sb.append(String.format(Locale.ROOT,
                "Repère : le tick vanilla vise 50 ms (20 TPS). Au-dessus, le serveur prend du retard.%n"));
        sb.append('\n');

        sb.append("MÉTHODES LES PLUS COÛTEUSES (temps propre, hors ce qu'elles appellent)\n");
        sb.append("-".repeat(60)).append('\n');
        synchronized (lock) {
            selfByMethod.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(25)
                    .forEach(entry -> {
                        double pct = totalSamples > 0 ? 100.0 * entry.getValue() / totalSamples : 0;
                        sb.append(String.format(Locale.ROOT, "  %5.1f %%  %s%n", pct, entry.getKey()));
                    });
        }

        try (OutputStream stream = Files.newOutputStream(out)) {
            stream.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return out;
    }
}
