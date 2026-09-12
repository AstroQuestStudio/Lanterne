package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Sleep;

/**
 * Le four qui dort jusqu'à ce que quelque chose arrive.
 *
 * <h2>Ce que coûte une cuisson tranquille</h2>
 *
 * <p>En régime établi — four allumé, ingrédient présent, recette connue — chaque tick exécute :
 *
 * <pre>
 * SingleRecipeInput input = new SingleRecipeInput(ingredient);        // allocation
 * RecipeHolder&lt;…&gt; recipe = entity.quickCheck.getRecipeFor(input, level);
 * ItemStack burnResult = recipe.value().assemble(input);              // allocation
 * canBurn(entity.items, maxStackSize, burnResult);
 * entity.cookingTimer++;                                             // le seul effet utile
 * </pre>
 *
 * <p>Deux allocations et une recherche de recette, par four et par tick, pour <b>incrémenter un
 * compteur</b>. Deux cents fours dans une base en font huit mille allocations par seconde — et sur
 * une machine à un cœur, un ramassage ne s'exécute pas en parallèle : il fige le serveur.
 *
 * <h2>Dormir plutôt que ralentir</h2>
 *
 * <p>Ce mod a d'abord écarté l'idée de toucher aux fours et aux entonnoirs, et il avait raison de le
 * faire : les <b>ralentir</b> divise leur débit, et un four qui cuit seize fois moins vite est un
 * four cassé. Mais il existe une troisième voie, que la question « on sait combien de temps ça
 * prend, pourquoi recalculer chaque tick ? » désigne exactement.
 *
 * <p>En régime établi, l'issue est <b>connue d'avance</b>. Deux échéances seulement :
 *
 * <ul>
 *   <li>la cuisson s'achève dans {@code cookingTotalTime - cookingTimer} ticks ;</li>
 *   <li>le combustible s'épuise dans {@code litTimeRemaining} ticks.</li>
 * </ul>
 *
 * <p>Entre maintenant et la première des deux, <b>rien d'observable ne se produit</b> : les
 * compteurs avancent d'un pas régulier, et c'est tout. On peut donc ne rien faire jusque-là, puis
 * avancer les compteurs de la quantité exacte de ticks écoulés.
 *
 * <p>La différence avec un ralentissement est entière : le four s'éteint au même tick, l'objet est
 * cuit au même tick, le débit est <b>identique au tick près</b>. On ne dégrade rien, on cesse
 * simplement de recalculer une réponse déjà connue.
 *
 * <h2>Ce qui réveille</h2>
 *
 * <p>Le sommeil se rompt dès que l'hypothèse tombe. {@code setChanged()} est appelé par le jeu à
 * chaque modification du contenu — joueur, entonnoir, dispenseur — et c'est exactement le signal
 * qu'il faut. On y raccroche le réveil plutôt que de surveiller l'inventaire soi-même : le jeu sait
 * déjà quand il change, et il le dit.
 *
 * <p>Le sommeil est en outre borné : on ne dort jamais plus que la prochaine échéance, et jamais
 * au-delà d'un plafond. Un four oublié se réveille donc régulièrement même si personne ne le
 * touche — au pire on aura économisé un peu moins, jamais trop.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceMixin {
    @Shadow
    private int litTimeRemaining;
    @Shadow
    private int cookingTimer;
    @Shadow
    private int cookingTotalTime;

    /**
     * Décide de dormir, ou rattrape le temps dormi.
     *
     * <p>Le rattrapage précède le travail : au réveil, on remet les compteurs à la valeur qu'ils
     * auraient eue si chaque tick avait été exécuté, puis on laisse le jeu faire son tick normal.
     * Celui-ci constatera donc exactement ce qu'il aurait constaté — fin de cuisson, fin de
     * combustible, ou rien.
     */
    @Inject(method = "serverTick", at = @At("HEAD"), cancellable = true)
    private static void lanterne$sleepUntilDue(ServerLevel level, BlockPos pos, BlockState state,
                                               AbstractFurnaceBlockEntity entity,
                                               CallbackInfo callback) {
        if (!Settings.sleep()) {
            return;
        }

        FurnaceMixin self = (FurnaceMixin) (Object) entity;
        long now = level.getGameTime();

        int skipped = Sleep.due(pos, now);
        if (skipped < 0) {
            callback.cancel(); // l'échéance n'est pas arrivée : rien à faire, et rien ne se passe
            return;
        }

        if (skipped > 0) {
            // <h2>Une égalité stricte, et un compteur qui la dépasse</h2>
            //
            // Le jeu teste la fin de cuisson ainsi :
            //
            // <pre>
            // entity.cookingTimer++;
            // if (entity.cookingTimer == entity.cookingTotalTime) { … }
            // </pre>
            //
            // C'est une <b>égalité</b>, pas une comparaison. La première version amenait le compteur
            // exactement à {@code cookingTotalTime} ; le {@code ++} du jeu le portait aussitôt à
            // deux cent un, l'égalité était manquée, et le compteur montait indéfiniment sans que
            // rien ne cuise jamais. Le journal l'a montré sans détour : « timer=219/200 ».
            //
            // On s'arrête donc un cran avant, pour que ce soit bien le jeu qui franchisse la ligne.
            self.cookingTimer = Math.min(self.cookingTimer + skipped, self.cookingTotalTime - 1);
            self.litTimeRemaining = Math.max(0, self.litTimeRemaining - skipped);
        }

        // Le tick vanilla va s'exécuter. On calcule ensuite jusqu'à quand il ne se passera rien.
        // Le tick vanilla va s'exécuter juste après et avancera les compteurs d'un pas : on retire
        // donc deux ticks à l'échéance, un pour ce tick-ci et un pour celui qui devra franchir la
        // ligne lui-même.
        Sleep.plan(pos, now, self.nextDeadline());
    }

    /**
     * Ticks avant le prochain évènement observable, ou zéro s'il peut survenir tout de suite.
     *
     * <p>On retranche un tick aux deux échéances : le tick où la chose se produit doit être joué
     * par le jeu lui-même, pas rattrapé par un calcul. C'est ce qui garantit que la cuisson, la
     * consommation du combustible et la mise à jour de l'état du bloc empruntent le chemin normal.
     */
    private int nextDeadline() {
        if (litTimeRemaining <= 0) {
            return 0; // éteint : le prochain tick peut tout changer, on ne préjuge de rien
        }
        int untilCooked = cookingTotalTime - cookingTimer;
        if (untilCooked <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(untilCooked, litTimeRemaining) - 2);
    }
}
