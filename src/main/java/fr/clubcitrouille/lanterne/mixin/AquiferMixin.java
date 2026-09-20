package fr.clubcitrouille.lanterne.mixin;

import org.apache.commons.lang3.mutable.MutableDouble;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Une boîte réutilisée à la place d'une allocation par bloc, dans le calcul des aquifères.
 *
 * <h2>D'où vient cette cible, et ce qui a changé entre 26.1.2 et 26.3</h2>
 *
 * <p>Trouvée et vérifiée par {@code notes/recherche-chunks.md} §4.3 sur les sources décompilées
 * 26.1.2, puis reconfirmée ici <b>au javap sur le vrai jar client 26.3</b>
 * ({@code neoformruntime/artifacts/minecraft_26.3_client.jar}) : `Aquifer$NoiseBasedAquifer
 * .computeSubstance` alloue toujours exactement un {@code new MutableDouble(Double.NaN)}
 * (désassemblage : un seul {@code NEW} sur ce type dans le corps de la méthode), passé à
 * {@code calculatePressure} pour ses trois appels possibles (barrières 1-2, 1-3, 2-3 entre poches de
 * fluide). La logique de mémoïsation à un coup que porte cette boîte est correcte et utile — c'est
 * son <b>support</b> qui coûte : un objet alloué sur le tas pour chaque bloc qui tombe près d'une
 * frontière d'aquifère, potentiellement plusieurs milliers par chunk généré en sous-sol accidenté.
 *
 * <h2>Pourquoi un champ d'instance est sûr ici</h2>
 *
 * <p>Un {@code NoiseBasedAquifer} est construit et utilisé par une seule tâche de génération de
 * chunk à la fois — il n'est jamais partagé entre threads (vérifié par la recherche 26.1.2 citée
 * ci-dessus ; propriété structurelle du pipeline de génération, pas un détail de version). Un champ
 * mutable non synchronisé ne casse donc rien : au pire, deux appels <em>séquentiels</em> sur la même
 * instance réutilisent la même boîte l'un après l'autre, ce qui est exactement le comportement
 * recherché.
 *
 * <h2>Pourquoi {@code @Redirect} sur le {@code NEW}, pas un {@code @ModifyVariable}</h2>
 *
 * <p>{@code computeSubstance} n'expose la boîte à aucune variable locale nommée qu'un
 * {@code @ModifyVariable} pourrait cibler proprement à travers les optimisations du compilateur — le
 * point d'ancrage stable est l'instruction {@code NEW} elle-même, exactement le patron déjà utilisé
 * par {@code EmballageEnvoiMixin} de ce même dépôt pour la même raison (cible par descripteur complet,
 * pas par nom de classe seul, pour qu'un changement de signature du constructeur fasse échouer
 * l'injection au démarrage plutôt que s'appliquer ailleurs).
 *
 * <p>Risque de déterminisme : nul — la valeur numérique produite par {@code calculatePressure} ne
 * change pas, seul l'endroit où elle est stockée entre les trois appels change.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.Aquifer$NoiseBasedAquifer")
public abstract class AquiferMixin {
    /** Une boîte par instance d'Aquifère, jamais partagée entre threads — voir la Javadoc de classe. */
    @Unique
    private final MutableDouble lanterne$barrierBox = new MutableDouble(Double.NaN);

    @Redirect(method = "computeSubstance",
            at = @At(value = "NEW",
                    target = "(D)Lorg/apache/commons/lang3/mutable/MutableDouble;"))
    private MutableDouble lanterne$reuseBarrierBox(double valeurInitiale) {
        this.lanterne$barrierBox.setValue(valeurInitiale);
        return this.lanterne$barrierBox;
    }
}
