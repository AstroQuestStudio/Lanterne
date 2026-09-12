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

        long now = entity.level().getGameTime();

        // Deux raisons de ne pas simuler à fond : c'est loin, ou c'est noyé dans le nombre. La
        // seconde échappe entièrement à la première — cinquante vaches dans un enclos à vingt blocs
        // sont toutes « proches », et ce sont elles qui coûtent le plus cher.
        int period = Cadence.forEntity(entity, Census.distanceOf(entity), TickBudget.pressure());

        if (Settings.density()) {
            // La foule multiplie la cadence au lieu de la faire descendre d'un cran : avec une
            // échelle continue, doubler l'attente est la traduction exacte de « on en voit deux fois
            // moins ». Le plafond propre à l'espèce s'applique ensuite — un villageois serré dans
            // une ferme reste un villageois qui travaille.
            int crowd = Crowd.noteAndPenalty(entity, now);
            if (crowd > 0) {
                period = Math.min(period << crowd, Cadence.ceiling(entity));
            }
        }
        Census.count(period);

        // Éteint, on classe mais on ne dégrade pas. Le rapport reste donc lisible pendant la phase
        // témoin du banc, et les deux moitiés de la comparaison portent bien sur le même monde.
        if (!Settings.lod()) {
            return true;
        }

        return Cadence.actsOn(period, now, entity.getId());
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

        // <h2>Une chute ne se ralentit pas : elle s'effondre</h2>
        //
        // La gravité s'applique dans le tick, et elle s'<em>accumule</em> : chaque tour ajoute à la
        // vitesse, et la vitesse ajoute à la position. Sur N tours, la distance parcourue va donc
        // comme le carré de N.
        //
        // La conséquence est brutale et n'avait pas été vue : ralentir une créature d'un facteur
        // quatre ne divise pas sa chute par quatre, <b>mais par seize</b>. À la cadence la plus
        // lente, par plus de cinq mille. L'épreuve de conformité l'a chiffré — sept pour cent de la
        // chute normale à quatre-vingts blocs, là où l'on attendait vingt-cinq.
        //
        // Or toutes les fermes à monstres reposent sur une chute : on fait tomber d'assez haut pour
        // tuer, et l'on ramasse en bas. Une chute seize fois plus lente est une ferme seize fois
        // moins productive — le joueur ne le verrait pas venir, et l'attribuerait à autre chose.
        //
        // Ce qui tombe garde donc sa pleine cadence. Le coût est négligeable : une créature ne tombe
        // que quelques secondes dans sa vie, et l'immense majorité de celles qui peuplent un serveur
        // ont les pieds sur terre à tout instant.
        if (carriesPlayer(entity)) {
            return true;
        }
        // <h2>Une chute, et non un sautillement</h2>
        //
        // Ce qu'on protège n'est pas le fait de quitter le sol, c'est la <b>chute</b> : celle des
        // fermes, où la créature tombe d'assez haut pour mourir. Trois seuils ont été essayés.
        //
        // <p><b>« Pas au sol »</b> exemptait tout : dans un troupeau serré, les créatures se
        // poussent sans arrêt et décollent en permanence. Le gain s'est effondré de dix fois et
        // demie à moins de quatre.
        //
        // <p><b>« Vitesse descendante franche »</b> corrigeait cela, mais trop tard : il faut cinq
        // tours de chute libre pour atteindre le seuil, et à la cadence d'une créature lointaine,
        // cinq tours <em>siens</em> font quinze tours réels. La chute démarrait avec un retard qui
        // croissait avec la distance — exactement là où l'on voulait rester juste.
        //
        // <p>Le bon critère réunit les deux questions que le mod se pose déjà : <b>hors du sol, et
        // hors d'un amas</b>. Une vache bousculée par quarante voisines n'est pas en train de
        // tomber, elle vibre ; une créature isolée qui quitte le sol, elle, tombe — et on le sait
        // dès le premier tour, sans attendre que la vitesse le confirme.
        //
        // Le comptage de voisines est déjà fait pour la densité : ce critère ne coûte donc rien de
        // plus qu'une lecture de table.
        // Et elle doit descendre. La phase montante d'un saut quitte le sol sans rien devoir à la
        // gravité : l'exempter ne protège aucune ferme et paie plein tarif. Une vraie chute, elle,
        // a une vitesse négative dès le premier tour — le critère reste donc immédiat.
        return !entity.onGround()
                && entity.getDeltaMovement().y < 0d
                && Crowd.neighbours(entity) < 6;
    }

    /** Un véhicule qui porte un joueur : le saccader, c'est saccader le joueur lui-même. */
    private static boolean carriesPlayer(Entity entity) {
        // Un véhicule ne se teste que s'il porte quelqu'un : l'appel parcourt la liste des passagers,
        // et la plupart des entités n'en ont aucun.
        return entity.isVehicle() && entity.hasPassenger(passenger -> passenger instanceof Player);
    }
}
