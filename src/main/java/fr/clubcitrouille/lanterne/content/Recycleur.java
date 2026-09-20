package fr.clubcitrouille.lanterne.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.jspecify.annotations.Nullable;

import fr.clubcitrouille.lanterne.core.Config;

/**
 * Le Recycleur : rend une fraction des matériaux d'un équipement usé, proportionnelle à ce qu'il
 * lui reste de durabilité — plutôt que de le voir finir à la poubelle ou dans un feu.
 *
 * <h2>Pourquoi ce n'est pas une porte vers la duplication</h2>
 *
 * <p>La règle qui rend ce bloc sûr tient en une phrase : <b>il ne rend jamais plus que ce qui reste
 * de valeur dans l'objet.</b> {@link #fractionDurabilite} vaut 1 quand l'objet sort tout juste de
 * l'enclume, et décroît linéairement jusqu'à 0 quand il est sur le point de casser. Fabriquer un
 * objet neuf puis le recycler dans la seconde rend donc <em>au mieux</em> ce qui a été dépensé pour
 * le fabriquer — jamais plus — et l'arrondi vers le bas de {@link #calculer} (un {@code
 * Math.floor} par matériau, jamais un arrondi au plus proche) retire même la fraction perdue à la
 * conversion. Le seul gain réel est d'éviter la perte <b>totale</b> d'un objet presque cassé que
 * l'on aurait sinon jeté — jamais un profit net.
 *
 * <h2>Retrouver la recette, sans jamais la deviner</h2>
 *
 * <p>Aucune table de correspondance maison : {@link #recetteDe} balaie {@link
 * RecipeManager#getRecipes()} et retient la première {@link ShapedRecipe} — le sous-type de {@code
 * CraftingRecipe} que couvrent les recettes d'outil et d'armure — dont le résultat correspond à
 * l'objet qu'on cherche à recycler. Un objet qu'aucune recette ne fabrique (butin unique, objet de
 * commande) ne trouve donc rien, et {@link #useItemOn} le dit au joueur sans toucher à sa main.
 *
 * <p><b>Le piège évité, trouvé au bytecode et pas deviné :</b> dans ce moteur, {@code
 * ShapedRecipe.assemble(CraftingInput)} ignore totalement l'argument qu'on lui passe — vérifié au
 * {@code javap -c} sur le jar client 26.3 réel, le résultat vient d'un champ {@code
 * ItemStackTemplate} stocké dans la recette elle-même, jamais recalculé depuis la grille. {@code
 * CraftingInput.EMPTY} est donc un argument légitime, pas un raccourci fragile : demander le
 * résultat d'une recette n'exige pas de reconstituer sa grille de fabrication.
 *
 * <h2>Le matériau représentatif d'un ingrédient</h2>
 *
 * <p>{@link ShapedRecipe#getIngredients()} rend une case par cellule de la grille — vide pour les
 * cases blanches du patron. Pour chaque case garnie, {@link #materiauxDeLaRecette} retient le
 * <b>premier</b> item accepté par son {@link Ingredient} (n'importe quelle planche pour un
 * ingrédient en balise, par exemple) et compte combien de cases en réclament ce même item. C'est
 * délibérément approximatif — le Recycleur rend « un lingot de fer », jamais « le lingot de fer
 * précis qui a servi » — et c'est sans conséquence : deux lingots de fer sont interchangeables.
 *
 * <h2>Ce qu'un clic fait, et ce qu'il refuse</h2>
 *
 * <ul>
 *   <li>Objet sans durabilité ({@link ItemStack#isDamageableItem()} faux) : refusé. Rendre la
 *       recette entière d'un objet qui ne s'use pas ne serait pas « recycler », ce serait fabriquer
 *       gratuitement.</li>
 *   <li>Objet sans recette connue : refusé, et le Recycleur le dit.</li>
 *   <li>Tous les matériaux arrondis à zéro (objet au bord de la casse) : refusé plutôt que de
 *       consommer l'objet contre rien.</li>
 *   <li>Cas normal : une seule unité consommée — jamais toute une pile, mais un objet endommageable
 *       ne s'empile de toute façon jamais au-delà de un — et les matériaux récupérés vont dans
 *       l'inventaire du joueur, ou tombent au sol s'il est plein, comme {@link
 *       fr.clubcitrouille.lanterne.content.disc.BurnerEntity#eject} le fait déjà pour le graveur de
 *       vinyle.</li>
 * </ul>
 *
 * <p>Le bloc reste toujours enregistré, y compris quand {@code recycleur_actif} vaut faux dans la
 * configuration — même règle que l'Ancre et la Lanterne de Veille (voir {@link Contents}) : un
 * réglage ne doit jamais transformer en air ce qui a été construit.
 */
