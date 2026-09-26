package fr.clubcitrouille.lanterne.chantier;

import java.util.List;

import net.minecraft.core.BlockPos;

/**
 * Un parcours de positions reprenable d'un tick à l'autre, toujours chunk par chunk : l'appelant
 * regarde {@link #chunk()} avant de toucher à {@link #courant()}, et attend le tick suivant si ce
 * chunk n'est pas encore chargé.
 */
interface Parcours {
    boolean fini();

    /** Le chunk (clé {@code ChunkPos.pack}) de la position courante. */
    long chunk();

    /** Les prochains chunks du parcours, à demander d'avance. */
    List<Long> aVenir(int n);

    BlockPos.MutableBlockPos courant();

    /** Rang d'origine de la position courante (liste), ou -1 (boîte). */
    default int rang() {
        return -1;
    }

    void avancer();
}
