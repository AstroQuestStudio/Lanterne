package fr.clubcitrouille.lanterne.core;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le ramasse-miettes, et ce qu'il coûte quand il n'y a qu'un cœur à partager.
 *
 * <h2>Pourquoi un cœur change la nature du problème</h2>
 *
 * <p>Sur une machine à huit cœurs, les fils concurrents de G1 travaillent <b>à côté</b> du tick :
 * ils consomment des cœurs que le serveur n'utilisait pas. Le tick ne les voit pas passer, et c'est
 * pourquoi G1 est un excellent choix par défaut.
 *
 * <p>Sur un cœur unique — la MyBox de l'hébergeur visé, un Xeon Gold 6230R dont une seule tranche
 * est louée — il n'y a pas de cœur inutilisé. Chaque milliseconde que le ramasseur prend est une
 * milliseconde que le tick n'a pas. Le travail « concurrent » devient du travail <em>volé</em>, et il
 * ne se voit dans aucun compteur de pause : les outils rapportent les pauses, pas le vol.
 *
 * <h2>Ce que la machine virtuelle décide déjà toute seule</h2>
 *
 * <p>Depuis Java 10, la machine virtuelle lit les quotas de conteneur et choisit le ramasseur
 * <b>série</b> en dessous de deux processeurs <em>et</em> de 1792 Mo de tas. Sur un hébergement
 * correctement isolé, elle fait donc déjà le bon choix, et ce module n'a rien à dire.
 *
 * <p>Le cas qui fait mal est l'autre : <b>un cœur et beaucoup de mémoire</b>. Les deux conditions
 * sont liées par un ET, si bien qu'un tas de quatre gigaoctets suffit à faire retenir G1 sur une
 * machine qui n'a qu'un cœur pour le faire tourner. C'est précisément la configuration vendue par
 * les hébergeurs Minecraft d'entrée de gamme, et personne ne prévient l'administrateur.
 *
 * <h2>Pourquoi ce module conseille au lieu d'agir</h2>
 *
 * <p>Le choix du ramasseur se fait sur la <b>ligne de commande</b>, avant que la moindre classe du
 * mod ne soit chargée. Aucun mod ne peut le changer — celui qui prétendrait le faire mentirait.
 *
 * <p>Ce que ce module peut faire, et que personne ne fait, c'est <b>constater la situation et donner
 * la ligne exacte</b>. Un administrateur qui découvre au démarrage « vous avez un cœur, un tas de
 * quatre gigaoctets et G1 : voici quoi coller » est mieux servi qu'un mod de plus qui promet d'être
 * rapide.
 *
 * <h2>Ce qu'il mesure, et pourquoi cette grandeur-là</h2>
 *
 * <p>{@code notes/serveur-un-coeur.md} constatait une impasse : comparer G1 et le ramasseur série
 * demande de comparer des temps de tick entre deux exécutions, or la variance d'une exécution à
 * l'autre sur la même charge va de 32 à 47 ms — plus que l'effet cherché. Le banc savait comparer
 * deux phases entrelacées ; il ne savait pas comparer deux démarrages.
 *
 * <p>La sortie n'est pas de mieux mesurer le tick, c'est de <b>mesurer autre chose</b> : le temps de
 * pause cumulé du ramasseur, que la machine virtuelle tient elle-même. C'est un compteur absolu,
 * insensible au bruit qui noie les temps de tick, et il répond directement à la question posée —
 * combien le ramasseur a-t-il pris. Deux exécutions deviennent comparables parce qu'on ne leur
 * demande plus le même genre de chiffre.
 */
public final class Creuset {

    /** En dessous de ce nombre de processeurs, le ramasseur série mérite d'être envisagé. */
    private static final int TIGHT_CORES = 2;

    /**
     * Le seuil de tas au-dessus duquel la machine virtuelle retient G1 malgré le cœur unique.
     *
     * <p>C'est la valeur que HotSpot emploie lui-même pour trancher entre « machine de serveur » et
     * « petite machine ». La reprendre ici garantit que l'avertissement ne se déclenche que dans le
     * cas où le choix automatique part effectivement de travers.
     */
    private static final long SERVER_CLASS_HEAP = 1792L * 1024L * 1024L;

    private static long baselineCount;
    private static long baselinePause;
    private static boolean marked;

    private Creuset() {}

    // ------------------------------------------------------------------ l'avis

