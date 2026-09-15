package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;

import fr.clubcitrouille.lanterne.core.Foule;

/**
 * Le trou qu'un changement de posture aurait laissé, et qu'on ferme.
 *
 * <h2>Une boîte qui grandit sans que personne ne bouge</h2>
 *
 * <p>La grille de {@link Foule} range les entités par <b>position</b>, et élargit chaque requête de
 * la plus grande boîte présente dans la section. Cette marge est une borne prouvée : si la boîte
 * d'une entité touche la boîte demandée, alors leur écart de position n'excède pas une taille de
 * boîte sur chaque axe. La démonstration tient tant que la marge est à jour.
 *
 * <p>Or {@code refreshDimensions} change la boîte <b>sans changer la position</b> :
 *
 * <pre>
 * public void refreshDimensions() {
 *     …
 *     this.dimensions = newDim;
 *     this.reapplyPosition();      // setPos(getX(), getY(), getZ()) — position inchangée
 * </pre>
 *
 * <p>Et {@code setPosRaw} ne prévient son rappel que si la position a réellement changé. Un joueur
 * qui se réveille passe de vingt centimètres à un mètre quatre-vingts sans un pas ; dans un enclos
 * où la plus grande bête mesure un mètre quarante, la marge resterait à un mètre quarante et il
 * pourrait manquer à l'appel d'une recherche — une vache qui ne le pousse plus, le temps qu'il
 * bouge.
 *
 * <p>Le dégât est minuscule et sa durée d'un tick. Il est surtout <b>invisible</b>, et c'est la
 * seule raison qui compte : un mod d'optimisation n'a pas le droit de laisser derrière lui une
 * erreur que personne ne peut voir ni reproduire.
 *
 * <h2>Pourquoi {@code refreshDimensions} et non {@code setBoundingBox}</h2>
 *
 * <p>{@code setBoundingBox} verrait tout, y compris ce qu'un mod ferait dans son coin — mais il est
 * appelé à chaque déplacement de chaque entité, et n'y voit qu'une <b>translation</b> : la taille
 * n'y change jamais. {@code refreshDimensions} est le seul chemin du jeu vers un changement de
 * taille, et il n'est emprunté qu'au changement de posture ou d'échelle. On paie donc le contrôle
 * là où il coûte quelque chose comme une fois par seconde, et non trois fois par tick et par bête.
 *
 * <p>Ce qui reste dehors est dit sans détour dans {@link Foule} : un mod qui appellerait
 * {@code setBoundingBox} avec une boîte d'une autre taille, sans passer par
 * {@code refreshDimensions}, ne serait pas vu. Aucun code du jeu ne fait cela.
 *
 * <h2>Le coût quand il n'y a pas foule</h2>
 *
 * <p>Une lecture de champ statique. {@code Foule.tenues()} est faux tant qu'aucune section du
 * serveur n'a atteint le seuil de foule — c'est-à-dire sur la quasi-totalité des mondes, tout le
 * temps.
 */
@Mixin(Entity.class)
public abstract class FouleEntityMixin {
    @Shadow
    private EntityInLevelCallback levelCallback;

    @Inject(method = "refreshDimensions", at = @At("TAIL"))
    private void lanterne$margeSuitLeGabarit(CallbackInfo retour) {
        if (Foule.tenues() && this.levelCallback instanceof Foule.Suivi suivi) {
            suivi.lanterne$replace();
        }
    }
}
