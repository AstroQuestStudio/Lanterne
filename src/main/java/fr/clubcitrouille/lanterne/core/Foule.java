package fr.clubcitrouille.lanterne.core;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.Continuation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.phys.AABB;

/**
 * La foule : subdiviser la section d'entités, parce que le jeu ne la subdivise pas.
 *
 * <h2>Le terme qui croît avec le carré, et le calcul qui le dit</h2>
 *
 * <p>Le jeu range les entités par <b>section</b> — un cube de seize blocs de côté, quatre mille
 * quatre-vingt-seize blocs cubes. C'est la seule subdivision qui existe, et toute recherche de
 * voisinage s'arrête là :
 *
 * <pre>
 * public Continuation getEntities(AABB bb, AbortableIterationConsumer&lt;T&gt; entities) {
 *     for (T entity : this.storage) {                        // TOUTE la section
 *         if (entity.getBoundingBox().intersects(bb) &amp;&amp; ...
 * </pre>
 *
 * <p>Le parcours porte sur le contenu entier de la section, jamais sur ce qui est dans la boîte
 * demandée. Une requête coûte donc {@code n} tests d'intersection, où {@code n} est le nombre
 * d'entités de la section — <b>et non le nombre de réponses</b>.
 *
 * <p>Or chaque entité pose au moins une requête par tick : la poussée ({@code Shove.pushables}), la
 * collision ({@code EntityGetter.getEntityCollisions}), la fusion des objets au sol, celle des
 * orbes. Le total du tick est donc {@code n × n}. Dans un enclos d'un chunk :
 *
 * <pre>
 *   250 bêtes →    62 500 tests d'intersection par tick
 *  1000 bêtes →  1 000 000
 *  4000 bêtes → 16 000 000
 * </pre>
 *
 * <p>Seize fois plus de bêtes, <b>deux cent cinquante-six fois</b> plus de travail. L'exposant vaut
 * deux, et c'est la définition même de ce qu'un serveur à un cœur ne peut pas absorber : à mille
 * bêtes le poste est déjà le plus lourd du profil — {@code EntitySection.getEntities} y pèse
 * vingt-quatre pour cent du travail réel, mod actif, d'après le profil de l'enclos cité par
 * {@link Shove} — et il double de part à chaque doublement du troupeau.
 *
 * <h2>Ce que {@link Shove} avait déjà fait, et ce qu'il ne pouvait pas faire</h2>
 *
 * <p>{@code Shove} interrompt le parcours dès qu'il a trouvé assez de voisines à pousser. C'est le
 * bon geste et il est acquis — mais il ne porte que sur le cas <b>saturé</b> : il faut que trente-
 * trois candidates aient effectivement touché la boîte pour que l'interruption tombe. Dans un enclos
 * d'un chunk, mille vaches réparties sur deux cent cinquante-six blocs au sol n'en mettent qu'une
 * poignée dans la boîte d'une voisine ; l'interruption ne se déclenche jamais, et le parcours va
 * jusqu'au bout des mille.
 *
 * <p>Et surtout, {@code Shove} ne vaut que pour la poussée. La collision, la fusion des objets,
 * l'explosion, le ciblage — tout cela repose sur le même parcours, et personne ne les a bornés.
 * <b>Le défaut n'est pas chez les appelants, il est dans le rangement.</b> On le corrige donc là.
 *
 * <h2>Le remède : une grille de deux blocs, dans la section</h2>
 *
 * <p>Une grille de huit cellules par axe découpe la section en cinq cent douze cellules de deux
 * blocs de côté. Une requête ne visite plus que les cellules que sa boîte touche — élargie de la
 * <b>plus grande entité présente</b>, sans quoi une vache dont le centre est dans la cellule voisine
 * mais dont le flanc dépasse serait perdue.
 *
 * <p>Sur la boîte de poussée d'une vache — un mètre trente de côté — et une marge d'un mètre quarante
 * (la hauteur d'une vache), la région balayée fait environ cent cinquante blocs cubes au lieu de
 * quatre mille quatre-vingt-seize : <b>un vingt-septième</b> du parcours. Le terme reste quadratique
 * — il l'est par nature, puisque la réponse elle-même grandit avec la densité — mais sa constante
 * est divisée par un ordre de grandeur, et c'est tout ce qu'on peut obtenir sans changer la réponse.
 *
 * <h2>Pourquoi l'entretien est incrémental, et pourquoi il ne peut pas être autre chose</h2>
 *
 * <p>La tentation est de reconstruire la grille une fois par tick. Elle ne marche pas, et l'échec est
 * instructif : dans un tick, les déplacements et les requêtes sont <b>entrelacés</b>. La vache numéro
 * un bouge, puis interroge ; la numéro deux bouge, puis interroge. Une grille invalidée par tout
 * déplacement serait reconstruite {@code n} fois par tick, en {@code O(n)} chacune — soit exactement
 * le {@code O(n²)} qu'on voulait supprimer.
 *
 * <p>L'entretien est donc <b>incrémental</b> : chaque déplacement replace son entité, et rien de
 * plus. Le point d'accroche est {@code PersistentEntitySectionManager$Callback.onMove}, que le jeu
 * appelle depuis {@code Entity.setPosRaw} à <b>chaque</b> changement de position — c'est le seul
 * chemin par lequel une position peut changer, {@code setPosRaw} étant {@code final} et le champ
 * {@code position} privé. Aucun déplacement ne peut donc échapper à la grille.
 *
 * <h2>Ce que cela ne change pas, et l'unique chose que cela change</h2>
 *
 * <p><b>L'ensemble rendu est identique.</b> Le test d'intersection final est celui du jeu, appliqué
 * aux mêmes entités ; la grille ne fait que dispenser de regarder celles qui ne peuvent pas répondre.
 * La marge est une borne supérieure prouvée : si la boîte d'une entité touche la boîte demandée,
 * alors sa position est à moins d'une taille de boîte de celle-ci sur chaque axe, donc dans les
 * cellules balayées.
 *
 * <p><b>L'ordre de parcours change</b>, et c'est la seule concession. Le jeu rend les entités dans
 * l'ordre d'insertion de la section ; la grille les rend cellule par cellule. Aucun appelant de
 * {@code getEntities} ne dépend de cet ordre — les uns collectent une liste, les autres cherchent un
 * minimum de distance, aucun ne lit « la première ». {@code Shove} garde bien les vingt-six
 * premières, mais son propre commentaire le dit : ces vingt-six-là étaient déjà arbitraires.
 * Et la question ne se pose que dans une foule : <b>en deçà de {@link #SEUIL} entités dans une
 * section, aucune grille n'est construite et rien n'est touché.</b>
 *
 * <h2>Ce qui pourrait casser, dit sans détour</h2>
 *
 * <ul>
 *   <li><b>Une entité perdue.</b> C'est le seul dégât possible, et il serait invisible : deux vaches
 *       qui ne se poussent plus se superposent. Trois garde-fous le rendent impossible — le compte
 *       de la grille est comparé à celui de la section à chaque requête, toute position hors de la
 *       section fait lâcher la grille, et toute grille lâchée rend la main au jeu.</li>
 *   <li><b>Une entité qui grandit sans bouger.</b> La marge est une borne sur les tailles vues. Un
 *       changement de posture modifie la boîte sans changer la position ; {@code FouleEntityMixin}
 *       branche donc {@code refreshDimensions}, qui est le seul chemin vanilla vers un changement de
 *       taille.</li>
 *   <li><b>Un mod qui appelle {@code setBoundingBox} avec une boîte d'une autre taille</b>, sans
 *       passer par {@code refreshDimensions}. La marge ne le verrait pas. Aucun code du jeu ne fait
 *       cela ; c'est le seul angle mort assumé.</li>
 *   <li><b>Un appelant qui retire une entité depuis son propre consommateur.</b> Le parcours
 *       sauterait un voisin. Vanilla parcourt une {@code ArrayList} et souffre du même défaut ; on
 *       n'aggrave rien.</li>
 * </ul>
 *
 * <h2>Ce qui a été cherché et écarté, pour éviter de le remesurer</h2>
 *
 * <p><b>{@code Mob.checkDespawn} n'est pas quadratique, et la piste est close.</b> Le balayage du
 * joueur le plus proche est bien non borné en <em>distance</em>, mais pas en nombre :
 *
 * <pre>
 * default Player getNearestPlayer(double x, double y, double z, double range, Predicate&lt;Entity&gt; p) {
 *     for (Player player : this.players()) {     // la liste des JOUEURS, pas celle des entités
 * </pre>
 *
 * <p>Le coût vaut donc {@code n × P}, où {@code P} est le nombre de joueurs — linéaire en entités, et
 * de l'ordre de la dizaine de microsecondes pour mille bêtes. Les branches mortes existent bien
 * ({@code Animal.removeWhenFarAway} rend {@code false} sans condition, et {@code noActionTime} n'est
 * jamais relu pour un animal : seuls {@code Raid}, {@code Raids} et {@code Raider} le consultent),
 * mais les supprimer rendrait des microsecondes. Et le vrai coût de cet appel — l'évènement que
 * NeoForge y alloue, trois cent neuf mégaoctets au profileur — est <b>déjà</b> traité par
 * {@code MobMixin}, qui aligne l'examen sur la cadence de la créature. Il n'y a rien à reprendre.
 *
 * <p><b>{@code EntityTickList} ne recopie pas sa liste par tick.</b> La recopie de
 * {@code ensureActiveIsNotIterated} n'a lieu qu'au <em>premier</em> ajout ou retrait survenant
 * pendant un parcours : après l'échange, {@code iterated != active} et les suivants ne coûtent rien.
 * Au pire une recopie par tick, soit {@code O(n)}. Rien à faire.
 *
 * <p><b>{@code EntitySectionStorage.forEachAccessibleNonEmptySection} balaie un plan entier du
 * monde, et cela n'a pas été corrigé ici faute de mesure.</b> Le fait est pourtant net :
 *
 * <pre>
 * long bas  = SectionPos.asLong(x,  0,  0);
 * long haut = SectionPos.asLong(x, -1, -1);
 * LongIterator it = this.sectionIds.subSet(bas, haut + 1L).iterator();   // TOUT le plan X
 * while (it.hasNext()) { … if (y &gt;= yMin &amp;&amp; y &lt;= yMax &amp;&amp; z &gt;= zMin &amp;&amp; z &lt;= zMax) … }
 * </pre>
 *
 * <p>La clé range x sur les bits 42 à 63, z sur 20 à 41, y sur 0 à 19 : l'intervalle demandé couvre
 * donc <b>toutes les sections non vides de ce plan X, à toutes les hauteurs et à tous les Z</b>, et
 * le filtre ne vient qu'après. Une requête de voisinage d'un mètre de côté parcourt ainsi quelques
 * dizaines à quelques centaines de nœuds d'un arbre AVL. Sur un monde où les entités sont
 * <em>dispersées</em>, le nombre de sections non vides croît avec leur nombre, et le terme redevient
 * quadratique.
 *
 * <p>La correction serait courte et <b>identique au bit près</b> : itérer chunk par chunk sur
 * l'intervalle demandé, chaque chunk ayant déjà son propre sous-intervalle contigu
 * ({@code getChunkSections}). L'ordre est même conservé, puisque pour un x donné la clé s'ordonne
 * d'abord par z puis par y — à condition d'énumérer les z dans l'ordre de leur champ brut, les
 * positifs avant les négatifs.
 *
 * <p>Elle n'est pas ici parce qu'<b>aucun banc de ce dépôt ne peut la juger</b> : il y faudrait un
 * monde largement chargé et des entités réparties sur des centaines de chunks, quand {@code Cohue} et
 * {@code Cheptel} mesurent un troupeau serré autour d'une doublure. Écrire une optimisation qu'on ne
 * peut pas éprouver, c'est {@link Ecluse} — et {@link Ecluse} est éteint. La piste est donc laissée
 * écrite plutôt qu'écrite à moitié.
 *
 * <h2>Le côté serveur seulement, et comment on le sait</h2>
 *
 * <p>Le client range ses entités dans un {@code TransientEntitySectionManager}, dont le rappel de
 * déplacement n'est pas branché. Une grille y serait tenue par personne et se périmerait au premier
 * pas. On ne la construit donc que sur une section qui a vu entrer une entité dont le monde est un
 * {@code ServerLevel} — le seul renseignement de côté dont une section dispose.
 */
