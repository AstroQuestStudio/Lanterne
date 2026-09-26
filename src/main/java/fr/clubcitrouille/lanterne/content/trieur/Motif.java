package fr.clubcitrouille.lanterne.content.trieur;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import org.jspecify.annotations.Nullable;

/**
 * Un motif texte de filtre : « sword », « #logs », « @lanterne », « épée !or », « *_ore ».
 *
 * <h2>Pourquoi un motif, en plus des gabarits</h2>
 *
 * <p>Un gabarit désigne UN objet. Une salle de tri réelle veut des familles : toutes les épées, tous
 * les minerais, tout ce qui vient d'un mod. Avec dix-huit cases de gabarit, « toutes les épées » en
 * mange déjà sept, et « toutes les bûches » dépasse la capacité du filtre. Un motif tient en UNE case.
 *
 * <h2>Syntaxe</h2>
 *
 * <p>Des mots séparés par des espaces, et TOUS doivent correspondre (un « et » logique) :
 *
 * <ul>
 * <li><b>mot</b> — correspond si c'est un JETON de l'identifiant de l'objet, découpé sur {@code _},
 * {@code /} et {@code :}. « axe » attrape donc {@code diamond_axe} mais PAS {@code pickaxe} : c'est
 * tout l'intérêt d'un jeton sur une simple sous-chaîne, qui aurait mêlé haches et pioches. Un mot qui
 * contient lui-même un séparateur (« fishing_rod ») exige la suite de jetons correspondante. Le nom
 * personnalisé de l'objet (renommé à l'enclume) compte aussi, découpé en mots de la même façon.</li>
 * <li><b>{@code *}</b>, <b>{@code ?}</b> — joker, sur le chemin de l'identifiant ({@code *_sword}),
 * ou sur l'identifiant complet si le motif contient {@code :} ({@code lanterne:*}).</li>
 * <li><b>{@code #tag}</b> — étiquette d'objets : {@code #logs}, {@code #minecraft:swords},
 * {@code #c:ingots}. L'espace de noms par défaut est {@code minecraft}.</li>
 * <li><b>{@code @mod}</b> — espace de noms : {@code @lanterne}, {@code @minecraft}.</li>
 * <li><b>{@code !}</b> en préfixe de n'importe lequel des précédents — négation : {@code épée !or}.</li>
 * </ul>
 *
 * <p>Insensible à la casse ET aux accents (« Épée » = « epee »). Un petit dictionnaire traduit les
 * mots français courants vers les jetons anglais des identifiants ({@link #DICTIONNAIRE}) ; le mot
 * lui-même reste toujours essayé, et un pluriel en {@code s}/{@code x} est essayé au singulier.
 *
 * <h2>Le coût, parce que ce code tourne dans le tick serveur</h2>
 *
 * <p>Un motif se compile UNE fois ({@link #compiler}) en un tableau de termes déjà prêts : jetons
 * normalisés, clé d'étiquette construite, joker gardé tel quel et parcouru sans expression
 * régulière. Le Trieur garde ce résultat jusqu'à ce que son filtre change. Le découpage de
 * l'identifiant d'un objet en jetons est mis en cache par {@link Item} ({@link #FICHES}) — le
 * registre ne change plus une fois le jeu lancé — si bien qu'un test ne coûte, dans le cas courant,
 * que quelques comparaisons de chaînes internées dans un tableau de trois ou quatre cases. Seul un
 * objet RENOMMÉ paie un découpage de son nom, et c'est rare.
 *
 * <p>Code COMMUN : le serveur s'en sert pour filtrer, le client pour l'aperçu en direct de l'écran —
 * le même code des deux côtés, donc l'aperçu ne peut pas mentir sur ce que le serveur fera.
 */
public final class Motif {
    /** Longueur maximale d'un motif, vérifiée côté serveur à la réception du paquet. */
    public static final int LONGUEUR_MAX = 48;

    /**
     * Marque posée (dans {@code minecraft:custom_data}) sur l'étiquette qu'un motif TAPÉ crée dans le
     * filtre. Elle distingue cette étiquette virtuelle — jamais rendue au joueur, sinon le champ texte
     * serait une fabrique d'étiquettes gratuites — d'une vraie étiquette renommée à l'enclume que le
     * joueur a lui-même posée, et qu'il récupère normalement.
     */
    public static final String MARQUE = "lanterne_motif";

