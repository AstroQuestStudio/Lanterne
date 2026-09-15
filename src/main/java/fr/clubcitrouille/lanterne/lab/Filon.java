package fr.clubcitrouille.lanterne.lab;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * Le filon : où passent réellement les soixante-quatre pour cent de l'étape « bruit ».
 *
 * <h2>Pourquoi ce banc-ci, alors que deux profileurs existent déjà</h2>
 *
 * <p>{@code core/Forge} dit qu'une étape pèse 64 % du temps d'un chunk. {@code lab/Sampler} dit que
 * {@code ImprovedNoise.noise} pèse 12,3 % du processeur. Aucun des deux ne dit <b>laquelle des trois
 * boucles de l'étape</b> paie ces 64 %, et c'est la seule question qui décide où creuser.
 *
 * <p>Un profil par méthode répartit le temps sur un peloton de vingt-six lignes dont aucune ne
 * dépasse quatre pour cent. On en conclut invariablement « c'est étalé, il n'y a rien à prendre ».
 * C'est faux : le temps est étalé sur les <em>méthodes</em>, pas sur les <em>boucles</em>. Trois
 * boucles seulement composent l'étape du bruit, et ce compteur les sépare.
 *
 * <h2>Les trois boucles, lues dans la source</h2>
 *
 * <p>{@code NoiseBasedChunkGenerator.doFill} ({@code NoiseBasedChunkGenerator.java} lignes 375-451)
 * est tout l'étage « bruit ». Il ne fait rien d'autre que :
 *
 * <ol>
 *   <li><b>Les tranches</b> — {@code NoiseChunk.fillSlice} ({@code NoiseChunk.java} ligne 231),
 *       appelé cinq fois par chunk. C'est là qu'est le vrai calcul : chaque appel évalue l'arbre de
 *       fonctions de densité en {@code (cellCountXZ+1) × (cellCountY+1)} points, soit 5 × 49 = 245
 *       points, pour <b>chacun</b> des interpolateurs enregistrés. Le surmonde en compte huit —
 *       {@code postProcess}, les quatre des nouilles, les trois des filons de minerai (voir
 *       {@code NoiseRouterData}). Cinq appels × 245 points × 8 interpolateurs = 9 800 évaluations
 *       d'arbre par chunk, et c'est ici que {@code BlendedNoise.compute} dépense ses quarante
 *       octaves de Perlin par point.</li>
 *   <li><b>Les cellules</b> — {@code NoiseChunk.selectCellYZ} ({@code NoiseChunk.java} ligne 295),
 *       appelé 4 × 4 × 48 = 768 fois par chunk. Chaque appel remplit le {@code CacheAllInCell} de
 *       128 valeurs, soit 98 304 évaluations de {@code add(finalDensity, beardifier)} par chunk. Ces
 *       évaluations-là sont <em>censées</em> être bon marché : l'interpolateur y rend un
 *       {@code Mth.lerp3} au lieu de recalculer du bruit. « Censées » est exactement ce qu'un banc
 *       doit vérifier plutôt que supposer.</li>
 *   <li><b>Le reste</b> — la boucle par bloc : {@code updateForY/X/Z} sur les huit interpolateurs,
 *       {@code aquifer.computeSubstance}, {@code section.setBlockState}, les deux cartes de hauteur.
 *       Obtenu par soustraction, ce qui évite d'instrumenter un chemin appelé 98 304 fois par chunk
 *       — {@code core/Forge} rapporte qu'un compteur posé là avait <b>doublé</b> le coût de l'étape
 *       qu'il mesurait.</li>
 * </ol>
 *
 * <h2>Et l'étape des biomes, qui paie la construction</h2>
 *
 * <p>Détail qui change la lecture du rapport : le {@code NoiseChunk} n'est <b>pas</b> construit
 * pendant l'étape du bruit. {@code doCreateBiomes} ({@code NoiseBasedChunkGenerator.java} ligne 90)
 * appelle {@code getOrCreateNoiseChunk} avant {@code fillBiomesFromNoise} ; les deux décalques
 * d'arbre du constructeur, le remplissage des neuf caches plats et la création de l'aquifère sont
 * donc facturés à <b>{@code minecraft:biomes}</b>, pas à {@code minecraft:noise}. Le module
 * {@code calque} n'a donc jamais pu apparaître dans les 64 % : il travaille dans les 22,6 %.
 *
 * <p>Trois postes de plus couvrent cette étape : le chantier complet ({@code createNoiseChunk}), la
 * part qu'y prend le décalque, et {@code fillBiomesFromNoise} — c'est-à-dire l'arbre de climat.
 *
 * <h2>Ce que coûte la mesure, et pourquoi c'est tenable</h2>
 *
 * <p>Le poste le plus sollicité est {@code CELLULES}, 768 passages par chunk. Deux lectures
 * d'horloge et un {@code LongAdder} par passage, soit environ quarante microsecondes sur les
 * quatre-vingt-seize millisecondes de l'étape : <b>quatre centièmes de pour cent</b>. Les autres
 * postes se comptent en unités par chunk. C'est la raison pour laquelle on mesure les tranches et
 * les cellules, puis on <em>soustrait</em> pour obtenir la boucle par bloc, au lieu de
 * l'instrumenter.
 *
 * <p>Éteint par défaut : {@code LANTERNE_FILON=1}. Sans cette variable, {@link #ON} est une
 * constante fausse et le compilateur à la volée retire les appels.
 */
public final class Filon {
    /** {@code LANTERNE_FILON=1}. Constante : le compilateur retire tout le reste quand elle est fausse. */
    public static final boolean ON = "1".equals(System.getenv("LANTERNE_FILON"));

    /** {@code NoiseChunk.fillSlice} — le remplissage des tranches d'interpolation. */
    public static final int TRANCHES = 0;
    /** {@code NoiseChunk.selectCellYZ} — le remplissage du cache par cellule. */
    public static final int CELLULES = 1;
    /** {@code NoiseBasedChunkGenerator.createNoiseChunk} — la construction complète. */
    public static final int CHANTIER = 2;
    /** Les deux {@code mapAll} du constructeur de {@code NoiseChunk} — le décalque de l'arbre. */
    public static final int DECALQUE = 3;
    /** {@code NoiseChunk.cachedClimateSampler} — les six décalques que le module calque supprime. */
    public static final int CLIMAT = 4;
    /** {@code ChunkAccess.fillBiomesFromNoise} — l'arbre de climat, {@code Climate.RTree.search}. */
    public static final int BIOMES = 5;

    private static final int COUNT = 6;

    private static final String[] NAMES = {
        "tranches (fillSlice)",
        "cellules (selectCellYZ)",
        "chantier (createNoiseChunk)",
        "  dont décalque (mapAll)",
        "climat (cachedClimateSampler)",
        "biomes (fillBiomesFromNoise)",
    };

    private static final LongAdder[] NANOS = new LongAdder[COUNT];
    private static final LongAdder[] CALLS = new LongAdder[COUNT];

    static {
        for (int i = 0; i < COUNT; i++) {
            NANOS[i] = new LongAdder();
            CALLS[i] = new LongAdder();
        }
    }

    /**
     * Horodatage d'entrée, par fil et par poste.
     *
     * <p>Un tableau par fil plutôt qu'un champ par objet : {@code createNoiseChunk} est appelé sur
     * un générateur <b>partagé</b> entre tous les fils de travail, un champ d'instance y serait faux
     * dès le second fil. Aucun de ces postes n'est récursif, un seul horodatage par poste suffit
     * donc.
     */
    private static final ThreadLocal<long[]> MARKS = ThreadLocal.withInitial(() -> new long[COUNT]);

    private Filon() {}

    public static void enter(int slot) {
        if (!ON) {
            return;
        }
        MARKS.get()[slot] = System.nanoTime();
    }

    public static void exit(int slot) {
        if (!ON) {
            return;
        }
        long started = MARKS.get()[slot];
        if (started == 0L) {
            return;
        }
        NANOS[slot].add(System.nanoTime() - started);
        CALLS[slot].increment();
    }

    /** Pour les postes mesurés par enveloppement plutôt que par entrée/sortie. */
    public static void add(int slot, long nanos) {
        if (!ON) {
            return;
        }
        NANOS[slot].add(nanos);
        CALLS[slot].increment();
    }

    public static void reset() {
        for (int i = 0; i < COUNT; i++) {
            NANOS[i].reset();
            CALLS[i].reset();
        }
    }

    /**
     * Le relevé, rapporté aux deux étapes qu'il ventile.
     *
     * @param chunks nombre de chunks générés pendant la fenêtre de mesure, dénominateur des
     *               « par chunk ». Zéro si inconnu — les colonnes correspondantes sont alors tues.
     */
    public static String describe(int chunks) {
        if (!ON) {
            return "NON MESURÉ — poser LANTERNE_FILON=1 pour ventiler l'intérieur de l'étape du bruit";
        }
        long tranches = NANOS[TRANCHES].sum();
        long cellules = NANOS[CELLULES].sum();
        if (tranches == 0L && cellules == 0L) {
            return "aucun chunk de bruit fabriqué — le filon n'a rien vu passer";
        }

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < COUNT; i++) {
            long nanos = NANOS[i].sum();
            long calls = CALLS[i].sum();
            if (calls == 0L) {
                continue;
            }
            text.append(String.format(Locale.ROOT,
                    "%n[FILON]  %-30s %9.2f ms cumulées · %8d passage(s) · %8.3f µs l'un",
                    NAMES[i], nanos / 1.0E6d, calls, nanos / 1.0E3d / calls));
            if (chunks > 0) {
                text.append(String.format(Locale.ROOT, " · %7.3f ms/chunk · %6.1f passage(s)/chunk",
                        nanos / 1.0E6d / chunks, calls / (double) chunks));
            }
        }
        return text.toString();
    }

    /**
     * La seule ligne du rapport qui décide où creuser : la part de l'étape du bruit que prennent les
     * tranches, les cellules, et ce qui reste.
     *
     * <p>Le « reste » est obtenu par soustraction — voir le Javadoc de classe sur la raison de ne
     * jamais instrumenter une boucle appelée 98 304 fois par chunk.
     *
     * @param noiseStepNanos le total de {@code minecraft:noise} relevé par {@code core/Forge}
     */
    public static String ventilation(long noiseStepNanos) {
        if (!ON) {
            return "NON MESURÉE — poser LANTERNE_FILON=1";
        }
        if (noiseStepNanos <= 0L) {
            return "REFUS DE VENTILER : l'étape du bruit n'a pas été chronométrée, il n'y a pas de "
                    + "dénominateur. Un pourcentage sans total ne veut rien dire.";
        }
        long tranches = NANOS[TRANCHES].sum();
        long cellules = NANOS[CELLULES].sum();
        long reste = noiseStepNanos - tranches - cellules;
        if (reste < 0L) {
            return String.format(Locale.ROOT,
                    "REFUS DE VENTILER : les sous-postes (%.1f ms) dépassent l'étape qu'ils ventilent "
                            + "(%.1f ms). Le chronomètre d'étape et celui-ci ne mesurent pas la même "
                            + "fenêtre — ne rien conclure de ce relevé.",
                    (tranches + cellules) / 1.0E6d, noiseStepNanos / 1.0E6d);
        }
        return String.format(Locale.ROOT,
                "%n[FILON]  %5.1f %%  tranches — l'arbre de densité évalué aux coins de cellule"
                        + "%n[FILON]  %5.1f %%  cellules — le cache par cellule, 128 valeurs × 768"
                        + "%n[FILON]  %5.1f %%  reste    — la boucle par bloc : interpolation, "
                        + "aquifère, écriture",
                tranches * 100d / noiseStepNanos,
                cellules * 100d / noiseStepNanos,
                reste * 100d / noiseStepNanos);
    }
}
