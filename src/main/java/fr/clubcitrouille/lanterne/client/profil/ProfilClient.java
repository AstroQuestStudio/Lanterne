package fr.clubcitrouille.lanterne.client.profil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jdk.jfr.Recording;

import net.minecraft.client.Minecraft;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.client.crash.BoiteNoireConsent;
import fr.clubcitrouille.lanterne.core.network.ProfilTranche;
import fr.clubcitrouille.lanterne.core.profil.AnalyseJfr;
import fr.clubcitrouille.lanterne.core.profil.MoteurJfr;
import fr.clubcitrouille.lanterne.report.Compagnons;

/**
 * Les profils de performance continus, côté client : contrairement à {@code lab.Radiographie}
 * (échantillonnage {@code ThreadMXBean}, safepoint à chaque relevé — voir sa Javadoc), ce fichier
 * s'appuie entièrement sur {@link MoteurJfr} pour l'échantillonnage — voir la Javadoc de cette classe
 * pour pourquoi c'est sûr là où {@code Radiographie} en continu ne l'est plus.
 *
 * <h2>Cycle de vie</h2>
 *
 * <p>Comme {@code Radiographie}, démarre tout seul à l'apparition d'un monde ({@link #pulse}, appelé
 * depuis {@code Pane.onFrame} juste après {@code Radiographie.pulse()}) et s'arrête à sa disparition.
 * Contrairement à elle, une session n'écrit PAS un seul rapport à la fin : elle se découpe en
 * <b>tranches de 60 minutes de temps en monde</b> ({@link #ROTATION_NANOS}), chacune donnant son propre
 * rapport — c'est la demande explicite du propriétaire, pour des profils réguliers sur toute une
 * session plutôt qu'un seul run géant à la fin. Une tranche plus courte que
 * {@link #DUREE_MINIMALE_NANOS} (sortie du monde prématurée) n'écrit rien : trop peu de données pour
 * qu'un rapport serve à quoi que ce soit.
 *
 * <h2>Le fil de rendu ne fait QUE figer un instantané</h2>
 *
 * <p>Arrêter un enregistrement JFR d'une heure, le vider sur disque, le relire et le mettre en forme
 * prend, mesuré, plusieurs centaines de millisecondes à quelques secondes — inacceptable sur le fil de
 * rendu, qui aurait figé le jeu une fois par heure et à chaque sortie de monde. {@link #pulse} ne fait
 * donc, sur ce fil, que trois choses bon marché : copier les compteurs primitifs de la tranche dans un
 * {@link TrancheSnapshot} immuable, détacher l'enregistrement JFR en cours (retiré du champ statique,
 * jamais touché par deux fils à la fois), et redémarrer aussitôt la tranche suivante. Tout le reste —
 * arrêt/vidage JFR, lecture par {@link AnalyseJfr}, mise en forme, écriture disque, mise en file réseau
 * — est délégué à {@link #ANALYSE_EXECUTOR}, un fil démon unique et de priorité basse : un rapport en
 * retard de quelques secondes ne coûte rien, une image qui saute en coûte beaucoup.
 *
 * <h2>Zéro allocation par image</h2>
 *
 * <p>{@link #enregistrerImage} incrémente des compteurs primitifs et une case d'histogramme à pas fixe
 * ({@link #histogrammeFrameMs}) — jamais de boîte {@code Double} ajoutée à une liste qui grandirait
 * toute l'heure (~216 000 images). Les centiles 1 % et 0,1 % se lisent depuis cet histogramme, déjà là.
 *
 * <h2>Copie locale toujours écrite, envoi réseau sous consentement</h2>
 *
 * <p>Le rapport texte est toujours écrit dans {@code config/lanterne/profils/} — utile même hors
 * ligne, ou sur un serveur non-Lanterne. Il n'est mis en file pour le RÉSEAU que si
 * {@link BoiteNoireConsent#profilsAllowed()} répond vrai (vérifié sur le fil de rendu, AVANT de passer
 * la main au fil d'analyse — {@code Minecraft.hasSingleplayerServer()} ne doit être lu que là) —
 * exactement comme {@code client.crash.BoiteNoire} pour les plantages, voir sa Javadoc de classe pour
 * la garde {@code Dist.CLIENT} et pourquoi ce fichier ne doit jamais être chargé par un serveur dédié.
 */
