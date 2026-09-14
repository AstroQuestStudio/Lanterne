package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;

import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le bon moteur de redstone, celui que Mojang a écrit puis laissé éteint.
 *
 * <h2>Deux évaluateurs dans le jeu, et le plus lent est celui qui tourne</h2>
 *
 * <p>26.1 contient <b>deux</b> implémentations du calcul de puissance de la poussière de redstone :
 *
 * <ul>
 *   <li>{@code DefaultRedstoneWireEvaluator} — quarante-deux lignes. Chaque brin recalcule sa
 *       puissance <em>isolément</em>, sans savoir ce que font ses voisins. Dans un réseau, un brin
 *       change donc de valeur une demi-douzaine de fois avant de se fixer, et chaque changement
 *       émet quarante-deux mises à jour de bloc.</li>
 *   <li>{@code ExperimentalRedstoneWireEvaluator} — deux cent vingt et une lignes. Il traite le
 *       réseau <b>entier</b> à l'aide de files ({@code wiresToTurnOff}, {@code wiresToTurnOn}), et
 *       n'émet que les mises à jour finales.</li>
 * </ul>
 *
 * <p>Le second est exactement l'algorithme que le mod de référence sur ce sujet apporte depuis des
 * années. Mojang l'a donc écrit, et l'a placé derrière {@code FeatureFlags.REDSTONE_EXPERIMENTS} —
 * un drapeau qu'il faut cocher à la création du monde, et que presque personne ne coche. Par défaut,
 * c'est le premier qui tourne.
 *
 * <h2>Cinq lignes plutôt que mille quatre cents</h2>
 *
 * <p>Porter le mod aurait voulu dire copier son moteur complet — files de priorité, ordre de mise à
 * jour, gestionnaire de connexions — et le maintenir ensuite contre chaque version de Minecraft.
 * Basculer le drapeau donne le même algorithme, écrit et testé par Mojang, maintenu par Mojang, et
 * qui suivra les versions futures sans nous.
 *
 * <h2>Pourquoi ce module est éteint par défaut</h2>
 *
 * <p>Parce qu'il <b>change le comportement du jeu</b>, et pas seulement sa vitesse. L'ordre dans
 * lequel la poussière met à jour ses voisins cesse de dépendre des coordonnées pour devenir
 * prévisible — ce qui corrige au passage le défaut MC-11193, mais peut faire se comporter
 * différemment un circuit bâti, volontairement ou non, sur l'ordre d'origine.
 *
 * <p>La règle de ce mod est qu'un module d'optimisation qui casse une ferme a échoué, même s'il
 * double le nombre de ticks par seconde. Celui-ci ne casse rien en soi, mais il modifie une
 * mécanique dont certains joueurs dépendent finement. Ce choix leur appartient, et il est écrit en
 * clair dans le fichier de réglages.
 */
@Mixin(RedStoneWireBlock.class)
public abstract class RedstoneEvaluatorMixin {
    @Inject(method = "useExperimentalEvaluator", at = @At("HEAD"), cancellable = true)
    private static void lanterne$preferTheGoodOne(Level level,
            CallbackInfoReturnable<Boolean> callback) {
        if (Settings.redstone()) {
            callback.setReturnValue(true);
        }
    }
}
