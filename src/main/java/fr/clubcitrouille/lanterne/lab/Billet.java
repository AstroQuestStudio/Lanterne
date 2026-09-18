package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Loterie;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le billet : ce que {@link Loterie} vaut réellement, sur une charge que {@link Cheptel} ne peut pas
 * juger.
 *
 * <h2>Pourquoi {@code Cheptel} ne pouvait pas servir ici</h2>
 *
 * <p>{@code Cheptel} refuse de conclure quand une charge « ne fait naître que 0 entité(s) » — son
 * garde-fou contre un monde resté vide. Or {@link Scene.Kind#FARM}, la charge conçue pour exactement
 * ce module (un champ de blé, vitesse de tick aléatoire portée à 256 pour que le tirage batte assez
 * souvent en vingt-cinq secondes), ne crée <b>aucune entité</b> par construction — voir le
 * commentaire de {@code Scene.field}. Le même garde-fou qui protège les charges d'élevage
 * disqualifierait à tort celle-ci.
 *
 * <p>Ce banc reprend donc le protocole de {@code Cheptel} — deux phases chronométrées, médiane
 * retenue, profil ventilé par {@link Sampler} — mais juge la charge par ce qu'elle a réellement
 * planté et par ce que {@link Loterie} rapporte avoir vu, pas par un compte d'entités qui n'existe
 * pas ici.
 *
 * <h2>Ce que le contrôle de section vérifie, en plus du temps</h2>
 *
 * <p>Un chiffre de gain sans contrôle ne vaut rien si le module a pu, en douce, cesser de ticker ce
 * qu'il aurait dû. {@link Loterie#incoherences()} doit rester à zéro — sinon le module s'est
 * lui-même désarmé en cours de route, et le banc le dit au lieu de publier un gain qui ne
 * correspond à rien. {@link Loterie#sectionsRares()} doit être non nul — sinon le seuil n'a jamais
 * été franchi et les deux phases ont exécuté le même code.
 */
public final class Billet {
    /** Taille du champ. Grand : c'est à cette échelle qu'une section rare devient courante. */
    private static final int COUNT = 10_000;
    private static final int RADIUS = 96;

    private static final int WARMUP = 80;
    private static final int SAMPLE = 220;
    private static final long BUDGET_NANOS = 60_000_000_000L;
    private static final int LEAST = 20;
    private static final double LEAST_BUSY = 0.10d;

    private enum Step { IDLE, SOW, SETTLING, WARM_OFF, MEASURE_OFF, WARM_ON, MEASURE_ON, DONE }

    private static Step step = Step.IDLE;
    private static int left;
    private static long phaseOpened;
    private static long tickStart;
    private static final long[] TIMES = new long[SAMPLE];
    private static int filled;

    private static double msOff;
    private static int keptOff;
    private static double busyOff;
    private static Map<String, Double> shareOff = Map.of();
    private static long raresOff;
    private static long incoherencesOff;

    private static double msOn;
    private static int keptOn;
    private static double busyOn;
    private static Map<String, Double> shareOn = Map.of();
    private static long raresOn;
    private static long coupsOn;
    private static long manquesOn;
    private static long incoherencesOn;

    private Billet() {}

    public static boolean running() {
        return step != Step.IDLE && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        step = Step.SOW;
        Settings.setTide(false);
        Lanterne.LOG.info("[BILLET] Épreuve armée — champ de {} parcelle(s), rayon {}.", COUNT, RADIUS);
    }

    public static void beginTick() {
        if (running()) {
            tickStart = System.nanoTime();
        }
    }

    private static boolean overdue() {
        return filled >= LEAST && System.nanoTime() - phaseOpened > BUDGET_NANOS;
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();
        long elapsed = System.nanoTime() - tickStart;

        switch (step) {
            case SOW -> {
                fr.clubcitrouille.lanterne.report.Herd.sweepEntities(level);
                Scene.build(level, Scene.Kind.FARM, COUNT, RADIUS);
                step = Step.SETTLING;
                left = WARMUP;
            }
            case SETTLING -> {
                if (--left <= 0) {
                    openWarm(Step.WARM_OFF, false);
                }
            }
            case WARM_OFF -> {
                if (--left <= 0) {
                    openMeasure();
                    step = Step.MEASURE_OFF;
                }
            }
            case MEASURE_OFF -> {
                if (filled < SAMPLE) {
                    TIMES[filled++] = elapsed;
                }
                if (filled >= SAMPLE || overdue()) {
                    Sampler.stop();
                    msOff = median(filled) / 1e6d;
                    keptOff = filled;
                    busyOff = busyShare();
                    shareOff = shares();
                    raresOff = Loterie.sectionsRares();
                    incoherencesOff = Loterie.incoherences();
                    openWarm(Step.WARM_ON, true);
                }
            }
            case WARM_ON -> {
                if (--left <= 0) {
                    openMeasure();
                    step = Step.MEASURE_ON;
                }
            }
            case MEASURE_ON -> {
                if (filled < SAMPLE) {
                    TIMES[filled++] = elapsed;
                }
                if (filled >= SAMPLE || overdue()) {
                    Sampler.stop();
                    msOn = median(filled) / 1e6d;
                    keptOn = filled;
                    busyOn = busyShare();
                    shareOn = shares();
                    raresOn = Loterie.sectionsRares();
                    coupsOn = Loterie.coups();
                    manquesOn = Loterie.manques();
                    incoherencesOn = Loterie.incoherences();
                    step = Step.DONE;
                    conclude(server);
                }
            }
            default -> { }
        }
    }

    private static void openWarm(Step next, boolean modOn) {
        Settings.setEnabled(modOn);
        step = next;
        left = WARMUP;
    }

    private static void openMeasure() {
        filled = 0;
        phaseOpened = System.nanoTime();
        Sampler.start(Thread.currentThread());
    }

    private static double busyShare() {
        int total = Sampler.totalSamples();
        return total == 0 ? 0d : Sampler.workingSamples() / (double) total;
    }

    private static Map<String, Double> shares() {
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        for (Sampler.Post post : Sampler.snapshot()) {
            out.put(post.label(), post.share());
        }
        return out;
    }

    private static double median(int count) {
        if (count <= 0) {
            return 0d;
        }
        long[] copy = java.util.Arrays.copyOf(TIMES, count);
        java.util.Arrays.sort(copy);
        int middle = count / 2;
        return count % 2 == 0 ? (copy[middle - 1] + copy[middle]) / 2d : copy[middle];
    }

    private static void conclude(MinecraftServer server) {
        Settings.setEnabled(true);
        say("──────────────────────────────────────────────────────────────");
        say(String.format(Locale.ROOT,
                "Champ de %d parcelles, rayon %d. Le mod est ÉTEINT dans la colonne « sans » et "
                + "ALLUMÉ dans la colonne « avec ».", COUNT, RADIUS));
        say("");

        if (!usable()) {
            server.halt(false);
            return;
        }

        say(String.format(Locale.ROOT,
                "tick sans Lanterne : %.2f ms (%d relevé(s), %.0f %% travaillé)", msOff, keptOff,
                busyOff * 100d));
        say(String.format(Locale.ROOT,
                "tick avec Lanterne : %.2f ms (%d relevé(s), %.0f %% travaillé)", msOn, keptOn,
                busyOn * 100d));
        double gain = msOn <= 0d ? 0d : msOff / msOn;
        say(String.format(Locale.ROOT, "gain : ×%.2f", gain));
        say("");
        say(String.format(Locale.ROOT,
                "[LOTERIE] sans : %d section(s) rare(s) vue(s) (module éteint : ne devrait rien "
                + "faire) · %d incohérence(s)", raresOff, incoherencesOff));
        say(String.format(Locale.ROOT,
                "[LOTERIE] avec : %d section(s) rare(s) · %d coup(s) · %d manque(s) · "
                + "%d incohérence(s) — %s",
                raresOn, coupsOn, manquesOn, incoherencesOn,
                Loterie.describe()));

        say("");
        say("── Où part le temps — SANS Lanterne ──");
        ventilation(shareOff, msOff);
        say("");
        say("── Où part le temps — AVEC Lanterne ──");
        ventilation(shareOn, msOn);

        say("");
        say("Rappel d'instrument : un sommet de pile désigne une RÉGION, pas une ligne — voir Cheptel.");
        say("──────────────────────────────────────────────────────────────");
        server.halt(false);
    }

    private static boolean usable() {
        if (busyOff < LEAST_BUSY) {
            say(String.format(Locale.ROOT,
                    "REFUS DE CONCLURE : le serveur a dormi %.0f %% du temps sans le mod. Rien à "
                    + "mesurer — monter COUNT.", (1d - busyOff) * 100d));
            return false;
        }
        if (incoherencesOn > 0) {
            say(String.format(Locale.ROOT,
                    "REFUS DE CONCLURE : %d incohérence(s) détectée(s) dans les compteurs de "
                    + "section pendant la phase active. Le module s'est désarmé lui-même en cours "
                    + "de route — voir Loterie.signaleIncoherence dans le journal ci-dessus. C'est "
                    + "un bogue, pas un gain à publier.", incoherencesOn));
            return false;
        }
        if (raresOn == 0) {
            say("REFUS DE CONCLURE : le seuil n'a jamais été franchi (0 section rare vue) — le "
                    + "module n'a rien pris en charge, et le gain ci-dessus serait celui du bruit, "
                    + "pas du module.");
            return false;
        }
        return true;
    }

    private static void ventilation(Map<String, Double> shares, double totalMs) {
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(shares.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        int shown = 0;
        for (Map.Entry<String, Double> entry : sorted) {
            if (entry.getValue() < 0.01d || shown >= 15) {
                break;
            }
            say(String.format(Locale.ROOT, "  %-44s %6.2f ms  (%5.1f %%)",
                    trim(entry.getKey()), entry.getValue() * totalMs, entry.getValue() * 100d));
            shown++;
        }
    }

    private static String trim(String label) {
        return label.length() <= 44 ? label : label.substring(0, 44);
    }

    private static void say(String text) {
        Lanterne.LOG.info("[BILLET] {}", text);
    }
}
