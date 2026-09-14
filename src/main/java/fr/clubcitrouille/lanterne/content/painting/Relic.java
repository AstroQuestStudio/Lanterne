package fr.clubcitrouille.lanterne.content.painting;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La relique : ce qu'on hérite d'Immersive Paintings quand on le remplace.
 *
 * <h2>Le problème que ce fichier résout</h2>
 *
 * <p>Un joueur qui installe Lanterne à la place d'Immersive Paintings a déjà, sur son disque, des
 * dizaines d'images qu'il a lui-même importées, et sur ses murs, des dizaines de tableaux posés. Un
 * mod de remplacement qui les fait disparaître n'est pas un remplacement, c'est une perte. Ce
 * fichier existe pour que <b>rien ne soit perdu</b>, et pour que la reprise se fasse sans que le
 * joueur ait à faire quoi que ce soit.
 *
 * <p>Le transfert se fait en deux morceaux, indépendants l'un de l'autre et qui échouent
 * séparément :
 *
 * <ol>
 *   <li><b>La bibliothèque d'images.</b> Immersive Paintings range ses images dans un dossier
 *       {@code immersive_paintings_cache/} placé à côté du jeu, organisé par identifiant d'auteur.
 *       Ce dossier survit à la désinstallation du mod — c'est notre chance. Chaque image y est
 *       relue, repassée au {@link Mill}, et rejoint le catalogue de Lanterne comme si le joueur
 *       venait de la déposer lui-même.</li>
 *   <li><b>Les tableaux déjà posés.</b> Ils existent dans le monde sous la forme d'entités
 *       {@code immersive_paintings:painting}. Quand le mod disparaît, cet identifiant ne désigne
 *       plus rien et le jeu <em>supprime silencieusement</em> ces entités au chargement du chunk.
 *       On l'empêche par un <b>alias de registre</b> : {@code immersive_paintings:painting} est
 *       déclaré comme un autre nom de {@code lanterne:tableau}. Voir {@link Easel#register}.</li>
 * </ol>
 *
 * <h2>Pourquoi l'alias est sans danger même si l'autre mod est là</h2>
 *
 * <p>Les alias de NeoForge ne sont consultés <b>que si le nom réel est absent</b> du registre
 * ({@code BaseMappedRegistry.resolve} teste {@code containsKey} en premier). Tant qu'Immersive
 * Paintings est installé, il enregistre son propre type d'entité, l'alias dort, et les deux mods
 * cohabitent sans se marcher dessus. Le jour où le joueur le retire, l'alias prend le relais tout
 * seul. Il n'y a rien à activer et rien à désactiver.
 *
 * <h2>Le pont entre les deux nommages</h2>
 *
 * <p>Un tableau d'Immersive Paintings désigne son image par un « motif » de la forme
 * {@code immersive_paintings:<identifiant-auteur>/<empreinte>}. Lanterne désigne la sienne par une
 * empreinte SHA-256 de l'image <em>ré-échantillonnée</em>, qui n'a aucune raison de coïncider. La
 * table {@link #BRIDGE} fait le lien, et elle est construite au moment de l'import : quand on lit
 * {@code immersive_paintings_cache/<auteur>/<empreinte>.png}, on sait à la fois d'où il vient et ce
 * qu'il devient.
 *
 * <p>Cette table est <b>écrite sur disque</b>. Sans cela, elle serait reconstruite à chaque
 * démarrage, ce qui suppose que le dossier d'Immersive Paintings soit encore là — or le joueur le
 * supprimera tôt ou tard, et il en a le droit. Une fois le pont écrit, la migration reste possible
 * même si la source a disparu.
 *
 * <h2>Ce qui n'est pas repris, et pourquoi</h2>
 *
 * <ul>
 *   <li><b>Les cadres et les matières.</b> Immersive Paintings entoure ses tableaux de cadres
 *       modélisés en trois dimensions, chargés depuis des fichiers {@code .obj} et reconstruits
 *       sommet par sommet à chaque image. C'est précisément le genre de dépense que ce paquet
 *       refuse. Les tableaux migrés perdent leur cadre et gardent leur image.</li>
 *   <li><b>Les graffitis.</b> Ce sont des entités d'un autre type, posées à plat sur le sol ou le
 *       plafond, alors qu'une toile s'accroche à un mur. Les faire passer pour des tableaux
 *       donnerait des objets tournés n'importe comment. Ils ne sont donc pas aliasés — ils restent
 *       dans le monde tant qu'Immersive Paintings est installé, et disparaissent avec lui.</li>
 *   <li><b>Les réductions.</b> Immersive Paintings garde côté client jusqu'à cinq copies de chaque
 *       image — pleine, moitié, quart, huitième, vignette — parce que son niveau de détail est fait
 *       à la main. Sur le dossier qui a servi à écrire ce fichier, cela donnait quatre-vingts
 *       fichiers pour <b>vingt</b> tableaux. Seule la copie pleine est reprise : les autres
 *       deviendraient autant de faux tableaux au catalogue, et les mipmaps de la mosaïque les
 *       rendent inutiles. Voir {@link #REDUCTIONS}.</li>
 * </ul>
 */
public final class Relic {
    /** Le domaine de nommage du mod dont on hérite. */
    public static final String FOREIGN = "immersive_paintings";

    /** Le dossier qu'Immersive Paintings crée à côté du jeu. */
    public static final String FOREIGN_CACHE = "immersive_paintings_cache";

    /**
     * Les suffixes des copies réduites, à ne pas reprendre.
     *
     * <p>Ce sont les noms de l'énumération {@code Painting.Size} du mod d'origine, en minuscules :
     * son cache écrit {@code <empreinte>_<taille>.png} pour tout ce qui n'est pas la copie pleine,
     * laquelle n'a pas de suffixe. La liste est écrite en dur plutôt que devinée par une expression
     * régulière, parce qu'une expression trop large écarterait un tableau que le joueur aurait
     * lui-même nommé {@code paysage_half}.
     */
    private static final String[] REDUCTIONS =
            {"_thumbnail", "_half", "_quarter", "_eighth", "_nsfw", "_full"};

    /**
     * Le pont : motif étranger vers ce que Lanterne en a fait.
     *
     * <p>Concurrente parce qu'elle est écrite par le fil d'import et lue par le fil du serveur au
     * chargement d'un chunk. Les deux peuvent se produire en même temps, et un tableau dont
     * l'empreinte n'est pas encore connue doit obtenir une réponse — fût-elle « pas encore » —
     * plutôt qu'une exception.
     */
    private static final Map<String, Heir> BRIDGE = new ConcurrentHashMap<>();

    private Relic() {}

    /**
     * Ce qu'un motif étranger est devenu.
     *
     * @param hash l'empreinte Lanterne de la même image.
     * @param width largeur en blocs telle qu'Immersive Paintings la connaissait.
     * @param height hauteur en blocs, idem.
     */
    public record Heir(String hash, int width, int height) {}

    /** Ce qu'est devenu ce motif, ou {@code null} si on ne le sait pas (encore). */
    public static Heir lookup(String motive) {
        return BRIDGE.get(normalise(motive));
    }

    public static int bridged() {
        return BRIDGE.size();
    }

    /**
     * Un motif écrit sans son domaine, pour que la table réponde aux deux écritures.
     *
     * <p>Le mod étranger écrit {@code immersive_paintings:uuid/empreinte} dans son NBT, mais son
     * dossier de cache ne connaît que {@code uuid/empreinte}. Normaliser à la lecture évite d'avoir
     * à choisir laquelle des deux formes est « la bonne ».
     */
    private static String normalise(String motive) {
        if (motive == null) {
            return "";
        }
        int colon = motive.indexOf(':');
        return colon < 0 ? motive : motive.substring(colon + 1);
    }

    // --- Import de la bibliothèque -----------------------------------------

    /** Un fichier à reprendre, avec ce que l'on sait déjà de lui. */
    public record Bequest(Path file, String motive, String name, int width, int height) {}

    /**
     * Dresse la liste des images à reprendre.
     *
     * <p>Aucun décodage ici : la liste ne contient que des chemins et des métadonnées. Le travail
     * coûteux est fait par l'appelant, sur un fil de fond, car repasser quatre-vingts images au
     * moulin prend plusieurs secondes et le fil du serveur n'en a pas à donner.
     *
     * @param gameDir le dossier du jeu, où se trouve {@code immersive_paintings_cache/}.
     * @param server le serveur, pour retrouver les métadonnées dans la sauvegarde. Peut être
     *     {@code null} : on se rabat alors sur les proportions de l'image.
     */
    public static List<Bequest> inventory(Path gameDir, MinecraftServer server) {
        Path root = gameDir.resolve(FOREIGN_CACHE);
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        Map<String, Lore> lore = readLore(server);
        List<Bequest> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, 3)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".png") || reduced(name)) {
                    return;
                }
                Path relative = root.relativize(file);
                // Le motif est le chemin relatif sans l'extension, séparateurs normalisés : c'est
                // exactement la forme que le mod étranger écrit dans le NBT de ses entités.
                String motive = relative.toString().replace('\\', '/');
                motive = motive.substring(0, motive.length() - 4);
                Lore known = lore.get(motive);
                found.add(new Bequest(file, motive,
                        known != null ? known.name() : friendly(motive),
                        known != null ? known.width() : 0,
                        known != null ? known.height() : 0));
            });
        } catch (IOException problem) {
            Lanterne.LOG.warn("[ATELIER] le dossier hérité « {} » est illisible : {}",
                    root, problem.getMessage());
            return List.of();
        }
        return found;
    }

    /** Ce fichier est-il une copie réduite plutôt que l'image elle-même ? */
    private static boolean reduced(String lowerCaseName) {
        String stem = lowerCaseName.substring(0, lowerCaseName.length() - 4);
        for (String suffix : REDUCTIONS) {
            if (stem.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Inscrit au pont ce qu'une image héritée est devenue. */
    public static void bridge(String motive, String hash, int width, int height) {
        BRIDGE.put(normalise(motive), new Heir(hash, width, height));
    }

    // --- Persistance du pont ----------------------------------------------

    /**
     * Relit le pont écrit lors d'un démarrage précédent.
     *
     * <p>Un format lisible à l'œil — une ligne par motif — plutôt que du binaire ou du JSON : ce
     * fichier est le seul endroit où un joueur peut comprendre pourquoi tel tableau est devenu tel
     * autre, et il doit pouvoir le lire sans outil.
     */
    public static void recall(Path file) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException problem) {
            Lanterne.LOG.warn("[ATELIER] pont d'héritage illisible : {}", problem.getMessage());
            return;
        }
        for (String motive : properties.stringPropertyNames()) {
            String[] parts = properties.getProperty(motive).split(":");
            if (parts.length != 3 || !Mill.plausible(parts[0])) {
                continue;
            }
            try {
                BRIDGE.put(motive, new Heir(parts[0],
                        Math.clamp(Integer.parseInt(parts[1]), 1, 16),
                        Math.clamp(Integer.parseInt(parts[2]), 1, 16)));
            } catch (NumberFormatException ignored) {
                // Une ligne abîmée n'est pas une raison d'abandonner les autres.
            }
        }
        if (!BRIDGE.isEmpty()) {
            Lanterne.LOG.info("[ATELIER] pont d'héritage relu : {} motif(s) d'Immersive Paintings.",
                    BRIDGE.size());
        }
    }

    /** Écrit le pont, pour qu'il survive à la disparition du dossier d'origine. */
    public static void inscribe(Path file) {
        if (BRIDGE.isEmpty()) {
            return;
        }
        Properties properties = new Properties();
        BRIDGE.forEach((motive, heir) ->
                properties.setProperty(motive, heir.hash() + ":" + heir.width() + ":" + heir.height()));
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "Lanterne - pont d'heritage Immersive Paintings -> tableaux."
                        + " Ne pas editer a la main sauf si tu sais ce que tu fais.");
            }
        } catch (IOException problem) {
            Lanterne.LOG.warn("[ATELIER] pont d'héritage non écrit : {}", problem.getMessage());
        }
    }

    // --- Métadonnées de la sauvegarde --------------------------------------

    /** Ce que la sauvegarde d'Immersive Paintings sait d'une image. */
    private record Lore(String name, int width, int height) {}

    /**
     * Relit {@code data/immersive_paintings.dat} pour retrouver noms et tailles.
     *
     * <h2>Pourquoi lire le fichier d'un autre mod plutôt que de deviner</h2>
     *
     * <p>Les proportions d'une image donnent une taille en blocs plausible — et pas forcément celle
     * que le joueur avait choisie. Un tableau qu'il avait posé en quatre sur trois deviendrait
     * cinq sur quatre, ne tiendrait plus dans son cadre de bois, et la migration se verrait. La
     * taille exacte est dans ce fichier ; elle vaut la centaine de lignes qu'il faut pour aller la
     * chercher.
     *
     * <p>La lecture est faite avec le lecteur NBT du jeu, sans aucune dépendance de compilation
     * envers le mod étranger : on ne connaît que des noms de champs, et un champ manquant se solde
     * par une valeur par défaut. Si le format change ou si le fichier est absent, on retombe
     * proprement sur les proportions.
     */
    private static Map<String, Lore> readLore(MinecraftServer server) {
        if (server == null) {
            return Map.of();
        }
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FOREIGN + ".dat");
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (IOException problem) {
            Lanterne.LOG.warn("[ATELIER] sauvegarde héritée illisible : {}", problem.getMessage());
            return Map.of();
        }
        // Les données d'un « SavedData » sont toujours sous une clé « data ». Si elle manque, on
        // tente la racine : certaines versions ont écrit à plat, et un essai de plus ne coûte rien.
        CompoundTag data = root.getCompound("data").orElse(root);
        CompoundTag paintings = data.getCompound("paintings").orElse(null);
        if (paintings == null) {
            return Map.of();
        }

        Map<String, Lore> lore = new HashMap<>();
        for (String key : paintings.keySet()) {
            CompoundTag entry = paintings.getCompound(key).orElse(null);
            if (entry == null) {
                continue;
            }
            int width = Math.clamp(entry.getIntOr("width", 0), 0, 16);
            int height = Math.clamp(entry.getIntOr("height", 0), 0, 16);
            String name = entry.getStringOr("name", "");
            lore.put(normalise(key), new Lore(name.isEmpty() ? friendly(key) : name, width, height));
        }
        Lanterne.LOG.info("[ATELIER] sauvegarde héritée lue : {} tableau(x) décrit(s).", lore.size());
        return lore;
    }

    /** Un nom présentable quand on n'en a pas de vrai : la fin du chemin, tronquée. */
    private static String friendly(String motive) {
        String tail = motive.substring(motive.lastIndexOf('/') + 1);
        if (tail.length() > 12) {
            tail = tail.substring(0, 12);
        }
        return "hérité " + tail;
    }
}
