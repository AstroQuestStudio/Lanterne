package fr.clubcitrouille.lanterne.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * <h2>Réglage {@code profil_serveur}, vrai par défaut</h2>
 *
 * <p>Contrairement à {@code lab.RadiographieServeur} (manuel, réservé aux diagnostics ponctuels), ce
 * mécanisme est actif par défaut : c'est la demande explicite du propriétaire — un flux continu de
 * profils serveur, sans avoir à se souvenir de lancer une commande. {@link Config#PROFIL_SERVEUR}
 * permet de l'éteindre sur un hébergement où même le coût annoncé (1-2 %) ne serait pas souhaité.
 *
 * <h2>Repli si {@code jdk.jfr} est absent</h2>
 *
 * <p>Comme côté client : {@link MoteurJfr#disponible()} est vérifié avant tout démarrage. Si le module
 * manque, ce mécanisme se contente de suivre le MSPT (accumulé à la main depuis
 * {@link ServerTickEvent.Post}, sans coût de safepoint) et l'écrit quand même à chaque rotation — un
 * rapport moins riche, jamais un plantage.
 */
public final class ProfilServeurContinu {
    private ProfilServeurContinu() {}

    private static final long ROTATION_NANOS = 60L * 60L * 1_000_000_000L;

    private static boolean actif;
    private static Recording recordingEnCours;
    private static boolean jfrDisponible;

    private static long trancheDebutNanos;
    private static long ticksVus;
    private static long lastTickNanos;
    private static final List<Double> msptTriables = new ArrayList<>();
    private static long ticksAuDela50ms;

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

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (!actif) {
            return;
        }
        rotation(event.getServer(), "arrêt du serveur");
        actif = false;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!actif) {
            return;
        }
        long now = System.nanoTime();
        ticksVus++;
        if (lastTickNanos != 0L) {
            double ms = (now - lastTickNanos) / 1e6d;
            msptTriables.add(ms);
            if (ms > 50.0d) {
                ticksAuDela50ms++;
            }
        }
        lastTickNanos = now;

        if (now - trancheDebutNanos >= ROTATION_NANOS) {
            rotation(event.getServer(), "rotation automatique (60 minutes)");
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
        msptTriables.clear();
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

    /** Force une rotation immédiate — voir {@code report.ProfilServeurCommand} ("/lanterne profil serveur maintenant"). */
    public static synchronized Path forcerMaintenant(MinecraftServer server) {
        if (!actif) {
            return null;
        }
        Path ecrit = rotation(server, "forcé par commande OP");
        demarrerTranche();
        return ecrit;
    }

    private static synchronized Path rotation(MinecraftServer server, String why) {
        Path jfrTemp = null;
        boolean jfrOk = false;
        if (recordingEnCours != null) {
            try {
                jfrTemp = Files.createTempFile("lanterne-profil-serveur-", ".jfr");
                jfrOk = MoteurJfr.arreterEtVider(recordingEnCours, jfrTemp);
            } catch (IOException echec) {
                Lanterne.LOG.warn("[PROFIL] Fichier temporaire JFR serveur impossible.", echec);
            }
            recordingEnCours = null;
        }
        try {
            return ecrireRapport(server, why, jfrOk ? jfrTemp : null);
        } catch (Throwable echec) {
            Lanterne.LOG.warn("[PROFIL] Échec de l'écriture du profil serveur.", echec);
            return null;
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

    private static Path ecrireRapport(MinecraftServer server, String why, Path jfrFichier) throws IOException {
        double sessionSec = Math.max(0.001d, (System.nanoTime() - trancheDebutNanos) / 1e9d);
        double avgMspt = ticksVus > 0 ? (sessionSec * 1000.0d) / ticksVus : 0;

        List<Double> triees = new ArrayList<>(msptTriables);
        triees.sort(Comparator.naturalOrder());

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path dir = server.getServerDirectory().resolve("logs").resolve("lanterne-profils");
        Files.createDirectories(dir);
        Path out = dir.resolve("serveur-" + timestamp + ".txt");

        StringBuilder sb = new StringBuilder();
        sb.append("PROFIL LANTERNE — serveur, tranche continue\n").append("=".repeat(60)).append('\n');
        sb.append("Fin de tranche  : ").append(why).append('\n');
        sb.append(String.format(Locale.ROOT, "Durée observée  : %.1f s (%d tick(s))%n", sessionSec, ticksVus));
        sb.append('\n');

        sb.append("MSPT\n").append("-".repeat(60)).append('\n');
        sb.append(String.format(Locale.ROOT, "Moyenne : %.2f ms (%.1f ticks/s)%n", avgMspt,
                ticksVus / sessionSec));
        if (!triees.isEmpty()) {
            sb.append(String.format(Locale.ROOT, "p50 : %.2f ms — p95 : %.2f ms — p99 : %.2f ms — max : %.2f ms%n",
                    percentile(triees, 0.50), percentile(triees, 0.95), percentile(triees, 0.99),
                    triees.get(triees.size() - 1)));
        }
        sb.append(String.format(Locale.ROOT, "Ticks au-delà de 50 ms (le tick vanilla entier) : %d%n",
                ticksAuDela50ms));
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

        sb.append(instantaneDimensions(server));

        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        Lanterne.LOG.info("[PROFIL] Rapport serveur écrit : {}", out);
        return out;
    }

    /**
     * Un instantané, PAR DIMENSION, pris à la rotation : chunks chargés, entités, block entities par
     * type, et les dix chunks les plus chargés en entités.
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
     */
    private static String instantaneDimensions(MinecraftServer server) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSTANTANÉ PAR DIMENSION (pris à la rotation)\n").append("-".repeat(60)).append('\n');
        for (ServerLevel level : server.getAllLevels()) {
            int chunksCharges = level.getChunkSource().getLoadedChunksCount();
            Map<ChunkPos, Integer> entitesParChunk = new HashMap<>();
            int totalEntites = 0;
            for (var entity : level.getAllEntities()) {
                entitesParChunk.merge(entity.chunkPosition(), 1, Integer::sum);
                totalEntites++;
            }

            Map<String, Integer> blockEntitiesParType = new HashMap<>();
            int totalBlockEntities = 0;
            int simDistance = server.getPlayerList().getSimulationDistance();
            java.util.Set<ChunkPos> vus = new java.util.HashSet<>();
            for (var player : level.players()) {
                ChunkPos centre = player.chunkPosition();
                for (int dx = -simDistance; dx <= simDistance; dx++) {
                    for (int dz = -simDistance; dz <= simDistance; dz++) {
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

            sb.append(String.format(Locale.ROOT, "  %s : %d chunk(s) chargé(s), %d entité(s), "
                    + "%d block entity/entities (autour des joueurs)%n",
                    level.dimension().identifier(), chunksCharges, totalEntites, totalBlockEntities));

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

    private static double percentile(List<Double> triees, double p) {
        if (triees.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p * triees.size()) - 1;
        index = Math.max(0, Math.min(triees.size() - 1, index));
        return triees.get(index);
    }
}
