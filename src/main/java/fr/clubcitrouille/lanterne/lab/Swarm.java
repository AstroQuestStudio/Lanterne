package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le débit de génération selon le nombre de chunks demandés à la fois.
 *
 * <h2>Seize fils qui dorment pendant qu'on attend des chunks</h2>
 *
 * <p>Le profileur, une fois ouvert à tous les fils, a rendu sur la génération de terrain un relevé
 * que personne n'attendait :
 *
 * <pre>
 * Worker-Main-1  … Worker-Main-15   →   actifs entre 2,5 et 4,6 %
 * Unsafe.unpark                     →   37,2 %
 * ForkJoinPool.deactivate           →    4,3 %
 * Thread.setNativeName              →    6,8 %
 * </pre>
 *
 * <p>Seize fils de travail, et pas un qui dépasse cinq pour cent d'activité. Le poste principal n'est
 * pas la génération : c'est <b>le réveil mutuel des fils</b>. Et {@code Thread.setNativeName}, qui
 * n'apparaît nulle part dans le code du jeu, trahit un bassin qui crée et détruit ses ouvriers en
 * boucle — le comportement d'un {@code ForkJoinPool} à qui l'on ne donne jamais assez de travail pour
 * qu'il vaille la peine de rester éveillé.
 *
 * <p>La cause est dans la façon dont on demande. Le banc de carrière, comme le jeu lorsqu'un joueur
 * avance, réclame <b>un chunk, puis attend qu'il soit prêt, puis en réclame un autre</b> :
 *
 * <pre>
 * chunkSource.getChunk(x, z, ChunkStatus.FULL, true);   // bloquant, un par tick
 * </pre>
 *
 * <p>Un chunk traverse douze étapes dont plusieurs dépendent de ses voisins. Demandé seul, il ne peut
 * occuper qu'un fil à la fois, et les quinze autres attendent. Aucun mod, aucune réécriture du
 * générateur ne peut corriger cela : <b>le travail n'est pas là</b>.
 *
 * <h2>Ce que cette épreuve mesure, et pourquoi elle décide de tout</h2>
 *
 * <p>Elle génère le même nombre de chunks vierges plusieurs fois de suite, en ne changeant qu'une
 * chose : combien de demandes sont <b>en vol simultanément</b>. Une par une, huit par huit, puis
 * trente-deux, puis cent vingt-huit.
 *
 * <p>Si le débit reste plat, le générateur est le goulot et il faudra le réécrire — un chantier de
 * plusieurs milliers de lignes, comme celui de C2ME. S'il monte, alors l'essentiel de ce qu'un joueur
 * attend en explorant est <b>de l'attente pure</b>, et un outil de pré-génération qui sature le
 * bassin rendra ce temps sans toucher à une seule ligne du générateur.
 *
 * <p>C'est la mesure qui doit précéder l'écriture, et ce projet a déjà payé huit fois le prix de
 * l'ordre inverse.
 *
 * <h2>Des chunks réellement vierges</h2>
 *
 * <p>Chaque vague travaille à quatre mille blocs de la précédente, et la première à cent mille blocs
 * de l'origine. Générer deux fois la même région mesurerait un chargement depuis le disque — c'est
 * une autre grandeur, et bien plus rapide, ce qui donnerait l'illusion d'un gain.
 */
public final class Swarm {
    /** Nombres de demandes simultanées éprouvés, dans l'ordre. */
    private static final int[] WIDTHS = {1, 8, 32, 128, 32, 128, 512};

    /**
     * Les vagues où l'on pompe le tick au lieu de le laisser dormir.
     *
     * <h2>Quatre-vingt-six pour cent de sommeil pendant qu'on attend des chunks</h2>
     *
     * <p>Le profil de la première série a rendu un relevé qu'il a fallu relire deux fois :
     *
     * <pre>
     * fil du serveur   :  86 % du temps dormi
     * seize workers    :   4 % d'activité chacun
     * travail restant  :  Unsafe.unpark  34,8 %
     * </pre>
     *
     * <p><b>Personne ne travaille.</b> Ni le serveur, ni les ouvriers. Les vingt-cinq secondes qu'il
     * faut pour deux cent cinquante-six chunks ne sont pas du calcul : c'est de la coordination, et
     * l'essentiel de cette coordination consiste à réveiller des fils qui se rendorment aussitôt.
     *
     * <p>La raison du sommeil du serveur est simple et vaut d'être dite : un tick dure cinquante
     * millisecondes, et quand il a fini son travail, il attend le suivant. C'est exactement ce qu'il
     * doit faire quand des joueurs jouent — le temps doit s'écouler à la bonne vitesse.
     *
     * <p>Mais pendant une <b>pré-génération</b>, il n'y a rien à faire s'écouler. Le monde n'a pas
     * besoin de ticker à vingt hertz pour qu'on fabrique du terrain. Ce sommeil est du temps machine
     * jeté, et il représente six septièmes du total.
     *
     * <p>Ces vagues-ci le reprennent : au lieu de rendre la main, le serveur pompe les tâches en
     * attente jusqu'à ce qu'un chunk aboutisse. Si le pipeline est bien limité par les allers-retours
     * avec le fil principal, le débit doit bondir. Sinon, c'est que le goulot est ailleurs, et on
     * l'aura appris pour le prix d'une boucle.
     */
    private static final boolean[] PUMPED = {false, false, false, false, true, true, true};

