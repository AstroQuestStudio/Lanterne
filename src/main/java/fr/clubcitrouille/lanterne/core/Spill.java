package fr.clubcitrouille.lanterne.core;

/**
 * Le compteur des tableaux vides non fabriqués.
 *
 * <p>Voir {@code InsideEffectsMixin}. Ce compteur ne prouve rien à lui seul — deux modules ont déjà
 * été retirés après avoir supprimé un million d'objets pour zéro milliseconde, la machine virtuelle
 * les ayant déjà supprimés d'elle-même.
 *
 * <p>Il sert à poser la multiplication : <b>compte × taille</b>. C'est la seule opération qui
 * distingue un vrai poste du chiffre d'un profileur — celui-ci s'était trompé d'un facteur
 * soixante-dix sur ce même module.
 */
public final class Spill {
    private static long skipped;

    private Spill() {}

    public static void noteSkipped() {
        skipped++;
    }

    public static long skipped() {
        return skipped;
    }

    /** Ce que ces tableaux auraient pesé : seize octets pièce, en-tête d'objet compris. */
    public static double megabytes() {
        return skipped * 16d / 1048576d;
    }

    public static void reset() {
        skipped = 0L;
    }
}
