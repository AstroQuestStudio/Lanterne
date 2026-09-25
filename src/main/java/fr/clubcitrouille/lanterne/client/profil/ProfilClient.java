package fr.clubcitrouille.lanterne.client.profil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * <h2>Ce que fait, et ne fait pas, chaque tranche</h2>
 *
 * <p>Statistiques d'images (FPS moyen/min/percentiles, histogramme, pires images) : accumulées à la
 * main, image par image, exactement comme {@code Radiographie} — bon marché, voir sa Javadoc citée
 * dans la demande d'origine. Tout le reste (méthodes chaudes par fil, pauses GC, allocations,
 * contention, arbre d'appel) vient d'un {@link Recording} JFR démarré au début de la tranche et
 * vidé/arrêté à sa fin, puis lu par {@link AnalyseJfr}.
 *
 * <h2>Copie locale toujours écrite, envoi réseau sous consentement</h2>
 *
 * <p>Le rapport texte est toujours écrit dans {@code config/lanterne/profils/} — utile même hors
 * ligne, ou sur un serveur non-Lanterne. Il n'est mis en file pour le RÉSEAU que si
 * {@link BoiteNoireConsent#profilsAllowed()} répond vrai, exactement comme {@code client.crash.BoiteNoire}
 * pour les plantages — voir sa Javadoc de classe pour la garde {@code Dist.CLIENT} et pourquoi ce
 * fichier ne doit jamais être chargé par un serveur dédié.
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

    private static boolean running;
    private static boolean wasInWorld;
    private static long renderThreadId = -1L;
    private static String renderThreadName;

    // --- La tranche JFR en cours ---
    private static Recording recordingEnCours;
    private static boolean jfrDisponibleCetteTranche;
    private static Path fichierJfrTemp;

    // --- Repère pour convertir System.nanoTime() en Instant JFR — voir enregistrerImage. ---
    private static Instant refInstant;
    private static long refNanos;

    // --- Statistiques d'images de la tranche en cours ---
    private static long trancheDebutNanos;
    private static long framesVues;
    private static double sommeFrameMs;
    private static double minFrameMs;
    private static double maxFrameMs;
    private static final List<Double> frameMsTriables = new ArrayList<>();
    private static final int[] BORNES_FPS = {20, 40, 60, 90, 120};
    private static final long[] histogrammeFps = new long[BORNES_FPS.length + 1];
    private static long fenetreFrames;
    private static long fenetreDebutNanos;

    private static final class PireImage {
        final Instant instant;
        final double ms;

        PireImage(Instant instant, double ms) {
            this.instant = instant;
            this.ms = ms;
        }
    }

    private static final List<PireImage> piresImages = new ArrayList<>();

    // --- File d'attente réseau, drainée depuis pulse() (une image = un tick de cooldown ici). ---
    private static final Deque<ProfilTranche> FILE_ATTENTE = new ArrayDeque<>();
    private static final int IMAGES_ENTRE_PAQUETS = 3;
    private static int cooldown;

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
        frameMsTriables.clear();
        java.util.Arrays.fill(histogrammeFps, 0L);
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
        double dureeMin = (System.nanoTime() - trancheDebutNanos) / 60e9d;
        if (dureeMin * 60e9d >= DUREE_MINIMALE_NANOS) {
            rotation(why);
        } else if (recordingEnCours != null) {
            // Tranche trop courte pour un rapport utile : on ferme l'enregistrement sans l'analyser.
            recordingEnCours.close();
            recordingEnCours = null;
        }
    }

    /** Clôt la tranche en cours : arrête/vide le JFR, écrit et met en file le rapport. */
    private static void rotation(String why) {
        Path jfrTemp = null;
        boolean jfrOk = false;
        if (recordingEnCours != null) {
            try {
                jfrTemp = Files.createTempFile("lanterne-profil-", ".jfr");
                jfrOk = MoteurJfr.arreterEtVider(recordingEnCours, jfrTemp);
            } catch (IOException echec) {
                Lanterne.LOG.warn("[PROFIL] Fichier temporaire JFR impossible.", echec);
            }
            recordingEnCours = null;
        }
        try {
            Path rapport = ecrireRapport(why, jfrOk ? jfrTemp : null);
            Lanterne.LOG.info("[PROFIL] Rapport écrit : {}", rapport);
            if (BoiteNoireConsent.profilsAllowed() && !Minecraft.getInstance().hasSingleplayerServer()) {
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

    private static void enregistrerImage(Minecraft mc) {
        long now = System.nanoTime();
        double frameMs = mc.getFrameTimeNs() / 1e6d;
        framesVues++;
        sommeFrameMs += frameMs;
        minFrameMs = Math.min(minFrameMs, frameMs);
        maxFrameMs = Math.max(maxFrameMs, frameMs);
        frameMsTriables.add(frameMs);

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

    // ------------------------------------------------------------------------------------------
    // Le rapport
    // ------------------------------------------------------------------------------------------

    private static Path ecrireRapport(String why, Path jfrFichier) throws IOException {
        double sessionSec = Math.max(0.001d, (System.nanoTime() - trancheDebutNanos) / 1e9d);
        double avgFps = framesVues / sessionSec;
        double avgFrameMs = framesVues > 0 ? sommeFrameMs / framesVues : 0;

        List<Double> triees = new ArrayList<>(frameMsTriables);
        triees.sort(Comparator.naturalOrder());
        double p1Ms = percentile(triees, 0.99); // pire 1 % des images = 99e centile du TEMPS d'image
        double p01Ms = percentile(triees, 0.999);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(Lanterne.ID)
                .resolve("profils");
        Files.createDirectories(dir);
        Path out = dir.resolve("client-" + timestamp + ".txt");

        StringBuilder sb = new StringBuilder();
        sb.append("PROFIL LANTERNE — client, tranche de session\n").append("=".repeat(60)).append('\n');
        sb.append("Fin de tranche  : ").append(why).append('\n');
        sb.append(String.format(Locale.ROOT, "Durée observée  : %.1f s (%d image(s))%n", sessionSec, framesVues));
        sb.append('\n');

        sb.append(parametresSysteme());

        sb.append("IMAGES\n").append("-".repeat(60)).append('\n');
        sb.append(String.format(Locale.ROOT, "FPS moyen : %.1f (%.2f ms/image en moyenne)%n", avgFps, avgFrameMs));
        if (minFrameMs <= maxFrameMs) {
            sb.append(String.format(Locale.ROOT, "Image min/max : %.1f ms / %.1f ms%n", minFrameMs, maxFrameMs));
        }
        if (!triees.isEmpty()) {
            sb.append(String.format(Locale.ROOT,
                    "Pire 1 %% des images : %.1f ms (~%.1f FPS) — pire 0,1 %% : %.1f ms (~%.1f FPS)%n",
                    p1Ms, 1000.0 / Math.max(0.01, p1Ms), p01Ms, 1000.0 / Math.max(0.01, p01Ms)));
        }
        long totalFenetres = 0;
        for (long compte : histogrammeFps) {
            totalFenetres += compte;
        }
        if (totalFenetres > 0) {
            String[] etiquettes = {"< 20", "20-40", "40-60", "60-90", "90-120", "120+"};
            sb.append("Répartition FPS (fenêtres de 1s) :\n");
            for (int i = 0; i < histogrammeFps.length; i++) {
                if (histogrammeFps[i] == 0) {
                    continue;
                }
                sb.append(String.format(Locale.ROOT, "  %-7s : %5.1f %% (%d s)%n", etiquettes[i],
                        100.0 * histogrammeFps[i] / totalFenetres, histogrammeFps[i]));
            }
        }
        sb.append('\n');

        if (jfrFichier != null) {
            try {
                List<AnalyseJfr.Fenetre> fenetres = new ArrayList<>();
                for (PireImage pire : piresImages) {
                    fenetres.add(new AnalyseJfr.Fenetre(
                            pire.instant.minusMillis((long) pire.ms),
                            pire.instant,
                            String.format(Locale.ROOT, "image de %.1f ms à %s", pire.ms, pire.instant)));
                }
                sb.append(AnalyseJfr.rapportTexte(jfrFichier, renderThreadName, fenetres));
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

    /** Infos système : CPU, RAM, GPU/pilote, Java, GC, options de rendu, mods de rendu présents. */
    private static String parametresSysteme() {
        StringBuilder sb = new StringBuilder();
        sb.append("SYSTÈME\n").append("-".repeat(60)).append('\n');
        Runtime rt = Runtime.getRuntime();
        sb.append(String.format(Locale.ROOT, "Cœurs logiques : %d — mémoire JVM max : %d Mo%n",
                rt.availableProcessors(), rt.maxMemory() / 1024 / 1024));
        sb.append("Java : ").append(System.getProperty("java.version"))
                .append(" (").append(System.getProperty("java.vendor")).append(")\n");
        sb.append("Ramasse-miettes actif(s) : ");
        List<String> gc = new ArrayList<>();
        for (var bean : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            gc.add(bean.getName());
        }
        sb.append(gc.isEmpty() ? "(inconnu)" : String.join(", ", gc)).append('\n');
        try {
            var options = Minecraft.getInstance().options;
            sb.append("Backend graphique : ").append(options.preferredGraphicsBackend().get()).append('\n');
            sb.append(String.format(Locale.ROOT, "Distance de vue/simulation : %d / %d chunks%n",
                    options.renderDistance().get(), options.simulationDistance().get()));
            sb.append("Limite FPS (réglage joueur) : ").append(options.framerateLimit().get()).append('\n');
        } catch (Throwable pasEncorePret) {
            sb.append("(réglages de rendu indisponibles à cet instant)\n");
        }
        try {
            String renderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            String vendor = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR);
            String version = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION);
            if (renderer != null) {
                sb.append("GPU : ").append(renderer)
                        .append(vendor != null ? " (" + vendor + ")" : "")
                        .append(version != null ? " — pilote " + version : "").append('\n');
            }
        } catch (Throwable pasDeContexteGl) {
            // Hors contexte GL courant : pas d'erreur à remonter, juste une ligne en moins.
        }
        sb.append("Mods de rendu présents : ");
        List<String> presents = new ArrayList<>();
        for (var entry : Map.of(
                Compagnons.SODIUM, "Sodium",
                Compagnons.HORIZONS, "Distant Horizons",
                Compagnons.IRIS, "Iris").entrySet()) {
            if (Compagnons.present(entry.getKey())) {
                presents.add(entry.getValue());
            }
        }
        sb.append(presents.isEmpty() ? "(aucun)" : String.join(", ", presents)).append('\n');
        sb.append('\n');
        return sb.toString();
    }

    private static double percentile(List<Double> triees, double p) {
        if (triees.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p * triees.size()) - 1;
        index = Math.max(0, Math.min(triees.size() - 1, index));
        return triees.get(index);
    }

    // ------------------------------------------------------------------------------------------
    // Envoi réseau — même découpage que client.crash.BoiteNoire, voir sa Javadoc.
    // ------------------------------------------------------------------------------------------

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
