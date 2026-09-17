package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Emballage;
import fr.clubcitrouille.lanterne.core.Souvenir;
import fr.clubcitrouille.lanterne.core.network.SouvenirManifest;

/**
 * Le rappel : la décision de {@code Souvenir} est-elle correcte, et coûte-t-elle vraiment moins que
 * ce qu'elle évite ?
 *
 * <h2>Ce que ce banc peut mesurer, et ce qu'il ne peut pas</h2>
 *
 * <p>{@code Souvenir} est un protocole en deux moitiés : la décision côté <b>serveur</b>
 * ({@code Souvenir.claimed}/{@code revisionFor}, consultées par {@code SouvenirSendMixin}), et la
 * persistance côté <b>client</b> ({@code SouvenirVault}, rejouée par {@code SouvenirClient}). Ce
 * dépôt n'a pas de banc qui fait tourner un vrai client à côté d'un serveur — tous les bancs
 * existants ({@code Volley}, {@code Flow}, {@code Restitution}, {@code Coince}) tournent sur
 * {@code ./gradlew runServer}, un serveur dédié sans aucun client attaché, et {@code SouvenirVault}
 * vit dans le paquetage {@code client}, injoignable depuis ce fil. Ce banc mesure donc <b>seulement
 * la moitié serveur</b> — la seule qu'il puisse honnêtement mesurer — et le dit sans détour plutôt
 * que de prétendre avoir vérifié le protocole entier. La moitié client (le vault, le rejeu) reste à
 * vérifier par un test en vraies conditions, comme celui qui a déjà validé le correctif kqueue ce
 * soir sur le serveur réel.
 *
 * <h2>Ce qui compte vraiment côté serveur</h2>
 *
 * <p>Quand la révision annoncée correspond, {@code SouvenirSendMixin} coupe {@code sendChunk} avant
 * qu'il ne touche à quoi que ce soit — en particulier avant {@code EmballageEnvoiMixin}, qui appelle
 * {@code Emballage.paquet(...)}, la reconstruction du paquet dont la propre Javadoc d'{@code Emballage}
 * chiffre le coût à environ deux millisecondes par chunk (43% du coût d'une arrivée à distance de vue
 * dix). La question qui décide si {@code Souvenir} vaut son prix n'est donc pas « la décision est-elle
 * rapide ? » — un accès à deux tables de hachage l'est presque toujours — mais « la décision coûte-t-
 * elle vraiment moins que ce qu'elle évite, mesuré ici même, sur les mêmes chunks ? »
 *
 * <h2>La conformité qui compte le plus : ne jamais matcher à tort</h2>
 *
 * <p>Un faux positif ici — le serveur croit à tort que le client a la bonne révision — ferait
 * disparaître silencieusement un changement réel aux yeux d'un joueur. C'est vérifié en forçant un
 * changement ({@code Souvenir.bump}) sur la moitié de la grille après avoir enregistré une manifeste
 * qui prétend tout connaître, puis en s'assurant qu'aucune des positions modifiées ne matche plus —
 * jamais une seule, sur l'ensemble de la grille.
 */
public final class Rappel {
    private static final int SIDE = 12;
    private static final int GRID_CHUNKS = SIDE * SIDE;
    private static final int ORIGIN_CX = -3300;
    private static final int ORIGIN_CZ = -3300;

    private static final UUID FAKE_PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000b0b");

    private static final int READINGS = 8;
    private static final int WARMUP = 2;

    private enum Step { OFF, SEEDING, SETTLE, BUILD_MANIFEST, MEASURE_DECISION, MEASURE_PACKET, BUMP_HALF, VERIFY_STALE, DONE }

    /**
     * Ticks d'attente après le chargement, avant de construire la manifeste.
     *
     * <h2>Ce que le premier relevé de ce banc a découvert</h2>
     *
     * <p>Sans cette attente, la révision de plusieurs dizaines de chunks — jusqu'à 52 sur 144 —
     * dérivait pendant la seule mesure de décision, avant même le {@code bump} volontaire de ce
     * banc. La cause la plus probable, jamais confirmée autrement qu'en la contournant : la
     * propagation de lumière entre les 144 chunks fraîchement chargés d'un coup continue plusieurs
     * ticks après leur passage à {@code FULL}, et {@code Emballage} documente déjà que
     * {@code LayerLightSectionStorage.swapSectionMap} appelle {@code markUnsaved()} — donc
     * {@code Souvenir.bump} — sans condition, à chaque section touchée. Un client qui se reconnecte
     * juste après un redémarrage, sur un monde qui vient d'être rechargé, pourrait donc voir un taux
     * de correspondance plus bas qu'en régime stable — pas une erreur, un raté qui coûte un envoi
     * complet, mais un comportement réel qui vaut d'être écrit plutôt que découvert en production.
     */
    private static final int SETTLE_TICKS = 60;
    private static int settleLeft;

