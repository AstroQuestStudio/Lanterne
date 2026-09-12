package fr.clubcitrouille.lanterne.report;

import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.TickBudget;

/**
 * Le banc de mesure : la même charge, deux fois, avec et sans.
 *
 * <h2>Le protocole, et pourquoi il compte plus que le résultat</h2>
 *
 * <p>Comparer deux mesures prises à des moments différents ne prouve rien : entre les deux, un
 * orage a pu éclater, un chunk se générer, le ramasse-miettes passer. Sur une instance à vingt-deux
 * mods d'optimisation, le bruit dépasse largement l'effet qu'on cherche à mesurer.
 *
 * <p>Le banc impose donc trois règles.
 *
 * <ol>
 *   <li><b>Une phase de chauffe.</b> Les premières secondes après un changement mesurent le
 *       compilateur à la volée en train de recompiler, pas le code. On les jette.</li>
 *   <li><b>Deux phases de durée égale</b>, l'une après l'autre, sur le même monde et la même
 *       charge — seul l'interrupteur change.</li>
 *   <li><b>La médiane, et non la moyenne.</b> Une sauvegarde automatique au milieu d'une phase
 *       ajoute deux cents millisecondes à un tick ; la moyenne en sort ruinée, la médiane n'en
 *       sait rien. On ne cherche pas le tick moyen, on cherche le tick <em>typique</em>.</li>
 * </ol>
 *
 * <p>Le résultat est un rapport entre deux nombres mesurés dans les mêmes conditions. S'il vaut un,
 * le mod ne sert à rien dans cette situation — et le banc le dira aussi clairement que l'inverse.
 */
public final class Bench {
    /**
     * Ticks jetés après chaque bascule, le temps que la machine virtuelle se restabilise.
     *
     * <h2>Pourquoi soixante ne suffisaient pas</h2>
     *
     * <p>Un banc lancé avec le mod <em>désactivé des deux côtés</em> a rendu quatorze millisecondes
     * pour la première phase et onze pour la seconde. Les deux mesuraient rigoureusement la même
     * chose : l'écart de vingt-sept pour cent était entièrement dû au compilateur à la volée, qui
     * continuait d'optimiser pendant la première.
     *
     * <p>Autrement dit, le protocole avantageait systématiquement la seconde phase — celle du
     * témoin. Le mod était donc sous-évalué, ce qui est la bonne direction pour se tromper, mais
     * une erreur reste une erreur. Deux cents ticks laissent au compilateur le temps de se fixer.
     */
    private static final int WARMUP = 200;
    /** Ticks mesurés par phase. Cinq cents ticks valent vingt-cinq secondes. */
    private static final int SAMPLE = 500;

    private enum Phase { IDLE, WARM_ON, MEASURE_ON, WARM_OFF, MEASURE_OFF, DONE }

    private static Phase phase = Phase.IDLE;
    private static int left;
    private static CommandSourceStack listener;
    /** Vrai quand le banc tourne sans personne : le verdict part alors au journal. */
    private static boolean headless;
    /** Arrêter le serveur en fin de banc — seulement en auto-test. */
    private static net.minecraft.server.MinecraftServer stopAfter;

    private static final long[] WITH = new long[SAMPLE];
    private static final long[] WITHOUT = new long[SAMPLE];
    private static int filled;

    private static long tickStart;

    /**
     * Mémoire allouée pendant chaque phase, en octets.
     *
     * <h2>Pourquoi mesurer l'allocation et non l'occupation</h2>
     *
     * <p>« Combien de mémoire le serveur occupe-t-il ? » est une question sans réponse stable : le
     * tas monte jusqu'au prochain ramassage, puis retombe. Deux relevés pris à deux instants
     * différents du même cycle donnent des chiffres opposés, et aucun n'est faux.
     *
     * <p>Ce qui compte est le <b>débit d'allocation</b> : combien d'octets par seconde le serveur
     * demande. C'est lui qui décide de la fréquence des ramassages, et donc des à-coups — sur une
     * machine à un seul cœur, un ramassage ne s'exécute pas « en parallèle », il fige le serveur.
     *
     * <p>Moins allouer, c'est ramasser moins souvent. C'est la seule façon d'agir sur la mémoire
     * dont un mod comme celui-ci soit capable, et elle se mesure.
     */
    private static long allocWith;
    private static long allocWithout;
    private static long allocMark;
    private static long gcWith;
    private static long gcWithout;
    private static long gcMark;

    /**
     * Paquets que le serveur a voulu envoyer pendant chaque phase.
     *
     * <p>La seule mesure honnête de l'effet réseau d'un mod : ce sur quoi il peut agir est le
     * <b>nombre de paquets décidés</b>, pas le débit réel, qui dépend de la ligne du joueur.
     */
    private static long packetsWith;
    private static long packetsWithout;

    private Bench() {}

    public static boolean running() {
        return phase != Phase.IDLE && phase != Phase.DONE;
    }

