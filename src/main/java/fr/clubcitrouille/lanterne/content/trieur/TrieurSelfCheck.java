package fr.clubcitrouille.lanterne.content.trieur;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
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
 *
 * <h2>Les motifs (26.3.141)</h2>
 *
 * <p>Le dépôt n'a pas d'infrastructure de tests unitaires (pas de {@code src/test}) : le matcher
 * {@link Motif} est donc vérifié ici, sur le vrai registre et les vrais tags chargés — c'est
 * précisément ce qu'un test isolé n'aurait pas eu ({@code #logs} n'existe qu'une fois les données
 * chargées). Couvre les jetons (« axe » ≠ « pickaxe »), les jokers, les tags, l'espace de noms, la
 * négation, les accents, le dictionnaire français, le nom personnalisé, et le filtre complet d'un
 * Trieur qui mélange gabarits et motifs. Lancer un serveur avec {@code LANTERNE_TRIEUR_TEST=1} et lire
 * les lignes {@code [TRIEUR-TEST]} du journal.
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
        int failures = testWhitelist() + testBlacklist() + testMotifs() + testFiltreMotifs() + testEtiquettes()
                + testCapacite(event.getServer().overworld());
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

    /** Le matcher seul, objet par objet, sur le vrai registre. */
    private static int testMotifs() {
        int f = 0;
        f += motif("sword", Items.DIAMOND_SWORD, true);
        f += motif("sword", Items.DIAMOND_PICKAXE, false);
        f += motif("axe", Items.DIAMOND_AXE, true);
        f += motif("axe", Items.DIAMOND_PICKAXE, false);
        f += motif("axes", Items.NETHERITE_AXE, true);
        f += motif("pickaxe", Items.IRON_PICKAXE, true);
        f += motif("netherite", Items.NETHERITE_INGOT, true);
        f += motif("netherite", Items.DIAMOND, false);
        f += motif("#logs", Items.OAK_LOG, true);
        f += motif("#logs", Items.OAK_PLANKS, false);
        f += motif("#minecraft:swords", Items.WOODEN_SWORD, true);
        f += motif("@minecraft", Items.STONE, true);
        f += motif("@lanterne", Items.STONE, false);
        f += motif("*_ore", Items.IRON_ORE, true);
        f += motif("*_ore", Items.RAW_IRON, false);
        f += motif("minecraft:*_sword", Items.STONE_SWORD, true);
        f += motif("diamond sword", Items.DIAMOND_SWORD, true);
        f += motif("diamond sword", Items.DIAMOND_AXE, false);
        f += motif("sword !golden", Items.GOLDEN_SWORD, false);
        f += motif("sword !golden", Items.IRON_SWORD, true);
        f += motif("Épée", Items.IRON_SWORD, true);
        f += motif("épée !or", Items.GOLDEN_SWORD, false);
        f += motif("hache", Items.STONE_AXE, true);
        f += motif("hache", Items.STONE_PICKAXE, false);
        f += motif("bois", Items.OAK_PLANKS, true);
        f += motif("bûche", Items.BIRCH_LOG, true);
        f += motif("fishing_rod", Items.FISHING_ROD, true);
        f += motif("rod fishing", Items.FISHING_ROD, true);
        f += motif("", Items.STONE, false);
        f += motif("!!!", Items.STONE, false);
        f += motif("#pas un tag valide", Items.STONE, false);

        // Le nom personnalisé compte, découpé en mots : « Super Axe » attrape « axe », pas « pick ».
        Motif axe = Motif.compiler("axe");
        ItemStack baton = new ItemStack(Items.STICK);
        baton.set(DataComponents.CUSTOM_NAME, Component.literal("Super Axe"));
        f += attendre("motif « axe » sur un bâton renommé « Super Axe »", true, axe.correspond(baton));
        f += attendre("motif « pick » sur un bâton renommé « Super Axe »", false,
                Motif.compiler("pick").correspond(baton));
        return f;
    }

    private static int motif(String texte, Item item, boolean attendu) {
        return attendre("motif « " + texte + " » sur " + item, attendu,
                Motif.compiler(texte).correspond(new ItemStack(item)));
    }

    /** Un Trieur complet : gabarits et motifs mêlés, en liste blanche puis noire. */
    private static int testFiltreMotifs() {
        int f = 0;
        TrieurBlockEntity blanche = fraisEntonnoir();
        blanche.filtre().setItem(0, Motif.etiquette("sword"));
        blanche.filtre().setItem(1, new ItemStack(Items.COBBLESTONE));
        f += attendre("liste blanche [sword, caillou] : épée en fer", true,
                blanche.canPlaceItem(0, new ItemStack(Items.IRON_SWORD)));
        f += attendre("liste blanche [sword, caillou] : caillou", true,
                blanche.canPlaceItem(0, new ItemStack(Items.COBBLESTONE)));
        f += attendre("liste blanche [sword, caillou] : pioche -> refusée", false,
                blanche.canPlaceItem(0, new ItemStack(Items.IRON_PICKAXE)));
        // Changer le filtre doit invalider le filtre compilé.
        blanche.filtre().setItem(0, ItemStack.EMPTY);
        f += attendre("après retrait du motif : épée refusée (cache invalidé)", false,
                blanche.canPlaceItem(0, new ItemStack(Items.IRON_SWORD)));

        TrieurBlockEntity noire = fraisEntonnoir();
        noire.setWhitelist(false);
        noire.filtre().setItem(17, Motif.etiquette("#logs"));
        f += attendre("liste noire [#logs] : bûche de chêne -> refusée", false,
                noire.canPlaceItem(0, new ItemStack(Items.OAK_LOG)));
        f += attendre("liste noire [#logs] : planche -> acceptée", true,
                noire.canPlaceItem(0, new ItemStack(Items.OAK_PLANKS)));

        // Une étiquette NON renommée reste un gabarit : elle désigne les étiquettes.
        TrieurBlockEntity etiquettes = fraisEntonnoir();
        etiquettes.filtre().setItem(0, new ItemStack(Items.NAME_TAG));
        f += attendre("gabarit étiquette nue : une étiquette passe", true,
                etiquettes.canPlaceItem(0, new ItemStack(Items.NAME_TAG)));
        f += attendre("gabarit étiquette nue : un caillou est refusé", false,
                etiquettes.canPlaceItem(0, new ItemStack(Items.COBBLESTONE)));
        return f;
    }

    /** Étiquettes virtuelles, vraies étiquettes renommées, nettoyage du texte réseau. */
    private static int testEtiquettes() {
        int f = 0;
        f += attendre("étiquette créée par le champ = virtuelle", true, Motif.estVirtuel(Motif.etiquette("sword")));
        ItemStack vraie = new ItemStack(Items.NAME_TAG);
        vraie.set(DataComponents.CUSTOM_NAME, Component.literal("sword"));
        f += attendre("étiquette renommée à l'enclume = motif", true, Motif.estMotif(vraie));
        f += attendre("étiquette renommée à l'enclume ≠ virtuelle", false, Motif.estVirtuel(vraie));
        f += attendre("nettoyer : codes § et espaces", true,
                "a b".equals(Motif.nettoyer("  §ca   " + (char) 7 + "b  ")));
        f += attendre("nettoyer : borné à " + Motif.LONGUEUR_MAX, true,
                Motif.nettoyer("x".repeat(500)).length() == Motif.LONGUEUR_MAX);
        return f;
    }

    /**
     * La capacité d'objets est bien enregistrée pour le type du Trieur (voir {@link TrieurCablage}) :
     * sans elle, un tuyau de mod ou le ticker d'un autre Trieur ne verrait rien. Surcharge de {@code
     * getCapability} qui prend l'état et le bloc-entité explicitement : aucun bloc n'est posé.
     */
    private static int testCapacite(ServerLevel level) {
        TrieurBlockEntity trieur = fraisEntonnoir();
        trieur.setWhitelist(false);
        var poignee = level.getCapability(Capabilities.Item.BLOCK, BlockPos.ZERO, trieur.getBlockState(),
                trieur, Direction.UP);
        int f = attendre("capacité Item.BLOCK exposée par le Trieur", true, poignee != null);
        if (poignee != null) {
            f += attendre("capacité : 5 cases (celles du hopper, pas le filtre)", true, poignee.size() == 5);
        }
        return f;
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
