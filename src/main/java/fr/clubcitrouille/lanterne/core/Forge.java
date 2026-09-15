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
 *
 * <h2>Ce qu'il a répondu, sur un cœur unique</h2>
 *
 * <pre>
 * 60,7 %  minecraft:noise             21,9 %  minecraft:biomes
 *  6,3 %  minecraft:surface            4,4 %  minecraft:features
 *  2,4 %  minecraft:initialize_light   1,8 %  minecraft:structure_starts
 * </pre>
 *
 * <p>Il a fallu le mesurer deux fois. La première version arrêtait le chronomètre au retour de la
 * méthode — or {@code noise} et {@code biomes} sont asynchrones, et paraissaient donc <b>gratuites</b>
 * là où elles pèsent en réalité plus des quatre cinquièmes. Le relevé initial désignait
 * {@code surface} à 47 % et {@code features} à 26 %, ce qui aurait envoyé le prochain chantier
 * exactement au mauvais endroit.
 *
 * <h2>Et pourquoi il n'y a pas de gros poisson à prendre</h2>
 *
 * <p>Le demandeur principal du bruit est {@code NoiseBasedChunkGenerator.doFill}, qui appelle la
 * règle de matériau une fois par bloc — quatre-vingt-dix-huit mille fois par chunk, sur toute la
 * hauteur du monde. Et {@code BlendedNoise.compute} coûte, par point, jusqu'à <b>quarante évaluations
 * de bruit de Perlin en trois dimensions</b> : huit octaves de bruit principal, seize de limite
 * basse, seize de limite haute.
 *
 * <p>La piste évidente était de court-circuiter les blocs qui ne produisent que de l'air, puisqu'un
 * chunk fait trois cent quatre-vingt-quatre blocs de haut pour un terrain qui en occupe une fraction.
 * Un compteur posé sur {@code MaterialRuleList.calculate} l'a chiffrée :
 *
 * <pre>
 * 253 811 068 appels, dont 31,1 % ne produisent que de l'air
 * </pre>
 *
 * <p>Trente et un pour cent, et non soixante-quinze. Un court-circuit parfait rapporterait donc au
 * mieux 0,31 × 0,607 — <b>dix-huit pour cent</b> de la génération. Réel, mais pas un facteur.
 *
 * <p>Ce compteur-là a été retiré aussitôt, et pour une raison qui vaut d'être retenue : instrumenter
 * un chemin appelé cent quarante mille fois par chunk a <b>doublé</b> le coût de l'étape qu'il
 * mesurait — 35,4 ms par chunk sans lui, 71,8 avec. Le rapport qu'il a rendu ne vaut donc que comme
 * proportion, jamais comme durée.
 *
 * <p>Conclusion : le coût de ce générateur est étalé sur un peloton serré et il est <b>intrinsèque</b>
 * — le bruit de Perlin <em>est</em> le terrain. Le seul facteur multiplicatif établi sur ce domaine
 * reste la pré-génération, mesurée à quinze fois. Voir {@code Pregen}.
 */
public final class Forge {
    private static final Map<String, AtomicLong> NANOS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();

    private Forge() {}

    public static void note(String status, long nanos) {
        NANOS.computeIfAbsent(status, key -> new AtomicLong()).addAndGet(nanos);
        COUNTS.computeIfAbsent(status, key -> new AtomicLong()).incrementAndGet();
    }

    /**
     * Le total cumulé d'une étape, en nanosecondes, ou zéro si elle n'a jamais été vue.
     *
     * <p>Sert de <b>dénominateur</b> à {@code lab/Filon}, qui ventile l'intérieur de l'étape du
     * bruit. Un sous-poste ne veut rien dire sans le total auquel il se rapporte — c'est la règle
     * d'instrument que ce dépôt s'est donnée après la chute libre du recensement.
     */
    public static long nanos(String status) {
        AtomicLong tally = NANOS.get(status);
        return tally == null ? 0L : tally.get();
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
