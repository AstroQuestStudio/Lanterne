package fr.clubcitrouille.lanterne.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jdk.jfr.Recording;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.profil.AnalyseJfr;
import fr.clubcitrouille.lanterne.core.profil.MoteurJfr;

/**
 * Le profil de performance continu du SERVEUR lui-même : même moteur ({@link MoteurJfr}) que
 * {@code client.profil.ProfilClient}, même rotation de 60 minutes, mais démarré tout seul au lancement
 * du serveur (pas besoin d'un « monde vu » — le serveur EST le monde) et sans aucune question de
 * consentement (ce sont les données du serveur, sur sa propre machine, écrites dans son propre dossier
 * {@code logs/}, jamais envoyées nulle part).
 *
 * <h2>Le Server thread ne fait QUE figer un instantané</h2>
 *
 * <p>Exactement le même problème, et la même réponse, que {@code client.profil.ProfilClient} — voir sa
 * Javadoc de classe. Arrêter/vider un enregistrement JFR d'une heure puis l'analyser prend, mesuré,
 * jusqu'à quelques secondes : un tick qui ferait ça bloquerait TOUS les joueurs connectés, une fois par
 * heure. {@link #onServerTick} ne fait donc, sur le Server thread, que copier des compteurs primitifs
 * dans un {@link TrancheSnapshot} immuable, détacher l'enregistrement JFR, et redémarrer aussitôt la
 * tranche suivante ; {@link #ANALYSE_EXECUTOR} (fil démon unique, priorité basse) fait tout le reste.
 * Seule exception délibérée : {@link #onServerStopping}, où l'on ATTEND la fin de l'écriture — le
 * process va se terminer de toute façon, et un rapport de fin de session perdu ne se rattrape pas.
 *
 * <h2>L'instantané par dimension reste plafonné</h2>
 *
 * <p>Pris SUR le Server thread (il touche l'état du monde, non thread-safe) mais borné en coût :
 * {@link #MAX_ENTITES_INSTANTANE} et {@link #MAX_CHUNKS_INSTANTANE} arrêtent le parcours dès que le
 * plafond est atteint plutôt que de laisser un monde immense allonger un seul tick sans limite.
 *
 * <h2>Réglage {@code profil_serveur}, vrai par défaut</h2>
 *
 * <p>Contrairement à {@code lab.RadiographieServeur} (manuel, réservé aux diagnostics ponctuels), ce
 * mécanisme est actif par défaut : c'est la demande explicite du propriétaire. {@link Config#PROFIL_SERVEUR}
 * permet de l'éteindre sur un hébergement où même le coût annoncé (1-2 %) ne serait pas souhaité.
 *
 * <h2>Repli si {@code jdk.jfr} est absent</h2>
 *
 * <p>Comme côté client : {@link MoteurJfr#disponible()} est vérifié avant tout démarrage. Si le module
 * manque, ce mécanisme se contente de suivre le MSPT (accumulé à la main, sans coût de safepoint) et
 * l'écrit quand même à chaque rotation — un rapport moins riche, jamais un plantage.
 */
public final class ProfilServeurContinu {
    private ProfilServeurContinu() {}

    private static final long ROTATION_NANOS = 60L * 60L * 1_000_000_000L;

    // --- Histogramme MSPT, pas fixe de 0,1 ms jusqu'à 250 ms + un seau "au-delà" — même technique
    // que ProfilClient.histogrammeFrameMs, voir sa Javadoc : zéro allocation par tick.
    private static final double HISTO_PAS_MS = 0.1d;
    private static final int HISTO_BUCKETS = 2500;

    /** Bornes de coût de l'instantané par dimension — voir la Javadoc de classe. */
    private static final int MAX_ENTITES_INSTANTANE = 20_000;
    private static final int MAX_CHUNKS_INSTANTANE = 4_000;

