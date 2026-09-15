package fr.clubcitrouille.lanterne.core;

import java.util.concurrent.atomic.LongAdder;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Le ciel : une cellule de terrain entièrement vide n'est plus remplie bloc par bloc.
 *
 * <h2>Le défaut</h2>
 *
 * <p>{@code NoiseBasedChunkGenerator.doFill} remplit un chunk cellule par cellule. Une cellule du
 * surmonde fait quatre blocs de côté et huit de haut, et pour chacune la boucle intérieure fait
 * <b>4 × 8 × 4 = 128</b> passages : trois mises à jour d'interpolation, une évaluation de
 * l'aquifère, une descente des règles de matériaux — et un état de bloc qui est jeté à la ligne
 * suivante si c'est de l'air, ce qui est le cas de tout ce qui est au-dessus du sol.
 *
 * <p>Un chunk du surmonde fait 384 blocs de haut, soit <b>48 cellules</b> par colonne et 768
 * cellules en tout. Le sol est vers la dix-septième. Tout le reste est du ciel, et le ciel se paie
 * au même prix que la pierre.
 *
 * <h2>Ce que le module fait, et à quelle condition</h2>
 *
 * <p>Juste après {@code selectCellYZ}, on demande à la cellule si elle est <em>entièrement</em> de
 * l'air. Si oui, les 128 passages ne font plus rien : les trois {@code updateFor*} sont sautés et
 * {@code getInterpolatedState} rend de l'air sans rien calculer. La boucle de vanilla, elle, tourne
 * toujours — voir plus bas pourquoi c'est un choix et non un renoncement.
 *
 * <h2>L'exactitude, et pourquoi elle n'est pas un argument de convexité</h2>
 *
 * <p>Le raisonnement naturel est celui-ci : l'interpolation trilinéaire est une combinaison
 * <em>convexe</em> des huit coins de la cellule, donc huit coins négatifs impliquent un intérieur
 * négatif. C'est un théorème, il est juste, et <b>on ne s'en sert pas</b>. Deux raisons, et la
 * seconde est décisive :
 *
 * <ol>
 *   <li><b>Il n'y a rien à interpoler.</b> {@code fullNoiseDensity} est un
 *       {@code cacheAllInCell(add(finalDensity, beardifier))} : {@code selectCellYZ} y range
 *       <b>les 128 densités de la cellule</b>, déjà interpolées, déjà passées par {@code squeeze},
 *       beardifier compris. {@code getInterpolatedDensity()} ne relance aucun bruit — il lit ce
 *       tableau, à l'index que donnent {@code inCellX/Y/Z}. Et c'est <em>exactement</em> la valeur
 *       que {@code getInterpolatedState} passera à {@code Aquifer.computeSubstance}. On ne raisonne
 *       donc pas sur une borne : on lit le nombre.</li>
 *   <li><b>Le théorème ne survit pas aux datapacks.</b> Le marqueur {@code Interpolated} est posé
 *       <em>au milieu</em> de l'arbre de densité, pas au sommet : en vanilla, {@code squeeze} est
 *       au-dessus et le beardifier s'ajoute encore par-dessus. {@code squeeze} est croissante et
 *       conserve le signe, et un beardifier nul ne change rien — mais rien n'oblige un datapack à
 *       s'en tenir là. Une fonction non monotone posée au-dessus du marqueur, ou une structure qui
 *       « barbe » le terrain en plein ciel, rendrait huit coins négatifs parfaitement compatibles
 *       avec un intérieur positif. Lire le cache est vrai pour <b>tous</b> les mondes ; lire les
 *       coins ne l'est que pour celui de Mojang.</li>
 * </ol>
 *
 * <p>La condition tenue est donc, pour les 128 positions de la cellule :
 *
 * <pre>
 * densité &lt;= 0        ET      le fluide global y rend de l'AIR
 * </pre>
 *
 * <p>et, en tête, une seule comparaison d'entiers : le plancher de la cellule doit être au-dessus
 * de {@code skipSamplingAboveY}. C'est ce qui rend {@code computeSubstance} <em>prévisible</em> :
 *
 * <pre>
 * if (density &gt; 0.0)                  return null;                 // solide — écarté par notre test
 * if (posY &gt; this.skipSamplingAboveY) return globalFluid.at(posY); // ← la seule branche qui reste
 * </pre>
 *
 * <p>{@code FluidStatus.at(y)} vaut {@code y < fluidLevel ? fluidType : AIR}. Au-dessus du niveau
 * de fluide, c'est de l'air, <b>sans un seul échantillonnage</b> : ni grille d'aquifères, ni tirage
 * aléatoire, ni bruit de barrière. La cellule est donc de l'air partout, et on le sait sans avoir
 * rien approché.
 *
 * <p>Restent les deux marches suivantes de {@code doFill}, et elles sont inertes elles aussi :
 * {@code MaterialRuleList} rend la <b>première</b> règle non nulle, donc l'aquifère ayant rendu de
 * l'air, {@code OreVeinifier} n'est pas consulté — pas plus qu'en vanilla ; et le corps de la
 * boucle est tout entier sous {@code if (state != AIR …)}, donc aucun bloc n'est posé, aucune carte
 * de hauteur n'est touchée, aucune position n'est marquée pour post-traitement.
 *
 * <h2>Le seul état qu'on abîme, et la raison pour laquelle ça ne se voit pas</h2>
 *
 * <p>Sauter les {@code updateFor*} laisse trois choses en arrière : les valeurs intermédiaires des
 * interpolateurs, {@code inCellX/Y/Z}, et {@code interpolationCounter}.
 *
 * <ul>
 *   <li>Les valeurs intermédiaires ne sont lues que par {@code NoiseInterpolator.compute} hors
 *       remplissage de cellule — c'est-à-dire par un calcul qui, dans une cellule sautée, n'a pas
 *       lieu. À la cellule suivante, {@code selectCellYZ} puis {@code updateForY/X/Z} les
 *       réécrivent avant le premier calcul.</li>
 *   <li>{@code inCellX/Y/Z} sont réécrits en entier par le remplissage du cache de cellule, que
 *       {@code selectCellYZ} fait à chaque fois. Notre propre examen les emprunte et <b>les
 *       remet</b> : après lui, l'état du {@code NoiseChunk} est celui que vanilla aurait laissé.</li>
 *   <li>{@code interpolationCounter} n'est qu'un jeton de « la position a changé », lu par
 *       {@code CacheOnce}. Il ne fait que croître, et tout calcul est précédé d'un
 *       {@code updateForZ} qui l'incrémente : deux positions distinctes ne peuvent donc jamais
 *       porter le même jeton, qu'on ait sauté des incréments ou non. Sauter n'introduit pas de
 *       réutilisation périmée.</li>
 * </ul>
 *
 * <h2>Ce qu'on ne fait pas : sauter {@code selectCellYZ}</h2>
 *
 * <p>Le vrai gros morceau d'une cellule de ciel est ailleurs : {@code selectCellYZ} remplit le cache
 * de 128 cases, ce qui coûte 128 {@code lerp3} <b>et 128 appels au beardifier</b>. On le paie
 * quand même. Pour l'éviter il faudrait décider avant d'avoir rempli le cache — donc sur les huit
 * coins, donc par l'argument de convexité — et c'est précisément l'argument dont on vient de dire
 * qu'il n'est vrai que pour l'arbre de densité de Mojang. <b>Un monde plus rapide mais différent
 * n'est pas un monde optimisé, c'est un monde cassé</b> : les structures ne tomberaient plus où les
 * cartes les annoncent. Le gain est laissé sur la table, sciemment, et il est rappelé ici pour qui
 * voudrait y revenir avec une vérification de forme d'arbre.
 *
 * <p>Pour la même raison la boucle de {@code doFill} n'est pas court-circuitée d'un saut : la seule
 * façon de le faire en Mixin est de trafiquer le compteur de boucle par son emplacement dans la
 * table des variables locales, et ce dépôt s'interdit de viser autre chose qu'une signature
 * complète ({@code JigsawPlacerMixin} dit pourquoi). On paie donc le squelette de la boucle — de
 * l'arithmétique sur des entiers et un {@code getSectionIndex} tous les huit passages — et l'on
 * vide son corps.
 *
 * <p>Il y a un bénéfice inattendu à garder la boucle : {@code debugPreliminarySurfaceLevel} continue
 * de tourner, sur exactement l'état que vanilla lui aurait donné. {@code SharedConstants.DEBUG_AQUIFERS}
 * n'a donc pas à désactiver le module — les blocs de miel et de slime du mode de mise au point sont
 * posés comme avant.
 *
 * <h2>Ce que ce module ne touche pas</h2>
 *
 * <p>Seul l'aquifère <b>bruité</b> est pris en charge. Quand les aquifères sont désactivés — le
 * Nether, l'End — {@code Aquifer.createDisabled} rend une classe anonyme dont on ne peut atteindre
 * le sélecteur de fluide sans viser {@code Aquifer$1}, c'est-à-dire un numéro attribué par le
 * compilateur. Ces dimensions ne gagnent rien. Le Nether ne fait que 128 blocs de haut et n'a
 * presque pas de ciel ; l'End en a beaucoup, et c'est le morceau à reprendre si quelqu'un trouve
 * une prise honnête.
 *
 * <h2>Quelle fraction, et pourquoi pas les deux tiers</h2>
 *
 * <p>Ce n'est <b>pas</b> le sommet du terrain qui fixe la limite, c'est
 * {@code skipSamplingAboveY}, et il est franchement plus haut :
 *
 * <pre>
 * skipSamplingAboveY = (floorDiv(maxSurfacePreliminaire + 8 + 12, 12) + 1) × 12 + 10
 * </pre>
 *
 * <p>soit le point le plus haut du <em>voisinage</em> de trois chunks, majoré de vingt à trente
 * blocs. Sur une plaine dont la surface préliminaire culmine vers 75, cela donne environ 106 : les
 * cellules court-circuitées sont celles dont le plancher dépasse 106, c'est-à-dire de l'ordre de
 * <b>la moitié</b> des 48. Les deux tiers annoncés par le raisonnement de départ supposaient qu'on
 * puisse descendre jusqu'au terrain ; la marge de l'aquifère coûte cinq à huit cellules par colonne.
 * En montagne, moins encore.
 *
 * <h2>Rien n'a été mesuré</h2>
 *
 * <p>Ce module est écrit, pas éprouvé : aucun banc n'a été lancé. La règle du dépôt est qu'un module
 * non prouvé reste éteint ; celui-ci est allumé par défaut parce que {@code Config.CIEL} l'était
 * déjà, et c'est la mesure qui tranchera. Les compteurs ci-dessous sont là pour ça : le taux de
 * cellules court-circuitées est le premier des trois nombres — taux, épargne, appels — et c'est
 * toujours celui qui démolit les plans.
 */
