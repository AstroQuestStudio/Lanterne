package fr.clubcitrouille.lanterne.client.bilan;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.client.Vigie;

/**
 * Le bilan : des lignes {@code [BILAN]} structurées dans le journal du client, pour diagnostiquer les
 * performances et le rendu SANS capture d'écran ni devinette. Format et exemples : {@code
 * BILAN-FORMAT.md}.
 *
 * <ul>
 *   <li>{@code type=boot} : une fois, à la première image (contexte graphique prêt) — machine, JVM,
 *       GPU, backend, mods de rendu, état du contournement NVIDIA.</li>
 *   <li>{@code type=world} : à chaque entrée en monde — options vidéo, shaders, DH.</li>
 *   <li>{@code type=perf} : toutes les 30 s (réglable) — FPS, 1 % low, temps d'image, temps GPU,
 *       sections, mémoire, ramasse-miettes.</li>
 *   <li>{@code type=event} : changement de shaderpack/dimension, gel d'image (&gt; 100 ms).</li>
 *   <li>{@code type=summary} : à la sortie du monde et à la fermeture du jeu.</li>
 * </ul>
 *
 * <p>Coût : aucune allocation par image (histogramme à pas fixe, requêtes GPU en anneau non
 * bloquantes), une lecture de champs toutes les 30 images. Désactivable : {@code -Dlanterne.bilan=off}.
 * Période : {@code -Dlanterne.bilan.period=<secondes>}. Mesure GPU : {@code
 * -Dlanterne.bilan.gpu=off}.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class Bilan {
    /** Vrai sauf si {@code -Dlanterne.bilan=off}. */
    static final boolean ENABLED = !"off".equalsIgnoreCase(System.getProperty("lanterne.bilan", "on"));
    private static final boolean GPU_TIMING =
            !"off".equalsIgnoreCase(System.getProperty("lanterne.bilan.gpu", "on"));
    private static final long PERIOD_NANOS = periodNanos();
    /** Une image plus longue que ceci est un « gel » et fait l'objet d'un évènement. */
    private static final long SPIKE_NANOS = 100_000_000L;
    private static final int SPIKE_MAX = 50;
    private static final int SAMPLE_EVERY = 30;

    // --- phases mesurées par d'autres parties du code (API publique, très bon marché) ---
    public static final int PHASE_SHADOW = 0;
    public static final int PHASE_TERRAIN = 1;
    public static final int PHASE_TRANSLUCENT = 2;
    public static final int PHASE_COMPOSITE = 3;
    public static final int PHASE_DH = 4;
    public static final int PHASE_ENTITIES = 5;
    public static final int PHASE_GUI = 6;
    private static final String[] PHASE_NAMES =
            {"shadow", "terrain", "translucent", "composite", "dh", "entities", "gui"};
    private static final long[] phaseNanos = new long[PHASE_NAMES.length];
    private static final long[] phaseCount = new long[PHASE_NAMES.length];

    private static final BilanFrames interval = new BilanFrames();
    private static final BilanFrames cpuInterval = new BilanFrames();
    private static final BilanFrames gpuInterval = new BilanFrames();
    private static final BilanFrames session = new BilanFrames();

    private static boolean booted;
    private static boolean broken;
    private static boolean vulkan;
    private static long lastPre;
    private static long preStart;
    private static long intervalStart;
    private static long sessionStart;
    private static int frameCounter;
    private static long unfocusedFrames;
    private static long spikes;
    private static long lastSpikeLog;
    private static long sessionGcCount;
    private static long sessionGcMs;

    private static long sampleCount;
    private static long visibleSum;
    private static int queueMax;

    private static String lastPack = "";
    private static boolean lastShaders;
    private static Object lastDimension;
    private static boolean inWorld;
    private static boolean hookArmed;

    private Bilan() {}

    private static long periodNanos() {
        String raw = System.getProperty("lanterne.bilan.period", "30");
        try {
            long seconds = Long.parseLong(raw.strip());
            return seconds <= 0 ? 0L : seconds * 1_000_000_000L;
        } catch (NumberFormatException bad) {
            return 30_000_000_000L;
        }
    }

    // ---------------------------------------------------------------- API des phases

    /** Début d'une phase : à appeler avec {@link #phaseEnd}. Renvoie 0 si le bilan est coupé. */
    public static long phaseStart() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /** Fin d'une phase ; à appeler sur le fil de rendu uniquement. */
    public static void phaseEnd(int phase, long start) {
        if (start != 0L && phase >= 0 && phase < phaseNanos.length) {
            phaseNanos[phase] += System.nanoTime() - start;
            phaseCount[phase]++;
        }
    }

    // ---------------------------------------------------------------- évènements

    @SubscribeEvent
    static void onFramePre(RenderFrameEvent.Pre event) {
        if (!ENABLED || broken) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            long now = System.nanoTime();
            if (!booted) {
                boot(mc);
            }
            armHook();
            if (mc.level != null) {
                BilanGpu.poll(gpuInterval, BilanBench.gpuSink());
                if (lastPre != 0L) {
                    long dt = now - lastPre;
                    if (!mc.isWindowActive()) {
                        unfocusedFrames++; // fenêtre non focalisée : le jeu ralentit volontairement
                        BilanBench.unfocusedFrame();
                    } else {
                        interval.record(dt);
                        BilanBench.frame(dt);
                        session.record(dt);
                        if (dt > SPIKE_NANOS) {
                            spike(mc, dt, now);
                        }
                    }
                }
                BilanGpu.begin();
                if (++frameCounter % SAMPLE_EVERY == 0) {
                    sample(mc);
                    pollChanges(mc);
                }
                lastPre = now;
            } else {
                lastPre = 0L; // pas de mesure à cheval sur un écran de chargement ou un menu
            }
            preStart = now;
        } catch (Throwable failure) {
            disable(failure);
        }
    }

    @SubscribeEvent
    static void onFramePost(RenderFrameEvent.Post event) {
        if (!ENABLED || broken || !booted) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            long now = System.nanoTime();
            if (mc.level != null) {
                BilanGpu.end();
                cpuInterval.record(now - preStart);
                BilanBench.cpuFrame(now - preStart);
                if (PERIOD_NANOS > 0L && now - intervalStart >= PERIOD_NANOS) {
                    if (interval.count() > 0) {
                        perf(mc, now);
                    }
                    resetInterval(now);
                }
            }
        } catch (Throwable failure) {
            disable(failure);
        }
    }

    @SubscribeEvent
    static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!ENABLED || broken || !booted) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            inWorld = true;
            long now = System.nanoTime();
            sessionStart = now;
            session.reset();
            resetInterval(now);
            spikes = 0;
            sessionGcCount = 0;
            sessionGcMs = 0;
            lastPre = 0L;
            world(mc);
        } catch (Throwable failure) {
            disable(failure);
        }
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (!ENABLED || broken || !booted || !inWorld) {
            return;
        }
        try {
            inWorld = false;
            summary("world-leave");
            lastDimension = null;
        } catch (Throwable failure) {
            disable(failure);
        }
    }

    // ---------------------------------------------------------------- lignes

    private static void boot(Minecraft mc) {
        booted = true;
        vulkan = Vigie.vulkanActif();
        BilanGpu.init(vulkan, GPU_TIMING);
        intervalStart = System.nanoTime();
        BilanLine line = BilanLine.of("boot")
                .kv("lanterne", version("lanterne"))
                .kv("minecraft", version("minecraft"))
                .kv("neoforge", version("neoforge"))
                .kv("os", System.getProperty("os.name", "?") + " " + System.getProperty("os.version", ""))
                .kv("cpu", BilanSystem.cpuName())
                .kv("cores", Runtime.getRuntime().availableProcessors())
                .kv("ram_mb", BilanSystem.totalRamMb())
                .kv("java", System.getProperty("java.version", "?"))
                .kv("heap_max_mb", BilanSystem.heapMaxMb())
                .kv("gc", BilanSystem.gcNames())
                .kv("jvm_args", BilanSystem.jvmArgs())
                .kv("backend", vulkan ? "Vulkan" : "OpenGL")
                .kv("gpu", BilanGpu.renderer)
                .kv("gpu_vendor", BilanGpu.vendor)
                .kv("gpu_kind", BilanGpu.glReady() ? (BilanGpu.integrated() ? "integrated" : "dedicated") : "n/a")
                .kv("vram_mb", BilanGpu.vramMb)
                .kv("gl_version", BilanGpu.glVersion)
                .kv("gl_debug", BilanGpu.glDebugState())
                .kv("glsync_prop", System.getProperty("lanterne.glsync", "unset"))
                .kv("gpu_time", BilanGpu.timingState())
                .kv("inactivity_fps_limit", BilanThrottle.inactivityOption(mc))
                .kv("fps_limit_now", BilanThrottle.effectiveLimit(mc));
        modsState(line);
        line.emit();
    }

    private static void world(Minecraft mc) {
        BilanLine line = BilanLine.of("world")
                .kv("gpu", BilanGpu.renderer)
                .kv("backend", vulkan ? "Vulkan" : "OpenGL");
        options(line, mc);
        modsState(line);
        line.emit();
        pollChanges(mc);
    }

    private static void options(BilanLine line, Minecraft mc) {
        try {
            var o = mc.options;
            var w = mc.getWindow();
            line.kv("render_distance", o.renderDistance().get())
                    .kv("simulation_distance", o.simulationDistance().get())
                    .kv("max_fps", o.framerateLimit().get())
                    .kv("vsync", o.enableVsync().get())
                    .kv("gui_scale", o.guiScale().get())
                    .kv("fov", o.fov().get())
                    .kv("clouds", String.valueOf(o.cloudStatus().get()))
                    .kv("particles", String.valueOf(o.particles().get()))
                    .kv("biome_blend", o.biomeBlendRadius().get())
                    .kv("mipmaps", o.mipmapLevels().get())
                    .kv("entity_distance", o.entityDistanceScaling().get(), 2)
                    .kv("resolution", w.getWidth() + "x" + w.getHeight());
        } catch (Throwable unavailable) {
            line.kv("options", "n/a");
        }
    }

    /** Sodium / Iris / DH : présence, version, mode, shaderpack. */
    /** Même contenu que {@code modsState}, pour le résultat du banc. */
    static void modsStateFor(BilanLine line) {
        modsState(line);
    }

    private static void modsState(BilanLine line) {
        ModList mods = ModList.get();
        boolean sodium = mods.isLoaded("sodium");
        boolean dh = mods.isLoaded("distanthorizons");
        boolean irisVanilla = Boolean.getBoolean("lanterne.irisvanilla");
        String irisMode = sodium ? "active" : (irisVanilla ? "vanilla" : "inert");
        line.kv("sodium", sodium ? version("sodium") : "absent")
                .kv("iris", irisMode)
                .kv("dh", dh ? version("distanthorizons") : "absent");
        boolean irisUsable = irisMode.equals("active") || irisMode.equals("vanilla");
        boolean inUse = false;
        if (irisUsable) {
            try {
                inUse = BilanIris.packInUse();
                line.kv("shaders", BilanIris.shadersEnabled())
                        .kv("shaderpack", BilanIris.packName())
                        .kv("shader_active", inUse)
                        .kv("shadow_distance", BilanIris.shadowDistance());
            } catch (Throwable unavailable) {
                line.kv("shaders", "n/a");
            }
        } else {
            line.kv("shaders", false);
        }
        if (dh) {
            boolean fullIntegration = Boolean.getBoolean("lanterne.dhshaders");
            line.kv("dh_mode", inUse ? (fullIntegration ? "full-under-shaders" : "cut-under-shaders") : "normal");
        }
    }

    private static void perf(Minecraft mc, long now) {
        double seconds = (now - intervalStart) / 1.0e9;
        long[] gc = BilanSystem.gcSinceLast();
        sessionGcCount += gc[0];
        sessionGcMs += gc[1];
        ClientLevel level = mc.level;
        BilanLine line = BilanLine.of("perf")
                .kv("seconds", seconds, 1)
                .kv("frames", interval.count())
                .kv("fps", interval.fps(), 1)
                .kv("low1", interval.lowFps(0.01), 1)
                .kv("low01", interval.lowFps(0.001), 1)
                .kv("ms_mean", interval.meanMs(), 2)
                .kv("ms_p99", interval.percentileMs(0.99), 2)
                .kv("ms_max", interval.maxMs(), 1)
                .kv("cpu_ms_mean", cpuInterval.meanMs(), 2)
                .kv("cpu_ms_p99", cpuInterval.percentileMs(0.99), 2);
        if (gpuInterval.count() > 0) {
            line.kv("gpu_ms_mean", gpuInterval.meanMs(), 2)
                    .kv("gpu_ms_p99", gpuInterval.percentileMs(0.99), 2)
                    .kv("gpu_samples", gpuInterval.count());
        } else {
            line.kv("gpu_ms_mean", "n/a").kv("gpu_time", BilanGpu.timingState());
        }
        long frames = Math.max(1L, interval.count());
        for (int i = 0; i < PHASE_NAMES.length; i++) {
            if (phaseCount[i] > 0) {
                line.kv("phase_" + PHASE_NAMES[i] + "_ms", phaseNanos[i] / 1.0e6 / frames, 3);
            }
        }
        try {
            line.kv("sections_visible_avg", sampleCount == 0 ? 0d : visibleSum / (double) sampleCount, 0)
                    .kv("sections_visible", mc.levelRenderer.visibleSections().size())
                    .kv("sections_near", mc.levelRenderer.nearbyVisibleSections().size())
                    .kv("mesh_queue", mc.levelRenderer.sectionRenderDispatcher().getCompileQueueSize())
                    .kv("mesh_queue_max", queueMax)
                    .kv("mesh_free_buffers", mc.levelRenderer.sectionRenderDispatcher().getFreeBufferCount());
            if (level != null) {
                line.kv("entities", level.getEntityCount())
                        .kv("chunks_loaded", level.getChunkSource().getLoadedChunksCount());
            }
            line.kv("particles", firstNumber(mc.particleEngine.countParticles()));
        } catch (Throwable unavailable) {
            line.kv("sections", "n/a");
        }
        line.kv("heap_used_mb", BilanSystem.heapUsedMb())
                .kv("heap_max_mb", BilanSystem.heapMaxMb())
                .kv("gc_count", gc[0])
                .kv("gc_ms", gc[1])
                .kv("unfocused_frames", unfocusedFrames)
                .kv("spikes", spikes);
        // Bridage : la part du temps d'image passée à ATTENDRE dans le limiteur de FPS, le temps
        // d'image « utile » hors attente, la limite effective et la raison selon Minecraft.
        long frames2 = Math.max(1L, interval.count());
        double share = BilanThrottle.waitShare(now - intervalStart);
        double waitMs = BilanThrottle.waitMsPerFrame(frames2);
        String causes = BilanThrottle.causes(mc, unfocusedFrames > 0, share);
        line.kv("limiter_wait_ms", waitMs, 2)
                .kv("limiter_share_pct", share * 100d, 1)
                .kv("ms_useful", Math.max(0d, interval.meanMs() - waitMs), 2)
                .kv("fps_limit", BilanThrottle.effectiveLimit(mc))
                .kv("throttle", BilanThrottle.reason(mc))
                .kv("valid", causes.isEmpty())
                .kv("invalid_causes", causes.isEmpty() ? "none" : causes);
        line.emit();
        if (!causes.isEmpty()) {
            Lanterne.LOG.warn("[BILAN] AVERTISSEMENT : FPS mesures NON REPRESENTATIFS : jeu bride ({}). "
                    + "Temps d'image utile hors attente : {} ms ({} FPS equivalents).",
                    causes, String.format(java.util.Locale.ROOT, "%.2f", Math.max(0d, interval.meanMs() - waitMs)),
                    String.format(java.util.Locale.ROOT, "%.0f",
                            1000.0d / Math.max(0.01d, interval.meanMs() - waitMs)));
        }
    }

    private static void spike(Minecraft mc, long dt, long now) {
        spikes++;
        if (spikes > SPIKE_MAX || now - lastSpikeLog < 1_000_000_000L) {
            return;
        }
        lastSpikeLog = now;
        BilanLine.of("event")
                .kv("event", "spike")
                .kv("ms", dt / 1.0e6, 1)
                .kv("screen", mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName())
                .kv("heap_used_mb", BilanSystem.heapUsedMb())
                .kv("mesh_queue", safeQueue(mc))
                .emit();
    }

    private static int safeQueue(Minecraft mc) {
        try {
            return mc.levelRenderer.sectionRenderDispatcher().getCompileQueueSize();
        } catch (Throwable unavailable) {
            return -1;
        }
    }

    private static void sample(Minecraft mc) {
        try {
            visibleSum += mc.levelRenderer.visibleSections().size();
            sampleCount++;
            int queue = mc.levelRenderer.sectionRenderDispatcher().getCompileQueueSize();
            if (queue > queueMax) {
                queueMax = queue;
            }
        } catch (Throwable unavailable) {
            // pas encore de monde rendu : on réessaie plus tard
        }
    }

    /** Changement de dimension, de shaderpack, activation/désactivation des shaders. */
    private static void pollChanges(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level != null) {
            Object dim = level.dimension();
            if (lastDimension != null && dim != lastDimension) {
                BilanLine.of("event").kv("event", "dimension")
                        .kv("to", String.valueOf(level.dimension().identifier())).emit();
            }
            lastDimension = dim;
        }
        boolean sodium = ModList.get().isLoaded("sodium");
        if (!sodium && !Boolean.getBoolean("lanterne.irisvanilla")) {
            return;
        }
        try {
            String pack = BilanIris.packName();
            boolean shaders = BilanIris.shadersEnabled();
            if (!pack.equals(lastPack) || shaders != lastShaders) {
                if (!lastPack.isEmpty() || shaders) {
                    BilanLine.of("event").kv("event", "shaderpack")
                            .kv("shaderpack", pack).kv("shaders", shaders)
                            .kv("shader_active", BilanIris.packInUse())
                            .kv("shadow_distance", BilanIris.shadowDistance()).emit();
                }
                lastPack = pack;
                lastShaders = shaders;
                resetInterval(System.nanoTime()); // un intervalle ne mélange jamais deux réglages
            }
        } catch (Throwable unavailable) {
            // Iris pas encore initialisé
        }
    }

    private static void summary(String reason) {
        long now = System.nanoTime();
        long[] gc = BilanSystem.gcSinceLast();
        sessionGcCount += gc[0];
        sessionGcMs += gc[1];
        BilanLine.of("summary")
                .kv("reason", reason)
                .kv("seconds", sessionStart == 0L ? 0d : (now - sessionStart) / 1.0e9, 1)
                .kv("frames", session.count())
                .kv("fps", session.fps(), 1)
                .kv("low1", session.lowFps(0.01), 1)
                .kv("low01", session.lowFps(0.001), 1)
                .kv("ms_mean", session.meanMs(), 2)
                .kv("ms_p99", session.percentileMs(0.99), 2)
                .kv("ms_max", session.maxMs(), 1)
                .kv("spikes", spikes)
                .kv("gc_count", sessionGcCount)
                .kv("gc_ms", sessionGcMs)
                .kv("gpu", BilanGpu.renderer)
                .kv("gpu_time", BilanGpu.timingState())
                .emit();
    }

    private static void resetInterval(long now) {
        interval.reset();
        cpuInterval.reset();
        gpuInterval.reset();
        java.util.Arrays.fill(phaseNanos, 0L);
        java.util.Arrays.fill(phaseCount, 0L);
        BilanThrottle.reset();
        unfocusedFrames = 0;
        visibleSum = 0;
        sampleCount = 0;
        queueMax = 0;
        intervalStart = now;
    }

    /** À la fermeture de la JVM sans passer par une sortie de monde (fermeture directe du jeu). */
    private static void armHook() {
        if (hookArmed) {
            return;
        }
        hookArmed = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (inWorld && session.count() > 0) {
                    inWorld = false;
                    summary("shutdown");
                }
            } catch (Throwable ignored) {
                // la fermeture ne doit jamais échouer à cause du bilan
            }
        }, "Lanterne-bilan-fin"));
    }

    private static void disable(Throwable failure) {
        broken = true;
        Lanterne.LOG.warn("[BILAN] erreur interne, bilan interrompu pour cette session : {}",
                failure.toString());
    }

    private static String version(String modId) {
        try {
            return ModList.get().getModContainerById(modId)
                    .map(c -> c.getModInfo().getVersion().toString()).orElse("absent");
        } catch (Throwable unavailable) {
            return "n/a";
        }
    }

    /** Premier entier d'un texte comme « P: 12. T: 40 » — le nombre de particules affichées. */
    private static long firstNumber(String text) {
        if (text == null) {
            return -1L;
        }
        long value = -1L;
        int i = 0;
        while (i < text.length() && !Character.isDigit(text.charAt(i))) {
            i++;
        }
        if (i < text.length()) {
            value = 0;
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                value = value * 10 + (text.charAt(i) - '0');
                i++;
            }
        }
        return value;
    }
}
