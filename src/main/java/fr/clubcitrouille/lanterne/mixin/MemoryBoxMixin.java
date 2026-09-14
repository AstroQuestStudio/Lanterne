package fr.clubcitrouille.lanterne.mixin;

import java.util.Optional;

import com.mojang.datafixers.kinds.OptionalBox;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.entity.ai.behavior.declarative.MemoryCondition;

import fr.clubcitrouille.lanterne.core.Mind;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * La boîte vide, allouée cent dix-sept millions de fois, désormais partagée.
 *
 * <h2>Le premier poste d'allocation du serveur</h2>
 *
 * <p>Le profileur d'allocations, lancé sur six cents villageois après l'accélération du cerveau, a
 * rendu un classement sans appel :
 *
 * <pre>
 * 10,5 %  MemoryCondition$Registered.createAccessor → OptionalBox   (1,88 Go)
 *  4,3 %  Brain.getRunningBehaviors → LinkedHashMap$LinkedKeyIterator
 *  3,0 %  SerializableChunkData.write → CompoundTag
 * </pre>
 *
 * <p>Un poste à lui seul plus lourd que les trois suivants réunis, pour un objet qui ne contient
 * <b>rien</b>.
 *
 * <h2>D'où viennent ces boîtes</h2>
 *
 * <p>Les comportements déclaratifs de 26.1 décrivent leurs conditions d'entrée par des
 * {@code MemoryCondition}. À chaque tentative de démarrage, {@code BehaviorBuilder$PureMemory}
 * interroge le cerveau puis emballe la réponse :
 *
 * <pre>
 * Optional&lt;Value&gt; value = brain.getMemoryInternal(condition.memory());
 * return value == null ? null : condition.createAccessor(brain, value);
 * </pre>
 *
 * <p>Et pour la condition {@code REGISTERED} — « cette mémoire existe, peu importe son contenu » —
 * l'emballage est&nbsp;:
 *
 * <pre>
 * return new MemoryAccessor&lt;&gt;(brain, this.memory, OptionalBox.create(value));
 * </pre>
 *
 * <p>Or {@code value} est très souvent {@code Optional.empty()} : une mémoire enregistrée mais vide
 * est le cas ordinaire, pas l'exception. On fabrique donc une boîte neuve pour y ranger un vide, on
 * la consulte, et on la jette — quelques dizaines de fois par créature et par tick.
 *
 * <h2>Pourquoi partager une seule boîte est sûr</h2>
 *
 * <p>Ce n'est pas une intuition, c'est une vérification. {@code javap} sur la bibliothèque
 * DataFixerUpper 9.0.19 rend ceci :
 *
 * <pre>
 * public final class OptionalBox&lt;T&gt; implements App&lt;OptionalBox$Mu, T&gt; {
 *     private final Optional&lt;T&gt; value;
 *     public static &lt;T&gt; OptionalBox&lt;T&gt; create(Optional&lt;T&gt;);
 *     private OptionalBox(Optional&lt;T&gt;);
 * }
 * </pre>
 *
 * <p>Classe {@code final}, champ {@code final}, constructeur privé, aucune méthode de mutation.
 * L'objet est immuable au sens strict : deux boîtes vides sont indiscernables, et personne ne peut
 * transformer l'une en l'autre. Le type générique, lui, disparaît à l'exécution — une boîte vide de
 * {@code Villager} et une boîte vide de {@code BlockPos} sont le même objet dans la machine.
 *
 * <p>Cette vérification n'était pas facultative. Ce mod a déjà payé une supposition sur une
 * bibliothèque qu'il n'avait pas lue, et l'a payée à l'écran.
 *
 * <h2>Ce qui n'est pas économisé</h2>
 *
 * <p>Le {@code MemoryAccessor} qui enveloppe la boîte reste alloué à chaque appel, lui. Il retient le
 * cerveau et le type de mémoire, et sert ensuite à écrire — on ne peut pas le partager. Ce module ne
 * prétend donc qu'à la moitié du chemin, et c'est celle que le profileur désigne.
 *
 * <h2>Ce que le banc a rendu, et la troisième règle d'instrument</h2>
 *
 * <p>Mesuré seul sur six cents villageois : <b>×1,04, non prouvé</b> — sous la dérive de ce banc.
 * 1 198 076 boîtes partagées, pour <b>vingt mégaoctets</b> d'allocations en moins par fenêtre.
 *
 * <p>Vingt mégaoctets, quand le profileur annonçait 1,88 Go. L'écart n'est pas une erreur de mesure,
 * c'est une erreur de lecture — la mienne. Le profileur d'allocations classe par <b>volume cumulé
 * depuis l'ouverture de l'enregistrement</b>, qui couvre le chargement du serveur, la décantation et
 * les <em>deux</em> phases entrelacées. Les 17,99 Go de son total ne sont pas le travail d'un tick.
 *
 * <p>Voilà la troisième règle d'instrument de ce projet, après celle de l'échantillonneur de temps
 * et celle du facteur soixante-dix : <b>un pourcentage n'a de sens que rapporté à ce qui a été
 * totalisé</b>. « Dix pour cent des allocations » désignait un poste réel, et ce poste pèse quatre
 * pour cent d'une fenêtre de mesure.
 *
 * <h2>Pourquoi il est gardé quand même</h2>
 *
 * <p>Parce qu'il ne coûte rien : une comparaison {@code isEmpty()} sur un {@code Optional} déjà en
 * main, et un champ statique en moins à remplir. La règle de la maison est qu'on retire ce qui
 * coûte et qu'on garde ce qui gagne, même peu — et vingt mégaoctets de moins toutes les vingt-cinq
 * secondes, c'est peu, mais c'est du bon côté. Sur une machine à un seul cœur, où le ramasse-miettes
 * ne tourne pas à côté du tick mais le vole, ce peu se paie en temps de jeu.
 *
 * <p>Il est marqué <b>non prouvé</b> dans le rapport, et il le restera tant qu'une charge ne l'aura
 * pas fait sortir de la dérive.
 */
@Mixin(MemoryCondition.Registered.class)
public abstract class MemoryBoxMixin {
    /**
     * L'unique boîte vide.
     *
     * <p>Créée par le constructeur de la bibliothèque, comme n'importe quelle autre : on ne
     * contourne rien, on cesse simplement d'en refaire une à chaque fois.
     */
    @Unique
    private static final OptionalBox<Object> LANTERNE_EMPTY = OptionalBox.create(Optional.empty());

    @SuppressWarnings("unchecked")
    @Redirect(method = "createAccessor",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/datafixers/kinds/OptionalBox;create"
                             + "(Ljava/util/Optional;)Lcom/mojang/datafixers/kinds/OptionalBox;"))
    private <Value> OptionalBox<Value> lanterne$shareTheEmptyBox(Optional<Value> value) {
        if (Settings.boxes() && value.isEmpty()) {
            Mind.noteBox();
            return (OptionalBox<Value>) LANTERNE_EMPTY;
        }
        return OptionalBox.create(value);
    }
}
