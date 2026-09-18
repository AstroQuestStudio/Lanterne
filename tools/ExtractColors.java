import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Outil ponctuel, pas partie du mod : lit les textures réelles du jar client Minecraft
 * et calcule une couleur moyenne par bloc (en ignorant les pixels transparents), pour
 * nourrir un module de carte 3D avec des couleurs authentiques plutôt qu'inventées.
 *
 * Usage: java ExtractColors.java <chemin-jar-client> <chemin-sortie-json>
 */
public class ExtractColors {
    // bloc -> chemin(s) de texture relatifs à assets/minecraft/textures/block/
    // Un bloc avec plusieurs faces (ex: herbe) liste top d'abord (c'est la face qui compte pour une carte vue du dessus).
    static final Map<String, String[]> MAP = new LinkedHashMap<>();
    static {
        // Terrain naturel
        MAP.put("grass_block", new String[]{"grass_block_top", "short_grass"});
        MAP.put("dirt", new String[]{"dirt"});
        MAP.put("coarse_dirt", new String[]{"coarse_dirt"});
        MAP.put("podzol", new String[]{"podzol_top"});
        MAP.put("mycelium", new String[]{"mycelium_top"});
        MAP.put("stone", new String[]{"stone"});
        MAP.put("granite", new String[]{"granite"});
        MAP.put("diorite", new String[]{"diorite"});
        MAP.put("andesite", new String[]{"andesite"});
        MAP.put("deepslate", new String[]{"deepslate_top"});
        MAP.put("cobblestone", new String[]{"cobblestone"});
        MAP.put("mossy_cobblestone", new String[]{"mossy_cobblestone"});
        MAP.put("sand", new String[]{"sand"});
        MAP.put("red_sand", new String[]{"red_sand"});
        MAP.put("sandstone", new String[]{"sandstone_top"});
        MAP.put("red_sandstone", new String[]{"red_sandstone_top"});
        MAP.put("gravel", new String[]{"gravel"});
        MAP.put("clay", new String[]{"clay"});
        MAP.put("snow", new String[]{"snow"});
        MAP.put("snow_block", new String[]{"snow"});
        MAP.put("ice", new String[]{"ice"});
        MAP.put("packed_ice", new String[]{"packed_ice"});
        MAP.put("blue_ice", new String[]{"blue_ice"});
        MAP.put("water", new String[]{"water_still"});
        MAP.put("lava", new String[]{"lava_still"});
        MAP.put("obsidian", new String[]{"obsidian"});
        MAP.put("bedrock", new String[]{"bedrock"});
        MAP.put("moss_block", new String[]{"moss_block"});
        MAP.put("mud", new String[]{"mud"});
        MAP.put("muddy_mangrove_roots", new String[]{"muddy_mangrove_roots_top"});
        MAP.put("calcite", new String[]{"calcite"});
        MAP.put("tuff", new String[]{"tuff"});
        MAP.put("dripstone_block", new String[]{"dripstone_block"});
        MAP.put("rooted_dirt", new String[]{"rooted_dirt"});
        MAP.put("terracotta", new String[]{"terracotta"});
        MAP.put("white_terracotta", new String[]{"white_terracotta"});
        MAP.put("orange_terracotta", new String[]{"orange_terracotta"});
        MAP.put("yellow_terracotta", new String[]{"yellow_terracotta"});
        MAP.put("red_terracotta", new String[]{"red_terracotta"});
        MAP.put("brown_terracotta", new String[]{"brown_terracotta"});
        // Nether
        MAP.put("netherrack", new String[]{"netherrack"});
        MAP.put("soul_sand", new String[]{"soul_sand"});
        MAP.put("soul_soil", new String[]{"soul_soil"});
        MAP.put("crimson_nylium", new String[]{"crimson_nylium"});
        MAP.put("warped_nylium", new String[]{"warped_nylium"});
        MAP.put("basalt", new String[]{"basalt_top"});
        MAP.put("blackstone", new String[]{"blackstone_top"});
        MAP.put("magma_block", new String[]{"magma"});
        MAP.put("glowstone", new String[]{"glowstone"});
        MAP.put("nether_gold_ore", new String[]{"nether_gold_ore"});
        MAP.put("nether_quartz_ore", new String[]{"nether_quartz_ore"});
        MAP.put("crimson_stem", new String[]{"crimson_stem"});
        MAP.put("warped_stem", new String[]{"warped_stem"});
        // End
        MAP.put("end_stone", new String[]{"end_stone"});
        MAP.put("end_stone_bricks", new String[]{"end_stone_bricks"});
        MAP.put("purpur_block", new String[]{"purpur_block"});
        MAP.put("chorus_plant", new String[]{"chorus_plant"});
        MAP.put("chorus_flower", new String[]{"chorus_flower"});
        // Bois / feuillages (troncs -> texture top pour vue de dessus, sinon planches)
        for (String wood : new String[]{"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo"}) {
            MAP.put(wood + "_log", new String[]{wood + "_log_top", wood + "_log"});
            MAP.put(wood + "_leaves", new String[]{wood + "_leaves"});
            MAP.put(wood + "_planks", new String[]{wood + "_planks"});
        }
        MAP.put("crimson_planks", new String[]{"crimson_planks"});
        MAP.put("warped_planks", new String[]{"warped_planks"});
        MAP.put("azalea_leaves", new String[]{"azalea_leaves"});
        MAP.put("flowering_azalea_leaves", new String[]{"flowering_azalea_leaves"});
        // Minéraux (visibles en surface dans les montagnes / grottes ouvertes)
        MAP.put("coal_ore", new String[]{"coal_ore"});
        MAP.put("iron_ore", new String[]{"iron_ore"});
        MAP.put("copper_ore", new String[]{"copper_ore"});
        MAP.put("gold_ore", new String[]{"gold_ore"});
        MAP.put("diamond_ore", new String[]{"diamond_ore"});
        MAP.put("emerald_ore", new String[]{"emerald_ore"});
        MAP.put("lapis_ore", new String[]{"lapis_ore"});
        MAP.put("redstone_ore", new String[]{"redstone_ore"});
        MAP.put("amethyst_block", new String[]{"amethyst_block"});
        MAP.put("copper_block", new String[]{"copper_block"});
        // Constructions courantes des joueurs
        MAP.put("bricks", new String[]{"bricks"});
        MAP.put("smooth_stone", new String[]{"smooth_stone"});
        MAP.put("stone_bricks", new String[]{"stone_bricks"});
        MAP.put("glass", new String[]{"glass"});
        for (String c : new String[]{"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"}) {
            MAP.put(c + "_wool", new String[]{c + "_wool"});
            MAP.put(c + "_concrete", new String[]{c + "_concrete"});
            MAP.put(c + "_stained_glass", new String[]{c + "_stained_glass"});
        }
        MAP.put("farmland", new String[]{"farmland"});
        MAP.put("dirt_path", new String[]{"dirt_path_top"});
    }

