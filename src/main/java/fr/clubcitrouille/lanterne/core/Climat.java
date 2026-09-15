package fr.clubcitrouille.lanterne.core;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.level.biome.Climate;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le climat : la descente de l'arbre des biomes lit ses bornes dans un tableau de {@code long}.
 *
 * <h2>Ce qu'on cherchait, et ce qu'on a trouvé</h2>
 *
 * <p>L'étape des biomes pèse <b>22,6 %</b> du temps de génération sur un seul fil — le régime de la
 * machine visée. Le relevé par échantillonnage des fils de travail y désigne un seul nom :
 *
 * <pre>
 * 4,2 %  Climate$RTree$SubTree.search
 * </pre>
 *
 * <p>Deux pistes avaient été proposées pour ce module. <b>Les deux se sont révélées sans objet</b>,
 * et la démonstration de leur inutilité vaut mieux que le code qu'elles auraient produit. Ce qui
 * reste — et que ce module fait — est d'un autre ordre : la recherche ne calcule pas trop, elle
 * <em>lit mal</em>.
 *
 * <h2>Piste écartée n°1 : mémoïser {@code RTree.search}. Sa prémisse est fausse deux fois.</h2>
 *
 * <p>L'idée était : « les mêmes six paramètres climatiques rendent toujours le même biome, donc un
 * cache est exact par construction ». Ni la pureté ni la répétition ne tiennent.
 *
 * <p><b>Première raison : {@code search} n'est pas une fonction pure de ses paramètres.</b> Elle
 * prend un second argument, invisible depuis l'appelant : le <em>dernier résultat du fil</em>,
 * rangé dans un {@code ThreadLocal} et passé comme borne initiale à la séparation-évaluation
 * ({@code Climate.java}, lignes 387-392). Or l'élagage de {@code SubTree.search} est <b>strict</b> :
 *
 * <pre>
 * if (minDistance &gt; childDistance) { … }      // ligne 455
 * </pre>
 *
 * <p>Un enfant dont la borne inférieure <em>égale</em> le meilleur connu n'est donc jamais visité.
 * Tant que les distances sont deux à deux distinctes, cela ne change rien — la feuille la plus
 * proche est atteinte quelle que soit la borne de départ, parce que tout ancêtre de cette feuille a
 * une distance de boîte inférieure ou égale à la sienne, donc strictement inférieure au meilleur
 * connu tant qu'on ne l'a pas trouvée. <b>Mais dès qu'il y a égalité, c'est la feuille héritée de
 * l'appel précédent qui est rendue</b>, et l'égalité n'est pas un cas d'école : deux points
 * climatiques qui partagent une frontière donnent tous deux une distance <b>nulle</b> à un échantillon
 * posé dessus. Le résultat de {@code search} dépend donc de l'<em>historique du fil</em>. Mémoïser
 * sur les six longs seuls ne serait exact que si aucune égalité n'existait jamais — ce qu'on ne peut
 * pas prouver — et servir depuis le cache sauterait la mise à jour du {@code ThreadLocal}, ce qui
 * changerait la borne de <em>tous les appels suivants</em>.
 *
 * <p><b>Seconde raison, et elle suffirait seule : la clé ne se répète jamais.</b> La quantification
 * n'est pas grossière — {@code quantizeCoord} multiplie par dix mille ({@code Climate.java},
 * ligne 69), donc le pas vaut un dix-millième. Et surtout, l'un des six paramètres est la
 * <b>profondeur</b>, qui dépend de la hauteur :
 *
 * <pre>
 * depth = yClampedGradient(-64, 320, 1.5, -1.5) + offset   // NoiseRouterData.java, ligne 195
 * </pre>
 *
 * <p>La pente vaut 3,0 / 384 = 0,0078125 par bloc, soit <b>0,03125 par pas de quart</b> (quatre
 * blocs), soit <b>312,5 unités quantifiées</b>. Deux hauteurs voisines d'une même colonne ne donnent
 * donc jamais la même clé. Un chunk de surmonde échantillonne 4 × 4 colonnes de quarts sur 24
 * sections, soit 16 × 96 = <b>1536 points, et 1536 clés deux à deux distinctes</b>. Le taux de succès
 * d'un cache exact sur ce chemin est <b>exactement zéro</b>, pour toute taille de cache.
 *
 * <p>Il n'est pas nul partout : dans le Nid — où {@code continents}, {@code erosion}, {@code depth}
 * et {@code ridges} valent tous {@code DensityFunctions.zero()} ({@code NoiseRouterData.java},
 * lignes 409-425) et où température et végétation ne dépendent pas de la hauteur — les 32 quarts
 * d'une colonne rendent le <em>même</em> point climatique, et un cache toucherait 31 fois sur 32.
 * Mais l'arbre du Nid compte cinq feuilles : sa descente ne coûte rien, et il n'y a rien à y gagner.
 *
 * <h2>Piste écartée n°2 : hisser le {@code ThreadLocal} hors de la boucle</h2>
 *
 * <p>{@code RTree.search} fait un {@code get()} et un {@code set()} par position. Sur les 1536
 * positions d'un chunk cela fait 3072 opérations de {@code ThreadLocal}. À cinq nanosecondes pièce —
 * une lecture de table de hachage à sondage linéaire, déjà chaude — cela représente <b>quinze
 * microsecondes</b> par chunk. L'étape des biomes en coûte <b>25,1 milli</b>secondes sur un fil
 * ({@code notes/cps-generation.md}, §3). La piste vaut donc <b>0,06 % de l'étape</b>, soit 0,014 % de
 * la génération : cent fois sous le seuil de dérive du laboratoire. Ce n'est pas une optimisation,
 * c'est du bruit.
 *
 * <p>Et elle n'est même pas gratuite à écrire : hisser l'état au niveau du chunk demanderait de le
 * faire descendre jusqu'à {@code RTree.search}, qui ne reçoit que le point visé. Il faudrait ou bien
 * changer sa signature, ou bien le relire depuis… un {@code ThreadLocal}. Sans compter qu'il faudrait
 * préserver à l'identique la <em>chaîne</em> des derniers résultats — voir la première raison
 * ci-dessus : la couper change le terrain.
 *
 * <h2>Ce que ce module fait : sept déréférencements en moins par nœud visité</h2>
 *
 * <p>La descente ne fait qu'une chose, des dizaines de fois par position : mesurer la distance d'un
 * point à la boîte d'un nœud ({@code Climate.java}, lignes 420-428).
 *
 * <pre>
 * protected long distance(long[] target) {
 *     long distance = 0L;
 *     for (int i = 0; i &lt; 7; i++) {
 *         distance += Mth.square(this.parameterSpace[i].distance(target[i]));
 *     }
 *     return distance;
 * }
 * </pre>
 *
 * <p>{@code parameterSpace} est un <b>tableau d'objets</b>. Chaque tour lit une référence, saute à un
 * {@code Climate.Parameter} — un record de deux {@code long} — et y lit deux champs. Sept sauts de
 * pointeur par nœud, vers sept objets que le ramasse-miettes a pu disperser n'importe où.
 *
 * <p>L'empreinte mémoire de cette seule mesure, par nœud :
 *
 * <pre>
 * vanilla : Parameter[7]  48 octets  +  7 × Parameter (32 octets)  =  272 octets, 8 objets
 * ici     : long[14]     128 octets                                =  128 octets, 1 objet, 2 lignes de cache
 * </pre>
 *
 * <p>On aplatit donc les quatorze bornes du nœud dans un seul {@code long[]}, une fois pour toutes,
 * à la construction de l'arbre. La boucle lit alors deux {@code long} <b>voisins</b> au lieu de
 * suivre une référence vers un objet séparé. Le calcul est le même ; c'est le chemin qui y mène qui
 * change.
 *
 * <p>Un détail s'y ajoute, et il vaut un septième de la boucle. La septième dimension du point visé
 * est <b>toujours zéro</b> : {@code TargetPoint.toParameterArray} l'écrit en dur ({@code Climate.java},
 * ligne 555), parce que le décalage ({@code offset}) est une propriété des points climatiques et non
 * des échantillons. Sa contribution est donc une <b>constante par nœud</b>, calculée une fois à
 * l'aplatissement. On ne s'en sert que si la cible vaut bien zéro — un seul test — et on retombe
 * autrement sur le calcul général.
 *
 * <h2>Pourquoi c'est exact, et pas seulement « équivalent »</h2>
 *
 * <p>L'argument est court parce qu'il n'y a rien à démontrer de subtil : <b>on ne change aucune
 * opération arithmétique, aucun ordre de parcours, aucune borne</b>. L'expression évaluée par tour de
 * boucle est copiée mot pour mot de {@code Climate.Parameter.distance} — {@code above > 0 ? above :
 * max(below, 0)} — avec {@code above} et {@code below} tirés des mêmes deux nombres. La somme se fait
 * dans le même ordre, sur des {@code long} dont aucune valeur n'approche le débordement (l'écart
 * maximal entre deux coordonnées quantifiées est de l'ordre de 10<sup>5</sup>, son carré de
 * 10<sup>10</sup>, et sept de ces carrés tiennent dans un {@code long} avec neuf chiffres de marge).
 *
 * <p>Le raccourci de la septième dimension est gardé sous condition explicite : il ne s'applique que
 * lorsque {@code target[6] == 0}, c'est-à-dire exactement dans le cas où il a été démontré juste.
 *
 * <p>Comme la feuille rendue dépend aussi de la borne héritée (voir la piste n°1), l'exactitude des
 * distances ne suffirait pas si le module touchait à la chaîne des derniers résultats. <b>Il n'y
 * touche pas</b> : il ne connaît pas le {@code ThreadLocal}, il ne connaît qu'un nœud et un point.
 *
 * <h2>Comment le mesurer, parce que le banc de débit ne le verra pas</h2>
 *
 * <p>Il faut dire d'avance ce que ce module peut valoir, sans quoi on croira l'avoir mesuré. La
 * descente d'arbre pèse 4,2 % de la génération, soit <b>18,6 % de l'étape des biomes</b> — et c'est
 * un plafond : même rendue gratuite, elle ne rendrait pas davantage. Si l'aplatissement divise par
 * deux le coût de {@code distance}, l'étape des biomes recule d'environ <b>9 %</b> et la génération
 * entière d'environ <b>2 %</b>.
 *
 * <p>Deux pour cent sont <b>invisibles</b> au banc de débit, dont le seuil de dérive est de ×1,08.
 * L'instrument qui convient existe déjà : {@code LANTERNE_FILON=1} chronomètre
 * {@code ChunkAccess.fillBiomesFromNoise} à la nanoseconde et cumule sur des centaines de chunks
 * ({@code lab/Filon}, poste {@code BIOMES}). C'est là qu'un recul de neuf pour cent se voit, et nulle
 * part ailleurs. Annoncer ce module sur un chiffre de chunks par seconde serait annoncer du bruit.
 *
 * <h2>Le contrôle, et l'interrupteur qui le fait échouer</h2>
 *
 * <p>{@code LANTERNE_CLIMAT_AUDIT=1} recalcule, à chaque nœud visité, la distance <em>à la manière de
 * vanilla</em> depuis les objets {@code Parameter} d'origine, et la compare à celle qu'on vient de
 * rendre. Ce n'est pas un échantillon : c'est <b>chaque appel</b>, des dizaines de millions par
 * grille de chunks. Le premier écart est journalisé avec ses deux valeurs.
 *
 * <p>Une épreuve qui ne peut pas échouer ne prouve rien. {@code LANTERNE_BREAK_CLIMAT=1} échange la
 * température et l'humidité dans le tableau aplati : le contrôle <b>doit</b> alors se remplir
 * d'écarts, et le monde engendré doit changer de biomes.
 *
 * <h2>Ce que ce module ne fait pas, et où est le vrai gisement de l'étape des biomes</h2>
 *
 * <p>La descente d'arbre pèse 4,2 % du temps de génération. L'étape des biomes en pèse 22,6 %. <b>Les
 * dix-huit points qui restent ne sont pas dans l'arbre</b> : ils sont dans {@code Climate.Sampler.sample}
 * ({@code Climate.java}, lignes 479-492), qui alloue un contexte et évalue six fonctions de densité
 * par position.
 *
 * <p>Et il y a là une redondance nette, qui n'est pas dans ce module parce qu'elle n'est pas dans ce
 * fichier. Dans le routeur du surmonde, {@code continents}, {@code erosion} et {@code ridges} sont
 * enveloppés de {@code flatCache} — un cache par colonne ({@code NoiseRouterData.java}, lignes 92, 95
 * et 98). <b>{@code temperature} et {@code vegetation} ne le sont pas</b> (lignes 340-345) : ce sont
 * des {@code shiftedNoise2d} nus. Or {@code shiftedNoise2d} pose {@code yScale = 0} — leur valeur ne
 * dépend <em>pas</em> de la hauteur. Elles sont donc recalculées, bruit de Perlin compris, pour
 * chacun des 96 quarts de hauteur d'une colonne, alors qu'une seule évaluation suffirait :
 * <b>1536 évaluations par chunk là où 16 auraient le même résultat</b>, pour chacune des deux.
 *
 * <p>Ce serait le gros morceau de l'étape. Il demande de <em>certifier</em> l'indépendance en hauteur
 * plutôt que de la supposer — un tiers peut remplacer ces fonctions par un arbre quelconque — et il
 * se pose sur le chemin du module {@code calque}, pas sur celui-ci. Il est signalé ici pour qu'on ne
 * le cherche pas deux fois.
 */