    /** Le fil unique, démon, priorité basse, qui fait tout le travail lourd d'une rotation. */
    private static final ExecutorService ANALYSE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread fil = new Thread(runnable, "Lanterne-Profil-Analyse-Serveur");
        fil.setDaemon(true);
        fil.setPriority(Thread.MIN_PRIORITY);
        return fil;
    });

    private static boolean actif;
    private static Recording recordingEnCours;
    private static boolean jfrDisponible;

    private static long trancheDebutNanos;
    private static long ticksVus;
    private static long lastTickNanos;
    private static long[] histogrammeMspt = new long[HISTO_BUCKETS + 1];
    private static double maxMspt;
    private static long ticksAuDela50ms;

    private record TrancheSnapshot(
            String why, double sessionSec, long ticksVus, long[] histogrammeMspt, double maxMspt,
            long ticksAuDela50ms, Recording recording, boolean jfrDisponible, String instantaneDimensions,
            Path profilsDir) {}

    public static void register() {
        NeoForge.EVENT_BUS.register(ProfilServeurContinu.class);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Config.PROFIL_SERVEUR.get()) {
            Lanterne.LOG.info("[PROFIL] Profil serveur continu désactivé (réglage profil_serveur=false).");
            return;
        }
        demarrer();
    }

    /**
     * Seule rotation qui ATTEND la fin de l'écriture — voir la Javadoc de classe : le process va se
     * terminer, un rapport de fin de session perdu ne se rattrape pas. Le délai (10 s) protège quand
     * même un arrêt normal d'un blocage indéfini si l'analyse traîne anormalement.
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (!actif) {
            return;
        }
        actif = false;
        var future = rotationAsync(event.getServer(), "arrêt du serveur");
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (Exception attenteEchouee) {
            Lanterne.LOG.warn("[PROFIL] Le rapport de fin de session n'a pas pu être confirmé à temps.",
                    attenteEchouee);
        }
    }

    /** Début du tick en cours (Pre) : le MSPT est le TRAVAIL d'un tick, Pre → Post, pas l'intervalle. */
    private static long debutTickNanos;

    @SubscribeEvent
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        debutTickNanos = System.nanoTime();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!actif) {
            return;
        }
        long now = System.nanoTime();
        ticksVus++;
        // Temps de TRAVAIL du tick (Pre → Post). L'ancienne mesure (Post → Post) donnait l'intervalle
        // entre ticks, ~50 ms par construction à 20 TPS, même serveur vide : inutilisable.
        if (debutTickNanos != 0L) {
            double ms = (now - debutTickNanos) / 1e6d;
            int seau = (int) (ms / HISTO_PAS_MS);
            histogrammeMspt[Math.max(0, Math.min(HISTO_BUCKETS, seau))]++;
            maxMspt = Math.max(maxMspt, ms);
            if (ms > 50.0d) {
                ticksAuDela50ms++;
            }
        }
        lastTickNanos = now;

        if (now - trancheDebutNanos >= ROTATION_NANOS) {
            rotationAsync(event.getServer(), "rotation automatique (60 minutes)");
            demarrerTranche();
        }
    }

    private static void demarrer() {
        actif = true;
        demarrerTranche();
        Lanterne.LOG.info("[PROFIL] Profil serveur continu démarré (rotation toutes les 60 minutes).");
    }

    private static void demarrerTranche() {
        trancheDebutNanos = System.nanoTime();
        ticksVus = 0;
        lastTickNanos = 0L;
        histogrammeMspt = new long[HISTO_BUCKETS + 1]; // une seule allocation ici, jamais par tick
        maxMspt = 0;
        ticksAuDela50ms = 0;

        jfrDisponible = MoteurJfr.disponible();
        if (jfrDisponible) {
            try {
                recordingEnCours = MoteurJfr.construire("Lanterne-ProfilServeur");
                recordingEnCours.start();
            } catch (Throwable echec) {
                Lanterne.LOG.warn("[PROFIL] Démarrage JFR serveur impossible, repli sur le MSPT seul.", echec);
                jfrDisponible = false;
                recordingEnCours = null;
            }
        } else {
            recordingEnCours = null;
        }
    }

    /**
     * Force une rotation immédiate — voir {@code report.ProfilServeurCommand} ("/lanterne profil
     * serveur maintenant"). Ne bloque jamais l'appelant (le Server thread, dans le cas d'une commande) :
     * rend {@code true} si une rotation a bien été programmée, le rapport arrive ensuite dans
     * {@code logs/lanterne-profils/} et dans le journal serveur.
     */
    public static synchronized boolean forcerMaintenant(MinecraftServer server) {
        if (!actif) {
            return false;
        }
        rotationAsync(server, "forcé par commande OP");
        demarrerTranche();
        return true;
    }

    /**
     * Fige l'instantané SUR LE FIL APPELANT (Server thread, bon marché — voir la Javadoc de classe),
     * puis délègue l'analyse et l'écriture à {@link #ANALYSE_EXECUTOR}. Rend le futur de cette tâche
     * pour le seul appelant qui a besoin d'attendre : {@link #onServerStopping}.
     */
    private static synchronized java.util.concurrent.Future<?> rotationAsync(MinecraftServer server, String why) {
        double sessionSec = Math.max(0.001d, (System.nanoTime() - trancheDebutNanos) / 1e9d);

        Recording recording = recordingEnCours;
        recordingEnCours = null; // détaché : le fil d'analyse en devient seul propriétaire

        TrancheSnapshot snap = new TrancheSnapshot(
                why, sessionSec, ticksVus, histogrammeMspt, maxMspt, ticksAuDela50ms,
                recording, jfrDisponible,
                instantaneDimensions(server), // touche le monde : DOIT rester sur ce fil, voir la Javadoc
                server.getServerDirectory().resolve("logs").resolve("lanterne-profils"));

        return ANALYSE_EXECUTOR.submit(() -> traiterTranche(snap));
    }

    // ------------------------------------------------------------------------------------------
    // Le travail lourd — TOUJOURS sur ANALYSE_EXECUTOR, jamais sur le Server thread.
    // ------------------------------------------------------------------------------------------

    private static void traiterTranche(TrancheSnapshot snap) {
        Path jfrTemp = null;
        boolean jfrOk = false;
        if (snap.recording() != null) {
            try {
                jfrTemp = Files.createTempFile("lanterne-profil-serveur-", ".jfr");
                jfrOk = MoteurJfr.arreterEtVider(snap.recording(), jfrTemp);
            } catch (IOException echec) {
                Lanterne.LOG.warn("[PROFIL] Fichier temporaire JFR serveur impossible.", echec);
            }
        }
        try {
            ecrireRapport(snap, jfrOk ? jfrTemp : null);
        } catch (Throwable echec) {
            Lanterne.LOG.warn("[PROFIL] Échec de l'écriture du profil serveur.", echec);
        } finally {
            if (jfrTemp != null) {
                try {
                    Files.deleteIfExists(jfrTemp);
                } catch (IOException ignored) {
                    // fichier temporaire : pas grave s'il traîne
                }
            }
        }
    }

    private static Path ecrireRapport(TrancheSnapshot snap, Path jfrFichier) throws IOException {
        // Moyenne du temps de TRAVAIL, lue dans l'histogramme (milieu de chaque seau, max pour le seau
        // « au-delà ») — sessionSec / ticks donnerait l'intervalle entre ticks, pas le MSPT.
        long totalTicks = 0;
        double sommeMs = 0;
        long[] histo = snap.histogrammeMspt();
        for (int i = 0; i < histo.length; i++) {
            totalTicks += histo[i];
            double milieu = i == histo.length - 1 ? snap.maxMspt() : (i + 0.5d) * HISTO_PAS_MS;
            sommeMs += histo[i] * milieu;
        }
        double avgMspt = totalTicks > 0 ? sommeMs / totalTicks : 0;

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Files.createDirectories(snap.profilsDir());
        Path out = snap.profilsDir().resolve("serveur-" + timestamp + ".txt");

        StringBuilder sb = new StringBuilder();
        sb.append("PROFIL LANTERNE — serveur, tranche continue\n").append("=".repeat(60)).append('\n');
        sb.append("Fin de tranche  : ").append(snap.why()).append('\n');
        sb.append(String.format(Locale.ROOT, "Durée observée  : %.1f s (%d tick(s))%n",
                snap.sessionSec(), snap.ticksVus()));
        sb.append('\n');

        sb.append("MSPT\n").append("-".repeat(60)).append('\n');
        sb.append(String.format(Locale.ROOT, "Moyenne : %.2f ms (%.1f ticks/s)%n", avgMspt,
                snap.ticksVus() / snap.sessionSec()));
        if (totalTicks > 0) {
            sb.append(String.format(Locale.ROOT, "p50 : %.2f ms — p95 : %.2f ms — p99 : %.2f ms — max : %.2f ms%n",
                    percentileHisto(snap.histogrammeMspt(), totalTicks, 0.50, snap.maxMspt()),
                    percentileHisto(snap.histogrammeMspt(), totalTicks, 0.95, snap.maxMspt()),
                    percentileHisto(snap.histogrammeMspt(), totalTicks, 0.99, snap.maxMspt()),
                    snap.maxMspt()));
        }
        sb.append(String.format(Locale.ROOT, "Ticks au-delà de 50 ms (le tick vanilla entier) : %d%n",
                snap.ticksAuDela50ms()));
        sb.append("Repère : 20 TPS = 50 ms/tick visé.\n\n");

        if (jfrFichier != null) {
            try {
                sb.append(AnalyseJfr.rapportTexte(jfrFichier, "Server thread", List.of()));
            } catch (IOException echecLecture) {
                sb.append("(lecture du profil JFR impossible : ").append(echecLecture.getMessage()).append(")\n\n");
            }
        } else {
            sb.append("PROFIL JFR INDISPONIBLE — module jdk.jfr absent ou démarrage échoué : MSPT seul.\n\n");
        }

        sb.append(snap.instantaneDimensions());

        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        Lanterne.LOG.info("[PROFIL] Rapport serveur écrit : {}", out);
        return out;
    }

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

    /**
     * Un instantané, PAR DIMENSION, pris à la rotation : chunks chargés, entités, block entities par
     * type, et les dix chunks les plus chargés en entités. Appelée SUR LE SERVER THREAD (voir
     * {@link #rotationAsync}) : rend directement du texte déjà formaté, pour que le fil d'analyse n'ait
     * plus jamais besoin de toucher {@code server}/{@code level} — non thread-safe hors de ce fil.
     *
     * <h2>Pourquoi limité aux chunks autour des joueurs pour les block entities</h2>
     *
     * <p>Aucune liste publique et stable de « tous les chunks chargés » n'est exposée par
     * {@code ServerChunkCache} sans passer par un champ interne de {@code ChunkMap} — un raccourci
     * fragile d'une version de Minecraft à l'autre pour un instantané qui n'a lieu qu'une fois par
     * heure. On retombe donc, comme {@code core.Inventaire} pour un relevé de même nature, sur les
     * chunks effectivement chargés autour de chaque joueur (le même rayon que la distance de
     * simulation) : c'est là que vit la quasi-totalité de ce qui tourne réellement sur un serveur
     * survival, et un serveur sans joueur n'a de toute façon rien à profiler.
     *
     * <h2>Plafonds</h2>
     *
     * <p>{@link #MAX_ENTITES_INSTANTANE} et {@link #MAX_CHUNKS_INSTANTANE} bornent le pire cas : sur un
     * monde immense ou saturé d'entités, l'instantané s'arrête et le dit, plutôt que d'allonger le tick
     * de rotation sans limite.
     */
    private static String instantaneDimensions(MinecraftServer server) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSTANTANÉ PAR DIMENSION (pris à la rotation)\n").append("-".repeat(60)).append('\n');
        for (ServerLevel level : server.getAllLevels()) {
            int chunksCharges = level.getChunkSource().getLoadedChunksCount();
            Map<ChunkPos, Integer> entitesParChunk = new HashMap<>();
            int totalEntites = 0;
            boolean entitesTronquees = false;
            for (var entity : level.getAllEntities()) {
                if (totalEntites >= MAX_ENTITES_INSTANTANE) {
                    entitesTronquees = true;
                    break;
                }
                entitesParChunk.merge(entity.chunkPosition(), 1, Integer::sum);
                totalEntites++;
            }

            Map<String, Integer> blockEntitiesParType = new HashMap<>();
            int totalBlockEntities = 0;
            int simDistance = server.getPlayerList().getSimulationDistance();
            java.util.Set<ChunkPos> vus = new java.util.HashSet<>();
            boolean chunksTronques = false;
            chunks:
            for (var player : level.players()) {
                ChunkPos centre = player.chunkPosition();
                for (int dx = -simDistance; dx <= simDistance; dx++) {
                    for (int dz = -simDistance; dz <= simDistance; dz++) {
                        if (vus.size() >= MAX_CHUNKS_INSTANTANE) {
                            chunksTronques = true;
                            break chunks;
                        }
                        ChunkPos pos = new ChunkPos(centre.x() + dx, centre.z() + dz);
                        if (!vus.add(pos)) {
                            continue;
                        }
                        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
                        if (chunk == null) {
                            continue;
                        }
                        for (BlockEntity be : chunk.getBlockEntities().values()) {
                            String type = be.getType().toString();
                            blockEntitiesParType.merge(type, 1, Integer::sum);
                            totalBlockEntities++;
                        }
                    }
                }
            }

            sb.append(String.format(Locale.ROOT, "  %s : %d chunk(s) chargé(s), %d entité(s)%s, "
                    + "%d block entity/entities (autour des joueurs)%s%n",
                    level.dimension().identifier(), chunksCharges, totalEntites,
                    entitesTronquees ? " (plafond atteint, tronqué)" : "", totalBlockEntities,
                    chunksTronques ? " (plafond de chunks scannés atteint, tronqué)" : ""));

            if (!blockEntitiesParType.isEmpty()) {
                sb.append("    Top block entities par type :\n");
                blockEntitiesParType.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(10)
                        .forEach(e -> sb.append(String.format(Locale.ROOT, "      %5d  %s%n",
                                e.getValue(), e.getKey())));
            }
            if (!entitesParChunk.isEmpty()) {
                sb.append("    Top 10 chunks les plus chargés en entités :\n");
                entitesParChunk.entrySet().stream()
                        .sorted(Map.Entry.<ChunkPos, Integer>comparingByValue().reversed())
                        .limit(10)
                        .forEach(e -> sb.append(String.format(Locale.ROOT, "      %5d entité(s)  chunk (%d, %d)%n",
                                e.getValue(), e.getKey().x(), e.getKey().z())));
            }
        }
        sb.append('\n');
        return sb.toString();
    }
}
