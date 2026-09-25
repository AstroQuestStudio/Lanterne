package fr.clubcitrouille.lanterne.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le bouclier : un pack de ressources ne peut plus rendre transparents les blocs qui cachent les
 * minerais.
 *
 * <h2>Le vecteur, lu dans le bytecode du jar patché 26.3 (jamais supposé)</h2>
 *
 * <p>Dans ce moteur, la couche de rendu d'une face de bloc n'est plus une table codée en dur : c'est
 * {@code BakedQuad.MaterialInfo.of(...)} qui l'obtient par {@code ChunkSectionLayer.byTransparency(
 * Transparency)}, et cette {@code Transparency} est calculée par {@code SpriteContents} sur les
 * PIXELS de la texture. Remplacer {@code textures/block/stone.png} par une image transparente suffit
 * donc à faire passer toute la pierre en couche découpée/translucide — la roche devient invisible, les
 * filons et les grottes apparaissent. Aucun modèle à modifier, aucun code : un simple pack.
 *
 * <p>Trois vecteurs secondaires existent aussi, fermés ici pour la même raison : un modèle de bloc
 * vidé (ou un de ses parents : {@code block/cube_all}…), un {@code blockstates/*.json} qui pointe
 * ailleurs, et les sources d'atlas ({@code atlases/}) qui peuvent réassigner le sprite
 * {@code block/stone} vers une autre image ; plus les shaders du moteur ({@code shaders/}, tous
 * espaces de noms confondus, Sodium compris), qui peuvent jeter des fragments à volonté.
 *
 * <h2>La parade : un tri au chargement, jamais par image</h2>
 *
 * <p>Chaque pack NON fiable (tout ce qui n'est ni vanilla, ni un mod, ni Lanterne lui-même : les packs
 * du dossier {@code resourcepacks}, le pack imposé par un serveur, programmer art…) est enveloppé dans
 * un {@link BouclierPack} au moment où il est empilé dans le gestionnaire de ressources. Pour les
 * seuls chemins protégés, cette enveloppe répond « absent » — le jeu retombe alors sur la ressource
 * vanilla, exactement comme si le pack n'avait jamais défini ce fichier. Les textures protégées ne
 * sont refusées que si elles contiennent de la transparence : un pack cosmétique qui redessine la
 * pierre, opaque, reste affiché.
 *
 * <p>Coût : nul en jeu. Le tri n'agit qu'au (re)chargement des ressources, sur une soixantaine de
 * fichiers au plus par pack ; tout le reste passe par délégation directe.
 */
public final class Bouclier {
    private Bouclier() {}

    /** Blocs couvrants dont l'état de bloc est verrouillé sur la version vanilla/mod. */
    static final Set<String> BLOCKSTATES = Set.of(
            "stone", "deepslate", "cobbled_deepslate", "tuff", "granite", "diorite", "andesite",
            "calcite", "dripstone_block", "dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol",
            "mycelium", "mud", "clay", "gravel", "sand", "red_sand", "sandstone", "red_sandstone",
            "netherrack", "basalt", "smooth_basalt", "blackstone", "end_stone", "soul_sand", "soul_soil",
            "magma_block", "crimson_nylium", "warped_nylium", "bedrock", "terracotta", "white_terracotta",
            "orange_terracotta", "yellow_terracotta", "red_terracotta", "brown_terracotta",
            "light_gray_terracotta", "packed_ice", "snow_block", "moss_block", "cobblestone",
            "infested_stone", "infested_deepslate", "amethyst_block", "budding_amethyst", "smooth_stone",
            "obsidian");

    /**
     * Modèles (chemin sous {@code models/}) de ces blocs ET toute leur chaîne de parents, calculée
     * sur les vrais fichiers vanilla 26.3 : vider {@code block/cube_all} rendrait la pierre invisible
     * même avec {@code block/stone} intact.
     */
    static final Set<String> MODELS = Set.of(
            "block/amethyst_block", "block/andesite", "block/basalt", "block/bedrock",
            "block/bedrock_mirrored", "block/blackstone", "block/block", "block/brown_terracotta",
            "block/budding_amethyst", "block/calcite", "block/clay", "block/coarse_dirt",
            "block/cobbled_deepslate", "block/cobblestone", "block/crimson_nylium", "block/cube",
            "block/cube_all", "block/cube_bottom_top", "block/cube_column", "block/cube_column_mirrored",
            "block/cube_mirrored", "block/cube_mirrored_all", "block/deepslate", "block/deepslate_mirrored",
            "block/diorite", "block/dirt", "block/dripstone_block", "block/end_stone", "block/granite",
            "block/grass_block", "block/grass_block_snow", "block/gravel", "block/light_gray_terracotta",
            "block/magma_block", "block/moss_block", "block/mud", "block/mycelium", "block/netherrack",
            "block/obsidian", "block/orange_terracotta", "block/packed_ice", "block/podzol",
            "block/red_sand", "block/red_sandstone", "block/red_terracotta", "block/rooted_dirt",
            "block/sand", "block/sandstone", "block/smooth_basalt", "block/smooth_stone",
            "block/snow_block", "block/soul_sand", "block/soul_soil", "block/stone",
            "block/stone_mirrored", "block/terracotta", "block/tuff", "block/warped_nylium",
            "block/white_terracotta", "block/yellow_terracotta");

    /**
     * Textures (chemin sous {@code textures/}, sans extension) de ces blocs, toutes OPAQUES en vanilla
     * — vérifié pixel par pixel. {@code grass_block_side_overlay} et {@code ice} n'y figurent pas :
     * elles sont transparentes par conception, un pack a le droit de les redessiner ainsi.
     */
    static final Set<String> TEXTURES = Set.of(
            "block/amethyst_block", "block/andesite", "block/basalt_side", "block/basalt_top",
            "block/bedrock", "block/blackstone", "block/blackstone_top", "block/brown_terracotta",
            "block/budding_amethyst", "block/calcite", "block/clay", "block/coarse_dirt",
            "block/cobbled_deepslate", "block/cobblestone", "block/crimson_nylium",
            "block/crimson_nylium_side", "block/deepslate", "block/deepslate_top", "block/diorite",
            "block/dirt", "block/dripstone_block", "block/end_stone", "block/granite",
            "block/grass_block_side", "block/grass_block_snow", "block/grass_block_top", "block/gravel",
            "block/light_gray_terracotta", "block/magma", "block/moss_block", "block/mud",
            "block/mycelium_side", "block/mycelium_top", "block/netherrack", "block/obsidian",
            "block/orange_terracotta", "block/packed_ice", "block/podzol_side", "block/podzol_top",
            "block/red_sand", "block/red_sandstone", "block/red_sandstone_bottom",
            "block/red_sandstone_top", "block/red_terracotta", "block/rooted_dirt", "block/sand",
            "block/sandstone", "block/sandstone_bottom", "block/sandstone_top", "block/smooth_basalt",
            "block/smooth_stone", "block/snow", "block/soul_sand", "block/soul_soil", "block/stone",
            "block/terracotta", "block/tuff", "block/warped_nylium", "block/warped_nylium_side",
            "block/white_terracotta", "block/yellow_terracotta");

    /** Ce que le bouclier fait d'un chemin donné. */
    enum Verdict { LIBRE, CACHE, OPAQUE_REQUIS }

    /** Le serveur connecté impose-t-il le bouclier ? Posé par {@code client.BouclierClient}. */
    private static volatile boolean impose;

    /**
     * État effectivement appliqué au dernier (re)chargement des ressources. Le tri n'agit qu'au
     * chargement : changer le réglage demande un rechargement, que {@code client.BouclierClient}
     * déclenche lui-même quand un serveur impose le bouclier à un client qui l'avait coupé.
     */
    private static volatile boolean actif = true;

    /** Ce qui a été refusé, une fois par (pack, chemin) : pas de journal répété à chaque rechargement. */
    private static final Set<String> DEJA_DIT = ConcurrentHashMap.newKeySet();

    /** Packs dont au moins une ressource a été refusée au dernier chargement (pour le message joueur). */
    private static final Set<String> PACKS_REFUSES = ConcurrentHashMap.newKeySet();

    public static boolean actif() {
        return actif;
    }

    public static void imposer(boolean valeur) {
        impose = valeur;
    }

    /** Ce que le PROCHAIN chargement appliquera : imposé par le serveur, ou réglage local. */
    public static boolean voulu() {
        if (impose) {
            return true;
        }
        try {
            return ClientConfig.BOUCLIER.get();
        } catch (RuntimeException pasEncoreCharge) {
            return true; // dans le doute, protégé
        }
    }

    /** Appelé à chaque empilement de pack client : fige l'état appliqué pour ce chargement. */
    static void noteChargement() {
        actif = voulu();
    }

    public static Set<String> packsRefuses() {
        return Set.copyOf(PACKS_REFUSES);
    }

    /**
     * Enveloppe un pack au moment où il est empilé. Seuls les packs de ressources CLIENT et non fiables
     * sont concernés ; {@code null} (empilement « filtre seul ») est rendu tel quel.
     */
    public static PackResources envelopper(PackType type, PackResources pack) {
        if (type == PackType.CLIENT_RESOURCES) {
            noteChargement();
        }
        if (pack == null || type != PackType.CLIENT_RESOURCES || pack instanceof BouclierPack
                || fiable(pack)) {
            return pack;
        }
        return new BouclierPack(pack);
    }

    /**
     * Vanilla, les mods (NeoForge les nomme {@code mod_resources} ou {@code mod/<id>}) et les packs
     * générés par Lanterne. Un pack du dossier {@code resourcepacks} s'appelle {@code file/...} et ne
     * peut pas se faire passer pour l'un d'eux.
     */
    static boolean fiable(PackResources pack) {
        String id;
        try {
            id = pack.packId();
        } catch (RuntimeException illisible) {
            return false;
        }
        if (id == null) {
            return false;
        }
        return id.equals("vanilla") || id.equals("mod_resources") || id.startsWith("mod/")
                || id.startsWith("lanterne");
    }

    static Verdict verdict(Identifier id) {
        if (!actif) {
            return Verdict.LIBRE;
        }
        String path = id.getPath();
        if (path.startsWith("shaders/") || path.startsWith("atlases/")) {
            return Verdict.CACHE;
        }
        if (!"minecraft".equals(id.getNamespace())) {
            return Verdict.LIBRE;
        }
        if (path.startsWith("blockstates/") && path.endsWith(".json")) {
            String nom = path.substring("blockstates/".length(), path.length() - ".json".length());
            return BLOCKSTATES.contains(nom) ? Verdict.CACHE : Verdict.LIBRE;
        }
        if (path.startsWith("models/") && path.endsWith(".json")) {
            String nom = path.substring("models/".length(), path.length() - ".json".length());
            return MODELS.contains(nom) ? Verdict.CACHE : Verdict.LIBRE;
        }
        if (path.startsWith("textures/")) {
            if (path.endsWith(".png")) {
                String nom = path.substring("textures/".length(), path.length() - ".png".length());
                return TEXTURES.contains(nom) ? Verdict.OPAQUE_REQUIS : Verdict.LIBRE;
            }
            if (path.endsWith(".png.mcmeta")) {
                String nom = path.substring("textures/".length(), path.length() - ".png.mcmeta".length());
                return TEXTURES.contains(nom) ? Verdict.CACHE : Verdict.LIBRE;
            }
        }
        return Verdict.LIBRE;
    }

    static void refuse(PackResources pack, Identifier id, String raison) {
        String packId;
        try {
            packId = pack.packId();
        } catch (RuntimeException illisible) {
            packId = "?";
        }
        PACKS_REFUSES.add(packId);
        if (DEJA_DIT.add(packId + "|" + id)) {
            Lanterne.LOG.info("[BOUCLIER] pack « {} » : {} ignoré ({}) — la version vanilla est utilisée.",
                    packId, id, raison);
        }
    }

    static byte[] lire(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    // --- Opacité d'un PNG, sans aucune classe cliente (ce fichier est chargé des deux côtés) ------

    /**
     * {@code true} si chaque pixel de ce PNG est totalement opaque. Toute image que ce décodeur minimal
     * ne sait pas lire avec certitude (entrelacée, 16 bits, corrompue) est traitée comme NON opaque :
     * le doute retombe toujours sur la texture vanilla, jamais sur celle du pack.
     */
    static boolean opaque(byte[] png) {
        try {
            return opaqueOuFaux(png);
        } catch (RuntimeException | DataFormatException illisible) {
            return false;
        }
    }

    private static boolean opaqueOuFaux(byte[] d) throws DataFormatException {
        if (d.length < 8 || (d[0] & 0xFF) != 0x89 || d[1] != 'P' || d[2] != 'N' || d[3] != 'G') {
            return false;
        }
        int pos = 8;
        int width = 0;
        int height = 0;
        int depth = 0;
        int colorType = -1;
        int interlace = 0;
        boolean trns = false;
        byte[] trnsData = null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        while (pos + 8 <= d.length) {
            int len = ((d[pos] & 0xFF) << 24) | ((d[pos + 1] & 0xFF) << 16) | ((d[pos + 2] & 0xFF) << 8)
                    | (d[pos + 3] & 0xFF);
            String type = new String(d, pos + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int data = pos + 8;
            if (len < 0 || data + len > d.length) {
                return false;
            }
            switch (type) {
                case "IHDR" -> {
                    width = ((d[data] & 0xFF) << 24) | ((d[data + 1] & 0xFF) << 16)
                            | ((d[data + 2] & 0xFF) << 8) | (d[data + 3] & 0xFF);
                    height = ((d[data + 4] & 0xFF) << 24) | ((d[data + 5] & 0xFF) << 16)
                            | ((d[data + 6] & 0xFF) << 8) | (d[data + 7] & 0xFF);
                    depth = d[data + 8] & 0xFF;
                    colorType = d[data + 9] & 0xFF;
                    interlace = d[data + 12] & 0xFF;
                }
                case "tRNS" -> {
                    trns = true;
                    trnsData = java.util.Arrays.copyOfRange(d, data, data + len);
                }
                case "IDAT" -> idat.write(d, data, len);
                default -> { }
            }
            pos = data + len + 4;
        }
        if (width <= 0 || height <= 0 || width > 8192 || height > 65536 || interlace != 0) {
            return false;
        }
        switch (colorType) {
            case 0, 2 -> {
                return !trns; // niveaux de gris / RGB : opaques sauf couleur-clé transparente
            }
            case 3 -> {
                if (!trns) {
                    return true;
                }
                // Palette : une seule entrée tRNS < 255 suffit à rendre l'image non opaque si elle
                // est utilisée. Par prudence, toute entrée non opaque déclarée compte.
                for (byte a : trnsData) {
                    if ((a & 0xFF) != 255) {
                        return false;
                    }
                }
                return true;
            }
            case 4, 6 -> {
                if (depth != 8) {
                    return false;
                }
                int channels = colorType == 4 ? 2 : 4;
                return alphaPlein(idat.toByteArray(), width, height, channels);
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean alphaPlein(byte[] compressed, int width, int height, int bpp)
            throws DataFormatException {
        int stride = width * bpp;
        byte[] raw = new byte[(stride + 1) * height];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            int n = 0;
            while (n < raw.length && !inflater.finished()) {
                int got = inflater.inflate(raw, n, raw.length - n);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += got;
            }
            if (n != raw.length) {
                return false;
            }
        } finally {
            inflater.end();
        }
        byte[] prev = new byte[stride];
        byte[] line = new byte[stride];
        int i = 0;
        for (int y = 0; y < height; y++) {
            int filter = raw[i++] & 0xFF;
            System.arraycopy(raw, i, line, 0, stride);
            i += stride;
            for (int x = 0; x < stride; x++) {
                int a = x >= bpp ? line[x - bpp] & 0xFF : 0;
                int b = prev[x] & 0xFF;
                int c = x >= bpp ? prev[x - bpp] & 0xFF : 0;
                int v = line[x] & 0xFF;
                switch (filter) {
                    case 0 -> { }
                    case 1 -> v += a;
                    case 2 -> v += b;
                    case 3 -> v += (a + b) >> 1;
                    case 4 -> {
                        int p = a + b - c;
                        int pa = Math.abs(p - a);
                        int pb = Math.abs(p - b);
                        int pc = Math.abs(p - c);
                        v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
                    }
                    default -> {
                        return false;
                    }
                }
                line[x] = (byte) v;
            }
            for (int x = bpp - 1; x < stride; x += bpp) {
                if ((line[x] & 0xFF) != 255) {
                    return false;
                }
            }
            byte[] t = prev;
            prev = line;
            line = t;
        }
        return true;
    }
}
