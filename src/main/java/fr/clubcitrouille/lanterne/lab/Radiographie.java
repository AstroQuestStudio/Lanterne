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
import java.util.LinkedHashMap;
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

    // --- Creux de FPS : capture la pile echantillonnee PENDANT une fenetre de 1s trop lente. ---
    // Seuil sous lequel une fenetre de 1s est consideree comme un creux qui merite d'etre expliqué
    // dans le rapport, pas juste compté dans fpsMin. 10 FPS : sous ce chiffre, une image dure plus
    // de 100 ms — un gel perceptible, jamais une simple fluctuation de charge.
    private static double seuilCreuxFps = 10.0d;
    // Nombre maximal de creux conserves AVEC leur pile dans un rapport : borne la taille du
    // rapport final pour une session ou le jeu resterait durablement sous le seuil (le probleme,
    // alors, n'est plus de savoir OU chercher — il est ailleurs, et un rapport de 500 piles
    // identiques n'aiderait pas plus qu'un chiffre de FPS seul).
    private static final int CREUX_MAX = 15;
    // Echantillons (tableaux de frames DEJA captures par le fil d'echantillonnage, voir record())
    // vus depuis le debut de la fenetre de 1s en cours. Vide a chaque fin de fenetre (creux ou
    // non) par recordFrame, sous treeLock.
    private static final List<StackTraceElement[]> fenetreEnCours = new ArrayList<>();
    // Un bloc de texte deja mis en forme par creux detecte, pret a etre colle dans le rapport.
    private static final List<String> creuxDetectes = new ArrayList<>();

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
        synchronized (treeLock) {
            fenetreEnCours.clear();
        }
        creuxDetectes.clear();
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

            // Conserve une référence vers ce tableau pour la fenêtre FPS en cours (voir
            // recordFrame/noterCreuxSiBesoin) : ThreadMXBean#getThreadInfo a DÉJÀ été appelé par
            // loop() avant record() — on ne fait ici que garder le résultat un peu plus longtemps
            // (le temps d'une fenêtre, une seconde) plutôt que de le laisser partir au ramasse-
            // miettes immédiatement. Coût : une référence ajoutée à une liste, rien de plus.
            fenetreEnCours.add(frames);
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
        noterCreuxSiBesoin(fps, (now - sessionStartNanos) / 1e9d);
        windowFrames = 0;
        windowNanos = 0;
        lastFrameLogNanos = now;
    }

    /**
     * Si la fenêtre de 1s qui vient de se terminer est passée sous {@link #seuilCreuxFps}, fige un
     * aperçu des piles échantillonnées PENDANT cette fenêtre dans le rapport final — un horodatage
     * et une vraie pile d'appel, plutôt qu'un chiffre de FPS isolé sans aucun moyen de savoir ce qui
     * se passait à ce moment-là (voir le rapport réel du 20/09 : « FPS min/max : 0.4 / 326.0 » sur
     * 613 s, sans aucune piste sur le gel de ~2,5 s qu'un FPS de 0.4 implique).
     *
     * <h2>Pourquoi ce n'est pas un coût ajouté à chaque échantillon</h2>
     *
     * <p>Aucun relevé de pile supplémentaire n'a lieu ici : {@link #record} garde déjà, sans coût
     * réel, une référence vers chaque tableau de frames dans {@link #fenetreEnCours} — l'appel à
     * {@link ThreadMXBean#getThreadInfo} a déjà eu lieu dans {@link #loop}, on retient juste le
     * résultat un peu plus longtemps. Le travail réellement coûteux — dédupliquer et formater ces
     * piles en texte lisible, dans {@link #formatCreux} — n'a lieu QUE si un creux est confirmé,
     * ce qui reste rare par construction (un seuil de {@link #seuilCreuxFps} FPS n'est, par
     * définition, pas la norme d'une session jouable).
     */
    private static void noterCreuxSiBesoin(double fps, double sec) {
        List<StackTraceElement[]> echantillons;
        synchronized (treeLock) {
            if (fenetreEnCours.isEmpty()) {
                return;
            }
            echantillons = new ArrayList<>(fenetreEnCours);
            fenetreEnCours.clear();
        }
        if (fps >= seuilCreuxFps || creuxDetectes.size() >= CREUX_MAX) {
            return;
        }
        creuxDetectes.add(formatCreux(sec, fps, echantillons));
    }

    /**
     * Formate un creux détecté : horodatage, FPS de la fenêtre, et les piles DISTINCTES vues
     * pendant cette fenêtre — dédupliquées et comptées, plutôt que répétées une fois par
     * échantillon. Un gel bloque généralement sur la même instruction pendant toute sa durée ;
     * répéter soixante fois la même pile de vingt lignes n'aiderait pas plus qu'une seule copie
     * avec un compte à côté.
     */
    private static String formatCreux(double sec, double fps, List<StackTraceElement[]> echantillons) {
        // LinkedHashMap : conserve l'ordre de première apparition, pour un rapport reproductible
        // (deux lectures du même fichier donnent le même ordre) plutôt que l'ordre de hachage.
        Map<String, Integer> comptes = new LinkedHashMap<>();
        Map<String, StackTraceElement[]> exemples = new LinkedHashMap<>();
        for (StackTraceElement[] frames : echantillons) {
            String cle = signatureCourte(frames);
            comptes.merge(cle, 1, Integer::sum);
            exemples.putIfAbsent(cle, frames);
        }
        List<Map.Entry<String, Integer>> tri = new ArrayList<>(comptes.entrySet());
        tri.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "  t+%.0fs : %.1f FPS — %d échantillon(s) capturé(s) pendant cette fenêtre, "
                        + "%d pile(s) distincte(s)%n",
                sec, fps, echantillons.size(), tri.size()));
        int rang = 0;
        for (Map.Entry<String, Integer> entry : tri) {
            rang++;
            if (rang > 3) {
                sb.append(String.format(Locale.ROOT,
                        "    ... %d pile(s) distincte(s) supplémentaire(s), non affichée(s)%n",
                        tri.size() - 3));
                break;
            }
            StackTraceElement[] frames = exemples.get(entry.getKey());
            sb.append(String.format(Locale.ROOT, "    pile n°%d (%d/%d échantillons) :%n",
                    rang, entry.getValue(), echantillons.size()));
            int montrees = 0;
            for (StackTraceElement frame : frames) {
                if (estFrameOpaque(frame)) {
                    continue; // même résolution que le reste du rapport, voir estFrameOpaque
                }
                sb.append("      ").append(frame.getClassName()).append('.')
                        .append(frame.getMethodName()).append('\n');
                montrees++;
                if (montrees >= 25) {
                    sb.append("      ... (pile tronquée)\n");
                    break;
                }
            }
        }
        return sb.toString();
    }

    /**
     * Une clef courte pour regrouper deux échantillons de la même fenêtre s'ils viennent du même
     * endroit — les six frames du sommet (sommet de pile en premier) suffisent à distinguer deux
     * piles vraiment différentes sans comparer les 128 frames possibles à chaque fois.
     */
    private static String signatureCourte(StackTraceElement[] frames) {
        StringBuilder sig = new StringBuilder();
        int max = Math.min(frames.length, 6);
        for (int i = 0; i < max; i++) {
            if (estFrameOpaque(frames[i])) {
                continue;
            }
            sig.append(frames[i].getClassName()).append('.').append(frames[i].getMethodName()).append('|');
        }
        return sig.toString();
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
        // La grêle : voir fr.clubcitrouille.lanterne.core.Grele — plafond de particules de degat,
        // cumule depuis le demarrage du client, cote serveur (integre en solo).
        sb.append(fr.clubcitrouille.lanterne.core.Grele.report()).append('\n');
        // Le transfert par lots : voir fr.clubcitrouille.lanterne.core.Bulk — cumule depuis le
        // demarrage du client, cote serveur ; reste a "aucun transfert" si le reglage "bulk" est
        // eteint ou si aucun entonnoir n'a travaille cette session.
        sb.append(fr.clubcitrouille.lanterne.core.Bulk.report()).append('\n');
        // Le pont AutoMiner pour la durabilite : voir fr.clubcitrouille.lanterne.core.Increvable —
        // cumule depuis le demarrage du client, cote serveur ; reste a "aucune durabilite epargnee"
        // sans client AutoMiner detecte, quel que soit l'etat du reglage.
        sb.append(fr.clubcitrouille.lanterne.core.Increvable.report()).append('\n');
        sb.append(fr.clubcitrouille.lanterne.core.Rafale.report()).append('\n');
        sb.append('\n');

        sb.append(String.format(Locale.ROOT,
                "CREUX DE FPS DÉTECTÉS (fenêtres de 1s sous %.1f FPS)%n", seuilCreuxFps))
                .append("-".repeat(60)).append('\n');
        if (creuxDetectes.isEmpty()) {
            sb.append("  (aucune fenêtre sous le seuil pendant cette session)\n");
        } else {
            for (String creux : creuxDetectes) {
                sb.append(creux);
            }
            if (creuxDetectes.size() >= CREUX_MAX) {
                sb.append(String.format(Locale.ROOT,
                        "  (limite de %d creux capturés atteinte — d'autres ont pu survenir sans "
                                + "être capturés)%n",
                        CREUX_MAX));
            }
        }
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
