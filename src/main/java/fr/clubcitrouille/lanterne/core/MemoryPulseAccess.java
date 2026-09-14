package fr.clubcitrouille.lanterne.core;

/**
 * Le battement des mémoires d'un cerveau.
 *
 * <p>Un simple compteur, incrémenté chaque fois qu'une mémoire est écrite, effacée ou expirée. Il ne
 * dit pas <em>ce qui</em> a changé — seulement que quelque chose a changé depuis la dernière fois
 * qu'on a regardé, ce qui suffit à savoir si une conclusion tirée plus tôt vaut toujours.
 *
 * <p>Implémentée par {@code MemoryPulseMixin} sur {@code Brain}, lue par
 * {@code BehaviorMemoryMixin} sur chaque comportement.
 */
public interface MemoryPulseAccess {
    /** Le nombre de modifications depuis la naissance du cerveau. */
    long lanterne$memoryPulse();

    /** Signale qu'une mémoire vient de changer. */
    void lanterne$bumpMemoryPulse();
}