    /** Chunks générés par vague. Assez pour que la mesure ne soit pas dominée par le démarrage. */
    private static final int PER_WAVE = 256;

    /**
     * Distance de la première vague, en chunks.
     *
     * <p>Cent mille blocs : au-delà de tout ce que les épreuves précédentes ont pu générer dans ce
     * monde, et en deçà de la limite du monde.
     */
    private static final int FAR = 6250;

    /**
     * Décalage tiré à chaque exécution, pour ne jamais regénérer ce qui l'a déjà été.
     *
     * <h2>L'avertissement que j'avais écrit, et dans lequel je suis tombé</h2>
     *
     * <p>Le commentaire de classe disait, avant même la première mesure : <em>« Générer deux fois la
     * même région mesurerait un chargement depuis le disque — c'est une autre grandeur, et bien plus
     * rapide, ce qui donnerait l'illusion d'un gain. »</em>
     *
     * <p>L'origine était pourtant fixe. La seconde exécution a donc rejoué les coordonnées de la
     * première, et le banc a annoncé un triomphe :
     *
     * <pre>
     * 32 demandes en vol   →  209,1 chunk(s) par seconde     (en réalité : lecture du disque)
     * 32 demandes en vol   →   13,7 chunk(s) par seconde     (coordonnées neuves : vraie génération)
     * </pre>
     *
     * <p>Quinze fois l'écart, entre deux lignes du même rapport. C'est le même défaut que celui de la
     * dynamite — un banc qui ne mesure pas ce qu'il croit — et il est arrivé le jour même où il avait
     * été corrigé ailleurs.
     *
     * <p>Le tirage règle le cas, et le rapport distingue désormais les deux grandeurs au lieu de les
     * confondre. Car la seconde n'est pas une erreur à jeter : elle est le <b>meilleur argument</b>
     * pour une pré-génération. Servir un chunk fabriqué d'avance coûte quinze fois moins cher que le
     * fabriquer devant le joueur.
     */
    private static final int DRIFT =
            new java.util.Random().nextInt(20000);

    /** Écart entre deux vagues, en chunks — quatre mille blocs. */
    private static final int GAP = 250;

    private static boolean running;
    private static int wave;
    private static int issued;
    private static int done;
    private static long waveStarted;
    private static final List<CompletableFuture<?>> IN_FLIGHT = new ArrayList<>();
    private static final List<String> VERDICTS = new ArrayList<>();
    private static double bestRate;
    private static int bestWidth;

    private Swarm() {}

    public static boolean running() {
        return running;
    }

    public static void begin(MinecraftServer server) {
        running = true;
        // Le profileur tourne pendant toute l.épreuve : c.est lui qui dira si le goulot est le
        // générateur, le bassin de tâches, ou le fil du serveur qui les orchestre.
        Sampler.start(Thread.currentThread());
        wave = 0;
        VERDICTS.clear();
        bestRate = 0d;
        bestWidth = 0;
        startWave(server);
    }

    private static void startWave(MinecraftServer server) {
        issued = 0;
        done = 0;
        IN_FLIGHT.clear();
        waveStarted = System.nanoTime();
        Lanterne.LOG.info("[ESSAIM] vague {} sur {} — {} chunks, {} demande(s) en vol à la fois.",
                wave + 1, WIDTHS.length, PER_WAVE, WIDTHS[wave]);
    }

