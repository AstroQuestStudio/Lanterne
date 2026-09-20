package fr.clubcitrouille.lanterne.content;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Vérification automatique du calcul du Recycleur, sans humain devant l'écran — même geste que
 * {@code content.trieur.TrieurSelfCheck} : un plan correct par lecture de code ne suffit pas à
 * savoir si {@link Recycleur#calculer} retrouve vraiment la bonne recette, choisit le bon matériau
 * représentatif et arrondit dans le bon sens.
 *
 * <h2>Gated, comme {@code TrieurSelfCheck}</h2>
 *
 * <p>{@code LANTERNE_RECYCLEUR_TEST=1} uniquement, sur {@code ./gradlew runServer} — jamais actif
 * pour un joueur normal. Sans variable, cette classe ne fait rien : son écouteur sort immédiatement.
 *
 * <h2>Pourquoi une pioche en fer</h2>
 *
 * <p>Recette connue et fixe dans les données de ce moteur (vérifiée dans {@code
 * data/minecraft/recipe/iron_pickaxe.json} du jar client 26.3) : trois lingots de fer (la balise
 * {@code #minecraft:iron_tool_materials}, qui ne contient qu'un seul item dans ce moteur — le choix
 * du « premier item accepté » n'est donc ambigu pour personne) et deux bâtons. C'est exactement
 * l'exemple que le patron a donné pour le casque, transposé à un objet qu'on peut endommager par
 * code sans simuler un vrai minage.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class RecycleurSelfCheck {
    private static final boolean ARMED = "1".equals(System.getenv("LANTERNE_RECYCLEUR_TEST"));

    private RecycleurSelfCheck() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!ARMED) {
            return;
        }
        RecipeManager recettes = event.getServer().getRecipeManager();
        int failures = testDurabiliteComplete(recettes)
                + testDurabiliteMoitie(recettes)
                + testObjetNonEndommageable(recettes)
                + testObjetSansRecette(recettes);
        if (failures == 0) {
            Lanterne.LOG.info("[RECYCLEUR-TEST] tout est passé.");
        } else {
            Lanterne.LOG.error("[RECYCLEUR-TEST] {} échec(s).", failures);
        }
    }

    /** À 100% de durabilité, le Recycleur rend exactement la recette : rien n'est perdu ni gagné. */
    private static int testDurabiliteComplete(RecipeManager recettes) {
        ItemStack pioche = new ItemStack(Items.IRON_PICKAXE);
        pioche.setDamageValue(0);

        Recycleur.Verdict verdict = Recycleur.calculer(recettes, pioche);
        int failures = attendre("pioche neuve, acceptée", true, verdict.estAcceptee());
        if (!verdict.estAcceptee()) {
            return failures + 1;
        }
        failures += attendreMateriaux("pioche neuve -> 3 lingots de fer + 2 bâtons", verdict.rendu(),
                Items.IRON_INGOT, 3, Items.STICK, 2);
        return failures;
    }

    /**
     * À une fraction de durabilité mesurée (proche de 50%, calculée depuis le vrai {@code
     * getMaxDamage()} plutôt que supposée) : 3 × f et 2 × f arrondis vers le bas.
     */
    private static int testDurabiliteMoitie(RecipeManager recettes) {
        ItemStack pioche = new ItemStack(Items.IRON_PICKAXE);
        int max = pioche.getMaxDamage();
        int degats = max / 2;
        pioche.setDamageValue(degats);
        double fraction = 1.0 - (degats / (double) max);
        int lingotsAttendus = (int) Math.floor(3 * fraction);
        int batonsAttendus = (int) Math.floor(2 * fraction);
        Lanterne.LOG.info("[RECYCLEUR-TEST] pioche à {}/{} dégâts -> fraction={} (attendu {} lingot(s), "
                + "{} bâton(s))", degats, max, fraction, lingotsAttendus, batonsAttendus);

        Recycleur.Verdict verdict = Recycleur.calculer(recettes, pioche);
        int failures = attendre("pioche à moitié usée, acceptée", true, verdict.estAcceptee());
        if (!verdict.estAcceptee()) {
            return failures + 1;
        }
        failures += attendreMateriaux("pioche à moitié usée -> quantités arrondies vers le bas",
                verdict.rendu(), Items.IRON_INGOT, lingotsAttendus, Items.STICK, batonsAttendus);
        return failures;
    }

    /** Un lingot n'a pas de barre de durabilité : refusé, sans même chercher de recette. */
    private static int testObjetNonEndommageable(RecipeManager recettes) {
        Recycleur.Verdict verdict = Recycleur.calculer(recettes, new ItemStack(Items.IRON_INGOT));
        return attendre("lingot de fer (sans durabilité) -> refusé", false, verdict.estAcceptee());
    }

    /** L'élytre s'endommage, mais aucune recette de ce moteur ne la fabrique : refusée. */
    private static int testObjetSansRecette(RecipeManager recettes) {
        ItemStack elytre = new ItemStack(Items.ELYTRA);
        int failures = attendre("élytre endommageable (prérequis du test)", true, elytre.isDamageableItem());
        Recycleur.Verdict verdict = Recycleur.calculer(recettes, elytre);
        failures += attendre("élytre (sans recette) -> refusée", false, verdict.estAcceptee());
        return failures;
    }

    private static int attendreMateriaux(String intitule, List<ItemStack> rendu,
            Item item1, int quantite1, Item item2, int quantite2) {
        List<ItemStack> attendus = new ArrayList<>();
        if (quantite1 > 0) {
            attendus.add(new ItemStack(item1, quantite1));
        }
        if (quantite2 > 0) {
            attendus.add(new ItemStack(item2, quantite2));
        }
        boolean ok = rendu.size() == attendus.size();
        for (int i = 0; ok && i < rendu.size(); i++) {
            ok = rendu.get(i).getItem() == attendus.get(i).getItem()
                    && rendu.get(i).getCount() == attendus.get(i).getCount();
        }
        if (ok) {
            Lanterne.LOG.info("[RECYCLEUR-TEST] [OK]    {} (obtenu={})", intitule, decrire(rendu));
            return 0;
        }
        Lanterne.LOG.error("[RECYCLEUR-TEST] [ECHEC] {} (attendu={}, obtenu={})", intitule,
                decrire(attendus), decrire(rendu));
        return 1;
    }

    private static String decrire(List<ItemStack> stacks) {
        StringBuilder texte = new StringBuilder("[");
        for (int i = 0; i < stacks.size(); i++) {
            if (i > 0) {
                texte.append(", ");
            }
            texte.append(stacks.get(i).getCount()).append("× ")
                    .append(stacks.get(i).getItem());
        }
        return texte.append(']').toString();
    }

    private static int attendre(String intitule, boolean attendu, boolean obtenu) {
        boolean ok = attendu == obtenu;
        if (ok) {
            Lanterne.LOG.info("[RECYCLEUR-TEST] [OK]    {} (attendu={}, obtenu={})", intitule, attendu, obtenu);
        } else {
            Lanterne.LOG.error("[RECYCLEUR-TEST] [ECHEC] {} (attendu={}, obtenu={})", intitule, attendu, obtenu);
        }
        return ok ? 0 : 1;
    }
}
