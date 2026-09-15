package fr.clubcitrouille.lanterne.core;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Le cadastre : la place prise par les pièces d'une structure, tenue dans un registre de boîtes.
 *
 * <h2>Le défaut, et son exposant</h2>
 *
 * <p>Un village, un avant-poste, un bastion ou une cité ancienne ne sont pas des gabarits : ce sont
 * des <b>assemblages de pièces</b> que {@code JigsawPlacement$Placer.tryPlacingChildren} ajoute une
 * par une, en suivant les blocs de jigsaw. Avant chaque ajout, il pose deux questions à une
 * {@code VoxelShape} qui représente <em>ce qui reste libre</em> :
 *
 * <pre>
 * // la pièce tient-elle dans ce qui reste ?
 * Shapes.joinIsNotEmpty(libre, Shapes.create(AABB.of(boîte).deflate(0.25)), BooleanOp.ONLY_SECOND)
 * // si oui, retirer sa place du libre
 * libre = Shapes.joinUnoptimized(libre, Shapes.create(AABB.of(boîte)), BooleanOp.ONLY_FIRST)
 * </pre>
 *
 * <p>La seconde ligne est celle qui coûte, et il faut lire {@code Shapes.joinUnoptimized} pour voir
 * pourquoi ({@code Shapes.java} ligne 139, {@code BitSetDiscreteVoxelShape.join} ligne 112). Elle
 * <b>fusionne les listes de coordonnées</b> des deux formes sur les trois axes, puis remplit une
 * grille de bits de la taille du produit des trois listes fusionnées. Chaque pièce ajoute deux
 * coordonnées par axe. Après {@code N} pièces, la grille compte donc de l'ordre de {@code (2N)³}
 * cellules, toutes visitées.
 *
 * <p><b>Le coût d'une seule soustraction croît comme le cube du nombre de pièces déjà posées, et le
 * coût total comme sa puissance quatrième.</b> Il n'y a aucun court-circuit dans
 * {@code joinUnoptimized} : les seuls retours anticipés concernent les formes vides ou identiques.
 * C'est ce qui fige un serveur quand un joueur explore vers un village — et sur un VPS à un cœur, où
 * le fil de génération et le fil du serveur se partagent le même processeur, ce gel se voit.
 *
 * <h2>Ce que le cadastre fait à la place</h2>
 *
 * <p>Il observe que la région libre est <b>toujours</b> de la forme « une région de départ, moins un
 * ensemble de boîtes » :
 *
 * <ul>
 *   <li>à la racine, {@code Shapes.join(create(aabb), create(AABB.of(boîteCentrale)), ONLY_FIRST)} —
 *       le cube de portée maximale, moins la pièce centrale ;</li>
 *   <li>pour chaque pièce source dont un jigsaw pointe vers l'intérieur d'elle-même,
 *       {@code Shapes.create(AABB.of(boîteSource))} — la pièce source entière.</li>
 * </ul>
 *
 * <p>Et il observe que la question posée, {@code candidat ⊆ libre}, se décompose exactement :
 *
 * <pre>
 * candidat ⊆ (départ \ ∪ boîtes)   ⟺   candidat ⊆ départ   ET   ∀i, candidat ∩ boîteᵢ = ∅
 * </pre>
 *
 * <p>Le premier terme est posé <b>à vanilla</b>, sur la forme de départ — qui ne grossit jamais, et
 * dont la grille fait au plus vingt-sept cellules. Le second est une question d'intersection de
 * boîtes, à laquelle une grille de hachage répond en temps quasi constant.
 *
 * <h2>Pourquoi c'est exactement le même résultat, et non « presque »</h2>
 *
 * <p>La décomposition ci-dessus est une identité ensembliste : elle ne peut pas se tromper. Le seul
 * endroit où une divergence pourrait naître est l'<b>epsilon</b> — vanilla fusionne deux coordonnées
 * distantes de moins de {@code 1,0E-7} ({@code IndirectMerger} ligne 52), là où
 * {@code AABB.intersects} compare strictement.
 *
 * <p>Cette zone grise n'existe pas ici, et c'est arithmétique. {@code AABB.of(BoundingBox)} rend des
 * coordonnées <b>entières</b> ({@code AABB.java} ligne 41), et la boîte testée est rétrécie d'un
 * quart de bloc. Tout recouvrement entre un candidat et une boîte posée vaut donc soit exactement
 * zéro, soit au moins {@code 0,25} — jamais {@code 1,0E-8}. Les deux méthodes répondent la même
 * chose, sans tolérance à régler.
 *
 * <p>Le cas « bord à bord » — deux pièces qui se touchent sans se recouvrir — se résout du même côté
 * chez les deux : vanilla ne trouve aucune cellule commune, {@code AABB.intersects} rend faux sur
 * une inégalité stricte. C'est le cas le plus fréquent d'un village, et il fallait qu'il tombe juste.
 *
 * <h2>Ce qu'on ne fait PAS, et c'est le cœur de la décision</h2>
 *
 * <p>Le mod de référence — <b>StructureLayoutOptimizer</b> (TelepathicGrunt, MIT) — range son octree
 * dans une sous-classe de {@code VoxelShape} qu'il glisse à la place de celle de vanilla. Cette
 * classe rend {@code null} sur {@code getCoords()} et porte une grille discrète de taille
 * {@code 0×0×0}, si bien que {@code isEmpty()} y répond <b>vrai</b>. N'importe quel autre mod qui
 * lit cette forme reçoit soit un {@code NullPointerException} au fond de son propre code, soit —
 * bien pire — une réponse fausse et silencieuse : {@code Shapes.joinUnoptimized} y rendrait
 * {@code empty()} sans rien signaler.
 *
 * <p>Ce module <b>n'ajoute aucun type à la hiérarchie de {@code VoxelShape}</b>. La case
 * {@code MutableObject} de vanilla continue de contenir une forme de vanilla, construite par
 * vanilla, qui répond correctement à toutes les questions de son interface. Le registre vit à côté.
 *
 * <p>Le prix de ce choix, et il faut le nommer : pendant qu'une structure se place, la forme laissée
 * dans cette case est celle du <b>départ</b>, pas celle du moment. Un mod tiers qui la lirait y
 * verrait plus de place libre qu'il n'y en a réellement. C'est une réponse périmée — pas une réponse
 * absurde, pas un plantage, et surtout pas une réponse silencieusement fausse <em>sur elle-même</em>.
 * Et l'information n'est pas perdue : {@link #materialise} la reconstruit à l'identique, en rejouant
 * les soustractions que vanilla aurait faites. Personne ne l'appelle aujourd'hui ; elle existe pour
 * que « différé » ne veuille pas dire « détruit ».
 *
 * <h2>Où le gain va, et où il ne va pas</h2>
 *
 * <p>Le placement Jigsaw s'exécute pendant l'étape {@code generateStructureStarts} de la génération
 * de chunk, donc sur le <b>pool de travail</b> et non sur le fil du serveur. Ce que l'on gagne est de
 * la <b>latence d'exploration</b> : le temps qu'un joueur qui avance attend son chunk. Ce n'est pas
 * des TPS, et le README ne doit pas les confondre — {@code notes/serveur-un-coeur.md} a déjà établi
 * ce point pour la génération de terrain en général.
 *
 * <p>La nuance qui compte quand même pour un cœur unique : ce pool n'a qu'un travailleur, et ce
 * travailleur partage le processeur avec le tick. Un pic de génération de plusieurs centaines de
 * millisecondes n'est pas neutre pour le tick, même s'il ne s'y inscrit pas.
 */
public final class Cadastre {
    /**
     * Côté d'une cellule de la grille de hachage, en blocs.
     *
     * <p>Trente-deux, parce qu'une pièce de village ou de cité ancienne fait de l'ordre de dix à
     * quarante blocs : elle occupe donc une poignée de cellules à l'insertion, et une question la
     * concernant n'en visite pas davantage. Seize multiplierait les insertions par huit pour la même
     * sélectivité ; soixante-quatre rendrait les listes trop longues sur une cité dense.
     */
    private static final int CELL = 32;

    /**
     * Nombre de boîtes à partir duquel on bâtit la grille.
     *
     * <p>En dessous, le balayage linéaire d'une liste de seize boîtes contiguës en mémoire est plus
     * rapide que le hachage, l'accès à la table et le parcours des index. Et surtout, l'immense
     * majorité des régions — celles d'une pièce source, créées une par jigsaw — n'atteignent jamais
     * ce seuil : elles ne paient alors pas une table de hachage pour trois boîtes.
     */
    private static final int GRID_FROM = 16;

    /** Régions prises en charge. */
    private static final LongAdder taken = new LongAdder();
    /** Régions laissées à vanilla, module éteint ou forme de départ non identifiable. */
    private static final LongAdder declined = new LongAdder();
    /** Questions « cette pièce tient-elle ? » répondues par le registre. */
    private static final LongAdder asked = new LongAdder();
    /** Boîtes inscrites au registre, toutes régions confondues. */
    private static final LongAdder filed = new LongAdder();
    /** Boîtes de la région la plus chargée rencontrée. C'est l'exposant du défaut, mesuré. */
    private static final LongAdder busiest = new LongAdder();

    private Cadastre() {}

    /**
     * La région attachée à cette forme de départ, ou {@code null} si vanilla doit garder la main.
     *
     * <p>Le registre est indexé par l'<b>identité</b> de la forme de départ, et non par sa valeur :
     * c'est la seule clé disponible sans capturer une variable locale de vanilla, et elle est stable
     * parce que ce module empêche justement vanilla de remplacer cette forme.
     *
     * <p>D'où le refus qui suit : {@code Shapes.block()} et {@code Shapes.empty()} sont des
     * <b>singletons partagés</b>. Deux régions différentes qui auraient l'un ou l'autre pour départ
     * se confondraient dans la table, et l'une hériterait des boîtes de l'autre — une pièce refusée
     * là où elle tenait. Le cas demande une pièce de structure d'un bloc de côté posée à l'origine du
     * monde ; il est donc presque impossible, et « presque » ne suffit pas quand le symptôme serait
     * une maison manquante.
     *
     * <p>Le réglage n'est lu qu'ici, <b>à la prise en charge</b>. Une bascule en cours de placement
     * décide du sort de la prochaine région, jamais de celle qui est en cours : abandonner une
     * région à mi-chemin laisserait vanilla continuer sur une forme périmée, ce qui ferait se
     * chevaucher des pièces.
     */
    public static @Nullable Region region(Map<VoxelShape, Region> plots, VoxelShape seed) {
        Region known = plots.get(seed);
        if (known != null) {
            return known;
        }
        if (!Settings.cadastre() || seed == Shapes.block() || seed == Shapes.empty()
                || seed.isEmpty()) {
            declined.increment();
            return null;
        }
        Region fresh = new Region(seed);
        plots.put(seed, fresh);
        taken.increment();
        return fresh;
    }

    /** Une table d'identité neuve, pour un placement. */
    public static Map<VoxelShape, Region> plots() {
        return new IdentityHashMap<>();
    }

    /** Le registre d'une région libre : une forme de départ, et les boîtes qu'on lui a retirées. */
    public static final class Region {
        private final VoxelShape seed;
        private final List<AABB> boxes = new ArrayList<>();

        /**
         * Cellule → index des boîtes qui la touchent. Nulle tant que la liste est courte.
         *
         * <p>Les <b>index</b> et non les boîtes : une boîte qui couvre vingt-sept cellules est alors
         * stockée une fois et référencée vingt-sept fois par un entier, au lieu d'être vingt-sept
         * fois une référence d'objet dans vingt-sept listes. Sur un cœur unique, ce qui n'est pas
         * alloué n'est pas ramassé.
         */
        private @Nullable Long2ObjectOpenHashMap<int[]> grid;

        private Region(VoxelShape seed) {
            this.seed = seed;
        }

        /** La forme de départ, telle que vanilla l'a construite. */
        public VoxelShape seed() {
            return this.seed;
        }

        public int size() {
            return this.boxes.size();
        }

        /**
         * La pièce tient-elle ?
         *
         * <p>{@code candidate} est la boîte <b>rétrécie d'un quart de bloc</b> que vanilla teste ; on
         * la relit sur la forme que vanilla venait de construire pour elle, plutôt que sur la
         * variable locale dont elle sort. Ce n'est pas de la coquetterie : la boîte d'origine est
         * <em>mutable</em> et vanilla l'agrandit juste avant ({@code targetBB.encapsulate}, pour la
         * rallonge des pièces plates). Lire la forme, c'est lire ce que vanilla a réellement demandé.
         */
        public boolean fits(VoxelShape candidateShape, AABB candidate) {
            asked.increment();
            // Le terme « dans la région de départ », posé à vanilla. La forme de départ ne grossit
            // jamais : cet appel coûte le même prix à la première pièce et à la trois centième.
            if (Shapes.joinIsNotEmpty(this.seed, candidateShape, BooleanOp.ONLY_SECOND)) {
                return false;
            }
            return !this.hits(candidate);
        }

        /** Inscrit la place prise. */
        public void file(AABB box) {
            int index = this.boxes.size();
            this.boxes.add(box);
            filed.increment();
            if (index + 1 > busiest.sum()) {
                // Approximatif entre fils, et c'est assumé : ce compteur sert à dire l'ordre de
                // grandeur de N dans le N⁴ qu'on supprime, pas à être exact à l'unité.
                busiest.reset();
                busiest.add(index + 1L);
            }
            if (this.grid == null) {
                if (this.boxes.size() < GRID_FROM) {
                    return;
                }
                this.grid = new Long2ObjectOpenHashMap<>();
                for (int at = 0; at < this.boxes.size(); at++) {
                    this.insert(at, this.boxes.get(at));
                }
                return;
            }
            this.insert(index, box);
        }

        private boolean hits(AABB candidate) {
            if (BROKEN) {
                // LANTERNE_BREAK_CADASTRE=1 : le registre ne se souvient plus de rien. Les pièces se
                // chevauchent, et l'épreuve du bourg DOIT annoncer une divergence de disposition.
                return false;
            }
            Long2ObjectOpenHashMap<int[]> table = this.grid;
            if (table == null) {
                for (int at = 0; at < this.boxes.size(); at++) {
                    if (this.boxes.get(at).intersects(candidate)) {
                        return true;
                    }
                }
                return false;
            }
            int minX = cell(candidate.minX);
            int maxX = cell(candidate.maxX);
            int minY = cell(candidate.minY);
            int maxY = cell(candidate.maxY);
            int minZ = cell(candidate.minZ);
            int maxZ = cell(candidate.maxZ);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        int[] here = table.get(key(x, y, z));
                        if (here == null) {
                            continue;
                        }
                        // On ne dédoublonne pas entre cellules : une même boîte peut être examinée
                        // deux fois. Tenir un ensemble de vus coûterait davantage que le second
                        // test, et le premier qui touche arrête la boucle de toute façon.
                        for (int index : here) {
                            if (this.boxes.get(index).intersects(candidate)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }

        private void insert(int index, AABB box) {
            Long2ObjectOpenHashMap<int[]> table = this.grid;
            if (table == null) {
                return;
            }
            int minX = cell(box.minX);
            int maxX = cell(box.maxX);
            int minY = cell(box.minY);
            int maxY = cell(box.maxY);
            int minZ = cell(box.minZ);
            int maxZ = cell(box.maxZ);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        long at = key(x, y, z);
                        int[] was = table.get(at);
                        if (was == null) {
                            table.put(at, new int[] {index});
                            continue;
                        }
                        int[] now = new int[was.length + 1];
                        System.arraycopy(was, 0, now, 0, was.length);
                        now[was.length] = index;
                        table.put(at, now);
                    }
                }
            }
        }

        /**
         * Reconstruit la forme de vanilla, à l'identique, en rejouant les soustractions.
         *
         * <p>Elle n'est appelée nulle part dans ce mod, et c'est exprès : elle est la preuve que le
         * registre <b>contient</b> ce que la forme de vanilla aurait contenu, et le recours d'un mod
         * tiers qui aurait besoin de la vérité plutôt que de la région de départ. Son coût est
         * exactement celui que le module évite — c'est-à-dire tout le coût de vanilla — et c'est
         * pourquoi elle ne s'appelle pas toute seule.
         */
        public VoxelShape materialise() {
            VoxelShape shape = this.seed;
            for (AABB box : this.boxes) {
                shape = Shapes.joinUnoptimized(shape, Shapes.create(box), BooleanOp.ONLY_FIRST);
            }
            return shape;
        }
    }

    /**
     * L'indice de cellule d'une coordonnée.
     *
     * <p>{@code Math.floorDiv} et non une division entière : les coordonnées sont négatives sur la
     * moitié du monde, et {@code -1 / 32} vaut zéro en Java là où il doit valoir moins un. Une pièce
     * posée à {@code x = -5} irait alors dans la cellule de {@code x = +5}, et l'on répondrait
     * « libre » à un endroit occupé — une maison dans une autre.
     */
    private static int cell(double coordinate) {
        return Math.floorDiv((int) Math.floor(coordinate), CELL);
    }

    /**
     * La clé d'une cellule.
     *
     * <p>Vingt et un bits par axe, ce qui couvre {@code ±1 048 576} cellules, soit {@code ±33}
     * millions de blocs : bien au-delà de la bordure du monde. Le masque est indispensable — sans
     * lui, un indice négatif remplirait de un tous les bits de poids fort et écraserait les deux
     * autres axes.
     */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    /** Ce que le module a fait, pour les relevés. Voir {@code lab/Bourg} et {@code lab/Quarry}. */
    public static String describe() {
        long regions = taken.sum();
        if (regions == 0L && declined.sum() == 0L) {
            return "AUCUNE structure à pièces rencontrée — le module n'a rien eu à faire, et aucun "
                    + "verdict de génération ne dit quoi que ce soit de lui";
        }
        return String.format(Locale.ROOT,
                "%d région(s) prise(s) en charge, %d laissée(s) à vanilla · %d question(s) de "
                        + "placement · %d boîte(s) inscrite(s) · la région la plus chargée en a "
                        + "porté %d",
                regions, declined.sum(), asked.sum(), filed.sum(), busiest.sum());
    }

    public static long taken() {
        return taken.sum();
    }

    public static long declined() {
        return declined.sum();
    }

    public static long asked() {
        return asked.sum();
    }

    public static long filed() {
        return filed.sum();
    }

    public static long busiest() {
        return busiest.sum();
    }

    public static void reset() {
        taken.reset();
        declined.reset();
        asked.reset();
        filed.reset();
        busiest.reset();
    }

    /**
     * Rend le registre amnésique, et fait donc échouer l'épreuve.
     *
     * <p>Une épreuve qui ne peut pas échouer ne prouve rien — c'est la règle de {@code lab/Duel}. Ici
     * la panne qu'il faut pouvoir provoquer n'est pas une lenteur : c'est une <b>divergence de
     * disposition</b>, c'est-à-dire un village bâti autrement que celui de vanilla.
     *
     * <p>L'interrupteur vise la moitié que <em>ce dépôt</em> a écrite — la recherche d'intersection —
     * et non le terme délégué à vanilla. C'est le bon choix pour deux raisons. La première est qu'on
     * n'éprouve pas le code des autres. La seconde est arithmétique : casser le terme de vanilla ne
     * ferait diverger que les structures assez grandes pour atteindre leur portée maximale, ce qui
     * n'est pas garanti ; casser la recherche d'intersection fait se chevaucher les pièces
     * <b>dès la seconde</b>, puisque la boîte de la pièce centrale est la toute première inscrite.
     *
     * <p>{@code LANTERNE_BREAK_CADASTRE=1}. L'épreuve {@code lab.Bourg} <b>doit</b> alors annoncer
     * une divergence.
     */
    private static final boolean BROKEN = "1".equals(System.getenv("LANTERNE_BREAK_CADASTRE"));

    public static boolean broken() {
        return BROKEN;
    }

}