public final class Ciel {
    /**
     * L'air rendu à la place du calcul.
     *
     * <p>C'est le <b>même objet</b> que le {@code AIR} privé de {@code NoiseBasedChunkGenerator} :
     * {@code defaultBlockState()} est un singleton par bloc, et la boucle de {@code doFill} compare
     * par référence ({@code state != AIR}). Rendre un autre état d'air ferait poser des blocs.
     */
    public static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /**
     * Ce que le mixin ajoute à {@code NoiseChunk} : la cellule courante est-elle du ciel ?
     *
     * <p>Le drapeau vit sur le {@code NoiseChunk} et non sur le générateur, et ce n'est pas un
     * détail : le générateur est un objet <b>partagé</b> par tous les fils de génération, alors
     * qu'un {@code NoiseChunk} appartient à un chunk et n'est vu que par le fil qui le remplit. Un
     * champ statique ou un champ du générateur demanderait un verrou ou un {@code ThreadLocal} pour
     * dire la même chose.
     */
    public interface Cell {
        /**
         * Examine la cellule que {@code selectCellYZ} vient de sélectionner.
         *
         * <p>Appelée une fois par cellule, avant la boucle intérieure. Elle doit laisser le
         * {@code NoiseChunk} dans l'état exact où {@code selectCellYZ} l'a laissé.
         */
        void lanterne$examineCell();

