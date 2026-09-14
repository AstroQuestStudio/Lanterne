package fr.clubcitrouille.lanterne.mixin;

import java.util.Set;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.GateBehavior;
import net.minecraft.world.entity.ai.behavior.ShufflingList;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import fr.clubcitrouille.lanterne.core.Mind;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.ShufflingListAccess;

/**
 * Les trois chemins d'un comportement composite, parcourus sans flux et sans itérateur.
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
 * <p>De la <b>tuyauterie de flux</b>. Pas du calcul de chemin, pas de la recherche de mémoire : la
 * machinerie que Java déploie pour parcourir une collection. Il a fallu élargir deux fois le motif du
 * profileur pour remonter jusqu'au demandeur, car la tuyauterie s'appelle elle-même en cascade et se
 * masque ainsi elle-même.
 *
 * <h2>Les trois sites, et lequel est le plus chaud</h2>
 *
 * <p>{@code GateBehavior} monte un pipeline sur chacun de ses trois chemins :
 *
 * <pre>
 * tryStart   → this.runningPolicy.apply(this.behaviors.stream(), …)
 * tickOrStop → this.behaviors.stream().filter(…).forEach(…)
 *              this.behaviors.stream().noneMatch(…)
 * doStop     → this.behaviors.stream().filter(…).forEach(…)
 * </pre>
 *
 * <p>Le plus chaud n'est pas celui qu'on croit. {@code tickOrStop} n'est appelé que sur les
 * comportements <em>déjà en cours</em> — deux pipelines, mais sur peu de candidats.
 * {@code tryStart}, lui, part de {@code Brain.startEachNonRunningBehavior}, que le profil crédite de
 * <b>28,9 %</b> de la pile : à chaque tick, chaque comportement composite <em>à l'arrêt</em> y est
 * proposé. Un villageois en porte une quinzaine.
 *
 * <p>Et la politique de démarrage cache un pipeline de plus, dans une énumération que le premier
 * examen ne montre pas :
 *
 * <pre>
 * RUN_ONE : behaviors.filter(STOPPED).filter(goal -&gt; goal.tryStart(…)).findFirst();
 * TRY_ALL : behaviors.filter(STOPPED).forEach(goal -&gt; goal.tryStart(…));
 * </pre>
 *
 * <h2>Ce que les boucles préservent exactement</h2>
 *
 * <ul>
 *   <li><b>La paresse des flux.</b> {@code filter(A).filter(B)} n'applique pas A à toute la liste
 *       avant de passer à B : il traite un élément de bout en bout, puis le suivant. La boucle fait
 *       le même entrelacement — c'est pour cela que {@code tryStart} est appelé dans la condition et
 *       non dans un second passage.</li>
 *   <li><b>Le court-circuit de {@code findFirst}.</b> RUN_ONE s'arrête au premier comportement qui
 *       accepte de démarrer. Le {@code break} n'a lieu qu'après un {@code tryStart} rendu vrai, et
 *       seulement sous cette politique-là.</li>
 *   <li><b>Le double parcours de {@code tickOrStop}.</b> Un comportement peut s'être arrêté pendant
 *       le premier ; la seconde question est justement là pour le voir. Les deux boucles restent
 *       deux boucles.</li>
 *   <li><b>L'ordre.</b> {@code orderPolicy.apply} peut battre la liste avant le parcours. Elle est
 *       appelée au même endroit, et l'indexation qui suit lit l'ordre qu'elle vient de poser.</li>
 * </ul>
 *
 * <h2>Le parcours lui-même, qui allouait encore</h2>
 *
 * <p>La première version de ce module remplaçait les flux par des boucles {@code for-each}. C'était
 * déjà bien moins cher — mais {@code ShufflingList.iterator()} passe par {@code Iterators.transform}
 * de Guava, qui alloue deux objets à chaque appel pour déballer les éléments de leur enveloppe
 * pondérée. Sur quinze comportements par villageois et six cents villageois, cela restait dix-huit
 * mille objets par tick, uniquement pour avoir le droit de regarder une liste.
 *
 * <p>{@link ShufflingListAccess} donne l'élément numéro <i>i</i> et n'alloue rien.
 *
 * <h2>Ce qui n'a pas été touché</h2>
 *
 * <p>{@code debugString} monte un pipeline lui aussi, avec un {@code Collectors.toSet()}. Il ne
 * s'exécute que lorsqu'un client de débogage est branché, et le réécrire n'aurait rien rapporté à
 * personne : on ne retire pas ce qui ne coûte rien.
 */
@Mixin(GateBehavior.class)
public abstract class GateBehaviorMixin<E extends LivingEntity> {
    @Shadow @Final private ShufflingList<BehaviorControl<? super E>> behaviors;

    @Shadow @Final private GateBehavior.OrderPolicy orderPolicy;

    @Shadow @Final private GateBehavior.RunningPolicy runningPolicy;