    /** Le motif qui ne correspond à rien : un filtre sans terme n'attrape rien. */
    public static final Motif VIDE = new Motif("", new Terme[0]);

    private final String source;
    private final Terme[] termes;

    private Motif(String source, Terme[] termes) {
        this.source = source;
        this.termes = termes;
    }

    public String source() {
        return this.source;
    }

    public boolean estVide() {
        return this.termes.length == 0;
    }

    // --- Compilation ---------------------------------------------------------

    /** Compile un motif tapé. Jamais d'exception : un terme illisible ne correspond simplement à rien. */
    public static Motif compiler(@Nullable String texte) {
        if (texte == null) {
            return VIDE;
        }
        String propre = normaliser(texte).trim();
        if (propre.isEmpty()) {
            return VIDE;
        }
        List<Terme> termes = new ArrayList<>();
        for (String mot : propre.split("\\s+")) {
            Terme terme = terme(mot);
            if (terme != null) {
                termes.add(terme);
            }
        }
        return termes.isEmpty() ? VIDE : new Motif(texte.trim(), termes.toArray(new Terme[0]));
    }

    private static @Nullable Terme terme(String mot) {
        boolean negatif = false;
        while (mot.startsWith("!")) {
            negatif = !negatif;
            mot = mot.substring(1);
        }
        if (mot.isEmpty()) {
            return null;
        }
        char tete = mot.charAt(0);
        if (tete == '#') {
            Identifier id = mot.length() > 1 ? Identifier.tryParse(mot.substring(1)) : null;
            return new Terme(Genre.TAG, negatif, null, null,
                    id == null ? null : TagKey.create(Registries.ITEM, id));
        }
        if (tete == '@') {
            return mot.length() > 1 ? new Terme(Genre.MOD, negatif, mot.substring(1), null, null) : null;
        }
        if (mot.indexOf('*') >= 0 || mot.indexOf('?') >= 0) {
            return new Terme(Genre.JOKER, negatif, mot, null, null);
        }
        return new Terme(Genre.MOT, negatif, mot, alternatives(mot), null);
    }

    /**
     * Les suites de jetons qu'un mot accepte : lui-même, sa traduction, son singulier et la traduction
     * de son singulier. Chaque alternative est une SUITE (« fishing_rod » = [fishing, rod]).
     */
    private static String[][] alternatives(String mot) {
        List<String> brutes = new ArrayList<>();
        brutes.add(mot);
        ajouterTraductions(brutes, mot);
        if (mot.length() > 3 && (mot.endsWith("s") || mot.endsWith("x"))) {
            String singulier = mot.substring(0, mot.length() - 1);
            brutes.add(singulier);
            ajouterTraductions(brutes, singulier);
        }
        String[][] suites = new String[brutes.size()][];
        for (int i = 0; i < suites.length; i++) {
            suites[i] = decouper(brutes.get(i));
        }
        return suites;
    }

    private static void ajouterTraductions(List<String> into, String mot) {
        String[] traductions = DICTIONNAIRE.get(mot);
        if (traductions != null) {
            for (String t : traductions) {
                into.add(t);
            }
        }
    }

    // --- Correspondance ------------------------------------------------------

    public boolean correspond(ItemStack stack) {
        return !stack.isEmpty() && correspond(stack.typeHolder(), stack.get(DataComponents.CUSTOM_NAME));
    }

    /**
     * Le test lui-même, sur ce qui suffit à décider : le type d'objet et son nom personnalisé. Pas
     * d'{@code ItemStack} exigé — le ticker du Trieur l'appelle sur une {@code ItemResource} sans
     * allouer de pile.
     */
    public boolean correspond(Holder<Item> holder, @Nullable Component nomPerso) {
        if (this.termes.length == 0) {
            return false;
        }
        Fiche fiche = fiche(holder.value());
        String[] jetonsNom = null;
        boolean nomCalcule = false;
        for (Terme terme : this.termes) {
            boolean ok;
            switch (terme.genre) {
                case MOT -> {
                    if (!nomCalcule) {
                        jetonsNom = nomPerso == null ? null : decouperMots(normaliser(nomPerso.getString()));
                        nomCalcule = true;
                    }
                    ok = terme.mot(fiche.jetons) || (jetonsNom != null && terme.mot(jetonsNom));
                }
                case JOKER -> ok = joker(terme.texte,
                        terme.texte.indexOf(':') >= 0 ? fiche.complet : fiche.chemin);
                case TAG -> ok = terme.tag != null && holder.is(terme.tag);
                case MOD -> ok = terme.texte.equals(fiche.espace);
                default -> ok = false;
            }
            if (ok == terme.negatif) {
                return false;
            }
        }
        return true;
    }