public final class Foule {
    /** Blocs par cellule. Deux : la boîte de poussée d'une vache en couvre à peine plus d'une. */
    public static final int ARETE = 2;

    /** Cellules par axe. Huit, puisqu'une section fait seize blocs. */
    public static final int COTE = 16 / ARETE;

    /**
     * Décalage qui transforme un bloc relatif en numéro de cellule.
     *
     * <p>Déduit de {@link #ARETE} plutôt qu'écrit en dur : un {@code >> 1} oublié quelque part
     * ferait ranger les entités dans des cellules que les requêtes ne visiteraient pas, et le défaut
     * serait une entité manquante — la seule forme d'erreur que ce module ne peut pas se permettre.
     */
    private static final int DECALAGE = Integer.numberOfTrailingZeros(ARETE);

    /** Cellules d'une grille : cinq cent douze références, soit quatre kilo-octets. */
    public static final int CELLULES = COTE * COTE * COTE;

    /**
     * Entités dans une section en deçà desquelles on ne construit rien.
     *
     * <p>Soixante-quatre entités dans quatre mille blocs cubes, c'est déjà une ferme. En deçà, le
     * parcours vanilla tient dans une ligne de cache et la grille coûterait plus qu'elle ne rend —
     * et surtout, <b>rien n'est touché</b> : même ordre, même code, même résultat qu'un jeu nu.
     */
    public static final int SEUIL = 64;

