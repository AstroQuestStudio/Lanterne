package fr.clubcitrouille.lanterne.client.bilan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.gamerules.GameRules;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * MODE BANC : la même scène à chaque mesure, sans clavier. Activé par {@code -Dlanterne.bench=true}.
 *
 * <p>À l'entrée dans un monde solo (serveur intégré) : temps figé au coucher de soleil, météo claire
 * figée, plus d'apparition de mobs, joueur immobile et invulnérable à une position et une orientation
 * fixes, limite de FPS d'inactivité neutralisée. Après {@code warmup} secondes de chauffe puis
 * {@code measure} secondes de mesure, un enregistrement « BENCH RESULT » est écrit dans le journal
 * ({@code [BILAN] type=bench}) et dans {@code logs/lanterne-bench-result.txt} ; le jeu se ferme si
 * {@code -Dlanterne.bench.exit=true}.
 *
 * <h2>Propriétés</h2>
 * <ul>
 *   <li>{@code lanterne.bench.time} : heure du jour en ticks (défaut 12500 = coucher de soleil, ombres
 *       longues : le cas le plus coûteux pour les ombres, et reproductible).</li>
 *   <li>{@code lanterne.bench.pos} : {@code x,y,z} (défaut : là où le joueur apparaît, c'est-à-dire le
 *       point d'apparition du monde ; comparer des mesures exige donc le MÊME monde).</li>
 *   <li>{@code lanterne.bench.rot} : {@code yaw,pitch} (défaut {@code 270,-3} : plein ouest, vers le
 *       soleil couchant, horizon légèrement au-dessus du centre de l'écran : beaucoup de terrain
 *       lointain et les ombres les plus longues).</li>
 *   <li>{@code lanterne.bench.warmup} (défaut 30 s), {@code lanterne.bench.measure} (défaut 60 s).</li>
 *   <li>{@code lanterne.bench.exit=true} : fermer le jeu proprement après le résultat.</li>
 * </ul>
 * Les commandes passent par le serveur intégré : aucune dépendance aux permissions du joueur.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class BilanBench {
    static final boolean ENABLED = Boolean.getBoolean("lanterne.bench");

    private enum Phase { WAIT_WORLD, SETTLE, WARMUP, MEASURE, DONE }

    private static Phase phase = Phase.WAIT_WORLD;
    private static long phaseStart;
    private static int settleTicks;
    private static long time = 12500L;
    private static double[] pos;
    private static float yaw = 270f;
    private static float pitch = -3f;
    private static long warmupNanos = 30_000_000_000L;
    private static long measureNanos = 60_000_000_000L;
    private static boolean exit;
    private static boolean configured;

    private static final BilanFrames frames = new BilanFrames();
    private static final BilanFrames cpu = new BilanFrames();
    private static final BilanFrames gpu = new BilanFrames();
    private static long unfocused;
    private static double appliedX;
    private static double appliedY;
    private static double appliedZ;

    private BilanBench() {}

    static boolean active() {
        return ENABLED && phase != Phase.DONE;
    }

    static BilanFrames gpuSink() {
        return ENABLED && phase == Phase.MEASURE ? gpu : null;
    }

    /** Une durée d'image (fenêtre focalisée) : comptée seulement pendant la mesure. */
    static void frame(long nanos) {
        if (ENABLED && phase == Phase.MEASURE) {
            frames.record(nanos);
        }
    }

    static void cpuFrame(long nanos) {
        if (ENABLED && phase == Phase.MEASURE) {
            cpu.record(nanos);
        }
    }

    static void unfocusedFrame() {
        if (ENABLED && phase == Phase.MEASURE) {
            unfocused++;
        }
    }

    private static void configure() {
        configured = true;
        time = parseLong("lanterne.bench.time", 12500L);
        warmupNanos = parseLong("lanterne.bench.warmup", 30L) * 1_000_000_000L;
        measureNanos = parseLong("lanterne.bench.measure", 60L) * 1_000_000_000L;
        exit = Boolean.getBoolean("lanterne.bench.exit");
        String p = System.getProperty("lanterne.bench.pos");
        if (p != null) {
            String[] parts = p.split(",");
            if (parts.length == 3) {
                try {
                    pos = new double[] {Double.parseDouble(parts[0].strip()),
                            Double.parseDouble(parts[1].strip()), Double.parseDouble(parts[2].strip())};
                } catch (NumberFormatException bad) {
                    pos = null;
                }
            }
        }
        String r = System.getProperty("lanterne.bench.rot");
        if (r != null) {
            String[] parts = r.split(",");
            if (parts.length == 2) {
                try {
                    yaw = Float.parseFloat(parts[0].strip());
                    pitch = Float.parseFloat(parts[1].strip());
                } catch (NumberFormatException bad) {
                    // valeurs par défaut conservées
                }
            }
        }
    }

    private static long parseLong(String key, long fallback) {
        try {
            return Long.parseLong(System.getProperty(key, String.valueOf(fallback)).strip());
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED || phase == Phase.DONE) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (!configured) {
                configure();
            }
            // Mode sans entrée : ni « AFK » (la limite à ~30 FPS après une minute sans touche), ni plafond.
            mc.getFramerateLimitTracker().onInputReceived();
            mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);

            MinecraftServer server = mc.getSingleplayerServer();
            if (mc.level == null || mc.player == null || server == null) {
                phase = Phase.WAIT_WORLD;
                return;
            }
            long now = System.nanoTime();
            switch (phase) {
                case WAIT_WORLD -> {
                    phase = Phase.SETTLE;
                    settleTicks = 0;
                    server.execute(() -> setupOnServer(server));
                }
                case SETTLE -> {
                    // laisse le temps aux chunks autour de la position figée d'être chargés/maillés
                    if (++settleTicks >= 100) {
                        phase = Phase.WARMUP;
                        phaseStart = now;
                        BilanLine.of("event").kv("event", "bench-warmup")
                                .kv("seconds", warmupNanos / 1.0e9, 0).emit();
                    }
                }
                case WARMUP -> {
                    if (now - phaseStart >= warmupNanos) {
                        frames.reset();
                        cpu.reset();
                        gpu.reset();
                        unfocused = 0;
                        phase = Phase.MEASURE;
                        phaseStart = now;
                        BilanLine.of("event").kv("event", "bench-measure")
                                .kv("seconds", measureNanos / 1.0e9, 0).emit();
                    }
                }
                case MEASURE -> {
                    if (now - phaseStart >= measureNanos) {
                        result(mc, now - phaseStart);
                        phase = Phase.DONE;
                        if (exit) {
                            mc.execute(mc::stop);
                        }
                    }
                }
                default -> { }
            }
        } catch (Throwable failure) {
            Lanterne.LOG.warn("[BILAN] mode banc interrompu : {}", failure.toString());
            phase = Phase.DONE;
        }
    }

    /** Sur le fil du serveur intégré : règles, horloge, météo, position du joueur. */
    private static void setupOnServer(MinecraftServer server) {
        try {
            var rules = server.getGameRules();
            rules.set(GameRules.ADVANCE_TIME, false, server);
            rules.set(GameRules.ADVANCE_WEATHER, false, server);
            rules.set(GameRules.SPAWN_MOBS, false, server);
            rules.set(GameRules.SPAWN_MONSTERS, false, server);
            rules.set(GameRules.SPAWN_PATROLS, false, server);

            ServerLevel overworld = server.overworld();
            var clock = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)
                    .getOrThrow(WorldClocks.OVERWORLD);
            var manager = overworld.clockManager();
            if (manager instanceof net.minecraft.world.clock.ServerClockManager serverClock) {
                serverClock.setTotalTicks(clock, time);
                serverClock.setPaused(clock, true);
            }
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), "weather clear");

            if (server.getPlayerList().getPlayers().isEmpty()) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            double x = pos != null ? pos[0] : player.getX();
            double y = pos != null ? pos[1] : player.getY();
            double z = pos != null ? pos[2] : player.getZ();
            player.teleportTo(player.level() instanceof ServerLevel sl ? sl : overworld,
                    x, y, z, Set.of(), yaw, pitch, true);
            player.setPermanentlyInvulnerable(true);
            player.setNoGravity(true);
            player.setDeltaMovement(0, 0, 0);
            appliedX = x;
            appliedY = y;
            appliedZ = z;
            Lanterne.LOG.info("[BILAN] banc : scene figee (heure {}, meteo claire, pos {},{},{} rot {},{}).",
                    overworld.getOverworldClockTime(), fmt(x), fmt(y), fmt(z), yaw, pitch);
        } catch (Throwable failure) {
            Lanterne.LOG.warn("[BILAN] banc : mise en place impossible : {}", failure.toString());
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static void result(Minecraft mc, long measuredNanos) {
        double seconds = measuredNanos / 1.0e9;
        String causes = BilanThrottle.causes(mc, unfocused > 0, BilanThrottle.waitShare(measuredNanos));
        BilanLine line = BilanLine.of("bench")
                .kv("result", "BENCH RESULT")
                .kv("seconds", seconds, 1)
                .kv("frames", frames.count())
                .kv("fps", frames.fps(), 1)
                .kv("low1", frames.lowFps(0.01), 1)
                .kv("low01", frames.lowFps(0.001), 1)
                .kv("ms_mean", frames.meanMs(), 2)
                .kv("ms_p99", frames.percentileMs(0.99), 2)
                .kv("ms_max", frames.maxMs(), 1)
                .kv("cpu_ms_mean", cpu.meanMs(), 2);
        if (gpu.count() > 0) {
            line.kv("gpu_ms_mean", gpu.meanMs(), 2).kv("gpu_ms_p99", gpu.percentileMs(0.99), 2);
        } else {
            line.kv("gpu_ms_mean", "n/a").kv("gpu_time", BilanGpu.timingState());
        }
        line.kv("gpu", BilanGpu.renderer)
                .kv("gpu_kind", BilanGpu.integrated() ? "integrated" : "dedicated")
                .kv("backend", Boolean.getBoolean("lanterne.vulkan") ? "Vulkan" : BilanGpu.glReady() ? "OpenGL" : "n/a")
                .kv("scene_time", time)
                .kv("scene_pos", fmt(appliedX) + "," + fmt(appliedY) + "," + fmt(appliedZ))
                .kv("scene_rot", yaw + "," + pitch)
                .kv("unfocused_frames", unfocused)
                .kv("valid", causes.isEmpty() && frames.count() > 0)
                .kv("invalid_causes", causes.isEmpty() ? "none" : causes);
        Bilan.modsStateFor(line);
        line.emit();
        Lanterne.LOG.info("BENCH RESULT : {} FPS moyens, 1 % low {}, {} ms/image, GPU {} ({} images en {} s){}",
                fmt(frames.fps()), fmt(frames.lowFps(0.01)), String.format(Locale.ROOT, "%.2f", frames.meanMs()),
                BilanGpu.renderer, frames.count(), fmt(seconds),
                causes.isEmpty() ? "" : " — MESURE NON VALIDE : " + causes);
        writeResultFile(frames, cpu, gpu, seconds, causes);
    }

    private static void writeResultFile(BilanFrames f, BilanFrames c, BilanFrames g, double seconds, String causes) {
        try {
            Path logs = FMLPaths.GAMEDIR.get().resolve("logs");
            Files.createDirectories(logs);
            String text = "BENCH RESULT\n"
                    + "fps=" + fmt(f.fps()) + "\n"
                    + "low1=" + fmt(f.lowFps(0.01)) + "\n"
                    + "low01=" + fmt(f.lowFps(0.001)) + "\n"
                    + "ms_mean=" + String.format(Locale.ROOT, "%.2f", f.meanMs()) + "\n"
                    + "ms_p99=" + String.format(Locale.ROOT, "%.2f", f.percentileMs(0.99)) + "\n"
                    + "cpu_ms_mean=" + String.format(Locale.ROOT, "%.2f", c.meanMs()) + "\n"
                    + "gpu_ms_mean=" + (g.count() > 0 ? String.format(Locale.ROOT, "%.2f", g.meanMs()) : "n/a") + "\n"
                    + "frames=" + f.count() + "\n"
                    + "seconds=" + fmt(seconds) + "\n"
                    + "gpu=" + BilanGpu.renderer + "\n"
                    + "valid=" + (causes.isEmpty() && f.count() > 0) + "\n"
                    + "invalid_causes=" + (causes.isEmpty() ? "none" : causes) + "\n";
            Files.writeString(logs.resolve("lanterne-bench-result.txt"), text, StandardCharsets.UTF_8);
        } catch (IOException unwritable) {
            Lanterne.LOG.warn("[BILAN] resultat du banc non ecrit : {}", unwritable.toString());
        }
    }
}