public final class Climat {
    /**
     * Le contrôle de conformité, nœud par nœud et appel par appel.
     *
     * <p>{@code static final} et lu depuis une variable d'environnement : éteint, la machine
     * virtuelle replie la constante et <b>tout le code de contrôle disparaît</b> de la boucle chaude.
     * C'est la condition pour qu'un contrôle aussi bavard puisse exister dans une méthode appelée des
     * dizaines de millions de fois par grille.
     */
    public static final boolean AUDIT = "1".equals(System.getenv("LANTERNE_CLIMAT_AUDIT"));

    /**
     * L'interrupteur de panne : la température et l'humidité échangées dans le tableau aplati.
     *
     * <p>Voir la javadoc de la classe. Sans lui, {@code AUDIT} serait une épreuve qui ne peut pas
     * échouer, donc une épreuve qui ne prouve rien.
     */
    public static final boolean BROKEN = "1".equals(System.getenv("LANTERNE_BREAK_CLIMAT"));

    /** Nœuds d'arbre climatique dont les bornes ont été aplaties. */
    private static final LongAdder flattened = new LongAdder();

    /** Nœuds dont la forme n'était pas celle attendue, et qu'on a laissés à vanilla. */
    private static final LongAdder declined = new LongAdder();

    /** Distances comparées à celles de vanilla, sous {@code AUDIT}, et écarts trouvés. */
    private static final LongAdder checked = new LongAdder();
    private static final LongAdder faults = new LongAdder();

