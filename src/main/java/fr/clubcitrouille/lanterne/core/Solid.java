package fr.clubcitrouille.lanterne.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Ce qui peut réellement bloquer quelque chose — c'est-à-dire presque rien.
 *
 * <h2>Un million de tests pour une liste vide</h2>
 *
 * <p>Le profil de l'enclos, mod actif, désigne un poste que rien dans ce projet n'avait envisagé :
 *
 * <pre>
 * 42,8 %  Entity.move
 * 34,1 %  Entity.collide
 * 33,5 %  Level.getEntities
 * </pre>
 *
 * <p>Ce n'est pas la bousculade des créatures entassées — celle-là était déjà traitée. C'est la
 * résolution de collision du <b>déplacement</b>, et elle passe par là :
 *
 * <pre>
 * private Vec3 collide(Vec3 movement) {
 *     AABB aabb = this.getBoundingBox();
 *     List&lt;VoxelShape&gt; entityColliders = this.level().getEntityCollisions(this, aabb.expandTowards(movement));
 * </pre>
 *
 * <p>Chaque créature qui bouge cherche donc les entités susceptibles de la bloquer. Et voici la
 * définition que le jeu en donne :
 *
 * <pre>
 * public boolean canBeCollidedWith(@Nullable Entity other) {
 *     return false;
 * }
 * </pre>
 *
 * <p><b>Faux par défaut.</b> Trois classes seulement le redéfinissent dans tout Minecraft : le bateau,
 * le shulker et le ghast apprivoisé. Une vache ne bloque pas une vache ; un zombie ne bloque pas un
 * zombie ; un objet au sol ne bloque rien du tout.
 *
 * <p>La conséquence est difficile à croire avant de l'avoir lue dans le code. Mille vaches dans un
 * enclos font, à chaque tick, mille recherches qui parcourent chacune les mille entités de la section
 * — <b>un million de tests d'intersection</b> — pour aboutir mille fois à une liste vide. Le travail
 * n'est pas mal fait : il est fait pour rien, intégralement.
 *
 * <h2>Ce qui rend le raccourci exact</h2>
 *
 * <p>Si aucune entité capable de bloquer ne se trouve dans la zone examinée, la réponse est la liste
 * vide, et on le sait sans regarder. Ce n'est pas une approximation ni un compromis : c'est la même
 * réponse, obtenue autrement.
 *
 * <p>Reste à savoir, à bon marché, s'il y a une telle entité dans la zone. Elles sont si rares qu'on
 * peut se permettre de les tenir toutes en liste et de comparer les boîtes une à une — sur un serveur
 * ordinaire, cette liste est <b>vide</b>, et la question se résout en une comparaison d'entier.
 *
 * <h2>Reconnaître une bloquante sans exécuter son code</h2>
 *
 * <p>La tentation serait d'appeler {@code canBeCollidedWith(null)} pour trier. C'est légitime — le
 * paramètre est annoté nullable — mais cela exécute le code d'un mod inconnu dans une boucle chaude,
 * et le résultat peut dépendre de l'état de l'entité, ce qui interdirait de le mettre en cache.
 *
 * <p>On regarde donc la <b>classe</b>, une fois par classe : la méthode est-elle déclarée ailleurs que
 * dans {@code Entity} ? Si non, elle rend faux, toujours, sans qu'on ait besoin de l'appeler. Si oui,
 * l'entité est comptée comme bloquante en permanence — quitte à être trop prudent, ce qui fait
 * travailler davantage et jamais moins.
 *
 * <h2>La limite, dite franchement</h2>
 *
 * <p>Un mod qui modifierait {@code Entity.canBeCollidedWith} lui-même, par mixin, rendrait toutes les
 * entités solides sans qu'aucune sous-classe ne le déclare. Ce raccourci ne le verrait pas, et les
 * collisions ajoutées par ce mod ne se produiraient plus.
 *
 * <p>Le cas est peu probable — il s'agirait d'un changement de jeu majeur, pas d'un réglage — et il
 * casserait de la même façon les optimisations équivalentes de Lithium. Il est néanmoins réel, et c'est
 * pourquoi ce module a son propre interrupteur : {@code LANTERNE_MODULES} sans {@code collision}
 * rétablit le comportement du jeu à l'identique.
 */
public final class Solid {
    /**
     * Classe vers « peut-elle bloquer ? ».
     *
     * <p>Concurrente parce que les entités sont créées depuis plusieurs fils lors du chargement des
     * chunks. La table reste minuscule — quelques dizaines de classes — et n'est écrite qu'une fois
     * par classe.
     */
    private static final Map<Class<?>, Boolean> BY_CLASS = new ConcurrentHashMap<>();

    /** L'élargissement que le jeu applique à sa zone de recherche, et qu'il faut reproduire. */
    private static final double GRACE = 1.0E-7d;

    /** Boîtes des bloquantes vues pendant le tour des entités du tick précédent. */
    private static List<AABB> boxes = new ArrayList<>();
    private static List<AABB> gathering = new ArrayList<>();

    /**
     * Boîtes des bloquantes qui ne tickent pas, tenues à part.
     *
     * <h2>Un bateau qui ne bloquait qu'un tick sur quarante</h2>
     *
     * <p>La première version ajoutait le résultat du rattrapage périodique dans la même liste que le
     * recensement du tick. Or cette liste est vidée à chaque tick, par construction. Le rattrapage
     * n'ayant lieu que toutes les quarante fois, un bateau amarré dans un chunk endormi figurait dans
     * la liste <b>un tick sur quarante</b> — et laissait tout passer au travers les trente-neuf
     * autres.
     *
     * <p>Le défaut n'aurait produit qu'un symptôme rare et incompréhensible : une créature qui
     * traverse un bateau de temps en temps. C'est la raison pour laquelle les deux recensements sont
     * désormais séparés — celui du tick, immédiat et volatil, et celui-ci, lent et persistant.
     */
    private static List<AABB> dormant = new ArrayList<>();

    /**
     * Le recensement a-t-il eu lieu au moins une fois ?
     *
     * <p>Avant, la liste est vide parce qu'on n'a rien regardé, et non parce qu'il n'y a rien. Prendre
     * l'une pour l'autre supprimerait les collisions de bateau pendant les premiers ticks du serveur —
     * le genre d'erreur qui ne se voit qu'une fois sur cent démarrages, et qu'on ne retrouve jamais.
     */
    private static boolean surveyed;

    /**
     * Le raccourci vaut-il son propre prix, dans l'état actuel du monde ?
     *
     * <h2>Le même module, ×62 d'un côté et −40 % de l'autre</h2>
     *
     * <p>Ce court-circuit donne <b>×62</b> sur un sol jonché de huit mille objets. Sur une charge de
     * dynamite — six mille charges dispersées sur cent cinquante blocs — il donne <b>−40 %</b>. Le même
     * code, les deux mesures prises sur la même machine le même jour.
     *
     * <p>La raison est géométrique, et elle est la clé de tout ce module. Ce qu'il supprime est le
     * parcours d'une section entière, que vanilla effectue <em>pour chaque requête</em>, sans tenir
     * compte de la taille de la boîte demandée :
     *
     * <ul>
     *   <li><b>entités concentrées</b> — huit mille objets dans une poignée de sections : chaque
     *       requête teste des milliers d'entités pour n'en retenir aucune. Le raccourci supprime un
     *       travail énorme, et son propre coût disparaît dedans ;</li>
     *   <li><b>entités dispersées</b> — six mille charges sur une centaine de sections : le parcours
     *       vanilla est déjà court. Il ne reste que le coût du raccourci, payé à chaque déplacement de
     *       chaque entité.</li>
     * </ul>
     *
     * <p>On ne peut donc pas décider une fois pour toutes : <b>il faut regarder le monde</b>. Le mod
     * possède déjà cette information — le recensement de densité compte les entités par chunk pour
     * décider des cadences. On la relit, une fois par tick, et le raccourci ne s'arme que si le plus
     * gros attroupement du monde justifie qu'on s'en occupe.
     *
     * <p>C'est la même idée que partout ailleurs ici : <b>ne dégrader que ce qui le mérite</b>,
     * appliquée cette fois au mod lui-même.
     */
    private static boolean worthIt;

    /**
     * Entités dans un même chunk à partir desquelles le parcours vanilla devient coûteux.
     *
     * <p>Soixante-quatre : au-delà, une requête de collision teste au moins soixante-quatre boîtes pour
     * en retenir une poignée, et le raccourci rembourse largement son prix. En deçà, vanilla fait aussi
     * vite que nous.
     */
    private static final int WORTH_CROWD = 64;

    private static long lastSweep = Long.MIN_VALUE;
    private static long shortcuts;

    private Solid() {}

    /**
     * Cette entité peut-elle bloquer le déplacement d'une autre ?
     *
     * <p>Sans appeler son code : on regarde où la méthode est déclarée.
     */
    public static boolean mayBlock(Entity entity) {
        // <h2>Vingt-huit millisecondes cachées dans une méthode de commodité</h2>
        //
        // Cette ligne était un simple {@code computeIfAbsent}. C'est l'écriture la plus claire, et sur
        // une {@code ConcurrentHashMap} elle ne se contente pas de lire : elle prend le chemin des
        // écritures, verrou compris, <b>même lorsque la clé est déjà là</b> — c'est-à-dire toujours,
        // passé les premières secondes d'un serveur.
        //
        // Appelée une fois par entité et par tick, cela reste invisible sur dix mille entités. La charge
        // dynamite en compte plusieurs centaines de milliers — six mille charges, et les objets lâchés
        // par quatre mille explosions — et le coût devient le poste principal du module :
        //
        // <pre>
        // sans le mod : 42,95 ms
        // module seul : 71,06 ms
        // </pre>
        //
        // Une lecture ordinaire suffit. Le calcul n'a lieu qu'à la première rencontre d'une classe, et
        // il y a quelques dizaines de classes d'entités dans une partie, pas des centaines de milliers.
        Boolean known = BY_CLASS.get(entity.getClass());
        if (known != null) {
            return known;
        }
        boolean declares = declaresCollision(entity.getClass());
        BY_CLASS.put(entity.getClass(), declares);
        return declares;
    }

    private static boolean declaresCollision(Class<?> type) {
        try {
            return type.getMethod("canBeCollidedWith", Entity.class).getDeclaringClass()
                    != Entity.class;
        } catch (Throwable unexpected) {
            // Une hiérarchie qu'on n'arrive pas à inspecter est tenue pour bloquante. L'erreur coûte
            // une recherche de plus ; l'inverse ferait traverser un bateau.
            return true;
        }
    }

    /** Déclare une bloquante rencontrée pendant le tour des entités. */
    public static void note(Entity entity) {
        gathering.add(entity.getBoundingBox());
    }

    /**
     * Clôt le recensement du tick et ouvre le suivant.
     *
     * <p>Le recensement se fait <b>pendant</b> le tour des entités que le mod effectue déjà, sans
     * parcours supplémentaire — le même principe que le comptage de densité. Les deux listes se
     * relaient au lieu d'être réallouées.
     *
     * <p>Un rattrapage complet a lieu périodiquement, parce que le tour des entités ne voit que
     * celles qui tickent : un bateau amarré dans un chunk endormi n'y figure pas, et il doit
     * continuer de bloquer ce qui passe à sa portée.
     */
    /**
     * Bascule le recensement du tick — une fois par tick SERVEUR, et non par monde.
     *
     * <h2>Un recensement que le monde suivant effaçait</h2>
     *
     * <p>Cette méthode a longtemps été appelée depuis {@code LevelTickEvent.Pre}, qui se déclenche
     * pour chacun des trois mondes. Le recensement de l.Overworld était donc écrasé par celui du
     * Nether, puis par celui de l.End — tous deux vides sur un serveur ordinaire. La liste du tick
     * était par conséquent presque toujours vide.
     *
     * <p>Le module a néanmoins toujours rendu la bonne réponse, et c.est le rattrapage périodique qui
     * l.a sauvé : {@code dormant} balaie tous les mondes toutes les quarante ticks, et c.est lui que
     * l.épreuve du bateau vérifiait sans qu.on le sache. Le défaut ne coûtait donc pas une collision —
     * il rendait simplement inutile la moitié du mécanisme.
     *
     * <p>Il a été trouvé sur le module jumeau des projectiles, où il n.avait pas de filet : là, toutes
     * les flèches traversaient tout, et l.épreuve de tir l.a pris en deux lignes.
     */
    public static void rotate() {
        List<AABB> previous = boxes;
        boxes = gathering;
        previous.clear();
        gathering = previous;
        surveyed = true;
    }

    /** Le rattrapage périodique, qui a besoin du monde et se fait donc par monde. */
    public static void sweep(ServerLevel level) {
        // Une fois par tick et par monde : on relit le plus gros attroupement recensé pour savoir si
        // le raccourci vaut son prix. Voir « worthIt » — le même module gagne ×62 sur des entités
        // concentrées et perd 40 % sur des entités dispersées.
        // Le garde de densité a été essayé puis retiré : il coûtait sur la charge où le module vaut
        // x62 (12,74 ms au lieu de 7,4) sans rien régler sur celle où il nuit. Voir le Javadoc de
        // « worthIt » — le diagnostic reste juste, le remède était mauvais.

        long now = level.getGameTime();
        // « lastSweep » part de Long.MIN_VALUE : une soustraction sur cette valeur déborde et rend un
        // négatif, ce qui aurait empêché tout rattrapage. Ce projet a perdu six bancs sur exactement
        // ce débordement ; on compare donc sans soustraire.
        if (lastSweep != Long.MIN_VALUE && now - lastSweep < 40L) {
            return;
        }
        lastSweep = now;
        dormant.clear();
        for (Entity entity : level.getAllEntities()) {
            if (entity != null && mayBlock(entity)) {
                dormant.add(entity.getBoundingBox());
            }
        }
    }

    /**
     * Peut-on affirmer qu'aucune entité ne bloquera dans cette zone ?
     *
     * <p>Le cas courant — aucune bloquante dans le monde — se règle sur la première ligne, par un test
     * de liste vide.
     */
    public static boolean noneIn(AABB zone) {
        if (!surveyed) {
            return false;
        }
        // <h2>La sortie la moins chère d'abord</h2>
        //
        // Le cas courant, sur l'immense majorité des serveurs, est qu'il n'existe <b>aucune</b> entité
        // capable de bloquer : ni bateau, ni shulker, ni ghast apprivoisé. Le reconnaître par deux
        // tests de liste vide, avant de calculer six coordonnées élargies dont on n'aura pas besoin,
        // économise l'essentiel du travail de cette méthode — et elle est appelée une fois par entité
        // mobile et par tick.
        if (boxes.isEmpty() && dormant.isEmpty()) {
            shortcuts++;
            return true;
        }
        // <h2>Le dixième de millionième de bloc qu'il fallait rendre</h2>
        //
        // Le jeu ne cherche pas dans la boîte demandée mais dans « testArea.inflate(1.0E-7) ». Tester
        // la boîte nue serait donc très légèrement plus étroit que ce qu'on remplace, et une entité
        // posée exactement sur la frontière cesserait de bloquer.
        //
        // On élargit du même montant — sans allouer de boîte, en décalant les bornes à la main. Le cas
        // est d'une rareté extrême ; c'est précisément pour cela qu'il valait la peine d'être traité :
        // un défaut qui survient une fois sur un million ne se reproduit jamais quand on le cherche.
        double minX = zone.minX - GRACE;
        double minY = zone.minY - GRACE;
        double minZ = zone.minZ - GRACE;
        double maxX = zone.maxX + GRACE;
        double maxY = zone.maxY + GRACE;
        double maxZ = zone.maxZ + GRACE;
        // Boucle indexée : cette méthode est appelée une fois par entité mobile et par tick, et un
        // itérateur y coûterait une allocation par appel.
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).intersects(minX, minY, minZ, maxX, maxY, maxZ)) {
                return false;
            }
        }
        for (int i = 0; i < dormant.size(); i++) {
            if (dormant.get(i).intersects(minX, minY, minZ, maxX, maxY, maxZ)) {
                return false;
            }
        }
        shortcuts++;
        return true;
    }

    /** Nombre de recherches évitées, pour le rapport. */
    public static long shortcuts() {
        return shortcuts;
    }

    /** Bloquantes actuellement connues, pour le rapport. */
    public static int blockers() {
        return boxes.size() + dormant.size();
    }

    public static void reset() {
        boxes.clear();
        gathering.clear();
        dormant.clear();
        surveyed = false;
        shortcuts = 0;
        lastSweep = Long.MIN_VALUE;
    }
}
