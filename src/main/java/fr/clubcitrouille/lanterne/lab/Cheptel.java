package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.report.Herd;

/**
 * Le cheptel : où part le temps quand le troupeau grandit, poste par poste et palier par palier.
 *
 * <h2>La question qu'un seul palier ne peut pas poser</h2>
 *
 * <p>Tous les bancs de ce dépôt mesurent une charge et rendent un rapport de durées. C'est ce qu'il
 * faut pour juger un module, et cela ne dit <b>rien</b> de ce qu'il faut écrire ensuite.
 *
 * <p>La raison tient à une propriété que le rapport de durées efface : <b>les postes n'ont pas tous
 * le même degré</b>. Un poste qui double quand la charge double est linéaire, et il restera vivable
 * ; un poste qui quadruple est quadratique, et il décidera de tout à dix mille. Or à mille entités
 * le second peut être <em>plus petit</em> que le premier, et un profil pris là désignera la mauvaise
 * cible avec une parfaite assurance.
 *
 * <p>Ce banc mesure donc la même charge à plusieurs tailles, et publie pour chaque poste <b>comment
 * il grandit</b>. C'est le seul renseignement qui permette de choisir quoi attaquer, et aucun banc du
 * dépôt ne le fournissait.
 *
 * <h2>Pourquoi des millisecondes et non des pourcentages</h2>
 *
 * <p>Le profileur rend des parts. Une part ne se compare pas entre deux paliers : un poste resté à
 * dix pour cent d'un tick passé de cinq à soixante millisecondes a été <b>multiplié par douze</b>, et
 * le profil affiche « 10 % » des deux côtés sans sourciller.
 *
 * <p>Ce qui se compare est la durée absolue — la part multipliée par la durée du tick. C'est elle
 * qu'on publie, et c'est d'elle qu'on tire l'exposant.
 *
 * <h2>L'exposant, et comment le lire</h2>
 *
 * <p>Entre deux paliers, la charge est multipliée par {@code r} et le poste par {@code c}. L'exposant
 * est {@code log(c) / log(r)} :
 *
 * <ul>
 *   <li><b>0</b> — le poste ne dépend pas du nombre d'entités. C'est un frais fixe ; il ne sera
 *       jamais le problème.</li>
 *   <li><b>1</b> — linéaire. Dix fois plus d'entités, dix fois plus de temps. C'est le régime normal
 *       et le mieux qu'on puisse espérer d'un poste par entité.</li>
 *   <li><b>2</b> — quadratique. Dix fois plus d'entités, <b>cent</b> fois plus de temps. Un poste de
 *       ce degré finit toujours par tout dominer, et il faut le tuer même s'il est petit aujourd'hui.</li>
 * </ul>
 *
 * <h2>Les trois règles d'instrument, qui s'appliquent ici aussi</h2>
 *
 * <p>Ce banc échantillonne : il hérite donc du défaut de l'échantillonnage. <b>Une méthode qui monte
 * haut désigne une région, pas une ligne</b> — le compilateur à la volée incorpore les méthodes
 * courtes, et le sommet de pile attribue alors à l'une le temps de plusieurs. Tout poste de ce
 * tableau est une piste de lecture dans les sources du jeu, jamais une conclusion.
 *
 * <p>Et les parts portent sur le <b>travail</b>, jamais sur le temps écoulé : le sommeil du serveur
 * entre deux ticks est compté à part par {@link Sampler}. Sans cela, un serveur qui tient ses ticks
 * ferait paraître tous ses postes quatre fois plus petits qu'ils ne sont.
 *
 * <h2>Ce qui fait refuser de conclure</h2>
 *
 * <p>Trois conditions, et chacune vient d'un banc de ce dépôt qui a rendu un chiffre faux :
 *
 * <ul>
 *   <li><b>La charge n'est pas née.</b> Des entités créées dans des chunks absents disparaissent
 *       aussitôt ; deux rapports ont annoncé « 0 entité » avant qu'on ne l'apprenne.</li>
 *   <li><b>Le serveur a dormi.</b> Un palier où le tick tient largement dans ses cinquante
 *       millisecondes ne profile presque rien, et les rares relevés obtenus sont du bruit.</li>
 *   <li><b>Le palier n'a pas grandi.</b> Si le second palier ne porte pas plus d'entités que le
 *       premier, l'exposant se calcule sur une division par zéro déguisée.</li>
 * </ul>
 */