    private enum Genre { MOT, JOKER, TAG, MOD }

    private record Terme(Genre genre, boolean negatif, @Nullable String texte,
            String[][] suites, @Nullable TagKey<Item> tag) {
        /** Une des suites apparaît-elle, jetons consécutifs, dans ce tableau de jetons ? */
        boolean mot(String[] jetons) {
            for (String[] suite : this.suites) {
                if (contientSuite(jetons, suite)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean contientSuite(String[] jetons, String[] suite) {
        int n = suite.length;
        if (n == 0) {
            return false;
        }
        outer:
        for (int i = 0; i + n <= jetons.length; i++) {
            for (int j = 0; j < n; j++) {
                if (!jetons[i + j].equals(suite[j])) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Joker classique, {@code *} (n'importe quelle suite) et {@code ?} (un caractère), sans expression
     * régulière : un seul point de reprise sur la dernière étoile, parcours linéaire en pratique.
     */
    static boolean joker(String motif, String texte) {
        int m = 0;
        int t = 0;
        int etoile = -1;
        int reprise = 0;
        while (t < texte.length()) {
            if (m < motif.length() && (motif.charAt(m) == '?' || motif.charAt(m) == texte.charAt(t))) {
                m++;
                t++;
            } else if (m < motif.length() && motif.charAt(m) == '*') {
                etoile = m++;
                reprise = t;
            } else if (etoile >= 0) {
                m = etoile + 1;
                t = ++reprise;
            } else {
                return false;
            }
        }
        while (m < motif.length() && motif.charAt(m) == '*') {
            m++;
        }
        return m == motif.length();
    }

    // --- Fiches d'objets (cache) ----------------------------------------------

    /** L'identifiant d'un objet, prédécoupé une fois pour toutes. */
    private record Fiche(String espace, String chemin, String complet, String[] jetons) {}

    /**
     * Par {@link Item}, jamais par pile : il y a quelques milliers d'objets au plus et leur identifiant
     * ne change pas une fois le registre gelé. {@link ConcurrentHashMap}, parce qu'en solo le fil du
     * serveur intégré (filtrage) et le fil de rendu (aperçu) peuvent y entrer en même temps.
     */
    private static final Map<Item, Fiche> FICHES = new ConcurrentHashMap<>();

    private static Fiche fiche(Item item) {
        Fiche fiche = FICHES.get(item);
        if (fiche == null) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            String espace = id.getNamespace();
            String chemin = id.getPath();
            List<String> jetons = new ArrayList<>();
            jetons.add(espace.intern());
            for (String j : decouper(chemin)) {
                jetons.add(j);
            }
            fiche = new Fiche(espace, chemin, espace + ":" + chemin, jetons.toArray(new String[0]));
            FICHES.put(item, fiche);
        }
        return fiche;
    }

    /** Découpe sur les séparateurs d'identifiant : {@code _ / : -} et le point. */
    private static String[] decouper(String s) {
        List<String> out = new ArrayList<>(4);
        int debut = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || estSeparateur(s.charAt(i))) {
                if (i > debut) {
                    out.add(s.substring(debut, i).intern());
                }
                debut = i + 1;
            }
        }
        return out.toArray(new String[0]);
    }

    private static boolean estSeparateur(char c) {
        return c == '_' || c == '/' || c == ':' || c == '-' || c == '.';
    }

    /** Découpe un nom affiché en mots : tout ce qui n'est ni lettre ni chiffre sépare. */
    private static String[] decouperMots(String s) {
        List<String> out = new ArrayList<>(4);
        int debut = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || !Character.isLetterOrDigit(s.charAt(i))) {
                if (i > debut) {
                    out.add(s.substring(debut, i));
                }
                debut = i + 1;
            }
        }
        return out.toArray(new String[0]);
    }

    /** Minuscules, sans accents, ligatures dépliées : « Épée » → « epee », « Œuf » → « oeuf ». */
    public static String normaliser(String s) {
        String decompose = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder(decompose.length());
        for (int i = 0; i < decompose.length(); i++) {
            char c = decompose.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            switch (c) {
                case 'œ', 'Œ' -> b.append("oe");
                case 'æ', 'Æ' -> b.append("ae");
                case 'ß' -> b.append("ss");
                default -> b.append(c);
            }
        }
        return b.toString().toLowerCase(Locale.ROOT);
    }

    // --- L'étiquette qui porte un motif ---------------------------------------

    /** Une étiquette renommée (vraie ou virtuelle) : sa case de filtre est un motif, pas un gabarit. */
    public static boolean estMotif(ItemStack stack) {
        return stack.is(Items.NAME_TAG) && stack.has(DataComponents.CUSTOM_NAME);
    }

    /** Le texte du motif porté par cette étiquette — son nom. */
    public static String texte(ItemStack stack) {
        Component nom = stack.get(DataComponents.CUSTOM_NAME);
        return nom == null ? "" : nom.getString();
    }

    /** Étiquette créée par le champ texte : voir {@link #MARQUE}. Jamais rendue au joueur. */
    public static boolean estVirtuel(ItemStack stack) {
        if (!estMotif(stack)) {
            return false;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(MARQUE);
    }

    /** L'étiquette virtuelle d'un motif tapé, prête à poser dans une case de filtre. */
    public static ItemStack etiquette(String motif) {
        ItemStack stack = new ItemStack(Items.NAME_TAG);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(motif));
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(MARQUE, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /**
     * Nettoie un motif reçu du réseau : caractères de contrôle et {@code §} (codes de mise en forme)
     * retirés, espaces multiples réduits, longueur bornée. Rend une chaîne vide si rien ne reste.
     */
    public static String nettoyer(@Nullable String brut) {
        if (brut == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(Math.min(brut.length(), LONGUEUR_MAX));
        boolean espace = false;
        for (int i = 0; i < brut.length() && b.length() < LONGUEUR_MAX; i++) {
            char c = brut.charAt(i);
            if (c == '§') {
                i++; // le code de mise en forme qui suit (§c, §l…) part avec lui
                continue;
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            if (Character.isWhitespace(c)) {
                espace = b.length() > 0;
                continue;
            }
            if (espace) {
                b.append(' ');
                espace = false;
                if (b.length() >= LONGUEUR_MAX) {
                    break;
                }
            }
            b.append(c);
        }
        return b.toString().trim();
    }

    // --- Dictionnaire français → jetons anglais -------------------------------

    /**
     * Mots français courants → jetons des identifiants vanilla. Clés déjà normalisées (sans accents,
     * minuscules). Plusieurs valeurs = « l'une ou l'autre » ; une valeur à {@code _} = suite de jetons.
     * Volontairement court : ce n'est pas une traduction du jeu, c'est ce qu'un joueur tape d'instinct
     * devant sa salle de tri.
     */
    static final Map<String, String[]> DICTIONNAIRE = new HashMap<>();

    private static void d(String fr, String... en) {
        DICTIONNAIRE.put(fr, en);
    }

    static {
        // Outils, armes, armures.
        d("epee", "sword");
        d("hache", "axe");
        d("pioche", "pickaxe");
        d("pelle", "shovel");
        d("houe", "hoe");
        d("casque", "helmet");
        d("plastron", "chestplate");
        d("jambieres", "leggings");
        d("jambiere", "leggings");
        d("bottes", "boots");
        d("botte", "boots");
        d("armure", "helmet", "chestplate", "leggings", "boots", "armor");
        d("outil", "pickaxe", "axe", "shovel", "hoe");
        d("arme", "sword", "axe", "bow", "crossbow", "trident", "mace");
        d("arc", "bow");
        d("arbalete", "crossbow");
        d("fleche", "arrow");
        d("bouclier", "shield");
        d("masse", "mace");
        d("cisailles", "shears");
        d("cisaille", "shears");
        d("canne", "rod", "cane");
        d("briquet", "flint_and_steel");
        d("selle", "saddle");
        // Matériaux.
        d("diamant", "diamond");
        d("fer", "iron");
        d("or", "golden", "gold");
        d("cuivre", "copper");
        d("emeraude", "emerald");
        d("charbon", "coal");
        d("amethyste", "amethyst");
        d("obsidienne", "obsidian");
        d("minerai", "ore");
        d("lingot", "ingot");
        d("pepite", "nugget");
        d("brut", "raw");
        d("bloc", "block");
        d("cuir", "leather");
        d("maille", "chainmail");
        d("mailles", "chainmail");
        // Blocs.
        d("bois", "log", "planks", "wood");
        d("buche", "log");
        d("planche", "planks");
        d("pierre", "stone");
        d("roche", "stone", "deepslate");
        d("sable", "sand");
        d("gravier", "gravel");
        d("terre", "dirt");
        d("argile", "clay");
        d("brique", "brick", "bricks");
        d("verre", "glass");
        d("vitre", "pane");
        d("laine", "wool");
        d("tapis", "carpet");
        d("beton", "concrete");
        d("dalle", "slab");
        d("escalier", "stairs");
        d("muret", "wall");
        d("mur", "wall");
        d("barriere", "fence");
        d("portillon", "fence_gate");
        d("porte", "door");
        d("trappe", "trapdoor");
        d("bouton", "button");
        d("levier", "lever");
        d("plaque", "pressure_plate");
        d("glace", "ice");
        d("neige", "snow");
        d("feuille", "leaves");
        d("feuilles", "leaves");
        d("pousse", "sapling");
        d("fleur", "flower");
        d("champignon", "mushroom");
        d("corail", "coral");
        d("ame", "soul");
        d("torche", "torch");
        d("lanterne", "lantern");
        d("coffre", "chest");
        d("tonneau", "barrel");
        d("entonnoir", "hopper");
        d("lit", "bed");
        d("bougie", "candle");
        d("banniere", "banner");
        d("panneau", "sign");
        d("pancarte", "sign");
        d("tete", "head", "skull");
        d("crane", "skull");
        // Essences de bois.
        d("chene", "oak");
        d("bouleau", "birch");
        d("sapin", "spruce");
        d("cerisier", "cherry");
        d("bambou", "bamboo");
        d("carmin", "crimson");
        d("biscornu", "warped");
        // Couleurs.
        d("blanc", "white");
        d("blanche", "white");
        d("noir", "black");
        d("noire", "black");
        d("rouge", "red");
        d("bleu", "blue");
        d("bleue", "blue");
        d("vert", "green");
        d("verte", "green");
        d("jaune", "yellow");
        d("rose", "pink");
        d("violet", "purple");
        d("violette", "purple");
        d("gris", "gray");
        d("grise", "gray");
        d("marron", "brown");
        d("brun", "brown");
        d("teinture", "dye");
        d("colorant", "dye");
        // Nourriture, cultures, butin de créatures.
        d("graine", "seeds");
        d("ble", "wheat");
        d("carotte", "carrot");
        d("patate", "potato");
        d("pomme", "apple");
        d("pain", "bread");
        d("boeuf", "beef");
        d("porc", "porkchop");
        d("poulet", "chicken");
        d("mouton", "mutton");
        d("lapin", "rabbit");
        d("poisson", "cod", "salmon", "fish");
        d("morue", "cod");
        d("saumon", "salmon");
        d("cuit", "cooked");
        d("cuite", "cooked");
        d("citrouille", "pumpkin");
        d("pasteque", "melon");
        d("sucre", "sugar");
        d("miel", "honey");
        d("oeuf", "egg");
        d("lait", "milk");
        d("os", "bone");
        d("ficelle", "string");
        d("fil", "string");
        d("plume", "feather");
        d("poudre", "powder", "gunpowder");
        d("perle", "pearl");
        d("oeil", "eye");
        d("chair", "flesh");
        d("toile", "cobweb");
        // Divers.
        d("seau", "bucket");
        d("eau", "water");
        d("lave", "lava");
        d("livre", "book");
        d("enchante", "enchanted");
        d("fiole", "bottle");
        d("bouteille", "bottle");
        d("carte", "map");
        d("boussole", "compass");
        d("horloge", "clock");
        d("disque", "disc");
        d("minecart", "minecart");
        d("wagonnet", "minecart");
        d("bateau", "boat");
        d("etiquette", "name_tag");
        d("balise", "waypoint");
    }
}
