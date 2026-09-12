package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le profileur : où passent réellement les millisecondes.
 *
 * <h2>Pourquoi ne pas deviner</h2>
 *
 * <p>Ce mod a été contredit quatre fois par ses propres mesures, et à chaque fois l'intuition
 * paraissait solide. Compenser les ticks aléatoires : gain nul. Ralentir les entonnoirs : fermes
 * cassées. Un débordement d'entier neutralisait le recensement entier pendant six bancs sans que
 * rien ne le signale.
 *
 * <p>Il reste vingt-deux millisecondes par tick après la première optimisation. Les attaquer à
 * l'aveugle serait recommencer la même erreur une cinquième fois. <b>On mesure d'abord.</b>
 *
 * <h2>L'échantillonnage, et pourquoi il ne fausse presque rien</h2>
 *
 * <p>Deux façons de profiler. L'<b>instrumentation</b> compte chaque entrée et sortie de méthode :
 * exacte, et si coûteuse qu'elle déforme ce qu'elle mesure — les petites méthodes appelées souvent,
 * précisément celles qu'on cherche, sont les plus pénalisées.
 *
 * <p>L'<b>échantillonnage</b> interrompt le thread à intervalles réguliers et note où il en est. Si
 * une méthode occupe trente pour cent du temps, elle apparaîtra dans trente pour cent des relevés.
 * Le coût est celui des relevés eux-mêmes — quelques centaines par seconde — et il ne dépend
 * <em>pas</em> de la fréquence d'appel du code observé. C'est la méthode de tous les profileurs
 * sérieux, et c'est celle-ci.
 *
 * <h2>Ce qu'on agrège</h2>
 *
 * <p>Deux vues, parce qu'aucune ne suffit seule.
 *
 * <ul>
 *   <li><b>Le sommet de pile</b> dit quelle méthode consomme le processeur à l'instant du relevé.
 *       C'est ce qu'on optimise.</li>
 *   <li><b>La pile entière</b> dit d'où l'appel vient. Sans elle, on voit « accès à une table de
 *       hachage : douze pour cent » sans savoir qui la sollicite, ce qui ne mène nulle part.</li>
 * </ul>
 */
public final class Sampler {
    /** Millisecondes entre deux relevés. Deux cents par seconde : assez fin, et sans effet notable. */
    private static final long PERIOD_MS = 5L;
    /** Profondeur de pile retenue pour l'attribution. */
    private static final int DEPTH = 24;

    private static Thread worker;
    private static volatile boolean running;

    /** Sommet de pile vers nombre de relevés. */
    private static final Map<String, int[]> TOPS = new HashMap<>();
    /** Cadre d'intérêt vers nombre de relevés — toute la pile, dédoublonnée. */
    private static final Map<String, int[]> FRAMES = new HashMap<>();
    /**
     * Appelants directs d'une méthode surveillée.
     *
     * <p>Savoir qu'une recherche dans une table occupe dix-sept pour cent du temps ne mène nulle
     * part : on n'optimise pas {@code ThreadLocal.get}. Ce qu'il faut, c'est <b>qui l'appelle</b>,
     * et c'est ce que cette vue donne.
     */
    private static final Map<String, int[]> CALLERS = new HashMap<>();
    /** Motif de la méthode dont on veut connaître les appelants. */
    private static volatile String watched = "ThreadLocal";
    private static int samples;

    private Sampler() {}

    public static boolean running() {
        return running;
    }

    /** Démarre l'observation du thread donné. */
    public static synchronized void start(Thread target) {
        if (running) {
            return;
        }
        TOPS.clear();
        FRAMES.clear();
        CALLERS.clear();
        samples = 0;
        running = true;

        worker = new Thread(() -> loop(target), "lanterne-sampler");
        // Démon : il ne doit jamais retenir la machine virtuelle à l'arrêt du serveur.
        worker.setDaemon(true);
        // Sous la priorité du thread serveur : le profileur ne doit pas voler le processeur qu'il
        // mesure, ce qui serait une façon très sûre de fausser le résultat sur une machine à un cœur.
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    public static synchronized void stop() {
        running = false;
        worker = null;
    }

    private static void loop(Thread target) {
        while (running) {
            StackTraceElement[] stack = target.getStackTrace();
            if (stack.length > 0) {
                record(stack);
            }
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static synchronized void record(StackTraceElement[] stack) {
        samples++;
        String top = label(stack[0]);
        count(TOPS, top);

        // Le premier cadre hors de la méthode surveillée : son appelant utile.
        String probe = watched;
        if (probe != null && top.contains(probe)) {
            for (int i = 1; i < Math.min(stack.length, 6); i++) {
                String caller = label(stack[i]);
                if (!caller.contains(probe)) {
                    count(CALLERS, caller);
                    break;
                }
            }
        }

        // Une méthode récursive apparaîtrait autant de fois qu'elle s'appelle, et pèserait dix fois
        // son poids réel. On ne compte donc chaque cadre qu'une fois par relevé.
        int depth = Math.min(stack.length, DEPTH);
        for (int i = 0; i < depth; i++) {
            String name = label(stack[i]);
            boolean seen = false;
            for (int j = 0; j < i; j++) {
                if (label(stack[j]).equals(name)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                count(FRAMES, name);
            }
        }
    }

    private static void count(Map<String, int[]> into, String key) {
        into.computeIfAbsent(key, ignored -> new int[1])[0]++;
    }

    /** Nom court et lisible : classe simple, puis méthode. */
    private static String label(StackTraceElement frame) {
        String type = frame.getClassName();
        int dot = type.lastIndexOf('.');
        return (dot < 0 ? type : type.substring(dot + 1)) + '.' + frame.getMethodName();
    }

    /** Écrit le relevé dans le journal, les deux vues, du plus lourd au plus léger. */
    public static synchronized void report(int limit) {
        if (samples == 0) {
            Lanterne.LOG.info("[PROFIL] Aucun relevé.");
            return;
        }
        Lanterne.LOG.info("[PROFIL] {} relevés, un toutes les {} ms.", samples, PERIOD_MS);

        Lanterne.LOG.info("[PROFIL] ── Où le processeur se trouve (sommet de pile) ──");
        dump(TOPS, limit);

        Lanterne.LOG.info("[PROFIL] ── Sous quel appel (pile entière) ──");
        dump(FRAMES, limit);

        if (!CALLERS.isEmpty()) {
            Lanterne.LOG.info("[PROFIL] ── Qui appelle « {} » ──", watched);
            dump(CALLERS, 10);
        }
    }

    /** Choisit la méthode dont on veut les appelants. */
    public static void watch(String fragment) {
        watched = fragment;
    }

    private static void dump(Map<String, int[]> from, int limit) {
        List<Map.Entry<String, int[]>> rows = new ArrayList<>(from.entrySet());
        rows.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0]).reversed());

        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            Map.Entry<String, int[]> row = rows.get(i);
            double share = row.getValue()[0] * 100d / samples;
            if (share < 0.5d) {
                break; // sous un demi pour cent, c'est du bruit et cela allonge le rapport pour rien
            }
            Lanterne.LOG.info(String.format(Locale.ROOT, "[PROFIL] %5.1f %%  %s  (%d)",
                    share, row.getKey(), row.getValue()[0]));
        }
    }
}
