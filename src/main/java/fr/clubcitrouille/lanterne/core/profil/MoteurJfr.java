package fr.clubcitrouille.lanterne.core.profil;

import java.nio.file.Path;
import java.time.Duration;

import jdk.jfr.Recording;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le moteur commun aux profils continus (client ET serveur) : un unique point qui sait démarrer et
 * arrêter un {@link Recording} JFR configuré à la main, sans jamais passer par une configuration
 * {@code .jfc} livrée sur le disque — voir la section « Pourquoi configuré à la main » plus bas.
 *
 * <h2>Pourquoi JFR, et pas la technique de {@code lab.Radiographie}</h2>
 *
 * <p>{@code Radiographie} interroge {@link java.lang.management.ThreadMXBean#getThreadInfo} depuis un
 * fil séparé, toutes les 15 ms, en continu : chaque relevé impose un <b>safepoint</b> à la JVM entière
 * (le thread ciblé, mais aussi potentiellement d'autres, doivent atteindre un point sûr avant que la
 * pile ne soit lisible). Trois joueurs ont planté sur un bug connu du ramasse-miettes G1 de Java 25 qui
 * se déclenche précisément à un safepoint — voir {@code client.ParadeG1} et la Javadoc de
 * {@code Radiographie} pour l'historique complet. Réintroduire ce même relevé en continu, même pour un
 * usage différent, referait courir le même risque.
 *
 * <p>L'échantillonneur natif de JFR ({@code jdk.ExecutionSample})
 * utilise un mécanisme distinct côté JVM (AsyncGetCallTrace / profilage asynchrone), conçu depuis
 * l'origine pour tourner en continu en production — c'est exactement l'outil que des profileurs comme
 * Spark ou async-profiler utilisent pour cette même raison. Aucune boucle Java à ce fichier ne réclame
 * de safepoint à intervalle fixe : c'est la JVM elle-même, via son moteur de diagnostic, qui échantillonne.
 *
 * <h2>Pourquoi configuré à la main plutôt qu'un fichier {@code .jfc}</h2>
 *
 * <p>Un fichier de configuration livré dans les ressources du mod serait un fichier de plus à
 * maintenir en phase avec cette classe, pour un gain nul : {@link Recording#enable(String)} accepte
 * directement un nom d'évènement JFR et un réglage, sans détour par XML. Toute la configuration
 * demandée (échantillonnage d'exécution ~20 ms, pauses GC, allocations avec seuil, contention à partir
 * de 20 ms, charge CPU, résumé du tas) tient dans {@link #construire}.
 *
 * <h2>Repli propre si {@code jdk.jfr} est absent</h2>
 *
 * <p>{@link #disponible()} sonde le module au runtime avant toute tentative — certains runtimes Java
 * distribués (musl, certains jlink minimalistes) l'excluent. Un lanceur CurseForge « epsilon » a été
 * vérifié porteur du module (java --list-modules, 25/09/2026) mais rien ne garantit que CE SERA
 * toujours le cas sur la machine d'un joueur : le repli doit rester réel, pas théorique.
 */
public final class MoteurJfr {
    private MoteurJfr() {}

    /** Vrai si le module {@code jdk.jfr} est présent dans ce runtime — à vérifier AVANT tout {@link #construire}. */
    public static boolean disponible() {
        try {
            return ModuleLayer.boot().findModule("jdk.jfr").isPresent();
        } catch (Throwable improbable) {
            // Un ModuleLayer.boot() qui lève serait déjà un environnement cassé bien au-delà de ce
            // mod — ne jamais laisser cette sonde elle-même faire planter le démarrage.
            return false;
        }
    }

    /**
     * Construit (sans le démarrer) un enregistrement JFR configuré pour un profil continu.
     *
     * @param nom un nom de diagnostic (visible dans {@code jcmd JFR.check}), pas un chemin de fichier.
     */
    public static Recording construire(String nom) {
        // AUCUNE configuration ".jfc" nommée (ni "default", ni "profile") : ces profils prédéfinis de
        // la JVM activent bien plus que ce qui est demandé ici (compilations JIT, sockets, classes
        // chargées...) — inutilement coûteux sur une fenêtre de 60 minutes. Le constructeur sans
        // argument démarre d'un enregistrement VIDE ; seule la liste exacte ci-dessous est activée.
        Recording recording = new Recording();
        recording.setName(nom);
        recording.setToDisk(true);
        recording.setDumpOnExit(false);
        recording.setMaxAge(Duration.ofMinutes(90)); // un peu plus que la rotation, pour ne rien perdre en bord de fenêtre

        // Échantillonnage d'exécution des piles — l'équivalent JFR de ce que Radiographie faisait à la
        // main, mais échantillonné par la JVM elle-même (voir la Javadoc de classe).
        recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(20));
        recording.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(20));

        // Pauses GC — chaque pause individuelle (jeune/vieille génération, remarquage...), et le
        // cycle complet pour le contexte (avant/après, cause).
        recording.enable("jdk.GCPhasePause");
        recording.enable("jdk.GCPhasePauseLevel1");
        recording.enable("jdk.GarbageCollection");
        recording.enable("jdk.GCHeapSummary");

        // Allocations, avec un débit raisonnable : "throttle" limite le nombre d'évènements ÉMIS par
        // la JVM elle-même (pas un filtre après coup), donc le coût reste plat même si le code alloue
        // énormément. 150 évènements/s suffit à faire ressortir les sites dominants sur une fenêtre de
        // 60 minutes sans noyer le rapport ni le disque.
        recording.enable("jdk.ObjectAllocationSample").with("throttle", "150/s");

        // Contention de moniteur : seulement les attentes réellement gênantes (>= 20 ms), pas chaque
        // verrou traversé sans attente.
        recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(20));

        // Charge CPU (process + machine) et résumé mémoire — bon marché, un évènement par seconde,
        // indispensables pour distinguer "le serveur/le jeu est le goulot" de "la machine entière l'est".
        recording.enable("jdk.CPULoad").withPeriod(Duration.ofSeconds(1));

        return recording;
    }

    /**
     * Arrête proprement l'enregistrement et le vide vers {@code destination}. Rend {@code false} sans
     * lever si l'écriture échoue — un profil raté ne doit jamais faire planter le jeu ou le serveur.
     */
    public static boolean arreterEtVider(Recording recording, Path destination) {
        try {
            recording.stop();
            recording.dump(destination);
            return true;
        } catch (Throwable probleme) {
            Lanterne.LOG.warn("[PROFIL] Échec de l'écriture de l'enregistrement JFR vers {}.",
                    destination, probleme);
            return false;
        } finally {
            recording.close();
        }
    }
}
