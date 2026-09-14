package fr.clubcitrouille.lanterne.content.shop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * L'étal : le catalogue de la boutique administrateur, et le fichier qui le porte.
 *
 * <h2>Pourquoi un fichier texte et non du JSON ou du TOML</h2>
 *
 * <p>Ce catalogue a une forme très particulière : treize cents lignes rigoureusement identiques,
 * qu'un administrateur relit en diagonale pour trouver un prix et le changer. Du JSON en ferait six
 * mille lignes et une accolade oubliée casserait tout le fichier ; un TOML de tableaux imbriqués
 * serait pire. Le format retenu tient en une phrase :
 *
 * <pre>    objet ; prix d'achat ; prix de rachat ; rayon</pre>
 *
 * <p>Une ligne vide ou commençant par {@code #} est ignorée. Une ligne fautive est <b>annoncée avec
 * son numéro et sa raison, puis sautée</b> : le reste du catalogue se charge quand même. C'est le
 * point le plus important du format — un fichier de prix qui refuse de se charger en entier parce
 * qu'une virgule traîne, sur un serveur qui vient de redémarrer, c'est une boutique vide et cent
 * joueurs qui croient avoir perdu leur argent.
 *
 * <h2>Le catalogue par défaut n'est pas un exemple : c'est tout le jeu</h2>
 *
 * <p>Au premier démarrage, le fichier n'existe pas et il est <b>engendré</b> — voir {@link Assay} :
 * mille trois cents articles, tout le vanilla tarifable, déduits des recettes du jeu. Un mod de
 * boutique livré avec trois articles de démonstration laisse tout le travail d'équilibrage à
 * l'administrateur, et il ne le fera pas : il mettra des prix au hasard, et l'économie du serveur
 * sera morte au bout d'une semaine. On installe, on lance, l'économie existe.
 *
 * <p>Et <b>une fois écrit, le fichier lui appartient</b>. Il n'est plus jamais réécrit tout seul, ni
 * au démarrage suivant, ni après une mise à jour. Seul {@code /boutique generer} le refait, et cette
 * commande dit qu'elle écrase.
 *
 * <h2>Le refus qui n'a pas d'exception</h2>
 *
 * <p>Un article dont le prix de rachat atteint le prix d'achat est refusé. Voir {@link #put} pour la
 * règle exacte et {@link Offer} pour la raison. C'est le seul endroit du mod où un administrateur
 * s'entend dire non.
 *
 * <h2>Fil d'exécution</h2>
 *
 * <p>Tout ce fichier vit sur le <b>fil du serveur</b> : les commandes y sont exécutées, les paquets
 * entrants y sont renvoyés par {@code enqueueWork}, et le chargement a lieu dans
 * {@code ServerStartedEvent}. Aucune structure concurrente n'est donc nécessaire — et il ne faut pas
 * en introduire, car elles donneraient l'illusion qu'un accès depuis un autre fil serait correct.
 */
public final class Stall {
    /** L'ordre d'insertion est l'ordre du fichier, qui est l'ordre affiché. */
    private static final Map<Identifier, Offer> BOARD = new LinkedHashMap<>();

    private static final String SEPARATOR = ";";

    private Stall() {}

    // --- Emplacement -------------------------------------------------------

    /**
     * Le dossier du jeu — le répertoire courant du processus.
     *
     * <p>Même choix, et même justification, que {@code content.painting.Easel.gameDir()} : c'est là
     * que le lanceur place {@code config/} et {@code mods/}, pour le client comme pour un serveur
     * dédié, et cela évite une dépendance de plus envers le chargeur de mods.
     */
    public static Path folder() {
        return Path.of("").toAbsolutePath().resolve("config").resolve("lanterne");
    }

    public static Path file() {
        return folder().resolve("boutique.txt");
    }

    // --- Consultation ------------------------------------------------------

    public static Offer find(Identifier item) {
        return BOARD.get(item);
    }

    public static boolean holds(Identifier item) {
        return BOARD.containsKey(item);
    }

    public static Collection<Offer> all() {
        return BOARD.values();
    }

    public static int size() {
        return BOARD.size();
    }

    /** Les rayons, dans l'ordre alphabétique — c'est la colonne de gauche de l'écran. */
    public static List<String> aisles() {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Offer offer : BOARD.values()) {
            names.add(offer.category());
        }
        return new ArrayList<>(names);
    }

    // --- Modification ------------------------------------------------------

    /**
     * Inscrit ou remplace un article.
     *
     * <h2>Les quatre refus</h2>
     *
     * <ol>
     *   <li>l'objet n'existe pas dans le registre, ou c'est de l'air ;</li>
     *   <li>le prix d'achat est nul, négatif, ou au-delà du plafond ;</li>
     *   <li>le prix de rachat est négatif, ou dépasse le plafond ;</li>
     *   <li><b>le prix de rachat ne laisse pas la marge minimale.</b></li>
     * </ol>
     *
     * <p>Le quatrième est celui qui compte. Un article racheté au moins aussi cher qu'il est vendu est
     * une machine à fabriquer de l'argent : acheter, revendre, recommencer, aussi vite qu'on clique.
     * Un écart d'un centime ne change rien à l'affaire, il la ralentit — et un automate ne se fatigue
     * pas. La marge minimale du serveur est donc vérifiée ici, et nulle part ailleurs : c'est le seul
     * chemin par lequel un article entre au catalogue, que ce soit par commande ou par le fichier.
     *
     * @return {@code null} si l'article est inscrit, ou la raison du refus, en clair
     */
    public static String put(Identifier item, long buy, long sell, String category) {
        if (item == null || !BuiltInRegistries.ITEM.containsKey(item)) {
            return "Aucun objet ne s'appelle « " + item + " » dans ce jeu.";
        }
        Item value = BuiltInRegistries.ITEM.getValue(item);
        if (value == Items.AIR) {
            return "L'air ne se vend pas.";
        }
        if (buy < 1L) {
            return "Le prix d'achat doit valoir au moins un centime.";
        }
        if (buy > Coin.PRICE_CEILING) {
            return "Le prix d'achat dépasse le plafond de " + Coin.say(Coin.PRICE_CEILING) + ".";
        }
        if (sell < 0L || sell > Coin.PRICE_CEILING) {
            return "Le prix de rachat doit être compris entre zéro et "
                    + Coin.say(Coin.PRICE_CEILING) + ". Zéro signifie « non racheté ».";
        }
        if (sell > 0L) {
            long allowed = buy - Math.max(1L, buy * Tariff.margin() / 100L);
            if (sell > allowed) {
                return "Refusé : racheté " + Coin.say(sell) + " et vendu " + Coin.say(buy)
                        + ", cet article se revendrait en boucle. Avec la marge minimale de "
                        + Tariff.margin() + " %, le rachat ne peut pas dépasser "
                        + Coin.say(Math.max(0L, allowed)) + ".";
            }
        }
        BOARD.put(item, new Offer(item, buy, sell, Offer.cleanCategory(category)));
        return null;
    }

    /** Retire un article. */
    public static boolean drop(Identifier item) {
        return BOARD.remove(item) != null;
    }

    // --- Fichier -----------------------------------------------------------

    /** Vide l'étal. Réservé à la génération, qui repart toujours d'une table rase. */
    static void clear() {
        BOARD.clear();
    }

    /**
     * Charge le catalogue au démarrage du serveur.
     *
     * <h2>Le fichier appartient à l'administrateur</h2>
     *
     * <p>S'il existe, il est lu — <b>et rien d'autre</b>. La génération n'a lieu qu'au tout premier
     * démarrage, quand il n'y a rien à lire, ou sur demande explicite par {@code /boutique generer}.
     * C'est une règle sans exception : un mod qui réécrirait le fichier de prix d'un serveur à chaque
     * mise à jour effacerait des semaines de réglage, et on ne s'en apercevrait qu'après.
     *
     * @return le nombre d'articles au catalogue
     */
    public static int awaken(MinecraftServer server) {
        try {
            Files.createDirectories(folder());
        } catch (IOException problem) {
            Lanterne.LOG.error("[BOUTIQUE] dossier impossible à créer : {}", problem.getMessage());
        }
        if (!Files.isRegularFile(file())) {
            Lanterne.LOG.info("[BOUTIQUE] aucun catalogue : on l'engendre à partir des recettes du"
                    + " jeu.");
            Assay.brew(server);
            inscribe();
            Lanterne.LOG.info("[BOUTIQUE] premier démarrage : {} article(s) écrits dans {}.",
                    BOARD.size(), file());
            return BOARD.size();
        }
        return reload();
    }

    /**
     * Refait le catalogue de zéro à partir des recettes, et l'écrit.
     *
     * <p><b>Écrase le fichier.</b> C'est annoncé par la commande qui l'appelle, et c'est le seul
     * chemin par lequel un catalogue déjà écrit peut être remplacé.
     */
    public static Assay.Tally generate(MinecraftServer server) {
        Assay.Tally tally = Assay.brew(server);
        inscribe();
        return tally;
    }

    /**
     * Relit le fichier, à chaud.
     *
     * <p>Le catalogue en mémoire n'est vidé qu'<b>après</b> une lecture réussie du fichier : si le
     * disque refuse de le donner, la boutique continue de tourner sur ce qu'elle avait. Un
     * rechargement raté ne doit jamais coûter plus qu'un rechargement pas fait.
     *
     * @return le nombre d'articles, ou {@code -1} si le fichier n'a pas pu être lu
     */
    public static int reload() {
        List<String> lines;
        try {
            lines = Files.readAllLines(file(), StandardCharsets.UTF_8);
        } catch (IOException problem) {
            Lanterne.LOG.error("[BOUTIQUE] {} illisible : {} — le catalogue en place est conservé.",
                    file(), problem.getMessage());
            return -1;
        }
        BOARD.clear();
        int count = absorb(lines, file().toString());
        Lanterne.LOG.info("[BOUTIQUE] {} article(s) chargé(s) depuis {}.", count, file());
        return count;
    }

    /**
     * Passe une suite de lignes à l'analyseur.
     *
     * <p>Chaque ligne fautive est journalisée avec son numéro et sa raison, puis sautée. Voir
     * l'en-tête pour la raison de ce choix.
     */
    private static int absorb(List<String> lines, String origin) {
        int accepted = 0;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String refusal = readLine(line);
            if (refusal == null) {
                accepted++;
            } else {
                Lanterne.LOG.warn("[BOUTIQUE] {} ligne {} ignorée : {}", origin, i + 1, refusal);
            }
        }
        return accepted;
    }

    /** Lit une ligne du fichier. Rend {@code null} si elle est bonne, ou la raison du refus. */
    private static String readLine(String line) {
        String[] parts = line.split(SEPARATOR, -1);
        if (parts.length < 3) {
            return "il faut au moins « objet ; achat ; rachat ».";
        }
        Identifier item = Identifier.tryParse(parts[0].trim().toLowerCase(Locale.ROOT));
        if (item == null) {
            return "« " + parts[0].trim() + " » n'est pas un identifiant d'objet.";
        }
        long buy = Coin.parse(parts[1]);
        long sell = Coin.parse(parts[2]);
        if (buy == Coin.BAD) {
            return "« " + parts[1].trim() + " » n'est pas un montant.";
        }
        if (sell == Coin.BAD) {
            return "« " + parts[2].trim() + " » n'est pas un montant.";
        }
        String category = parts.length > 3 ? parts[3] : Offer.LOOSE;
        return put(item, buy, sell, category);
    }

    /**
     * Écrit le catalogue sur le disque.
     *
     * <p>Par un fichier temporaire puis un déplacement : une coupure de courant au milieu d'une
     * écriture laisserait sinon un catalogue tronqué, c'est-à-dire des articles disparus au prochain
     * démarrage. Le déplacement, lui, est atomique sur tous les systèmes de fichiers qui nous
     * intéressent.
     */
    public static boolean inscribe() {
        List<String> lines = new ArrayList<>(BOARD.size() + 24);
        for (String line : HEADER) {
            lines.add(line);
        }
        String aisle = null;
        for (Offer offer : sortedForFile()) {
            if (!offer.category().equals(aisle)) {
                aisle = offer.category();
                lines.add("");
                lines.add("# --- " + aisle + " ---");
            }
            lines.add(pad(offer.item().toString(), 34) + " ; " + pad(Coin.plain(offer.buy()), 10)
                    + " ; " + pad(Coin.plain(offer.sell()), 10) + " ; " + offer.category());
        }
        lines.add("");
        try {
            Files.createDirectories(folder());
            Path temporary = folder().resolve("boutique.txt.tmp");
            Files.write(temporary, lines, StandardCharsets.UTF_8);
            Files.move(temporary, file(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException problem) {
            Lanterne.LOG.error("[BOUTIQUE] {} non écrit : {}", file(), problem.getMessage());
            return false;
        }
    }

    /** Par rayon, puis par identifiant : c'est ce qui rend le fichier relisible. */
    private static List<Offer> sortedForFile() {
        List<Offer> offers = new ArrayList<>(BOARD.values());
        offers.sort((left, right) -> {
            int byAisle = String.CASE_INSENSITIVE_ORDER.compare(left.category(), right.category());
            return byAisle != 0 ? byAisle
                    : left.item().toString().compareTo(right.item().toString());
        });
        return offers;
    }

    private static String pad(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());
    }

    private static final String[] HEADER = {
        "# Lanterne - le catalogue de la boutique administrateur.",
        "#",
        "# CE FICHIER A ETE ENGENDRE a partir des recettes du jeu, puis il est DEVENU LE TIEN :",
        "# il n'est plus jamais reecrit tout seul. Corrige une ligne, puis :",
        "#     /boutique recharger      relit ce fichier a chaud",
        "#     /boutique ecrire         y ecrit le catalogue courant (ecrase)",
        "#     /boutique generer        REFAIT TOUT depuis les recettes (ecrase)",
        "#     /boutique verifier       cherche les boucles d'arbitrage et les nomme",
        "#",
        "# UNE LIGNE PAR ARTICLE, quatre champs separes par des points-virgules :",
        "#",
        "#     objet ; prix d'achat ; prix de rachat ; rayon",
        "#",
        "#   objet          l'identifiant de registre, par exemple minecraft:diamond",
        "#   prix d'achat   ce que le JOUEUR paie pour une unite. Toujours > 0.",
        "#   prix de rachat ce que la BOUTIQUE paie pour une unite. ZERO = non rachete.",
        "#   rayon          le classement a l'ecran. Libre, 24 caracteres au plus.",
        "#",
        "# La virgule et le point marquent tous deux les decimales. Deux decimales au plus.",
        "# Une ligne vide ou commencant par # est ignoree.",
        "# Une ligne fautive est ignoree AVEC SON NUMERO dans le journal : le reste du",
        "# catalogue se charge quand meme.",
        "#",
        "# LA REGLE QUI N'A PAS D'EXCEPTION : le prix de rachat doit rester sous le prix",
        "# d'achat, d'au moins la marge minimale du serveur (config/lanterne-boutique.toml,",
        "# boutique.marge_min_pourcent). Un article qui l'enfreint est refuse, nomme dans le",
        "# journal, et n'entre pas au catalogue - sinon il suffirait d'acheter et de revendre",
        "# en boucle pour fabriquer de l'argent.",
        "#",
        "# COMMENT LES PRIX ONT ETE OBTENUS : seules les matieres premieres ont un prix ecrit",
        "# a la main. Tout le reste vaut la somme de ses ingredients multipliee par le facteur",
        "# de valeur ajoutee, divisee par la quantite produite. C'est ce qui garantit qu'aucune",
        "# recette du jeu - artisanat, fonte, tailleur de pierre, forgeron - ne rapporte quoi",
        "# que ce soit a qui achete les entrees pour revendre la sortie.",
        "#",
        "# SI TU CHANGES UN PRIX A LA MAIN, lance /boutique verifier apres. C'est gratuit et",
        "# cela dit en clair si tu viens d'ouvrir une boucle.",
        "#",
        "# Les prix sont des ANCRES. La derive les fait bouger autour, dans une fourchette",
        "# bornee, selon ce que les joueurs echangent reellement. Ce fichier, lui, ne bouge",
        "# pas tout seul : tu y retrouves toujours tes chiffres.",
    };
}
