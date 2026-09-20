package fr.clubcitrouille.lanterne.lab;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.client.upscale.Upscale;

/**
 * La radiographie : un profileur d'échantillonnage façon Spark, embarqué, qui se déclenche tout
 * seul et écrit son rapport tout seul — parce que la vraie machine à mesurer, cette session, n'a
 * jamais été un poste de développement mais celle du joueur.
 *
 * <h2>Pourquoi automatique et non commandé</h2>
 *
 * <p>Un profileur qu'il faut penser à armer avant de jouer, puis penser à arrêter avant de fermer,
 * est un profileur qui ne tourne jamais quand l'incident qu'on cherche se produit — on ne sait pas
 * à l'avance qu'on va voir un flash. Celui-ci démarre au premier monde vu ({@link #pulse}, appelé
 * depuis {@code Pane.onFrame}, qui remarque la transition « pas de monde → monde ») et écrit son
 * rapport dès que le monde disparaît, qu'il s'agisse d'une déconnexion propre ou d'un retour au
 * menu. Un crochet d'arrêt de la JVM ({@link #armShutdownHook}) couvre le cas où la fenêtre est
 * fermée directement, sans passage par un menu.
 *
 * <p>Une commande manuelle ({@code /lanterne profil demarrer|arreter}, voir le paquet
 * {@code client.profil}) reste disponible en secours — pour rejouer une session ciblée sans
 * redémarrer le jeu, ou pour couper l'échantillonnage si jamais son coût dérangeait.
 *
 * <h2>Comment l'échantillonnage reste bon marché</h2>
 *
 * <p>{@link ThreadMXBean#getThreadInfo(long, int)} lit la pile du thread de rendu <b>sans le
 * suspendre</b> — contrairement à {@code Thread.getStackTrace()} appelé depuis un autre thread, qui
 * peut brièvement geler sa cible sur certaines JVM. Le fil qui échantillonne est un fil séparé,
 * démon, qui dort {@link #INTERVAL_MS} millisecondes entre deux relevés : le coût pour le thread de
 * rendu lui-même est nul, celui pour la JVM est un relevé de pile toutes les quelques millisecondes,
 * ce que Spark fait déjà en production sur des serveurs bien plus chargés qu'un client solo.
 */
public final class Radiographie {
    private Radiographie() {}

    private static final ThreadMXBean BEAN = ManagementFactory.getThreadMXBean();
    private static final int MAX_DEPTH = 128;
    private static final long INTERVAL_MS = 15L;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile Thread samplerThread;
    private static volatile long renderThreadId = -1L;
    private static boolean wasInWorld;
    private static boolean shutdownHookArmed;

    // --- Arbre d'appel : accumulé sous verrou, la construction est bon marché comparée au relevé. ---
    private static final Object treeLock = new Object();
    private static final Node root = new Node("(racine)");
    private static final Map<String, Integer> selfByMethod = new HashMap<>();
    private static int totalSamples;

    // --- FPS, sur toute la session, pas juste une fenêtre glissante comme Observe. ---
    private static long sessionStartNanos;
    private static long framesSeen;
    private static long frameNanosAccum;
    private static double fpsMin;
    private static double fpsMax;
    private static long lastFrameLogNanos;
    private static long windowFrames;
    private static long windowNanos;

    // --- L'état de la lentille, releve a chaque transition plutot qu'a chaque image. ---
    private static final List<String> lentilleTimeline = new ArrayList<>();
    private static String lastLentilleState;

    /**
     * Appelée depuis {@code Pane.onFrame}, chaque image, sans condition. Doit rester la méthode la
     * moins chère de ce fichier : c'est la seule qui tourne soixante fois par seconde même quand
     * l'échantillonnage est arrêté.
     */
    public static void pulse() {
        armShutdownHook();

        Minecraft mc = Minecraft.getInstance();
        boolean inWorld = mc.level != null;
        if (inWorld && !wasInWorld) {
            start();
        } else if (!inWorld && wasInWorld) {
            stop("monde quitté");
        }
        wasInWorld = inWorld;

        if (!running.get()) {
            return;
        }
        if (renderThreadId < 0) {
            renderThreadId = Thread.currentThread().threadId();
        }

        recordFrame(mc);
        recordLentille();
    }