    /**
     * Entités en deçà desquelles une grille existante est rendue.
     *
     * <p>Volontairement plus bas que {@link #SEUIL} : sans cet écart, une section qui oscille autour
     * du seuil construirait et détruirait sa grille plusieurs fois par seconde.
     */
    public static final int LACHER = 32;

    /**
     * Taille au-delà de laquelle une entité sort de la grille.
     *
     * <p>Sans cela, un seul ghast de quatre mètres porterait la marge de toute la section à quatre
     * mètres, et les mille vaches paieraient son gabarit. Les géants sont donc rangés à part et
     * balayés à chaque requête — ils sont rares, et vanilla emploie déjà ce même seuil de quatre
     * sous le nom de {@code MAX_NON_CHONKY_ENTITY_SIZE}.
     */
    public static final double GEANT = 4.0d;

    /** Rang des géants dans la table des places. */
    private static final int RANG_GEANT = -1;

    /** Rang d'une entité que la grille ne connaît pas. */
    private static final int ABSENT = Integer.MIN_VALUE;

    /**
     * Grilles vivantes, tous mondes confondus.
     *
     * <p>Sert de sortie immédiate aux deux points d'accroche froids : tant qu'aucune section n'est
     * en foule, {@code refreshDimensions} et le rappel de déplacement ne font qu'une lecture de
     * champ statique.
     */
    private static int armees;