public class Recycleur extends Block {
    public Recycleur(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useItemOn(ItemStack held, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (held.isEmpty()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel server)) {
            return InteractionResult.PASS;
        }
        if (!Config.RECYCLEUR_ACTIF.get()) {
            tell(player, "Le Recycleur est désactivé sur ce serveur.");
            return InteractionResult.SUCCESS_SERVER;
        }

        Verdict verdict = calculer(server.recipeAccess(), held);
        if (!verdict.estAcceptee()) {
            tell(player, verdict.refus());
            return InteractionResult.SUCCESS_SERVER;
        }

        String nomObjet = held.getHoverName().getString();
        int pourcentage = (int) Math.round(fractionDurabilite(held) * 100.0);
        held.shrink(1);

        for (ItemStack materiau : verdict.rendu()) {
            if (!player.getInventory().add(materiau)) {
                player.drop(materiau, false, Prediction.SERVER_ONLY);
            }
        }
        level.playSound(null, pos, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 0.8F, 1.0F);
        tell(player, nomObjet + " (" + pourcentage + "% de durabilité) → " + decrire(verdict.rendu()));
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * Le calcul pur : ni consommation ni don, seulement ce que rendrait le recyclage de {@code
     * stack}. Exposé séparément de {@link #useItemOn} pour que {@code RecycleurSelfCheck} puisse le
     * vérifier sans passer par un vrai clic sur un vrai bloc.
     */
    static Verdict calculer(RecipeManager recettes, ItemStack stack) {
        if (!stack.isDamageableItem()) {
            return Verdict.refuse("Cet objet n'a pas de durabilité : rien à récupérer proportionnellement.");
        }
        Optional<ShapedRecipe> recette = recetteDe(recettes, stack.getItem());
        if (recette.isEmpty()) {
            return Verdict.refuse("Aucune recette connue ne fabrique cet objet.");
        }

        double fraction = fractionDurabilite(stack);
        List<ItemStack> rendu = new ArrayList<>();
        for (Map.Entry<Item, Integer> materiau : materiauxDeLaRecette(recette.get()).entrySet()) {
            int quantite = (int) Math.floor(materiau.getValue() * fraction);
            if (quantite > 0) {
                rendu.add(new ItemStack(materiau.getKey(), quantite));
            }
        }
        if (rendu.isEmpty()) {
            return Verdict.refuse("Trop usé : il ne reste rien à récupérer.");
        }
        return Verdict.acceptee(rendu);
    }

    private static double fractionDurabilite(ItemStack stack) {
        return 1.0 - (stack.getDamageValue() / (double) stack.getMaxDamage());
    }

    /** La première recette dont le résultat est {@code produit} — voir la javadoc de classe. */
    private static Optional<ShapedRecipe> recetteDe(RecipeManager recettes, Item produit) {
        for (RecipeHolder<?> holder : recettes.getRecipes()) {
            if (holder.value() instanceof ShapedRecipe recette
                    && recette.assemble(CraftingInput.EMPTY).getItem() == produit) {
                return Optional.of(recette);
            }
        }
        return Optional.empty();
    }

    /** Un item représentatif par case garnie de la grille, et le compte de cases qui le réclament. */
    private static Map<Item, Integer> materiauxDeLaRecette(ShapedRecipe recette) {
        Map<Item, Integer> quantites = new LinkedHashMap<>();
        for (Optional<Ingredient> cellule : recette.getIngredients()) {
            cellule.flatMap(ingredient -> ingredient.items().findFirst())
                    .ifPresent(item -> quantites.merge(item.value(), 1, Integer::sum));
        }
        return quantites;
    }

    private static String decrire(List<ItemStack> materiaux) {
        StringBuilder texte = new StringBuilder();
        for (int i = 0; i < materiaux.size(); i++) {
            if (i > 0) {
                texte.append(", ");
            }
            ItemStack stack = materiaux.get(i);
            texte.append(stack.getCount()).append(" × ").append(stack.getHoverName().getString());
        }
        return texte.toString();
    }

    private static void tell(Player player, String text) {
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    /** Soit un refus motivé, soit ce qui serait rendu — jamais les deux. */
    record Verdict(@Nullable String refus, List<ItemStack> rendu) {
        static Verdict refuse(String raison) {
            return new Verdict(raison, List.of());
        }

        static Verdict acceptee(List<ItemStack> rendu) {
            return new Verdict(null, rendu);
        }

        boolean estAcceptee() {
            return this.refus == null;
        }
    }
}
