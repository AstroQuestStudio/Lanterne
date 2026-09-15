package fr.clubcitrouille.lanterne.core;

import java.util.Arrays;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La colonne : deux chunks voisins calculent le même bruit deux fois.
 *
 * <h2>Le compte, et il est exact</h2>
 *
 * <p>Le bruit du terrain n'est pas évalué à chaque bloc : il l'est aux <b>coins des cellules</b>,
 * puis interpolé. Pour un chunk, cela fait une grille de <b>cinq sur cinq colonnes</b> — les
 * cellules font quatre blocs de côté, et il faut un coin de plus que de cellules.
 *
 * <p>À l'intérieur d'un chunk, le jeu réutilise correctement : {@code swapSlices()} garde la tranche
 * déjà calculée quand on avance d'une cellule en X. Entre <b>deux chunks</b>, rien.
 *
 * <pre>
 * chunk (0,0) calcule les colonnes cellX = 0 1 2 3 4
 * chunk (1,0) calcule les colonnes cellX =         4 5 6 7 8
 *                                                  ^
 *                                       calculee deux fois
 * </pre>
 *
 * <p>Et la même chose en Z, donc les coins sont calculés <b>quatre</b> fois. Pour une grille de
 * N × N chunks :
 *
 * <pre>
 * ce que le jeu calcule      :  25 N²  colonnes
 * ce qui est reellement distinct : (4N+1)² , soit 16 N² quand N grandit
 * </pre>
 *
 * <p><b>25 / 16 = 1,5625.</b> Trente-six pour cent du bruit interpolé est donc recalculé à
 * l'identique. Sur une étape de bruit qui pèse 64 % du temps de génération, cela fait <b>23 % du
 * temps total</b> — le plus gros poste évitable trouvé dans ce domaine.
 *
 * <h2>Pourquoi c'est exact, et pas seulement « très probablement exact »</h2>
 *
 * <p>Une fonction de densité est <b>pure</b> : la même position rend toujours la même valeur, pour
 * une graine donnée. Rendre une valeur déjà calculée n'est donc pas une approximation, c'est la
 * même valeur — au bit près, puisque c'est littéralement le même {@code double}.
 *
 * <p>Cela n'exempte de rien : l'épreuve {@code LANTERNE_COLONNE_AUDIT=1} recalcule ce que le cache
 * vient de rendre et compare les tableaux <b>entièrement</b>, pas par échantillon. Un seul bit
 * d'écart fait échouer.
 *
 * <h2>Ce à quoi il a fallu faire attention</h2>
 *
 * <p>Sauter un remplissage laisse les caches internes de l'arbre là où ils étaient. Trois formes
 * existent en 26.2, et elles ne se comportent pas pareil :
 *
 * <ul>
 *   <li>{@code flat_cache} et {@code cache_2d} sont indexés <b>par position</b> : ils ne peuvent pas
 *       rendre la valeur d'une autre colonne, quoi qu'on saute ;</li>
 *   <li>{@code cache_once} est indexé par un <b>compteur</b>, {@code arrayInterpolationCounter} —
 *       mais ce compteur est incrémenté par le jeu <em>hors</em> de la boucle des interpolateurs,
 *       donc il avance même quand on ne remplit rien. Un cache périmé ne peut donc pas se faire
 *       passer pour le cache courant.</li>
 * </ul>
 *
 * <p>C'est cette lecture-là qui rend le saut sûr, et elle vaut d'être écrite : sans le troisième
 * point, ce module aurait produit des falaises aux frontières de chunks — le genre de défaut qui ne
 * se voit pas dans un test et se voit dans un monde.
 *
 * <h2>Plusieurs fils</h2>
 *
 * <p>La génération tourne sur un pool de travail, et depuis que la soumission est asynchrone,
 * plusieurs chunks peuvent être fabriqués en même temps. Les accès sont donc <b>synchronisés</b> :
 * une table de hachage à laquelle deux fils écrivent sans verrou ne se contente pas de perdre une
 * entrée, elle peut boucler à l'infini.
 *
 * <p>Le verrou ne coûte rien sur la machine visée, qui n'a qu'un cœur. Il coûte quelque chose sur
 * une machine de développement à seize fils — mais ce qu'il protège, une recopie de quatre cents
 * octets, dure moins longtemps que le calcul qu'il évite.
 *
 * <h2>Ce qu'il ne rend pas</h2>
 *
 * <p>Rien, si les chunks voisins ne sont pas engendrés à peu près en même temps : un cache dont on
 * a évincé l'entrée avant de la relire ne sert à rien. C'est le cas favorable qui est aussi le cas
 * courant — une pré-génération avance de proche en proche, et un joueur qui explore aussi.
 */
