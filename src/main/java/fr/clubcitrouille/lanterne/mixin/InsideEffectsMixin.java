package fr.clubcitrouille.lanterne.mixin;

import java.util.Collection;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Spill;

/**
 * Le tableau vide qu'on ne fabrique pas.
 *
 * <h2>La cause, vérifiée ligne par ligne</h2>
 *
 * <pre>
 * private void flushStep() {
 *     for (InsideBlockEffectType type : APPLY_ORDER) {
 *         List&lt;Consumer&lt;Entity&gt;&gt; beforeEffects = this.beforeEffectsInStep.get(type);
 *         this.finalEffects.addAll(beforeEffects);      // &lt;- liste VIDE, presque toujours
 * </pre>
 *
 * <p>{@code ArrayList.addAll} appelle {@code toArray()} sur sa source <b>avant</b> de regarder si
 * elle contient quelque chose, et {@code Arrays.copyOf(données, 0)} rend un tableau neuf. Deux appels
 * par type d'effet, à chaque pas de déplacement, pour chaque entité — et toiles, feu, buissons et
 * poudre à neige ne sont presque jamais là.
 *
 * <h2>Ce que la mesure a dit, et pourquoi ce module reste</h2>
 *
 * <pre>
 * 2 448 370 tableaux non fabriqués
 * mémoire — sans : 2,92 et 2,93 Go  ·  avec : 2,90 et 2,91 Go   (0,7 %)
 * temps   — aucun effet
 * </pre>
 *
 * <p>Le profileur d'allocations annonçait 2,70 Go. La multiplication qu'il fallait poser avant de
 * coder en donne trente-neuf : deux millions quatre cent mille tableaux de longueur zéro, seize
 * octets pièce. Le profileur s'était trompé d'un facteur soixante-dix, parce qu'il
 * <b>échantillonne et extrapole</b> — et surévalue les petits objets très nombreux.
 *
 * <p>Ce module a d'abord été retiré pour cette raison. Il est revenu parce que la règle du projet
 * était trop grossière : <b>vingt mégaoctets restent vingt mégaoctets, et le temps ne bouge pas.</b>
 * C'est la différence de nature avec la bordure du monde, qui coûtait quatre millisecondes, ou le
 * repos posé, qui en coûtait une.
 *
 * <p>On retire ce qui coûte ; on garde ce qui gagne, même peu. Un serveur rapide n'est pas fait d'un
 * gros poisson, mais de leur somme.
 *
 * <h2>{@code @Redirect}, et non {@code @WrapOperation}</h2>
 *
 * <p>« à chaque pas de déplacement, pour chaque entité » ci-dessus n'est pas une figure de style :
 * 2,4 millions d'appels mesurés dans une seule session, le chemin chaud le plus fréquent de tout ce
 * module. Cette substitution n'appelle jamais l'original plus d'une fois (soit elle l'appelle une
 * fois telle quelle, soit elle le remplace entièrement par {@code false}) — exactement le genre de
 * site que {@code @Redirect} couvre sans passer par {@code Operation<Boolean>.call(Object...)}
 * (tableau alloué, boîtage, {@code invokedynamic}) : {@code @Redirect} le remplace par un appel
 * statique typé direct, comportement identique bit à bit.
 */
@Mixin(targets = "net.minecraft.world.entity.InsideBlockEffectApplier$StepBasedCollector")
public abstract class InsideEffectsMixin {
    @Redirect(method = "flushStep",
            at = @At(value = "INVOKE", target = "Ljava/util/List;addAll(Ljava/util/Collection;)Z"))
    private boolean lanterne$skipEmptyCopy(List<Object> target, Collection<Object> source) {
        if (!Settings.spill() || !source.isEmpty()) {
            return target.addAll(source);
        }
        // addAll d'une collection vide rend faux et ne change rien. Ne pas l'appeler produit le même
        // état, sans le tableau de longueur zéro que toArray() aurait fabriqué.
        Spill.noteSkipped();
        return false;
    }
}
