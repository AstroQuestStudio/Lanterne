package fr.clubcitrouille.lanterne.chantier;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Les modèles et textures de blocs, lus dans un jar client Minecraft (le serveur n'a pas les assets).
 *
 * <p>Même chemin que le client, en plus court : {@code blockstates/<bloc>.json} (variantes ou
 * multipart, {@code OR}/{@code AND} compris) → modèles (héritage {@code parent}, textures {@code #ref},
 * éléments {@code from/to}, rotation d'élément avec {@code rescale}, uv, rotation d'uv, {@code cullface},
 * {@code tintindex}) → rotations x/y de variante, {@code uvlock}. Les blocs des mods sont cherchés dans
 * les jars de {@code mods/} (leurs assets y sont). Ce qui manque retombe sur la forme de collision du
 * bloc, texturée par sa particule — ou sur sa couleur de carte sans jar du tout.
 *
 * <p>Tout est gardé en mémoire d'un rendu à l'autre (quelques Mo) : seul le premier rendu paie la
 * lecture du jar. Un seul fil de rendu ({@link Rendu3d#FIL}) : pas de concurrence à gérer.
 */
final class Modeles {
    private static @Nullable Modeles courant;

    private final Path jar;
    private final long date;
    private final ZipFile client;
    private @Nullable Map<String, ZipFile> espaces;
    private final Map<String, Optional<JsonObject>> json = new HashMap<>();
    private final Map<String, Optional<Png.Image>> textures = new HashMap<>();
    private final IdentityHashMap<BlockState, Forme> formes = new IdentityHashMap<>();
    /** Blocs sans modèle exploitable (repli), pour le dire dans la réponse. */
    final Set<String> replis = new TreeSet<>();

    private Modeles(Path jar) throws IOException {
        this.jar = jar;
        this.date = Files.getLastModifiedTime(jar).toMillis();
        this.client = new ZipFile(jar.toFile());
    }

    /** Le magasin du jar donné, gardé tant que le jar ne change pas ; {@code null} s'il est illisible. */
    static synchronized @Nullable Modeles pour(Path jar) {
        try {
            if (courant != null && courant.jar.equals(jar) && courant.date == Files.getLastModifiedTime(jar).toMillis()) {
                return courant;
            }
            if (courant != null) {
                courant.client.close();
            }
            courant = new Modeles(jar);
            return courant;
        } catch (IOException e) {
            Lanterne.LOG.warn("[CHANTIER] jar client illisible pour le rendu 3D ({}) : {}", jar, e.toString());
            courant = null;
            return null;
        }
    }

    Path jar() {
        return jar;
    }

    int texturesChargees() {
        return (int) textures.values().stream().filter(Optional::isPresent).count();
    }

    // ------------------------------------------------------------------------------------------------
    // Fichiers

    private @Nullable ZipFile source(String ns, String chemin) {
        if (client.getEntry("assets/" + ns + "/" + chemin) != null) {
            return client;
        }
        if (espaces == null) {
            espaces = indexerMods();
        }
        return espaces.get(ns);
    }

    /** Espace de noms → jar de mod qui porte ses assets, en une passe sur {@code mods/}. */
    private static Map<String, ZipFile> indexerMods() {
        Map<String, ZipFile> m = new HashMap<>();
        Path mods = Path.of("mods");
        if (!Files.isDirectory(mods)) {
            return m;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.jar")) {
            for (Path p : ds) {
                try {
                    ZipFile z = new ZipFile(p.toFile());
                    boolean garde = false;
                    for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
                        String n = e.nextElement().getName();
                        if (n.startsWith("assets/") && n.indexOf('/', 7) > 7) {
                            String ns = n.substring(7, n.indexOf('/', 7));
                            if (!ns.equals("minecraft") && !m.containsKey(ns)) {
                                m.put(ns, z);
                                garde = true;
                            }
                        }
                    }
                    if (!garde) {
                        z.close();
                    }
                } catch (IOException ignored) {
                    // jar illisible : ses blocs retomberont sur leur couleur de carte
                }
            }
        } catch (IOException ignored) {
            // pas de mods lisibles
        }
        return m;
    }

    private @Nullable JsonObject lireJson(String ns, String chemin) {
        return json.computeIfAbsent(ns + ":" + chemin, k -> {
            ZipFile z = source(ns, chemin);
            if (z == null) {
                return Optional.empty();
            }
            try (InputStream in = z.getInputStream(z.getEntry("assets/" + ns + "/" + chemin));
                 Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement e = JsonParser.parseReader(r);
                return e.isJsonObject() ? Optional.of(e.getAsJsonObject()) : Optional.empty();
            } catch (Exception e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** Texture {@code ns:chemin} (sans « textures/ » ni « .png ») ; première image d'une animation. */
    Png.@Nullable Image texture(String id) {
        return textures.computeIfAbsent(id, k -> {
            String[] r = separer(k);
            String chemin = "textures/" + r[1] + ".png";
            ZipFile z = source(r[0], chemin);
            if (z == null) {
                return Optional.empty();
            }
            try (InputStream in = z.getInputStream(z.getEntry("assets/" + r[0] + "/" + chemin))) {
                Png.Image i = Png.lire(in.readAllBytes());
                if (i == null) {
                    return Optional.empty();
                }
                if (i.h() > i.w() && i.h() % i.w() == 0) {
                    // Bande d'animation (eau, lave, feu…) : la première image suffit.
                    int[] a = new int[i.w() * i.w()];
                    System.arraycopy(i.argb(), 0, a, 0, a.length);
                    i = new Png.Image(i.w(), i.w(), a);
                }
                return Optional.of(i);
            } catch (Exception e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    private static String[] separer(String id) {
        int i = id.indexOf(':');
        return i < 0 ? new String[] {"minecraft", id} : new String[] {id.substring(0, i), id.substring(i + 1)};
    }

    // ------------------------------------------------------------------------------------------------
    // États → formes

    /** Une application de modèle choisie par le blockstate. */
    private record Application(String modele, int x, int y, boolean uvlock) {}

    private static final Pattern PROPRIETES = Pattern.compile("\\[(.*)]");

    Forme forme(BlockState s, int carte) {
        Forme f = formes.get(s);
        if (f == null) {
            f = construire(s, carte, this);
            formes.put(s, f);
        }
        return f;
    }

    /** Sans jar : chaque bloc est sa forme de collision, en couleur de carte. */
    static Forme sansTextures(BlockState s, int carte) {
        return construire(s, carte, null);
    }

    private static Forme construire(BlockState s, int carte, @Nullable Modeles m) {
        FluidState fl = s.getFluidState();
        Forme f;
        if (s.isAir()) {
            return Forme.VIDE;
        }
        Identifier id = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        f = new Forme(6);
        f.carte = carte;
        f.plein = s.isSolidRender();
        f.couleurTeinte = teinte(id.getPath(), s);
        String particule = null;
        if (s.getRenderShape() != RenderShape.INVISIBLE && m != null) {
            Map<String, String> props = new HashMap<>();
            Matcher mt = PROPRIETES.matcher(Etats.texte(s));
            if (mt.find() && !mt.group(1).isEmpty()) {
                for (String kv : mt.group(1).split(",")) {
                    int e = kv.indexOf('=');
                    props.put(kv.substring(0, e), kv.substring(e + 1));
                }
            }
            JsonObject bs = m.lireJson(id.getNamespace(), "blockstates/" + id.getPath() + ".json");
            if (bs != null) {
                for (Application a : applications(bs, props)) {
                    String p = m.poser(f, a);
                    if (particule == null) {
                        particule = p;
                    }
                }
            }
        }
        if (f.n == 0 && s.getRenderShape() == RenderShape.MODEL) {
            // Pas de modèle à éléments (coffre, lit, panneau : rendus par une entité côté client) ou pas
            // de jar : la forme de collision, texturée par la particule du modèle s'il y en a une.
            f.repli = true;
            if (m != null) {
                m.replis.add(id.toString());
            }
            Png.Image tex = m != null && particule != null ? m.texture(particule) : null;
            List<AABB> boites = s.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
            if (boites.isEmpty() && !s.canBeReplaced()) {
                boites = List.of(new AABB(0.25, 0, 0.25, 0.75, 0.5, 0.75));
            }
            for (AABB b : boites) {
                f.pave(new double[] {b.minX, b.minY, b.minZ}, new double[] {b.maxX, b.maxY, b.maxZ}, tex, false, 0);
            }
        }
        if (!fl.isEmpty()) {
            boolean eau = fl.getType().isSame(Fluids.WATER);
            boolean lave = fl.getType().isSame(Fluids.LAVA);
            if (eau || lave) {
                f.fluide = eau ? 1 : 2;
                f.hautFluide = fl.getOwnHeight();
                Png.Image t = m != null ? m.texture(eau ? "minecraft:block/water_still" : "minecraft:block/lava_still") : null;
                f.eau = fluide(f.hautFluide, t, eau);
                f.eauPleine = fluide(1.0, t, eau);
            }
        }
        return f;
    }

    private static Forme fluide(double h, Png.@Nullable Image t, boolean eau) {
        Forme e = new Forme(6);
        e.estFluide = true;
        e.carte = eau ? 0x4040FF : 0xFF4400;
        e.couleurTeinte = eau ? 0x3F76E4 : 0xFFFFFF;
        e.fluide = eau ? 1 : 2;
        e.pave(new double[] {0, 0, 0}, new double[] {1, h, 1}, t, eau, 0);
        // La surface d'une eau pas pleine n'est pas sur la frontière : toujours visible (sauf eau dessus,
        // réglé par le rastériseur qui prend alors eauPleine).
        return e;
    }

    private static List<Application> applications(JsonObject bs, Map<String, String> props) {
        List<Application> out = new ArrayList<>();
        if (bs.has("variants")) {
            for (Map.Entry<String, JsonElement> e : bs.getAsJsonObject("variants").entrySet()) {
                if (correspond(e.getKey(), props)) {
                    out.add(application(e.getValue()));
                    break;
                }
            }
        } else if (bs.has("multipart")) {
            for (JsonElement p : bs.getAsJsonArray("multipart")) {
                JsonObject o = p.getAsJsonObject();
                if (!o.has("when") || quand(o.getAsJsonObject("when"), props)) {
                    out.add(application(o.get("apply")));
                }
            }
        }
        return out;
    }

    private static boolean correspond(String cle, Map<String, String> props) {
        if (cle.isEmpty()) {
            return true;
        }
        for (String kv : cle.split(",")) {
            int e = kv.indexOf('=');
            if (e < 0 || !kv.substring(e + 1).equals(props.get(kv.substring(0, e)))) {
                return false;
            }
        }
        return true;
    }

    private static boolean quand(JsonObject w, Map<String, String> props) {
        if (w.has("OR")) {
            for (JsonElement e : w.getAsJsonArray("OR")) {
                if (quand(e.getAsJsonObject(), props)) {
                    return true;
                }
            }
            return false;
        }
        if (w.has("AND")) {
            for (JsonElement e : w.getAsJsonArray("AND")) {
                if (!quand(e.getAsJsonObject(), props)) {
                    return false;
                }
            }
            return true;
        }
        for (Map.Entry<String, JsonElement> e : w.entrySet()) {
            String v = e.getValue().getAsString();
            boolean non = v.startsWith("!");
            if (non) {
                v = v.substring(1);
            }
            String reel = props.get(e.getKey());
            boolean un = false;
            for (String alt : v.split("\\|")) {
                if (alt.equals(reel)) {
                    un = true;
                    break;
                }
            }
            if (un == non) {
                return false;
            }
        }
        return true;
    }

    private static Application application(JsonElement e) {
        // Liste pondérée (herbe tournée au hasard…) : la première suffit à lire une construction.
        JsonObject o = e.isJsonArray() ? e.getAsJsonArray().get(0).getAsJsonObject() : e.getAsJsonObject();
        return new Application(o.get("model").getAsString(), o.has("x") ? o.get("x").getAsInt() : 0,
                o.has("y") ? o.get("y").getAsInt() : 0, o.has("uvlock") && o.get("uvlock").getAsBoolean());
    }

    /** Un modèle résolu : textures fusionnées le long des parents, éléments du plus proche qui en a. */
    private record Resolu(Map<String, String> textures, @Nullable JsonArray elements) {}

    private @Nullable Resolu resoudre(String modele) {
        Map<String, String> tex = new HashMap<>();
        JsonArray elements = null;
        List<JsonObject> chaine = new ArrayList<>();
        String cour = modele;
        for (int i = 0; i < 16 && cour != null; i++) {
            String[] r = separer(cour);
            JsonObject o = lireJson(r[0], "models/" + r[1] + ".json");
            if (o == null) {
                break;
            }
            chaine.add(o);
            cour = o.has("parent") ? o.get("parent").getAsString() : null;
        }
        if (chaine.isEmpty()) {
            return null;
        }
        for (int i = chaine.size() - 1; i >= 0; i--) {
            JsonObject o = chaine.get(i);
            if (o.has("textures")) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("textures").entrySet()) {
                    JsonElement v = e.getValue();
                    // 26.x : une texture peut être {"sprite": …, "force_translucent": true}.
                    String s = v.isJsonObject() ? v.getAsJsonObject().get("sprite").getAsString() : v.getAsString();
                    tex.put(e.getKey(), s);
                }
            }
        }
        for (JsonObject o : chaine) {
            if (o.has("elements")) {
                elements = o.getAsJsonArray("elements");
                break;
            }
        }
        return new Resolu(tex, elements);
    }

    private static @Nullable String texte(Map<String, String> tex, String ref) {
        String r = ref;
        for (int i = 0; i < 12 && r != null && r.startsWith("#"); i++) {
            r = tex.get(r.substring(1));
        }
        return r == null || r.startsWith("#") ? null : r;
    }

    private static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

    /** Pose les éléments d'un modèle dans la forme ; rend la texture de particule (repli). */
    private @Nullable String poser(Forme f, Application a) {
        Resolu r = resoudre(a.modele());
        if (r == null) {
            return null;
        }
        String particule = texte(r.textures(), "#particle");
        if (r.elements() == null) {
            return particule;
        }
        for (JsonElement ee : r.elements()) {
            JsonObject el = ee.getAsJsonObject();
            double[] de = vec(el.getAsJsonArray("from"));
            double[] a2 = vec(el.getAsJsonArray("to"));
            JsonObject rotEl = el.has("rotation") ? el.getAsJsonObject("rotation") : null;
            double[] ombreDir = null;
            if (el.has("shade_direction_override")) {
                int d = indexFace(el.get("shade_direction_override").getAsString());
                if (d >= 0) {
                    ombreDir = new double[] {Forme.DIR[d][0], Forme.DIR[d][1], Forme.DIR[d][2]};
                }
            }
            boolean ombre = !el.has("shade") || el.get("shade").getAsBoolean();
            JsonObject faces = el.getAsJsonObject("faces");
            if (faces == null) {
                continue;
            }
            for (Map.Entry<String, JsonElement> fe : faces.entrySet()) {
                int d = indexFace(fe.getKey());
                if (d < 0) {
                    continue;
                }
                JsonObject fo = fe.getValue().getAsJsonObject();
                double[][] q = quad(d, de, a2);
                float[] rect;
                if (fo.has("uv")) {
                    JsonArray u = fo.getAsJsonArray("uv");
                    rect = new float[] {u.get(0).getAsFloat() / 16f, u.get(1).getAsFloat() / 16f,
                            u.get(2).getAsFloat() / 16f, u.get(3).getAsFloat() / 16f};
                } else {
                    rect = uvDefaut(d, de, a2);
                }
                for (double[] p : q) {
                    tournerElement(p, rotEl);
                    tournerVariante(p, a.x(), a.y());
                }
                int cull = -1;
                if (fo.has("cullface")) {
                    int c = indexFace(fo.get("cullface").getAsString());
                    if (c >= 0) {
                        cull = tournerDirection(c, a.x(), a.y());
                    }
                }
                String t = fo.has("texture") ? texte(r.textures(), fo.get("texture").getAsString()) : null;
                Png.Image img = t != null ? texture(t) : null;
                int rot = fo.has("rotation") ? Math.floorMod(fo.get("rotation").getAsInt() / 90, 4) : 0;
                boolean teinte = fo.has("tintindex") && fo.get("tintindex").getAsInt() >= 0;
                double[] od = ombreDir;
                f.ajouter(q[0], q[1], q[2], rect, rot, cull, teinte, a.uvlock() && (a.x() != 0 || a.y() != 0), img, od, ombre);
            }
        }
        return particule;
    }

    private static int indexFace(String s) {
        for (int i = 0; i < 6; i++) {
            if (FACES[i].equals(s)) {
                return i;
            }
        }
        return switch (s) {
            case "bottom" -> 0;
            case "top" -> 1;
            default -> -1;
        };
    }

    private static double[] vec(JsonArray a) {
        return new double[] {a.get(0).getAsDouble() / 16.0, a.get(1).getAsDouble() / 16.0, a.get(2).getAsDouble() / 16.0};
    }

    /**
     * Les trois coins d'une face d'un pavé [a, b] : P0 = (u, v) = (0, 0), Pu = (1, 0), Pv = (0, 1), où u
     * suit la texture vers la droite et v vers le bas, comme le client la pose. Même table que les uv par
     * défaut ci-dessous (vérifiée : nord vu depuis le nord a l'est à gauche, etc.).
     */
    static double[][] quad(int d, double[] a, double[] b) {
        return switch (d) {
            case 0 -> new double[][] {{a[0], a[1], b[2]}, {b[0], a[1], b[2]}, {a[0], a[1], a[2]}};
            case 1 -> new double[][] {{a[0], b[1], a[2]}, {b[0], b[1], a[2]}, {a[0], b[1], b[2]}};
            case 2 -> new double[][] {{b[0], b[1], a[2]}, {a[0], b[1], a[2]}, {b[0], a[1], a[2]}};
            case 3 -> new double[][] {{a[0], b[1], b[2]}, {b[0], b[1], b[2]}, {a[0], a[1], b[2]}};
            case 4 -> new double[][] {{a[0], b[1], a[2]}, {a[0], b[1], b[2]}, {a[0], a[1], a[2]}};
            default -> new double[][] {{b[0], b[1], b[2]}, {b[0], b[1], a[2]}, {b[0], a[1], b[2]}};
        };
    }

    static float[] uvDefaut(int d, double[] a, double[] b) {
        double[] r = switch (d) {
            case 0 -> new double[] {a[0], 1 - b[2], b[0], 1 - a[2]};
            case 1 -> new double[] {a[0], a[2], b[0], b[2]};
            case 2 -> new double[] {1 - b[0], 1 - b[1], 1 - a[0], 1 - a[1]};
            case 3 -> new double[] {a[0], 1 - b[1], b[0], 1 - a[1]};
            case 4 -> new double[] {a[2], 1 - b[1], b[2], 1 - a[1]};
            default -> new double[] {1 - b[2], 1 - b[1], 1 - a[2], 1 - a[1]};
        };
        return new float[] {(float) r[0], (float) r[1], (float) r[2], (float) r[3]};
    }

    /** Rotation d'élément (axe + angle, main droite, {@code rescale}) ou forme 26.x {@code x/y/z}. */
    private static void tournerElement(double[] p, @Nullable JsonObject r) {
        if (r == null) {
            return;
        }
        double[] o = r.has("origin") ? vec(r.getAsJsonArray("origin")) : new double[] {0.5, 0.5, 0.5};
        p[0] -= o[0];
        p[1] -= o[1];
        p[2] -= o[2];
        if (r.has("axis")) {
            double ang = Math.toRadians(r.get("angle").getAsDouble());
            int axe = switch (r.get("axis").getAsString()) {
                case "x" -> 0;
                case "y" -> 1;
                default -> 2;
            };
            tourner(p, axe, ang);
            if (r.has("rescale") && r.get("rescale").getAsBoolean() && Math.abs(Math.cos(ang)) > 1e-6) {
                double s = 1.0 / Math.cos(ang);
                for (int k = 0; k < 3; k++) {
                    if (k != axe) {
                        p[k] *= s;
                    }
                }
            }
        } else {
            for (int k = 0; k < 3; k++) {
                String c = k == 0 ? "x" : k == 1 ? "y" : "z";
                if (r.has(c)) {
                    tourner(p, k, Math.toRadians(r.get(c).getAsDouble()));
                }
            }
        }
        p[0] += o[0];
        p[1] += o[1];
        p[2] += o[2];
    }

    /**
     * Rotation de variante : {@code rotateYXZ(-y, -x, 0)} du client, soit x d'abord puis y, angles
     * négatifs, autour du centre du bloc. Vérifié sur les escaliers : facing=north (y=270) met la
     * marche haute au nord, facing=south (y=90) au sud.
     */
    private static void tournerVariante(double[] p, int x, int y) {
        if (x == 0 && y == 0) {
            return;
        }
        p[0] -= 0.5;
        p[1] -= 0.5;
        p[2] -= 0.5;
        if (x != 0) {
            tourner(p, 0, Math.toRadians(-x));
        }
        if (y != 0) {
            tourner(p, 1, Math.toRadians(-y));
        }
        p[0] += 0.5;
        p[1] += 0.5;
        p[2] += 0.5;
        for (int k = 0; k < 3; k++) {
            // Les quarts de tour tombent juste : on efface le bruit de cos(90°) pour que les faces
            // restent exactement sur les frontières du bloc (occlusion, coupe).
            double r = Math.rint(p[k] * 16.0) / 16.0;
            if (Math.abs(p[k] - r) < 1e-9) {
                p[k] = r;
            }
        }
    }

    private static int tournerDirection(int d, int x, int y) {
        double[] v = {Forme.DIR[d][0] + 0.5, Forme.DIR[d][1] + 0.5, Forme.DIR[d][2] + 0.5};
        tournerVariante(v, x, y);
        double vx = v[0] - 0.5, vy = v[1] - 0.5, vz = v[2] - 0.5;
        int k = Math.abs(vx) > 0.5 ? 0 : Math.abs(vy) > 0.5 ? 1 : 2;
        return Forme.direction(k, (k == 0 ? vx : k == 1 ? vy : vz) > 0);
    }

    /** Rotation main droite d'angle {@code a} autour de l'axe {@code axe} (0 x, 1 y, 2 z). */
    private static void tourner(double[] p, int axe, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        double x = p[0], y = p[1], z = p[2];
        switch (axe) {
            case 0 -> {
                p[1] = y * c - z * s;
                p[2] = y * s + z * c;
            }
            case 1 -> {
                p[0] = x * c + z * s;
                p[2] = -x * s + z * c;
            }
            default -> {
                p[0] = x * c - y * s;
                p[1] = x * s + y * c;
            }
        }
    }

    /**
     * Couleur de teinte ({@code tintindex}) : celle des plaines, faute de colormap de biome côté serveur.
     * Assez juste pour lire une construction ; un marais aura l'herbe trop verte.
     */
    private static int teinte(String bloc, BlockState s) {
        if (bloc.equals("birch_leaves")) {
            return 0x80A755;
        }
        if (bloc.equals("spruce_leaves")) {
            return 0x619961;
        }
        if (bloc.equals("mangrove_leaves")) {
            return 0x8DB127;
        }
        if (bloc.endsWith("leaves") || bloc.equals("vine")) {
            return 0x77AB2F;
        }
        if (bloc.contains("water") || bloc.equals("bubble_column")) {
            return 0x3F76E4;
        }
        if (bloc.equals("lily_pad")) {
            return 0x208030;
        }
        if (bloc.equals("redstone_wire")) {
            var puissance = net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER;
            int p = s.hasProperty(puissance) ? s.getValue(puissance) : 0;
            int r = (int) (255 * (p == 0 ? 0.3 : 0.4 + 0.6 * p / 15.0));
            return r << 16;
        }
        if (bloc.contains("stem")) {
            return 0xC0A020;
        }
        return 0x91BD59;
    }
}
