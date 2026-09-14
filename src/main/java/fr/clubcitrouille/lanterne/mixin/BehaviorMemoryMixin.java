package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;

import fr.clubcitrouille.lanterne.core.MemoryPulseAccess;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Les conditions d'entrée d'un comportement, retestées seulement si quelque chose a changé.
 *
 * <h2>Le poste</h2>
 *
 * <p>{@code Behavior.tryStart} commence par {@code hasRequiredMemories}, qui parcourt les conditions
 * d'entrée du comportement et interroge le cerveau pour chacune. Un villageois porte une quinzaine
 * de comportements, chacun exigeant deux à cinq mémoires : cela fait une cinquantaine
 * d'interrogations par créature et par tick. Sur six cents villageois, trente mille par tick.
 *
 * <p>Et la plupart du temps, <b>rien n'a changé</b> depuis le tick précédent. La réponse est la
 * même, recalculée pour rien.
 *
 * <h2>Ce que le profil dit, et ce qu'il ne dit pas</h2>
 *
 * <p>{@code Brain.startEachNonRunningBehavior} porte <b>28,9 %</b> de la pile sur six cents
 * villageois. Un premier module a tenté d'en profiter en supprimant le parcours des tables du
 * cerveau : il a rendu ×1,01, puis ×1,03. Le temps n'était donc pas dans la boucle, mais dans ce
 * qu'elle appelle — c'est-à-dire ici.
 *
 * <p>C'est la première règle d'instrument sous son visage utile : une méthode qui monte haut dans un
 * échantillonneur de temps désigne <em>une région</em>, pas une ligne. Il faut descendre.
 *
 * <h2>Pourquoi ce cache ne peut pas figer une créature</h2>
 *
 * <p>Le cache n'est valable que pour une valeur donnée du compteur de {@code MemoryPulseMixin}, qui
 * s'incrémente à toute écriture, tout effacement, et à chaque passage du ramasseur de mémoires
 * expirées. Une créature dont l'état change voit donc son cache invalidé dans le même tick.
 *
 * <p>Et si un chemin de mutation avait malgré tout été oublié, l'incrément posé sur les expirations
 * — qui a lieu à <b>chaque</b> tick de cerveau — garantit que l'erreur ne survivrait pas au tick en
 * cours.
 */
@Mixin(Behavior.class)
public abstract class BehaviorMemoryMixin<E extends LivingEntity> {
    @Unique
    private long lanterne$seenPulse = -1L;

    @Unique
    private boolean lanterne$lastAnswer;

    @Inject(method = "hasRequiredMemories", at = @At("HEAD"), cancellable = true)
    private void lanterne$recall(E body, CallbackInfoReturnable<Boolean> callback) {
        if (!Settings.recall()) {
            return;
        }
        long pulse = ((MemoryPulseAccess) body.getBrain()).lanterne$memoryPulse();
        if (pulse == this.lanterne$seenPulse) {
            callback.setReturnValue(this.lanterne$lastAnswer);
        }
    }

    @Inject(method = "hasRequiredMemories", at = @At("RETURN"))
    private void lanterne$remember(E body, CallbackInfoReturnable<Boolean> callback) {
        if (!Settings.recall()) {
            return;
        }
        this.lanterne$seenPulse = ((MemoryPulseAccess) body.getBrain()).lanterne$memoryPulse();
        this.lanterne$lastAnswer = callback.getReturnValue();
    }
}
