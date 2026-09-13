package fr.clubcitrouille.lanterne.lab;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La pré-génération : le seul levier massif qui tienne sur un cœur unique.
 *
 * <h2>Quinze fois, mesuré deux fois sur la même machine</h2>
 *
 * <p>L'épreuve « essaim » a mesuré, par accident d'abord puis à dessein, les deux façons dont un
 * serveur peut fournir un chunk à un joueur qui avance :
 *
 * <pre>
 * le fabriquer devant lui   :  13 à 17 chunk(s) par seconde
 * le servir depuis le disque : 155 à 209 chunk(s) par seconde
 * </pre>
 *
 * <p>Le second chiffre a d'abord été pris pour un triomphe de parallélisme — c'était une exécution
 * qui rejouait les coordonnées de la précédente. L'erreur corrigée, il reste ce qu'il est : la mesure
 * du coût de <b>servir</b> plutôt que de <b>fabriquer</b>. Quinze fois moins cher.
 *
 * <h2>Pourquoi c'est le bon outil pour une machine à un cœur</h2>
 *
 * <p>Les mods de référence qui attaquent la génération — C2ME, VMP — la <b>répartissent</b> sur
 * plusieurs fils. Sur un hébergement à un cœur, du genre qu'on loue pour jouer entre amis, il n'y a
 * rien à répartir : distribuer du travail sur quinze fils qui n'existent pas ne rend rien, et fait
 * changer de contexte.
 *
 * <p>La pré-génération ne répartit rien. Elle <b>supprime</b> le travail du moment où il gêne pour le
 * faire à un moment où il ne gêne personne. C'est la seule optimisation de génération dont le gain ne
 * dépende pas du nombre de cœurs.
 *
 * <h2>Compatible avec un monde déjà joué, sans rien lui demander</h2>
 *
 * <p>Un monde vanilla existant contient déjà des chunks, et les regénérer serait au mieux du temps
 * perdu, au pire une perte de constructions. On saute donc ce qui existe — mais sans le charger, car
 * charger pour vérifier coûterait plus cher que de générer.
 *
 * <p>Le format des fichiers de région rend la chose triviale, et c'est pour cela qu'on le lit
 * directement plutôt que de passer par le jeu : les <b>quatre premiers kilo-octets</b> d'un
 * {@code r.x.z.mca} sont une table de mille vingt-quatre entrées de quatre octets, une par chunk. Une
 * entrée nulle veut dire « jamais écrit ». Quatre kilo-octets lus disent donc l'état de mille
 * vingt-quatre chunks, sans ouvrir une seule fois le générateur ni le décompresseur.
 *
 * <p>Conséquence heureuse : la <b>reprise est gratuite</b>. Une pré-génération interrompue et relancée
 * saute d'elle-même tout ce qu'elle avait déjà fait, sans fichier d'état à tenir, à corrompre ou à
 * oublier.
 *
 * <h2>Ce qu'elle ne fait pas</h2>
 *
 * <p>Elle ne bloque pas le serveur. Elle travaille dans le temps qu'un tick laisse libre, sous un
 * budget explicite, et s'arrête dès qu'elle l'a dépassé. Des joueurs peuvent jouer pendant ce
 * temps — plus lentement, puisqu'ils partagent le cœur, mais sans déconnexion.
 */
public final class Pregen {
    /** Octets de l'en-tête d'un fichier de région : mille vingt-quatre entrées de quatre. */
    private static final int HEADER_BYTES = 4096;

    /** Chunks par côté d'une région. */
    private static final int REGION_SIDE = 32;

    /**
     * Demandes de chunk en vol simultanément.
     *
     * <p>L'épreuve « essaim » a montré que le débit plafonne bien avant trente-deux : la génération
     * est limitée ailleurs. On n'en demande donc pas davantage, et l'on évite ainsi de retenir en
     * mémoire des centaines de chunks qu'un hébergement modeste ne pourrait pas porter.
     */
    private static final int IN_FLIGHT = 24;

    /** Part d'un tick qu'on s'autorise à consommer, en millisecondes. */
    private static final long BUDGET_MS = 30L;