    /**
     * Maintient la vague en vol et passe à la suivante quand elle est finie.
     *
     * <p>Appelé depuis le tick du serveur, parce que {@code getChunkFuture} veut être appelé de là.
     * On complète les demandes en vol jusqu'à la largeur voulue, puis on retire celles qui ont abouti.
     */
    public static void tick(MinecraftServer server) {
        if (!running) {
            return;
        }
        ServerLevel level = server.overworld();
        ServerChunkCache source = level.getChunkSource();
        int width = WIDTHS[wave];
        int originX = FAR + DRIFT + wave * GAP;

        IN_FLIGHT.removeIf(pending -> {
            if (pending.isDone()) {
                done++;
                return true;
            }
            return false;
        });

        while (IN_FLIGHT.size() < width && issued < PER_WAVE) {
            // Une bande de seize chunks de large : les voisins d'un chunk sont demandés à peu près en
            // même temps, ce qui est la situation d'un joueur qui avance et non un cas de laboratoire.
            int spotX = originX + (issued % 16);
            int spotZ = issued / 16;
            CompletableFuture<?> pending =
                    source.getChunkFuture(spotX, spotZ, ChunkStatus.FULL, true);
            IN_FLIGHT.add(pending);
            issued++;
        }

        if (PUMPED[wave] && !IN_FLIGHT.isEmpty()) {
            // On reprend le sommeil du tick. managedBlock exécute les tâches en attente du fil
            // principal jusqu'à ce que la condition soit vraie — c'est le mécanisme que le jeu
            // emploie lui-même quand il a besoin d'un chunk tout de suite. On s'arrête dès qu'une
            // demande aboutit, pour que la boucle de vague puisse en resoumettre.
            server.managedBlock(() -> {
                for (CompletableFuture<?> pending : IN_FLIGHT) {
                    if (pending.isDone()) {
                        return true;
                    }
                }
                return false;
            });
        }

        if (done < PER_WAVE) {
            return;
        }

        double seconds = (System.nanoTime() - waveStarted) / 1.0E9d;
        double rate = PER_WAVE / seconds;
        VERDICTS.add(String.format(java.util.Locale.ROOT,
                "%3d demande(s) en vol%s  →  %6.1f s  soit  %5.1f chunk(s) par seconde",
                width, PUMPED[wave] ? " + tick pompé" : "             ", seconds, rate));
        Lanterne.LOG.info("[ESSAIM] vague {} terminée — {} chunk(s) en {} s, soit {} par seconde.",
                wave + 1, PER_WAVE, String.format(java.util.Locale.ROOT, "%.1f", seconds),
                String.format(java.util.Locale.ROOT, "%.1f", rate));
        if (rate > bestRate) {
            bestRate = rate;
            bestWidth = width;
        }

        wave++;
        if (wave < WIDTHS.length) {
            startWave(server);
            return;
        }

        conclude(server);
    }

    private static void conclude(MinecraftServer server) {
        running = false;
        Sampler.stop();
        Sampler.report(20);
        Lanterne.LOG.info(String.format(java.util.Locale.ROOT,
                "[ESSAIM] re-soumissions de tâches de génération : %d pour %d chunk(s), soit %.0f "
                + "par chunk · vidanges de file (une par tick) : %d",
                fr.clubcitrouille.lanterne.core.Relay.submissions(),
                (long) PER_WAVE * WIDTHS.length,
                (double) fr.clubcitrouille.lanterne.core.Relay.submissions()
                        / Math.max(1L, (long) PER_WAVE * WIDTHS.length),
                fr.clubcitrouille.lanterne.core.Relay.drains()));
        Lanterne.LOG.info("[FORGE] ── Où va le temps, étape par étape ──{}",
                fr.clubcitrouille.lanterne.core.Forge.describe());
        Lanterne.LOG.info("[ESSAIM] ── Débit de génération selon le nombre de demandes en vol ──");
        for (String line : VERDICTS) {
            Lanterne.LOG.info("[ESSAIM] {}", line);
        }

        double solo = bestRate;
        for (String line : VERDICTS) {
            if (line.startsWith("  1 ")) {
                solo = readRate(line);
            }
        }
        if (solo > 0d) {
            double factor = bestRate / solo;
            if (factor > 1.15d) {
                Lanterne.LOG.info("[ESSAIM] VERDICT : saturer le bassin multiplie le débit par {} "
                        + "(meilleur à {} demandes en vol). Ce que le joueur attend en explorant est "
                        + "donc de l'attente, et non du calcul — un outil de pré-génération peut le "
                        + "rendre sans toucher au générateur.",
                        String.format(java.util.Locale.ROOT, "%.2f", factor), bestWidth);
            } else {
                Lanterne.LOG.info("[ESSAIM] VERDICT : le débit ne monte pas ({} au mieux). Le "
                        + "générateur lui-même est le goulot ; seul le réécrire changerait quelque "
                        + "chose, et c'est un tout autre chantier.",
                        String.format(java.util.Locale.ROOT, "×%.2f", factor));
            }
        }
        server.halt(false);
    }

    private static double readRate(String line) {
        int arrow = line.lastIndexOf("soit");
        if (arrow < 0) {
            return 0d;
        }
        String tail = line.substring(arrow + 4).trim();
        int space = tail.indexOf(' ');
        try {
            return Double.parseDouble(space < 0 ? tail : tail.substring(0, space));
        } catch (NumberFormatException unreadable) {
            return 0d;
        }
    }

    /** Le type d'accès qu'on demande, gardé pour que le compilateur retienne l'import. */
    static Class<?> accessed() {
        return ChunkAccess.class;
    }
}