        /** La cellule courante est-elle de l'air de part en part ? */
        boolean lanterne$skyCell();
    }

    /** Cellules examinées depuis le démarrage. */
    private static final LongAdder examined = new LongAdder();

    /** Cellules dont la boucle intérieure a été vidée. */
    private static final LongAdder emptied = new LongAdder();

    /**
     * Déclare vide <b>toute</b> cellule, y compris celles qui ne le sont pas.
     *
     * <p>Une épreuve qui ne peut pas échouer ne prouve rien. La panne à pouvoir provoquer ici n'est
     * pas une lenteur, c'est un <b>terrain différent</b> : avec cet interrupteur le monde engendré
     * est creux, et le contrôle ci-dessous doit le voir.
     *
     * <p>{@code LANTERNE_BREAK_CIEL=1}.
     */
    private static final boolean BROKEN = "1".equals(System.getenv("LANTERNE_BREAK_CIEL"));

    /**
     * Le contrôle : refaire le travail qu'on évite, et comparer ce qu'il rend.
     *
     * <p>Pour chaque cellule déclarée vide, on appelle réellement {@code getInterpolatedState} aux
     * 128 positions et l'on vérifie que chacune rend l'air — le <em>même</em> objet {@code AIR} que
     * la boucle de {@code doFill} compare. Ce n'est pas un échantillon de terrain : c'est la
     * question exacte que le module prétend avoir déjà répondue, posée à vanilla.
     *
     * <p>Ce mode fait donc exactement ce que le module est censé épargner. Il sert à prouver, pas à
     * produire. {@code LANTERNE_CIEL_AUDIT=1}.
     */
    private static final boolean AUDIT = "1".equals(System.getenv("LANTERNE_CIEL_AUDIT"));

