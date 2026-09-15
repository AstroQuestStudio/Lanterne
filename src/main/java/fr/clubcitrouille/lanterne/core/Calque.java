package fr.clubcitrouille.lanterne.core;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * Le calque : l'arbre des fonctions de densité, décalqué une fois par chunk au lieu de trois.
 *
 * <h2>Le défaut, trouvé par le profileur puis lu dans la source</h2>
 *
 * <p>Le relevé de génération, tous fils échantillonnés, sur un serveur à un seul fil de travail :
 *
 * <pre>
 * 12,3 %  ImprovedNoise.noise                  ← le noyau de Perlin, le vrai travail
 *  4,2 %  Climate$RTree$SubTree.search
 *  4,0 %  DensityFunctions$PureTransformer.compute
 *  3,1 %  NoiseChunk.lambda$new$0
 *  2,7 %  Objects.hashCode                     ← ???
 *  1,1 %  TypedInstance.is
 *  1,0 %  DensityFunctions$MarkerOrMarked.mapChildren
 * </pre>
 *
 * <p>{@code Objects.hashCode} à 2,7 % d'un profil de génération de terrain n'a aucune raison
 * d'exister. {@code mapChildren} non plus : c'est une méthode de <em>construction</em> d'arbre, qui
 * ne devrait pas tourner pendant qu'on fabrique du relief.
 *
 * <p>La source explique les deux, et c'est une seule ligne
 * ({@code NoiseChunk.java}, ligne 374) :
 *
 * <pre>
 * private final Map&lt;DensityFunction, DensityFunction&gt; wrapped = new HashMap&lt;&gt;();
 *
 * protected DensityFunction wrap(DensityFunction function) {
 *     return this.wrapped.computeIfAbsent(function, this::wrapNew);
 * }
 * </pre>
 *
 * <p>Les fonctions de densité sont des <b>records</b>. Leur {@code hashCode()} est donc structurel et
 * <b>récursif</b> : hacher un nœud parcourt tout son sous-arbre, et rien n'est mémorisé. Or
 * {@code mapAll} visite chaque nœud de l'arbre et appelle {@code wrap} sur chacun. Le coût d'un
 * décalque complet est donc de l'ordre de la <b>somme des tailles de tous les sous-arbres</b> —
 * quadratique en la taille de l'arbre, pour un routeur de surmonde qui en compte plusieurs centaines.
 *
 * <h2>Et ce décalque est refait trois fois par chunk</h2>
 *
 * <p>Le constructeur de {@code NoiseChunk} en fait deux ({@code NoiseChunk.java}, lignes 142 et 158) :
 *
 * <pre>
 * NoiseRouter wrappedRouter = router.mapAll(this::wrap);   // les QUINZE fonctions du routeur
 * …
 * DensityFunction fullNoiseValue = …mapAll(this::wrap);
 * </pre>
 *
 * <p>Puis {@code NoiseBasedChunkGenerator.doCreateBiomes} en demande <b>six de plus</b>, une par
 * dimension du climat, par {@code cachedClimateSampler} ({@code NoiseChunk.java}, ligne 169) :
 *
 * <pre>
 * return new Climate.Sampler(
 *     noises.temperature().mapAll(this::wrap),
 *     noises.vegetation().mapAll(this::wrap),
 *     …
 * );
 * </pre>
 *
 * <p><b>Ces six-là sont déjà calculées.</b> Le {@code wrappedRouter} du constructeur contient
 * exactement {@code temperature}, {@code vegetation}, {@code continents}, {@code erosion},
 * {@code depth} et {@code ridges} décalquées par le même {@code wrap} — vérifié dans
 * {@code NoiseRouter.mapAll} ({@code NoiseRouter.java}, lignes 49-67), qui mappe ses quinze champs et
 * dans cet ordre. Le constructeur les jette : il ne garde que {@code preliminarySurfaceLevel},
 * {@code finalDensity} et les trois fonctions de filons.
 *
 * <h2>Pourquoi le résultat est identique, et non « équivalent »</h2>
 *
 * <p>C'est la question qui décide, et elle se démontre plutôt qu'elle ne se suppose.
 *
 * <p>Décalquer deux fois le même arbre avec le même {@code wrap} rend <b>les mêmes objets</b>. La
 * raison est le mémo lui-même. En remontant depuis les feuilles : une feuille passe par
 * {@code wrap} et le mémo rend la même enveloppe la seconde fois. Un nœud interne est reconstruit —
 * {@code mapChildren} alloue un record neuf — mais ce record neuf est <b>structurellement égal</b> à
 * celui du premier passage, puisque ses enfants sont les mêmes objets ; {@code computeIfAbsent} le
 * reconnaît donc et rend l'enveloppe déjà créée. De proche en proche, le second décalque ne produit
 * pas un arbre équivalent : il produit <b>le même</b>.
 *
 * <p>Ce module ne fait donc rien d'autre que garder ce que vanilla avait déjà entre les mains. Il ne
 * change ni une valeur, ni un ordre, ni un tirage aléatoire.
 *
 * <p><b>Le garde-fou</b> : on ne sert le calque que si le routeur demandé est celui qu'on a décalqué.
 * {@code cachedClimateSampler} le reçoit en argument, et rien n'interdit à un mod d'en passer un
 * autre. Une comparaison de référence suffit, et le cas contraire retombe sur vanilla.
 *
 * <h2>Ce que ce module ne fait pas, et qu'il faudrait faire un jour</h2>
 *
 * <p>Il supprime six décalques sur huit, <b>pas les deux du constructeur</b>. Ceux-là sont refaits
 * pour chaque chunk parce que les enveloppes qu'ils fabriquent portent des caches propres au chunk :
 * un {@code NoiseInterpolator} ou un {@code FlatCache} ne se partage pas d'un chunk à l'autre.
 *
 * <p>Les supprimer demanderait de séparer la <em>forme</em> de l'arbre — identique pour tous les
 * chunks d'un même monde — de l'<em>état</em> des enveloppes, et d'instancier l'état depuis un plan
 * précalculé. C'est le chantier que C2ME a mené, il se compte en centaines de lignes, et il n'a pas
 * été fait ici. La mesure ci-dessous dit ce que vaudrait ce chantier : elle en est la borne basse.
 */