    /** Interrupteur de banc : {@code lab/Cohue} éteint le module sans toucher aux réglages. */
    private static boolean bancEteint;

    /**
     * Le rappel de déplacement a-t-il seulement été branché ?
     *
     * <h2>La seule combinaison qui ferait vraiment des dégâts</h2>
     *
     * <p>Trois mixins portent ce module. Deux d'entre eux forment un couple indissociable :
     * {@code FouleSectionMixin} construit la grille, {@code FouleMoveMixin} la tient à jour. Si le
     * premier s'appliquait et le second non — conflit avec un autre mod, changement de version,
     * classe interne renommée — des grilles naîtraient que personne n'entretiendrait, et les entités
     * seraient cherchées là où elles ne sont plus. C'est le seul scénario où ce module ferait du mal,
     * et il serait <b>invisible</b>.
     *
     * <p>{@code defaultRequire: 1} ferait normalement échouer le démarrage, bruyamment, et c'est la
     * bonne façon de casser. Ce drapeau est la ceinture qui va avec : aucune grille n'est construite
     * tant qu'un déplacement n'est pas réellement passé par le rappel. Sur un serveur, cela arrive
     * dans le premier tick ; si cela n'arrive jamais, le module ne fait simplement rien.
     */
    private static boolean suivi;

    private Foule() {}

    /**
     * Le module agit-il ?
     *
     * <p>L'interrupteur général compte, bien qu'{@code il ne figure pas dans Settings.foule()} : un
     * banc entrelacé qui coupe le mod doit couper <b>toutes</b> les optimisations, sinon la moitié
     * éteinte n'est pas celle qu'on croit et la comparaison ne vaut rien.
     */
    public static boolean actif() {
        return !bancEteint && Settings.enabled() && Settings.foule();
    }

    /** Vrai tant qu'au moins une grille existe. Lecture d'un champ, rien de plus. */
    public static boolean tenues() {
        return armees > 0;
    }