public final class ProfilClient {
    private ProfilClient() {}

    /** Une tranche par heure de jeu en monde — voir la Javadoc de classe. */
    private static final long ROTATION_NANOS = 60L * 60L * 1_000_000_000L;

    /** En dessous, une tranche interrompue (sortie du monde) n'écrit rien : pas assez de données. */
    private static final long DUREE_MINIMALE_NANOS = 5L * 60L * 1_000_000_000L;

    /** Une image plus longue que ça mérite d'être corrélée aux échantillons JFR du moment — voir AnalyseJfr. */
    private static final double SEUIL_PIRE_IMAGE_MS = 50.0d;

    private static final int PIRES_IMAGES_MAX = 40;

    /** Plafond du rapport écrit — voir la demande d'origine ("≤ ~200 Ko"). */
    private static final int TAILLE_RAPPORT_MAX = 200 * 1024;

    // --- Histogramme de temps d'image, pas fixe de 0,1 ms, jusqu'à 250 ms + un seau "au-delà". ---
    // Alloué UNE FOIS par tranche (demarrerTranche), jamais dans enregistrerImage : voir la Javadoc
    // de classe sur le zéro-allocation par image.
    private static final double HISTO_PAS_MS = 0.1d;
    private static final int HISTO_BUCKETS = 2500; // 250 ms / 0,1 ms
    private static long[] histogrammeFrameMs = new long[HISTO_BUCKETS + 1]; // dernier = "au-delà de 250 ms"

