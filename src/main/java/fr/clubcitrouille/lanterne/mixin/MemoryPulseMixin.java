package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.ai.Brain;

import fr.clubcitrouille.lanterne.core.MemoryPulseAccess;

/**
 * Le compteur de modifications de mémoire, posé sur le cerveau.
 *
 * <h2>Pourquoi 26.1 rend ce module beaucoup plus sûr qu'ailleurs</h2>
 *
 * <p>Le mod dont ce mécanisme vient doit surveiller cinq points de mutation différents, et manquer
 * l'un d'eux figerait une créature dans un comportement — un défaut silencieux, qui ne se verrait
 * qu'en jouant, et tard.
 *
 * <p>En 26.1, <b>toutes les écritures passent par {@code setMemoryInternal}</b>, quelles que soient
 * les trois surcharges publiques employées ({@code setMemory}, {@code setMemoryWithExpiry},
 * {@code setMemory} avec {@code Optional}). Il ne reste donc que quatre portes à garder, et elles
 * sont toutes nommées ici :
 *
 * <ul>
 *   <li>{@code setMemoryInternal} — toute écriture</li>
 *   <li>{@code eraseMemory} — un effacement ciblé</li>
 *   <li>{@code clearMemories} — un effacement total</li>
 *   <li>{@code forgetOutdatedMemories} — les expirations, qui changent l'état sans que personne
 *       n'ait rien écrit</li>
 * </ul>
 *
 * <p>La dernière est la plus facile à oublier et la plus traître : une mémoire qui expire cesse
 * d'exister sans qu'aucun appel ne le demande. La manquer donnerait une créature qui continue de
 * croire qu'elle se souvient de quelque chose.
 *
 * <h2>Pourquoi compter grossièrement suffit</h2>
 *
 * <p>Le compteur ne dit pas <em>quelle</em> mémoire a changé. Un comportement qui ne dépend pas de
 * la mémoire modifiée reverifiera donc ses conditions pour rien. C'est voulu : suivre les
 * dépendances mémoire par mémoire coûterait plus cher que les vérifications qu'on cherche à éviter,
 * et surtout donnerait une occasion de plus de se tromper.
 *
 * <p>Ce qu'on économise est le cas immensément majoritaire : un cerveau dont <b>rien</b> n'a bougé
 * d'un tick à l'autre, et qui reteste pourtant la cinquantaine de conditions de ses quinze
 * comportements.
 */
@Mixin(Brain.class)
public abstract class MemoryPulseMixin implements MemoryPulseAccess {
    @Unique
    private long lanterne$pulse = 1L;

    @Override
    public long lanterne$memoryPulse() {
        return this.lanterne$pulse;
    }

    @Override
    public void lanterne$bumpMemoryPulse() {
        this.lanterne$pulse++;
    }

    @Inject(method = "setMemoryInternal(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;"
            + "Ljava/lang/Object;J)V", at = @At("RETURN"))
    private void lanterne$onWriteWithExpiry(CallbackInfo callback) {
        this.lanterne$pulse++;
    }

    @Inject(method = "setMemoryInternal(Lnet/minecraft/world/entity/ai/memory/MemoryModuleType;"
            + "Ljava/lang/Object;)V", at = @At("RETURN"))
    private void lanterne$onWrite(CallbackInfo callback) {
        this.lanterne$pulse++;
    }

    @Inject(method = "setMemoryInternal(Lnet/minecraft/world/entity/ai/memory/MemoryMap$Value;)V",
            at = @At("RETURN"))
    private void lanterne$onWriteValue(CallbackInfo callback) {
        this.lanterne$pulse++;
    }

    @Inject(method = "eraseMemory", at = @At("RETURN"))
    private void lanterne$onErase(CallbackInfo callback) {
        this.lanterne$pulse++;
    }

    @Inject(method = "clearMemories", at = @At("RETURN"))
    private void lanterne$onClear(CallbackInfo callback) {
        this.lanterne$pulse++;
    }

    /**
     * Les expirations.
     *
     * <p>Une mémoire qui arrive au bout de sa durée de vie s'efface d'elle-même, sans qu'aucun
     * appelant ne l'ait demandé. On ne sait pas ici <em>si</em> l'une d'elles a expiré — seulement
     * que le moment où cela peut arriver vient de passer. On compte donc à chaque tick.
     *
     * <p>Cela revient à invalider le cache une fois par tick de cerveau, ce qui semble annuler tout
     * le bénéfice. Ce n'est pas le cas : {@code startEachNonRunningBehavior} teste quinze
     * comportements <b>dans le même tick</b>, et les tests qui suivent le premier profitent tous du
     * cache tant qu'aucune écriture n'a lieu entre eux. C'est la prudence qui commande : une
     * créature figée coûte plus cher qu'une vérification refaite.
     */
    @Inject(method = "forgetOutdatedMemories", at = @At("RETURN"))
    private void lanterne$onExpiry(CallbackInfo callback) {
        this.lanterne$pulse++;
    }
}
