package fr.clubcitrouille.lanterne.mixin;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import fr.clubcitrouille.lanterne.core.Raccourci;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le point d'accroche unique de {@link Raccourci} : la place où vanilla appelle
 * {@code RecipeManager.getRecipeFor} avec un indice toujours nul.
 *
 * <h2>Une seule cible pour les deux grilles</h2>
 *
 * <p>{@code CraftingMenu.slotChangedCraftingGrid} est une méthode {@code static} PARTAGÉE : lue par
 * {@code javap} sur le vrai jar patché, {@code CraftingMenu.slotsChanged} (la table à craft) ET
 * {@code InventoryMenu.slotsChanged} (la grille 2x2 du joueur) l'appellent TOUTES LES DEUX, chacune
 * avec {@code null} en dernier argument. Un seul mixin, sur cette seule méthode, couvre donc les
 * deux grilles — voir la javadoc de {@link Raccourci} pour la preuve bytecode complète.
 *
 * <h2>Pourquoi un redirect, pas une réécriture</h2>
 *
 * <p>Même doctrine que {@code BurinMixin} : rediriger l'appel porte sa signature exacte, que
 * {@code tools/verifie_mixins.py} sait vérifier contre le vrai jar — réécrire le corps de la
 * méthode ne laisserait rien à vérifier statiquement, et risquerait de diverger de vanilla à la
 * moindre mise à jour sans qu'aucun outil ne le remarque.
 */
@Mixin(CraftingMenu.class)
public abstract class RaccourciCraftingMixin {

    /**
     * Remplace l'indice nul par le souvenir de {@link Raccourci} quand la grille n'a pas été
     * remplie par le livre de recettes (auto-placement), puis mémorise ce que vanilla a réellement
     * trouvé pour le prochain essai.
     *
     * <p>Quand le livre de recettes fournit DÉJÀ un indice explicite (un joueur qui clique sur une
     * recette suggérée), il est laissé intact : c'est déjà la bonne réponse, {@link Raccourci} n'a
     * rien à y ajouter.
     */
    @SuppressWarnings("unchecked")
    @Redirect(
            method = "slotChangedCraftingGrid",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/crafting/RecipeManager;getRecipeFor(Lnet/minecraft/world/item/crafting/RecipeType;Lnet/minecraft/world/item/crafting/RecipeInput;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/crafting/RecipeHolder;)Ljava/util/Optional;"))
    private static Optional<RecipeHolder<CraftingRecipe>> lanterne$raccourci(
            RecipeManager recettes, RecipeType<CraftingRecipe> type, RecipeInput entree, Level niveau,
            RecipeHolder<CraftingRecipe> indiceLivre,
            AbstractContainerMenu menu, ServerLevel serverLevel, Player joueur,
            CraftingContainer grille, ResultContainer resultat, RecipeHolder<CraftingRecipe> place) {
        // Appel brut (types effacés) et non générique. Deux contraintes incompatibles sinon :
        // Mixin exige "entree" déclaré EXACTEMENT RecipeInput au point d'accroche (vérifié en
        // pratique : un @Redirect refuse un type plus précis, même assignable) ; mais dès qu'UN
        // SEUL des quatre arguments reste parametré (RecipeType<CraftingRecipe> ou
        // RecipeHolder<CraftingRecipe>), javac en déduit T#=CraftingRecipe puis, par la borne
        // structurelle « CraftingRecipe implements Recipe<CraftingInput> » (jamais
        // Recipe<RecipeInput>), exige I#=CraftingInput — ce que "entree" (RecipeInput) ne satisfait
        // plus. Les QUATRE arguments doivent donc être bruts ensemble pour que l'appel s'efface
        // entièrement ; c'est exactement ce que fait déjà FastSuite dans AuxRecipeManager pour la
        // même raison.
        RecipeType rawType = type;
        RecipeHolder rawIndiceLivre = indiceLivre;
        if (!Settings.raccourci()) {
            return recettes.getRecipeFor(rawType, entree, niveau, rawIndiceLivre);
        }
        RecipeHolder<CraftingRecipe> essai = indiceLivre != null ? indiceLivre : Raccourci.indice(menu);
        RecipeHolder rawEssai = essai;
        Optional<RecipeHolder<CraftingRecipe>> trouve =
                recettes.getRecipeFor(rawType, entree, niveau, rawEssai);
        Raccourci.retiens(menu, essai, trouve.orElse(null));
        return trouve;
    }
}
