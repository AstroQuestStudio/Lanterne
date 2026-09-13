package fr.clubcitrouille.lanterne.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GateBehavior;

import fr.clubcitrouille.lanterne.core.Mind;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Deux pipelines de streams par comportement composite et par tick, remplacés par deux boucles.
 *
 * <h2>L'intelligence la plus chère du jeu, et où elle dépense</h2>
 *
 * <p>Un villageois porte un {@code Brain} : des dizaines de comportements, des souvenirs, des
 * capteurs. C'est la créature la plus coûteuse de vanilla, et six cents d'entre eux font passer un
 * serveur de vingt à cinquante-six millisecondes par tick.
 *
 * <p>Le profil, mod éteint, désigne un poste que rien dans l'IA elle-même n'explique :
 *
 * <pre>
 *  9,6 %  MatchOps$MatchOp.evaluateSequential
 *  7,8 %  ReferencePipeline$3$1.accept
 *  4,6 %  LinkedHashMap.keySet
 * </pre>
 *
 * <p>De la <b>tuyauterie de streams</b>. Pas du calcul de chemin, pas de la recherche de mémoire : la
 * machinerie que Java déploie pour parcourir une collection.
 *
 * <p>Il a fallu élargir deux fois le motif du profileur pour remonter jusqu'au coupable, car la
 * tuyauterie s'appelle elle-même en cascade et masquait son propre demandeur. Le voici :
 *
 * <pre>
 * public final void tickOrStop(ServerLevel level, E body, long timestamp) {
 *     this.behaviors.stream().filter(g -&gt; g.getStatus() == RUNNING).forEach(g -&gt; g.tickOrStop(…));
 *     if (this.behaviors.stream().noneMatch(g -&gt; g.getStatus() == RUNNING)) {
 *         this.doStop(level, body, timestamp);
 *     }
 * }
 * </pre>
 *
 * <p><b>Deux pipelines complets</b> — chacun avec son spliterator, ses lambdas et ses objets
 * intermédiaires — pour parcourir une liste qui compte, le plus souvent, deux ou trois éléments. Et
 * cela une fois par comportement composite, par villageois, par tick.
 *
 * <h2>Ce que les boucles changent, et ce qu'elles ne changent pas</h2>
 *
 * <p>Rien, du point de vue du jeu. Le premier stream filtre puis applique ; la première boucle teste
 * puis applique, dans le même ordre. Le second cherche s'il reste un comportement actif ; la seconde
 * boucle s'arrête au premier trouvé — ce qui est même un peu mieux, {@code noneMatch} ayant déjà ce
 * court-circuit.
 *
 * <p>L'ordre d'itération est celui de la même liste, inchangé. Le seul écart possible serait une
 * modification de la liste pendant le parcours — mais le stream d'origine la parcourt aussi, et
 * échouerait de la même façon.
 */
@Mixin(GateBehavior.class)
public abstract class GateBehaviorMixin<E extends LivingEntity> {
    @Shadow @Final private ShufflingList<BehaviorControl<? super E>> behaviors;

    @Shadow protected abstract void doStop(ServerLevel level, E body, long timestamp);

    @WrapMethod(method = "tickOrStop")
    private void lanterne$tickWithoutStreams(ServerLevel level, E body, long timestamp,
            Operation<Void> original) {
        if (!Settings.mind()) {
            original.call(level, body, timestamp);
            return;
        }

        Mind.noteGate();
        boolean anyRunning = false;
        for (BehaviorControl<? super E> behavior : this.behaviors) {
            if (behavior.getStatus() == Behavior.Status.RUNNING) {
                behavior.tickOrStop(level, body, timestamp);
            }
        }
        // Second parcours, comme vanilla : un comportement peut s'être arrêté pendant le premier, et
        // c'est justement ce que la seconde question cherche à savoir.
        for (BehaviorControl<? super E> behavior : this.behaviors) {
            if (behavior.getStatus() == Behavior.Status.RUNNING) {
                anyRunning = true;
                break;
            }
        }
        if (!anyRunning) {
            this.doStop(level, body, timestamp);
        }
    }
}
