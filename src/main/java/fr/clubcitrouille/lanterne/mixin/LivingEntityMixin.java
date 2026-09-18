package fr.clubcitrouille.lanterne.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import fr.clubcitrouille.lanterne.core.Crowd;
import fr.clubcitrouille.lanterne.core.Jam;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le coût quadratique des entités entassées.
 *
 * <h2>Ce que fait le jeu</h2>
 *
 * <pre>
 * protected void pushEntities() {
 *     List&lt;Entity&gt; pushableEntities = this.level().getPushableEntities(this, this.getBoundingBox());
 *     ...
 *     for (Entity other : pushableEntities) {
 *         this.doPush(other);
 *     }
 * }
 * </pre>
 *
 * <p>Chaque créature cherche toutes ses voisines poussables, puis les pousse une à une. Avec
 * cinquante vaches dans un même chunk, cela fait cinquante recherches spatiales et deux mille cinq
 * cents poussées <b>par tick</b>. Le coût croît avec le <em>carré</em> du nombre d'entités : c'est
 * la raison pour laquelle une ferme qui fonctionne bien à trois cents mobs s'effondre à six cents.
 *
 * <p>Le profileur le confirme sur le banc : {@code EntitySection.getEntities} pèse sept virgule huit
 * pour cent du temps, et c'est cette recherche-là.
 *
 * <h2>Le plafond, et pourquoi il est acceptable</h2>
 *
 * <p>Paper plafonne ce nombre depuis des années — {@code max-entity-collisions}, valeur par défaut
 * huit — sur des dizaines de milliers de serveurs. Ce n'est donc pas une idée neuve mais une
 * pratique établie, et la raison en est simple : <b>au-delà de quelques voisines, une poussée de
 * plus ne change rien à la position finale</b>. Les forces s'annulent, la créature est coincée de
 * toute façon.
 *
 * <h2>Où ce mod diffère</h2>
 *
 * <p>Paper applique son plafond en permanence, à toutes les entités. Ici il ne s'applique
 * <b>qu'au-delà du seuil de foule</b> : en deçà, la liste est rendue intacte et le comportement est
 * celui du jeu, au geste près.
 *
 * <p>Deux ou trois cochons qui se bousculent dans un enclos se poussent donc exactement comme
 * d'habitude. Seul le tas de cinquante voit sa liste tronquée — et dans un tas de cinquante,
 * personne ne peut dire quelle vache a poussé quelle autre.
 *
 * <h2>Ce qui n'est pas touché</h2>
 *
 * <p>Le plafond retenu reste au-dessus de la règle d'entassement ({@code maxEntityCramming}, vingt-
 * quatre par défaut). Le comptage qui inflige des dégâts d'écrasement lit la même liste : la
 * tronquer trop court supprimerait silencieusement une mécanique du jeu, et les fermes qui
 * <em>reposent</em> sur ces dégâts cesseraient de produire.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    /**
     * Tick auquel la décision a été prise, pour ne la prendre qu'une fois.
     *
     * <p>Initialisé à moins un et non à {@code Long.MIN_VALUE} : ce projet a déjà perdu six bancs sur
     * un débordement d'entier né de cette constante. Le temps de jeu part de zéro et ne décroît
     * jamais, donc moins un ne peut coïncider avec aucun tick réel.
     */
    @org.spongepowered.asm.mixin.Unique
    private long lanterne$decidedAt = -1L;

    @org.spongepowered.asm.mixin.Unique
    private boolean lanterne$decision;

    /**
     * Court-circuite entièrement la bousculade d'un amas coincé.
     *
     * <p>Ce n'est pas un plafond mais une suppression : ni recherche de voisines, ni poussées. On ne
     * réduit pas le nombre d'opérations d'un calcul quadratique, <b>on l'annule</b> — et sans rien
     * changer au résultat, puisque ce résultat était l'immobilité.
     */
    /**
     * Retire l'intelligence et le déplacement, et laisse tourner tout le reste.
     *
     * <h2>La découverte qui a changé le cœur de ce mod</h2>
     *
     * <p>Jusqu'ici, ralentir une créature signifiait <b>annuler son tick entier</b>. C'était simple,
     * efficace, et cela détruisait discrètement le rendement des fermes — ce que ce mod jure de ne
     * jamais faire.
     *
     * <p>La raison est dans la chaîne d'héritage. {@code Chicken.aiStep} appelle
     * {@code super.aiStep()}, qui remonte jusqu'à {@code LivingEntity.aiStep}, puis <b>redescend</b> :
     * chaque sous-classe fait son travail après l'appel au parent. Et ce travail, ce sont les
     * compteurs qui produisent :
     *
     * <pre>
     * AgeableMob : if (this.canAgeUp()) this.setAge(++age);      // un veau devient vache
     * Animal     : if (this.inLove > 0) this.inLove--;           // le délai de reproduction
     * Chicken    : if (--this.eggTime <= 0) ... pond un œuf      // un œuf toutes les 5 à 10 minutes
     * </pre>
     *
     * <p>Annuler le tick, c'est arrêter ces trois horloges. Une ferme à œufs ralentie d'un facteur
     * huit produit huit fois moins, et rien ne le signale.
     *
     * <h2>Le point d'appui : un interrupteur que le jeu possède déjà</h2>
     *
     * <p>{@code LivingEntity.aiStep} garde <em>les deux</em> postes coûteux derrière le même test :
     *
     * <pre>
     * } else if (this.isEffectiveAi() && !this.level().isClientSide()) {
     *     this.serverAiStep();          // perception, objectifs, navigation, contrôles
     * ...
     * } else if (this.canSimulateMovement() && this.isEffectiveAi()) {
     *     this.travel(input);           // gravité, déplacement, résolution de collisions
     * </pre>
     *
     * <p>Il suffit donc de rendre {@code false} à cette question pour retirer l'intelligence et le
     * déplacement d'un seul geste — <b>sans toucher à une seule autre ligne</b> de la méthode.
     *
     * <p>Et c'est le point qui rend ce choix défendable : cet état existe dans le jeu. C'est celui
     * d'une créature marquée {@code NoAI}, que Minecraft gère depuis toujours et que les
     * constructeurs de fermes utilisent tous les jours. On ne fabrique pas un état inédit dont
     * personne ne sait ce qu'il vaut : on emprunte un chemin que le jeu connaît.
     *
     * <h2>Ce qui continue de tourner</h2>
     *
     * <p>Tout le reste : le vieillissement, la reproduction, la ponte, le ramassage d'objets, le gel,
     * le feu, les portails, la noyade, la disparition, et — c'est ce qui compte le plus — <b>les
     * compteurs des créatures ajoutées par les mods</b>, qu'on n'a pas à connaître pour les préserver.
     *
     * <p>La version précédente aurait exigé une liste de tous les compteurs de tous les mods, et cette
     * liste aurait été fausse le jour de sa publication.
     */
    @org.spongepowered.asm.mixin.injection.Redirect(
            method = "aiStep",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/entity/LivingEntity;isEffectiveAi()Z"))
    private boolean lanterne$idleWhenThrottled(LivingEntity self) {
        if (!self.isEffectiveAi()) {
            return false; // déjà sans intelligence : rien à décider, et rien à compter
        }
        // Hors du mode strict, la décision a déjà été prise et appliquée en tête du tick : la reprendre
        // ici compterait chaque créature deux fois dans le recensement de densité, et lui retirerait
        // son intelligence un tick sur deux en plus de ce qui était prévu.
        if (!fr.clubcitrouille.lanterne.core.Settings.strictYield()) {
            return true;
        }
        // « isEffectiveAi » est interrogé DEUX fois dans aiStep — une fois pour l'intelligence, une
        // fois pour le déplacement. Décider deux fois comptabiliserait chaque créature deux fois dans
        // le recensement de densité et dans le rapport : le premier gonflerait la pénalité de foule,
        // le second annoncerait deux fois plus d'entités qu'il n'y en a. On décide une fois par tick,
        // et l'on répond la même chose aux deux questions — ce qui est aussi la seule façon de ne pas
        // laisser une créature réfléchir sans pouvoir bouger.
        long now = self.level().getGameTime();
        if (lanterne$decidedAt != now) {
            lanterne$decidedAt = now;
            lanterne$decision = fr.clubcitrouille.lanterne.core.EntityThrottle.shouldTick(self);
        }
        return lanterne$decision;
    }

    /**
     * {@code pushEntities} n'est PAS gardée par {@code isClientSide()} dans {@code aiStep} : le
     * bytecode de Minecraft l'appelle sans condition, juste après {@code checkAutoSpinAttack}. En
     * solo, où client et serveur intégré tournent dans la même JVM sur deux fils distincts, cette
     * injection s'exécute donc deux fois par tick pour une même créature — une fois pour l'entité
     * cliente (fil de rendu), une fois pour l'entité serveur (fil du serveur intégré), les deux
     * partageant le même {@code getId()} puisque l'identifiant est répliqué du serveur vers le
     * client. {@link Jam#jammed} indexe ses tables par cet identifiant, sans synchronisation : deux
     * fils qui y écrivent en même temps corrompent le tableau interne des tables fastutil et font
     * planter la partie ({@code ArrayIndexOutOfBoundsException}).
     *
     * <p>Le calcul que {@code Jam} économise n'a de sens que côté serveur : c'est lui, et lui seul,
     * qui paie le coût quadratique de {@code getPushableEntities}. Le client ne fait que rejouer la
     * même bousculade sur une position qu'une prochaine synchronisation réseau va de toute façon
     * écraser ; la court-circuiter là-bas n'économise rien qui compte, et l'évaluer y était une
     * erreur d'injection, pas un besoin réel. On restreint donc l'appel au côté serveur — ce qui
     * supprime la course à la racine, sans verrou : les tables de {@code Jam} ne sont plus jamais
     * touchées que par le fil du serveur.
     */
    @Inject(method = "pushEntities", at = @At("HEAD"), cancellable = true)
    private void lanterne$skipJammed(CallbackInfo callback) {
        if (!Settings.jam()) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) {
            return; // voir le commentaire ci-dessus : Jam est une optimisation serveur uniquement
        }
        if (Jam.jammed(self, Crowd.neighbours(self), self.level().getGameTime())) {
            callback.cancel();
        }
    }

    @Redirect(
            method = "pushEntities",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/level/Level;getPushableEntities"
                            + "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)"
                            + "Ljava/util/List;"))
    private List<Entity> lanterne$capPushables(Level level, Entity pusher, AABB box) {
        // La troncature a quitté cette méthode pour la recherche elle-même. Elle ne coupait que la
        // seconde moitié de la dépense : le parcours de la section allait au bout de ses mille
        // entités quand même, et pesait vingt-quatre pour cent du travail du serveur. Voir Shove.
        return fr.clubcitrouille.lanterne.core.Shove.pushables(level, pusher, box);
    }
}
