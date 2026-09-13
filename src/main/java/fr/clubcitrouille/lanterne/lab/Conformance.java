package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.levelgen.Heightmap;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'épreuve de conformité : ce que le mod ne doit pas avoir changé.
 *
 * <h2>La question qu'aucun banc ne pose</h2>
 *
 * <p>Tous les chiffres de ce projet répondent à « est-ce plus rapide ? ». Aucun ne répond à la seule
 * question qui puisse le disqualifier : <b>est-ce toujours le même jeu ?</b>
 *
 * <p>Un mod d'optimisation qui casse une ferme a échoué, même à seize fois plus rapide. Le joueur a
 * perdu davantage qu'il n'a gagné, et il ne s'en apercevra que la semaine suivante, devant des
 * coffres vides, sans savoir pourquoi.
 *
 * <h2>Le soupçon</h2>
 *
 * <p>La cadence ralentit le tick d'une créature, et la chute <em>est</em> un tick : la gravité
 * s'applique dans {@code Entity.tick()}. Une créature réveillée une fois sur huit tombe donc huit
 * fois plus lentement.
 *
 * <p>Les fermes à monstres reposent toutes sur ce principe : on fait tomber les créatures d'assez
 * haut pour qu'elles meurent, et l'on ramasse. Si la chute dure huit fois plus longtemps, la ferme
 * produit huit fois moins — et le mod aura transformé un gain de calcul en perte de production.
 *
 * <h2>Mesurer une distance, et non un évènement</h2>
 *
 * <p>La première version chronométrait l'atterrissage : « combien de ticks jusqu'à toucher le sol ».
 * Elle a rendu des résultats contradictoires — une créature à cent vingt blocs paraissait tomber
 * <em>plus vite</em> avec le mod qu'avec rien.
 *
 * <p>La faute était au critère, pas au mod. Une vache lâchée de quarante blocs meurt en touchant le
 * sol ; entre {@code onGround()} et {@code isAlive()}, l'instant exact de l'atterrissage est
 * ambigu, et l'ambiguïté produisait des relevés au hasard.
 *
 * <p>On mesure donc <b>la distance descendue en un nombre fixe de ticks</b>. C'est déterministe,
 * sans évènement à guetter, et directement interprétable : si le mod ne change rien, la distance est
 * la même ; s'il divise la cadence par huit, la distance l'est aussi.
 */
public final class Conformance {
    /** Hauteur du lâcher, en blocs au-dessus du sol. Assez pour ne jamais toucher pendant l'épreuve. */
    private static final int DROP_HEIGHT = 45;
    /** Durée de l'observation, en ticks. Une seconde et demie de chute libre. */
    private static final int WINDOW = 25;
    /** Distances auxquelles on éprouve la chute. */
    // On évite zéro : une créature lâchée sur la tête du joueur retombe sur lui et ne mesure
    // plus une chute mais une collision.
    private static final int[] RANGES = {16, 40, 64};
    /**
     * Sujets lâchés par distance.
     *
     * <p>Un seul suffisait en théorie — la chute est déterministe. En pratique le relevé oscillait
     * de vingt-cinq à cent pour cent d'une exécution à l'autre, parce que le moment du premier
     * réveil dépend du décalage propre à chaque créature. Cinq sujets et une médiane rendent le
     * verdict stable, et un verdict instable ne sert à rien.
     */
    private static final int PER_RANGE = 5;

    private record Subject(Entity entity, int range, double startY) {}

    private static final List<Subject> SUBJECTS = new ArrayList<>();
    private static final double[] WITH = new double[RANGES.length];
    private static final double[] WITHOUT = new double[RANGES.length];

    private enum Step { OFF, FALLING_ON, FALLING_OFF, DONE }

    private static Step step = Step.OFF;
    private static int waiting;
    private static MinecraftServer host;

    private Conformance() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    /** Ouvre l'épreuve. Le mod est actif pour la première moitié, éteint pour la seconde. */
    public static void begin(MinecraftServer server) {
        var verdict = Preflight.check(server, server.overworld(), 0);
        if (!Preflight.announce("épreuve de chute", verdict)) {
            server.halt(false);
            return;
        }
        host = server;
        step = Step.FALLING_ON;
        Settings.setEnabled(true);
        drop(server.overworld());
        Lanterne.LOG.info("[CONFORMITÉ] Chute observée sur {} ticks, mod actif.", WINDOW);
    }

