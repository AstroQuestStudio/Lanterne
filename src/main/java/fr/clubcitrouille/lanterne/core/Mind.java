package fr.clubcitrouille.lanterne.core;

/**
 * Le compteur des comportements composites parcourus sans streams.
 *
 * <p>Voir {@code GateBehaviorMixin}. Un seul nombre suffit : combien de fois on a remplacé deux
 * pipelines par deux boucles. S'il est petit, le module ne sert à rien ; s'il se compte en millions,
 * le temps gagné doit se voir au banc — et s'il ne s'y voit pas, c'est que le compilateur faisait
 * déjà le travail, comme il l'a fait deux fois aujourd'hui.
 */
public final class Mind {
    private static long gates;

    private Mind() {}

    public static void noteGate() {
        gates++;
    }

    public static long gates() {
        return gates;
    }

    public static void reset() {
        gates = 0L;
    }
}
