package fr.clubcitrouille.lanterne.client.bilan;

import java.lang.management.ManagementFactory;

/**
 * Octets alloués par le fil de rendu, à partir de {@code com.sun.management.ThreadMXBean
 * #getThreadAllocatedBytes}. Deux lectures par intervalle (début, fin) : aucun coût par image.
 *
 * <p>Un jeu qui alloue beaucoup par image fait travailler le ramasse-miettes et crée des creux : au-delà
 * de {@link #ALERT_BYTES_PER_FRAME} (2 Mo par image), le bilan le signale. Sans le module
 * {@code jdk.management} (ou si le JVM refuse), la mesure vaut {@code -1} : jamais d'invention.
 */
final class BilanAlloc {
    /** Au-delà de 2 Mo par image, une alerte est écrite. */
    static final double ALERT_BYTES_PER_FRAME = 2.0d * 1024 * 1024;

    private static com.sun.management.ThreadMXBean bean;
    private static boolean tried;
    private static long threadId = -1L;
    private static long lastBytes = -1L;
    private static long lastNanos;

    private BilanAlloc() {}

    private static boolean ready() {
        if (!tried) {
            tried = true;
            try {
                var b = ManagementFactory.getThreadMXBean();
                if (b instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported()) {
                    sun.setThreadAllocatedMemoryEnabled(true);
                    bean = sun;
                }
            } catch (Throwable unavailable) {
                bean = null;
            }
        }
        return bean != null;
    }

    /** À appeler sur le fil de rendu : (re)démarre la fenêtre de mesure. */
    static void start(long nowNanos) {
        if (!ready()) {
            return;
        }
        threadId = Thread.currentThread().threadId();
        lastBytes = bean.getThreadAllocatedBytes(threadId);
        lastNanos = nowNanos;
    }

    /** Octets alloués depuis le dernier {@link #start}/{@link #take}, ou -1 ; remet la fenêtre à zéro. */
    static long take(long nowNanos) {
        if (!ready() || threadId < 0 || lastBytes < 0) {
            return -1L;
        }
        long bytes = bean.getThreadAllocatedBytes(threadId);
        long delta = bytes - lastBytes;
        lastBytes = bytes;
        lastNanos = nowNanos;
        return Math.max(0L, delta);
    }
}
