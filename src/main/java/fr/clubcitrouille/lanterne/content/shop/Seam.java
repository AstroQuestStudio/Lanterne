package fr.clubcitrouille.lanterne.content.shop;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.Identifier;

/**
 * La veine : le seul endroit du mod où un prix est écrit à la main.
 *
 * <h2>Pourquoi si peu de prix écrits, et pourquoi ceux-là</h2>
 *
 * <p>Tarifer mille articles à la main est impossible à faire juste. Ce n'est pas une question de
 * courage : c'est qu'<b>un prix posé au jugé est une boucle d'arbitrage en puissance</b>. Il suffit
 * d'un seul objet dont le rachat dépasse le coût de ses ingrédients pour qu'un joueur fabrique de
 * l'argent en boucle, et l'erreur est invisible tant que personne ne l'a trouvée.
 *
 * <p>Ce fichier ne contient donc que les <b>matières premières</b> — ce que le monde donne et
 * qu'aucune recette ne fabrique : la terre, la pierre, les bûches, les minerais bruts, le blé, le
 * cuir, les butins de créature. Tout le reste est <em>calculé</em> à partir des recettes du jeu par
 * {@link Assay}. C'est ici, et ici seulement, qu'est le jugement d'équilibrage.
 *
 * <h2>Un prix de base est un plancher, pas une consigne</h2>
 *
 * <p>Si une recette permet d'obtenir un objet moins cher que ce qui est écrit ici, <b>c'est la
 * recette qui gagne</b>. Cette règle n'est pas un détail d'implémentation : elle rend impossible
 * toute une famille d'erreurs. Un prix écrit trop haut ici ne peut pas créer de boucle, puisqu'il
 * sera remplacé par le coût réel de fabrication dès qu'il le dépasse.
 *
 * <p>Trois motifs de bannière ont été surpris exactement ainsi pendant la mise au point : écrits à
 * 60 pièces alors qu'on les fabrique pour deux, ils rendaient le rachat plus rentable que
 * l'artisanat. Le passage au plancher les a corrigés tous les trois sans qu'on ait à les nommer.
 *
 * <h2>Les trois listes</h2>
 *
 * <ul>
 *   <li>{@link #base} — le prix plancher d'une matière première, en centimes ;</li>
 *   <li>{@link #banned} — ce qui ne doit <b>jamais</b> figurer au catalogue : blocs techniques, œufs
 *       d'apparition, objets porteurs de composants par nature (potions, livres enchantés) ;</li>
 *   <li>{@link #noBuyback} — ce qui s'achète mais ne se revend <b>jamais</b> : élytres, totem, étoile
 *       du Nether, têtes, tessons d'archéologie, disques. Tous se produisent en ferme ou se ramassent
 *       en quantité ; les racheter, même modestement, ferait du serveur cette seule ferme.</li>
 * </ul>
 *
 * <h2>L'échelle</h2>
 *
 * <p>Une pièce vaut à peu près deux blocs de pierre taillés à la main. Tous les montants de ce
 * fichier sont en <b>centimes</b> : {@code 250} se lit « deux pièces cinquante ».
 */
public final class Seam {
    private static final Map<Identifier, Long> BASE = new HashMap<>(512);
    private static final Set<Identifier> BANNED = new HashSet<>(128);
    private static final Set<Identifier> NO_BUYBACK = new HashSet<>(64);

    private static final String[] WOODS = {
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak"
    };
    private static final String[] STEMS = {"crimson", "warped"};
    private static final String[] DYES = {
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };
    private static final String[] CORALS = {"tube", "brain", "bubble", "fire", "horn"};
    private static final String[] COPPER_AGE = {"exposed", "weathered", "oxidized"};
    private static final String[] TRIMS = {
        "sentry", "dune", "coast", "wild", "ward", "eye", "vex", "tide", "snout", "rib", "spire",
        "wayfinder", "shaper", "silence", "raiser", "host", "flow", "bolt"
    };

    private Seam() {}