    /** Éteint ou rallume le module pour la durée d'une mesure. Voir {@code lab/Cohue}. */
    public static void banc(boolean eteint) {
        bancEteint = eteint;
    }

    /**
     * Ce qu'une {@code EntitySection} expose à ce module, via {@code FouleSectionMixin}.
     *
     * <p>Le duck-typing de Mixin plutôt qu'un champ statique indexé par section : une table
     * {@code section → grille} coûterait un hachage à chaque requête, soit précisément ce qu'on
     * cherche à supprimer.
     */
    public interface Section {
        Grille lanterne$grille();

        void lanterne$grille(Grille grille);

        boolean lanterne$serveur();

        int lanterne$taille();

        Iterable<? extends EntityAccess> lanterne$contenu();
    }

    /**
     * Ce qu'un rappel de déplacement expose, via {@code FouleMoveMixin}.
     *
     * <p>{@code Entity} ne connaît pas sa section ; il ne connaît que son rappel. C'est donc par le
     * rappel que {@code refreshDimensions} rejoint la grille.
     */
    public interface Suivi {
        void lanterne$replace();
    }

    /**
     * Replace une entité dans la grille de sa section, ou lâche la grille si c'est impossible.
     *
     * <p>Appelé à chaque déplacement et à chaque changement de gabarit. Quand aucune grille n'existe
     * — le cas de l'immense majorité des sections — le coût est une lecture statique.
     */
    public static void replace(Object section, Object habitant) {
        if (!suivi) {
            suivi = true;
        }
        if (armees <= 0 || !(section instanceof Section logis)) {
            return;
        }
        Grille grille = logis.lanterne$grille();
        if (grille == null) {
            return;
        }
        if (!actif() || !(habitant instanceof Entity bete) || !grille.place(bete)) {
            lache(logis);
        }
    }

    /**
     * La grille utilisable d'une section, ou {@code null} pour dire « laisse faire le jeu ».
     *
     * <p>Toute la décision est ici, et elle est prise à chaque requête parce qu'elle est bon marché :
     * quatre comparaisons. C'est aussi le seul endroit où une grille naît, ce qui garantit qu'elle
     * naît au moment où toutes les positions sont stables — jamais au milieu d'un {@code setPos}.
     *
     * <p>Le contrôle de compte est le garde-fou principal : si un ajout ou un retrait avait échappé
     * à la grille, elle décrirait une section qui n'existe plus, et une entité pourrait manquer à
     * l'appel. On préfère alors la jeter et repartir du jeu.
     */
    public static Grille grilleDe(Section logis) {
        Grille grille = logis.lanterne$grille();
        if (grille == null) {
            // L'ordre des tests n'est pas indifférent : la très grande majorité des sections du
            // monde n'a pas de grille et n'en aura jamais. Le chemin le plus fréquent doit donc être
            // le plus court — deux lectures de champ, et l'on rend la main.
            if (!logis.lanterne$serveur() || !suivi
                    || logis.lanterne$taille() < SEUIL || !actif()) {
                return null;
            }
            grille = batit(logis);
            if (grille != null) {
                logis.lanterne$grille(grille);
            }
            return grille;
        }
        if (!actif() || grille.compte() != logis.lanterne$taille()) {
            lache(logis);
            return null;
        }
        return grille;
    }

    /** Une entité entre dans la section : la grille la range, ou se retire. */
    public static void entre(Section logis, Object habitant) {
        Grille grille = logis.lanterne$grille();
        if (grille == null) {
            return;
        }
        if (!(habitant instanceof Entity bete) || !grille.place(bete)) {
            lache(logis);
        }
    }

    /** Une entité quitte la section. Sous {@link #LACHER}, la grille n'a plus de raison d'être. */
    public static void sort(Section logis, Object habitant) {
        Grille grille = logis.lanterne$grille();
        if (grille == null) {
            return;
        }
        if (habitant instanceof Entity bete) {
            grille.retire(bete);
        }
        if (logis.lanterne$taille() < LACHER) {
            lache(logis);
        }
    }

    /** Rend la grille d'une section au ramasse-miettes, et rend la main au jeu. */
    public static void lache(Section logis) {
        if (logis.lanterne$grille() != null) {
            logis.lanterne$grille(null);
            armees--;
        }
    }