    /** Positions contrôlées, et celles où vanilla n'aurait pas mis de l'air. */
    private static final LongAdder checked = new LongAdder();
    private static final LongAdder faults = new LongAdder();

    private Ciel() {}

    /** Voir {@link #BROKEN}. */
    public static boolean broken() {
        return BROKEN;
    }

    /** Voir {@link #AUDIT}. */
    public static boolean auditing() {
        return AUDIT;
    }

    /** Note le verdict rendu sur une cellule. */
    public static void examine(boolean empty) {
        examined.increment();
        if (empty) {
            emptied.increment();
        }
    }

    /** Note une position contrôlée en mode audit. */
    public static void check(boolean air) {
        checked.increment();
        if (!air) {
            faults.increment();
        }
    }

    /** Cellules examinées depuis le démarrage. */
    public static long examined() {
        return examined.sum();
    }

    /** Cellules dont la boucle intérieure a été vidée. */
    public static long emptied() {
        return emptied.sum();
    }

    /** Positions contrôlées en mode audit. */
    public static long checked() {
        return checked.sum();
    }

    /**
     * Positions où le module s'est trompé.
     *
     * <p>Doit valoir zéro. Un seul écart signifie un monde différent de celui de vanilla, donc un
     * module à éteindre — pas à régler.
     */
    public static long faults() {
        return faults.sum();
    }

    /**
     * Le premier des trois nombres : la part des cellules court-circuitées.
     *
     * <p>Rendue en pour cent. Zéro veut dire que le module ne sert à rien, quelle que soit
     * l'épargne par cellule — c'est la leçon que {@code Solid} a coûté à ce dépôt.
     */
    public static double rate() {
        long seen = examined.sum();
        return seen == 0L ? 0d : 100d * emptied.sum() / seen;
    }
}
