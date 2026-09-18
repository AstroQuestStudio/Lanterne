package fr.clubcitrouille.lanterne.lab;

import net.minecraft.client.Minecraft;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.client.upscale.Upscale;

/**
 * Instrumentation temporaire de diagnostic pour l'incident 450→130 FPS / "Resource reload failed" /
 * flashs, rapporté en jeu réel. Journalise le temps d'image réel et l'état de la lentille toutes les
 * ~3 secondes, sans construire aucune scène (contrairement à Glass) : n'importe quel monde, y compris
 * une vraie partie importée, peut être observé tel quel.
 *
 * <p>À retirer une fois la cause confirmée — voir le rapport de ce fork.
 */
public final class Observe {
    private static long lastLogAt;
    private static int frames;
    private static long accumNanos;

    private Observe() {}

    public static void frame() {
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.nanoTime();
        long frameNs = minecraft.getFrameTimeNs();
        frames++;
        accumNanos += frameNs;

        if (lastLogAt == 0L) {
            lastLogAt = now;
            return;
        }
        if (now - lastLogAt < 3_000_000_000L) {
            return;
        }

        double avgMs = (accumNanos / (double) frames) / 1e6d;
        double fps = frames / ((now - lastLogAt) / 1e9d);
        var player = minecraft.player;
        String pos = player != null
                ? String.format(java.util.Locale.ROOT, "%.0f,%.0f,%.0f", player.getX(), player.getY(), player.getZ())
                : "?";
        Lanterne.LOG.info(
                "[OBSERVE] {} image(s)/s (moy {} ms/image) - pos={} - lentille={} - cassee={}",
                String.format(java.util.Locale.ROOT, "%.1f", fps),
                String.format(java.util.Locale.ROOT, "%.2f", avgMs),
                pos, Upscale.describe(), Upscale.broken());

        frames = 0;
        accumNanos = 0L;
        lastLogAt = now;
    }
}