    /** Lance la comparaison. Deux phases de vingt-cinq secondes, plus la chauffe. */
    /** Lance le banc sans interlocuteur : le verdict ira au journal, puis le serveur s'arrête. */
    public static void startHeadless(net.minecraft.server.MinecraftServer server) {
        headless = true;
        stopAfter = server;
        listener = null;
        phase = Phase.WARM_ON;
        left = WARMUP;
        filled = 0;
        Settings.setEnabled(true);
        fr.clubcitrouille.lanterne.Lanterne.LOG.info(
                "Banc lancé — deux phases de {} s.", SAMPLE / 20);
    }

    public static void start(CommandSourceStack source) {
        headless = false;
        stopAfter = null;
        listener = source;
        phase = Phase.WARM_ON;
        left = WARMUP;
        filled = 0;
        Settings.setEnabled(true);

        source.sendSuccess(() -> Component.literal(
                        "Banc lancé — deux phases de " + (SAMPLE / 20) + " s. Ne bouge pas, et ne touche à rien.")
                .withStyle(ChatFormatting.GOLD), false);
    }

    /** Ouvre le chronomètre du tick. */
    public static void beginTick() {
        if (running()) {
            tickStart = System.nanoTime();
        }
    }

    /**
     * Ferme le chronomètre et fait avancer le protocole.
     *
     * <p>On mesure ici et non via {@link TickBudget} : celui-ci lisse et rejette les valeurs
     * extrêmes, ce qui est exactement ce qu'il faut pour piloter la sévérité, et exactement ce
     * qu'il ne faut pas pour mesurer.
     */
    public static void endTick(ServerLevel level) {
        if (!running()) {
            return;
        }
        long elapsed = System.nanoTime() - tickStart;

        switch (phase) {
            case WARM_ON -> {
                if (--left <= 0) {
                    phase = Phase.MEASURE_ON;
                    filled = 0;
                    allocMark = allocatedBytes();
                    gcMark = gcCount();
                    fr.clubcitrouille.lanterne.lab.Understudy.resetPackets();
                    fr.clubcitrouille.lanterne.lab.Sampler.start(Thread.currentThread());
                    fr.clubcitrouille.lanterne.lab.Allocations.start();
                }
            }
            case MEASURE_ON -> {
                WITH[filled++] = elapsed;
                if (filled >= SAMPLE) {
                    allocWith = allocatedBytes() - allocMark;
                    gcWith = gcCount() - gcMark;
                    packetsWith = fr.clubcitrouille.lanterne.lab.Understudy.packetsSent();
                    phase = Phase.WARM_OFF;
                    left = WARMUP;
                    Settings.setEnabled(false);
                    fr.clubcitrouille.lanterne.lab.Sampler.stop();
                    say("── Profil AVEC Lanterne ──");
                    fr.clubcitrouille.lanterne.lab.Sampler.report(14);
                    fr.clubcitrouille.lanterne.lab.Allocations.stopAndReport(20);
                    say("Phase 1 terminée (mod actif). Bascule — phase 2 sans le mod.");
                }
            }
            case WARM_OFF -> {
                if (--left <= 0) {
                    phase = Phase.MEASURE_OFF;
                    filled = 0;
                    allocMark = allocatedBytes();
                    gcMark = gcCount();
                    fr.clubcitrouille.lanterne.lab.Understudy.resetPackets();
                    // On profile la phase témoin, c'est-à-dire le jeu tel qu'il est. C'est cette
                    // répartition-là qu'il faut connaître : elle dit ce qui reste à attaquer.
                    fr.clubcitrouille.lanterne.lab.Sampler.start(Thread.currentThread());
                }
            }
            case MEASURE_OFF -> {
                WITHOUT[filled++] = elapsed;
                if (filled >= SAMPLE) {
                    allocWithout = allocatedBytes() - allocMark;
                    gcWithout = gcCount() - gcMark;
                    packetsWithout = fr.clubcitrouille.lanterne.lab.Understudy.packetsSent();
                    phase = Phase.DONE;
                    Settings.setEnabled(true);
                    fr.clubcitrouille.lanterne.lab.Sampler.stop();
                    conclude(level);
                    say("── Profil SANS Lanterne (le jeu tel quel) ──");
                    fr.clubcitrouille.lanterne.lab.Sampler.report(22);
                    if (stopAfter != null) {
                        stopAfter.halt(false);
                    }
                }
            }
            default -> { }
        }
    }