    /** Le fil unique, démon, priorité basse, qui fait tout le travail lourd d'une rotation. */
    private static final ExecutorService ANALYSE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread fil = new Thread(runnable, "Lanterne-Profil-Analyse");
        fil.setDaemon(true);
        fil.setPriority(Thread.MIN_PRIORITY);
        return fil;
    });

    private static boolean running;
    private static boolean wasInWorld;
    private static long renderThreadId = -1L;
    private static String renderThreadName;

    // --- La tranche JFR en cours ---
    private static Recording recordingEnCours;
    private static boolean jfrDisponibleCetteTranche;

    // --- Repère pour convertir System.nanoTime() en Instant JFR — voir enregistrerImage. ---
    private static Instant refInstant;
    private static long refNanos;

    // --- Statistiques d'images de la tranche en cours (compteurs primitifs uniquement). ---
    private static long trancheDebutNanos;
    private static long framesVues;
    private static double sommeFrameMs;
    private static double minFrameMs;
    private static double maxFrameMs;
    private static final int[] BORNES_FPS = {20, 40, 60, 90, 120};
    private static final long[] histogrammeFps = new long[BORNES_FPS.length + 1];
    private static long fenetreFrames;
    private static long fenetreDebutNanos;

    private record PireImage(Instant instant, double ms) {}

    private static final List<PireImage> piresImages = new ArrayList<>();

    // --- File d'attente réseau : remplie par le fil d'analyse, drainée par le fil client. ---
    private static final Deque<ProfilTranche> FILE_ATTENTE = new ConcurrentLinkedDeque<>();
    private static final int IMAGES_ENTRE_PAQUETS = 3;
    private static int cooldown;

    /**
     * Tout ce qu'un rapport de tranche a besoin de lire — figé sur le fil de rendu, lu ensuite
     * uniquement par {@link #ANALYSE_EXECUTOR}. Rien ici ne référence un champ statique mutable : voir
     * la Javadoc de classe.
     */
    private record TrancheSnapshot(
            String why,
            double sessionSec,
            long framesVues,
            double sommeFrameMs,
            double minFrameMs,
            double maxFrameMs,
            long[] histogrammeFrameMs,
            long[] histogrammeFps,
            List<PireImage> piresImages,
            String renderThreadName,
            Recording recording,
            boolean jfrDisponible,
            SystemeSnapshot systeme,
            Path profilsDir,
            boolean envoiAutorise) {}

    /** Tout ce que le rapport dit du système — lu UNIQUEMENT sur le fil de rendu (GL, options MC). */
    private record SystemeSnapshot(
            int coeurs, long memoireMaxMo, String javaVersion, String javaVendor, List<String> gc,
            String backendGraphique, int renderDistance, int simulationDistance, int framerateLimit,
            String gpu, List<String> modsRendu) {}

    /** Appelée depuis {@code Pane.onFrame}, juste après {@code Radiographie.pulse()}. */
    public static void pulse() {
        Minecraft mc = Minecraft.getInstance();
        boolean inWorld = mc.level != null;
        if (inWorld && !wasInWorld) {
            demarrerSession();
        } else if (!inWorld && wasInWorld && running) {
            terminerSession("sortie du monde");
        }
        wasInWorld = inWorld;

        drainerFileAttente();

        if (!running) {
            return;
        }
        if (renderThreadId < 0) {
            renderThreadId = Thread.currentThread().threadId();
            renderThreadName = Thread.currentThread().getName();
        }
        enregistrerImage(mc);

        if (System.nanoTime() - trancheDebutNanos >= ROTATION_NANOS) {
            rotation("rotation automatique (60 minutes de jeu)");
            demarrerTranche();
        }
    }

    private static void demarrerSession() {
        running = true;
        renderThreadId = -1L;
        demarrerTranche();
        Lanterne.LOG.info("[PROFIL] Session de profils de performance démarrée.");
    }

    private static void demarrerTranche() {
        trancheDebutNanos = System.nanoTime();
        refNanos = trancheDebutNanos;
        refInstant = Instant.now();
        framesVues = 0;
        sommeFrameMs = 0;
        minFrameMs = Double.MAX_VALUE;
        maxFrameMs = 0;
        histogrammeFrameMs = new long[HISTO_BUCKETS + 1]; // une seule allocation, ici, jamais par image
        Arrays.fill(histogrammeFps, 0L);
        fenetreFrames = 0;
        fenetreDebutNanos = trancheDebutNanos;
        piresImages.clear();

        jfrDisponibleCetteTranche = MoteurJfr.disponible();
        if (jfrDisponibleCetteTranche) {
            try {
                recordingEnCours = MoteurJfr.construire("Lanterne-ProfilClient");
                recordingEnCours.start();
            } catch (Throwable echec) {
                Lanterne.LOG.warn("[PROFIL] Démarrage JFR impossible, repli sur les statistiques d'images seules.", echec);
                jfrDisponibleCetteTranche = false;
                recordingEnCours = null;
            }
        } else {
            recordingEnCours = null;
        }
    }

    private static void terminerSession(String why) {
        running = false;
        double dureeSec = (System.nanoTime() - trancheDebutNanos) / 1e9d;
        if (dureeSec * 1e9d >= DUREE_MINIMALE_NANOS) {
            rotation(why);
        } else if (recordingEnCours != null) {
            // Tranche trop courte pour un rapport utile : on ferme l'enregistrement sans l'analyser,
            // mais toujours HORS du fil de rendu — close() sur un enregistrement "to disk" peut
            // encore flusher un tampon, même bref, on ne le suppose pas gratuit sur ce fil-ci.
            Recording aFermer = recordingEnCours;
            recordingEnCours = null;
            ANALYSE_EXECUTOR.submit(aFermer::close);
        }
    }

    /**
     * Fige l'instantané de la tranche en cours (fil de rendu, bon marché) puis délègue tout le travail
     * lourd à {@link #ANALYSE_EXECUTOR} — voir la Javadoc de classe. Ne bloque jamais l'appelant.
     */
    private static void rotation(String why) {
        Minecraft mc = Minecraft.getInstance();
        double sessionSec = Math.max(0.001d, (System.nanoTime() - trancheDebutNanos) / 1e9d);

        Recording recording = recordingEnCours;
        recordingEnCours = null; // détaché : le fil d'analyse en devient seul propriétaire

        TrancheSnapshot snap = new TrancheSnapshot(
                why,
                sessionSec,
                framesVues,
                sommeFrameMs,
                minFrameMs,
                maxFrameMs,
                histogrammeFrameMs, // le tableau lui-même : demarrerTranche() en alloue un NOUVEAU juste après
                Arrays.copyOf(histogrammeFps, histogrammeFps.length),
                List.copyOf(piresImages), // au plus PIRES_IMAGES_MAX (40) éléments : copie négligeable
                renderThreadName,
                recording,
                jfrDisponibleCetteTranche,
                capturerSysteme(mc),
                mc.gameDirectory.toPath().resolve("config").resolve(Lanterne.ID).resolve("profils"),
                BoiteNoireConsent.profilsAllowed() && !mc.hasSingleplayerServer());

        ANALYSE_EXECUTOR.submit(() -> traiterTranche(snap));
    }

    private static void enregistrerImage(Minecraft mc) {
        long now = System.nanoTime();
        double frameMs = mc.getFrameTimeNs() / 1e6d;
        framesVues++;
        sommeFrameMs += frameMs;
        minFrameMs = Math.min(minFrameMs, frameMs);
        maxFrameMs = Math.max(maxFrameMs, frameMs);

        // Histogramme à pas fixe : un incrément d'entier, zéro allocation — voir la Javadoc de classe.
        int seau = (int) (frameMs / HISTO_PAS_MS);
        histogrammeFrameMs[Math.max(0, Math.min(HISTO_BUCKETS, seau))]++;

        if (frameMs > SEUIL_PIRE_IMAGE_MS && piresImages.size() < PIRES_IMAGES_MAX) {
            Instant instant = refInstant.plusNanos(now - refNanos);
            piresImages.add(new PireImage(instant, frameMs));
        }

        fenetreFrames++;
        double dureeFenetreS = (now - fenetreDebutNanos) / 1e9d;
        if (dureeFenetreS >= 1.0d) {
            double fps = fenetreFrames / dureeFenetreS;
            int tranche = BORNES_FPS.length;
            for (int i = 0; i < BORNES_FPS.length; i++) {
                if (fps < BORNES_FPS[i]) {
                    tranche = i;
                    break;
                }
            }
            histogrammeFps[tranche]++;
            fenetreFrames = 0;
            fenetreDebutNanos = now;
        }
    }

    /** Tout ce que le rapport dit du système — GL et options MC compris, donc UNIQUEMENT sur ce fil. */
    private static SystemeSnapshot capturerSysteme(Minecraft mc) {
        Runtime rt = Runtime.getRuntime();
        List<String> gc = new ArrayList<>();
        for (var bean : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            gc.add(bean.getName());
        }
        String backend = "(indisponible)";
        int renderDistance = -1;
        int simulationDistance = -1;
        int framerateLimit = -1;
        try {
            var options = mc.options;
            backend = options.preferredGraphicsBackend().get().toString();
            renderDistance = options.renderDistance().get();
            simulationDistance = options.simulationDistance().get();
            framerateLimit = options.framerateLimit().get();
        } catch (Throwable pasEncorePret) {
            // Réglages indisponibles à cet instant : les champs gardent leur valeur sentinelle -1.
        }
        String gpu = null;
        try {
            String renderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            String vendor = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR);
            String version = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION);
            if (renderer != null) {
                gpu = renderer + (vendor != null ? " (" + vendor + ")" : "")
                        + (version != null ? " — pilote " + version : "");
            }
        } catch (Throwable pasDeContexteGl) {
            // Hors contexte GL courant : pas d'erreur à remonter, juste une ligne en moins.
        }
        List<String> modsRendu = new ArrayList<>();
        for (var entry : Map.of(
                Compagnons.SODIUM, "Sodium",
                Compagnons.HORIZONS, "Distant Horizons",
                Compagnons.IRIS, "Iris").entrySet()) {
            if (Compagnons.present(entry.getKey())) {
                modsRendu.add(entry.getValue());
            }
        }
        return new SystemeSnapshot(rt.availableProcessors(), rt.maxMemory() / 1024 / 1024,
                System.getProperty("java.version"), System.getProperty("java.vendor"), gc, backend,
                renderDistance, simulationDistance, framerateLimit, gpu, modsRendu);
    }

    // ------------------------------------------------------------------------------------------
    // Le travail lourd — TOUJOURS sur ANALYSE_EXECUTOR, jamais sur le fil de rendu.
    // ------------------------------------------------------------------------------------------

    private static void traiterTranche(TrancheSnapshot snap) {
        Path jfrTemp = null;
        boolean jfrOk = false;
        if (snap.recording() != null) {
            try {
                jfrTemp = Files.createTempFile("lanterne-profil-", ".jfr");
                jfrOk = MoteurJfr.arreterEtVider(snap.recording(), jfrTemp);
            } catch (IOException echec) {
                Lanterne.LOG.warn("[PROFIL] Fichier temporaire JFR impossible.", echec);
            }
        }
        try {
            Path rapport = ecrireRapport(snap, jfrOk ? jfrTemp : null);
            Lanterne.LOG.info("[PROFIL] Rapport écrit : {}", rapport);
            if (snap.envoiAutorise()) {
                mettreEnFile(rapport);
            }
        } catch (Throwable echec) {
            Lanterne.LOG.warn("[PROFIL] Échec de l'écriture du rapport de performance.", echec);
        } finally {
            if (jfrTemp != null) {
                try {
                    Files.deleteIfExists(jfrTemp);
                } catch (IOException ignored) {
                    // Fichier temporaire : pas grave s'il traîne, le système le nettoiera.
                }
            }
        }
    }

    private static Path ecrireRapport(TrancheSnapshot snap, Path jfrFichier) throws IOException {
        double avgFps = snap.framesVues() / snap.sessionSec();
        double avgFrameMs = snap.framesVues() > 0 ? snap.sommeFrameMs() / snap.framesVues() : 0;

        long totalImages = 0;
        for (long compte : snap.histogrammeFrameMs()) {
            totalImages += compte;
        }
        double p1Ms = percentileHisto(snap.histogrammeFrameMs(), totalImages, 0.99, snap.maxFrameMs());
        double p01Ms = percentileHisto(snap.histogrammeFrameMs(), totalImages, 0.999, snap.maxFrameMs());

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Files.createDirectories(snap.profilsDir());
        Path out = snap.profilsDir().resolve("client-" + timestamp + ".txt");

        StringBuilder sb = new StringBuilder();
        sb.append("PROFIL LANTERNE — client, tranche de session\n").append("=".repeat(60)).append('\n');
        sb.append("Fin de tranche  : ").append(snap.why()).append('\n');
        sb.append(String.format(Locale.ROOT, "Durée observée  : %.1f s (%d image(s))%n",
                snap.sessionSec(), snap.framesVues()));
        sb.append('\n');

        sb.append(formatSysteme(snap.systeme()));

        sb.append("IMAGES\n").append("-".repeat(60)).append('\n');
        sb.append(String.format(Locale.ROOT, "FPS moyen : %.1f (%.2f ms/image en moyenne)%n", avgFps, avgFrameMs));
        if (snap.minFrameMs() <= snap.maxFrameMs()) {
            sb.append(String.format(Locale.ROOT, "Image min/max : %.1f ms / %.1f ms%n",
                    snap.minFrameMs(), snap.maxFrameMs()));
        }
        if (totalImages > 0) {
            sb.append(String.format(Locale.ROOT,
                    "Pire 1 %% des images : %.1f ms (~%.1f FPS) — pire 0,1 %% : %.1f ms (~%.1f FPS)%n",
                    p1Ms, 1000.0 / Math.max(0.01, p1Ms), p01Ms, 1000.0 / Math.max(0.01, p01Ms)));
        }
        long totalFenetres = 0;
        for (long compte : snap.histogrammeFps()) {
            totalFenetres += compte;
        }
        if (totalFenetres > 0) {
            String[] etiquettes = {"< 20", "20-40", "40-60", "60-90", "90-120", "120+"};
            sb.append("Répartition FPS (fenêtres de 1s) :\n");
            long[] histoFps = snap.histogrammeFps();
            for (int i = 0; i < histoFps.length; i++) {
                if (histoFps[i] == 0) {
                    continue;
                }
                sb.append(String.format(Locale.ROOT, "  %-7s : %5.1f %% (%d s)%n", etiquettes[i],
                        100.0 * histoFps[i] / totalFenetres, histoFps[i]));
            }
        }
        sb.append('\n');

        if (jfrFichier != null) {
            try {
                List<AnalyseJfr.Fenetre> fenetres = new ArrayList<>();
                for (PireImage pire : snap.piresImages()) {
                    fenetres.add(new AnalyseJfr.Fenetre(
                            pire.instant().minusMillis((long) pire.ms()),
                            pire.instant(),
                            String.format(Locale.ROOT, "image de %.1f ms à %s", pire.ms(), pire.instant())));
                }
                sb.append(AnalyseJfr.rapportTexte(jfrFichier, snap.renderThreadName(), fenetres));
            } catch (IOException echecLecture) {
                sb.append("(lecture du profil JFR impossible : ").append(echecLecture.getMessage()).append(")\n\n");
            }
        } else {
            sb.append("PROFIL JFR INDISPONIBLE\n").append("-".repeat(60)).append('\n')
                    .append("  Le module jdk.jfr n'est pas présent sur ce runtime Java, ou son démarrage a")
                    .append('\n')
                    .append("  échoué : seules les statistiques d'images ci-dessus sont disponibles.\n\n");
        }

        String texte = sb.toString();
        byte[] octets = texte.getBytes(StandardCharsets.UTF_8);
        if (octets.length > TAILLE_RAPPORT_MAX) {
            String tronque = new String(octets, 0, TAILLE_RAPPORT_MAX, StandardCharsets.UTF_8)
                    + "\n\n[... rapport tronqué à " + (TAILLE_RAPPORT_MAX / 1024) + " Ko ...]\n";
            octets = tronque.getBytes(StandardCharsets.UTF_8);
        }
        Files.write(out, octets);
        return out;
    }

    /** Met en texte un {@link SystemeSnapshot} déjà capturé — aucun appel MC/GL ici, sûr hors fil de rendu. */
    private static String formatSysteme(SystemeSnapshot sys) {
        StringBuilder sb = new StringBuilder();
        sb.append("SYSTÈME\n").append("-".repeat(60)).append('\n');
        sb.append(String.format(Locale.ROOT, "Cœurs logiques : %d — mémoire JVM max : %d Mo%n",
                sys.coeurs(), sys.memoireMaxMo()));
        sb.append("Java : ").append(sys.javaVersion()).append(" (").append(sys.javaVendor()).append(")\n");
        sb.append("Ramasse-miettes actif(s) : ")
                .append(sys.gc().isEmpty() ? "(inconnu)" : String.join(", ", sys.gc())).append('\n');
        if (sys.renderDistance() >= 0) {
            sb.append("Backend graphique : ").append(sys.backendGraphique()).append('\n');
            sb.append(String.format(Locale.ROOT, "Distance de vue/simulation : %d / %d chunks%n",
                    sys.renderDistance(), sys.simulationDistance()));
            sb.append("Limite FPS (réglage joueur) : ").append(sys.framerateLimit()).append('\n');
        } else {
            sb.append("(réglages de rendu indisponibles à la capture)\n");
        }
        if (sys.gpu() != null) {
            sb.append("GPU : ").append(sys.gpu()).append('\n');
        }
        sb.append("Mods de rendu présents : ")
                .append(sys.modsRendu().isEmpty() ? "(aucun)" : String.join(", ", sys.modsRendu())).append('\n');
        sb.append('\n');
        return sb.toString();
    }

    /**
     * Centile approché depuis l'histogramme à pas fixe : compte depuis le bas jusqu'à atteindre la
     * fraction {@code p} des images, et rend la borne haute du seau atteint. Le dernier seau
     * (au-delà de 250 ms) n'a pas de borne haute connue : on rend alors le maximum EXACT déjà
     * conservé à côté ({@link TrancheSnapshot#maxFrameMs()}), jamais une valeur inventée.
     */
    private static double percentileHisto(long[] histo, long total, double p, double maxExact) {
        if (total <= 0) {
            return 0;
        }
        long cible = (long) Math.ceil(p * total);
        long cumul = 0;
        for (int i = 0; i < histo.length; i++) {
            cumul += histo[i];
            if (cumul >= cible) {
                return i == histo.length - 1 ? maxExact : (i + 1) * HISTO_PAS_MS;
            }
        }
        return maxExact;
    }

    // ------------------------------------------------------------------------------------------
    // Envoi réseau — même découpage que client.crash.BoiteNoire, voir sa Javadoc.
    // ------------------------------------------------------------------------------------------

    /** Appelée depuis {@link #ANALYSE_EXECUTOR} : lecture disque, jamais sur le fil de rendu. */
    private static void mettreEnFile(Path rapport) {
        try {
            byte[] bytes = Files.readAllBytes(rapport);
            if (bytes.length > ProfilTranche.MAX_TOTAL_BYTES) {
                byte[] tronque = new byte[ProfilTranche.MAX_TOTAL_BYTES];
                System.arraycopy(bytes, 0, tronque, 0, tronque.length);
                bytes = tronque;
            }
            int total = bytes.length;
            int offset = 0;
            while (offset < total) {
                int taille = Math.min(ProfilTranche.MAX_CHUNK_BYTES, total - offset);
                byte[] morceau = new byte[taille];
                System.arraycopy(bytes, offset, morceau, 0, taille);
                FILE_ATTENTE.add(new ProfilTranche(offset, total, morceau));
                offset += taille;
            }
            if (total == 0) {
                FILE_ATTENTE.add(new ProfilTranche(0, 0, new byte[0]));
            }
        } catch (IOException echec) {
            Lanterne.LOG.warn("[PROFIL] Lecture du rapport impossible, non envoyé : {}", rapport);
        }
    }

    /** Appelée depuis {@link #pulse}, donc sur le fil de rendu — {@link #FILE_ATTENTE} est concurrente. */
    private static void drainerFileAttente() {
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        ProfilTranche next = FILE_ATTENTE.poll();
        if (next == null) {
            return;
        }
        cooldown = IMAGES_ENTRE_PAQUETS;
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.getConnection() != null) {
                client.getConnection().send(next);
            }
        } catch (RuntimeException clientOuServeurSansCanal) {
            // Voir la Javadoc de MecheNet : un serveur d'avant ce mécanisme n'a pas négocié ce canal.
            FILE_ATTENTE.clear();
        }
    }
}
