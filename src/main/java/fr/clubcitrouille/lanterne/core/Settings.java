package fr.clubcitrouille.lanterne.core;

import java.util.Locale;

/**
 * Les interrupteurs, et pourquoi ils sont le module le plus important du mod.
 *
 * <h2>Quatre plans démolis par la mesure</h2>
 *
 * <p>Ce mod a commencé par des idées séduisantes, défendables, et fausses.
 *
 * <p><b>La compensation des ticks aléatoires.</b> Ne tirer qu'une fois sur seize, mais seize fois
 * plus. La loi est préservée — c'est exact — et le gain est <b>nul</b> : même nombre total de
 * tirages, avant comme après.
 *
 * <p><b>Le ralentissement des entonnoirs.</b> Un entonnoir tické une fois sur seize transfère seize
 * fois moins d'objets, et les chunks concernés sont dans le rayon de simulation — ce sont donc des
 * fermes <em>en marche</em>. On ne les aurait pas optimisées, on les aurait cassées.
 *
 * <p><b>Un débordement d'entier</b> a neutralisé le recensement entier pendant six bancs, sans que
 * rien ne le signale. Le mod compilait, démarrait, journalisait, et ne faisait rien.
 *
 * <p>Ce qui les a écartés n'est ni l'expérience ni le flair : une multiplication posée avant de
 * coder, et une ligne de rapport. <b>Et ce qui vaut pour un plan vaut pour un résultat.</b>
 *
 * <h2>Un interrupteur par optimisation, et non un seul pour tout</h2>
 *
 * <p>Un interrupteur global suffit à prouver que le mod sert à quelque chose. Il ne dit pas
 * <em>lequel</em> de ses modules y est pour quelque chose — et c'est très exactement le reproche
 * qu'on fait à un assemblage de vingt mods d'optimisation.
 *
 * <p>Le cas s'est présenté aussitôt : le cache du profileur, qui vise seize pour cent du temps
 * mesuré, n'a rien changé au résultat d'ensemble. Non qu'il soit inutile, mais parce que le niveau
 * de détail écarte déjà la plupart des appels qu'il aurait accélérés. Sans interrupteurs séparés, on
 * aurait conclu « inutile » et jeté un module qui vaut peut-être beaucoup une fois seul.
 *
 * <p>Chaque module se mesure donc seul, et le rapport porte sur ce qu'il fait, pas sur ce qu'on
 * espérait qu'il fasse.
 */
public final class Settings {
    /** Coupe tout, d'un coup. Sert au banc et au diagnostic. */
    private static boolean master = true;

    /** Le niveau de détail appliqué au tick des entités. Le cœur du mod. */
    private static boolean lod = true;
    /** L'espacement des paquets de position pour les entités lointaines. */
    private static boolean network = true;
    /** Le cache du profileur intégré, qui évite une recherche par entité et par tick. */
    private static boolean profilerCache = true;
    /** La dégradation par densité : ce qui est noyé dans le nombre ne se distingue pas. */
    private static boolean density = true;
    /** Le plafond de poussées dans les tas d'entités, où le coût est quadratique. */
    private static boolean collisions = true;
    /** Le court-circuit de bousculade pour les amas immobiles. */
    private static boolean jam = true;

    private Settings() {}

    /**
     * Le mod agit-il ?
     *
     * <p>Éteint, <b>rien</b> n'est dégradé : le recensement continue de tourner pour que le rapport
     * reste lisible, mais aucune entité ne perd un tick. C'est ce qui rend la comparaison honnête —
     * on mesure deux fois le même monde, pas deux mondes différents.
     */
    public static boolean enabled() {
        return master;
    }

    public static void setEnabled(boolean value) {
        master = value;
    }

    public static boolean lod() {
        return master && lod;
    }

    public static boolean network() {
        return master && network;
    }

    public static boolean profilerCache() {
        return master && profilerCache;
    }

    public static boolean density() {
        return master && density;
    }

    public static boolean collisions() {
        return master && collisions;
    }

    public static boolean jam() {
        return master && jam;
    }

    /**
     * Lit la sélection de modules depuis l'environnement.
     *
     * <p>{@code LANTERNE_MODULES="lod"} n'active que le niveau de détail ; {@code "lod,network"} en
     * active deux ; {@code "none"} n'en active aucun. Absent, tout est actif.
     *
     * <p>C'est ce qui permet d'attribuer un gain à un module plutôt qu'au mod dans son ensemble —
     * et donc de savoir ce qu'on garde.
     */
    public static void configureFromEnvironment() {
        String raw = System.getenv("LANTERNE_MODULES");
        if (raw == null || raw.isBlank()) {
            return;
        }
        String wanted = raw.toLowerCase(Locale.ROOT);
        lod = wanted.contains("lod");
        network = wanted.contains("network") || wanted.contains("reseau");
        profilerCache = wanted.contains("profiler");
        density = wanted.contains("density") || wanted.contains("densite");
        collisions = wanted.contains("collision");
        jam = wanted.contains("jam");
    }

    /** Ce qui est actif, pour l'en-tête du rapport. */
    public static String describe() {
        if (!master) {
            return "tout éteint";
        }
        StringBuilder text = new StringBuilder();
        if (lod) {
            text.append("lod ");
        }
        if (network) {
            text.append("réseau ");
        }
        if (profilerCache) {
            text.append("profileur ");
        }
        if (density) {
            text.append("densité ");
        }
        if (collisions) {
            text.append("collisions ");
        }
        if (jam) {
            text.append("amas ");
        }
        return text.isEmpty() ? "aucun module" : text.toString().trim();
    }
}
