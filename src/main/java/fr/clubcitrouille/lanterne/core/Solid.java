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

    private static long lastSweep = Long.MIN_VALUE;
    private static long shortcuts;

    private Solid() {}

    /**
     * Cette entité peut-elle bloquer le déplacement d'une autre ?
     *
     * <p>Sans appeler son code : on regarde où la méthode est déclarée.
     */
    public static boolean mayBlock(Entity entity) {
        return BY_CLASS.computeIfAbsent(entity.getClass(), Solid::declaresCollision);
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
    public static void rotate(ServerLevel level) {
        List<AABB> previous = boxes;
        boxes = gathering;
        previous.clear();
        gathering = previous;
        surveyed = true;

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