    /**
     * Regarde la machine et le ramasseur en place, et le dit — avec la ligne à coller s'il le faut.
     */
    public static void appraise() {
        int cores = Runtime.getRuntime().availableProcessors();
        long maxHeap = Runtime.getRuntime().maxMemory();
        String collector = collector();

        Lanterne.LOG.info("[CREUSET] {} processeur(s) visible(s), tas plafonné à {} Mo, "
                + "ramasseur « {} ».", cores, maxHeap / 1048576L, collector);

        boolean tight = cores <= TIGHT_CORES;
        boolean concurrent = collector.contains("G1") || collector.contains("Shenandoah")
                || collector.contains("ZGC") || collector.contains("Z ");

        if (!tight) {
            // Rien à dire : sur plusieurs cœurs, le travail concurrent ne prend pas le tick.
            return;
        }

        if (!concurrent) {
            Lanterne.LOG.info("[CREUSET] Un cœur et un ramasseur non concurrent : c'est la bonne "
                    + "combinaison, rien à changer.");
            return;
        }

        // Le cas qui coûte, et qu'aucun hébergeur ne signale.
        Lanterne.LOG.warn("[CREUSET] ─────────────────────────────────────────────────────────");
        Lanterne.LOG.warn("[CREUSET] {} processeur(s) et un ramasseur concurrent ({}).",
                cores, collector);
        Lanterne.LOG.warn("[CREUSET] Sur un cœur unique, ses fils de fond ne travaillent pas à côté");
        Lanterne.LOG.warn("[CREUSET] du tick : ils le lui prennent. La machine virtuelle a retenu ce");
        if (maxHeap > SERVER_CLASS_HEAP) {
            Lanterne.LOG.warn("[CREUSET] ramasseur parce que le tas dépasse 1792 Mo — le seuil est un ET");
            Lanterne.LOG.warn("[CREUSET] entre le nombre de cœurs et la taille du tas, et c'est la");
            Lanterne.LOG.warn("[CREUSET] taille du tas qui l'a emporté ici.");
        }
        Lanterne.LOG.warn("[CREUSET] À essayer, dans les options Java de votre hébergeur :");
        Lanterne.LOG.warn("[CREUSET]");
        Lanterne.LOG.warn("[CREUSET]     -XX:+UseSerialGC");
        Lanterne.LOG.warn("[CREUSET]");
        Lanterne.LOG.warn("[CREUSET] Et NE PAS gonfler le tas : un tas énorme ne rend pas le serveur");
        Lanterne.LOG.warn("[CREUSET] plus rapide, il rend ses pauses plus longues.");
        Lanterne.LOG.warn("[CREUSET] Mesurez avant de garder : /lanterne memoire donne le temps de");
        Lanterne.LOG.warn("[CREUSET] pause cumulé, qui est le chiffre à comparer entre deux essais.");
        Lanterne.LOG.warn("[CREUSET] ─────────────────────────────────────────────────────────");
    }

    // ------------------------------------------------------------- le compteur

    /** Le nom du ou des ramasseurs en service, tel que la machine virtuelle le donne. */
    public static String collector() {
        List<String> names = new ArrayList<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            names.add(bean.getName());
        }
        return names.isEmpty() ? "inconnu" : String.join(" + ", names);
    }

    /** Ramassages effectués depuis le démarrage, toutes générations confondues. */
    public static long collections() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count > 0L) {
                total += count;
            }
        }
        return total;
    }

    /**
     * Millisecondes de pause cumulées depuis le démarrage.
     *
     * <p>Attention à ce que ce chiffre ne dit pas : il compte les <b>pauses</b>, c'est-à-dire les
     * arrêts complets du monde. Le travail concurrent de G1 n'y figure pas, alors que c'est lui qui
     * fait mal sur un cœur unique. Le chiffre sous-estime donc systématiquement G1 — ce qui est la
     * bonne façon de se tromper : si G1 perd malgré cet avantage, la conclusion tient.
     */
    public static long pauseMillis() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = bean.getCollectionTime();
            if (time > 0L) {
                total += time;
            }
        }
        return total;
    }

    /** Pose le repère à partir duquel on comptera. */
    public static void mark() {
        baselineCount = collections();
        baselinePause = pauseMillis();
        marked = true;
    }

    /** Ramassages depuis le repère. */
    public static long collectionsSinceMark() {
        return marked ? collections() - baselineCount : 0L;
    }

    /** Pause cumulée depuis le repère, en millisecondes. */
    public static long pauseSinceMark() {
        return marked ? pauseMillis() - baselinePause : 0L;
    }

    /** La ligne de comptes, pour un rapport ou un banc. */
    public static String ledger() {
        return String.format(Locale.ROOT,
                "ramasseur « %s » · %d ramassage(s), %d ms de pause cumulée depuis le démarrage",
                collector(), collections(), pauseMillis());
    }
}
