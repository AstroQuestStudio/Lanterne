package fr.clubcitrouille.lanterne.core;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Où va le temps d'une génération de chunk, étape par étape.
 *
 * <h2>La mesure qui manquait avant de toucher au générateur</h2>
 *
 * <p>Un chunk traverse douze étapes avant d'être servi au joueur : structures, biomes, bruit,
 * surface, grottes, décorations, lumière, apparitions. Le profil d'échantillonnage dit quelles
 * <em>méthodes</em> consomment le processeur, et il désignait un peloton serré — bruit de Perlin,
 * règles de surface, arbre de climat — sans qu'aucune ne dépasse six pour cent.
 *
 * <p>Un peloton serré est le pire cas pour qui veut multiplier un débit par sept : il n'y a pas de
 * gros poisson, et grappiller six pour cent douze fois ne fait pas un facteur sept.
 *
 * <p>Mais un profil par méthode ne dit pas la même chose qu'un profil par <b>étape</b>. Si les
 * décorations — arbres, minerais, herbe — pèsent la moitié du temps, alors optimiser le bruit ne
 * servira à rien, quel que soit le talent qu'on y mette. Et l'inverse est vrai.
 *
 * <p>Ce compteur tranche la question avant qu'on écrive une ligne de générateur. Il ne change rien :
 * il additionne des nanosecondes par nom d'étape.
 */
public final class Forge {
    private static final Map<String, AtomicLong> NANOS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();

    private Forge() {}

    public static void note(String status, long nanos) {
        NANOS.computeIfAbsent(status, key -> new AtomicLong()).addAndGet(nanos);
        COUNTS.computeIfAbsent(status, key -> new AtomicLong()).incrementAndGet();
    }

    /** Le relevé, trié du plus coûteux au moins coûteux. */
    public static String describe() {
        long total = 0L;
        for (AtomicLong tally : NANOS.values()) {
            total += tally.get();
        }
        if (total == 0L) {
            return "aucune étape mesurée";
        }
        var rows = new java.util.ArrayList<>(NANOS.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()));
        StringBuilder text = new StringBuilder();
        for (var row : rows) {
            long nanos = row.getValue().get();
            long count = COUNTS.getOrDefault(row.getKey(), new AtomicLong()).get();
            if (nanos * 100d / total < 0.5d) {
                continue;
            }
            text.append(String.format(Locale.ROOT, "%n[FORGE]  %5.1f %%  %-22s  %8.2f ms cumulées, "
                    + "%d passage(s), %.3f ms l'un",
                    nanos * 100d / total, row.getKey(), nanos / 1.0E6d, count,
                    nanos / 1.0E6d / Math.max(1L, count)));
        }
        return text.toString();
    }

    public static void reset() {
        NANOS.clear();
        COUNTS.clear();
    }
}