    private static Step step = Step.OFF;
    private static MinecraftServer host;
    private static net.minecraft.resources.Identifier dimensionId;
    private static final List<ChunkPos> positions = new ArrayList<>();

    private static int issued;
    private static int done;
    private static final List<java.util.concurrent.CompletableFuture<?>> IN_FLIGHT = new ArrayList<>();

    private static final long[] decisionNanos = new long[READINGS];
    private static final long[] packetNanos = new long[READINGS];
    private static int reading;

    private static boolean falseMatch;
    private static boolean falseMiss;

    private Rappel() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        ServerLevel level = server.overworld();
        dimensionId = level.dimension().identifier();
        Souvenir.reset();
        positions.clear();
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                positions.add(new ChunkPos(ORIGIN_CX + x, ORIGIN_CZ + z));
            }
        }
        // Forcés : sans joueur ni ticket, rien ne garde ces chunks résidents entre la fin de SEEDING
        // et MEASURE_PACKET, quelques ticks plus tard — le premier relevé de ce banc l'a appris en
        // trouvant zéro chunk réellement en mémoire à ce stade.
        for (ChunkPos pos : positions) {
            level.setChunkForced(pos.x(), pos.z(), true);
        }
        issued = 0;
        done = 0;
        IN_FLIGHT.clear();
        reading = 0;
        falseMatch = false;
        falseMiss = false;
        step = Step.SEEDING;
        Lanterne.LOG.info("[RAPPEL] Épreuve lancée — {} chunks en ({},{}). Mesure la seule moitié "
                + "serveur du protocole Souvenir ; voir la Javadoc de classe pour ce qui ne peut pas "
                + "être vérifié ici (le vault client, le rejeu).", GRID_CHUNKS, ORIGIN_CX, ORIGIN_CZ);
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();
        switch (step) {
            case SEEDING -> {
                if (pumpLoad(server)) {
                    settleLeft = SETTLE_TICKS;
                    step = Step.SETTLE;
                }
            }
            case SETTLE -> {
                if (--settleLeft <= 0) {
                    // Une révision par chunk seulement une fois la lumière posée — voir Javadoc de
                    // SETTLE_TICKS. C'est bien le premier appel de revisionFor sur ces positions :
                    // il fixe la révision « déjà envoyée » que le reste du banc considère comme
                    // acquise.
                    for (ChunkPos pos : positions) {
                        Souvenir.revisionFor(level, pos);
                    }
                    step = Step.BUILD_MANIFEST;
                }
            }
            case BUILD_MANIFEST -> {
                List<SouvenirManifest.Entry> entries = new ArrayList<>(positions.size());
                for (ChunkPos pos : positions) {
                    long revision = Souvenir.revisionFor(level, pos);
                    entries.add(new SouvenirManifest.Entry(pos.pack(), revision));
                }
                Souvenir.recordManifest(FAKE_PLAYER, dimensionId, entries);
                Lanterne.LOG.info("[RAPPEL] Manifeste construite — {} entrée(s), toutes à jour.",
                        entries.size());
                step = Step.MEASURE_DECISION;
            }
            case MEASURE_DECISION -> {
                long start = System.nanoTime();
                int matched = 0;
                for (ChunkPos pos : positions) {
                    long claimed = Souvenir.claimed(FAKE_PLAYER, dimensionId, pos.pack());
                    long current = Souvenir.revisionFor(level, pos);
                    if (claimed != 0L && claimed == current) {
                        matched++;
                    }
                }
                long elapsed = System.nanoTime() - start;
                if (matched != positions.size()) {
                    falseMiss = true;
                    Lanterne.LOG.error("[RAPPEL] {} chunk(s) sur {} ne matchent pas alors que la "
                            + "manifeste les annonce à jour — défaut de conformité.",
                            positions.size() - matched, positions.size());
                }
                if (reading >= WARMUP) {
                    decisionNanos[reading - WARMUP] = elapsed;
                }
                reading++;
                if (reading >= READINGS) {
                    reading = 0;
                    step = Step.MEASURE_PACKET;
                }
            }
            case MEASURE_PACKET -> {
                long start = System.nanoTime();
                int built = 0;
                for (ChunkPos pos : positions) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
                    if (chunk != null) {
                        Emballage.paquet(chunk, level.getLightEngine(), null, null);
                        built++;
                    }
                }
                long elapsed = System.nanoTime() - start;
                if (reading >= WARMUP) {
                    packetNanos[reading - WARMUP] = elapsed;
                }
                reading++;
                if (reading == 1) {
                    Lanterne.LOG.info("[RAPPEL] {} paquet(s) réellement construits au premier relevé "
                            + "(référence de conformité de comptage).", built);
                }
                if (reading >= READINGS) {
                    step = Step.BUMP_HALF;
                }
            }
            case BUMP_HALF -> {
                for (int i = 0; i < positions.size(); i++) {
                    if ((i & 1) == 0) {
                        Souvenir.bump(level, positions.get(i));
                    }
                }
                Lanterne.LOG.info("[RAPPEL] {} chunk(s) modifiés (bump) sur {} — vérification qu'aucun "
                        + "ne matche plus à tort.", (positions.size() + 1) / 2, positions.size());
                step = Step.VERIFY_STALE;
            }
            case VERIFY_STALE -> {
                for (int i = 0; i < positions.size(); i++) {
                    ChunkPos pos = positions.get(i);
                    long claimed = Souvenir.claimed(FAKE_PLAYER, dimensionId, pos.pack());
                    long current = Souvenir.revisionFor(level, pos);
                    boolean bumped = (i & 1) == 0;
                    boolean matches = claimed != 0L && claimed == current;
                    if (bumped && matches) {
                        falseMatch = true;
                    }
                    if (!bumped && !matches) {
                        falseMiss = true;
                    }
                }
                step = Step.DONE;
                for (ChunkPos pos : positions) {
                    level.setChunkForced(pos.x(), pos.z(), false);
                }
                report();
                if (host != null) {
                    host.halt(false);
                }
            }
            default -> { }
        }
    }

    /** Charge la grille pour de vrai, avec plusieurs demandes en vol — même geste que {@code Flow}. */
    private static boolean pumpLoad(MinecraftServer server) {
        ServerLevel level = server.overworld();
        var source = level.getChunkSource();
        while (IN_FLIGHT.size() < 16 && issued < positions.size()) {
            ChunkPos pos = positions.get(issued++);
            IN_FLIGHT.add(source.getChunkFuture(pos.x(), pos.z(),
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true));
        }
        IN_FLIGHT.removeIf(f -> {
            if (f.isDone()) {
                done++;
                return true;
            }
            return false;
        });
        return done >= positions.size();
    }

    private static void report() {
        Lanterne.LOG.info("[RAPPEL] ── Verdict de CONFORMITÉ (avant toute vitesse) ──");
        if (falseMatch) {
            Lanterne.LOG.error("[RAPPEL] ÉCHEC DE CONFORMITÉ : au moins un chunk modifié matche encore "
                    + "après bump — un client pourrait recevoir un décor périmé. Le module doit être "
                    + "retiré tant que ce n'est pas expliqué.");
        } else if (falseMiss) {
            Lanterne.LOG.error("[RAPPEL] ÉCHEC DE PROTOCOLE : un chunk non modifié a cessé de matcher, "
                    + "ou la manifeste initiale ne matchait pas tout — le banc lui-même est en cause, "
                    + "pas nécessairement Souvenir. À corriger avant de refaire confiance au verdict.");
        } else {
            Lanterne.LOG.info("[RAPPEL] CONFORME : tous les chunks à jour matchent, tous les chunks "
                    + "modifiés cessent de matcher — jamais un faux positif, sur {} chunks.",
                    GRID_CHUNKS);
        }

        double decisionMedian = median(decisionNanos);
        double packetMedian = median(packetNanos);
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RAPPEL] Décision Souvenir (claimed+revisionFor, %d chunks) : médiane %.1f µs, "
                        + "soit %.3f µs/chunk.",
                GRID_CHUNKS, decisionMedian / 1e3, decisionMedian / 1e3 / GRID_CHUNKS));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RAPPEL] Construction Emballage.paquet (%d chunks) : médiane %.2f ms, soit %.3f "
                        + "ms/chunk — c'est ce que la décision évite quand elle matche.",
                GRID_CHUNKS, packetMedian / 1e6, packetMedian / 1e6 / GRID_CHUNKS));

        if (decisionMedian <= 0d || packetMedian <= 0d) {
            Lanterne.LOG.warn("[RAPPEL] VERDICT : au moins une médiane nulle ou négative — signal sous "
                    + "le bruit, aucun ratio publié.");
            return;
        }
        double ratio = packetMedian / decisionMedian;
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RAPPEL] VERDICT : la décision coûte %.0f fois moins que ce qu'elle évite quand elle "
                        + "matche (%.3f µs contre %.3f ms, par chunk). Le coût propre de Souvenir est "
                        + "négligeable ; le gain réel dépend entièrement du taux de correspondance "
                        + "obtenu en vraies conditions — non mesurable dans ce banc, voir la Javadoc "
                        + "de classe.",
                ratio, decisionMedian / 1e3 / GRID_CHUNKS, packetMedian / 1e6 / GRID_CHUNKS));
    }

    private static double median(long[] values) {
        long[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0 ? (copy[middle - 1] + copy[middle]) / 2d : copy[middle];
    }
}