    @Shadow @Final private Set<MemoryModuleType<?>> exitErasedMemories;

    @Shadow private Behavior.Status status;

    /**
     * Le contrôle d'entrée du composite, qui n'est pas celui de {@code Behavior}.
     *
     * <p>{@code GateBehavior} implémente {@code BehaviorControl} directement, sans passer par
     * {@code Behavior} : sa méthode d'entrée est une méthode privée qui lui est propre, et le cache
     * de {@link BehaviorMemoryMixin} ne la couvre donc pas. On l'appelle ici telle quelle.
     */
    @Shadow private boolean hasRequiredMemories(E body) {
        throw new AssertionError();
    }

    @Shadow protected abstract void doStop(ServerLevel level, E body, long timestamp);

    /**
     * La liste des comportements, vue par indice.
     *
     * <p>Le double transtypage par {@code Object} est la forme habituelle du typage canard sous
     * mixin : à la compilation, {@code ShufflingList} n'implémente pas encore notre interface — c'est
     * {@link ShufflingListMixin} qui la lui ajoutera au chargement.
     */
    @Unique
    @SuppressWarnings("unchecked")
    private ShufflingListAccess<BehaviorControl<? super E>> lanterne$list() {
        return (ShufflingListAccess<BehaviorControl<? super E>>) (Object) this.behaviors;
    }

    @WrapMethod(method = "tryStart")
    private boolean lanterne$startWithoutStreams(ServerLevel level, E body, long timestamp,
            Operation<Boolean> original) {
        if (!Settings.mind()) {
            return original.call(level, body, timestamp);
        }
        if (!this.hasRequiredMemories(body)) {
            return false;
        }

        Mind.noteGate();
        this.status = Behavior.Status.RUNNING;
        this.orderPolicy.apply(this.behaviors);

        // Lue une fois, après le brassage éventuel : c'est l'ordre que la politique vient de poser.
        ShufflingListAccess<BehaviorControl<? super E>> list = this.lanterne$list();
        boolean stopAtFirst = this.runningPolicy == GateBehavior.RunningPolicy.RUN_ONE;
        int count = list.lanterne$size();
        for (int i = 0; i < count; i++) {
            BehaviorControl<? super E> behavior = list.lanterne$at(i);
            // L'ordre d'évaluation compte : le démarrage est tenté avant que la politique ne décide
            // s'il faut s'arrêter là. TRY_ALL les tente donc tous, comme son forEach d'origine.
            if (behavior.getStatus() == Behavior.Status.STOPPED
                    && behavior.tryStart(level, body, timestamp)
                    && stopAtFirst) {
                break;
            }
        }
        return true;
    }

    @WrapMethod(method = "tickOrStop")
    private void lanterne$tickWithoutStreams(ServerLevel level, E body, long timestamp,
            Operation<Void> original) {
        if (!Settings.mind()) {
            original.call(level, body, timestamp);
            return;
        }

        Mind.noteGate();
        ShufflingListAccess<BehaviorControl<? super E>> list = this.lanterne$list();
        int count = list.lanterne$size();
        for (int i = 0; i < count; i++) {
            BehaviorControl<? super E> behavior = list.lanterne$at(i);
            if (behavior.getStatus() == Behavior.Status.RUNNING) {
                behavior.tickOrStop(level, body, timestamp);
            }
        }

        // Second parcours, comme vanilla : un comportement peut s'être arrêté pendant le premier, et
        // c'est justement ce que la seconde question cherche à savoir.
        boolean anyRunning = false;
        for (int i = 0; i < count; i++) {
            if (list.lanterne$at(i).getStatus() == Behavior.Status.RUNNING) {
                anyRunning = true;
                break;
            }
        }
        if (!anyRunning) {
            this.doStop(level, body, timestamp);
        }
    }

    @WrapMethod(method = "doStop")
    private void lanterne$stopWithoutStreams(ServerLevel level, E body, long timestamp,
            Operation<Void> original) {
        if (!Settings.mind()) {
            original.call(level, body, timestamp);
            return;
        }

        this.status = Behavior.Status.STOPPED;
        ShufflingListAccess<BehaviorControl<? super E>> list = this.lanterne$list();
        int count = list.lanterne$size();
        for (int i = 0; i < count; i++) {
            BehaviorControl<? super E> behavior = list.lanterne$at(i);
            if (behavior.getStatus() == Behavior.Status.RUNNING) {
                behavior.doStop(level, body, timestamp);
            }
        }

        // Ce jeu de mémoires est vide dans l'immense majorité des comportements composites ; le
        // demander à un ensemble vide allouerait quand même son itérateur.
        if (!this.exitErasedMemories.isEmpty()) {
            Brain<?> brain = body.getBrain();
            for (MemoryModuleType<?> memory : this.exitErasedMemories) {
                brain.eraseMemory(memory);
            }
        }
    }
}