    /** Le premier écart se journalise ; les suivants se comptent. */
    private static final AtomicBoolean cried = new AtomicBoolean();

    /** La première fois qu'on passe ce cap sans écart, on le dit une fois. */
    private static final long SAY_AFTER = 1_000_000L;
    private static final AtomicBoolean said = new AtomicBoolean();

    private Climat() {}

    /**
     * Aplatit les sept intervalles d'un nœud en quatorze {@code long} contigus.
     *
     * <p>Rend {@code null} si la forme n'est pas celle attendue — sept dimensions, ni plus ni moins.
     * Vanilla suppose ce sept en dur dans sa propre boucle et {@code RTree.create} refuse tout autre
     * nombre ({@code Climate.java}, lignes 260-263) ; on ne l'impose donc pas, on le vérifie, et un
     * nœud d'une autre forme retombe sur le chemin d'origine plutôt que de rendre un faux.
     *
     * @param espace les intervalles du nœud, dans l'ordre où vanilla les lit
     * @return les bornes {@code [min0, max0, min1, max1, …]}, ou {@code null} pour laisser vanilla
     */
    public static long @Nullable [] aplatir(Climate.Parameter[] espace) {
        if (espace.length != 7) {
            declined.increment();
            return null;
        }
        long[] bornes = new long[14];
        for (int dimension = 0; dimension < 7; dimension++) {
            Climate.Parameter intervalle = espace[dimension];
            // L'interrupteur de panne échange les deux premières dimensions — température et
            // humidité. Le tableau reste bien formé : ce qui change est le monde engendré.
            int place = BROKEN && dimension < 2 ? 1 - dimension : dimension;
            bornes[place * 2] = intervalle.min();
            bornes[place * 2 + 1] = intervalle.max();
        }
        flattened.increment();
        return bornes;
    }