    private static void drop(ServerLevel level) {
        clear();
        for (int range : RANGES) {
          for (int copy = 0; copy < PER_RANGE; copy++) {
            // Les sujets d'une même distance sont écartés de quatre blocs : assez pour qu'aucun ne
            // tombe sur un autre, assez peu pour qu'ils partagent la même distance au joueur.
            double x = range;
            double z = copy * 4d;
            double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int) x, (int) z);
            Cow cow = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
            if (cow == null) {
                continue;
            }
            double y = ground + DROP_HEIGHT;
            cow.snapTo(x, y, z, 0f, 0f);
            cow.setPersistenceRequired();
            // Invulnérable : une créature qui meurt en route ne mesure plus rien, et les dégâts de
            // chute ne sont pas le sujet.
            cow.setInvulnerable(true);
            if (level.addFreshEntity(cow)) {
                SUBJECTS.add(new Subject(cow, range, y));
            }
          }
        }
        waiting = WINDOW;
    }

    /** Fait avancer l'épreuve. Appelé à chaque tick du serveur. */
    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        if (--waiting > 0) {
            return;
        }
        final MinecraftServer host = server;

        double[] into = step == Step.FALLING_ON ? WITH : WITHOUT;

        // On regroupe par distance, puis l'on prend la médiane. Un sujet unique suffisait en
        // théorie — la chute est déterministe — mais le relevé oscillait de vingt-cinq à cent pour
        // cent d'une exécution à l'autre : le moment du premier réveil dépend du décalage propre à
        // chaque créature. Cinq sujets et une médiane rendent le verdict stable.
        java.util.Map<Integer, java.util.List<Double>> byRange = new java.util.HashMap<>();
        for (Subject subject : SUBJECTS) {
            byRange.computeIfAbsent(subject.range(), ignored -> new java.util.ArrayList<>())
                    .add(subject.startY() - subject.entity().getY());
        }
        for (var entry : byRange.entrySet()) {
            int index = indexOf(entry.getKey());
            if (index < 0) {
                continue;
            }
            java.util.List<Double> falls = entry.getValue();
            java.util.Collections.sort(falls);
            into[index] = falls.get(falls.size() / 2);
        }

        // Un relevé de contexte par distance, et non par sujet : ce qu'on veut savoir est ce que le
        // mod croit de cette zone, pas de chaque vache.
        for (int range : RANGES) {
            SUBJECTS.stream().filter(s -> s.range() == range).findFirst().ifPresent(witness ->
                    Lanterne.LOG.info(String.format(Locale.ROOT,
                            "[CONFORMITÉ] · %d blocs : distance vue=%.0f, cadence=%d, "
                                    + "voisines=%d, chunks recensés=%d",
                            witness.range(),
                            fr.clubcitrouille.lanterne.core.Census.distanceOf(witness.entity()),
                            fr.clubcitrouille.lanterne.core.Cadence.forEntity(witness.entity(),
                                    fr.clubcitrouille.lanterne.core.Census
                                            .distanceOf(witness.entity()),
                                    fr.clubcitrouille.lanterne.core.TickBudget.pressure()),
                            fr.clubcitrouille.lanterne.core.Crowd.neighbours(witness.entity()),
                            fr.clubcitrouille.lanterne.core.Census.chunksSeen())));
        }

        clear();
        advance(server.overworld());
    }

    private static void clear() {
        for (Subject subject : SUBJECTS) {
            subject.entity().discard();
        }
        SUBJECTS.clear();
    }

    private static void advance(ServerLevel level) {
        if (step == Step.FALLING_ON) {
            step = Step.FALLING_OFF;
            Settings.setEnabled(false);
            drop(level);
            Lanterne.LOG.info("[CONFORMITÉ] Seconde moitié, mod éteint.");
            return;
        }

        step = Step.DONE;
        Settings.setEnabled(true);
        report();
        if (host != null) {
            host.halt(false);
        }
    }

    /**
     * Le verdict.
     *
     * <p>Écrit sans ménagement : si le mod divise par huit la vitesse de chute à cent blocs, la ligne
     * le dira. Un rapport qui n'annonce jamais de mauvaise nouvelle n'est pas un rapport.
     */
    private static void report() {
        Lanterne.LOG.info("[CONFORMITÉ] ── Chute en {} ticks, en blocs ──", WINDOW);
        for (int i = 0; i < RANGES.length; i++) {
            double with = WITH[i];
            double without = WITHOUT[i];
            double ratio = without <= 0.01d ? 0d : with / without;

            String verdict;
            if (ratio >= 0.9d) {
                verdict = "conforme";
            } else if (ratio >= 0.5d) {
                verdict = "ralenti";
            } else {
                verdict = "CASSÉ — une ferme à chute produirait d'autant moins";
            }

            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[CONFORMITÉ] %4d blocs : sans %.1f · avec %.1f · %.0f %% de la chute — %s",
                    RANGES[i], without, with, ratio * 100d, verdict));
        }
    }

    private static int indexOf(int range) {
        for (int i = 0; i < RANGES.length; i++) {
            if (RANGES[i] == range) {
                return i;
            }
        }
        return -1;
    }
}