public final class Colonne {
    /**
     * Colonnes gardées au plus.
     *
     * <p>Une colonne pèse {@code nombre d'interpolateurs × (hauteur en cellules + 1) × 8} octets,
     * soit environ un kilo-octet et demi dans le surmonde. Quatre mille colonnes tiennent donc dans
     * six mégaoctets — à comparer aux quatre gigaoctets de la machine visée, et surtout aux dix-huit
     * mégaoctets que la génération d'<b>un seul</b> chunk alloue.
     *
     * <p>Le bon ordre de grandeur n'est pas « le plus possible » : il suffit de tenir le voisinage
     * immédiat du front de génération. Au-delà, on paie de la mémoire pour des colonnes qu'on ne
     * relira jamais.
     *
     * <p>Mesuré : à quatre mille, le banc de carrière servait <b>32,1 %</b> des colonnes — contre un
     * maximum théorique de 36 % — mais en évinçait <b>40 208 sur 65 224</b>. Le cache débordait donc
     * franchement, et l'écart au théorique venait de là. Huit mille tiennent sous quatre mégaoctets.
     */
    private static final int KEEP = 8192;

    private Colonne() {}

    /**
     * Les colonnes retenues, de la moins récemment lue à la plus récente.
     *
     * <p>Une table <em>linked</em> parce que l'éviction doit être ordonnée : sans ordre, on évince
     * au hasard, et sur un front de génération qui avance, évincer au hasard revient à évincer ce
     * dont on a justement besoin.
     */
    private static final Long2ObjectLinkedOpenHashMap<double[]> COLUMNS =
            new Long2ObjectLinkedOpenHashMap<>();

    /**
     * Les mondes vus, dans l'ordre de rencontre. L'indice sert de préfixe de clé.
     *
     * <h2>Pourquoi la dimension DOIT entrer dans la clé</h2>
     *
     * <p>La cellule (12, 7) existe dans le surmonde, dans le Nether et dans l'End, avec trois
     * valeurs de bruit qui n'ont rien à voir. Les confondre ne donnerait pas un terrain approché :
     * il donnerait le terrain d'une autre dimension, par plaques.
     *
     * <p>On pourrait vider le cache à chaque changement de monde — mais deux joueurs dans deux
     * dimensions le videraient en boucle, et il ne servirait plus jamais. Un préfixe coûte huit bits
     * et règle le cas.
     */
    private static final java.util.List<Object> OWNERS = new java.util.ArrayList<>();

    private static long hits;
    private static long misses;
    private static long evictions;

    /** Vrai quand on recalcule pour comparer. Voir {@link #audit()}. */
    private static final boolean AUDIT = "1".equals(System.getenv("LANTERNE_COLONNE_AUDIT"));

    private static long audited;
    private static long divergences;

    public static boolean audit() {
        return AUDIT;
    }

    /**
     * Le rang de ce monde, attribué à la première rencontre.
     *
     * <p>L'objet passé doit être celui qui <b>identifie le bruit</b> — en pratique l'état aléatoire
     * du niveau, un par monde et par graine. On compare par <b>identité</b> et non par égalité :
     * deux mondes distincts pourraient se déclarer égaux, jamais identiques.
     *
     * @return le rang, ou -1 si l'on en a vu trop pour les distinguer encore
     */
    public static synchronized int ownerOf(Object world) {
        for (int i = 0; i < OWNERS.size(); i++) {
            if (OWNERS.get(i) == world) {
                return i;
            }
        }
        if (OWNERS.size() >= 255) {
            // Au-delà, on ne saurait plus distinguer : on refuse de servir plutôt que de risquer
            // de rendre la colonne d'un autre monde.
            return -1;
        }
        OWNERS.add(world);
        return OWNERS.size() - 1;
    }

