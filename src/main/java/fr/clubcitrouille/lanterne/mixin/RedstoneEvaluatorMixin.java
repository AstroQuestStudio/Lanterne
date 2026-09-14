package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import net.minecraft.world.flag.FeatureFlags;
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
 *
 * <h2>Le module était juste, et son point d'accroche coûtait un quart de milliseconde par tick</h2>
 *
 * <p>Ce module n'avait jamais été mesuré. Il l'a été sur la nappe de redstone — trente-deux sur
 * trente-deux, mille vingt-quatre brins connectés, trente-deux sources qui battent tous les quatre
 * ticks — et le banc a rendu deux fois la même réponse, à un centième près :
 *
 * <pre>
 * sans : 1,75 ms · avec : 2,00 ms   →  ×0,88   ·  mémoire allouée 616,9 Mo contre 103,9 Mo
 * sans : 1,96 ms · avec : 2,21 ms   →  ×0,89   ·  mémoire allouée 463,2 Mo contre 115,6 Mo
 * </pre>
 *
 * <p>Deux exécutions, <b>exactement le même écart absolu</b> : vingt-cinq centièmes de milliseconde.
 * Pas un rapport, une <em>constante</em> — la signature d'un surcoût payé à chaque appel, et non
 * d'un algorithme plus lent. D'autant que le profil disait l'inverse sur le fond :
 * {@code ExperimentalRedstoneWireEvaluator.updatePowerStrength} pesait <b>17,1 %</b> du travail
 * relevé, là où {@code DefaultRedstoneWireEvaluator.updatePowerStrength} en pesait <b>57,9 %</b>, et
 * le serveur dormait 61 % du temps au lieu de 21 %.
 *
 * <p>La cause est celle que ce projet a déjà payée quatre fois — la bordure du monde, le repos posé,
 * le tirage aléatoire, les positions d'explosion : <b>sur un chemin très chaud, l'interception est le
 * coût</b>. {@code useExperimentalEvaluator} est appelée deux fois par mise à jour de brin, une fois
 * dans {@code neighborChanged} et une fois dans {@code updatePowerStrength} — des milliers de fois
 * par tick sur cette nappe. Un {@code @Inject} annulable y fabrique un {@code CallbackInfoReturnable}
 * à <em>chaque</em> appel, et retire au compilateur à la volée l'incorporation d'une méthode qui tient
 * en un test de masque de bits.
 *
 * <p>D'où le remplacement pur et simple du corps. Le coût redevient celui de vanilla — deux lectures
 * de champ statique et le même test de drapeau — et la méthode reste assez courte pour être
 * incorporée. C'est la première fois dans ce projet qu'un {@code @Overwrite} est préféré à un
 * {@code @Inject} <em>pour la vitesse</em>, et la raison est mesurée, pas supposée.
 */
@Mixin(RedStoneWireBlock.class)
public abstract class RedstoneEvaluatorMixin {
    /**
     * @author Lanterne
     * @reason Le corps tient en une ligne et se trouve sur un chemin parcouru des milliers de fois
     *         par tick ; un {@code @Inject} annulable y allouait un objet de rappel par appel, pour
     *         un quart de milliseconde de tick mesuré deux fois. Voir la javadoc de la classe.
     */
    @Overwrite
    private static boolean useExperimentalEvaluator(Level level) {
        return Settings.redstone()
                || level.enabledFeatures().contains(FeatureFlags.REDSTONE_EXPERIMENTS);
    }
}
