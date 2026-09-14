package fr.clubcitrouille.lanterne.content.shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le titrage : le catalogue entier, déduit des recettes du jeu.
 *
 * <h2>Pourquoi ne pas écrire les prix</h2>
 *
 * <p>Un catalogue vanilla complet, c'est mille trois cent cinquante articles. Les tarifer à la main
 * serait long, impossible à maintenir d'une version à l'autre, et surtout <b>faux</b> : chaque prix
 * posé au jugé est une boucle d'arbitrage en puissance. Il suffit d'<em>un</em> objet dont le rachat
 * dépasse le coût de ses ingrédients pour qu'un joueur fabrique de l'argent sans fin, et l'erreur
 * reste invisible jusqu'au jour où quelqu'un la trouve.
 *
 * <p>Le jeu, lui, sait déjà ce qui se fabrique avec quoi. On lui demande.
 *
 * <h2>La formule</h2>
 *
 * <pre>    prix(sortie) = ceil( somme(prix des ingrédients) × F / quantité produite )</pre>
 *
 * <p>{@code F} est le <b>facteur de valeur ajoutée</b>, 1,20 par défaut. Il ne représente pas une
 * marge de commerçant : il représente ce que le modèle ne sait pas compter — le combustible du four,
 * l'usure de l'outil, le temps. Le prendre strictement supérieur à 1 a une conséquence qui dépasse
 * l'équilibrage, et elle est la clef de tout ce fichier : <b>un cycle de recettes ne peut pas faire
 * baisser un prix</b>. Fabriquer A depuis B puis B depuis A rend A plus cher qu'au départ, donc le
 * minimum est toujours atteint par un chemin sans cycle, donc la relaxation converge.
 *
 * <h2>La relaxation, et pourquoi pas une récursion</h2>
 *
 * <p>La méthode naturelle serait une descente récursive mémoïsée. Elle demande de détecter les
 * cycles, de décider quoi mettre en mémoire quand on en traverse un, et se trompe discrètement si on
 * s'y prend mal. On fait autrement, et c'est plus simple :
 *
 * <ol>
 *   <li>on part des matières premières de {@link Seam}, les seules dont le prix soit écrit ;</li>
 *   <li>on parcourt toutes les recettes ; celle dont <b>tous</b> les ingrédients ont déjà un prix
 *       donne un prix à sa sortie, si c'est moins cher que ce qu'on avait ;</li>
 *   <li>on recommence tant que quelque chose a changé.</li>
 * </ol>
 *
 * <p>Un objet dont le prix dépendrait de lui-même n'obtient jamais de prix par cette voie : il ne
 * peut pas, puisqu'on n'attribue un prix qu'une fois tous les ingrédients connus. <b>Les cycles sont
 * donc traités par construction</b>, sans une ligne de code pour les détecter — c'est exactement
 * pour cela que la méthode a été choisie. Les objets pris dans un cycle pur — la pierre et ses
 * pavés, le charbon et son bloc, les gabarits de forge qui se dupliquent eux-mêmes — sont ceux que
 * {@link Seam} nomme, et c'est la seule raison pour laquelle il les nomme.
 *
 * <p>Sur les 1 466 recettes de vanilla, quatre passes suffisent.
 *
 * <h2>Les trois choix du moins-disant</h2>
 *
 * <p>Partout où le jeu laisse le choix, on prend <b>le moins cher</b>, parce que c'est ce qu'un
 * joueur ferait :
 *
 * <ul>
 *   <li>un ingrédient donné par étiquette ({@code #minecraft:planks}) vaut le moins cher de ses
 *       membres ;</li>
 *   <li>un objet fabricable de plusieurs façons vaut le moins cher de ses chemins ;</li>
 *   <li>un ingrédient qu'aucun prix ne couvre rend la recette inutilisable — et non gratuite.</li>
 * </ul>
 *
 * <h2>Le bénéfice qui ne coûte rien : les mods</h2>
 *
 * <p>Rien dans ce fichier ne parle de vanilla. On lit le gestionnaire de recettes du serveur, tel
 * qu'il est après le chargement des paquets de données. <b>Les objets d'un mod se tarifent donc tout
 * seuls</b> dès que leurs recettes redescendent à des matières premières connues — et un mod qui
 * fabrique ses machines avec du fer et de la redstone en fait partie. Ce qui ne se résout pas est
 * <b>nommé dans le journal</b>, pour qu'un administrateur sache exactement ce qui lui reste à écrire
 * à la main.
 */
public final class Assay {
    /** Ce qu'une génération a produit. */
    public record Tally(int fromSeam, int fromRecipes, int refused, int steps, int passes,
                        List<Identifier> orphans) {
        public int total() {
            return this.fromSeam + this.fromRecipes;
        }
    }

    /** Une boucle d'arbitrage trouvée par l'audit. */
    public record Flaw(Identifier recipe, Identifier item, boolean forward, long cost, long gain) {}

    /** Une recette réduite à ce qui nous intéresse : ce qu'elle coûte, ce qu'elle rend. */
    private record Step(Identifier recipe, List<List<Identifier>> inputs, Identifier output,
                        int count) {}

    private Assay() {}

    // --- La génération ------------------------------------------------------

    /**
     * Reconstruit le catalogue entier et le verse dans {@link Stall}.
     *
     * <p>Appelée au premier démarrage, et sur demande explicite par {@code /boutique generer}. Elle
     * <b>écrase</b> ce que l'étal contenait : c'est voulu, c'est annoncé, et c'est la seule façon de
     * reprendre à zéro après un changement de version ou l'ajout d'un mod.
     */
    public static Tally brew(MinecraftServer server) {
        long started = System.nanoTime();
        ContextMap context = SlotDisplayContext.fromLevel(server.overworld());
        List<Step> steps = collect(server, context);

        // 1. La veine : les seuls prix écrits à la main.
        Map<Identifier, Long> price = new HashMap<>(2048);
        int fromSeam = 0;
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            if (Seam.banned(id)) {
                continue;
            }
            Long floor = Seam.base(id);
            if (floor != null) {
                price.put(id, clamp(floor));
                fromSeam++;
            }
        }

        // 2. La relaxation. Le plafond de passes n'est pas une sécurité contre les cycles — ils ne
        // peuvent pas boucler ici, voir l'en-tête — mais contre un jeu de données pathologique
        // apporté par un mod : mieux vaut un catalogue incomplet qu'un serveur qui ne démarre pas.
        int passes = 0;
        boolean moved = true;
        while (moved && passes < 64) {
            passes++;
            moved = false;
            for (Step step : steps) {
                long total = 0L;
                boolean complete = true;
                for (List<Identifier> slot : step.inputs()) {
                    long best = Long.MAX_VALUE;
                    for (Identifier candidate : slot) {
                        Long known = price.get(candidate);
                        if (known != null && known < best) {
                            best = known;
                        }
                    }
                    if (best == Long.MAX_VALUE) {
                        complete = false;
                        break;
                    }
                    total += best;
                }
                if (!complete) {
                    continue;
                }
                long made = clamp(ceilDiv(total * Tariff.genFactor(),
                        100L * Math.max(1, step.count())));
                Long current = price.get(step.output());
                if (current == null || made < current) {
                    price.put(step.output(), made);
                    moved = true;
                }
            }
        }

        // 3. L'étal.
        Stall.clear();
        int refused = 0;
        for (Map.Entry<Identifier, Long> entry : price.entrySet()) {
            Identifier id = entry.getKey();
            long buy = entry.getValue();
            String refusal = Stall.put(id, buy, buyback(id, buy), Seam.aisle(id));
            if (refusal != null) {
                refused++;
                Lanterne.LOG.warn("[BOUTIQUE] {} écarté : {}", id, refusal);
            }
        }

        // 4. Ce qui reste sans prix, nommé — c'est la liste de courses de l'administrateur.
        List<Identifier> orphans = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            if (!Seam.banned(id) && !price.containsKey(id)
                    && BuiltInRegistries.ITEM.getValue(id) != Items.AIR) {
                orphans.add(id);
            }
        }
        Collections.sort(orphans, (left, right) -> left.toString().compareTo(right.toString()));

        int fromRecipes = price.size() - fromSeam;
        long millis = (System.nanoTime() - started) / 1_000_000L;
        Lanterne.LOG.info("[BOUTIQUE] catalogue engendré en {} ms : {} article(s) — {} de la table "
                        + "de base, {} déduits de {} recette(s) en {} passe(s).",
                millis, Stall.size(), fromSeam, fromRecipes, steps.size(), passes);
        if (!orphans.isEmpty()) {
            Lanterne.LOG.warn("[BOUTIQUE] {} objet(s) sans prix : aucune recette ne les relie à une "
                    + "matière première connue. Ajoute-les à la main dans {} si tu les veux au "
                    + "catalogue.", orphans.size(), Stall.file());
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < Math.min(orphans.size(), 60); i++) {
                line.append(i > 0 ? ", " : "").append(orphans.get(i));
            }
            if (orphans.size() > 60) {
                line.append(", … (").append(orphans.size() - 60).append(" de plus)");
            }
            Lanterne.LOG.warn("[BOUTIQUE]   {}", line);
        }
        return new Tally(fromSeam, fromRecipes, refused, steps.size(), passes, orphans);
    }

    /**
     * Le prix de rachat d'un article engendré.
     *
     * <p>Une part fixe du prix d'achat — 25 % par défaut —, sauf pour ce que {@link Seam} interdit de
     * racheter. Le résultat est <b>rabattu sous la marge minimale du serveur</b> : sans cela, un
     * administrateur qui pousserait {@code marge_min_pourcent} à 80 verrait mille articles refusés à
     * l'enregistrement, et un catalogue vide au démarrage.
     */
    private static long buyback(Identifier id, long buy) {
        if (Seam.noBuyback(id) || !Tariff.buyback()) {
            return 0L;
        }
        long share = buy * Tariff.genSell() / 100L;
        long allowed = buy - Math.max(1L, buy * Tariff.margin() / 100L);
        return Math.max(0L, Math.min(share, allowed));
    }

    // --- La lecture des recettes ---------------------------------------------

    /**
     * Réduit chaque recette du serveur à ses ingrédients et à son résultat.
     *
     * <p>On passe par {@code Recipe.display()} plutôt que par les types concrets. C'est la
     * représentation que le jeu construit lui-même pour <em>montrer</em> une recette, elle est
     * uniforme pour l'établi, le four, le tailleur de pierre et le forgeron, et surtout elle résout
     * les étiquettes en objets réels. Écrire un cas par classe de recette aurait demandé six branches
     * et en aurait oublié une à la prochaine version.
     *
     * <p>Les recettes <b>spéciales</b> sont écartées : feux d'artifice, copie de livre, réparation.
     * Leur résultat dépend de ce qu'on leur donne, elles n'ont pas de prix par nature, et la caisse
     * refuse de toute façon les objets qui en sortent — ils portent des composants.
     */
    private static List<Step> collect(MinecraftServer server, ContextMap context) {
        List<Step> steps = new ArrayList<>(2048);
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            if (recipe.isSpecial()) {
                continue;
            }
            for (RecipeDisplay display : recipe.display()) {
                List<SlotDisplay> slots = inputsOf(display);
                if (slots == null || slots.isEmpty()) {
                    continue;
                }
                ItemStack result = display.result().resolveForFirstStack(context);
                if (result.isEmpty()) {
                    continue;
                }
                Identifier output = BuiltInRegistries.ITEM.getKey(result.getItem());
                if (output == null || Seam.banned(output)) {
                    continue;
                }
                List<List<Identifier>> inputs = new ArrayList<>(slots.size());
                boolean usable = true;
                for (SlotDisplay slot : slots) {
                    List<Identifier> candidates = idsOf(slot, context);
                    if (candidates == null) {
                        // L'emplacement est vide : c'est une case de grille inoccupée, pas un refus.
                        continue;
                    }
                    if (candidates.isEmpty()) {
                        // L'emplacement n'accepte que des objets bannis : la recette est hors jeu.
                        usable = false;
                        break;
                    }
                    inputs.add(candidates);
                }
                if (!usable || inputs.isEmpty()) {
                    continue;
                }
                steps.add(new Step(holder.id().identifier(), inputs, output,
                        Math.max(1, result.getCount())));
            }
        }
        return steps;
    }

    /** Les emplacements d'entrée d'un affichage de recette, ou {@code null} si on ne le connaît pas. */
    private static List<SlotDisplay> inputsOf(RecipeDisplay display) {
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            return shaped.ingredients();
        }
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            return shapeless.ingredients();
        }
        if (display instanceof FurnaceRecipeDisplay furnace) {
            // Le combustible n'est PAS compté. Le compter supposerait de choisir lequel, et
            // l'ignorer va dans le sens sûr : le produit sort un peu moins cher que la réalité,
            // donc le revendre rapporte un peu moins. C'est l'erreur qu'on préfère faire.
            return List.of(furnace.ingredient());
        }
        if (display instanceof StonecutterRecipeDisplay cutter) {
            return List.of(cutter.input());
        }
        if (display instanceof SmithingRecipeDisplay smithing) {
            return List.of(smithing.template(), smithing.base(), smithing.addition());
        }
        return null;
    }

    /**
     * Les objets qu'un emplacement accepte.
     *
     * @return {@code null} si l'emplacement est vide, la liste des candidats non bannis sinon
     */
    private static List<Identifier> idsOf(SlotDisplay slot, ContextMap context) {
        List<ItemStack> stacks = slot.resolveForStacks(context);
        if (stacks.isEmpty()) {
            return null;
        }
        List<Identifier> ids = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            if (item == Items.AIR) {
                continue;
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null && !Seam.banned(id) && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    // --- L'audit --------------------------------------------------------------

    /**
     * Parcourt toutes les recettes et signale toute boucle qui rapporte.
     *
     * <h2>Ce qui est vraiment testé</h2>
     *
     * <p>Pas les prix d'ancrage : <b>les prix aux extrêmes de la dérive</b>. C'est la seule version
     * du test qui vaille, et elle a été payée cher. Avec un rachat à 40 % du prix d'achat — la
     * première valeur retenue — le catalogue d'ancrage ne présentait <em>aucune</em> boucle ; mais
     * deux articles peuvent dériver en sens contraire, et il suffit que les ingrédients soient au
     * plancher pendant que le produit est au plafond pour que 1 290 des 1 445 recettes deviennent
     * rentables. La mesure hors du jeu l'a établi, et c'est ce qui a fixé le rachat à 25 %.
     *
     * <p>La condition à tenir s'écrit en une ligne, et {@link Tariff} la vérifie au chargement :
     *
     * <pre>    rachat × facteur × (1 + amplitude) / (1 − amplitude) &lt; 1</pre>
     *
     * <p>Avec les valeurs par défaut : 0,25 × 1,20 × 3 = 0,90. Le pire rapport réellement mesuré sur
     * les 1 445 recettes de vanilla est 0,944 — il reste donc un peu moins de six pour cent de marge,
     * et l'exploitant y perd à chaque tour.
     *
     * <h2>Pourquoi le sens RETOUR n'est testé que sur les recettes réversibles</h2>
     *
     * <p>« Acheter la sortie, revendre les entrées » suppose qu'on puisse détricoter. On ne défait
     * pas des planches en bûche. Le test ne s'applique donc qu'aux couples pour lesquels <b>le jeu
     * possède réellement la recette inverse</b> — les neuf lingots contre un bloc, le cas classique,
     * en font partie. Appliqué partout, il produisait cinquante-sept fausses alertes qui auraient
     * masqué les vraies.
     */
    public static List<Flaw> audit(MinecraftServer server) {
        ContextMap context = SlotDisplayContext.fromLevel(server.overworld());
        List<Step> steps = collect(server, context);

        // « sources[x] » : tout ce qui sert à fabriquer x. Si « out » y figure, alors on sait
        // refaire x à partir de out, et la boucle retour est réellement praticable.
        Map<Identifier, Set<Identifier>> sources = new HashMap<>();
        for (Step step : steps) {
            Set<Identifier> known = sources.computeIfAbsent(step.output(), key -> new HashSet<>());
            for (List<Identifier> slot : step.inputs()) {
                known.addAll(slot);
            }
        }

        long cap = 100L * Tariff.elasticity();
        List<Flaw> flaws = new ArrayList<>();
        for (Step step : steps) {
            Offer made = Stall.find(step.output());
            if (made == null) {
                continue;
            }
            List<Identifier> chosen = new ArrayList<>(step.inputs().size());
            long cost = 0L;
            boolean priced = true;
            for (List<Identifier> slot : step.inputs()) {
                Identifier best = null;
                long bestPrice = Long.MAX_VALUE;
                for (Identifier candidate : slot) {
                    Offer offer = Stall.find(candidate);
                    if (offer == null) {
                        continue;
                    }
                    // Au plus bas que la dérive puisse descendre : c'est là que l'achat est facile.
                    long floor = Drift.buy(offer, -cap);
                    if (floor < bestPrice) {
                        bestPrice = floor;
                        best = candidate;
                    }
                }
                if (best == null) {
                    priced = false;
                    break;
                }
                chosen.add(best);
                cost += bestPrice;
            }
            if (!priced) {
                continue;
            }
            // Au plus haut que la dérive puisse monter : c'est là que la revente rapporte.
            long gain = (long) step.count() * Drift.sell(made, cap);
            if (gain >= cost) {
                flaws.add(new Flaw(step.recipe(), step.output(), true, cost, gain));
            }

            boolean undoable = !chosen.isEmpty();
            for (Identifier input : chosen) {
                Set<Identifier> known = sources.get(input);
                if (known == null || !known.contains(step.output())) {
                    undoable = false;
                    break;
                }
            }
            if (undoable) {
                long back = 0L;
                for (Identifier input : chosen) {
                    Offer offer = Stall.find(input);
                    back += offer == null ? 0L : Drift.sell(offer, cap);
                }
                long outlay = (long) step.count() * Drift.buy(made, -cap);
                if (back >= outlay) {
                    flaws.add(new Flaw(step.recipe(), step.output(), false, outlay, back));
                }
            }
        }
        return flaws;
    }

    /** Le nombre de recettes que l'audit sait examiner — pour l'annoncer avec son résultat. */
    public static int recipeCount(MinecraftServer server) {
        return collect(server, SlotDisplayContext.fromLevel(server.overworld())).size();
    }

    // --- Arithmétique ---------------------------------------------------------

    /** Division entière arrondie vers le haut. Un article ne doit jamais valoir moins que sa part. */
    private static long ceilDiv(long value, long divisor) {
        return (value + divisor - 1L) / divisor;
    }

    private static long clamp(long cents) {
        return Math.max(1L, Math.min(Coin.PRICE_CEILING, cents));
    }
}
