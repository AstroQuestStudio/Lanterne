package fr.clubcitrouille.lanterne.core;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Ce qu'un projectile peut réellement toucher — c'est-à-dire presque rien.
 *
 * <h2>Cinquante-neuf pour cent du serveur, pour ne trouver personne</h2>
 *
 * <p>Une charge de six mille flèches en vol, profilée sans le mod, donne ceci :
 *
 * <pre>
 * 59,2 %  EntitySection.getEntities
 *  9,6 %  ThreadLocal$ThreadLocalMap.getEntryAfterMiss
 *  1,2 %  AbstractArrow.tick
 * </pre>
 *
 * <p>Près de soixante pour cent du serveur passe à chercher des entités. Pas à simuler les flèches —
 * à les <b>chercher</b>. Le chemin est celui-ci, dans {@code ProjectileUtil} :
 *
 * <pre>
 * for (Entity entity : level.getEntities(source, targetSearchArea, matching)) {
 *     AABB bb = entity.getBoundingBox().inflate(entityMargin);
 *     ...
 * }
 * </pre>
 *
 * <p>À chaque tick, chaque projectile demande la liste des entités de sa boîte de déplacement. Et
 * {@code EntitySection.getEntities} parcourt la <b>section entière</b>, non la boîte. Six mille
 * flèches concentrées autour d'un point font donc, à chaque tick, six mille parcours de plusieurs
 * centaines d'entités chacun. Le coût croît avec le <b>carré</b> du nombre de projectiles.
 *
 * <h2>Une flèche ne peut pas toucher une flèche</h2>
 *
 * <p>Le prédicat que ces parcours évaluent est {@code Projectile.canHitEntity}, qui commence par :
 *
 * <pre>
 * public boolean canBeHitByProjectile() {
 *     return this.isAlive() &amp;&amp; this.isPickable();
 * }
 * </pre>
 *
 * <p>Et {@code isPickable()} rend <b>faux par défaut</b>. Une flèche n'est pas sélectionnable, un
 * objet au sol non plus, ni une boule de feu, ni une TNT amorcée, ni une orbe d'expérience. Seuls les
 * êtres vivants et une poignée d'entités spéciales le sont.
 *
 * <p>C'est très exactement la découverte qui fonde {@link Solid}, atteinte par un autre chemin : un
 * prédicat presque toujours faux, évalué après un parcours coûteux. Là-bas c'était
 * {@code canBeCollidedWith} ; ici c'est {@code isPickable}.
 *
 * <h2>Ce qui rend le raccourci exact</h2>
 *
 * <p>Si aucune entité sélectionnable ne se trouve dans la zone examinée, aucune ne peut satisfaire le
 * prédicat, et la réponse est <b>null</b> — la même que celle du parcours complet, obtenue sans le
 * faire.
 *
 * <p>Le recensement se fait pendant le tour des entités que le mod effectue déjà, comme celui des
 * bloquantes, et sans parcours supplémentaire. La liste est celle des créatures et des joueurs :
 * courte sur un serveur ordinaire, et vide dans un ciel qui ne contient que des projectiles.
 *
 * <h2>La limite, dite franchement</h2>
 *
 * <p>Un mod qui rendrait ses projectiles capables de s'entre-toucher — en redéfinissant
 * {@code isPickable} sur une flèche, ou {@code canHitEntity} pour ignorer le test — verrait ses
 * collisions disparaître. Le cas est peu probable et il est décelable : {@code LANTERNE_MODULES} sans
 * {@code projectile} rétablit le comportement du jeu à l'identique.
 */
public final class Quarry {
    /** L'élargissement que le jeu applique à sa zone de recherche, et qu'il faut reproduire. */
    private static final double GRACE = 1.0E-7d;

    /** Boîtes des entités sélectionnables vues pendant le tour du tick précédent. */
    private static List<AABB> boxes = new ArrayList<>();
    private static List<AABB> gathering = new ArrayList<>();

    /**
     * Le recensement a-t-il eu lieu au moins une fois ?
     *
     * <p>Avant, la liste est vide parce qu'on n'a rien regardé, et non parce qu'il n'y a rien. Prendre
     * l'une pour l'autre rendrait les flèches inoffensives pendant les premiers ticks du serveur.
     */
    private static boolean surveyed;

    /** Recherches de cible évitées, et entités sélectionnables recensées. */
    private static long shortcuts;
    private static int targets;

    private Quarry() {}

    /**
     * Cette entité peut-elle être touchée par un projectile ?
     *
     * <p>On interroge l'état et non la classe, contrairement à {@link Solid}. La raison est dans la
     * définition même : {@code isPickable()} d'un être vivant dépend de son état — une créature morte
     * ne se touche plus. Un cache par classe rendrait donc une réponse périmée, et le coût évité est
     * dérisoire puisque la méthode ne fait qu'une lecture de champ pour l'immense majorité des
     * entités.
     */
    public static boolean mayBeHit(Entity entity) {
        return entity.isPickable() && entity.isAlive();
    }

    /** Appels de recensement depuis le démarrage — la preuve que le mixin s.exécute. */
    private static long noted;

    /** Déclare une entité sélectionnable rencontrée pendant le tour des créatures. */
    public static void note(Entity entity) {
        gathering.add(entity.getBoundingBox());
        noted++;
    }

    /** Clôt le recensement du tick et ouvre le suivant. */
    public static void rotate() {
        List<AABB> previous = boxes;
        boxes = gathering;
        previous.clear();
        gathering = previous;
        targets = boxes.size();
        surveyed = true;
    }

    /**
     * Aucune cible possible dans cette zone ?
     *
     * <p>Les bornes reproduisent l'élargissement que le jeu applique à sa propre recherche. Un
     * raccourci qui examinerait un volume plus petit que celui qu'il remplace laisserait passer une
     * flèche au travers d'un joueur, une fois sur mille — le genre de défaut qu'on ne reproduit
     * jamais et qu'on ne retrouve pas.
     */
    public static boolean noTargetIn(AABB zone) {
        if (!surveyed) {
            return false;
        }
        if (boxes.isEmpty()) {
            shortcuts++;
            return true;
        }
        double minX = zone.minX - GRACE;
        double minY = zone.minY - GRACE;
        double minZ = zone.minZ - GRACE;
        double maxX = zone.maxX + GRACE;
        double maxY = zone.maxY + GRACE;
        double maxZ = zone.maxZ + GRACE;
        for (int i = 0; i < boxes.size(); i++) {
            AABB target = boxes.get(i);
            if (target.maxX > minX && target.minX < maxX
                    && target.maxY > minY && target.minY < maxY
                    && target.maxZ > minZ && target.minZ < maxZ) {
                return false;
            }
        }
        shortcuts++;
        return true;
    }

    public static long shortcuts() {
        return shortcuts;
    }

    public static long noted() {
        return noted;
    }

    public static int targets() {
        return targets;
    }

    public static void reset() {
        boxes.clear();
        gathering.clear();
        surveyed = false;
        shortcuts = 0L;
        targets = 0;
        noted = 0L;
    }
}
