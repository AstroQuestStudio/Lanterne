package fr.clubcitrouille.lanterne.lab;

import java.util.Arrays;
import java.util.Locale;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Creuset;
import fr.clubcitrouille.lanterne.report.Herd;

/**
 * Ce que le ramasse-miettes prend au tick, mesuré au lieu d'être supposé.
 *
 * <h2>L'impasse dont ce banc sort</h2>
 *
 * <p>{@code notes/serveur-un-coeur.md} a constaté, honnêtement, qu'il ne pouvait pas trancher entre
 * G1 et le ramasseur série : « la variance d'une exécution à l'autre sur la même charge va de 32 à
 * 47 ms — largement plus que l'effet cherché ».
 *
 * <p>Le constat était juste et la conclusion trop modeste. Comparer deux démarrages est impossible
 * <b>sur le temps de tick</b> ; ça ne l'est pas sur une grandeur que la machine virtuelle compte
 * elle-même et qui ne dépend pas du bruit de l'ordonnanceur : le <b>temps de pause cumulé</b> et le
 * <b>nombre de ramassages</b>, pour une charge d'allocation identique.
 *
 * <p>Ce banc fixe donc tout ce qui peut l'être — même monde, même troupeau posé par le même angle
 * d'or, même nombre de ticks — et publie quatre chiffres : la médiane du tick, le pire tick, les
 * ramassages, et la pause cumulée. Les deux derniers sont les seuls qu'on ait le droit de comparer
 * d'une exécution à l'autre, et ce sont eux qui répondent à la question posée.
 *
 * <h2>Comment on s'en sert</h2>
 *
 * <pre>
 * LANTERNE_FONTE=1 ./gradlew runServer -Pbanc=fonte -Pgc=serial -Pcoeurs=1
 * LANTERNE_FONTE=1 ./gradlew runServer -Pbanc=fonte -Pgc=g1     -Pcoeurs=1
 * </pre>
 *
 * <p>{@code -Pcoeurs=1} pose {@code -XX:ActiveProcessorCount=1}, qui fait dimensionner à la machine
 * virtuelle <b>tous</b> ses réservoirs de fils — ramasseur compris — comme si elle n'avait qu'un
 * processeur. C'est ce qui rend la mesure représentative de la cible.
 *
 * <p>Ce que cela ne fait <b>pas</b> : forcer le système à ne donner qu'un cœur. Les fils ainsi
 * dimensionnés tournent toujours sur la machine de mesure. Le banc reproduit donc la
 * <em>configuration</em> de la cible, pas sa <em>pénurie</em> — et il faut le dire, sans quoi on
 * publierait un chiffre pour ce qu'il n'est pas.
 *
 * <h2>L'interrupteur de rupture</h2>
 *
 * <p>{@code LANTERNE_BREAK_FONTE=1} retire la charge : le troupeau n'est pas posé. L'épreuve doit
 * alors <b>échouer</b>, faute d'allocation à mesurer. Un banc qui rend le même verdict avec et sans
 * sa scène ne mesure rien — cinq épreuves de ce dépôt ont un jour rendu « conforme » sur un monde
 * vide, et c'est la raison d'être de cet interrupteur.
 */
public final class Fonte {

    /** Bêtes posées : assez pour que l'allocation soit franche, pas assez pour tuer la machine. */
    private static final int HERD = 600;

    /** Rayon du troupeau, en blocs. */
    private static final int SPREAD = 160;

    /** Ticks mesurés. Vingt secondes de jeu : plusieurs ramassages, même sur un petit tas. */
    private static final int TICKS = 400;

    /** En deçà de ce nombre de bêtes vivantes, la scène est trop maigre pour conclure. */
    private static final int FLOOR = 100;

    private static boolean running;
    private static int done;
    private static long[] samples;
    private static long tickStart;
    private static int alive;

    private Fonte() {}

    public static boolean running() {
        return running;
    }

    /** Pose la scène et arme le compteur. */
    public static void begin(MinecraftServer server) {
        ServerLevel level = server.overworld();
        Herd.sweepEntities(level);

        boolean broken = "1".equals(System.getenv("LANTERNE_BREAK_FONTE"));
        if (broken) {
            Lanterne.LOG.warn("[FONTE] LANTERNE_BREAK_FONTE=1 — aucune charge posée. "
                    + "L'épreuve DOIT refuser de conclure.");
        } else {
            Herd.populate(level, 0d, 0d, HERD, SPREAD);
        }

        alive = count(level);
        samples = new long[TICKS];
        done = 0;
        tickStart = 0L;
        running = true;
        Creuset.mark();
        Lanterne.LOG.info("[FONTE] {} — {} bête(s) vivante(s), {} tick(s) à mesurer.",
                Creuset.collector(), alive, TICKS);
    }