    private static void put(String path, long cents) {
        BASE.put(Identifier.withDefaultNamespace(path), cents);
    }

    private static void ban(String... paths) {
        for (String path : paths) {
            BANNED.add(Identifier.withDefaultNamespace(path));
        }
    }

    /** Achetable, jamais racheté. */
    private static void prestige(String path, long cents) {
        put(path, cents);
        NO_BUYBACK.add(Identifier.withDefaultNamespace(path));
    }

    static {
        // --- Terres, pierres, roches ---------------------------------------
        //
        // « cobblestone » et « cobbled_deepslate » sont des BRISEURS DE CYCLE : le jeu ne sait les
        // fabriquer qu'à partir de la pierre, qui ne se fait qu'en les cuisant. Sans un prix écrit
        // ici, la roche entière du jeu resterait sans valeur — et avec elle la moitié du catalogue.
        put("dirt", 30);
        put("rooted_dirt", 40);
        put("coarse_dirt", 35);
        put("podzol", 60);
        put("mycelium", 120);
        put("grass_block", 60);
        put("mud", 40);
        put("clay_ball", 150);
        put("sand", 60);
        put("red_sand", 60);
        put("gravel", 60);
        put("flint", 200);
        put("cobblestone", 50);
        put("cobbled_deepslate", 50);
        put("netherrack", 40);
        put("basalt", 60);
        put("blackstone", 80);
        put("gilded_blackstone", 900);
        put("tuff", 60);
        put("calcite", 120);
        put("end_stone", 400);
        put("soul_sand", 300);
        put("soul_soil", 300);
        put("obsidian", 2400);
        put("crying_obsidian", 6000);
        put("moss_block", 200);
        put("pale_moss_block", 200);
        put("mangrove_roots", 200);
        put("pointed_dripstone", 300);
        put("ice", 300);
        put("snowball", 50);
        put("magma_block", 900);
        put("sculk", 200);
        put("sculk_sensor", 1500);
        put("sculk_catalyst", 2500);
        put("sculk_shrieker", 2500);
        put("sculk_vein", 150);
        put("cobweb", 400);
        put("amethyst_shard", 2000);
        put("small_amethyst_bud", 400);
        put("medium_amethyst_bud", 600);
        put("large_amethyst_bud", 800);
        put("amethyst_cluster", 1200);
        put("ochre_froglight", 1200);
        put("verdant_froglight", 1200);
        put("pearlescent_froglight", 1200);
        put("shroomlight", 600);
        put("crimson_nylium", 200);
        put("warped_nylium", 200);
        put("warped_wart_block", 300);
        put("brown_mushroom_block", 200);
        put("red_mushroom_block", 200);
        put("mushroom_stem", 200);
        put("resin_clump", 300);
        put("honey_bottle", 600);
        put("cut_standstone_slab", 60);

        // --- Minerais et métaux bruts --------------------------------------
        //
        // Le minerai POSÉ vaut plus cher que ce qu'il donne : on l'achète pour le décor ou pour le
        // ramasser à la soie, pas pour le fondre. « coal » est écrit ici parce que sa seule recette
        // est le démontage de son bloc — encore un cycle.
        put("coal", 400);
        put("coal_ore", 800);
        put("deepslate_coal_ore", 800);
        put("raw_copper", 600);
        put("copper_ore", 1000);
        put("deepslate_copper_ore", 1000);
        put("raw_iron", 1200);
        put("iron_ore", 2000);
        put("deepslate_iron_ore", 2000);
        put("raw_gold", 2400);
        put("gold_ore", 4000);
        put("deepslate_gold_ore", 4000);
        // L'or du Nether affleure par blocs entiers : il est abondant, mais le mettre trop bas
        // rendait le lingot d'or MOINS cher que celui de fer, ce qui se voyait tout de suite.
        put("nether_gold_ore", 2200);
        put("redstone_ore", 500);
        put("deepslate_redstone_ore", 500);
        put("lapis_ore", 700);
        put("deepslate_lapis_ore", 700);
        put("diamond_ore", 20000);
        put("deepslate_diamond_ore", 20000);
        put("emerald_ore", 10000);
        put("deepslate_emerald_ore", 10000);
        put("nether_quartz_ore", 1100);
        put("ancient_debris", 65000);

        // --- Bois -----------------------------------------------------------
        for (String wood : WOODS) {
            put(wood + "_log", 250);
            put("stripped_" + wood + "_log", 250);
            put(wood + "_leaves", 20);
            put(wood + "_sapling", 400);
        }
        for (String stem : STEMS) {
            put(stem + "_stem", 250);
            put("stripped_" + stem + "_stem", 250);
            put(stem + "_fungus", 150);
            put(stem + "_roots", 150);
        }
        put("mangrove_propagule", 400);
        put("azalea_leaves", 20);
        put("flowering_azalea_leaves", 30);
        put("azalea", 300);
        put("flowering_azalea", 400);
        put("bamboo", 40);
        put("stripped_bamboo_block", 400);

        // --- Fleurs et plantes ---------------------------------------------
        for (String flower : new String[] {"dandelion", "poppy", "blue_orchid", "allium",
                "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
                "oxeye_daisy", "cornflower", "lily_of_the_valley"}) {
            put(flower, 50);
        }
        put("wither_rose", 300);
        put("torchflower", 500);
        put("pitcher_plant", 500);
        put("sunflower", 60);
        put("lilac", 60);
        put("rose_bush", 60);
        put("peony", 60);
        put("pink_petals", 40);
        put("wildflowers", 40);
        put("closed_eyeblossom", 200);
        put("open_eyeblossom", 200);
        put("cactus_flower", 100);
        put("short_grass", 30);
        put("tall_grass", 50);
        put("dry_short_grass", 30);
        put("dry_tall_grass", 50);
        put("fern", 40);
        put("large_fern", 60);
        put("dead_bush", 40);
        put("bush", 100);
        put("firefly_bush", 200);
        put("seagrass", 60);
        put("lily_pad", 150);
        put("glow_lichen", 200);
        put("hanging_roots", 60);
        put("pale_hanging_moss", 60);
        put("spore_blossom", 500);
        put("big_dripleaf", 300);
        put("small_dripleaf", 300);
        put("nether_sprouts", 60);
        put("twisting_vines", 80);
        put("weeping_vines", 80);
        put("vine", 80);
        put("chorus_flower", 800);
        put("chorus_plant", 400);
        put("sea_pickle", 300);
        put("turtle_egg", 800);
        put("frogspawn", 200);
        put("bee_nest", 3000);
        put("nether_wart", 400);
        put("chorus_fruit", 600);

        // --- Agriculture et nourriture brute --------------------------------
        put("wheat", 150);
        put("wheat_seeds", 40);
        put("carrot", 150);
        put("potato", 150);
        put("poisonous_potato", 60);
        put("beetroot", 150);
        put("beetroot_seeds", 40);
        put("melon_slice", 80);
        put("pumpkin", 300);
        put("carved_pumpkin", 400);
        put("sugar_cane", 120);
        put("cocoa_beans", 200);
        put("apple", 300);
        put("kelp", 50);
        put("cactus", 100);
        put("sweet_berries", 150);
        put("glow_berries", 200);
        put("brown_mushroom", 150);
        put("red_mushroom", 150);
        put("torchflower_seeds", 800);
        put("pitcher_pod", 800);

        // --- Butin de créatures ---------------------------------------------
        put("beef", 300);
        put("porkchop", 300);
        put("chicken", 250);
        put("mutton", 250);
        put("rabbit", 250);
        put("cod", 250);
        put("salmon", 300);
        put("tropical_fish", 400);
        put("pufferfish", 400);
        put("egg", 150);
        put("blue_egg", 200);
        put("brown_egg", 200);
        put("feather", 150);
        put("string", 200);
        put("bone", 200);
        put("gunpowder", 600);
        put("spider_eye", 300);
        put("rotten_flesh", 60);
        put("ink_sac", 300);
        put("glow_ink_sac", 600);
        put("slime_ball", 600);
        put("honeycomb", 800);
        put("ender_pearl", 4000);
        put("blaze_rod", 4500);
        put("breeze_rod", 4500);
        put("ghast_tear", 9000);
        put("phantom_membrane", 3000);
        put("shulker_shell", 40000);
        put("nautilus_shell", 12000);
        put("prismarine_shard", 1000);
        put("prismarine_crystals", 1500);
        put("turtle_scute", 2000);
        put("armadillo_scute", 1200);
        put("rabbit_foot", 2000);
        put("rabbit_hide", 150);
        put("wet_sponge", 4000);
        put("echo_shard", 8000);
        put("dragon_breath", 5000);
        put("disc_fragment_5", 2000);
        put("glowstone_dust", 300);
        put("milk_bucket", 5500);

        // --- Corail -----------------------------------------------------------
        for (String coral : CORALS) {
            put(coral + "_coral", 400);
            put(coral + "_coral_block", 1200);
            put(coral + "_coral_fan", 400);
            put("dead_" + coral + "_coral", 200);
            put("dead_" + coral + "_coral_block", 600);
            put("dead_" + coral + "_coral_fan", 200);
        }

        // --- Cuivre oxydé ----------------------------------------------------
        //
        // Aucune recette ne les fabrique : c'est le temps qui les fait. Ils doivent donc être écrits,
        // faute de quoi tout le cuivre patiné du jeu — et ses versions taillées — resterait absent.
        for (String age : COPPER_AGE) {
            put(age + "_copper", 2400);
            put(age + "_copper_bars", 900);
            put(age + "_copper_chain", 900);
            put(age + "_copper_chest", 4000);
            put(age + "_copper_door", 1500);
            put(age + "_copper_trapdoor", 1500);
            put(age + "_copper_lantern", 1500);
            put(age + "_lightning_rod", 2000);
            put(age + "_copper_golem_statue", 3000);
        }
        put("copper_golem_statue", 3000);

        // --- Équipement que le jeu ne sait pas fabriquer ----------------------
        put("chainmail_helmet", 8000);
        put("chainmail_chestplate", 12000);
        put("chainmail_leggings", 10000);
        put("chainmail_boots", 6000);
        put("copper_horse_armor", 8000);
        put("iron_horse_armor", 15000);
        put("golden_horse_armor", 20000);
        put("diamond_horse_armor", 60000);
        put("copper_nautilus_armor", 8000);
        put("iron_nautilus_armor", 15000);
        put("golden_nautilus_armor", 20000);
        put("diamond_nautilus_armor", 60000);
        put("bell", 20000);
        put("chipped_anvil", 20000);
        put("damaged_anvil", 18000);

        // --- Seaux remplis ----------------------------------------------------
        put("water_bucket", 5300);
        put("lava_bucket", 6500);
        put("powder_snow_bucket", 6000);
        put("cod_bucket", 6000);
        put("salmon_bucket", 6000);
        put("pufferfish_bucket", 6000);
        put("tropical_fish_bucket", 6500);
        put("axolotl_bucket", 15000);
        put("tadpole_bucket", 8000);

        // --- Béton : c'est l'eau qui le durcit, il n'a pas de recette ---------
        for (String dye : DYES) {
            put(dye + "_concrete", 300);
        }

        // --- Gabarits de forge ------------------------------------------------
        //
        // Leur unique recette est leur propre duplication : sept diamants, un gabarit, et l'on en
        // obtient deux. C'est un cycle pur — le gabarit se fabrique à partir de lui-même —, donc
        // rien ne pourrait lui donner un prix sans cette ligne.
        for (String trim : TRIMS) {
            put(trim + "_armor_trim_smithing_template", 30000);
        }
        put("netherite_upgrade_smithing_template", 30000);

        // --- Motifs de bannière sans recette ---------------------------------
        for (String pattern : new String[] {"mojang_banner_pattern", "globe_banner_pattern",
                "piglin_banner_pattern", "flow_banner_pattern", "guster_banner_pattern"}) {
            put(pattern, 6000);
        }

        // --- Prestige : on l'achète, on ne le revend jamais -------------------
        prestige("nether_star", 400000);
        prestige("heart_of_the_sea", 150000);
        prestige("totem_of_undying", 250000);
        prestige("elytra", 600000);
        prestige("trident", 200000);
        prestige("dragon_egg", 500000);
        prestige("dragon_head", 100000);
        prestige("creeper_head", 20000);
        prestige("zombie_head", 15000);
        prestige("skeleton_skull", 15000);
        prestige("wither_skeleton_skull", 30000);
        prestige("piglin_head", 15000);
        prestige("heavy_core", 80000);
        prestige("enchanted_golden_apple", 300000);
        prestige("experience_bottle", 2500);
        prestige("goat_horn", 5000);
        prestige("sniffer_egg", 60000);
        prestige("trial_key", 20000);
        prestige("ominous_trial_key", 40000);
        prestige("ominous_bottle", 8000);
        for (String disc : new String[] {"13", "cat", "blocks", "chirp", "far", "mall", "mellohi",
                "stal", "strad", "ward", "11", "wait", "otherside", "5", "pigstep", "relic",
                "creator", "creator_music_box", "precipice", "tears", "lava_chicken"}) {
            prestige("music_disc_" + disc, 15000);
        }
        for (String sherd : new String[] {"angler", "archer", "arms_up", "blade", "brewer", "burn",
                "danger", "explorer", "flow", "friend", "guster", "heart", "heartbreak", "howl",
                "miner", "mourner", "plenty", "prize", "scrape", "sheaf", "shelter", "skull",
                "snort"}) {
            prestige(sherd + "_pottery_sherd", 5000);
        }

        // --- Jamais au catalogue ----------------------------------------------
        //
        // Trois familles : ce qui n'existe pas dans une partie ordinaire (blocs techniques), ce qui
        // casserait le jeu (œufs d'apparition), et ce qui porte des composants par nature — une
        // potion, un livre enchanté, une carte remplie ne sont jamais deux fois le même objet, et
        // la caisse les refuserait de toute façon au rachat.
        ban("air", "barrier", "bedrock", "command_block", "chain_command_block",
                "repeating_command_block", "command_block_minecart", "structure_block",
                "structure_void", "jigsaw", "debug_stick", "light", "end_portal_frame", "spawner",
                "trial_spawner", "vault", "knowledge_book", "test_block", "test_instance_block",
                "reinforced_deepslate", "budding_amethyst", "suspicious_sand", "suspicious_gravel",
                "petrified_oak_slab", "farmland", "dirt_path", "frosted_ice", "water", "lava",
                "moving_piston", "nether_portal", "end_portal", "end_gateway", "potion",
                "splash_potion", "lingering_potion", "tipped_arrow", "written_book",
                "enchanted_book", "player_head", "firework_star", "firework_rocket", "filled_map",
                "ominous_banner");
    }

