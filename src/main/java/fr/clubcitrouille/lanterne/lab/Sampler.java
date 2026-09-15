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

    /** Sommets de pile relevés sur les autres fils, et combien de fois chacun a été vu actif. */
    private static final Map<String, int[]> ELSEWHERE = new HashMap<>();
    private static final Map<String, int[]> ELSEWHERE_BUSY = new HashMap<>();
    /**
     * Motif de la méthode dont on veut connaître les appelants.
     *
     * <h2>Le réglage qui manquait</h2>
     *
     * <p>Cette vue répond à la seule question qui compte quand un poste remonte : <b>qui l'appelle ?</b>
     * Savoir que {@code PalettedContainer.get} pèse dix-sept pour cent ne mène nulle part — on
     * n'optimise pas une lecture de palette. Savoir que c'est {@code LivingEntity.onClimbable} qui la
     * demande vingt fois par seconde pour une vache qui ne grimpera jamais, si.
     *
     * <p>Elle était pourtant figée sur {@code ThreadLocal}, cible du jour où elle a été écrite, et
     * réglée depuis. Elle observait donc consciencieusement un problème résolu pendant que le suivant
     * passait inaperçu. Un outil de diagnostic qu'on ne peut pas rediriger finit par ne plus rien
     * apprendre.
     *
     * <p>{@code LANTERNE_WATCH=PalettedContainer} la pointe où l'on veut.
     */
    private static volatile String watched = envWatch();

    private static String envWatch() {
        String wanted = System.getenv("LANTERNE_WATCH");
        return wanted == null || wanted.isBlank() ? "ThreadLocal" : wanted.trim();
    }

    /**
     * Les motifs à traverser pour trouver le véritable demandeur.
     *
     * <h2>Un appelant qui n'apprenait rien</h2>
     *
     * <p>Interrogé sur {@code PalettedContainer}, le profileur répondait :
     * {@code LevelChunkSection.getBlockState — 15,2 %}. C'est exact et parfaitement inutile. Bien sûr
     * que c'est la section qui lit la palette ; la question est <b>qui demande à la section</b>.
     *
     * <p>Le défaut venait de la règle : on remontait jusqu'au premier cadre différent du motif. Or
     * l'accès à un bloc traverse quatre ou cinq couches d'infrastructure — palette, section, chunk,
     * monde — et chacune est « différente » de la précédente. On s'arrêtait donc systématiquement au
     * premier étage d'un escalier de cinq.
     *
     * <p>Le motif peut désormais énumérer toute la chaîne, séparée par des virgules. On remonte jusqu'au
     * premier cadre qui n'en fait pas partie — c'est-à-dire jusqu'à celui qui a vraiment posé la
     * question, et qui est le seul qu'on puisse corriger.
     */
    private static String[] probes() {
        String raw = watched;
        return raw == null ? new String[0] : raw.split(",");
    }

    private static boolean matchesProbe(String label, String[] probes) {
        for (String probe : probes) {
            String trimmed = probe.trim();
            if (!trimmed.isEmpty() && label.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }
    private static int samples;
    /**
     * Relevés pris pendant que le serveur travaille vraiment.
     *
     * <h2>Le pourcentage qui divisait tout par quatre</h2>
     *
     * <p>Un serveur qui tient ses vingt ticks par seconde passe le plus clair de son temps à
     * <b>attendre</b> : le tick fini, il dort jusqu'au suivant. Sur l'épreuve de l'enclos, le premier
     * relevé a donné {@code Unsafe.park : 75,9 %} — c'est-à-dire que trois relevés sur quatre ont
     * surpris le serveur à ne rien faire.
     *
     * <p>La conséquence était sournoise. Tous les autres chiffres étaient exprimés en part du temps
     * <em>écoulé</em>, attente comprise : {@code EntitySection.getEntities : 5,6 %} laissait croire à
     * un poste négligeable, alors qu'il pesait près d'un quart du travail réel. Un profil qui
     * sous-évalue d'un facteur quatre le poste le plus lourd ne désigne pas la bonne cible — et ce
     * projet a déjà perdu six bancs à poursuivre une cible mal désignée.
     *
     * <p>On compte donc séparément les relevés utiles, et c'est sur eux que portent les pourcentages.
     * L'attente reste affichée, mais à sa place : comme une <b>marge</b>, qui est une bonne nouvelle,
     * et non comme un poste de dépense.
     */
    private static int working;

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
        ELSEWHERE.clear();
        ELSEWHERE_BUSY.clear();
        busySum = 0L;
        busySamples = 0L;
        samples = 0;
        working = 0;
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

    /**
     * Les autres threads du serveur, échantillonnés à part.
     *
     * <h2>Un profileur qui regardait un thread sur douze</h2>
     *
     * <p>Ce profileur n'a jamais observé que le thread du serveur. C'était le bon choix tant qu'on
     * cherchait à faire baisser la durée d'un tick — mais cela rendait <b>invisible</b> tout ce que le
     * jeu exécute ailleurs, et ce n'est pas peu :
     *
     * <ul>
     *   <li>le <b>moteur de lumière</b>, qui tourne sur son propre fil et dont chaque bloc détruit
     *       déclenche la propagation ;</li>
     *   <li>la <b>génération de terrain</b>, répartie sur un bassin de tâches ;</li>
     *   <li>la <b>sauvegarde</b> et la compression des régions.</li>
     * </ul>
     *
     * <p>Le serveur attend ces travaux — un chunk n'est pas servi tant que sa lumière n'est pas
     * calculée — mais il attend en dormant, et le profileur classait ce sommeil dans la marge. Trois
     * domaines entiers, dont deux que ce projet s'est donné pour objectif d'optimiser, n'étaient
     * mesurés par rien.
     *
     * <p>{@code LANTERNE_PROFILE_ALL=1} échantillonne donc tous les fils vivants et non plus un seul.
     * Le relevé reste séparé : mélanger le thread du serveur et douze threads de travail dans un même
     * pourcentage donnerait un chiffre que personne ne saurait lire.
     */
    private static boolean everyThread() {
        return "1".equals(System.getenv("LANTERNE_PROFILE_ALL"));
    }

    /**
     * Fils qu'on n'échantillonne pas, même en mode complet.
     *
     * <p>Le profileur lui-même, et les fils de la machine virtuelle qui dorment en permanence : les
     * compter reviendrait à noyer les postes réels sous du sommeil.
     */
    private static boolean worthWatching(Thread candidate) {
        if (candidate == worker || !candidate.isAlive()) {
            return false;
        }
        String name = candidate.getName();
        return !name.startsWith("lanterne-")
                && !name.equals("Reference Handler")
                && !name.equals("Finalizer")
                && !name.equals("Signal Dispatcher")
                && !name.equals("Notification Thread")
                && !name.startsWith("Common-Cleaner")
                && !name.startsWith("process reaper")
                && !name.startsWith("JFR ")
                && !name.startsWith("FileSystemWatch")
                && !name.startsWith("Attach Listener");
    }

    private static void loop(Thread target) {
        boolean all = everyThread();
        while (running) {
            StackTraceElement[] stack = target.getStackTrace();
            if (stack.length > 0) {
                record(stack);
            }
            if (all) {
                int busy = 0;
                // getAllStackTraces fige brièvement chaque fil. On l'appelle donc à la même cadence
                // que le relevé principal et jamais plus souvent : c'est un profileur, il n'a pas le
                // droit de devenir la cause de ce qu'il mesure.
                for (var entry : Thread.getAllStackTraces().entrySet()) {
                    Thread other = entry.getKey();
                    if (other != target && worthWatching(other) && entry.getValue().length > 0) {
                        if (recordElsewhere(other.getName(), entry.getValue())) {
                            busy++;
                        }
                    }
                }
                // Le nombre de fils réellement occupés à cet instant, et non la simple présence
                // d.un nom. C.est la seule façon de savoir si la machine est saturée ou si elle
                // attend : un relevé qui dit « wgen_fill_noise 52 % » ne dit pas s.il y en avait un
                // ou quinze.
                noteBusyThreads(busy);
            }
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Le serveur attendait-il à cet instant ?
     *
     * <p>On cherche le cadre d'attente dans les quelques premiers niveaux de pile seulement : quand
     * le serveur dort, {@code waitUntilNextTick} est tout en haut. Parcourir la pile entière
     * classerait comme « attente » n'importe quel travail fait <em>depuis</em> cette attente — par
     * exemple les tâches différées, qui sont du vrai travail.
     */
    private static boolean idle(StackTraceElement[] stack) {
        int depth = Math.min(stack.length, 8);
        for (int i = 0; i < depth; i++) {
            String method = stack[i].getMethodName();
            if (method.equals("waitForTasks") || method.equals("waitUntilNextTick")) {
                return true;
            }
        }
        return false;
    }

    private static synchronized void record(StackTraceElement[] stack) {
        samples++;
        if (idle(stack)) {
            // Le serveur dort : ce relevé compte pour la marge, pas pour la dépense. L'inclure dans
            // le dénominateur divisait tous les postes par quatre et faisait passer le plus lourd
            // d'entre eux pour un détail.
            return;
        }
        working++;
        String top = label(stack[0]);
        count(TOPS, top);

        // Le premier cadre au-dessus de toute la chaîne surveillée : le demandeur véritable. On
        // cherche plus haut que six niveaux — l'accès à un bloc en traverse cinq à lui seul.
        String[] probes = probes();
        if (probes.length > 0 && matchesProbe(top, probes)) {
            for (int i = 1; i < Math.min(stack.length, 14); i++) {
                String caller = label(stack[i]);
                if (!matchesProbe(caller, probes)) {
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
        if (working == 0) {
            Lanterne.LOG.info("[PROFIL] {} relevés, tous pris pendant que le serveur attendait — "
                    + "la charge est trop légère pour qu'un profil ait un sens.", samples);
            return;
        }
        // La marge d'abord, parce que c'est elle qui dit si le reste vaut la peine d'être lu. Un
        // serveur qui dort les trois quarts du temps n'a pas de problème de performance ; les postes
        // qu'on listerait ensuite seraient exacts et sans intérêt.
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[PROFIL] %d relevés dont %d pendant le travail (%.0f %% du temps dormi — c'est la "
                + "marge). Les parts ci-dessous portent sur le travail seul.",
                samples, working, (samples - working) * 100d / samples));

        Lanterne.LOG.info("[PROFIL] ── Où le processeur se trouve (sommet de pile) ──");
        dump(TOPS, limit);

        Lanterne.LOG.info("[PROFIL] ── Sous quel appel (pile entière) ──");
        dump(FRAMES, limit);

        if (!CALLERS.isEmpty()) {
            Lanterne.LOG.info("[PROFIL] ── Qui appelle « {} » ──", watched);
            dump(CALLERS, 10);
        }

        reportOtherThreads(limit);
    }

    /**
     * Ce que font les autres fils pendant que le serveur travaille.
     *
     * <p>Séparé du relevé principal, et volontairement. Le thread du serveur décide de la durée d'un
     * tick ; les autres décident de ce que le serveur <em>attend</em>. Ce sont deux grandeurs
     * différentes, et les additionner dans un même pourcentage donnerait un nombre que personne ne
     * saurait interpréter.
     *
     * <p>On affiche d'abord combien de relevés chaque fil a fournis — un fil qui dort n'a pas de
     * problème de performance, et le dire évite de chercher une optimisation là où il n'y a rien à
     * gagner.
     */
    private static synchronized void reportOtherThreads(int limit) {
        if (ELSEWHERE.isEmpty()) {
            return;
        }
        Lanterne.LOG.info("[PROFIL] ── Les autres fils (lumière, génération, sauvegarde) ──");
        if (busySamples > 0L) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[PROFIL]  %.2f fil(s) occupé(s) en moyenne, hors fil du serveur — c.est la seule "
                    + "façon de savoir si la machine est saturée ou si elle attend.",
                    (double) busySum / busySamples));
        }

        List<Map.Entry<String, int[]>> busiest = new ArrayList<>(ELSEWHERE_BUSY.entrySet());
        busiest.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0]).reversed());
        for (int i = 0; i < Math.min(8, busiest.size()); i++) {
            Map.Entry<String, int[]> row = busiest.get(i);
            if (row.getValue()[0] * 100d / Math.max(1, samples) < 1d) {
                break;
            }
            Lanterne.LOG.info(String.format(Locale.ROOT, "[PROFIL]  %5.1f %% actif  %s",
                    row.getValue()[0] * 100d / Math.max(1, samples), row.getKey()));
        }

        int total = 0;
        for (int[] tally : ELSEWHERE.values()) {
            total += tally[0];
        }
        if (total == 0) {
            return;
        }
        List<Map.Entry<String, int[]>> rows = new ArrayList<>(ELSEWHERE.entrySet());
        rows.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0]).reversed());
        Lanterne.LOG.info("[PROFIL] ── Où ces fils passent leur temps ──");
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            Map.Entry<String, int[]> row = rows.get(i);
            double share = row.getValue()[0] * 100d / total;
            if (share < 0.5d) {
                break;
            }
            Lanterne.LOG.info(String.format(Locale.ROOT, "[PROFIL]  %5.1f %%  %s  (%d)",
                    share, row.getKey(), row.getValue()[0]));
        }
    }

    /**
     * Relève un autre fil que celui du serveur.
     *
     * <p>Un fil de travail qui n'a rien à faire attend sur sa file. Ce sommeil-là est compté à part —
     * comme l'attente du serveur l'est déjà — sans quoi douze fils oisifs noieraient le seul qui
     * travaille.
     */
    private static synchronized boolean recordElsewhere(String threadName, StackTraceElement[] stack) {
        if (parked(stack)) {
            return false;
        }
        count(ELSEWHERE_BUSY, threadName);
        count(ELSEWHERE, label(stack[0]));
        // La vue « qui appelle » vaut aussi pour les autres fils : c.est même là qu.elle sert le
        // plus, puisqu.on ne connaît pas leur code aussi bien que celui du tick.
        String[] probes = probes();
        if (probes.length > 0 && matchesProbe(label(stack[0]), probes)) {
            for (int i = 1; i < Math.min(stack.length, 14); i++) {
                String caller = label(stack[i]);
                if (!matchesProbe(caller, probes)) {
                    count(CALLERS, caller);
                    break;
                }
            }
        }
        return true;
    }

    /** Fils occupés, cumulés sur les relevés, pour en tirer une moyenne. */
    private static long busySum;
    private static long busySamples;

    private static synchronized void noteBusyThreads(int busy) {
        busySum += busy;
        busySamples++;
    }

    /** Le fil attend-il une tâche plutôt que d'en exécuter une ? */
    private static boolean parked(StackTraceElement[] stack) {
        int depth = Math.min(stack.length, 6);
        for (int i = 0; i < depth; i++) {
            String method = stack[i].getMethodName();
            if (method.equals("park") || method.equals("parkNanos") || method.equals("wait")
                    || method.equals("epollWait") || method.equals("poll0")
                    || method.equals("await") || method.equals("sleep")
                    // Attentes natives. Sans elles, un surveillant de fichiers de Windows bloqué
                    // dans GetQueuedCompletionStatus0 occupait 95 % du relevé en ne faisant
                    // rigoureusement rien, et masquait le moteur de lumière derrière lui.
                    || method.equals("GetQueuedCompletionStatus0") || method.equals("accept0")
                    || method.equals("select") || method.equals("poll")
                    || method.equals("takeEvents")
                    || method.equals("waitForReferencePendingList")) {
                return true;
            }
        }
        return false;
    }

    /** Choisit la méthode dont on veut les appelants. */
    public static void watch(String fragment) {
        watched = fragment;
    }

    /**
     * Un poste du relevé : son nom, et la part du travail qu'il porte.
     *
     * <p>{@code share} vaut entre zéro et un — et non entre zéro et cent. Le rapport qui l'affiche
     * multipliera ; celui qui le multiplie par une durée de tick, lui, n'a rien à diviser.
     */
    public record Post(String label, double share, int hits) {}

    /**
     * Le relevé, rendu au lieu d'être imprimé.
     *
     * <h2>Pourquoi un pourcentage ne suffit pas à comparer deux charges</h2>
     *
     * <p>{@link #report} imprime des parts, et c'est ce qu'il faut pour lire <em>un</em> profil : on
     * cherche le poste le plus lourd, et la part le désigne.
     *
     * <p>Mais la question posée par {@link Cheptel} est autre — <b>comment chaque poste grandit quand
     * la charge grandit</b> — et la part ne peut pas y répondre. Un poste qui reste à dix pour cent
     * alors que le tick passe de cinq à soixante millisecondes a été multiplié par douze ; le profil
     * affiche « 10 % » des deux côtés et ne dit rien.
     *
     * <p>Ce qui se compare entre deux paliers est la <b>durée absolue</b> : la part, multipliée par la
     * durée du tick. D'où ce rendu, qui laisse l'appelant faire la multiplication qu'il est le seul à
     * pouvoir faire.
     *
     * <p>À ne lire qu'après {@link #stop} : un relevé pris pendant que le profileur tourne encore
     * changerait sous les pieds de qui le lit.
     *
     * @return les sommets de pile, du plus lourd au plus léger
     */
    public static synchronized List<Post> snapshot() {
        List<Post> posts = new ArrayList<>();
        if (working == 0) {
            return posts;
        }
        List<Map.Entry<String, int[]>> rows = new ArrayList<>(TOPS.entrySet());
        rows.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0]).reversed());
        for (Map.Entry<String, int[]> row : rows) {
            posts.add(new Post(row.getKey(), row.getValue()[0] / (double) working, row.getValue()[0]));
        }
        return posts;
    }

    /**
     * Relevés pris pendant que le serveur travaillait.
     *
     * <p>C'est le dénominateur de toutes les parts, et il a sa place dans un rapport : une part de
     * trente pour cent tirée de six relevés ne vaut pas une part de trente pour cent tirée de mille.
     * Voir la troisième règle d'instrument de ce dépôt — un pourcentage n'a de sens que rapporté à ce
     * qui a été totalisé.
     */
    public static synchronized int workingSamples() {
        return working;
    }

    /** Relevés pris en tout, sommeil compris. */
    public static synchronized int totalSamples() {
        return samples;
    }

    /**
     * Qui appelle la méthode surveillée, rendu au lieu d'être imprimé.
     *
     * <h2>Pourquoi cette vue décide de tout quand un poste est quadratique</h2>
     *
     * <p>Un poste qui grandit comme le carré de la charge ne s'optimise pas : il se <b>supprime</b>.
     * Et l'on ne peut le supprimer qu'à l'endroit où il est demandé, jamais là où il est exécuté —
     * personne n'a jamais accéléré une recherche spatiale en la rendant plus rapide.
     *
     * <p>La question « qui appelle ? » est donc la seule qui mène quelque part, et {@code report} la
     * réservait au journal. Un banc qui doit choisir sa cible a besoin de la lire.
     *
     * <p>Les parts portent ici sur le total des appelants relevés, et non sur le travail : ce qu'on
     * veut savoir est <em>quelle part des appels vient d'où</em>, pas quelle part du tick.
     */
    public static synchronized List<Post> callers() {
        List<Post> posts = new ArrayList<>();
        int total = 0;
        for (int[] tally : CALLERS.values()) {
            total += tally[0];
        }
        if (total == 0) {
            return posts;
        }
        List<Map.Entry<String, int[]>> rows = new ArrayList<>(CALLERS.entrySet());
        rows.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0]).reversed());
        for (Map.Entry<String, int[]> row : rows) {
            posts.add(new Post(row.getKey(), row.getValue()[0] / (double) total, row.getValue()[0]));
        }
        return posts;
    }

    /** Le motif actuellement surveillé, pour que le rapport dise sur quoi porte la vue. */
    public static String watching() {
        return watched;
    }

    private static void dump(Map<String, int[]> from, int limit) {
        List<Map.Entry<String, int[]>> rows = new ArrayList<>(from.entrySet());
        rows.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0]).reversed());

        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            Map.Entry<String, int[]> row = rows.get(i);
            double share = row.getValue()[0] * 100d / working;
            if (share < 0.5d) {
                break; // sous un demi pour cent, c'est du bruit et cela allonge le rapport pour rien
            }
            Lanterne.LOG.info(String.format(Locale.ROOT, "[PROFIL] %5.1f %%  %s  (%d)",
                    share, row.getKey(), row.getValue()[0]));
        }
    }
}