    private static boolean running;
    private static ServerLevel level;
    private static final List<CompletableFuture<?>> PENDING = new ArrayList<>();
    private static final LongOpenHashSet ALREADY = new LongOpenHashSet();
    private static long wanted;
    private static long issued;
    private static long skipped;
    private static long finished;
    private static int ring;
    private static int radiusChunks;
    private static int cursorInRing;
    private static long startedAt;
    private static long lastReport;

    /**
     * La barre de progression, visible des seuls opérateurs.
     *
     * <h2>Pourquoi elle n'est pas un ornement</h2>
     *
     * <p>Une pré-génération dure des heures. Sans retour visible, celui qui l'a lancée n'a aucun moyen
     * de savoir si elle avance, si elle a fini, ou si elle s'est arrêtée sur une erreur. Le journal le
     * dit — encore faut-il y avoir accès, ce qui n'est pas le cas sur la plupart des hébergements
     * partagés, et encore faut-il y penser.
     *
     * <p>Elle affiche aussi le <b>débit</b> et le <b>temps restant estimé</b>, parce que « 34 % » ne
     * répond pas à la seule question qui intéresse vraiment : est-ce que je peux aller me coucher, ou
     * est-ce que ça va finir dans dix minutes ?
     *
     * <h2>Réservée à ceux qui peuvent la commander</h2>
     *
     * <p>Un joueur ordinaire n'a pas à subir une barre d'administration en travers de son écran
     * pendant qu'il joue. Seuls les opérateurs la voient, et la liste est révisée à chaque rapport —
     * un joueur promu en cours de route la reçoit, un joueur parti ne la garde pas.
     */
    private static ServerBossEvent bar;

    private Pregen() {}

    public static boolean running() {
        return running;
    }

    /**
     * Ouvre une pré-génération autour de l'origine du monde.
     *
     * @param radius rayon en chunks
     */
    public static void begin(ServerLevel target, int radius) {
        level = target;
        radiusChunks = Math.max(1, radius);
        running = true;
        PENDING.clear();
        issued = 0L;
        skipped = 0L;
        finished = 0L;
        ring = 0;
        cursorInRing = 0;
        startedAt = System.nanoTime();
        lastReport = startedAt;
        lastLogged = startedAt;
        hideBar();
        int side = radiusChunks * 2 + 1;
        wanted = (long) side * side;

        ALREADY.clear();
        long known = readExistingChunks(target);
        Lanterne.LOG.info("[PRÉGÉN] {} chunk(s) visés autour de l'origine (rayon {}), dont {} déjà "
                + "écrits sur disque et qui seront sautés. Budget : {} ms par tick.",
                wanted, radiusChunks, known, BUDGET_MS);
    }

