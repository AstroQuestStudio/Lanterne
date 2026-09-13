package fr.clubcitrouille.lanterne.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Le compteur des allers-retours de la génération de chunks.
 *
 * <p>Voir {@code ChunkMapMixin} pour ce qu'on cherche à établir. Les compteurs sont atomiques parce
 * qu'ils sont incrémentés depuis le bassin de travail aussi bien que depuis le fil du serveur.
 */
public final class Relay {
    private static final AtomicLong SUBMISSIONS = new AtomicLong();
    private static final AtomicLong DRAINS = new AtomicLong();

    private Relay() {}

    /** Une tâche de génération remise dans la file du répartiteur. */
    public static void noteSubmission() {
        SUBMISSIONS.incrementAndGet();
    }

    /** Une vidange de la file d'attente, qui a lieu une fois par tick du serveur. */
    public static void noteDrain() {
        DRAINS.incrementAndGet();
    }

    public static long submissions() {
        return SUBMISSIONS.get();
    }

    public static long drains() {
        return DRAINS.get();
    }

    public static void reset() {
        SUBMISSIONS.set(0L);
        DRAINS.set(0L);
    }
}
