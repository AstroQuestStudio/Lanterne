package fr.clubcitrouille.lanterne.core;

import java.util.HashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Les chunks sous la garde d'une Lanterne de Veille.
 *
 * <h2>Le seul ajout de ce mod qui rend le serveur plus rapide</h2>
 *
 * <p>L'Ancre coûte : elle garde un chunk chargé, donc tické, indéfiniment. La fusion des orbes ne
 * coûte ni ne rapporte rien à elle seule. Le balai supprime, ce qui est un aveu.
 *
 * <p>La Lanterne, elle, <b>enlève du travail</b> — et c'est le joueur qui décide où. Le point
 * d'accrochage n'est pas choisi au hasard :
 *
 * <pre>
 * public static void spawnCategoryForChunk(MobCategory c, ServerLevel level, LevelChunk chunk, …) {
 *     BlockPos start = getRandomPosWithin(level, chunk);   // &lt;- annulé avant ceci
 *     if (start.getY() &gt;= level.getMinY() + 1) {
 *         spawnCategoryForPosition(…);                      // &lt;- et avant ceci
 *     }
 * }
 * </pre>
 *
 * <p>Refuser à l'entrée épargne le tirage de position, la consultation de la carte des hauteurs, la
 * recherche du joueur le plus proche, les règles de placement, et jusqu'à douze créatures qu'il aurait
 * fallu créer puis faire vivre. Un chunk gardé ne coûte pas moins cher à l'apparition : il ne coûte
 * <b>rien du tout</b>.
 *
 * <p>C'est la thèse du mod rendue jouable. Le socle enlève le travail inutile ; la Lanterne laisse le
 * joueur en enlever davantage, chez lui, en connaissance de cause.
 *
 * <h2>Un compteur, et non un drapeau</h2>
 *
 * <p>Deux Lanternes dans un même chunk sont un cas ordinaire — une grande base en couvre plusieurs.
 * Un simple drapeau ferait qu'en retirer une lèverait la garde que l'autre tenait encore. On compte
 * donc, et l'on ne relâche qu'à zéro.
 *
 * <p>C'est exactement la faute que l'Ancre avait dû traiter, là par un balayage de la colonne. Ici le
 * bloc porte un bloc-entité, et un bloc-entité sait dire quand il arrive et quand il part : le compte
 * se tient tout seul.
 *
 * <h2>Ce que la garde ne couvre pas, et pourquoi on le dit</h2>
 *
 * <ul>
 *   <li><b>Les générateurs de créatures</b> — le bloc à monstres — restent actifs. C'est une
 *       construction du joueur, et l'éteindre serait lui retirer ce qu'il a bâti.</li>
 *   <li><b>Les raids et les invocations</b> passent par d'autres chemins, et les bloquer casserait
 *       des pans entiers du jeu.</li>
 *   <li><b>Les phantoms</b> ont leur propre générateur, hors de ce point d'accrochage.</li>
 *   <li><b>Les animaux</b> apparaissent normalement : on garde des monstres, pas du vivant.</li>
 * </ul>
 */
public final class Watch {
    /** Par dimension, le nombre de Lanternes présentes dans chaque chunk. */
    private static final Map<ResourceKey<Level>, Long2IntOpenHashMap> GUARDED = new HashMap<>();

    private static long prevented;

    private Watch() {}

    /** Une Lanterne de plus dans ce chunk. */
    public static void guard(ResourceKey<Level> dimension, ChunkPos where) {
        GUARDED.computeIfAbsent(dimension, key -> new Long2IntOpenHashMap())
                .addTo(where.pack(), 1);
    }

    /** Une Lanterne de moins. La garde tombe à zéro, pas avant. */
    public static void release(ResourceKey<Level> dimension, ChunkPos where) {
        Long2IntOpenHashMap inDimension = GUARDED.get(dimension);
        if (inDimension == null) {
            return;
        }
        long key = where.pack();
        int left = inDimension.addTo(key, -1) - 1;
        if (left <= 0) {
            inDimension.remove(key);
        }
    }

    /**
     * Ce chunk est-il sous garde ?
     *
     * <p>Appelé à chaque tentative d'apparition, pour chaque chunk éligible, à chaque tick. La table
     * est vide sur l'immense majorité des serveurs, et le test sort alors sur une comparaison — c'est
     * la seule forme acceptable pour un contrôle posé sur ce chemin.
     */
    public static boolean watched(ResourceKey<Level> dimension, ChunkPos where) {
        if (GUARDED.isEmpty()) {
            return false;
        }
        Long2IntOpenHashMap inDimension = GUARDED.get(dimension);
        return inDimension != null && inDimension.get(where.pack()) > 0;
    }

    public static void notePrevented() {
        prevented++;
    }

    /** Tentatives d'apparition refusées à l'entrée, pour le rapport. */
    public static long prevented() {
        return prevented;
    }

    /** Nombre de chunks sous garde, toutes dimensions confondues. */
    public static int guardedChunks() {
        int total = 0;
        for (Long2IntOpenHashMap inDimension : GUARDED.values()) {
            total += inDimension.size();
        }
        return total;
    }

    public static void reset() {
        GUARDED.clear();
        prevented = 0L;
    }
}
