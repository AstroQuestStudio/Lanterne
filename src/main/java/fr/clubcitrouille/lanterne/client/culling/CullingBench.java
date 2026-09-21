package fr.clubcitrouille.lanterne.client.culling;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Shroud;
import fr.clubcitrouille.lanterne.lab.Scene;

/**
 * Scène de mesure des cullings, déclenchée par propriété JVM — jamais active chez un joueur.
 *
 * <p>{@code -Dlanterne.culling.scene=<charge>[:<nombre>]} (charge : {@code barn}, {@code depot},
 * {@code pen}, {@code foliage}, {@code mixed}… voir {@link Scene#parse}) construit la charge de
 * {@code lab.Scene} à l'origine du monde quelques secondes après l'entrée du joueur, puis le place
 * face à elle, à distance de vue fixe. {@code -Dlanterne.culling.pos=x,z,yaw,pitch} force le point
 * de vue. Le compteur du voile est journalisé toutes les 20 s, ligne {@code [CULLING]}, pour que le
 * banc lise le nombre d'entités vues / écartées / rayons lancés sans capture.
 *
 * <p>La grange est LA charge qui prouve l'iso-rendu : depuis l'extérieur l'écran ne doit montrer
 * aucune bête, avec ou sans voile — deux captures identiques, mais un coût très différent.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class CullingBench {
    private static final String SPEC = System.getProperty("lanterne.culling.scene", "");
    private static final int BUILD_TICK = 100;
    private static final int PLACE_TICK = 140;
    private static final int LOG_EVERY = 400;

    private static int ticks;
    private static boolean announced;
    private static long lastSeen;
    private static long lastVeiled;
    private static long lastRays;

    private CullingBench() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SPEC.isEmpty()) {
            return;
        }
        var server = event.getServer();
        if (server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        ticks++;
        ServerLevel level = server.overworld();
        String[] parts = SPEC.split(":");
        Scene.Kind kind = Scene.parse(parts[0]);
        int count = parts.length > 1 ? parse(parts[1], 800) : 800;

        if (ticks == BUILD_TICK) {
            int born = Scene.build(level, kind, count, 0);
            Lanterne.LOG.info("[CULLING] scène « {} » construite : {} élément(s).", kind, born);
        } else if (ticks == PLACE_TICK) {
            place(level, server.getPlayerList().getPlayers().get(0));
        }
        if (ticks > PLACE_TICK && ticks % LOG_EVERY == 0) {
            long seen = Shroud.seen();
            long veiled = Shroud.veiled();
            long rays = Shroud.rays();
            Lanterne.LOG.info("[CULLING] voile : vues={} écartées={} rayons={} (sur {} ticks : +{} vues, +{} écartées, +{} rayons) coupé={}",
                    seen, veiled, rays, LOG_EVERY, seen - lastSeen, veiled - lastVeiled, rays - lastRays,
                    CullingSwitch.SHROUD_OFF);
            lastSeen = seen;
            lastVeiled = veiled;
            lastRays = rays;
        }
    }

    private static void place(ServerLevel level, ServerPlayer player) {
        double x = 0.5d;
        double z = -26.5d;
        float yaw = 0f;
        float pitch = 6f;
        String pos = System.getProperty("lanterne.culling.pos", "");
        if (!pos.isEmpty()) {
            String[] p = pos.split(",");
            if (p.length == 4) {
                x = Double.parseDouble(p[0]);
                z = Double.parseDouble(p[1]);
                yaw = Float.parseFloat(p[2]);
                pitch = Float.parseFloat(p[3]);
            }
        }
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x),
                (int) Math.floor(z));
        String command = String.format(java.util.Locale.ROOT, "tp %s %.2f %d %.2f %.1f %.1f",
                player.getGameProfile().name(), x, ground + 1, z, yaw, pitch);
        level.getServer().getCommands().performPrefixedCommand(
                level.getServer().createCommandSourceStack(), command);
        if (!announced) {
            announced = true;
            Lanterne.LOG.info("[CULLING] joueur placé : {}", command);
        }
    }

    private static int parse(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }
}