public final class Calque {
    /** Calques servis depuis le routeur déjà décalqué. */
    private static final LongAdder served = new LongAdder();
    /** Appels où l'on a rendu la main à vanilla, faute de pouvoir prouver que c'est le même arbre. */
    private static final LongAdder declined = new LongAdder();

    /**
     * Rend le calque périmé, et fait donc échouer l'épreuve de conformité.
     *
     * <p>Une épreuve qui ne peut pas échouer ne prouve rien — c'est la règle de {@code lab/Duel}.
     * Ici la panne à pouvoir provoquer n'est pas une lenteur mais un <b>terrain différent</b> : avec
     * cet interrupteur, on sert le climat d'un routeur qui n'est pas celui qu'on a décalqué, et les
     * biomes changent.
     *
     * <p>{@code LANTERNE_BREAK_CALQUE=1}. L'épreuve {@code lab.Quarry} en régime de conformité — et à
     * défaut, n'importe quelle comparaison de deux mondes engendrés de la même graine — <b>doit</b>
     * alors montrer un écart.
     */
    private static final boolean BROKEN = "1".equals(System.getenv("LANTERNE_BREAK_CALQUE"));

    private Calque() {}

    public static boolean broken() {
        return BROKEN;
    }

    /**
     * Le contrôle : refaire le décalque de vanilla et vérifier qu'on rend <b>les mêmes objets</b>.
     *
     * <h2>Pourquoi une comparaison de références et non de valeurs</h2>
     *
     * <p>Comparer des températures en quelques milliers de points dirait « je n'ai pas trouvé
     * d'écart ». Comparer les <em>références</em> dit autre chose, et de bien plus fort : si la
     * fonction rendue est <b>le même objet</b> que celle que vanilla aurait construite, alors aucun
     * écart n'est possible en aucun point, pour aucune graine, dans aucune dimension. Ce n'est plus
     * un échantillon, c'est une preuve.
     *
     * <p>{@code LANTERNE_CALQUE_AUDIT=1} l'active. Le module refait alors exactement le travail qu'il
     * est censé éviter — c'est un mode de contrôle, pas un mode de production, et il est éteint par
     * défaut.
     */
    private static final boolean AUDIT = "1".equals(System.getenv("LANTERNE_CALQUE_AUDIT"));

    public static boolean auditing() {
        return AUDIT;
    }

    /** Fonctions contrôlées, et celles qui n'étaient pas le bon objet. */
    private static final LongAdder checked = new LongAdder();
    private static final LongAdder faults = new LongAdder();

    public static void check(boolean same) {
        checked.increment();
        if (!same) {
            faults.increment();
        }
    }

    public static long faults() {
        return faults.sum();
    }

    public static void serve() {
        served.increment();
    }

    public static void decline() {
        declined.increment();
    }

    public static long served() {
        return served.sum();
    }

    public static String describe() {
        long ok = served.sum();
        long no = declined.sum();
        if (ok == 0L && no == 0L) {
            return "aucun chunk de bruit fabriqué — le module n'a rien eu à faire";
        }
        String base = String.format(Locale.ROOT,
                "%d échantillonneur(s) de climat servis depuis le routeur déjà décalqué, %d laissé(s) "
                        + "à vanilla · %d décalque(s) d'arbre évité(s)",
                ok, no, ok * 6L);
        long seen = checked.sum();
        if (seen == 0L) {
            return base + " · CONTRÔLE NON FAIT (poser LANTERNE_CALQUE_AUDIT=1)";
        }
        if (faults.sum() == 0L) {
            return base + String.format(Locale.ROOT,
                    " · CONTRÔLE : %d fonction(s) comparées à ce que vanilla aurait construit — "
                            + "AUCUN écart, et ce sont LES MÊMES OBJETS, donc aucun écart n'est "
                            + "possible en aucun point", seen);
        }
        return base + String.format(Locale.ROOT,
                " · CONTRÔLE : %d fonction(s) sur %d N'ÉTAIENT PAS celles de vanilla — ne pas "
                        + "publier ce module", faults.sum(), seen);
    }

    public static void reset() {
        served.reset();
        declined.reset();
    }
}
