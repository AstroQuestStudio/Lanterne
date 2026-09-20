package fr.clubcitrouille.lanterne.content.trieur;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.Contents;

/**
 * Vérification automatique du filtre du Trieur, sans humain devant l'écran — même geste que {@code
 * client.screen.PaletteSelfCheck} : un plan correct par lecture de code ne suffit pas à savoir si
 * {@link TrieurBlockEntity#canPlaceItem} rend vraiment ce qu'on attend.
 *
 * <h2>Gated, comme {@code PaletteSelfCheck}</h2>
 *
 * <p>{@code LANTERNE_TRIEUR_TEST=1} uniquement, sur {@code ./gradlew runServer} — jamais actif pour
 * un joueur normal. Sans variable, cette classe ne fait rien : son écouteur sort immédiatement.
 *
 * <h2>Pourquoi {@code ServerStartedEvent}, et pas un {@code main} isolé</h2>
 *
 * <p>Une première version tentait un {@code main} totalement isolé, hors du jeu. Elle a échoué au
 * démarrage même de {@code net.minecraft.server.Bootstrap.bootStrap()} : ce moteur patché exige un
 * {@code FMLLoader} déjà en place pour charger ses drapeaux de fonctionnalité ({@code
 * FeatureFlagLoader.loadModdedFlags}), et rien de plus léger qu'un vrai lancement ne le fournit. Un
 * évènement de cycle de vie du serveur, lui, s'exécute après un amorçage complet et réel — exactement
 * ce que {@code runServer} offre déjà pour les bancs de ce projet (voir {@code core.Config},
 * {@code lab/}).
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class TrieurSelfCheck {
    private static final boolean ARMED = "1".equals(System.getenv("LANTERNE_TRIEUR_TEST"));

    private TrieurSelfCheck() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!ARMED) {
            return;
        }
        int failures = testWhitelist() + testBlacklist();
        if (failures == 0) {
            Lanterne.LOG.info("[TRIEUR-TEST] tout est passé.");
        } else {
            Lanterne.LOG.error("[TRIEUR-TEST] {} échec(s).", failures);
        }
    }

    private static TrieurBlockEntity fraisEntonnoir() {
        BlockState state = Contents.TRIEUR.get().defaultBlockState();
        return new TrieurBlockEntity(BlockPos.ZERO, state);
    }

    /** Liste blanche avec deux objets inscrits : eux passent, un troisième objet est refusé. */
    private static int testWhitelist() {
        TrieurBlockEntity trieur = fraisEntonnoir();
        trieur.filtre().setItem(0, new ItemStack(Items.DIAMOND));
        trieur.filtre().setItem(1, new ItemStack(Items.EMERALD));
        // whitelist == true par défaut : ne rien changer teste aussi que le défaut est le bon.

        int failures = 0;
        failures += attendre("liste blanche, objet inscrit (diamant)", true,
                trieur.canPlaceItem(0, new ItemStack(Items.DIAMOND)));
        failures += attendre("liste blanche, objet inscrit (émeraude)", true,
                trieur.canPlaceItem(0, new ItemStack(Items.EMERALD)));
        failures += attendre("liste blanche, objet absent (caillou) -> refusé", false,
                trieur.canPlaceItem(0, new ItemStack(Items.COBBLESTONE)));
        return failures;
    }

    /** Liste noire avec un seul objet banni : lui seul est refusé, tout le reste passe. */
    private static int testBlacklist() {
        TrieurBlockEntity trieur = fraisEntonnoir();
        trieur.setWhitelist(false);
        trieur.filtre().setItem(0, new ItemStack(Items.COAL));

        int failures = 0;
        failures += attendre("liste noire, objet banni (charbon) -> refusé", false,
                trieur.canPlaceItem(0, new ItemStack(Items.COAL)));
        failures += attendre("liste noire, objet non banni (diamant) -> accepté", true,
                trieur.canPlaceItem(0, new ItemStack(Items.DIAMOND)));
        failures += attendre("liste noire, objet non banni (pain) -> accepté", true,
                trieur.canPlaceItem(0, new ItemStack(Items.BREAD)));
        return failures;
    }

    private static int attendre(String intitule, boolean attendu, boolean obtenu) {
        boolean ok = attendu == obtenu;
        if (ok) {
            Lanterne.LOG.info("[TRIEUR-TEST] [OK]    {} (attendu={}, obtenu={})", intitule, attendu, obtenu);
        } else {
            Lanterne.LOG.error("[TRIEUR-TEST] [ECHEC] {} (attendu={}, obtenu={})", intitule, attendu, obtenu);
        }
        return ok ? 0 : 1;
    }
}