    /**
     * La contribution constante de la septième dimension, celle du décalage.
     *
     * <p>Vanilla évalue {@code parameterSpace[6].distance(target[6])} à chaque nœud visité, alors que
     * {@code target[6]} vaut toujours zéro — {@code TargetPoint.toParameterArray} l'écrit en dur. Le
     * terme est donc le même à chaque appel pour un nœud donné. On le calcule ici avec exactement
     * l'expression de {@code Climate.Parameter.distance}, appliquée à zéro.
     */
    public static long carreDuDecalage(Climate.Parameter[] espace) {
        if (espace.length != 7) {
            return 0L;
        }
        Climate.Parameter decalage = espace[6];
        long dessus = -decalage.max();
        long ecart = dessus > 0L ? dessus : Math.max(decalage.min(), 0L);
        return ecart * ecart;
    }

    /**
     * Le contrôle : la distance qu'on vient de rendre est-elle celle de vanilla ?
     *
     * <p>Appelée par nœud visité, et seulement sous {@code AUDIT}. Le premier écart est journalisé
     * avec ses deux valeurs — un écart de distance ne se voit pas dans le terrain, il se voit ici.
     */
    public static void check(long rendue, long attendue) {
        checked.increment();
        if (rendue != attendue) {
            faults.increment();
            if (cried.compareAndSet(false, true)) {
                Lanterne.LOG.error("[CLIMAT] ÉCART de distance : {} rendu, {} attendu par vanilla — "
                        + "ne pas publier ce module en l'état.", rendue, attendue);
            }
            return;
        }
        // « said.get() » d'abord : sans lui, chaque appel sain paierait un parcours complet des
        // cellules du LongAdder. Le contrôle est lent par nature, pas par négligence.
        if (!said.get() && checked.sum() >= SAY_AFTER && said.compareAndSet(false, true)) {
            Lanterne.LOG.info("[CLIMAT] CONTRÔLE : {} distances comparées à vanilla, nœud par nœud — "
                    + "aucun écart.", SAY_AFTER);
        }
    }

