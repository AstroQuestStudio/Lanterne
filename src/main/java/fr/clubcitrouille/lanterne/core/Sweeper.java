package fr.clubcitrouille.lanterne.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La minuterie du balai : quand passer, et qui prévenir.
 *
 * <h2>Prévenir n'est pas une politesse, c'est la seule chose qui rend le balai acceptable</h2>
 *
 * <p>Un joueur qui voit son butin disparaître sans avertissement conclut que le serveur a un bogue.
 * Le même joueur, prévenu dix secondes plus tôt, va le ramasser. C'est la même suppression, et ce
 * n'est pas du tout la même expérience.
 *
 * <p>Trois avertissements : soixante secondes, dix, puis trois. Le premier laisse le temps de finir
 * ce qu'on est en train de faire et de revenir ramasser, le second celui de courir, le troisième
 * celui de lâcher ce qu'on fait.
 *
 * <h2>Ce que la minuterie ne fait pas</h2>
 *
 * <p>Elle ne compte pas les ticks d'un monde, mais ceux du <b>serveur</b>. Un balai qui tournerait
 * par monde passerait trois fois sur un serveur à trois dimensions, avec trois avertissements
 * décalés. Il passe une fois, et balaie toutes les dimensions au même instant.
 */
public final class Sweeper {
    /** Ticks par minute. */
    private static final int MINUTE = 1200;

    /** Avertissements, en ticks avant le passage. */
    private static final int[] HERALDS = {1200, 200, 60};

    private static long countdown = -1L;
    private static int lastWarned = -1;

    private Sweeper() {}

    /** Fait avancer la minuterie. Appelé une fois par tick du serveur. */
    public static void tick(MinecraftServer server) {
        if (!Settings.broom()) {
            countdown = -1L;
            return;
        }
        if (countdown < 0L) {
            rearm();
            return;
        }

        countdown--;
        for (int herald : HERALDS) {
            if (countdown == herald && lastWarned != herald) {
                lastWarned = herald;
                Broom.warn(server, Math.round(herald / 20f));
            }
        }

        if (countdown <= 0L) {
            run(server);
            rearm();
        }
    }

    /** Passe le balai sur tous les mondes, tout de suite. */
    public static Broom.Haul run(MinecraftServer server) {
        int items = 0;
        int mobs = 0;
        int arrows = 0;
        for (ServerLevel level : server.getAllLevels()) {
            Broom.Haul part = Broom.sweep(level,
                    Config.BROOM_ITEMS.get(), Config.BROOM_MOBS.get(), Config.BROOM_ARROWS.get());
            items += part.items();
            mobs += part.mobs();
            arrows += part.arrows();
        }
        Broom.Haul haul = new Broom.Haul(items, mobs, arrows);
        Broom.announce(server, haul);
        return haul;
    }

    /** Remet le compte à rebours à sa période. */
    public static void rearm() {
        countdown = (long) Config.BROOM_PERIOD.get() * MINUTE;
        lastWarned = -1;
        Lanterne.LOG.info("[BALAI] prochain passage dans {} minute(s).", Config.BROOM_PERIOD.get());
    }

    /** Ticks restants avant le prochain passage, ou -1 si le balai est éteint. */
    public static long remaining() {
        return Settings.broom() ? countdown : -1L;
    }
}
