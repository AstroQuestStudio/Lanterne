package fr.clubcitrouille.lanterne.compat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import net.neoforged.fml.ModList;
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforgespi.language.IModFileInfo;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Nos recettes relues depuis notre propre jar, côté client, quand personne d'autre ne les fournit.
 *
 * <h2>Le défaut que ce fichier corrige</h2>
 *
 * <blockquote>« Sur JEI, clic gauche est censé afficher le craft — et il n'affiche rien pour tes
 * objets. »</blockquote>
 *
 * <p>L'audit des fichiers avait répondu « tout est en ordre », et il avait raison : les onze
 * recettes sont au bon endroit, au bon format, avec des ingrédients qui existent et des résultats
 * enregistrés. Le serveur les charge — {@code /lanterne recettes} le confirme depuis sa console.
 * Le défaut n'est donc ni dans les fichiers, ni dans le chargement : il est dans le <b>trajet</b>
 * entre le serveur qui les connaît et l'afficheur qui devrait les montrer.
 *
 * <p>Ce trajet a été coupé par Minecraft lui-même. Le serveur n'envoie plus au client les recettes
 * complètes : il n'envoie que de quoi peindre le livre de recettes — les {@code RecipeDisplay}. Un
 * afficheur qui veut montrer un vrai plan de craft doit donc obtenir les recettes autrement, et JEI
 * le fait en installant sa propre moitié <b>sur le serveur</b>, qui les lui expédie.
 *
 * <p>Quand cette moitié manque — c'est le cas du serveur du Club, où JEI n'est pas installé —, JEI
 * se rabat sur un chargeur de secours, {@code mezz.jei.common.recipes.VanillaClientRecipeLoader}.
 * Et ce chargeur ne lit qu'<b>un seul pack</b> :
 *
 * <pre>
 * new MultiPackResourceManager(PackType.SERVER_DATA,
 *         List.of(ServerPacksSource.createVanillaPackSource()));
 * </pre>
 *
 * <p>Le pack de vanilla, et rien d'autre. Aucun jar de mod n'y figure. La ligne du journal ne dit
 * pas autre chose, et le compte est exact au fichier près :
 *
 * <pre>
 * [21:01:40] [Render thread/INFO]: Loaded 1585 vanilla recipes from client resources.
 * </pre>
 *
 * <p>Mille cinq cent quatre-vingt-cinq, soit précisément le contenu de
 * {@code data/minecraft/recipe} dans le jar du jeu. Nos onze ne sont pas mal formées : elles ne
 * sont <b>jamais lues</b>.
 *
 * <h2>Pourquoi relire notre propre jar, et pourquoi c'est licite ici</h2>
 *
 * <p>Le client qui se connecte a forcément Lanterne installé — c'est un mod commun, le serveur le
 * refuserait sinon. Le fichier {@code data/lanterne/recipe/*.json} est donc <b>déjà sur le disque
 * du joueur</b>, à quelques centimètres de l'afficheur qui se plaint de ne rien avoir. Il n'y a
 * rien à télécharger, rien à synchroniser : il y a un dossier à ouvrir.
 *
 * <p>C'est exactement ce que fait le chargeur de secours de JEI, avec le pack de vanilla. On lui
 * rend le même service avec le nôtre.
 *
 * <h2>Ce que cette relecture peut dire de faux, et pourquoi on la borne</h2>
 *
 * <p>Il faut être honnête sur la limite, parce qu'elle est réelle : ce que nous lisons est la
 * recette <b>telle que notre jar la porte</b>, pas telle que le serveur l'applique. Un
 * administrateur qui retire ou remplace une de nos recettes par un pack de données verra l'ancienne
 * dans JEI et la nouvelle dans sa table. C'est précisément le mensonge contre lequel JEI met en
 * garde quand il n'est pas sur le serveur.
 *
 * <p>D'où {@link #necessaire()}, et d'où sa sévérité : dès que quelqu'un de mieux placé peut
 * répondre, on se tait. Un serveur local sait ses recettes ; un serveur qui a JEI les envoie. Dans
 * ces deux cas ce fichier ne fait rien du tout, et l'afficheur montre la vérité du serveur.
 *
 * <p>La correction sans réserve, celle qui ne peut rien dire de faux, reste d'installer JEI sur le
 * serveur. Ce module est le filet en dessous, pour les hébergements où l'on ne peut pas.
 *
 * <h2>Pourquoi aucune classe de JEI n'apparaît ici</h2>
 *
 * <p>Ce fichier ne connaît que Minecraft et NeoForge. Il se compile, et s'embarque, que JEI soit
 * présent ou non — un serveur sans JEI le charge sans rien remarquer. Tout ce qui nomme
 * {@code mezz.jei} est enfermé dans la greffe, qui n'est chargée que si JEI la découvre.
 *
 * <p>La seule exception est {@link #jeiSynchronise()}, qui interroge une classe <b>interne</b> de
 * JEI par réflexion. Le choix est délibéré : cette classe ne fait pas partie de l'API publique, il
 * n'y a donc rien contre quoi compiler, et un {@code catch} qui répond « non » est un sens d'erreur
 * sans danger — au pire on propose des recettes que JEI avait déjà, au mieux on se tait.
 */
public final class RecettesEmbarquees {
    /**
     * Le dossier des recettes, tel qu'il s'appelle en 26.2.
     *
     * <p>Au singulier : {@code recipe}, pas {@code recipes}. Le pluriel a disparu en 1.21, et c'est
     * une confusion assez coûteuse pour mériter d'être écrite une fois — un dossier mal nommé ne
     * produit aucune erreur, juste un silence. Vérifié dans le jar du jeu : le chemin vanilla est
     * bien {@code data/minecraft/recipe/}.
     */
    private static final String DOSSIER = "data/" + Lanterne.ID + "/recipe";

    /** L'extension des fichiers qu'on accepte de lire. */
    private static final String EXTENSION = ".json";

    private RecettesEmbarquees() {}

    /**
     * Faut-il fournir nos recettes nous-mêmes ?
     *
     * <p>Deux personnes peuvent répondre avant nous, et toutes deux répondent mieux :
     *
     * <ol>
     *   <li>un <b>serveur dans cette machine</b> — le solo, ou un monde ouvert au réseau local —
     *       tient le vrai gestionnaire de recettes, packs de données compris ;</li>
     *   <li>un <b>serveur distant qui a JEI</b> lui expédie les siennes, et ce sont celles-là qui
     *       font foi.</li>
     * </ol>
     *
     * <p>On ne parle donc que dans le troisième cas : serveur distant, sans JEI. C'est le cas du
     * Club, et c'est le seul où nous en savons plus que l'afficheur.
     */
    public static boolean necessaire() {
        // Un serveur dans cette machine : le gestionnaire de recettes est à portée de JEI, qui le
        // lit directement. Rien à ajouter, et surtout rien à dupliquer.
        if (ServerLifecycleHooks.getCurrentServer() != null) {
            return false;
        }
        return !jeiSynchronise();
    }

    /**
     * Les recettes de Lanterne, relues dans notre jar et prêtes pour un afficheur.
     *
     * <p>Le décodage passe par {@link Recipe#CODEC} et non par une lecture à la main du JSON : c'est
     * le même codec que le serveur, donc la même tolérance et les mêmes refus. Une recette que le
     * serveur accepterait est acceptée ici ; une qu'il refuserait est refusée ici aussi, et le
     * journal le dit au lieu de la faire disparaître en silence.
     *
     * <p>Le {@code registres} n'est pas un ornement : les ingrédients par étiquette —
     * {@code "#minecraft:shulker_boxes"} dans {@code shulker_box_undye} — ne se résolvent qu'avec
     * un annuaire. Celui du client convient : les étiquettes sont synchronisées à la connexion,
     * avant que l'afficheur ne démarre.
     *
     * <p>Seules les recettes d'<b>établi</b> sortent d'ici. Lanterne n'enregistre aucun type de
     * recette à lui — pas un {@code RecipeSerializer}, pas un {@code RecipeType} — et ses stations
     * (l'atelier, la boutique, le projecteur, le cœur de repère) ne cuisent rien : elles n'ont donc
     * ni catégorie ni plan à montrer. Ce qui ne rentre pas dans une table à craft est écarté plutôt
     * que forcé dans la mauvaise catégorie.
     *
     * @param registres l'annuaire qui résout les objets et les étiquettes — côté client,
     *                  {@code level.registryAccess()}
     * @return les recettes lues, ou une liste vide si le jar est illisible : ce module ne lève rien.
     */
    public static List<RecipeHolder<CraftingRecipe>> lire(HolderLookup.Provider registres) {
        JarContents jar = jar();
        if (jar == null) {
            return List.of();
        }
        List<String> fichiers = fichiers(jar);
        if (fichiers.isEmpty()) {
            Lanterne.LOG.warn("[JEI] Aucun fichier dans {} : notre propre jar ne porte pas ses "
                    + "recettes, ou la lecture a été refusée.", DOSSIER);
            return List.of();
        }

        RegistryOps<JsonElement> ops = registres.createSerializationContext(JsonOps.INSTANCE);
        List<RecipeHolder<CraftingRecipe>> lues = new ArrayList<>(fichiers.size());
        for (String fichier : fichiers) {
            RecipeHolder<CraftingRecipe> recette = decoder(jar, fichier, ops);
            if (recette != null) {
                lues.add(recette);
            }
        }
        return lues;
    }

    /**
     * Le contenu de notre propre jar.
     *
     * <p>{@link ModList#getModFileById(String)} plutôt qu'un chemin construit : le jar peut
     * s'appeler n'importe comment, vivre dans un dossier {@code mods} déplacé, ou — en
     * développement — ne pas être un jar du tout mais le dossier des ressources compilées.
     * L'identifiant de mod, lui, ne change pas.
     */
    private static JarContents jar() {
        try {
            IModFileInfo nous = ModList.get().getModFileById(Lanterne.ID);
            return nous == null ? null : nous.getFile().getContents();
        } catch (Throwable troptot) {
            // ModList lève tant que la liste n'est pas peuplée. Répondre « rien » ne casse que
            // l'affichage des recettes, jamais le démarrage.
            return null;
        }
    }

    /**
     * Les chemins des fichiers de recettes, relevés puis relus — et jamais les deux à la fois.
     *
     * <p>{@code visitContent} ne prête pas une ressource neuve à chaque appel : l'implémentation de
     * FML pour un jar réutilise le <b>même objet</b> et lui réaffecte son entrée à chaque visite.
     * Lire pendant la visite marcherait, garder la ressource pour plus tard donnerait n'importe
     * quoi. On ne garde donc que des chaînes, et on rouvre ensuite par {@code openFile} — ce qui a
     * l'avantage de rendre la lecture interruptible sans laisser un flux ouvert dans une lambda qui
     * n'a pas le droit de lever.
     */
    private static List<String> fichiers(JarContents jar) {
        List<String> trouves = new ArrayList<>();
        try {
            jar.visitContent(DOSSIER, (chemin, ignoree) -> {
                if (chemin.endsWith(EXTENSION)) {
                    trouves.add(chemin);
                }
            });
        } catch (Throwable illisible) {
            Lanterne.LOG.warn("[JEI] Lecture de {} impossible : {}", DOSSIER, illisible.toString());
        }
        return trouves;
    }

    /** Un fichier, décodé — ou {@code null}, avec la raison dans le journal. */
    private static RecipeHolder<CraftingRecipe> decoder(JarContents jar, String chemin,
            RegistryOps<JsonElement> ops) {
        try (InputStream flux = jar.openFile(chemin)) {
            if (flux == null) {
                return null;
            }
            JsonElement arbre = JsonParser.parseReader(
                    new InputStreamReader(flux, StandardCharsets.UTF_8));
            Recipe<?> recette = Recipe.DIRECT_CODEC.parse(ops, arbre).getPartialOrThrow(
                    message -> new IllegalStateException(message));
            if (!(recette instanceof CraftingRecipe etabli)) {
                // Pas une faute : simplement une recette qui ne se montre pas dans une table à
                // craft. On la laisse à qui saura la présenter.
                return null;
            }
            return new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, identifiant(chemin)),
                    etabli);
        } catch (Throwable refusee) {
            // Un fichier fautif ne doit pas emporter les dix autres. On nomme le coupable : c'est
            // la seule chose qui distingue « JEI ne voit rien » de « cette recette-là est cassée ».
            Lanterne.LOG.warn("[JEI] Recette {} refusée : {}", chemin, refusee.toString());
            return null;
        }
    }

    /**
     * {@code data/lanterne/recipe/coeur_de_repere.json} → {@code lanterne:coeur_de_repere}.
     *
     * <p>Même règle que {@code FileToIdConverter} : on retire le préfixe du dossier et l'extension,
     * et ce qui reste — sous-dossiers compris — est le chemin de l'identifiant.
     */
    private static Identifier identifiant(String chemin) {
        String nu = chemin.substring(DOSSIER.length() + 1, chemin.length() - EXTENSION.length());
        return Identifier.fromNamespaceAndPath(Lanterne.ID, nu);
    }

    /**
     * JEI a-t-il déjà reçu les recettes du serveur ?
     *
     * <p>La question se pose à JEI parce que lui seul la sait : c'est sa propre moitié serveur qui
     * les lui envoie, sur son propre canal, et rien de vanilla n'en garde trace côté client.
     *
     * <p>{@code mezz.jei.common.Internal} n'est pas de l'API publique et ne s'embarque pas dans
     * l'artefact {@code -api} : il n'y a donc rien contre quoi compiler, et la réflexion n'est pas
     * un contournement de paresse mais le seul accès qui existe. Le nom peut disparaître à une mise
     * à jour de JEI ; c'est pourquoi l'échec est silencieux et penche du côté prudent — si l'on ne
     * sait pas, on suppose que JEI n'a rien, et le pire qui arrive est un doublon à l'écran plutôt
     * qu'une recette manquante.
     */
    private static boolean jeiSynchronise() {
        try {
            Class<?> interne = Class.forName("mezz.jei.common.Internal");
            Object reponse = interne.getMethod("hasClientSyncedRecipes").invoke(null);
            return reponse instanceof Boolean oui && oui.booleanValue();
        } catch (Throwable inconnu) {
            return false;
        }
    }
}