public final class Cheptel {
    /**
     * Les tailles de troupeau éprouvées, dans l'ordre.
     *
     * <p>Mille, trois mille, dix mille. Le premier est l'échelle à laquelle ce dépôt a mesuré
     * jusqu'ici ; le dernier est celle dont l'utilisateur parle. Le palier du milieu n'est pas un
     * confort : <b>deux points donnent une droite quoi qu'il arrive</b>, et trois permettent de voir
     * si la droite en est une.
     */
    private static final int[] DEFAULT_TIERS = {1000, 3000, 10000};

    /** Ticks jetés après chaque bascule, le temps que le compilateur à la volée se refixe. */
    private static final int WARMUP = 80;

    /** Ticks échantillonnés par phase. */
    private static final int SAMPLE = 220;

    /** Ticks d'attente après la pose de la charge, le temps que les bêtes s'installent. */
    private static final int SETTLE = 60;

    /**
     * Budget de temps réel par phase, en nanosecondes.
     *
     * <p>Le même garde-fou que {@code Bench.BUDGET_NANOS}, et pour la même raison : une charge de dix
     * mille entités peut porter le tick bien au-delà des cinquante millisecondes qu'on lui suppose, et
     * un banc qui ne finit pas est pire qu'un banc qui se trompe — il ne dit rien du tout.
     */
    private static final long BUDGET_NANOS = 75_000_000_000L;

    /** Relevés minimaux avant de laisser l'échéance trancher. */
    private static final int LEAST = 20;

    /**
     * Part minimale du temps passée à travailler pour qu'un profil ait un sens.
     *
     * <p>En deçà, le serveur dort : les relevés obtenus sont trop peu nombreux et trop dispersés pour
     * qu'on en tire un classement. Le banc le dit au lieu de publier un tableau de bruit.
     */
    private static final double LEAST_BUSY = 0.10d;

    private enum Step { IDLE, SOW, SETTLING, WARM_OFF, MEASURE_OFF, WARM_ON, MEASURE_ON, DONE }

    /** Ce qu'un palier a rendu : les durées, le profil de chaque côté, et les demandeurs. */
    private record Tier(int wanted, int born,
            double msOff, double msOn, int keptOff, int keptOn,
            double busyOff, double busyOn,
            Map<String, Double> shareOff, Map<String, Double> shareOn,
            List<Sampler.Post> callersOff, List<Sampler.Post> callersOn) {}

    private static Step step = Step.IDLE;
    private static int tierIndex;
    private static int[] tiers = DEFAULT_TIERS;
    private static final List<Tier> RESULTS = new ArrayList<>();

    private static int left;
    private static long phaseOpened;
    private static long tickStart;
    private static final long[] TIMES = new long[SAMPLE];
    private static int filled;

    private static int born;
    private static double msOff;
    private static int keptOff;
    private static double busyOff;
    private static Map<String, Double> shareOff = Map.of();
    private static List<Sampler.Post> callersOff = List.of();

    private static Scene.Kind kind = Scene.Kind.RING;
    private static int radius = 96;

    private Cheptel() {}

    public static boolean running() {
        return step != Step.IDLE && step != Step.DONE;
    }

