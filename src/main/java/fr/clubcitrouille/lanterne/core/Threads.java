package fr.clubcitrouille.lanterne.core;

/**
 * Combien de fils d'exécution le jeu a le droit de s'allouer — et pourquoi on n'y touche pas.
 *
 * <h2>Ce que ce module allait faire, et pourquoi il ne le fait plus</h2>
 *
 * <p>Il devait corriger un défaut supposé : sur un serveur loué, {@code availableProcessors()} rend
 * parfois le nombre de processeurs de la machine <b>hôte</b> plutôt que le quota du conteneur. Un
 * Xeon Gold 6230R en annonce vingt-six ; le jeu ouvrirait alors vingt-cinq fils de fond qui se
 * disputeraient le cœur unique sur lequel tourne aussi le fil du serveur.
 *
 * <p>La formule écrite pour cela réduisait le nombre de fils sur toutes les machines. Elle a été
 * retirée pour deux raisons, et la seconde est la bonne :
 *
 * <ul>
 *   <li><b>Le défaut est déjà corrigé en amont.</b> Depuis Java 10, la machine virtuelle respecte
 *       les quotas de conteneur par défaut ({@code UseContainerSupport}). Sur un VPS à un cœur
 *       correctement configuré, {@code availableProcessors()} rend donc 1 — et la formule du jeu,
 *       {@code clamp(1 - 1, 1, plafond)}, donne déjà un seul fil. Il n'y avait rien à gagner.</li>
 *   <li><b>Elle aurait coûté aux grosses machines.</b> Sur huit cœurs, elle serait passée de sept
 *       fils à deux. Un serveur qui a les cœurs doit pouvoir s'en servir : la génération de terrain
 *       et les entrées-sorties en profitent réellement.</li>
 * </ul>
 *
 * <p>Autrement dit : un module non mesuré qui brimait le cas le plus fréquent pour corriger un cas
 * qui ne se produit pas. La règle de ce projet vaut aussi contre ses propres idées — on ne garde que
 * ce qui gagne, et il fallait le constater plutôt que le supposer.
 *
 * <h2>Ce qui reste</h2>
 *
 * <p>Le réglage explicite, pour l'administrateur qui <em>sait</em> que sa machine virtuelle se
 * trompe : {@code LANTERNE_THREADS=2} dans l'environnement, ou {@code -Dlanterne.threads=2} au
 * lancement. Sans lui, le jeu décide seul, exactement comme sans ce mod.
 *
 * <p>C'est volontairement un réglage d'environnement et non une entrée du fichier de configuration :
 * {@code Util.BACKGROUND_EXECUTOR} est un champ {@code static final}, donc construit au tout premier
 * chargement de la classe {@code Util} — bien avant que NeoForge n'ait lu le moindre fichier de
 * configuration. Une option de config serait lue trop tard, et n'aurait aucun effet sans que rien ne
 * l'indique.
 *
 * <p>Le jeu offre déjà {@code -Dmax.bg.threads=N}, qui reste respecté : il sert de plafond, et nous
 * ne le franchissons jamais.
 */
public final class Threads {
    /** Le nom de la variable d'environnement qui impose le nombre de fils. */
    private static final String ENV = "LANTERNE_THREADS";

    /** La propriété système équivalente, pour ceux qui règlent tout dans la ligne de lancement. */
    private static final String PROPERTY = "lanterne.threads";

    /** Valeur de réglage qui rend la main au jeu. Sert au banc, qui ne peut pas basculer à chaud. */
    private static final String STAND_DOWN = "vanilla";

    private static int chosen = -1;

    private static boolean standDown;

    private Threads() {}

    /**
     * Vrai si l'on nous a demandé de ne rien faire.
     *
     * <p>Ce module ne peut pas être mesuré par le banc entrelacé : la réserve de fils est bâtie une
     * seule fois, au tout premier chargement de {@code Util}, et rien ne permet de la refaire au
     * milieu d'une partie. On le mesure donc en comparant <b>deux exécutions complètes</b> du
     * serveur, dont l'une avec {@code LANTERNE_THREADS=vanilla}.
     */
    public static boolean standDown() {
        forced();
        return standDown;
    }

    /**
     * Le nombre de fils de fond à accorder.
     *
     * @param vanillaCeiling le plafond que le jeu s'est lui-même fixé, jamais dépassé
     */
    public static int budget(int vanillaCeiling) {
        int forced = forced();
        if (forced > 0) {
            return Math.min(forced, vanillaCeiling);
        }
        // Personne n'a rien imposé : le jeu sait mieux que nous, et il tient déjà compte du quota
        // du conteneur. On rend sa propre valeur, inchangee.
        return vanillaCeiling;
    }

    /** Le nombre imposé, ou zéro si personne n'a rien imposé. Lu une seule fois. */
    private static int forced() {
        if (chosen >= 0) {
            return chosen;
        }
        chosen = 0;
        String raw = System.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(ENV);
        }
        if (raw != null && !raw.isBlank()) {
            String value = raw.trim();
            if (STAND_DOWN.equalsIgnoreCase(value)) {
                standDown = true;
                return 0;
            }
            try {
                chosen = Math.max(0, Integer.parseInt(value));
            } catch (NumberFormatException malformed) {
                chosen = 0;
            }
        }
        return chosen;
    }

    /** Ce qu'on dirait au journal : la valeur retenue et d'où elle vient. */
    public static String explain(int vanillaCeiling) {
        int announced = Runtime.getRuntime().availableProcessors();
        if (forced() > 0) {
            return String.format("%d fil(s) de fond — imposés par %s (le jeu en aurait pris %d).",
                    budget(vanillaCeiling), ENV, vanillaCeiling);
        }
        return String.format("%d fil(s) de fond — décidés par le jeu, sur %d processeur(s) annoncé(s). "
                + "Si votre hébergeur limite le quota sans que la machine virtuelle le voie, "
                + "imposez %s.", vanillaCeiling, announced, ENV);
    }
}