    /**
     * Construit la grille d'une section, ou rend {@code null} si la section s'y refuse.
     *
     * <p>Le refus n'est pas une panne : il vaut « le jeu garde la main ». Une section dont une entité
     * n'est pas un {@code Entity}, ou dont une position tombe hors du cube, n'est pas indexable — et
     * dans ce cas le parcours vanilla est juste, seulement plus lent.
     */
    public static Grille batit(Section logis) {
        java.util.Iterator<? extends EntityAccess> curseur = logis.lanterne$contenu().iterator();
        if (!curseur.hasNext()) {
            return null;
        }
        EntityAccess premier = curseur.next();
        var repere = premier.blockPosition();
        Grille grille = new Grille(
                SectionPos.blockToSectionCoord(repere.getX()) << 4,
                SectionPos.blockToSectionCoord(repere.getY()) << 4,
                SectionPos.blockToSectionCoord(repere.getZ()) << 4);
        for (EntityAccess habitant : logis.lanterne$contenu()) {
            if (!(habitant instanceof Entity bete) || !grille.place(bete)) {
                return null;
            }
        }
        armees++;
        return grille;
    }

    /**
     * Le rangement fin d'une section : cinq cent douze cellules, et les géants à part.
     *
     * <p>Toutes les positions sont exprimées en blocs relatifs au coin bas de la section, ce qui
     * permet de n'employer que des entiers et un décalage de bit pour trouver la cellule.
     */
    public static final class Grille {
        /** Les cellules, allouées à la première entité qui s'y range. */
        private final ObjectArrayList<Entity>[] cellules;

        /** Où chaque entité est rangée. {@link #RANG_GEANT} pour les hors-gabarit. */
        private final Reference2IntOpenHashMap<Entity> places = new Reference2IntOpenHashMap<>();

        /** Les hors-gabarit, balayés à chaque requête. */
        private final ObjectArrayList<Entity> geants = new ObjectArrayList<>();

        /** Coin bas de la section, en blocs absolus. */
        private final int coinX;
        private final int coinY;
        private final int coinZ;

        /**
         * La plus grande taille de boîte vue, par axe.
         *
         * <p>C'est la marge d'élargissement de toute requête, et elle ne décroît jamais tant que la
         * grille vit — une marge trop large ne coûte que du balayage, une marge trop courte perdrait
         * une entité. On ne prend donc jamais le risque de la resserrer.
         */
        private double margeX;
        private double margeY;
        private double margeZ;

        @SuppressWarnings("unchecked")
        private Grille(int coinX, int coinY, int coinZ) {
            this.cellules = new ObjectArrayList[CELLULES];
            this.coinX = coinX;
            this.coinY = coinY;
            this.coinZ = coinZ;
            this.places.defaultReturnValue(ABSENT);
        }

        /** Nombre d'entités connues de la grille — géants compris. */
        public int compte() {
            return this.places.size();
        }

        /**
         * Range ou replace une entité. Rend {@code false} si elle n'a rien à faire dans cette section.
         *
         * <p>Le cas courant — une bête qui a avancé de dix centimètres sans quitter sa cellule de
         * deux mètres — ne coûte qu'un hachage et une comparaison d'entiers. Ce n'est qu'en
         * franchissant une frontière de cellule qu'il y a deux listes à toucher.
         */
        public boolean place(Entity bete) {
            AABB boite = bete.getBoundingBox();
            double tailleX = boite.getXsize();
            double tailleY = boite.getYsize();
            double tailleZ = boite.getZsize();

            int voulu;
            if (tailleX > GEANT || tailleY > GEANT || tailleZ > GEANT) {
                voulu = RANG_GEANT;
            } else {
                int relX = Mth.floor(bete.getX()) - this.coinX;
                int relY = Mth.floor(bete.getY()) - this.coinY;
                int relZ = Mth.floor(bete.getZ()) - this.coinZ;
                // Une entité hors du cube signale que la grille ne décrit plus cette section. On ne
                // devine pas, on rend la main : le parcours du jeu est toujours juste.
                if (relX < 0 || relX > 15 || relY < 0 || relY > 15 || relZ < 0 || relZ > 15) {
                    return false;
                }
                voulu = (((relX >> DECALAGE) * COTE) + (relY >> DECALAGE)) * COTE
                        + (relZ >> DECALAGE);
                if (tailleX > this.margeX) {
                    this.margeX = tailleX;
                }
                if (tailleY > this.margeY) {
                    this.margeY = tailleY;
                }
                if (tailleZ > this.margeZ) {
                    this.margeZ = tailleZ;
                }
            }

            int actuel = this.places.getInt(bete);
            if (actuel == voulu) {
                return true;
            }
            if (actuel != ABSENT) {
                this.oter(actuel, bete);
            }
            this.pose(voulu, bete);
            this.places.put(bete, voulu);
            return true;
        }

