package fr.clubcitrouille.lanterne.mixin;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemorySlot;
import net.minecraft.world.entity.schedule.Activity;

import fr.clubcitrouille.lanterne.core.MaskedList;
import fr.clubcitrouille.lanterne.core.MemoryPulseAccess;
import fr.clubcitrouille.lanterne.core.Mind;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le cerveau, qui cesse de redécouvrir à chaque tick ce qu'il savait déjà.
 *
 * <h2>Ce que le profileur a fini par montrer</h2>
 *
 * <p>Sur six cents villageois, {@code Brain.startEachNonRunningBehavior} porte <b>38,7 %</b> de la
 * pile — plus que le calcul de chemin, plus que la lecture de blocs. Trois tentatives successives
 * ont cherché le temps ailleurs : dans les tables du cerveau (×1,01), dans les capteurs (×0,98),
 * dans les flux des comportements composites (×1,03). Aucune ne l'a trouvé, parce qu'aucune ne
 * regardait la bonne chose.
 *
 * <p>Le voici. {@code Brain.tick} fait quatre choses, et deux d'entre elles reconstruisent à chaque
 * fois une réponse qui n'a pas changé :
 *
 * <pre>
 * startEachNonRunningBehavior → parcourt les TROIS niveaux de tables imbriquées,
 *                               teste l'activité, teste le statut
 * tickEachRunningBehavior     → appelle getRunningBehaviors(), qui ALLOUE une liste
 *                               et reparcourt les mêmes trois niveaux
 * </pre>
 *
 * <p>{@code getRunningBehaviors} est pourtant marquée {@code @Deprecated @VisibleForDebug} dans le
 * code de Mojang. Elle est appelée à chaque tick, par chaque créature à cerveau, en pleine
 * production — et elle visite même les comportements des activités <em>inactives</em>, pour les
 * écarter.
 *
 * <p>C'est la deuxième règle d'instrument sous son visage le plus utile : le sommet de pile ne
 * désignait pas un coupable mais ses complices — {@code HashMap$Values.forEach},
 * {@code LinkedHashMap.keySet}, {@code Collections$UnmodifiableCollection$1.hasNext}. La machinerie
 * de parcours, jamais le parcouru.
 *
 * <h2>Le principe : un coût par changement, au lieu d'un coût par tick</h2>
 *
 * <p>Deux collections tenues à jour remplacent les deux parcours :
 *
 * <ul>
 *   <li><b>Les candidats</b> — la liste plate des comportements dont l'activité est active. Elle ne
 *       change que lorsque la créature change d'activité, c'est-à-dire quelques fois par journée de
 *       jeu.</li>
 *   <li><b>Les actifs</b> — une {@link MaskedList}, où démarrer un comportement allume un bit et
 *       l'arrêter l'éteint. Répondre « qui tourne ? » ne coûte plus qu'un saut de bit en bit.</li>
 * </ul>
 *
 * <h2>Pourquoi la liste des actifs ne peut pas se désynchroniser</h2>
 *
 * <p>C'est la question qui décide de tout : un comportement qui passerait à RUNNING sans qu'on
 * l'allume ne serait jamais tické, et la créature se figerait — un défaut silencieux, visible
 * seulement en jouant, et tard.
 *
 * <p>Or {@code Behavior.tryStart} et {@code GateBehavior.tryStart} sont les <b>deux seuls</b>
 * endroits du jeu qui écrivent {@code status = RUNNING}, et une recherche sur l'ensemble des sources
 * de 26.1 ne trouve qu'<b>un seul</b> appelant de {@code tryStart} hors des comportements composites
 * eux-mêmes : la ligne 422 de cette classe. Il n'y a donc qu'une porte d'entrée, et elle est
 * couverte ici. Les comportements imbriqués dans un composite, eux, ne figurent pas dans les tables
 * du cerveau — {@link MaskedList#setVisible} les ignore en silence.
 *
 * <p>Pour l'arrêt, trois portes : le tick d'un comportement qui se termine, {@code stopAll}, et le
 * changement d'activité. Les trois sont ici.
 *
 * <h2>Le piège du mod qu'on éteint</h2>
 *
 * <p>Cette liste n'est juste que tant que <b>nous</b> tenons les comptes. Si Lanterne s'éteint,
 * vanilla reprend la main et fait changer les statuts sans rien nous dire ; rallumer le mod ferait
 * alors travailler une liste périmée, et figerait exactement les créatures qu'on voulait accélérer.
 *
 * <p>{@code /lanterne off} puis {@code /lanterne on} suffit à le produire, et le banc entrelacé le
 * produit vingt fois par mesure. D'où le numéro de génération de {@link Settings#epoch()} : dès
 * qu'il change, les deux collections sont jetées et refaites.
 *
 * <h2>La seule différence de comportement, et pourquoi elle va dans le bon sens</h2>
 *
 * <p>Vanilla photographie la liste des actifs, <em>puis</em> la parcourt. Si un comportement en
 * arrête un autre pendant ce parcours, l'arrêté est tické quand même — et comme
 * {@code Behavior.tickOrStop} ne vérifie pas son propre statut, il appelle {@code doStop} une
 * seconde fois, donc {@code stop()} deux fois. Nous le sautons.
 *
 * <p>Ce cas suppose un {@code stopAll} déclenché au milieu du tick du cerveau ; il est rare. Mais on
 * ne prétendra pas ici à l'équivalence stricte : la différence existe, et elle évite un double
 * arrêt.
 *
 * <h2>La liste rendue est vivante, et c'est vérifié</h2>
 *
 * <p>{@code getRunningBehaviors()} rendait une liste fraîche à chaque appel ; elle rend désormais la
 * {@link MaskedList} elle-même, qui continue de changer sous les pieds de qui la garde. Un appelant
 * qui la conserverait verrait son contenu bouger.
 *
 * <p>Une recherche sur l'ensemble des sources de 26.1 n'en trouve qu'un seul hors de cette classe :
 * {@code DebugBrainDump} ligne 120, qui fait {@code .stream().map(...).toList()} sur-le-champ et ne
 * garde rien. Le cas dangereux n'existe donc pas dans le jeu — et le {@code spliterator} écrit à la
 * main dans {@link MaskedList} est précisément ce qui empêche ce {@code .stream()} de devenir
 * quadratique.
 *
 * <p>Dispositif porté depuis Lithium (LGPL-3.0), que la licence GPL-3.0 de ce mod absorbe.
 */
@Mixin(Brain.class)
public abstract class BrainMixin<E extends LivingEntity> {
    @Shadow
    @Final
    private Map<Integer, Map<Activity, Set<BehaviorControl<? super E>>>> availableBehaviorsByPriority;

    @Shadow
    @Final
    private Set<Activity> activeActivities;

    @Shadow
    @Final
    private Map<MemoryModuleType<?>, MemorySlot<?>> memories;

    /** Les cases mémoire à plat. Voir {@link #lanterne$forgetOutdated}. */
    @Unique
    private MemorySlot<?>[] lanterne$slots;

    /** Vrai si le dernier balayage a vu au moins une case susceptible d'expirer. */
    @Unique
    private boolean lanterne$anyExpiring = true;

    /** La valeur du pouls mémoire au dernier balayage. */
    @Unique
    private long lanterne$scannedAt = Long.MIN_VALUE;

    /** Les comportements des activités actives, à plat. {@code null} tant qu'il faut le refaire. */
    @Unique
    private ObjectArrayList<BehaviorControl<? super E>> lanterne$candidates;

    /** Les comportements en cours. {@code null} tant qu'il faut le refaire. */
    @Unique
    private MaskedList<BehaviorControl<? super E>> lanterne$active;

    /** La génération de réglages sous laquelle les deux collections ci-dessus ont été bâties. */
    @Unique
    private long lanterne$builtUnder = Long.MIN_VALUE;

    /**
     * Jette les deux collections.
     *
     * <p>Appelé quand les comportements eux-mêmes changent — ce qui n'arrive qu'à la construction de
     * la créature.
     */
    @Unique
    private void lanterne$forgetAll() {
        this.lanterne$candidates = null;
        this.lanterne$active = null;
    }

    /**
     * Jette les seuls candidats.
     *
     * <p>Un changement d'activité ne change pas quels comportements tournent : il change lesquels ont
     * le droit de démarrer. Garder la liste des actifs évite de la reconstruire pour rien.
     */
    @Unique
    private void lanterne$forgetCandidates() {
        this.lanterne$candidates = null;
    }

    /** Jette tout si les réglages ont basculé depuis la dernière fois. Voir {@link Settings#epoch()}. */
    @Unique
    private void lanterne$checkEpoch() {
        long now = Settings.epoch();
        if (now != this.lanterne$builtUnder) {
            this.lanterne$builtUnder = now;
            this.lanterne$forgetAll();
            // Pendant l'extinction, vanilla a pu écrire des mémoires à durée de vie sans que notre
            // balayage en sache rien. On repart de la position la plus prudente : tout rebalayer.
            this.lanterne$anyExpiring = true;
            this.lanterne$scannedAt = Long.MIN_VALUE;
        }
    }

    /**
     * Les cases mémoire à plat, refaites si leur nombre a changé.
     *
     * <p>Le contrôle sur le nombre est un filet, pas le mécanisme : les mémoires sont enregistrées à
     * la construction de la créature et à l'ajout d'une activité, et les deux invalident déjà la
     * liste. Mais {@code Map.size()} ne coûte rien, et il rattraperait une porte oubliée — c'est le
     * genre de garde qu'on ne regrette qu'en son absence.
     */
    @Unique
    private MemorySlot<?>[] lanterne$slots() {
        MemorySlot<?>[] slots = this.lanterne$slots;
        if (slots != null && slots.length == this.memories.size()) {
            return slots;
        }
        slots = this.memories.values().toArray(new MemorySlot<?>[0]);
        this.lanterne$slots = slots;
        return slots;
    }

    @Unique
    private ObjectArrayList<BehaviorControl<? super E>> lanterne$candidates() {
        ObjectArrayList<BehaviorControl<? super E>> list = this.lanterne$candidates;
        if (list != null) {
            return list;
        }
        list = new ObjectArrayList<>();
        // Le même ordre que vanilla : priorités croissantes (la table est un TreeMap), puis activités
        // dans l'ordre de la table de hachage, puis comportements dans leur ordre d'insertion.
        for (Map<Activity, Set<BehaviorControl<? super E>>> byActivity
                : this.availableBehaviorsByPriority.values()) {
            for (Map.Entry<Activity, Set<BehaviorControl<? super E>>> entry : byActivity.entrySet()) {
                if (!this.activeActivities.contains(entry.getKey())) {
                    continue;
                }
                for (BehaviorControl<? super E> behavior : entry.getValue()) {
                    list.add(behavior);
                }
            }
        }
        this.lanterne$candidates = list;
        return list;
    }

    @Unique
    private MaskedList<BehaviorControl<? super E>> lanterne$active() {
        MaskedList<BehaviorControl<? super E>> list = this.lanterne$active;
        if (list != null) {
            return list;
        }
        list = new MaskedList<>();
        // Ici toutes les activités, actives ou non : un comportement peut encore tourner alors que
        // son activité vient d'être quittée, et il faut pouvoir l'arrêter.
        for (Map<Activity, Set<BehaviorControl<? super E>>> byActivity
                : this.availableBehaviorsByPriority.values()) {
            for (Set<BehaviorControl<? super E>> behaviors : byActivity.values()) {
                for (BehaviorControl<? super E> behavior : behaviors) {
                    list.addOrSet(behavior, behavior.getStatus() == Behavior.Status.RUNNING);
                }
            }
        }
        this.lanterne$active = list;
        return list;
    }

    /**
     * Le vieillissement des mémoires, sauté quand rien ne peut avoir vieilli.
     *
     * <h2>Le poste</h2>
     *
     * <p>{@code this.memories.values().forEach(MemorySlot::tick)} : à chaque tick, chaque cerveau
     * parcourt la <b>totalité</b> de ses cases mémoire — une quarantaine chez un villageois — pour
     * décrémenter celles qui ont une durée de vie. C'est le {@code HashMap$Values.forEach} que le
     * profileur place juste derrière la lecture de blocs.
     *
     * <p>Or presque aucune case n'a de durée de vie. {@code MemorySlot.tick} commence par
     * {@code hasValue() && canExpire()}, et repart aussitôt pour la grande majorité d'entre elles.
     * On paie le parcours d'une table de hachage pour n'avoir rien à faire.
     *
     * <h2>Pourquoi le saut est sûr</h2>
     *
     * <p>Une case ne peut devenir périssable que si quelqu'un y écrit une durée de vie. Et toute
     * écriture passe par {@code setMemoryInternal}, que {@link MemoryPulseMixin} compte déjà.
     *
     * <p>Donc : si le dernier balayage n'a vu <b>aucune</b> case périssable, et que le pouls n'a pas
     * bougé depuis, alors rien ne peut avoir expiré. La condition ne repose sur aucune supposition —
     * elle repose sur le fait qu'il n'existe qu'une porte d'écriture, ce que
     * {@link MemoryPulseMixin} établit déjà pour un autre module.
     *
     * <h2>Le second gain, qui n'était pas cherché</h2>
     *
     * <p>{@link MemoryPulseMixin} incrémentait le pouls <em>à chaque</em> passage ici, par prudence :
     * il ne pouvait pas savoir si une case avait expiré. Le cache de {@link BehaviorMemoryMixin} était
     * donc jeté une fois par tick, ce qui lui retirait l'essentiel de son intérêt — il n'avait jamais
     * rendu mieux que ×1,01.
     *
     * <p>Ici, on le sait : c'est notre boucle qui appelle {@code tick()}, et elle voit la case
     * expirer. Le pouls n'avance donc que s'il s'est réellement passé quelque chose. La prudence
     * n'est pas abandonnée, elle est devenue inutile.
     */
    @WrapMethod(method = "forgetOutdatedMemories")
    private void lanterne$forgetOutdated(Operation<Void> original) {
        if (!Settings.mind()) {
            original.call();
            return;
        }
        this.lanterne$checkEpoch();

        // Le double transtypage : c'est MemoryPulseMixin qui pose cette interface sur Brain, et le
        // compilateur ne le sait pas encore.
        MemoryPulseAccess pulseHolder = (MemoryPulseAccess) (Object) this;
        long pulse = pulseHolder.lanterne$memoryPulse();
        if (!this.lanterne$anyExpiring && pulse == this.lanterne$scannedAt) {
            return;
        }

        boolean expiring = false;
        boolean expired = false;
        for (MemorySlot<?> slot : this.lanterne$slots()) {
            if (slot.hasValue() && slot.canExpire()) {
                if (slot.hasExpired()) {
                    expired = true;
                } else {
                    // Elle vivra encore après ce tick : il faudra repasser.
                    expiring = true;
                }
            }
            slot.tick();
        }

        this.lanterne$anyExpiring = expiring;
        if (expired) {
            // Une case s'est vidée : l'état du cerveau a changé, et les conditions d'entrée des
            // comportements doivent être retestées.
            pulseHolder.lanterne$bumpMemoryPulse();
        }
        this.lanterne$scannedAt = pulseHolder.lanterne$memoryPulse();
    }

    @WrapMethod(method = "startEachNonRunningBehavior")
    private void lanterne$startFromFlatList(ServerLevel level, E body, Operation<Void> original) {
        if (!Settings.mind()) {
            original.call(level, body);
            return;
        }
        this.lanterne$checkEpoch();

        long time = level.getGameTime();
        List<BehaviorControl<? super E>> candidates = this.lanterne$candidates();
        // Lue sans la forcer : si personne n'a encore demandé la liste des actifs, la bâtir ici ne
        // servirait qu'à la remplir une seconde fois juste après.
        MaskedList<BehaviorControl<? super E>> active = this.lanterne$active;

        int count = candidates.size();
        for (int i = 0; i < count; i++) {
            BehaviorControl<? super E> behavior = candidates.get(i);
            if (behavior.getStatus() != Behavior.Status.STOPPED) {
                continue;
            }
            behavior.tryStart(level, body, time);
            if (active != null && behavior.getStatus() == Behavior.Status.RUNNING) {
                active.setVisible(behavior, true);
            }
        }
        Mind.noteBrain();
    }

    @WrapMethod(method = "tickEachRunningBehavior")
    private void lanterne$tickFromMask(ServerLevel level, E body, Operation<Void> original) {
        if (!Settings.mind()) {
            original.call(level, body);
            return;
        }
        this.lanterne$checkEpoch();

        long timestamp = level.getGameTime();
        MaskedList<BehaviorControl<? super E>> active = this.lanterne$active();
        // Éteindre pendant le parcours est sûr : voir MaskedList, dont l'itérateur ne garde aucune
        // photo et redemande le bit suivant à chaque pas.
        for (BehaviorControl<? super E> behavior : active) {
            behavior.tickOrStop(level, body, timestamp);
            if (behavior.getStatus() != Behavior.Status.RUNNING) {
                active.setVisible(behavior, false);
            }
        }
    }

    @WrapMethod(method = "getRunningBehaviors")
    private List<BehaviorControl<? super E>> lanterne$runningBehaviors(
            Operation<List<BehaviorControl<? super E>>> original) {
        if (!Settings.mind()) {
            return original.call();
        }
        this.lanterne$checkEpoch();
        return this.lanterne$active();
    }

    @WrapMethod(method = "stopAll")
    private void lanterne$stopAll(ServerLevel level, E body, Operation<Void> original) {
        if (!Settings.mind()) {
            original.call(level, body);
            return;
        }
        this.lanterne$checkEpoch();

        long timestamp = body.level().getGameTime();
        MaskedList<BehaviorControl<? super E>> active = this.lanterne$active();
        for (BehaviorControl<? super E> behavior : active) {
            behavior.doStop(level, body, timestamp);
        }
        // Tout est arrêté, y compris ce qu'un doStop aurait pu arrêter en cascade.
        active.hideAll();
    }

    @Inject(method = "addActivity(Lnet/minecraft/world/entity/schedule/Activity;"
            + "Lcom/google/common/collect/ImmutableList;Ljava/util/Set;Ljava/util/Set;)V",
            at = @At("RETURN"))
    private void lanterne$onBehaviorsAdded(CallbackInfo callback) {
        this.lanterne$forgetAll();
    }

    @Inject(method = "removeAllBehaviors", at = @At("RETURN"))
    private void lanterne$onBehaviorsRemoved(CallbackInfo callback) {
        this.lanterne$forgetAll();
    }

    /**
     * Le changement d'activité.
     *
     * <p>Vanilla n'y touche à {@code activeActivities} que si l'activité demandée n'est pas déjà
     * active ; on invalide néanmoins à chaque passage. Viser le retour plutôt que l'appel interne à
     * {@code Set.add} coûte quelques reconstructions inutiles — une créature change d'activité
     * quelques fois par journée de jeu — et ne dépend d'aucun détail d'implémentation.
     */
    @Inject(method = "setActiveActivity", at = @At("RETURN"))
    private void lanterne$onActivityChanged(Activity activity, CallbackInfo callback) {
        this.lanterne$forgetCandidates();
    }
}