    /**
     * Lit les paliers demandés.
     *
     * <p>{@code LANTERNE_PALIERS=1000,3000,10000}. Le réglage existe pour pouvoir pousser plus haut
     * que ce que l'on croit tenable : c'est en montant à trois mille que l'épreuve de la marée a
     * cessé de ne rien prouver, et rien ne dit que dix mille soit le dernier mur.
     */
    private static int[] readTiers() {
        String raw = System.getenv("LANTERNE_PALIERS");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TIERS;
        }
        String[] parts = raw.split(",");
        List<Integer> read = new ArrayList<>();
        for (String part : parts) {
            try {
                int value = Integer.parseInt(part.trim());
                if (value > 0) {
                    read.add(value);
                }
            } catch (NumberFormatException malformed) {
                Lanterne.LOG.warn("[CHEPTEL] palier illisible, ignoré : {}", part);
            }
        }
        if (read.isEmpty()) {
            return DEFAULT_TIERS;
        }
        int[] out = new int[read.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = read.get(i);
        }
        return out;
    }

    private static int readRadius() {
        String raw = System.getenv("LANTERNE_RAYON");
        if (raw == null || raw.isBlank()) {
            return 96;
        }
        try {
            return Math.max(8, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException malformed) {
            return 96;
        }
    }

    public static void begin(MinecraftServer server) {
        tiers = readTiers();
        kind = Scene.parse(System.getenv("LANTERNE_SCENE"));
        radius = readRadius();
        RESULTS.clear();
        tierIndex = 0;
        step = Step.SOW;
        // La marée ajusterait les distances de vue et de simulation d'un palier à l'autre : les
        // paliers compareraient alors des mondes de tailles différentes en croyant comparer des
        // troupeaux de tailles différentes. Voir Bench.startHeadless, qui l'endort pour la même raison.
        Settings.setTide(false);
        Lanterne.LOG.info("[CHEPTEL] Épreuve armée — charge « {} », rayon {}, paliers {}.",
                kind, radius, java.util.Arrays.toString(tiers));
    }

    /** Ouvre le chronomètre du tick. Appelé depuis {@code ServerTickEvent.Pre}. */
    public static void beginTick() {
        if (running()) {
            tickStart = System.nanoTime();
        }
    }

    private static boolean overdue() {
        return filled >= LEAST && System.nanoTime() - phaseOpened > BUDGET_NANOS;
    }

    /** Fait avancer le protocole. Appelé depuis {@code ServerTickEvent.Post}. */
    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();
        long elapsed = System.nanoTime() - tickStart;

        switch (step) {
            case SOW -> {
                Herd.sweepEntities(level);
                born = Scene.build(level, kind, tiers[tierIndex], radius);
                Lanterne.LOG.info("[CHEPTEL] Palier {} — {} entité(s) demandée(s), {} née(s).",
                        tiers[tierIndex], tiers[tierIndex], born);
                step = Step.SETTLING;
                left = SETTLE;
            }
            case SETTLING -> {
                if (--left <= 0) {
                    openWarm(Step.WARM_OFF, false);
                }
            }
            case WARM_OFF -> {
                if (--left <= 0) {
                    openMeasure();
                    step = Step.MEASURE_OFF;
                }
            }
            case MEASURE_OFF -> {
                if (filled < SAMPLE) {
                    TIMES[filled++] = elapsed;
                }
                if (filled >= SAMPLE || overdue()) {
                    Sampler.stop();
                    msOff = median(filled) / 1e6d;
                    keptOff = filled;
                    busyOff = busyShare();
                    shareOff = shares();
                    callersOff = Sampler.callers();
                    // La phase témoin passe EN PREMIER, et la phase active ensuite. Le banc de vitesse
                    // a établi que la dérive de ce protocole favorise la première phase ; placer le mod
                    // en second le désavantage donc systématiquement. Se tromper dans ce sens ne
                    // produit jamais un gain inventé, et c'est la seule direction d'erreur acceptable
                    // quand on ne peut pas entrelacer — voir plus bas pourquoi on ne le peut pas.
                    openWarm(Step.WARM_ON, true);
                }
            }
            case WARM_ON -> {
                if (--left <= 0) {
                    openMeasure();
                    step = Step.MEASURE_ON;
                }
            }
            case MEASURE_ON -> {
                if (filled < SAMPLE) {
                    TIMES[filled++] = elapsed;
                }
                if (filled >= SAMPLE || overdue()) {
                    Sampler.stop();
                    RESULTS.add(new Tier(tiers[tierIndex], born,
                            msOff, median(filled) / 1e6d, keptOff, filled,
                            busyOff, busyShare(), shareOff, shares(),
                            callersOff, Sampler.callers()));
                    tierIndex++;
                    if (tierIndex < tiers.length) {
                        step = Step.SOW;
                    } else {
                        step = Step.DONE;
                        conclude(server, level);
                    }
                }
            }
            default -> { }
        }
    }

    private static void openWarm(Step next, boolean modOn) {
        Settings.setEnabled(modOn);
        step = next;
        left = WARMUP;
    }

    private static void openMeasure() {
        filled = 0;
        phaseOpened = System.nanoTime();
        Sampler.start(Thread.currentThread());
    }

    /**
     * La part du temps réellement travaillée pendant la phase qui s'achève.
     *
     * <p>{@link Sampler} compte à part les relevés pris pendant que le serveur dort. Ce rapport-là est
     * la première chose à lire d'un profil : un serveur qui dort les trois quarts du temps n'a pas de
     * problème, et les postes qu'on listerait ensuite seraient exacts et sans intérêt.
     */
    private static double busyShare() {
        int total = Sampler.totalSamples();
        return total == 0 ? 0d : Sampler.workingSamples() / (double) total;
    }

    /** Le profil de la phase qui s'achève, sous forme de parts par poste. */
    private static Map<String, Double> shares() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Sampler.Post post : Sampler.snapshot()) {
            out.put(post.label(), post.share());
        }
        return out;
    }

    private static double median(int count) {
        if (count <= 0) {
            return 0d;
        }
        long[] copy = java.util.Arrays.copyOf(TIMES, count);
        java.util.Arrays.sort(copy);
        int middle = count / 2;
        return count % 2 == 0 ? (copy[middle - 1] + copy[middle]) / 2d : copy[middle];
    }

    // ------------------------------------------------------------------ le rapport

    private static void conclude(MinecraftServer server, ServerLevel level) {
        Settings.setEnabled(true);
        say("──────────────────────────────────────────────────────────────");
        say(String.format(Locale.ROOT, "Charge « %s », rayon %d bloc(s). Le mod est ÉTEINT dans la "
                + "colonne « sans » et ALLUMÉ dans la colonne « avec ».", kind, radius));

        if (!usable()) {
            return;
        }

        say("");
        say("── Le tick, palier par palier ──");
        StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "%-34s", "entités visées"));
        StringBuilder bornRow = new StringBuilder(String.format(Locale.ROOT, "%-34s", "entités nées"));
        StringBuilder offRow = new StringBuilder(String.format(Locale.ROOT, "%-34s", "tick sans Lanterne (ms)"));
        StringBuilder onRow = new StringBuilder(String.format(Locale.ROOT, "%-34s", "tick avec Lanterne (ms)"));
        StringBuilder busyRow = new StringBuilder(String.format(Locale.ROOT, "%-34s", "part du temps travaillée (sans)"));
        for (Tier tier : RESULTS) {
            head.append(String.format(Locale.ROOT, "%10d", tier.wanted()));
            bornRow.append(String.format(Locale.ROOT, "%10d", tier.born()));
            offRow.append(String.format(Locale.ROOT, "%10.2f", tier.msOff()));
            onRow.append(String.format(Locale.ROOT, "%10.2f", tier.msOn()));
            busyRow.append(String.format(Locale.ROOT, "%9.0f%%", tier.busyOff() * 100d));
        }
        say(head.toString());
        say(bornRow.toString());
        say(offRow.toString());
        say(onRow.toString());
        say(busyRow.toString());

        Tier first = RESULTS.get(0);
        Tier last = RESULTS.get(RESULTS.size() - 1);
        double growth = (double) last.born() / Math.max(1, first.born());
        say(String.format(Locale.ROOT,
                "De %d à %d entités — soit ×%.2f — le tick sans Lanterne passe de %.2f à %.2f ms, "
                + "soit ×%.2f (exposant %.2f).",
                first.born(), last.born(), growth, first.msOff(), last.msOff(),
                last.msOff() / Math.max(0.001d, first.msOff()),
                exponent(first.msOff(), last.msOff(), growth)));

        ventilation("SANS Lanterne — le jeu tel quel", true, growth);
        ventilation("AVEC Lanterne — ce qui reste une fois tout dégradé", false, growth);

        demandeurs(last);

        say("");
        say("Rappel d'instrument : un sommet de pile désigne une RÉGION, pas une ligne. Le compilateur "
                + "à la volée incorpore les méthodes courtes, et leur temps est alors attribué à "
                + "celle qui les a absorbées. Chaque ligne ci-dessus est une piste de lecture dans "
                + "les sources du jeu, jamais une conclusion.");
        say("──────────────────────────────────────────────────────────────");
        server.halt(false);
    }

    /**
     * Le banc a-t-il de quoi conclure ?
     *
     * <p>Trois refus, chacun hérité d'un banc de ce dépôt qui avait conclu sans. Le refus est
     * explicite et nommé : un banc qui se tait laisse croire qu'il n'avait rien à dire.
     */
    private static boolean usable() {
        if (RESULTS.size() < 2) {
            say("REFUS DE CONCLURE : moins de deux paliers ont abouti. La croissance d'un poste ne se "
                    + "lit pas sur un point.");
            return false;
        }
        for (Tier tier : RESULTS) {
            if (tier.born() < tier.wanted() / 2) {
                say(String.format(Locale.ROOT,
                        "REFUS DE CONCLURE : le palier %d n'a fait naître que %d entité(s). Les "
                        + "chunks n'étaient pas là, et une charge absente se mesure très vite.",
                        tier.wanted(), tier.born()));
                return false;
            }
            if (tier.busyOff() < LEAST_BUSY) {
                say(String.format(Locale.ROOT,
                        "REFUS DE CONCLURE : au palier %d, le serveur a dormi %.0f %% du temps. Il "
                        + "n'y a pas de profil à tirer d'un serveur qui n'a rien à faire — il faut "
                        + "monter les paliers, pas publier ce tableau.",
                        tier.wanted(), (1d - tier.busyOff()) * 100d));
                return false;
            }
        }
        Tier first = RESULTS.get(0);
        Tier last = RESULTS.get(RESULTS.size() - 1);
        if (last.born() <= first.born()) {
            say(String.format(Locale.ROOT,
                    "REFUS DE CONCLURE : le dernier palier ne porte pas plus d'entités que le premier "
                    + "(%d contre %d). Il n'y a pas de croissance à mesurer.",
                    last.born(), first.born()));
            return false;
        }
        return true;
    }

    /**
     * Le tableau qui décide du travail : chaque poste, en millisecondes, à chaque palier.
     *
     * <p>Les postes retenus sont ceux qui pèsent au dernier palier — c'est lui qui pose le problème,
     * et un poste qui dominait à mille pour disparaître à dix mille n'intéresse personne.
     */
    private static void ventilation(String title, boolean without, double growth) {
        Tier first = RESULTS.get(0);
        Tier last = RESULTS.get(RESULTS.size() - 1);
        Map<String, Double> lastShares = without ? last.shareOff() : last.shareOn();
        double lastMs = without ? last.msOff() : last.msOn();

        say("");
        say("── Où part le temps — " + title + " ──");
        say(String.format(Locale.ROOT,
                "(millisecondes par tick, part du seul travail ; relevés retenus : %d)",
                without ? last.keptOff() : last.keptOn()));

        StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "%-44s", "poste"));
        for (Tier tier : RESULTS) {
            head.append(String.format(Locale.ROOT, "%10d", tier.wanted()));
        }
        head.append("   exposant");
        say(head.toString());

        Set<String> shown = new LinkedHashSet<>();
        for (Map.Entry<String, Double> entry : lastShares.entrySet()) {
            if (shown.size() >= 18) {
                break;
            }
            // Sous un pour cent du dernier palier, c'est du bruit : le publier allongerait le tableau
            // et donnerait à des postes inexistants l'air d'être des cibles.
            if (entry.getValue() < 0.01d) {
                break;
            }
            shown.add(entry.getKey());
        }

        for (String post : shown) {
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%-44s", trim(post)));
            for (Tier tier : RESULTS) {
                Map<String, Double> tierShares = without ? tier.shareOff() : tier.shareOn();
                double ms = tierShares.getOrDefault(post, 0d) * (without ? tier.msOff() : tier.msOn());
                row.append(String.format(Locale.ROOT, "%10.2f", ms));
            }
            Map<String, Double> firstShares = without ? first.shareOff() : first.shareOn();
            double msFirst = firstShares.getOrDefault(post, 0d) * (without ? first.msOff() : first.msOn());
            double msLast = lastShares.getOrDefault(post, 0d) * lastMs;
            row.append(String.format(Locale.ROOT, "  %s", degree(msFirst, msLast, growth)));
            say(row.toString());
        }
    }

    /**
     * Qui demande le poste surveillé, au plus haut palier.
     *
     * <h2>Pourquoi le tableau précédent ne suffit pas</h2>
     *
     * <p>La ventilation désigne le poste le plus lourd. Elle ne dit pas comment le supprimer, et pour
     * un poste quadratique c'est la seule chose qui compte : <b>une recherche spatiale ne s'accélère
     * pas, elle s'évite</b>. Il faut donc savoir qui la demande.
     *
     * <p>C'est la vue que {@link Sampler} tient déjà pour le journal, publiée ici aux deux états.
     * {@code LANTERNE_WATCH} la pointe où l'on veut — sans quoi elle observe consciencieusement un
     * problème résolu il y a trois semaines.
     */
    private static void demandeurs(Tier last) {
        if (last.callersOff().isEmpty() && last.callersOn().isEmpty()) {
            return;
        }
        say("");
        say(String.format(Locale.ROOT,
                "── Qui demande « %s » au palier %d ──", Sampler.watching(), last.wanted()));
        dumpCallers("sans Lanterne", last.callersOff());
        dumpCallers("avec Lanterne", last.callersOn());
    }

    private static void dumpCallers(String side, List<Sampler.Post> callers) {
        if (callers.isEmpty()) {
            say("  " + side + " : aucun relevé — la méthode surveillée n'a pas été vue.");
            return;
        }
        say("  " + side + " :");
        int shown = 0;
        for (Sampler.Post post : callers) {
            if (post.share() < 0.02d || shown >= 10) {
                break;
            }
            say(String.format(Locale.ROOT, "    %5.1f %%  %s  (%d)",
                    post.share() * 100d, post.label(), post.hits()));
            shown++;
        }
    }

    /**
     * L'exposant, et son nom.
     *
     * <p>Un poste absent du premier palier n'a pas d'exposant calculable : on le dit plutôt que de
     * rendre l'infini. C'est le cas d'un poste qui n'apparaît qu'à la charge haute — et c'est
     * précisément le genre de poste qu'on cherche, donc il mérite un mot et non un blanc.
     */
    private static String degree(double msFirst, double msLast, double growth) {
        if (growth <= 1.0001d) {
            return "—";
        }
        if (msFirst <= 0d) {
            return msLast > 0d ? "APPARU (absent au premier palier)" : "—";
        }
        double p = Math.log(msLast / msFirst) / Math.log(growth);
        String name;
        if (p < 0.4d) {
            name = "frais fixe";
        } else if (p < 1.35d) {
            name = "linéaire";
        } else if (p < 1.75d) {
            name = "sur-linéaire";
        } else {
            name = "QUADRATIQUE";
        }
        return String.format(Locale.ROOT, "%.2f  %s", p, name);
    }

    private static double exponent(double from, double to, double growth) {
        if (from <= 0d || growth <= 1.0001d) {
            return 0d;
        }
        return Math.log(to / from) / Math.log(growth);
    }

    private static String trim(String label) {
        return label.length() <= 43 ? label : label.substring(0, 43);
    }

    private static void say(String text) {
        Lanterne.LOG.info("[CHEPTEL] {}", text);
    }
}