    // Vanilla teinte certains blocs au rendu (herbe, feuillage, eau) au lieu de peindre la couleur
    // dans la texture, qui reste grise/neutre — sans ça la carte afficherait de l'herbe grise.
    // Valeurs par défaut vanilla (biome plaines, sans carte de couleur réelle par biome).
    static final Map<String, int[]> TINT = new HashMap<>();
    static {
        TINT.put("GRASS", new int[]{145, 189, 89});
        TINT.put("FOLIAGE", new int[]{119, 171, 47});
        TINT.put("WATER", new int[]{63, 118, 228});
    }

    static int[] tintFor(String block) {
        if (block.equals("grass_block")) return TINT.get("GRASS");
        if (block.equals("water")) return TINT.get("WATER");
        if (block.endsWith("_leaves")) return TINT.get("FOLIAGE");
        return null;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("Usage: ExtractColors <jar> <out.json>"); System.exit(1); }
        String jarPath = args[0];
        String outPath = args[1];

        Map<String, BufferedImage> images = new HashMap<>();
        try (ZipFile zf = new ZipFile(jarPath)) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (name.startsWith("assets/minecraft/textures/block/") && name.endsWith(".png")) {
                    String key = name.substring("assets/minecraft/textures/block/".length(), name.length() - 4);
                    try (InputStream is = zf.getInputStream(e)) {
                        BufferedImage img = ImageIO.read(is);
                        if (img != null) images.put(key, img);
                    } catch (Exception ex) { /* ignore image individuelle illisible */ }
                }
            }
        }
        System.out.println("Textures lues: " + images.size());

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        int done = 0, missing = 0;
        List<String> keys = new ArrayList<>(MAP.keySet());
        for (int i = 0; i < keys.size(); i++) {
            String block = keys.get(i);
            String[] candidates = MAP.get(block);
            int[] rgb = null;
            String usedTex = null;
            for (String cand : candidates) {
                BufferedImage img = images.get(cand);
                if (img != null) { rgb = averageColor(img); usedTex = cand; break; }
            }
            if (rgb == null) { missing++; System.err.println("MANQUANT: " + block + " (" + String.join(",", candidates) + ")"); continue; }
            int[] tint = tintFor(block);
            if (tint != null) {
                rgb = new int[]{
                        (rgb[0] * tint[0]) / 255,
                        (rgb[1] * tint[1]) / 255,
                        (rgb[2] * tint[2]) / 255
                };
            }
            done++;
            json.append(String.format("  \"minecraft:%s\": [%d, %d, %d]", block, rgb[0], rgb[1], rgb[2]));
            json.append(i < keys.size() - 1 ? ",\n" : "\n");
        }
        json.append("}\n");
        Files.write(Paths.get(outPath), json.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("Ecrit " + done + " couleurs (" + missing + " manquantes) -> " + outPath);
    }

    static int[] averageColor(BufferedImage img) {
        long r = 0, g = 0, b = 0, n = 0;
        int w = img.getWidth(), h = Math.min(img.getHeight(), img.getWidth()); // ignore les frames d'animation sous la 1ere (water/lava)
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a < 16) continue;
                r += (argb >> 16) & 0xFF;
                g += (argb >> 8) & 0xFF;
                b += argb & 0xFF;
                n++;
            }
        }
        if (n == 0) return new int[]{128, 128, 128};
        return new int[]{(int) (r / n), (int) (g / n), (int) (b / n)};
    }
}