    // --- Consultation -------------------------------------------------------

    /** Le prix plancher, en centimes, ou {@code null} si ce n'est pas une matière première. */
    public static Long base(Identifier item) {
        return BASE.get(item);
    }

    public static int baseCount() {
        return BASE.size();
    }

    /**
     * Cet objet n'a rien à faire dans une boutique.
     *
     * <p>Les deux familles à motif — œufs d'apparition et pierres infestées — sont reconnues par leur
     * nom plutôt qu'énumérées : le registre n'est pas encore lisible quand cette classe s'initialise,
     * et une liste écrite à la main vieillirait mal à chaque version.
     */
    public static boolean banned(Identifier item) {
        if (BANNED.contains(item)) {
            return true;
        }
        String path = item.getPath();
        return path.endsWith("_spawn_egg") || path.startsWith("infested_");
    }

    /** Achetable, mais jamais racheté. */
    public static boolean noBuyback(Identifier item) {
        return NO_BUYBACK.contains(item);
    }

    // --- Les rayons ---------------------------------------------------------

    /**
     * Le rayon d'un objet, deviné à partir de son nom.
     *
     * <h2>Pourquoi le nom et non l'onglet créatif</h2>
     *
     * <p>Les onglets créatifs seraient plus justes, mais ils ne sont construits qu'avec un contexte
     * client et leur contenu n'est pas interrogeable simplement depuis un serveur dédié. Le nom, lui,
     * est toujours là — et il porte en vanilla une information remarquablement régulière :
     * {@code _slab}, {@code _stairs}, {@code _wall} disent « construction », {@code _ore} dit
     * « minerai ».
     *
     * <p>Le bénéfice inattendu est que <b>cela marche aussi pour les mods</b> : un mod qui nomme ses
     * blocs à la façon de vanilla verra ses escaliers rangés avec les escaliers, sans avoir rien à
     * déclarer. Ce qui n'entre dans aucune règle tombe dans « Divers », et un administrateur n'a plus
     * qu'à corriger la colonne du fichier — c'est une seule ligne à changer.
     *
     * <p>L'ordre des règles est significatif : la première qui répond gagne. Un {@code music_disc_11}
     * est de la musique avant d'être du divers ; un {@code diamond_pickaxe} est un outil avant d'être
     * du minerai.
     */
    public static String aisle(Identifier item) {
        String s = item.getPath().toLowerCase(Locale.ROOT);

        if (s.startsWith("music_disc") || eq(s, "jukebox", "note_block", "goat_horn")) {
            return "Musique";
        }
        if (s.endsWith("_boat") || s.endsWith("_raft") || s.endsWith("_chest_boat")
                || s.endsWith("_chest_raft") || s.contains("minecart") || s.endsWith("_rail")
                || eq(s, "rail", "saddle", "elytra", "carrot_on_a_stick",
                        "warped_fungus_on_a_stick", "lead")) {
            return "Transport";
        }
        if (s.endsWith("_sword") || s.endsWith("_helmet") || s.endsWith("_chestplate")
                || s.endsWith("_leggings") || s.endsWith("_boots") || s.endsWith("_horse_armor")
                || s.endsWith("_nautilus_armor")
                || eq(s, "bow", "crossbow", "arrow", "spectral_arrow", "trident", "shield",
                        "turtle_helmet", "wolf_armor", "mace", "wind_charge")) {
            return "Armes et armures";
        }
        if (s.endsWith("_pickaxe") || s.endsWith("_axe") || s.endsWith("_shovel")
                || s.endsWith("_hoe")
                || eq(s, "shears", "flint_and_steel", "fishing_rod", "bucket", "brush", "spyglass",
                        "name_tag", "compass", "recovery_compass", "clock", "map", "lodestone",
                        "shulker_shell", "firework_rocket")) {
            return "Outils";
        }
        if (eq(s, "brewing_stand", "blaze_powder", "fermented_spider_eye", "glistering_melon_slice",
                "magma_cream", "glass_bottle", "cauldron", "dragon_breath", "ghast_tear",
                "nether_wart", "phantom_membrane", "rabbit_foot", "golden_carrot", "ominous_bottle",
                "experience_bottle", "sugar", "gunpowder", "redstone")) {
            return "Alchimie";
        }
        if (s.contains("redstone") || s.contains("piston") || s.endsWith("_button")
                || s.endsWith("_pressure_plate") || s.endsWith("_bulb")
                || eq(s, "observer", "hopper", "dropper", "dispenser", "repeater", "comparator",
                        "lever", "tripwire_hook", "target", "lightning_rod", "daylight_detector",
                        "sculk_sensor", "calibrated_sculk_sensor", "crafter", "slime_block",
                        "honey_block", "tnt", "trapped_chest", "bell", "copper_bulb")) {
            return "Redstone";
        }
        if (s.contains("chest") || s.contains("barrel") || s.contains("shulker_box")
                || s.contains("furnace") || s.endsWith("_bundle")
                || eq(s, "bundle", "smoker", "crafting_table", "loom", "cartography_table",
                        "fletching_table", "smithing_table", "stonecutter", "grindstone", "anvil",
                        "chipped_anvil", "damaged_anvil", "enchanting_table", "beacon", "lectern",
                        "composter", "scaffolding", "ladder")) {
            return "Rangement";
        }
        if (eq(s, "bread", "cooked_beef", "beef", "porkchop", "cooked_porkchop", "chicken",
                "cooked_chicken", "mutton", "cooked_mutton", "rabbit", "cooked_rabbit", "cod",
                "cooked_cod", "salmon", "cooked_salmon", "tropical_fish", "pufferfish", "apple",
                "golden_apple", "enchanted_golden_apple", "melon_slice", "pumpkin_pie", "cake",
                "cookie", "baked_potato", "potato", "poisonous_potato", "carrot", "beetroot",
                "beetroot_soup", "mushroom_stew", "rabbit_stew", "suspicious_stew", "dried_kelp",
                "kelp", "sweet_berries", "glow_berries", "honey_bottle", "milk_bucket",
                "chorus_fruit", "popped_chorus_fruit", "rotten_flesh", "spider_eye", "wheat",
                "hay_block", "egg", "blue_egg", "brown_egg")) {
            return "Nourriture";
        }
        if (s.endsWith("_seeds") || s.endsWith("_sapling") || s.endsWith("_propagule")
                || eq(s, "bone_meal", "sugar_cane", "cocoa_beans", "bamboo", "pitcher_pod", "melon",
                        "pumpkin", "carved_pumpkin", "jack_o_lantern", "bee_nest", "beehive",
                        "honeycomb", "honeycomb_block", "flower_pot", "turtle_egg", "sniffer_egg",
                        "frogspawn", "mud")) {
            return "Agriculture";
        }
        if (s.startsWith("nether") || s.startsWith("crimson") || s.startsWith("warped")
                || s.startsWith("soul_") || s.startsWith("end_") || s.startsWith("purpur")
                || s.startsWith("chorus") || s.startsWith("blackstone") || s.startsWith("basalt")
                || s.startsWith("polished_blackstone") || s.startsWith("gilded_")
                || eq(s, "obsidian", "crying_obsidian", "glowstone", "glowstone_dust",
                        "shroomlight", "blaze_rod", "breeze_rod", "ender_pearl", "ender_eye",
                        "dragon_egg", "dragon_head", "nether_star", "magma_block", "magma_cream",
                        "quartz", "quartz_block", "quartz_bricks", "quartz_pillar",
                        "chiseled_quartz_block", "smooth_quartz", "respawn_anchor",
                        "ancient_debris", "netherite_scrap", "netherite_ingot", "netherite_block",
                        "wither_skeleton_skull", "echo_shard", "sculk", "sculk_catalyst",
                        "sculk_shrieker", "sculk_vein", "heavy_core", "trial_key",
                        "ominous_trial_key")) {
            return "Nether et End";
        }
        if (s.endsWith("_ore") || s.startsWith("raw_") || s.endsWith("_ingot")
                || s.endsWith("_nugget")
                || eq(s, "coal", "charcoal", "diamond", "emerald", "lapis_lazuli", "amethyst_shard",
                        "coal_block", "iron_block", "gold_block", "diamond_block", "emerald_block",
                        "copper_block", "lapis_block", "redstone_block", "amethyst_block",
                        "raw_iron_block", "raw_gold_block", "raw_copper_block")) {
            return "Minerais";
        }
        if (s.endsWith("_log") || s.endsWith("_wood") || s.endsWith("_planks")
                || s.endsWith("_stem") || s.endsWith("_hyphae") || s.startsWith("stripped_")
                || eq(s, "stick", "bamboo_block", "bamboo_mosaic")) {
            return "Bois";
        }
        if (s.endsWith("_leaves") || s.endsWith("_coral") || s.endsWith("_coral_fan")
                || s.endsWith("_coral_block") || s.endsWith("_mushroom")
                || s.endsWith("_mushroom_block") || s.endsWith("_tulip") || s.endsWith("_orchid")
                || s.endsWith("_bush") || s.endsWith("_fern") || s.endsWith("_grass")
                || s.endsWith("_roots") || s.endsWith("_vines") || s.endsWith("_moss")
                || s.endsWith("_petals")
                || eq(s, "dandelion", "poppy", "allium", "azure_bluet", "oxeye_daisy", "cornflower",
                        "lily_of_the_valley", "wither_rose", "torchflower", "pitcher_plant",
                        "sunflower", "lilac", "rose_bush", "peony", "wildflowers", "open_eyeblossom",
                        "closed_eyeblossom", "cactus", "cactus_flower", "vine", "lily_pad",
                        "glow_lichen", "seagrass", "sea_pickle", "moss_block", "moss_carpet",
                        "pale_moss_block", "azalea", "flowering_azalea", "big_dripleaf",
                        "small_dripleaf", "spore_blossom", "hanging_roots", "mangrove_roots",
                        "muddy_mangrove_roots", "mushroom_stem", "cobweb", "dead_bush", "bush",
                        "firefly_bush")) {
            return "Plantes";
        }
        // Les couleurs sont sorties de la décoration : à elles seules elles pesaient deux cents
        // articles, et un rayon de deux cents lignes ne se parcourt pas — il se subit.
        if (s.endsWith("_wool") || s.endsWith("_carpet") || s.endsWith("_bed")
                || s.endsWith("_banner") || s.endsWith("_dye") || s.endsWith("_terracotta")
                || s.endsWith("_concrete") || s.endsWith("_concrete_powder")
                || s.endsWith("_stained_glass") || s.endsWith("_stained_glass_pane")
                || s.contains("glazed_terracotta")
                || eq(s, "white_dye", "ink_sac", "glow_ink_sac", "bone_meal")) {
            return "Couleurs";
        }
        if (s.endsWith("_candle") || s.endsWith("_lantern") || s.endsWith("_glass")
                || s.endsWith("_glass_pane") || s.endsWith("_sign") || s.endsWith("_hanging_sign")
                || s.endsWith("_head") || s.endsWith("_skull") || s.endsWith("_pottery_sherd")
                || s.endsWith("_banner_pattern") || s.endsWith("_smithing_template")
                || eq(s, "painting", "item_frame", "glow_item_frame", "armor_stand",
                        "decorated_pot", "candle", "torch", "soul_torch", "lantern", "soul_lantern",
                        "chain", "end_rod", "conduit", "sea_lantern", "shulker_box", "snow",
                        "snow_block", "snowball", "ice", "packed_ice", "blue_ice", "sponge",
                        "wet_sponge", "amethyst_cluster", "small_amethyst_bud",
                        "medium_amethyst_bud", "large_amethyst_bud", "ochre_froglight",
                        "verdant_froglight", "pearlescent_froglight")) {
            return "Decoration";
        }
        if (s.endsWith("_slab") || s.endsWith("_stairs") || s.endsWith("_wall")
                || s.endsWith("_fence") || s.endsWith("_fence_gate") || s.endsWith("_door")
                || s.endsWith("_trapdoor") || s.endsWith("_bricks") || s.endsWith("_brick")
                || s.endsWith("_tiles") || s.endsWith("_pillar") || s.endsWith("_bars")
                || s.endsWith("_pane") || s.endsWith("_grate") || s.contains("cut_")
                || s.contains("polished_") || s.contains("chiseled_") || s.contains("smooth_")
                || s.contains("cracked_") || s.contains("mossy_")) {
            return "Construction";
        }
        return "Pierres et terres";
    }

    private static boolean eq(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.equals(candidate)) {
                return true;
            }
        }
        return false;
    }
}