    private static void conclude(ServerLevel level) {
        double with = median(WITH);
        double without = median(WITHOUT);
        double ratio = with <= 0d ? 0d : without / with;

        say("── Résultat ──");
        say(String.format(Locale.ROOT, "Sans Lanterne : %.2f ms par tick (médiane)", without / 1e6));
        say(String.format(Locale.ROOT, "Avec Lanterne : %.2f ms par tick (médiane)", with / 1e6));
        say(String.format(Locale.ROOT, "Entités dans le monde : %d", count(level)));
        say(String.format(Locale.ROOT, "Modules actifs : %s", Settings.describe()));

        if (allocWithout > 0L) {
            double memoryRatio = (double) allocWithout / Math.max(1L, allocWith);
            say(String.format(Locale.ROOT,
                    "Mémoire allouée — sans : %s · avec : %s  (×%.2f moins)",
                    bytes(allocWithout), bytes(allocWith), memoryRatio));
            say(String.format(Locale.ROOT, "Ramassages — sans : %d · avec : %d",
                    gcWithout, gcWith));
        }

        if (packetsWithout > 0L) {
            say(String.format(Locale.ROOT,
                    "Paquets émis — sans : %d · avec : %d  (×%.2f moins)",
                    packetsWithout, packetsWith,
                    (double) packetsWithout / Math.max(1L, packetsWith)));
        }

        // Sans cette ventilation, un verdict « aucun effet » est indéchiffrable : on ne sait pas si
        // le mod n'a rien économisé, ou s'il n'a rien eu à économiser. Ce sont deux problèmes
        // opposés, et ils appellent des corrections opposées.
        say(String.format(Locale.ROOT, "Observateurs : %d · chunks recensés : %d",
                fr.clubcitrouille.lanterne.core.Census.probeCount(),
                fr.clubcitrouille.lanterne.core.Census.chunksSeen()));
        for (var entry : fr.clubcitrouille.lanterne.core.Census.buckets().entrySet()) {
            say(String.format(Locale.ROOT, "  %s : %d", entry.getKey(), entry.getValue()[0]));
        }
        say(String.format(Locale.ROOT, "Travail évité (calculé) : %.1f %%",
                fr.clubcitrouille.lanterne.core.Census.workAvoided() * 100d));

        // Le verdict, formulé pour être vérifiable et non pour flatter. Un gain sous cinq pour cent
        // n'est pas un gain : c'est du bruit, et le dire est la seule façon de rester crédible
        // quand le chiffre est bon.
        if (ratio > 1.05d) {
            say(String.format(Locale.ROOT, "Gain : ×%.2f (%.0f %% de temps en moins)",
                    ratio, (1d - with / without) * 100d));
        } else if (ratio < 0.95d) {
            say(String.format(Locale.ROOT,
                    "PERTE : ×%.2f — le mod coûte plus qu'il ne rapporte ici.", ratio));
        } else {
            say("Aucun effet mesurable dans cette situation (écart sous le bruit de fond).");
        }
    }

    /** Nombre d'entités réellement vivantes dans le monde — la vérification qui a tout débloqué. */
    public static int livingCount(ServerLevel level) {
        return count(level);
    }

    /**
     * Octets alloués par le thread courant depuis son démarrage.
     *
     * <p>La machine virtuelle tient ce compteur par thread, gratuitement. Il n'est pas exposé par
     * l'interface publique, d'où le détour par la classe d'implémentation — sans conséquence : en
     * cas d'absence, on rend zéro et le rapport se tait plutôt que de mentir.
     */
    private static long allocatedBytes() {
        try {
            java.lang.management.ThreadMXBean bean =
                    java.lang.management.ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean sun) {
                return sun.getCurrentThreadAllocatedBytes();
            }
        } catch (Throwable unsupported) {
            // Machine virtuelle sans cette extension : on renonce à la mesure, pas au banc.
        }
        return 0L;
    }

    /** Nombre total de ramassages depuis le démarrage, toutes générations confondues. */
    private static long gcCount() {
        long total = 0L;
        for (java.lang.management.GarbageCollectorMXBean bean
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count > 0L) {
                total += count;
            }
        }
        return total;
    }

    private static String bytes(long value) {
        if (value > 1_073_741_824L) {
            return String.format(Locale.ROOT, "%.2f Go", value / 1_073_741_824d);
        }
        if (value > 1_048_576L) {
            return String.format(Locale.ROOT, "%.1f Mo", value / 1_048_576d);
        }
        return String.format(Locale.ROOT, "%.1f Ko", value / 1024d);
    }

    private static int count(ServerLevel level) {
        int total = 0;
        for (var ignored : level.getAllEntities()) {
            total++;
        }
        return total;
    }

    /**
     * La médiane d'un échantillon.
     *
     * <p>Le tri coûte, mais il a lieu une fois en fin de banc : c'est exactement le genre d'endroit
     * où la simplicité vaut mieux que l'astuce.
     */
    private static double median(long[] values) {
        long[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0
                ? (copy[middle - 1] + copy[middle]) / 2d
                : copy[middle];
    }

    private static void say(String text) {
        if (listener != null) {
            listener.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.WHITE), false);
        }
        if (headless) {
            fr.clubcitrouille.lanterne.Lanterne.LOG.info("[BANC] {}", text);
        }
    }
}
