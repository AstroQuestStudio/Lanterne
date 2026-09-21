package fr.clubcitrouille.lanterne.client.bilan;

import net.minecraft.client.Minecraft;

/**
 * Le jeu est-il bridé ? Une mesure de FPS faite pendant que Minecraft se limite lui-même (AFK,
 * fenêtre sans focus, vsync, plafond de FPS) ne dit rien de la performance du rendu.
 *
 * <p>Deux sources : (1) ce que Minecraft affirme ({@code FramerateLimitTracker.getThrottleReason()}
 * et {@code getFramerateLimit()}, plus les options {@code inactivityFpsLimit}, {@code framerateLimit},
 * {@code enableVsync}) ; (2) ce qu'on MESURE : la part du temps d'image passée dans
 * {@code FramerateLimiter.limitDisplayFPS} (voir {@code BilanLimiterMixin}). Si cette part dépasse
 * {@link #WARN_SHARE}, le bilan écrit « FPS NON REPRESENTATIFS ».
 */
public final class BilanThrottle {
    /** Au-delà de cette part du temps d'image passée à attendre, la mesure est jugée invalide. */
    static final double WARN_SHARE = 0.10d;

    private static long limiterStart;
    private static long waitNanos;
    private static long waitCount;

    private BilanThrottle() {}

    /** Appelé par le mixin, fil de rendu. */
    public static void limiterStart() {
        limiterStart = System.nanoTime();
    }

    /** Appelé par le mixin, fil de rendu. */
    public static void limiterEnd() {
        if (limiterStart != 0L) {
            waitNanos += System.nanoTime() - limiterStart;
            waitCount++;
            limiterStart = 0L;
        }
    }

    /** Remet les compteurs de l'intervalle à zéro. */
    static void reset() {
        waitNanos = 0L;
        waitCount = 0L;
    }

    static double waitMsPerFrame(long frames) {
        return frames <= 0 ? 0d : waitNanos / 1.0e6 / frames;
    }

    /** Part du temps d'image passée à attendre (0..1), à partir de la durée totale de l'intervalle. */
    static double waitShare(long intervalNanos) {
        return intervalNanos <= 0 ? 0d : Math.min(1d, waitNanos / (double) intervalNanos);
    }

    /** {@code inactivityFpsLimit} tel qu'écrit dans options.txt : {@code afk} ou {@code minimized}. */
    static String inactivityOption(Minecraft mc) {
        try {
            return mc.options.inactivityFpsLimit().get().getSerializedName();
        } catch (Throwable unavailable) {
            return "n/a";
        }
    }

    /** Limite de FPS effective décidée par Minecraft à cet instant. */
    static int effectiveLimit(Minecraft mc) {
        try {
            return mc.getFramerateLimitTracker().getFramerateLimit();
        } catch (Throwable unavailable) {
            return -1;
        }
    }

    /** Raison du bridage selon Minecraft : NONE, WINDOW_ICONIFIED, LONG_AFK, SHORT_AFK, OUT_OF_LEVEL_MENU. */
    static String reason(Minecraft mc) {
        try {
            return mc.getFramerateLimitTracker().getThrottleReason().name();
        } catch (Throwable unavailable) {
            return "n/a";
        }
    }

    /**
     * Les causes de bridage vues pendant l'intervalle, séparées par des virgules ; vide = aucune.
     * {@code reasonNow} vient de {@link #reason}.
     */
    static String causes(Minecraft mc, boolean unfocusedSeen, double waitShare) {
        StringBuilder out = new StringBuilder();
        String reason = reason(mc);
        if (!reason.equals("NONE") && !reason.equals("n/a")) {
            out.append(reason);
        }
        if (unfocusedSeen) {
            append(out, "fenetre_sans_focus");
        }
        try {
            if (mc.options.enableVsync().get()) {
                append(out, "vsync");
            }
            int cap = mc.options.framerateLimit().get();
            if (cap > 0 && cap < 260) {
                append(out, "max_fps_" + cap);
            }
        } catch (Throwable unavailable) {
            // options illisibles : on ne devine pas
        }
        if (waitShare > WARN_SHARE) {
            append(out, "attente_limiteur_" + Math.round(waitShare * 100) + "pct");
        }
        return out.toString();
    }

    private static void append(StringBuilder out, String cause) {
        if (out.length() > 0) {
            out.append(',');
        }
        out.append(cause);
    }
}