    /** Démarre l'échantillonnage. Idempotent : un second appel pendant une session en cours ne fait rien. */
    public static synchronized boolean start() {
        if (running.get()) {
            return false;
        }
        synchronized (treeLock) {
            root.reset();
            selfByMethod.clear();
            totalSamples = 0;
        }
        sessionStartNanos = System.nanoTime();
        framesSeen = 0;
        frameNanosAccum = 0;
        fpsMin = Double.MAX_VALUE;
        fpsMax = 0;
        lastFrameLogNanos = 0;
        windowFrames = 0;
        windowNanos = 0;
        lentilleTimeline.clear();
        lastLentilleState = null;
        renderThreadId = -1L;
        running.set(true);

        samplerThread = new Thread(Radiographie::loop, "Lanterne-Radiographie");
        samplerThread.setDaemon(true);
        samplerThread.start();
        Lanterne.LOG.info("[RADIOGRAPHIE] Échantillonnage démarré (un monde vient d'apparaître).");
        return true;
    }

    /** Arrête l'échantillonnage et écrit le rapport. Rend le chemin écrit, ou {@code null} si rien n'était en cours. */
    public static synchronized Path stop(String why) {
        if (!running.compareAndSet(true, false)) {
            return null;
        }
        Thread sampler = samplerThread;
        samplerThread = null;
        if (sampler != null) {
            sampler.interrupt();
            try {
                sampler.join(1000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            Path written = writeReport(why);
            Lanterne.LOG.info("[RADIOGRAPHIE] Rapport écrit : {}", written);
            return written;
        } catch (Throwable problem) {
            Lanterne.LOG.warn("[RADIOGRAPHIE] Échec de l'écriture du rapport.", problem);
            return null;
        }
    }

    /**
     * Couvre la fermeture directe de la fenêtre : aucun évènement de démontage de monde ne passe
     * alors forcément par {@code Pane.onFrame} avant l'arrêt de la JVM. Posé une seule fois, au
     * premier {@link #start()} — pas besoin de plus, un crochet d'arrêt ne sert qu'une fois de toute
     * façon.
     */
    private static void armShutdownHook() {
        if (shutdownHookArmed) {
            return;
        }
        shutdownHookArmed = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (running.get()) {
                stop("fermeture du jeu");
            }
        }, "Lanterne-Radiographie-Arret"));
    }

    private static void loop() {
        while (running.get()) {
            long id = renderThreadId;
            if (id >= 0) {
                ThreadInfo info = BEAN.getThreadInfo(id, MAX_DEPTH);
                if (info != null) {
                    record(info.getStackTrace());
                }
            }
            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                return;
            }
        }
    }

    /**
     * Vrai si cette frame appartient à la machinerie interne de {@code java.lang.invoke}
     * (LambdaForm$MH, LambdaForm$DMH, DirectMethodHandle$Holder, BoundMethodHandle$Species...)
     * plutôt qu'à du code source réel — un nom que la JVM génère à la volée, souvent suffixé d'une
     * adresse hexadécimale comme {@code 0x00000000ac600000}, et qui ne dit RIEN du vrai site
     * d'appel.
     *
     * <h2>Vérifié au runtime (Java 21, JDK Temurin), pas supposé</h2>
     *
     * <p>Sonde autonome (hors dépôt, hors Minecraft — 3 s de boucle échantillonnée par
     * {@link ThreadMXBean#getThreadInfo}, exactement comme ici) : une boucle qui fait de la
     * <b>concaténation de chaînes</b> (le candidat le plus évident, {@code invokedynamic} vers
     * {@code StringConcatFactory}) ne produit JAMAIS ces frames en sommet de pile — 0 échantillon
     * sur 608. En revanche, une boucle qui fait un <b>switch sur type scellé</b> (pattern matching,
     * JEP 441, bootstrap {@code SwitchBootstraps.typeSwitch} — un mécanisme réellement présent
     * dans ce moteur, Java 21) produit {@code java.lang.invoke.LambdaForm$MH...invoke} en sommet de
     * pile pour 672 échantillons sur 672 — 100 %. C'est ce second mécanisme, vérifié et non une
     * hypothèse, qui explique les entrées opaques vues dans les rapports réels de cette session
     * (jusqu'à 9 % de temps propre cumulé pour une seule classe cachée, par exemple
     * {@code LambdaForm$MH/0x00000000ac600000.invoke} dans le rapport du 20/09 14:32).
     *
     * <p>Ce même rapport réel (38036 échantillons) confirme aussi que grouper par ce nom brut est
     * la PIRE façon d'agréger : dans l'arbre d'appel de ce rapport, les deux seules frames
     * LambdaForm visibles sont à la racine absolue (chaîne de lancement FML/ModLauncher, 100 %
     * trivial) — les 9 %, 5,1 %, 2,5 % etc. de la liste des méthodes coûteuses n'y apparaissent
     * NULLE PART, parce qu'elles sont fragmentées sur des dizaines de sites d'appel différents qui
     * partagent tous la même classe cachée (la JVM réutilise une classe LambdaForm par « forme » de
     * chaîne d'adaptateurs, pas par site d'appel source) : chaque branche individuelle tombe sous
     * le seuil d'élagage de 1 % de l'arbre, et le total de 9 % ne se voit donc QUE dans la liste
     * plate, sous un nom qui ne désigne aucun site d'appel réel.
     *
     * <p>D'où le choix retenu ici : ne pas grouper par ce nom du tout — grouper par le premier
     * appelant réel trouvé en remontant la pile DÉJÀ capturée. Aucune capture supplémentaire n'est
     * nécessaire : {@link ThreadMXBean#getThreadInfo} rapporte déjà jusqu'à {@link #MAX_DEPTH}
     * frames par relevé, la frame appelante réelle y est déjà, juste ignorée jusqu'ici.
     *
     * <p>N'exclut QUE {@code java.lang.invoke.*} — les lambdas Java ordinaires (capturées via
     * {@code LambdaMetafactory}) ne passent pas par là : elles s'exécutent comme des méthodes
     * compilées normales, nommées {@code lambda$méthode$N}, déjà lisibles (voir par exemple
     * {@code ClientLevel.lambda$tickEntities$0} dans l'arbre d'appel d'un rapport réel) — pas
     * besoin, et pas de preuve, de les filtrer aussi.
     */
    private static boolean estFrameOpaque(StackTraceElement frame) {
        return frame.getClassName().startsWith("java.lang.invoke.");
    }

    private static void record(StackTraceElement[] frames) {
        if (frames.length == 0) {
            return;
        }
        synchronized (treeLock) {
            totalSamples++;
            Node node = root;
            node.total++;
            // frames[0] est le sommet de pile (méthode en cours) ; le dernier indice est la racine
            // du thread. On parcourt donc de la racine vers la feuille pour bâtir l'arbre dans le
            // bon sens — celui d'un appelant vers ce qu'il appelle. Les frames opaques de
            // java.lang.invoke.* sont sautées (voir estFrameOpaque) : le nœud obtenu après la
            // boucle est donc déjà la frame réelle la plus proche du sommet de pile, pas un nom de
            // classe cachée généré par la JVM.
            for (int i = frames.length - 1; i >= 0; i--) {
                if (estFrameOpaque(frames[i])) {
                    continue;
                }
                String key = frames[i].getClassName() + "." + frames[i].getMethodName();
                node = node.child(key);
                node.total++;
            }
            node.self++;
            // Si le sommet de pile brut était lui-même opaque, le nœud retenu est celui de
            // l'appelant réel : l'étiquette le signale, pour que l'analyste sache que ce chiffre
            // vient d'une résolution et pas d'un temps propre mesuré au niveau du bytecode exact de
            // cette méthode.
            String leafKey = estFrameOpaque(frames[0])
                    ? node.name + "  [via invokedynamic/MethodHandle]"
                    : node.name;
            selfByMethod.merge(leafKey, 1, Integer::sum);
        }
    }

    private static void recordFrame(Minecraft mc) {
        long now = System.nanoTime();
        long frameNs = mc.getFrameTimeNs();
        framesSeen++;
        frameNanosAccum += frameNs;

        windowFrames++;
        windowNanos += frameNs;
        if (lastFrameLogNanos == 0L) {
            lastFrameLogNanos = now;
            return;
        }
        long elapsed = now - lastFrameLogNanos;
        if (elapsed < 1_000_000_000L) {
            return;
        }
        double fps = windowFrames / (elapsed / 1e9d);
        if (fps < fpsMin) {
            fpsMin = fps;
        }
        if (fps > fpsMax) {
            fpsMax = fps;
        }
        windowFrames = 0;
        windowNanos = 0;
        lastFrameLogNanos = now;
    }

    private static void recordLentille() {
        String state = Upscale.broken() ? "en-panne"
                : Upscale.active() ? Upscale.describe()
                : "native";
        if (!state.equals(lastLentilleState)) {
            double sec = (System.nanoTime() - sessionStartNanos) / 1e9d;
            lentilleTimeline.add(String.format(Locale.ROOT, "  t+%.0fs : %s", sec, state));
            lastLentilleState = state;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Le rapport
    // ------------------------------------------------------------------------------------------

    private static Path writeReport(String why) throws IOException {
        double sessionSec = Math.max(0.001d, (System.nanoTime() - sessionStartNanos) / 1e9d);
        double avgFps = framesSeen / sessionSec;
        double avgFrameMs = framesSeen > 0 ? (frameNanosAccum / (double) framesSeen) / 1e6d : 0;

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(Lanterne.ID);
        Files.createDirectories(dir);
        Path out = dir.resolve("profil-" + timestamp + ".txt");

        StringBuilder sb = new StringBuilder();
        sb.append("RADIOGRAPHIE LANTERNE — rapport de session\n");
        sb.append("=".repeat(60)).append('\n');
        sb.append("Fin de session : ").append(why).append('\n');
        sb.append(String.format(Locale.ROOT, "Durée observée  : %.1f s (%d image(s))%n", sessionSec, framesSeen));
        sb.append(String.format(Locale.ROOT, "Échantillons    : %d (un toutes les %d ms)%n", totalSamples, INTERVAL_MS));
        sb.append('\n');

        sb.append("RÉSUMÉ\n").append("-".repeat(60)).append('\n');
        sb.append(String.format(Locale.ROOT, "FPS moyen : %.1f (%.2f ms/image en moyenne)%n", avgFps, avgFrameMs));
        if (fpsMin <= fpsMax) {
            sb.append(String.format(Locale.ROOT, "FPS min/max (fenêtres de 1s) : %.1f / %.1f%n", fpsMin, fpsMax));
        }
        // Fusion de faces (terrain) : voir fr.clubcitrouille.lanterne.client.terrain.Greedy — cumulé
        // depuis le démarrage du client, pas juste cette session, mais suffisant pour comparer un
        // lancement LANTERNE_GREEDY_MESH=1 à un lancement sans (le compteur reste à "aucun quad vu"
        // dans ce cas, Greedy.accept() retournant false immédiatement).
        sb.append(fr.clubcitrouille.lanterne.client.terrain.Greedy.report()).append('\n');
        // Le guet : voir fr.clubcitrouille.lanterne.client.terrain.Guet — desactive par defaut
        // (reglage "guet"), rapporte donc "guet : desactive" tant qu'un joueur ne l'a pas active
        // explicitement pour une session de mesure.
        sb.append(fr.clubcitrouille.lanterne.client.terrain.Guet.report()).append('\n');
        // Le reflet : voir fr.clubcitrouille.lanterne.core.Reflet — MC-228976, allume par defaut,
        // cumule depuis le demarrage du client comme Greedy ci-dessus.
        sb.append(fr.clubcitrouille.lanterne.core.Reflet.report()).append('\n');
        sb.append('\n');

        sb.append("LENTILLE (mise à l'échelle) — chronologie\n").append("-".repeat(60)).append('\n');
        if (lentilleTimeline.isEmpty()) {
            sb.append("  (aucun changement d'état observé pendant la session)\n");
        } else {
            for (String line : lentilleTimeline) {
                sb.append(line).append('\n');
            }
        }
        sb.append('\n');

        sb.append("MÉTHODES LES PLUS COÛTEUSES (temps propre, hors ce qu'elles appellent)\n")
                .append("-".repeat(60)).append('\n');
        List<Map.Entry<String, Integer>> top = new ArrayList<>(selfByMethod.entrySet());
        top.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());
        int shown = 0;
        for (Map.Entry<String, Integer> entry : top) {
            if (shown >= 25) {
                break;
            }
            double pct = totalSamples > 0 ? 100.0 * entry.getValue() / totalSamples : 0;
            if (pct < 0.2d) {
                break;
            }
            sb.append(String.format(Locale.ROOT, "  %5.1f %%  %s%n", pct, entry.getKey()));
            shown++;
        }
        if (shown == 0) {
            sb.append("  (aucun échantillon exploitable — session trop courte ?)\n");
        }
        sb.append('\n');

        sb.append("ARBRE D'APPEL (branches chaudes, temps total, élagué sous 1 %)\n")
                .append("-".repeat(60)).append('\n');
        printTree(sb, root, 0, totalSamples);

        Files.writeString(out, sb.toString());
        return out;
    }

    private static void printTree(StringBuilder sb, Node node, int depth, int totalSamplesRef) {
        if (depth > 10) {
            return;
        }
        List<Node> children = new ArrayList<>(node.children.values());
        children.sort(Comparator.<Node>comparingInt(n -> n.total).reversed());
        for (Node child : children) {
            double pct = totalSamplesRef > 0 ? 100.0 * child.total / totalSamplesRef : 0;
            if (pct < 1.0d) {
                continue;
            }
            sb.append("  ".repeat(depth)).append(String.format(Locale.ROOT, "%5.1f %%  %s%n", pct, child.name));
            printTree(sb, child, depth + 1, totalSamplesRef);
        }
    }

    private static final class Node {
        final String name;
        final Map<String, Node> children = new HashMap<>();
        int total;
        int self;

        Node(String name) {
            this.name = name;
        }

        Node child(String key) {
            return children.computeIfAbsent(key, Node::new);
        }

        void reset() {
            children.clear();
            total = 0;
            self = 0;
        }
    }
}