    /** Ouvre la fenêtre du tick. */
    public static void beginTick() {
        if (running) {
            tickStart = System.nanoTime();
        }
    }

    /**
     * Referme la fenêtre, et conclut au dernier tick.
     *
     * <h2>Pourquoi le premier tick est jeté</h2>
     *
     * <p>{@code begin()} est appelé depuis la <b>fin</b> du tick, par l'auto-test ; l'ouverture de
     * fenêtre, elle, est appelée depuis le <b>début</b>. Le tout premier tick mesuré est donc un tick
     * dont on n'a jamais vu le début : son chronomètre vaut zéro, et la soustraction rend l'horloge
     * entière de la machine virtuelle.
     *
     * <p>La première version publiait le résultat sans broncher : <b>pire tick = 14 175 255,87 ms</b>,
     * soit près de quatre heures dans un banc qui en dure vingt secondes. Le chiffre était si absurde
     * qu'il se voyait — mais il avait aussi écrasé la part du tick prise par les pauses, qui
     * s'affichait « 0,00 % » au lieu de sa vraie valeur. C'est ce second effet qui est dangereux : il
     * ne ressemble pas à une erreur, il ressemble à un résultat.
     */
    public static void endTick(MinecraftServer server) {
        if (!running) {
            return;
        }
        if (tickStart == 0L) {
            // Tick commencé avant que le banc n'existe : il n'a pas de durée, on ne l'invente pas.
            return;
        }
        samples[done++] = System.nanoTime() - tickStart;
        tickStart = 0L;
        if (done < TICKS) {
            return;
        }
        running = false;
        report(server);
    }

    private static void report(MinecraftServer server) {
        long collections = Creuset.collectionsSinceMark();
        long pause = Creuset.pauseSinceMark();

        if (alive < FLOOR) {
            // La règle de la maison : sans scène, pas de verdict. C'est ce que l'interrupteur de
            // rupture vérifie, et c'est ce qui distingue une mesure d'une affirmation.
            Lanterne.LOG.error("[FONTE] REFUS DE CONCLURE — {} bête(s) vivante(s) sur {} demandées. "
                    + "Sans charge d'allocation, comparer deux ramasseurs n'a aucun sens.",
                    alive, HERD);
            server.halt(false);
            return;
        }

        long[] sorted = Arrays.copyOf(samples, done);
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2] / 1_000_000d;
        double worst = sorted[sorted.length - 1] / 1_000_000d;
        double p99 = sorted[(int) (sorted.length * 0.99d)] / 1_000_000d;

        long wall = 0L;
        for (long sample : sorted) {
            wall += sample;
        }
        double wallMs = wall / 1_000_000d;

        Lanterne.LOG.info("[FONTE] ─────────────────────────────────────────────");
        Lanterne.LOG.info("[FONTE] ramasseur        : {}", Creuset.collector());
        Lanterne.LOG.info("[FONTE] processeurs vus  : {}", Runtime.getRuntime().availableProcessors());
        Lanterne.LOG.info("[FONTE] tas plafonné     : {} Mo",
                Runtime.getRuntime().maxMemory() / 1048576L);
        Lanterne.LOG.info("[FONTE] bêtes vivantes   : {}", alive);
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[FONTE] tick médian     : %.2f ms", median));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[FONTE] tick 99e centile: %.2f ms", p99));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[FONTE] pire tick       : %.2f ms", worst));
        Lanterne.LOG.info("[FONTE] ramassages      : {}", collections);
        Lanterne.LOG.info("[FONTE] pause cumulée   : {} ms", pause);
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[FONTE] part du tick prise par les pauses : %.2f %%",
                pause * 100d / Math.max(1d, wallMs)));
        Lanterne.LOG.info("[FONTE] ─────────────────────────────────────────────");
        Lanterne.LOG.info("[FONTE] Les deux chiffres comparables d'une exécution à l'autre sont les "
                + "ramassages et la pause cumulée. Le tick, lui, varie trop.");
        server.halt(false);
    }

    private static int count(ServerLevel level) {
        int total = 0;
        for (var ignored : level.getAllEntities()) {
            total++;
        }
        return total;
    }
}