    /**
     * Relève, depuis les en-têtes des fichiers de région, tout ce qui existe déjà.
     *
     * <p>Une lecture de quatre kilo-octets par région, soit par tranche de mille vingt-quatre chunks.
     * Sur un monde de cent mille chunks, cela fait une centaine de lectures de quatre kilo-octets —
     * quelques dizaines de millisecondes, une fois.
     */
    private static long readExistingChunks(ServerLevel target) {
        Path regions = target.getServer().getWorldPath(LevelResource.ROOT)
                .resolve(target.dimension().identifier().getPath().isEmpty()
                        ? "region" : dimensionFolder(target) + "/region");
        if (!Files.isDirectory(regions)) {
            Lanterne.LOG.info("[PRÉGÉN] aucun dossier de régions à {} — le monde est neuf.", regions);
            return 0L;
        }
        long found = 0L;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(regions, "r.*.mca")) {
            for (Path file : files) {
                found += readOneRegion(file);
            }
        } catch (IOException unreadable) {
            Lanterne.LOG.warn("[PRÉGÉN] lecture des régions impossible ({}) — on générera peut-être "
                    + "des chunks qui existaient déjà, ce qui est une perte de temps et non une perte "
                    + "de données.", unreadable.toString());
        }
        return found;
    }

    /** Le sous-dossier d'une dimension autre que le monde principal. */
    private static String dimensionFolder(ServerLevel target) {
        var id = target.dimension().identifier();
        if (id.equals(net.minecraft.world.level.Level.NETHER.identifier())) {
            return "DIM-1";
        }
        if (id.equals(net.minecraft.world.level.Level.END.identifier())) {
            return "DIM1";
        }
        return "dimensions/" + id.getNamespace() + "/" + id.getPath();
    }

    private static long readOneRegion(Path file) {
        String name = file.getFileName().toString();
        String[] parts = name.split("\\.");
        if (parts.length != 4) {
            return 0L;
        }
        int regionX;
        int regionZ;
        try {
            regionX = Integer.parseInt(parts[1]);
            regionZ = Integer.parseInt(parts[2]);
        } catch (NumberFormatException malformed) {
            return 0L;
        }

        byte[] header = new byte[HEADER_BYTES];
        try (RandomAccessFile handle = new RandomAccessFile(file.toFile(), "r")) {
            if (handle.length() < HEADER_BYTES) {
                return 0L;
            }
            handle.readFully(header);
        } catch (IOException unreadable) {
            return 0L;
        }

        long found = 0L;
        for (int slot = 0; slot < REGION_SIDE * REGION_SIDE; slot++) {
            int offset = slot * 4;
            // Trois octets d'adresse, un de longueur. Tout à zéro : le chunk n'a jamais été écrit.
            int packed = ((header[offset] & 0xFF) << 16)
                    | ((header[offset + 1] & 0xFF) << 8)
                    | (header[offset + 2] & 0xFF);
            int sectors = header[offset + 3] & 0xFF;
            if (packed != 0 && sectors != 0) {
                int chunkX = regionX * REGION_SIDE + (slot & 31);
                int chunkZ = regionZ * REGION_SIDE + (slot >> 5);
                ALREADY.add(ChunkPos.pack(chunkX, chunkZ));
                found++;
            }
        }
        return found;
    }

    /**
     * Avance d'un tick, sans dépasser le budget.
     *
     * <p>Le budget est relu à chaque chunk soumis et non une fois pour toutes : une soumission peut
     * déclencher une génération synchrone si le chunk est presque prêt, et dépasser largement
     * l'estimation qu'on aurait faite en début de tick.
     */
    public static void tick(MinecraftServer server) {
        if (!running) {
            return;
        }
        long deadline = System.nanoTime() + BUDGET_MS * 1_000_000L;

        PENDING.removeIf(pending -> {
            if (pending.isDone()) {
                finished++;
                return true;
            }
            return false;
        });

        while (PENDING.size() < IN_FLIGHT && System.nanoTime() < deadline) {
            ChunkPos next = nextSpot();
            if (next == null) {
                if (PENDING.isEmpty()) {
                    conclude();
                }
                return;
            }
            if (ALREADY.contains(ChunkPos.pack(next.x(), next.z()))) {
                skipped++;
                continue;
            }
            PENDING.add(level.getChunkSource().getChunkFuture(next.x(), next.z(), ChunkStatus.FULL, true));
            issued++;
        }

        report();
    }

    /**
     * Le prochain chunk de la spirale, ou {@code null} quand le carré est couvert.
     *
     * <p>En anneaux concentriques depuis l'origine : ce qui est proche du point d'apparition est
     * généré d'abord. Si l'on interrompt la pré-génération à mi-chemin, ce qui a été fait est ce dont
     * les joueurs se serviront en premier.
     */
    private static ChunkPos nextSpot() {
        while (ring <= radiusChunks) {
            int side = ring * 2 + 1;
            int perimeter = ring == 0 ? 1 : side * 4 - 4;
            if (cursorInRing >= perimeter) {
                ring++;
                cursorInRing = 0;
                continue;
            }
            int index = cursorInRing++;
            if (ring == 0) {
                return new ChunkPos(0, 0);
            }
            int edge = side - 1;
            int x;
            int z;
            if (index < edge) {
                x = -ring + index;
                z = -ring;
            } else if (index < edge * 2) {
                x = ring;
                z = -ring + (index - edge);
            } else if (index < edge * 3) {
                x = ring - (index - edge * 2);
                z = ring;
            } else {
                x = -ring;
                z = ring - (index - edge * 3);
            }
            return new ChunkPos(x, z);
        }
        return null;
    }

    private static void report() {
        long now = System.nanoTime();
        if (now - lastReport < 1_000_000_000L) {
            return;
        }
        lastReport = now;
        double seconds = (now - startedAt) / 1.0E9d;
        double rate = finished / Math.max(0.001d, seconds);
        long seen = issued + skipped;
        float share = (float) Math.min(1d, seen / (double) Math.max(1L, wanted));

        showBar(share, seen, rate);

        // Le journal reste plus espacé que la barre : cinq secondes suffisent à suivre, et une ligne
        // par seconde sur une pré-génération de plusieurs heures rendrait le fichier illisible.
        if (now - lastLogged < 5_000_000_000L) {
            return;
        }
        lastLogged = now;
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[PRÉGÉN] %d / %d (%.1f %%) · %d généré(s), %d sauté(s) · %.1f chunk(s) par seconde",
                seen, wanted, share * 100d, finished, skipped, rate));
    }

    private static long lastLogged;

    /**
     * Met la barre à jour et la montre aux opérateurs connectés.
     *
     * <p>Le temps restant se calcule sur les chunks <em>à générer</em>, et non sur les chunks à
     * parcourir : sauter un chunk déjà écrit coûte une recherche dans un ensemble d'entiers, et le
     * compter comme du travail donnerait une estimation ridiculement pessimiste sur un monde déjà
     * largement exploré.
     */
    private static void showBar(float share, long seen, double rate) {
        if (level == null) {
            return;
        }
        if (bar == null) {
            bar = new ServerBossEvent(java.util.UUID.randomUUID(),
                    Component.literal("Pré-génération"),
                    BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
        }

        long remainingToMake = Math.max(0L, wanted - seen);
        // Sur ce qui reste, la proportion de chunks neufs devrait ressembler à celle déjà observée.
        double madeShare = seen == 0L ? 1d : finished / (double) Math.max(1L, seen);
        double secondsLeft = rate <= 0.01d ? -1d : remainingToMake * madeShare / rate;

        bar.setName(Component.literal(String.format(Locale.ROOT,
                "Pré-génération  %.1f %%  ·  %d / %d chunks  ·  %.1f/s  ·  %s",
                share * 100d, seen, wanted, rate, humanTime(secondsLeft)))
                .withStyle(ChatFormatting.AQUA));
        bar.setProgress(share);

        // La liste est révisée à chaque passage : un opérateur promu en cours de route la reçoit, un
        // joueur déconnecté cesse d'y figurer, et un joueur dépromu ne la voit plus.
        for (ServerPlayer viewer : level.getServer().getPlayerList().getPlayers()) {
            boolean allowed = level.getServer().getPlayerList().isOp(viewer.nameAndId());
            if (allowed) {
                bar.addPlayer(viewer);
            } else {
                bar.removePlayer(viewer);
            }
        }
    }

    private static String humanTime(double seconds) {
        if (seconds < 0d) {
            return "estimation en cours";
        }
        long total = (long) seconds;
        if (total < 60L) {
            return total + " s restantes";
        }
        if (total < 3600L) {
            return (total / 60L) + " min restantes";
        }
        return String.format(Locale.ROOT, "%d h %02d restantes", total / 3600L, (total % 3600L) / 60L);
    }

    private static void hideBar() {
        if (bar != null) {
            bar.removeAllPlayers();
            bar.setVisible(false);
            bar = null;
        }
    }

    private static void conclude() {
        running = false;
        hideBar();
        double seconds = (System.nanoTime() - startedAt) / 1.0E9d;
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[PRÉGÉN] terminé — %d chunk(s) générés et %d sautés en %.1f s, soit %.1f par seconde. "
                + "Ces chunks seront désormais servis depuis le disque, ce qui coûte une quinzaine de "
                + "fois moins que de les fabriquer devant le joueur.",
                finished, skipped, seconds, finished / Math.max(0.001d, seconds)));
    }

    /** Arrête proprement, en laissant aboutir ce qui est en vol. */
    public static void halt() {
        if (running) {
            running = false;
            hideBar();
            Lanterne.LOG.info("[PRÉGÉN] interrompu — {} chunk(s) générés, {} sautés. La reprise est "
                    + "gratuite : relancer sautera d'emblée tout ce qui vient d'être écrit.",
                    finished, skipped);
        }
    }
}
