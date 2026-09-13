package fr.clubcitrouille.lanterne.core;

/**
 * Le compteur des chunks de points d'intérêt parcourus.
 *
 * <p>Voir {@code PoiStreamMixin}. Ce compteur sert à une chose précise : rendre possible la
 * multiplication que ce projet a appris à poser <b>avant</b> de coder.
 *
 * <p>Le nombre d'appels, multiplié par ce qu'on épargne à chacun, donne l'ordre de grandeur du gain.
 * Sans lui, on ne dispose que du chiffre d'un profileur — et les deux profileurs de ce laboratoire
 * se sont trompés d'un facteur soixante-dix chacun, dans des directions opposées.
 */
public final class Poi {
    private static long chunks;

    private Poi() {}

    public static void noteChunk() {
        chunks++;
    }

    /** Chunks parcourus sans emballer vingt-quatre entiers. */
    public static long chunks() {
        return chunks;
    }

    /** Entiers non emballés : le compte multiplié par le nombre de sections d'un monde ordinaire. */
    public static long boxesSpared() {
        return chunks * 24L;
    }

    public static void reset() {
        chunks = 0L;
    }
}