        /** Retire une entité de la grille. Sans effet si elle n'y était pas. */
        public void retire(Entity bete) {
            int actuel = this.places.removeInt(bete);
            if (actuel != ABSENT) {
                this.oter(actuel, bete);
            }
        }

        private void pose(int rang, Entity bete) {
            if (rang == RANG_GEANT) {
                this.geants.add(bete);
                return;
            }
            ObjectArrayList<Entity> cellule = this.cellules[rang];
            if (cellule == null) {
                cellule = new ObjectArrayList<>(4);
                this.cellules[rang] = cellule;
            }
            cellule.add(bete);
        }

        private void oter(int rang, Entity bete) {
            ObjectArrayList<Entity> cellule =
                    rang == RANG_GEANT ? this.geants : this.cellules[rang];
            if (cellule == null) {
                return;
            }
            // Retrait par identité et permutation avec la dernière : l'ordre dans une cellule n'a
            // aucun sens, et le décalage d'une ArrayList en aurait un coût.
            for (int i = cellule.size() - 1; i >= 0; i--) {
                if (cellule.get(i) == bete) {
                    Entity derniere = cellule.remove(cellule.size() - 1);
                    if (i < cellule.size()) {
                        cellule.set(i, derniere);
                    }
                    return;
                }
            }
        }

        /**
         * Le parcours accéléré : mêmes entités rendues, mêmes tests d'intersection, moins de trajet.
         *
         * <p>Les géants d'abord, puis les cellules que la boîte élargie touche. Le test final est
         * celui du jeu, mot pour mot, et l'interruption est propagée telle quelle.
         */
        public Continuation parcourt(
                AABB boite, AbortableIterationConsumer<EntityAccess> sortie) {
            for (int i = 0; i < this.geants.size(); i++) {
                Entity bete = this.geants.get(i);
                if (bete.getBoundingBox().intersects(boite) && sortie.accept(bete).shouldAbort()) {
                    return Continuation.ABORT;
                }
            }

            int basX = borne(boite.minX - this.margeX, this.coinX);
            int hautX = borne(boite.maxX + this.margeX, this.coinX);
            int basY = borne(boite.minY - this.margeY, this.coinY);
            int hautY = borne(boite.maxY + this.margeY, this.coinY);
            int basZ = borne(boite.minZ - this.margeZ, this.coinZ);
            int hautZ = borne(boite.maxZ + this.margeZ, this.coinZ);

            for (int cx = basX; cx <= hautX; cx++) {
                for (int cy = basY; cy <= hautY; cy++) {
                    int rang = (cx * COTE + cy) * COTE;
                    for (int cz = basZ; cz <= hautZ; cz++) {
                        ObjectArrayList<Entity> cellule = this.cellules[rang + cz];
                        if (cellule == null) {
                            continue;
                        }
                        // La taille est relue à chaque tour : un consommateur qui retire une entité
                        // du monde vide la cellule sous nos pieds, et vanilla n'y survit pas mieux —
                        // mais au moins on ne sort pas du tableau.
                        for (int i = 0; i < cellule.size(); i++) {
                            Entity bete = cellule.get(i);
                            if (bete.getBoundingBox().intersects(boite)
                                    && sortie.accept(bete).shouldAbort()) {
                                return Continuation.ABORT;
                            }
                        }
                    }
                }
            }
            return Continuation.CONTINUE;
        }

        /**
         * La cellule d'une coordonnée, ramenée de force dans la section.
         *
         * <p>Le rabotage ne peut qu'élargir le balayage, jamais le rétrécir : une borne qui tombe
         * sous la section devient la cellule zéro, qui est bien la première à contenir quelque chose
         * de pertinent. C'est la forme d'erreur qu'on accepte — celle qui coûte du temps et non une
         * entité.
         */
        private static int borne(double valeur, int coin) {
            return Mth.clamp((Mth.floor(valeur) - coin) >> DECALAGE, 0, COTE - 1);
        }
    }
}
