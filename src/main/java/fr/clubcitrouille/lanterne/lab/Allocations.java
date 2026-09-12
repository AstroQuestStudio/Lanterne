package fr.clubcitrouille.lanterne.lab;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Qui alloue, et combien — parce que « deux gigaoctets » ne dit pas où chercher.
 *
 * <h2>Le chiffre qui manquait</h2>
 *
 * <p>Le banc mesure depuis longtemps la mémoire allouée par phase, et l'écart est spectaculaire :
 * dix-sept gigaoctets en vanilla, deux virgule sept avec ce mod, un seul lorsqu'on y ajoute un
 * ensemble de dix-sept mods d'optimisation. Ce dernier écart — <b>un gigaoctet et demi que d'autres
 * économisent et pas nous</b> — est resté sans explication faute de savoir <em>d'où</em> viennent
 * les allocations restantes.
 *
 * <p>Un total ne se corrige pas. Une liste de coupables, si.
 *
 * <h2>Pourquoi l'échantillonnage de pile ne suffisait pas</h2>
 *
 * <p>Le profileur de ce laboratoire relève où se trouve le processeur. C'est la bonne mesure pour le
 * temps, et la mauvaise pour la mémoire : allouer coûte presque rien sur le moment — un déplacement
 * de pointeur — et se paie plus tard, ailleurs, quand le ramasse-miettes passe. Le coupable
 * n'apparaît jamais dans le relevé de celui qui paie.
 *
 * <p>La machine virtuelle, elle, sait exactement qui alloue. {@code ObjectAllocationSample} est un
 * évènement du magnétoscope intégré — <i>Java Flight Recorder</i> — qui échantillonne les
 * allocations avec leur pile d'appel complète, pour un coût de l'ordre du pour cent. On lui demande
 * donc directement.
 *
 * <h2>Ce qu'on en fait</h2>
 *
 * <p>On agrège par méthode allocatrice et par classe allouée, et l'on classe par poids. Le rapport
 * dit alors, en clair : <i>telle méthode a alloué tant de mégaoctets pendant l'épreuve</i>. C'est
 * une liste de travail, et chaque ligne qui disparaît se mesure au banc suivant.
 */
public final class Allocations {
    /** Taille du tampon du magnétoscope. Large : on préfère perdre du temps que des évènements. */
    private static final String BUFFER = "32m";

    private static Recording recording;
    private static java.nio.file.Path dump;

    private Allocations() {}

    /** Ouvre l'enregistrement. Sans effet si le magnétoscope n'est pas disponible. */
    public static void start() {
        if (recording != null) {
            return;
        }
        try {
            recording = new Recording();
            // Le seuil d'échantillonnage décide du compromis précision/coût. Une milliseconde de
            // période est largement assez fine pour classer des postes qui pèsent des mégaoctets.
            recording.enable("jdk.ObjectAllocationSample")
                    .withPeriod(Duration.ofMillis(10));
            recording.setMaxSize(64L * 1024L * 1024L);
            recording.setToDisk(true);
            dump = java.nio.file.Files.createTempFile("lanterne-alloc", ".jfr");
            recording.setDestination(dump);
            recording.start();
            Lanterne.LOG.info("[ALLOC] Enregistrement des allocations ouvert.");
        } catch (Exception unavailable) {
            Lanterne.LOG.warn("[ALLOC] Magnétoscope indisponible : {}", unavailable.toString());
            recording = null;
        }
    }

    /** Ferme l'enregistrement et écrit le classement des allocateurs. */
    public static void stopAndReport(int limit) {
        if (recording == null) {
            return;
        }
        try {
            recording.stop();
            recording.close();

            Map<String, long[]> byMethod = new HashMap<>();
            long total = 0L;

            try (RecordingFile file = new RecordingFile(dump)) {
                while (file.hasMoreEvents()) {
                    RecordedEvent event = file.readEvent();
                    if (!event.getEventType().getName().equals("jdk.ObjectAllocationSample")) {
                        continue;
                    }
                    long weight = event.getLong("weight");
                    total += weight;
                    byMethod.computeIfAbsent(blame(event), ignored -> new long[1])[0] += weight;
                }
            }

            Lanterne.LOG.info("[ALLOC] ── Qui alloue ── ({} échantillonné au total)", bytes(total));
            List<Map.Entry<String, long[]>> rows = new ArrayList<>(byMethod.entrySet());
            rows.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0])
                    .reversed());

            for (int i = 0; i < Math.min(limit, rows.size()); i++) {
                Map.Entry<String, long[]> row = rows.get(i);
                double share = total == 0L ? 0d : row.getValue()[0] * 100d / total;
                if (share < 0.5d) {
                    break;
                }
                Lanterne.LOG.info(String.format(Locale.ROOT, "[ALLOC] %5.1f %%  %s  (%s)",
                        share, row.getKey(), bytes(row.getValue()[0])));
            }
        } catch (Exception failed) {
            Lanterne.LOG.warn("[ALLOC] Lecture impossible : {}", failed.toString());
        } finally {
            recording = null;
        }
    }

    /**
     * À qui imputer une allocation.
     *
     * <p>Le sommet de pile est souvent une classe du socle — un tableau, un constructeur de
     * collection — qui ne dit rien d'utile. On remonte donc jusqu'au premier cadre du jeu ou d'un
     * mod, qui est le véritable demandeur.
     */
    private static String blame(RecordedEvent event) {
        String type = event.getClass("objectClass") == null
                ? "?" : simple(event.getClass("objectClass").getName());

        if (event.getStackTrace() != null) {
            for (RecordedFrame frame : event.getStackTrace().getFrames()) {
                String owner = frame.getMethod().getType().getName();
                if (owner.startsWith("net.minecraft.") || owner.startsWith("fr.clubcitrouille.")
                        || owner.startsWith("net.neoforged.")) {
                    return simple(owner) + '.' + frame.getMethod().getName() + "  → " + type;
                }
            }
            if (!event.getStackTrace().getFrames().isEmpty()) {
                RecordedFrame top = event.getStackTrace().getFrames().get(0);
                return simple(top.getMethod().getType().getName()) + '.'
                        + top.getMethod().getName() + "  → " + type;
            }
        }
        return "?  → " + type;
    }

    private static String simple(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    private static String bytes(long value) {
        if (value > 1_073_741_824L) {
            return String.format(Locale.ROOT, "%.2f Go", value / 1_073_741_824d);
        }
        if (value > 1_048_576L) {
            return String.format(Locale.ROOT, "%.1f Mo", value / 1_048_576d);
        }
        return String.format(Locale.ROOT, "%.1f Ko", value / 1024d);
    }
}
