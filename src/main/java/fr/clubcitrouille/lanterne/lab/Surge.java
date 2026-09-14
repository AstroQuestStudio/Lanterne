package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Tide;

/**
 * L'épreuve de la marée : descend-elle quand il le faut, et remonte-t-elle ensuite ?
 *
 * <h2>Pourquoi ce module ne peut pas être éprouvé par le banc</h2>
 *
 * <p>Le banc de vitesse endort la marée, et il a raison de le faire : elle changerait les distances
 * de vue et de simulation en cours de mesure, si bien que les deux phases compareraient des mondes
 * de tailles différentes en croyant comparer deux versions du même code.
 *
 * <p>Mais un module qu'aucune épreuve ne touche est un module dont personne ne sait s'il fonctionne.
 * Et celui-ci est le seul du mod qui <b>change ce que le joueur voit</b> : s'il descendait sans
 * jamais remonter, il aurait silencieusement rogné l'horizon de tout le monde pour toujours.
 *
 * <h2>Les deux questions, et la seconde est la vraie</h2>
 *
 * <ol>
 *   <li><b>Descend-elle ?</b> On charge le serveur jusqu'à dépasser la cible, et l'on regarde si les
 *       distances reculent. C'est la partie facile.</li>
 *   <li><b>Remonte-t-elle ?</b> On retire la charge, et l'on regarde si elles reviennent au plafond.
 *       C'est la partie qui compte : un réglage qui ne sait que se dégrader n'est pas une marée,
 *       c'est une avarie.</li>
 * </ol>
 *
 * <p>Le verdict exige les deux. Une descente sans remontée est déclarée non conforme, aussi
 * fermement qu'une absence de descente.
 */
public final class Surge {
    /**
     * Villageois posés pour faire souffrir le serveur.
     *
     * <p>Mille deux cents ne suffisaient pas : le serveur tenait à vingt-neuf millisecondes, sous la
     * cible de la marée, qui n'avait donc aucune raison de bouger. C'est une bonne nouvelle pour le
     * mod et une mauvaise pour l'épreuve, qui ne prouvait rien.
     *
     * <p>À trois mille, le tick lissé monte à soixante-douze millisecondes et la marée travaille.
     */
    private static final int CROWD = 3000;

    /** Ticks laissés à la marée pour réagir à la charge. */
    private static final int UNDER_LOAD = 900;

    /** Ticks laissés à la marée pour remonter une fois la charge retirée. */
    private static final int AFTER_LOAD = 900;

    private enum Step { OFF, LOADING, RELIEVED, DONE }

    private static Step step = Step.OFF;
    private static int waiting;
    private static MinecraftServer host;

    private static int ceilingView;
    private static int ceilingSimulation;
    private static int lowestView;
    private static int lowestSimulation;
    private static int finalView;
    private static int finalSimulation;

    private Surge() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        ServerLevel level = server.overworld();

        // Contrairement à toutes les autres épreuves, celle-ci a besoin de la marée allumée : c'est
        // elle qu'on examine.
        Settings.setEnabled(true);
        Settings.setTide(true);
        Tide.anchor(server);
        // L'epreuve pose sa charge tout de suite : elle n'a pas a subir la grace du demarrage.
        Tide.skipGrace();

        ceilingView = server.getPlayerList().getViewDistance();
        ceilingSimulation = server.getPlayerList().getSimulationDistance();
        lowestView = ceilingView;
        lowestSimulation = ceilingSimulation;

        int born = Scene.build(level, Scene.Kind.VILLAGERS, CROWD, 64);
        step = Step.LOADING;
        waiting = UNDER_LOAD;
        Lanterne.LOG.info("[MARÉE] Épreuve lancée — {} villageois posés. Plafonds : vue {}, "
                + "simulation {}. On regarde si la marée descend.", born, ceilingView,
                ceilingSimulation);
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }

        int view = server.getPlayerList().getViewDistance();
        int simulation = server.getPlayerList().getSimulationDistance();
        lowestView = Math.min(lowestView, view);
        lowestSimulation = Math.min(lowestSimulation, simulation);

        if (--waiting > 0) {
            return;
        }

        switch (step) {
            case LOADING -> {
                Lanterne.LOG.info("[MARÉE] Sous charge — descendue à vue {}, simulation {} "
                        + "(tick lissé {} ms). On retire la charge.", lowestView, lowestSimulation,
                        String.format(java.util.Locale.ROOT, "%.1f",
                                server.getCurrentSmoothedTickTime()));
                relieve(server.overworld());
                step = Step.RELIEVED;
                waiting = AFTER_LOAD;
            }
            case RELIEVED -> {
                finalView = server.getPlayerList().getViewDistance();
                finalSimulation = server.getPlayerList().getSimulationDistance();
                step = Step.DONE;
                report(server);
                if (host != null) {
                    host.halt(false);
                }
            }
            default -> { }
        }
    }

    /** Retire la foule, pour que le serveur respire à nouveau. */
    private static void relieve(ServerLevel level) {
        List<Entity> crowd = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Villager) {
                crowd.add(entity);
            }
        }
        for (Entity villager : crowd) {
            villager.discard();
        }
        Lanterne.LOG.info("[MARÉE] {} villageois retiré(s).", crowd.size());
    }

    private static void report(MinecraftServer server) {
        Lanterne.LOG.info("[MARÉE] ── Verdict ──");
        Lanterne.LOG.info("[MARÉE] Vue        · plafond {} · plus bas {} · à la fin {}",
                ceilingView, lowestView, finalView);
        Lanterne.LOG.info("[MARÉE] Simulation · plafond {} · plus bas {} · à la fin {}",
                ceilingSimulation, lowestSimulation, finalSimulation);
        Lanterne.LOG.info("[MARÉE] {}", Tide.describe(server));

        boolean wentDown = lowestView < ceilingView || lowestSimulation < ceilingSimulation;
        boolean cameBack = finalView > lowestView || finalSimulation > lowestSimulation;
        boolean neverExceeded = finalView <= ceilingView && finalSimulation <= ceilingSimulation;

        if (!neverExceeded) {
            Lanterne.LOG.error("[MARÉE] NON CONFORME : la marée a dépassé les plafonds demandés dans "
                    + "server.properties. C'est la seule chose qu'elle ne doit jamais faire.");
            return;
        }
        if (!wentDown) {
            Lanterne.LOG.warn("[MARÉE] NON PROUVÉE : la marée n'est jamais descendue. Soit la charge "
                    + "n'a pas suffi à dépasser la cible sur cette machine, soit elle ne descend "
                    + "pas. Ce banc ne peut pas les distinguer, et ne conclura donc pas.");
            return;
        }
        if (!cameBack) {
            Lanterne.LOG.error("[MARÉE] NON CONFORME : elle est descendue et n'est pas remontée. Un "
                    + "réglage qui ne sait que se dégrader rogne l'horizon des joueurs pour toujours, "
                    + "et le module doit être retiré tant que ce n'est pas expliqué.");
            return;
        }
        Lanterne.LOG.info("[MARÉE] CONFORME : descendue sous la charge, remontée une fois soulagée, "
                + "et jamais au-dessus de ce que server.properties demande.");
    }
}
