package fr.clubcitrouille.lanterne.client.bilan;

import java.util.Locale;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL43C;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Identité du GPU réellement utilisé, et mesure du temps GPU par image sans jamais attendre.
 *
 * <h2>Mesure du temps GPU</h2>
 *
 * <p>Un anneau de {@link #RING} requêtes {@code GL_TIME_ELAPSED} : on en ouvre une au début de
 * l'image et on la ferme à la fin ; les résultats sont lus plus tard, seulement quand le pilote dit
 * qu'ils sont prêts ({@code GL_QUERY_RESULT_AVAILABLE}). Aucun {@code glFinish}, aucune attente : si
 * l'anneau est plein, l'image n'est simplement pas mesurée. Coût par image : deux appels GL.
 *
 * <p>Sous Vulkan, ces appels GL n'existent pas : la mesure est alors désactivée
 * ({@code gpu_time=off}). Si le premier essai déclenche une erreur GL (une autre partie du jeu utilise
 * déjà {@code GL_TIME_ELAPSED}), la mesure se coupe d'elle-même et le dit.
 */
final class BilanGpu {
    static final int RING = 4;

    private static boolean glReady;
    private static boolean timing;
    private static String timingState = "pending";
    private static final int[] queries = new int[RING];
    private static final boolean[] pending = new boolean[RING];
    private static int writeSlot = -1;
    private static int checkedFrames;

    static String renderer = "n/a";
    static String vendor = "n/a";
    static String glVersion = "n/a";
    static long vramMb = -1L;

    private BilanGpu() {}

    /** À appeler une fois, sur le fil de rendu, quand le contexte existe. */
    static void init(boolean vulkan, boolean wantTiming) {
        if (vulkan) {
            timingState = "off-vulkan";
            return;
        }
        try {
            renderer = orNa(GL11C.glGetString(GL11C.GL_RENDERER));
            vendor = orNa(GL11C.glGetString(GL11C.GL_VENDOR));
            glVersion = orNa(GL11C.glGetString(GL11C.GL_VERSION));
            var caps = GL.getCapabilities();
            if (caps.GL_NVX_gpu_memory_info) {
                vramMb = GL11C.glGetInteger(0x9048) / 1024L; // GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX, en Ko
            }
            glReady = true;
            if (!wantTiming) {
                timingState = "off-demande";
                return;
            }
            if (!caps.OpenGL33 && !caps.GL_ARB_timer_query) {
                timingState = "off-non-supporte";
                return;
            }
            for (int i = 0; i < RING; i++) {
                queries[i] = GL15C.glGenQueries();
            }
            timing = true;
            timingState = "on";
        } catch (Throwable failure) {
            timingState = "off-erreur";
            Lanterne.LOG.warn("[BILAN] init GPU impossible : {}", failure.toString());
        }
    }

    static boolean glReady() {
        return glReady;
    }

    static String timingState() {
        return timingState;
    }

    /** Vrai si le pilote prétend un GPU intégré (nom/fabricant), faux pour un GPU dédié. */
    static boolean integrated() {
        String s = (renderer + ' ' + vendor).toLowerCase(Locale.ROOT);
        if (s.contains("nvidia") || s.contains("geforce") || s.contains("quadro")) {
            return false;
        }
        if (s.contains("radeon rx") || s.contains("radeon pro") || s.contains("radeon vii")) {
            return false;
        }
        return s.contains("intel") || s.contains("uhd") || s.contains("iris")
                || s.contains("radeon(tm) graphics") || s.contains("radeon graphics")
                || s.contains("vega") || s.contains("apple") || s.contains("mali") || s.contains("adreno");
    }

    static String glDebugState() {
        if (!glReady) {
            return "n/a";
        }
        try {
            boolean out = GL11C.glIsEnabled(GL43C.GL_DEBUG_OUTPUT);
            boolean sync = GL11C.glIsEnabled(GL43C.GL_DEBUG_OUTPUT_SYNCHRONOUS);
            return "output=" + out + ",synchronous=" + sync;
        } catch (Throwable unavailable) {
            return "n/a";
        }
    }

    /** Lit les requêtes prêtes et renvoie la dernière durée GPU en ns, ou -1 s'il n'y en a pas. */
    static long poll(BilanFrames sink, BilanFrames second) {
        if (!timing) {
            return -1L;
        }
        long last = -1L;
        for (int i = 0; i < RING; i++) {
            if (!pending[i]) {
                continue;
            }
            if (GL15C.glGetQueryObjecti(queries[i], GL15C.GL_QUERY_RESULT_AVAILABLE) != 0) {
                long nanos = GL33C.glGetQueryObjecti64(queries[i], GL15C.GL_QUERY_RESULT);
                pending[i] = false;
                if (nanos > 0) {
                    sink.record(nanos);
                    if (second != null) {
                        second.record(nanos);
                    }
                    last = nanos;
                }
            }
        }
        return last;
    }

    static void begin() {
        writeSlot = -1;
        if (!timing) {
            return;
        }
        for (int i = 0; i < RING; i++) {
            if (!pending[i]) {
                if (checkedFrames < 3) {
                    // vide les erreurs laissées par d'autres parties du jeu : seule NOTRE erreur compte
                    for (int drain = 0; drain < 8 && GL11C.glGetError() != 0; drain++) {
                        // rien
                    }
                }
                GL15C.glBeginQuery(GL33C.GL_TIME_ELAPSED, queries[i]);
                if (checkedFrames < 3) {
                    checkedFrames++;
                    int error = GL11C.glGetError();
                    if (error != 0) {
                        timing = false;
                        timingState = "off-erreur-gl-0x" + Integer.toHexString(error);
                        return;
                    }
                }
                writeSlot = i;
                return;
            }
        }
    }

    static void end() {
        if (!timing || writeSlot < 0) {
            return;
        }
        GL15C.glEndQuery(GL33C.GL_TIME_ELAPSED);
        pending[writeSlot] = true;
        writeSlot = -1;
    }

    private static String orNa(String s) {
        return s == null || s.isBlank() ? "n/a" : s;
    }
}
