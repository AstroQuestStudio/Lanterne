package fr.clubcitrouille.lanterne.core;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

/**
 * Qui peut attendre, et qui ne le peut pas.
 *
 * <h2>Pourquoi aucun mixin</h2>
 *
 * <p>La tentation était d'injecter dans la boucle de tick du serveur. C'est inutile : NeoForge tire
 * déjà l'évènement qu'il faut, et il est annulable.
 *
 * <pre>
 * public void tickNonPassenger(Entity entity) {
 *     entity.setOldPosAndRot();
 *     entity.tickCount++;
 *     if (!EventHooks.fireEntityTickPre(entity).isCanceled()) {
 *         entity.tick();
 *         EventHooks.fireEntityTickPost(entity);
 *     }
 * </pre>
 *
 * <p>Deux conséquences, et la seconde vaut mieux que tout ce qu'un mixin aurait donné.
 *
 * <p><b>Un.</b> Aucun mixin sur le chemin le plus chaud du serveur, donc aucun risque de conflit
 * avec les vingt autres mods qui y injectent déjà. Le mod le plus rapide est celui qui laisse le
 * compilateur à la volée faire son travail ; empiler les interceptions sur une méthode appelée cent
 * mille fois par tick est précisément ce qui l'en empêche.
 *
 * <p><b>Deux.</b> {@code tickCount++} et {@code checkDespawn()} se font <em>avant</em> l'évènement,
 * donc hors de notre portée. Les compteurs continuent de tourner pour une entité qu'on met en
 * sommeil : elle vieillit, elle finit par disparaître, les fermes gardent leur débit. C'est
 * exactement le comportement voulu, et il est obtenu sans une ligne de code — il suffisait de
 * choisir le bon point d'accroche.
 *
 * <h2>Ce qui ne s'espace jamais</h2>
 *
 * <p>Une vache qui réfléchit trois fois par seconde au lieu de vingt reste une vache. Une flèche
 * qui vole trois fois par seconde n'est plus une flèche : elle traverse les murs, rate sa cible, ou
 * se fige en l'air. La règle qui sépare les deux ne tient pas à l'espèce mais à la nature du
 * comportement — <b>tout ce qui dépend d'une trajectoire précise ou d'un compte à rebours reste à
 * pleine vitesse</b>.
 *
 * <p>La liste est courte, et c'est ce qui la rend sûre. Ces entités sont rares, de courte vie, ou
 * les deux : les exclure ne coûte presque rien, alors que les dégrader se verrait immédiatement.
 */
public final class EntityThrottle {
    private EntityThrottle() {}

    /**
     * Faut-il laisser cette entité agir à ce tick ?
     *
     * <p>Appelé pour chaque entité, à chaque tick : tout ce qui est fait ici se paie cent mille fois
     * par seconde. D'où l'ordre des tests — le moins cher d'abord, et la lecture du niveau, qui est
     * une simple consultation de table, en dernier.
     *
     * @return vrai si l'entité doit s'exécuter normalement, faux si son tick peut être sauté
     */
    public static boolean shouldTick(Entity entity) {
        if (mustNeverSkip(entity)) {
            return true;
        }

        Lod level = Census.levelOf(entity);
        Census.count(level);

        // Éteint, on classe mais on ne dégrade pas. Le rapport reste donc lisible pendant la phase
        // témoin du banc, et les deux moitiés de la comparaison portent bien sur le même monde.
        if (!Settings.enabled()) {
            return true;
        }

        // Le décalage par identifiant étale le travail sur toute la période. Sans lui, toutes les
        // entités d'un même niveau s'exécuteraient dans le même tick : la charge ne baisserait pas,
        // elle se concentrerait, et les à-coups seraient pires qu'avant.
        return level.actsOn(entity.level().getGameTime(), entity.getId());
    }

    /**
     * Les entités dont le comportement ne survit pas à l'espacement.
     *
     * <p>Chaque ligne a sa raison, et aucune n'est de principe :
     *
     * <ul>
     *   <li><b>Le joueur</b> — la garantie qui rend tout le reste acceptable. Rien de ce qu'un
     *       joueur est ou fait n'est jamais dégradé.</li>
     *   <li><b>Ce qui porte un joueur</b> — un bateau ou un cheval qui tick une fois sur huit
     *       transforme un déplacement en téléportation, et c'est le joueur lui-même qu'on
     *       saccade.</li>
     *   <li><b>Les projectiles</b> — une flèche avance de plusieurs blocs par tick. En sauter un,
     *       c'est la faire traverser un mur, ou manquer ce qu'elle visait.</li>
     *   <li><b>La poudre amorcée</b> — son unique comportement est un compte à rebours de
     *       quatre-vingts ticks. L'espacer, c'est déplacer l'explosion.</li>
     *   <li><b>Les blocs qui tombent</b> — leur chute doit rester continue, sous peine de les voir
     *       se poser à travers le sol ou flotter.</li>
     * </ul>
     */
    private static boolean mustNeverSkip(Entity entity) {
        if (entity instanceof Player) {
            return true;
        }
        if (entity instanceof Projectile || entity instanceof PrimedTnt
                || entity instanceof FallingBlockEntity) {
            return true;
        }
        // Un véhicule ne se teste que s'il porte quelqu'un : l'appel parcourt la liste des passagers,
        // et la plupart des entités n'en ont aucun.
        return entity.isVehicle() && entity.hasPassenger(passenger -> passenger instanceof Player);
    }
}