    public static long faults() {
        return faults.sum();
    }

    public static long flattened() {
        return flattened.sum();
    }

    public static String describe() {
        long plats = flattened.sum();
        long laisses = declined.sum();
        if (plats == 0L && laisses == 0L) {
            return "aucun arbre climatique construit — le module n'a rien eu à faire";
        }
        String base = String.format(Locale.ROOT,
                "%d nœud(s) d'arbre climatique aplati(s) (%d Ko de bornes contiguës au lieu de "
                        + "%d Ko dispersés en %d objets), %d laissé(s) à vanilla",
                plats, plats * 128L / 1024L, plats * 272L / 1024L, plats * 8L, laisses);
        long vus = checked.sum();
        if (vus == 0L) {
            return base + " · CONTRÔLE NON FAIT (poser LANTERNE_CLIMAT_AUDIT=1)";
        }
        if (faults.sum() == 0L) {
            return base + String.format(Locale.ROOT,
                    " · CONTRÔLE : %d distances comparées à celles de vanilla, nœud par nœud et "
                            + "appel par appel — AUCUN écart", vus);
        }
        return base + String.format(Locale.ROOT,
                " · CONTRÔLE : %d distances sur %d DIFFÉRAIENT de vanilla — ne pas publier ce module",
                faults.sum(), vus);
    }
}
