package fr.clubcitrouille.lanterne.core;

import net.minecraft.core.BlockPos;

/**
 * L'impasse : ne pas redemander tout de suite un chemin qu'on vient de prouver impossible.
 *
 * <h2>Ce qui coûte, sur un mob bloqué</h2>
 *
 * <p>Un loup qui ne peut pas rejoindre son maître par une porte fermée, un golem de fer qui vise une
 * position murée depuis sa dernière génération, un raid dont la cible du prochain point est hors
 * d'atteinte : chacun redemande le <b>même</b> chemin à chaque fois que son IA se reconsidère —
 * parfois à chaque tick. Chaque demande relance une recherche A* complète : allocation d'une
 * {@code PathNavigationRegion}, exploration de nœuds, pour retrouver exactement le {@code null}
 * qu'on avait déjà l'instant d'avant. Un mob authentiquement coincé ne s'arrête jamais de payer ce
 * prix — {@link Pasture} traite le cas voisin de l'errance volontaire qui tourne en rond, celui-ci
 * traite le cas d'une destination précise qui reste hors d'atteinte.
 *
 * <h2>Ce que ce module retient, et où</h2>
 *
 * <p>Le dernier point de départ et le dernier ensemble de cibles pour lesquels la recherche a échoué,
 * avec l'instant de l'échec — un seul souvenir, porté directement par l'objet
 * {@code PathNavigation} de ce mob (voir {@code PathfindMixin}), et non par une table partagée. Il
 * n'existe donc <b>aucun</b> risque de servir à un mob le résultat calculé pour un autre : deux
 * gabarits différents, deux jeux de règles de déplacement différents (nage, vol, échelle) ne peuvent
 * jamais se croiser, puisqu'ils ne partagent rien.
 *
 * <h2>Pourquoi une fenêtre courte suffit, et pourquoi elle est sûre</h2>
 *
 * <p>Si le même mob redemande <b>exactement</b> le même départ et les mêmes cibles dans la seconde
 * qui suit, on rend {@code null} sans relancer la recherche — la réponse est celle qu'on vient de
 * prouver, et rien n'a eu le temps de changer d'assez près pour la contredire la plupart du temps.
 * Le seul risque réel est qu'un joueur ouvre la porte <em>pendant</em> cette seconde : le mob le
 * remarque alors avec un délai borné à {@link #TTL_TICKS}, jamais avec une erreur — il ne prendra
 * jamais un chemin qui n'existe pas, il attendra tout au plus un peu avant de reconnaître celui qui
 * vient d'apparaître. C'est le même pari, dans le même sens, que celui de {@link Emballage} pour un
 * paquet de chunk : se tromper en direction du retard est sans conséquence, se tromper dans l'autre
 * sens ne le serait pas — et cette direction-ci est la seule que ce module puisse jamais prendre.
 *
 * <h2>Ce qui n'est délibérément pas couvert</h2>
 *
 * <p>Une cible qui se déplace — un mob qui poursuit un joueur en mouvement — change de position
 * quasiment à chaque tick, donc de clef, donc ne touche jamais le cache. Ce n'est pas un défaut :
 * c'est exactement le sous-ensemble que ce module ne prétend pas traiter, une cible mobile n'étant
 * jamais à proprement parler « la même impasse ».
 */
public final class Impasse {
    /**
     * Ticks pendant lesquels un échec récent reste opposable au même mob pour la même recherche.
     *
     * <p>Une seconde : assez pour épargner l'essentiel du travail d'un mob authentiquement bloqué,
     * dont l'IA se reconsidère bien plus souvent que cela, et assez court pour qu'un obstacle qui
     * disparaît vraiment redevienne visible avant qu'un joueur n'ait eu le temps de s'en agacer.
     */
    public static final long TTL_TICKS = 20L;

    private static long skipped;
    private static long recorded;

    private Impasse() {}

    /** Combine les cibles d'une recherche en une seule clef, insensible à leur ordre. */
    public static long hashTargets(Iterable<BlockPos> targets) {
        long acc = 0L;
        for (BlockPos pos : targets) {
            // Multiplication par une constante impaire pour disperser les positions voisines : deux
            // ensembles de cibles proches mais distincts ne doivent pas s'annuler par XOR direct.
            acc ^= pos.asLong() * 0x9E3779B97F4A7C15L;
        }
        return acc;
    }

    /** Une recherche évitée parce que l'échec venait d'être prouvé pour ce mob précis. */
    public static void noteSkipped() {
        skipped++;
    }

    /** Un nouvel échec vient d'être retenu comme référence pour ce mob. */
    public static void noteRecorded() {
        recorded++;
    }

    /** Recherches A* évitées depuis le démarrage — le gain, en clair. */
    public static long skipped() {
        return skipped;
    }

    /** Échecs retenus depuis le démarrage, qu'ils aient servi ou non par la suite. */
    public static long recorded() {
        return recorded;
    }

    public static void reset() {
        skipped = 0L;
        recorded = 0L;
    }
}
