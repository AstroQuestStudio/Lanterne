package fr.clubcitrouille.lanterne.core.profil;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Lit un fichier {@code .jfr} déjà écrit par {@link MoteurJfr} et en tire des sections de rapport en
 * texte clair — la même idée que {@code lab.Radiographie.writeReport}, mais à partir d'évènements JFR
 * au lieu de relevés {@code ThreadMXBean} accumulés en direct.
 *
 * <h2>Tout est lu une seule fois, en mémoire</h2>
 *
 * <p>{@link RecordingFile#readAllEvents(Path)} charge l'intégralité du fichier avant tout traitement.
 * Pour une fenêtre de 60 minutes avec les débits fixés dans {@link MoteurJfr} (échantillons toutes les
 * 20 ms, 150 allocations/s au plus, contentions ≥ 20 ms seulement), ce fichier reste de l'ordre de
 * quelques mégaoctets — largement dans ce qu'une JVM de jeu peut garder un instant en tas pour produire
 * un rapport, une seule fois par heure.
 *
 * <h2>Lecture défensive des champs</h2>
 *
 * <p>Les noms de champs JFR ({@code "sampledThread"}, {@code "objectClass"}...) sont stables depuis
 * longtemps mais ne sont pas un contrat d'API formel entre versions de JDK. Chaque lecture de champ
 * passe donc par {@link #valeur} ou {@link #champLong}, qui rendent une valeur par défaut plutôt que de
 * faire échouer tout le rapport pour un seul champ absent sur une version de JDK inattendue.
 */
public final class AnalyseJfr {
    private AnalyseJfr() {}

    /** Une fenêtre à corréler avec les échantillons JFR pris pendant cet intervalle — voir {@link #pilesPendant}. */
    public record Fenetre(Instant debut, Instant fin, String etiquette) {}

    public static String rapportTexte(Path fichierJfr, String filPrincipal, List<Fenetre> pointsChauds)
            throws IOException {
        List<RecordedEvent> evenements = RecordingFile.readAllEvents(fichierJfr);

        StringBuilder sb = new StringBuilder();
        sb.append(chargeCpu(evenements));
        sb.append(pausesGc(evenements));
        sb.append(resumeTas(evenements));
        sb.append(methodesChaudesParFil(evenements));
        sb.append(arbreAppel(evenements, filPrincipal));
        sb.append(sitesAllocation(evenements));
        sb.append(contention(evenements));
        if (!pointsChauds.isEmpty()) {
            sb.append(correlationPiresImages(evenements, filPrincipal, pointsChauds));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Charge CPU
    // ------------------------------------------------------------------------------------------

    private static String chargeCpu(List<RecordedEvent> evenements) {
        List<Double> jvm = new ArrayList<>();
        List<Double> machine = new ArrayList<>();
        for (RecordedEvent event : evenements) {
            if (!event.getEventType().getName().equals("jdk.CPULoad")) {
                continue;
            }
            Float jvmUser = valeur(event, "jvmUser", Float.class);
            Float jvmSystem = valeur(event, "jvmSystem", Float.class);
            Float machineTotal = valeur(event, "machineTotal", Float.class);
            if (jvmUser != null && jvmSystem != null) {
                jvm.add((double) (jvmUser + jvmSystem));
            }
            if (machineTotal != null) {
                machine.add((double) machineTotal);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("CHARGE CPU (pendant la fenêtre JFR)\n").append("-".repeat(60)).append('\n');
        if (jvm.isEmpty()) {
            sb.append("  (aucun relevé)\n\n");
            return sb.toString();
        }
        sb.append(String.format(Locale.ROOT, "  JVM (ce process)  : moyenne %.1f %%, max %.1f %%%n",
                100.0 * moyenne(jvm), 100.0 * max(jvm)));
        if (!machine.isEmpty()) {
            sb.append(String.format(Locale.ROOT, "  Machine entière   : moyenne %.1f %%, max %.1f %%%n",
                    100.0 * moyenne(machine), 100.0 * max(machine)));
        }
        sb.append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Pauses GC
    // ------------------------------------------------------------------------------------------

    private static String pausesGc(List<RecordedEvent> evenements) {
        List<Double> pausesMs = new ArrayList<>();
        for (RecordedEvent event : evenements) {
            if (!event.getEventType().getName().equals("jdk.GCPhasePause")) {
                continue;
            }
            pausesMs.add(event.getDuration().toNanos() / 1e6d);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("PAUSES GC\n").append("-".repeat(60)).append('\n');
        if (pausesMs.isEmpty()) {
            sb.append("  (aucune pause enregistrée sur cette fenêtre)\n\n");
            return sb.toString();
        }
        List<Double> triees = new ArrayList<>(pausesMs);
        triees.sort(Comparator.naturalOrder());
        double total = pausesMs.stream().mapToDouble(Double::doubleValue).sum();
        sb.append(String.format(Locale.ROOT, "  Nombre : %d, total : %.1f ms%n", pausesMs.size(), total));
        sb.append(String.format(Locale.ROOT, "  p50 : %.2f ms, p99 : %.2f ms, max : %.2f ms%n",
                percentile(triees, 0.50), percentile(triees, 0.99), triees.get(triees.size() - 1)));
        long au_dela_50ms = pausesMs.stream().filter(p -> p > 50.0).count();
        if (au_dela_50ms > 0) {
            sb.append(String.format(Locale.ROOT,
                    "  Pauses au-delà de 50 ms (un tick vanilla entier) : %d%n", au_dela_50ms));
        }
        sb.append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Résumé du tas
    // ------------------------------------------------------------------------------------------

    private static String resumeTas(List<RecordedEvent> evenements) {
        long min = Long.MAX_VALUE;
        long max = 0L;
        long dernier = -1L;
        for (RecordedEvent event : evenements) {
            if (!event.getEventType().getName().equals("jdk.GCHeapSummary")) {
                continue;
            }
            Long used = champLong(event, "heapUsed");
            if (used == null) {
                continue;
            }
            min = Math.min(min, used);
            max = Math.max(max, used);
            dernier = used;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("TAS (heap)\n").append("-".repeat(60)).append('\n');
        if (dernier < 0) {
            sb.append("  (aucun résumé de tas capturé)\n\n");
            return sb.toString();
        }
        sb.append(String.format(Locale.ROOT, "  Utilisé — min : %.0f Mo, max : %.0f Mo, dernier relevé : %.0f Mo%n",
                min / 1024.0 / 1024.0, max / 1024.0 / 1024.0, dernier / 1024.0 / 1024.0));
        sb.append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Méthodes chaudes par fil
    // ------------------------------------------------------------------------------------------

    private static String methodesChaudesParFil(List<RecordedEvent> evenements) {
        // fil -> (méthode -> compte) — "propre" (sommet de pile) et "total" (apparaît n'importe où
        // dans la pile de l'échantillon, une seule fois par pile même si la méthode y est récursive).
        Map<String, Map<String, Integer>> selfParFil = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> totalParFil = new LinkedHashMap<>();
        Map<String, Integer> totalEchantillonsParFil = new LinkedHashMap<>();

        for (RecordedEvent event : evenements) {
            String type = event.getEventType().getName();
            if (!type.equals("jdk.ExecutionSample") && !type.equals("jdk.NativeMethodSample")) {
                continue;
            }
            RecordedStackTrace pile = event.getStackTrace();
            if (pile == null || pile.getFrames().isEmpty()) {
                continue;
            }
            String fil = nomFil(event);
            List<RecordedFrame> frames = pile.getFrames();
            String sommet = nomMethode(frames.get(0));
            // Un fil qui DORT dans du natif (poll, epoll, accept, park...) n'est pas du travail : le
            // compter faisait apparaître « FileSystemWatchService 100 % poll » en tête du rapport.
            // Les appels natifs utiles (OpenGL, compression, E/S réelles) restent comptés.
            if (type.equals("jdk.NativeMethodSample") && estAttente(sommet)) {
                continue;
            }
            selfParFil.computeIfAbsent(fil, k -> new HashMap<>()).merge(sommet, 1, Integer::sum);
            totalEchantillonsParFil.merge(fil, 1, Integer::sum);

            Set<String> dejaVues = new HashSet<>();
            Map<String, Integer> totalMethodes = totalParFil.computeIfAbsent(fil, k -> new HashMap<>());
            for (RecordedFrame frame : frames) {
                String nom = nomMethode(frame);
                if (dejaVues.add(nom)) {
                    totalMethodes.merge(nom, 1, Integer::sum);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("MÉTHODES LES PLUS COÛTEUSES, PAR FIL (temps propre puis temps total)\n")
                .append("-".repeat(60)).append('\n');
        if (selfParFil.isEmpty()) {
            sb.append("  (aucun échantillon d'exécution — fenêtre trop courte, ou échantillonnage indisponible)\n\n");
            return sb.toString();
        }
        // Les fils les plus échantillonnés d'abord — c'est là qu'est le coût réel.
        List<String> fils = new ArrayList<>(totalEchantillonsParFil.keySet());
        fils.sort(Comparator.<String>comparingInt(totalEchantillonsParFil::get).reversed());
        for (String fil : fils) {
            int total = totalEchantillonsParFil.get(fil);
            sb.append(String.format(Locale.ROOT, "  Fil « %s » — %d échantillon(s)%n", fil, total));
            List<Map.Entry<String, Integer>> self = new ArrayList<>(selfParFil.get(fil).entrySet());
            self.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());
            List<Map.Entry<String, Integer>> tot = new ArrayList<>(totalParFil.get(fil).entrySet());
            tot.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());
            sb.append("    propre :\n");
            afficherTop(sb, self, total, 12);
            sb.append("    total (avec ce qu'elle appelle) :\n");
            afficherTop(sb, tot, total, 12);
        }
        sb.append('\n');
        return sb.toString();
    }

    private static void afficherTop(StringBuilder sb, List<Map.Entry<String, Integer>> tri, int total, int max) {
        int montres = 0;
        for (Map.Entry<String, Integer> entry : tri) {
            if (montres >= max) {
                break;
            }
            double pct = total > 0 ? 100.0 * entry.getValue() / total : 0;
            if (pct < 0.5d) {
                break;
            }
            sb.append(String.format(Locale.ROOT, "      %5.1f %%  %s%n", pct, entry.getKey()));
            montres++;
        }
        if (montres == 0) {
            sb.append("      (rien au-dessus de 0,5 %)\n");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Arbre d'appel du fil principal
    // ------------------------------------------------------------------------------------------

    private static final class Noeud {
        final String nom;
        final Map<String, Noeud> enfants = new HashMap<>();
        int total;

        Noeud(String nom) {
            this.nom = nom;
        }

        Noeud enfant(String cle) {
            return enfants.computeIfAbsent(cle, Noeud::new);
        }
    }

    private static String arbreAppel(List<RecordedEvent> evenements, String filPrincipal) {
        Noeud racine = new Noeud("(racine)");
        int totalPiles = 0;
        for (RecordedEvent event : evenements) {
            if (!event.getEventType().getName().equals("jdk.ExecutionSample")) {
                continue;
            }
            if (filPrincipal != null && !filPrincipal.equals(nomFil(event))) {
                continue;
            }
            RecordedStackTrace pile = event.getStackTrace();
            if (pile == null) {
                continue;
            }
            List<RecordedFrame> frames = pile.getFrames();
            if (frames.isEmpty()) {
                continue;
            }
            totalPiles++;
            Noeud noeud = racine;
            noeud.total++;
            for (int i = frames.size() - 1; i >= 0; i--) {
                noeud = noeud.enfant(nomMethode(frames.get(i)));
                noeud.total++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "ARBRE D'APPEL — fil « %s » (élagué sous 1 %%)%n", filPrincipal))
                .append("-".repeat(60)).append('\n');
        if (totalPiles == 0) {
            sb.append("  (aucune pile pour ce fil sur cette fenêtre)\n\n");
            return sb.toString();
        }
        imprimerArbre(sb, racine, 0, totalPiles);
        sb.append('\n');
        return sb.toString();
    }

    private static void imprimerArbre(StringBuilder sb, Noeud noeud, int profondeur, int total) {
        if (profondeur > 12) {
            return;
        }
        List<Noeud> enfants = new ArrayList<>(noeud.enfants.values());
        enfants.sort(Comparator.<Noeud>comparingInt(n -> n.total).reversed());
        for (Noeud enfant : enfants) {
            double pct = 100.0 * enfant.total / total;
            if (pct < 1.0d) {
                continue;
            }
            sb.append("  ".repeat(profondeur))
                    .append(String.format(Locale.ROOT, "%5.1f %%  %s%n", pct, enfant.nom));
            imprimerArbre(sb, enfant, profondeur + 1, total);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Sites d'allocation
    // ------------------------------------------------------------------------------------------

    private static String sitesAllocation(List<RecordedEvent> evenements) {
        Map<String, Long> poidsParSite = new HashMap<>();
        Map<String, Long> comptesParSite = new HashMap<>();
        for (RecordedEvent event : evenements) {
            if (!event.getEventType().getName().equals("jdk.ObjectAllocationSample")) {
                continue;
            }
            String classe = classeAllouee(event);
            String site = classe;
            RecordedStackTrace pile = event.getStackTrace();
            if (pile != null && !pile.getFrames().isEmpty()) {
                site = classe + "  (à " + nomMethode(pile.getFrames().get(0)) + ")";
            }
            Long poids = champLong(event, "weight");
            poidsParSite.merge(site, poids != null ? poids : 1L, Long::sum);
            comptesParSite.merge(site, 1L, Long::sum);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("TOP SITES D'ALLOCATION (pondéré par le débit d'échantillonnage JFR)\n")
                .append("-".repeat(60)).append('\n');
        if (poidsParSite.isEmpty()) {
            sb.append("  (aucun échantillon d'allocation)\n\n");
            return sb.toString();
        }
        List<Map.Entry<String, Long>> tri = new ArrayList<>(poidsParSite.entrySet());
        tri.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed());
        int montres = 0;
        for (Map.Entry<String, Long> entry : tri) {
            if (montres++ >= 15) {
                break;
            }
            sb.append(String.format(Locale.ROOT, "  %,10d octets (%d échantillon(s))  %s%n",
                    entry.getValue(), comptesParSite.get(entry.getKey()), entry.getKey()));
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String classeAllouee(RecordedEvent event) {
        try {
            var classe = event.getClass("objectClass");
            return classe != null ? classe.getName() : "(classe inconnue)";
        } catch (Throwable absent) {
            return "(classe inconnue)";
        }
    }

    // ------------------------------------------------------------------------------------------
    // Contention
    // ------------------------------------------------------------------------------------------

    private static String contention(List<RecordedEvent> evenements) {
        Map<String, Long> dureeTotale = new HashMap<>();
        Map<String, Long> comptes = new HashMap<>();
        for (RecordedEvent event : evenements) {
            if (!event.getEventType().getName().equals("jdk.JavaMonitorEnter")) {
                continue;
            }
            String classe;
            try {
                var c = event.getClass("monitorClass");
                classe = c != null ? c.getName() : "(moniteur inconnu)";
            } catch (Throwable absent) {
                classe = "(moniteur inconnu)";
            }
            long ms = event.getDuration().toNanos() / 1_000_000L;
            dureeTotale.merge(classe, ms, Long::sum);
            comptes.merge(classe, 1L, Long::sum);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("CONTENTION (attentes de moniteur ≥ 20 ms)\n").append("-".repeat(60)).append('\n');
        if (dureeTotale.isEmpty()) {
            sb.append("  (aucune contention notable)\n\n");
            return sb.toString();
        }
        List<Map.Entry<String, Long>> tri = new ArrayList<>(dureeTotale.entrySet());
        tri.sort(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed());
        for (Map.Entry<String, Long> entry : tri) {
            sb.append(String.format(Locale.ROOT, "  %6d ms cumulés (%d attente(s))  %s%n",
                    entry.getValue(), comptes.get(entry.getKey()), entry.getKey()));
        }
        sb.append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Corrélation avec les pires images (client uniquement)
    // ------------------------------------------------------------------------------------------

    private static String correlationPiresImages(List<RecordedEvent> evenements, String filPrincipal,
            List<Fenetre> pointsChauds) {
        StringBuilder sb = new StringBuilder();
        sb.append("PIRES IMAGES — ce que faisait le fil de rendu à ce moment\n")
                .append("-".repeat(60)).append('\n');
        List<RecordedEvent> echantillons = evenements.stream()
                .filter(e -> e.getEventType().getName().equals("jdk.ExecutionSample"))
                .filter(e -> filPrincipal == null || filPrincipal.equals(nomFil(e)))
                .toList();
        for (Fenetre fenetre : pointsChauds) {
            sb.append(String.format(Locale.ROOT, "  %s :%n", fenetre.etiquette()));
            List<RecordedEvent> dedans = echantillons.stream()
                    .filter(e -> !e.getStartTime().isBefore(fenetre.debut())
                            && !e.getStartTime().isAfter(fenetre.fin()))
                    .toList();
            if (dedans.isEmpty()) {
                sb.append("    (aucun échantillon JFR tombé dans cette fenêtre)\n");
                continue;
            }
            Map<String, Integer> comptes = new LinkedHashMap<>();
            for (RecordedEvent event : dedans) {
                RecordedStackTrace pile = event.getStackTrace();
                if (pile == null || pile.getFrames().isEmpty()) {
                    continue;
                }
                comptes.merge(nomMethode(pile.getFrames().get(0)), 1, Integer::sum);
            }
            comptes.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(5)
                    .forEach(e -> sb.append(String.format(Locale.ROOT, "    %2d×  %s%n", e.getValue(), e.getKey())));
        }
        sb.append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Utilitaires
    // ------------------------------------------------------------------------------------------

    private static String nomFil(RecordedEvent event) {
        try {
            RecordedThread thread = event.getThread("sampledThread");
            if (thread == null) {
                thread = event.getThread();
            }
            return thread != null ? thread.getJavaName() : "(fil inconnu)";
        } catch (Throwable absent) {
            try {
                RecordedThread thread = event.getThread();
                return thread != null ? thread.getJavaName() : "(fil inconnu)";
            } catch (Throwable ignore) {
                return "(fil inconnu)";
            }
        }
    }

    /** Méthode native où un fil ATTEND (sommet d'un jdk.NativeMethodSample) plutôt qu'il ne travaille. */
    private static boolean estAttente(String methode) {
        String m = methode.substring(methode.lastIndexOf('.') + 1);
        return m.equals("poll") || m.equals("poll0") || m.startsWith("epollWait") || m.equals("wait")
                || m.equals("wait0") || m.equals("park") || m.equals("sleep") || m.equals("sleep0")
                || m.equals("accept") || m.equals("accept0") || m.equals("select") || m.equals("select0")
                || m.equals("socketAccept") || m.equals("take") || m.equals("GetQueuedCompletionStatus")
                || m.equals("waitForMultipleObjects") || m.equals("waitForReferencePendingList");
    }

    private static String nomMethode(RecordedFrame frame) {
        try {
            var methode = frame.getMethod();
            var type = methode.getType();
            return type.getName() + "." + methode.getName();
        } catch (Throwable absent) {
            return "(frame illisible)";
        }
    }

    private static <T> T valeur(RecordedEvent event, String champ, Class<T> type) {
        try {
            Object v = event.getValue(champ);
            return type.isInstance(v) ? type.cast(v) : null;
        } catch (Throwable absent) {
            return null;
        }
    }

    private static Long champLong(RecordedEvent event, String champ) {
        try {
            return event.getLong(champ);
        } catch (Throwable absent) {
            return null;
        }
    }

    private static double moyenne(List<Double> valeurs) {
        return valeurs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double max(List<Double> valeurs) {
        return valeurs.stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }

    private static double percentile(List<Double> triees, double p) {
        if (triees.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p * triees.size()) - 1;
        index = Math.max(0, Math.min(triees.size() - 1, index));
        return triees.get(index);
    }
}