    /**
     * Assemble la clé d'une colonne.
     *
     * <pre>
     * bits 56-63 : le monde      (255 au plus)
     * bits 32-55 : la cellule Z  (24 bits signes, decales)
     * bits  8-31 : la cellule X
     * bits  0-7  : le rang de l'interpolateur
     * </pre>
     *
     * <p>Vingt-quatre bits portent ±8,4 millions de cellules, soit ±33 millions de blocs — au-delà
     * de la bordure du monde, qui s'arrête à trente millions. Aucune position atteignable ne peut
     * donc se replier sur une autre.
     */
    public static long key(int world, int cellX, int cellZ, int rank) {
        long w = (long) (world & 0xFF) << 56;
        long z = (long) ((cellZ + 0x800000) & 0xFFFFFF) << 32;
        long x = (long) ((cellX + 0x800000) & 0xFFFFFF) << 8;
        return w | z | x | (rank & 0xFF);
    }

    /**
     * Rend la colonne déjà calculée, ou {@code null}.
     *
     * @param key identifiant de la colonne : cellule X, cellule Z et rang de l'interpolateur
     * @param length longueur attendue ; une longueur différente est une entrée d'un autre monde,
     *               qu'on refuse plutôt que de la tronquer
     */
    public static synchronized double[] serve(long key, int length) {
        double[] kept = COLUMNS.getAndMoveToLast(key);
        if (kept == null) {
            misses++;
            return null;
        }
        if (kept.length != length) {
            // Ne devrait pas arriver — la hauteur d'un monde ne change pas. Si cela arrive, c'est
            // que la clé collisionne ou que le propriétaire n'a pas été changé : dans les deux cas,
            // rendre ce tableau serait pire que de recalculer.
            COLUMNS.remove(key);
            misses++;
            return null;
        }
        hits++;
        return kept;
    }

    /** Retient une colonne qu'on vient de calculer. */
    public static synchronized void keep(long key, double[] values) {
        COLUMNS.putAndMoveToLast(key, values.clone());
        while (COLUMNS.size() > KEEP) {
            COLUMNS.removeFirst();
            evictions++;
        }
    }

    /**
     * Confronte ce que le cache a rendu à ce que le jeu aurait calculé.
     *
     * <p>Comparaison <b>bit à bit et sur tout le tableau</b>, via {@link Arrays#equals(double[],
     * double[])}. Un échantillon dirait « je n'ai pas trouvé d'écart » ; ceci dit qu'il n'y en a
     * aucun, pour cette colonne-là.
     */
    public static synchronized void confront(long key, double[] served, double[] recomputed) {
        audited++;
        if (Arrays.equals(served, recomputed)) {
            return;
        }
        divergences++;
        if (divergences <= 5) {
            Lanterne.LOG.error("[COLONNE] ÉCART sur la colonne {} : le cache et le calcul ne disent "
                    + "pas la même chose. Ce module NE DOIT PAS être publié en l'état.", key);
        }
    }

    /** Ce que le cache a fait, pour le rapport. */
    public static synchronized String summary() {
        long total = hits + misses;
        if (total == 0L) {
            return "aucune colonne demandée";
        }
        String base = String.format(java.util.Locale.ROOT,
                "%d colonne(s) demandée(s), %d servie(s) depuis le cache (%.1f %%), %d évincée(s)",
                total, hits, 100.0d * hits / total, evictions);
        if (!AUDIT) {
            return base;
        }
        return base + String.format(java.util.Locale.ROOT,
                " · CONTRÔLE : %d colonne(s) recalculée(s) et comparée(s) entièrement, %d écart(s)",
                audited, divergences);
    }

    /** Vrai si le contrôle a trouvé au moins un écart. */
    public static boolean broken() {
        return divergences > 0L;
    }

    public static synchronized void forget() {
        COLUMNS.clear();
        OWNERS.clear();
        hits = 0L;
        misses = 0L;
        evictions = 0L;
        audited = 0L;
        divergences = 0L;
    }
}
