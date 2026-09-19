package fr.clubcitrouille.lanterne.core;

import java.util.WeakHashMap;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Le raccourci de recette : essayer d'abord ce qui a déjà matché, avant de tout rebalayer.
 *
 * <h2>Ce que FastSuite fait vraiment, et pourquoi ce n'est pas repris</h2>
 *
 * <p>FastSuite (mod tiers, étudié par {@code javap} sur son vrai jar — jamais chargé, jamais
 * décompilé en bloc, seulement désassemblé classe par classe) ne construit PAS un index par
 * ingrédient, contrairement à ce qu'on pourrait supposer. Sa technique réelle, lue dans
 * {@code AuxRecipeManager}/{@code CachedRecipeList} : au-delà de cent recettes pour un même
 * {@code RecipeType} ({@code FastSuite.MIN_SIZE_REQUIRED_FOR_THREADING}), il partage une fois par
 * rechargement les recettes « sûres » (vanilla pur, thread-safe) des recettes « pas sûres »
 * (recettes de mods, potentiellement mutantes), puis fait matcher le lot sûr <b>en parallèle sur
 * un bassin de fils</b> ({@code StreamUtils}, verrouillage anti-mutation, délai de sécurité).
 *
 * <p><b>Cette technique n'est pas reprise ici, et ce n'est pas un oubli.</b> Ce serveur cible une
 * machine à un seul cœur (voir {@code notes/serveur-un-coeur.md}) : répartir un calcul sur un
 * bassin de fils n'a rien à répartir sur une machine qui n'a qu'un cœur pour le tick, et
 * l'ordonnancement d'un bassin de fils a lui-même un coût. Paralléliser le matching de recettes
 * sur CETTE machine ne ferait, au mieux, que déplacer le travail d'un thread à l'autre du même
 * cœur.
 *
 * <h2>Ce que ce moteur a déjà — vérifié, pas supposé</h2>
 *
 * <p>Avant d'écrire une ligne, trois choses ont été vérifiées par {@code javap} sur le vrai jar
 * patché de ce dépôt :
 *
 * <ol>
 *   <li>{@code RecipeMap} (le conteneur interne de {@code RecipeManager}) indexe déjà les recettes
 *       PAR TYPE, dans une {@code Multimap}. Un changement de grille à la table à craft ne rebalaie
 *       donc jamais les recettes de four ou d'enchantement — seulement celles de
 *       {@code RecipeType.CRAFTING}. Ce n'est pas naïf.</li>
 *   <li>{@code RecipeManager.getRecipeFor} porte, nativement, une signature à QUATRE arguments
 *       avec un {@link RecipeHolder} d'indice : si l'indice n'est pas nul et que
 *       {@code indice.value().matches(entrée, niveau)} est vrai, il est rendu SANS toucher au
 *       reste des recettes du type. Ce mécanisme existe déjà dans vanilla — ce module ne
 *       l'invente pas, il l'ALIMENTE.</li>
 *   <li>{@code AbstractFurnaceBlockEntity} porte déjà un champ {@code quickCheck}, du type officiel
 *       {@code RecipeManager.CachedCheck} — exactement le même principe de dernière-recette, pour
 *       le four. Et {@link Sleep} va plus loin encore pour ce cas précis : il saute carrément le
 *       tick tant que l'issue est connue d'avance. Le four n'avait donc PAS besoin de ce module.</li>
 * </ol>
 *
 * <h2>Le vrai trou trouvé — vérifié par bytecode, pas supposé</h2>
 *
 * <p>Le hint existe, mais personne ne le nourrit sur le chemin normal. Lu dans le bytecode réel de
 * {@code CraftingMenu.lambda$slotsChanged$0} et {@code InventoryMenu.lambda$slotsChanged$0} : les
 * DEUX appellent {@code CraftingMenu.slotChangedCraftingGrid(...)} avec {@code aconst_null} en
 * dernier argument — c'est-à-dire qu'à CHAQUE modification de la grille de craft, table comme
 * inventaire du joueur, l'indice transmis à {@code RecipeManager.getRecipeFor} est TOUJOURS nul.
 * Le raccourci que vanilla sait pourtant emprunter n'est jamais offert : chaque clic rebalaie donc
 * linéairement toutes les recettes de {@code RecipeType.CRAFTING} jusqu'à trouver — ou épuiser —
 * la liste. Sur ce serveur, ce sont 1266 recettes vanilla plus 11 recettes Lanterne, soit 1277
 * recettes de ce seul type (compté dans les fichiers réels du jar patché et des ressources de ce
 * dépôt, pas un chiffre inventé).
 *
 * <h2>Ce que ce module fait</h2>
 *
 * <p>Il retient, PAR MENU (table à craft ou grille 2x2 du joueur — {@link AbstractContainerMenu}
 * comme clé, dans une table à références faibles qui s'auto-nettoie quand le menu ferme), la
 * dernière recette qui a matché. Au clic suivant, ce souvenir devient l'indice transmis à
 * {@code RecipeManager.getRecipeFor} — exactement le canal que vanilla vérifie en premier. Le cas
 * fréquent — fabriquer plusieurs fois la même chose d'affilée, ou retirer/reposer un ingrédient qui
 * ne change pas la recette en cours — passe alors d'un balayage linéaire à un seul
 * {@code matches()}.
 *
 * <h2>Pourquoi c'est sûr par construction</h2>
 *
 * <p>Rien de neuf n'est inventé côté vérification : {@code RecipeManager.getRecipeFor} REVÉRIFIE
 * toujours {@code indice.matches(entrée, niveau)} avant de faire confiance à l'indice — c'est du
 * code vanilla, pas de ce module. Un indice périmé ou faux ne peut donc jamais rendre une mauvaise
 * recette : au pire, il coûte UN {@code matches()} de plus avant de retomber sur le balayage
 * complet, exactement le prix qu'aurait payé un indice nul. Ce module ne peut donc jamais être plus
 * lent que l'absence totale d'indice, seulement égal ou meilleur.
 */
public final class Raccourci {
    private static final WeakHashMap<AbstractContainerMenu, RecipeHolder<CraftingRecipe>> DERNIERE =
            new WeakHashMap<>();

    private static long essais;
    private static long reussites;

    private Raccourci() {}

    /** Le dernier indice connu pour ce menu, ou {@code null} si aucun n'est encore mémorisé. */
    public static RecipeHolder<CraftingRecipe> indice(AbstractContainerMenu menu) {
        return DERNIERE.get(menu);
    }

    /**
     * Enregistre le résultat d'un essai, et tient les compteurs du rapport.
     *
     * @param menu   le menu dont la grille vient de changer
     * @param essai  l'indice réellement transmis à {@code getRecipeFor} (nul si aucun n'était
     *               disponible)
     * @param trouve la recette effectivement trouvée, ou {@code null} si aucune ne correspond
     */
    public static void retiens(AbstractContainerMenu menu, RecipeHolder<CraftingRecipe> essai,
            RecipeHolder<CraftingRecipe> trouve) {
        essais++;
        if (essai != null && essai == trouve) {
            reussites++;
        }
        if (trouve == null) {
            DERNIERE.remove(menu);
        } else {
            DERNIERE.put(menu, trouve);
        }
    }

    /** Part des essais où l'indice mémorisé a directement fourni la bonne réponse. */
    public static double partReussie() {
        return essais == 0L ? 0d : (double) reussites / essais;
    }

    public static long essais() {
        return essais;
    }

    public static long reussites() {
        return reussites;
    }

    public static void resetCounters() {
        essais = 0L;
        reussites = 0L;
    }

    /** Vide la table — utilisé par les bancs pour repartir d'un état propre. */
    public static void reset() {
        DERNIERE.clear();
        resetCounters();
    }
}
