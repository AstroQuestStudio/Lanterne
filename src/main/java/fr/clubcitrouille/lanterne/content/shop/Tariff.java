package fr.clubcitrouille.lanterne.content.shop;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Le tarif : les règles du marché, dans {@code config/lanterne-boutique.toml}.
 *
 * <h2>Ce qui est ici, et ce qui n'y est pas</h2>
 *
 * <p>Ce fichier ne contient <b>aucun prix</b>. Les prix sont un catalogue de plusieurs dizaines de
 * lignes que l'on édite à la main ou par commande, et les faire tenir dans un TOML de réglages aurait
 * donné un fichier illisible où la moindre correction de prix demande de relire la syntaxe d'un
 * tableau de tableaux. Ils vivent donc dans {@code config/lanterne/boutique.txt} — voir {@link Stall}.
 *
 * <p>Ici ne se trouvent que les <b>règles</b> : le solde de départ, la forme de la dérive, les
 * plafonds. Ce sont des nombres isolés, on les change rarement, et un TOML commenté est exactement le
 * bon support pour cela.
 *
 * <h2>Pourquoi un fichier de type SERVER</h2>
 *
 * <p>Le client ne lit jamais ces valeurs. Il reçoit des prix déjà calculés et un solde déjà arrêté :
 * c'est la seule façon qu'un client modifié ne puisse pas s'inventer une amplitude de dérive de mille
 * pour cent. Le symbole de la monnaie est la seule exception apparente — le client l'affiche — mais il
 * lui parvient dans le paquet de synchronisation, et non par ce fichier.
 *
 * <h2>Les valeurs sont recopiées, pas relues</h2>
 *
 * <p>Même discipline que {@code content.painting.Studio} : {@code ModConfigSpec.IntValue.get()} lève
 * une exception tant que le fichier n'est pas chargé et coûte une consultation de table à chaque
 * appel. La grille de la boutique en demanderait plusieurs centaines par image. Les champs
 * {@code static} ci-dessous sont donc la vérité des chemins chauds, et {@link #apply} les recopie une
 * fois par chargement.
 */
public final class Tariff {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ACTIVE;
    public static final ModConfigSpec.ConfigValue<String> SYMBOL;
    public static final ModConfigSpec.IntValue START;
    public static final ModConfigSpec.IntValue BATCH;
    public static final ModConfigSpec.IntValue MARGIN;
    public static final ModConfigSpec.BooleanValue BUYBACK;
    public static final ModConfigSpec.BooleanValue PAY;
    public static final ModConfigSpec.IntValue BOARD_SIZE;

    public static final ModConfigSpec.IntValue GEN_FACTOR;
    public static final ModConfigSpec.IntValue GEN_SELL;

    public static final ModConfigSpec.BooleanValue DRIFT;
    public static final ModConfigSpec.IntValue AMPLITUDE;
    public static final ModConfigSpec.IntValue ELASTICITY;
    public static final ModConfigSpec.IntValue RELAX_TICKS;
    public static final ModConfigSpec.IntValue RELAX_PERMILLE;

    public static final ModConfigSpec SPEC;

    private static boolean active = true;
    private static String symbol = "¤";
    private static long start = 100L * Coin.UNIT;
    private static int batch = 4096;
    private static int margin = 10;
    private static boolean buyback = true;
    private static boolean pay = true;
    private static int boardSize = 10;

    private static int genFactor = 120;
    private static int genSell = 25;

    private static boolean drift = true;
    private static int amplitude = 50;
    private static int elasticity = 2048;
    private static int relaxTicks = 6000;
    private static int relaxPermille = 60;

    static {
        BUILDER.comment(
                "Lanterne - la boutique et l'economie.",
                "",
                "CE FICHIER NE CONTIENT AUCUN PRIX. Les prix sont dans :",
                "  config/lanterne/boutique.txt",
                "un fichier texte d'une ligne par article, recharge a chaud par",
                "  /boutique recharger",
                "",
                "Ici se trouvent les REGLES : combien on demarre avec, comment les prix bougent,",
                "ce qu'on s'interdit de depasser.").push("economie");

        ACTIVE = BUILDER.comment(
                "L'economie et la boutique, en un seul interrupteur.",
                "A faux : les commandes repondent qu'elles sont desactivees, l'ecran refuse de",
                "s'ouvrir, et AUCUN solde n'est perdu - les comptes restent dans la sauvegarde et",
                "reapparaissent tels quels si tu rallumes.")
                .define("active", true);
        SYMBOL = BUILDER.comment(
                "Le symbole de la monnaie, affiche apres chaque montant.",
                "Il est envoye au client avec les prix : il n'a pas a etre le meme des deux cotes,",
                "et un client qui n'a pas ce fichier l'affiche quand meme.",
                "Le defaut est le signe monetaire generique U+00A4, present dans la police du jeu.",
                "Trois caracteres au maximum.")
                .define("symbole", "¤");
        START = BUILDER.comment(
                "Solde de depart, en PIECES, credite a la premiere connexion.",
                "Il n'est credite qu'UNE FOIS : le compte est cree a la premiere connexion et le",
                "solde suivant est celui qu'on a laisse. Un joueur qui se ruine ne se refait pas en",
                "se deconnectant.")
                .defineInRange("solde_depart", 100, 0, 10_000_000);
        PAY = BUILDER.comment(
                "Autoriser les virements entre joueurs (/banque payer).",
                "A faux, l'argent ne circule qu'entre un joueur et la boutique. C'est plus terne,",
                "mais cela ferme la porte aux arnaques, qui sont le principal motif de plainte sur",
                "un serveur ou l'argent circule librement.")
                .define("virements", true);
        BOARD_SIZE = BUILDER.comment(
                "Nombre de noms affiches par /banque classement.")
                .defineInRange("classement_taille", 10, 3, 50);
        BUILDER.pop();

        BUILDER.comment("La boutique administrateur : ce qu'elle accepte de faire.").push("boutique");

        BUYBACK = BUILDER.comment(
                "La boutique rachete-t-elle les objets ? (l'onglet Vendre)",
                "A faux, l'onglet reste visible mais toute vente est refusee avec un message clair.",
                "Coupe-le si tu veux une boutique qui ne fait qu'injecter des objets, sans jamais",
                "reprendre d'argent aux joueurs.")
                .define("rachat", true);
        BATCH = BUILDER.comment(
                "Nombre maximal d'unites par transaction.",
                "Deux raisons, et la seconde est la vraie : d'abord un joueur ne peut pas ranger",
                "plus de 36 piles, donc au-dela de 2304 unites d'un objet empilable par 64 la",
                "transaction serait refusee de toute facon ; ensuite le PRIX EST FIGE pour toute la",
                "transaction, et un lot illimite permettrait d'acheter tout le stock d'un objet au",
                "prix d'avant la derive.")
                .defineInRange("lot_max", 4096, 1, 10_000);
        MARGIN = BUILDER.comment(
                "Ecart minimal, en POURCENT du prix d'achat, entre acheter et revendre.",
                "",
                "C'est la regle qui empeche la boucle infinie de profit. Un article dont le prix de",
                "revente serait superieur ou egal au prix d'achat permettrait d'acheter et revendre",
                "sans fin ; un ecart d'un centime laisserait la meme boucle rapporter un centime par",
                "aller-retour, ce qui est exactement aussi grave, en plus lent.",
                "",
                "10 % signifie : un article achete 100 est rachete 90 au maximum. Un article propose",
                "au-dela est REFUSE a l'enregistrement, avec la raison, et le fichier n'est pas",
                "modifie.")
                .defineInRange("marge_min_pourcent", 10, 0, 90);
        BUILDER.pop();

        BUILDER.comment(
                "La GENERATION du catalogue a partir des recettes du jeu.",
                "",
                "Au premier demarrage, config/lanterne/boutique.txt n'existe pas : il est ENGENDRE.",
                "Seules les matieres premieres ont un prix ecrit a la main (voir la classe Seam) ;",
                "tout le reste est deduit des recettes, y compris les objets des mods des lors que",
                "leurs recettes redescendent a des matieres premieres connues.",
                "",
                "    prix(sortie) = arrondi_sup( somme(prix des ingredients) * facteur / quantite )",
                "    rachat       = prix d'achat * rachat_pourcent",
                "",
                "Ensuite le fichier est A TOI : il n'est JAMAIS reecrit tout seul. Pour repartir de",
                "zero apres un changement de version ou l'ajout d'un mod : /boutique generer.",
                "Pour verifier qu'aucune boucle d'arbitrage n'existe : /boutique verifier.").push("generation");

        GEN_FACTOR = BUILDER.comment(
                "FACTEUR de valeur ajoutee, en pourcent. 120 = un objet fabrique vaut 1,2 fois ses",
                "ingredients.",
                "Il ne represente pas une marge de commercant : il represente ce que le modele ne",
                "sait pas compter - le combustible du four, l'usure de l'outil, le temps.",
                "Le garder STRICTEMENT au-dessus de 100 a une consequence qui depasse l'equilibrage :",
                "un cycle de recettes ne peut alors jamais faire BAISSER un prix, donc le calcul",
                "converge toujours, sans avoir a detecter les cycles.")
                .defineInRange("facteur_pourcent", 120, 101, 400);
        GEN_SELL = BUILDER.comment(
                "RACHAT des articles engendres, en pourcent de leur prix d'achat.",
                "",
                "LA CONDITION A TENIR, et elle n'est pas intuitive :",
                "",
                "    rachat * facteur * (1 + amplitude) / (1 - amplitude)  <  1",
                "",
                "Le dernier terme est la derive. Deux articles peuvent deriver en sens CONTRAIRE :",
                "il suffit que les ingredients soient au plancher pendant que le produit est au",
                "plafond pour qu'une boucle s'ouvre. Avec les valeurs par defaut :",
                "    0,25 * 1,20 * 3,00 = 0,90   -> il reste 10 % de marge.",
                "",
                "Mesure faite sur les 1 445 recettes de vanilla : a 40 % de rachat, 1 290 recettes",
                "deviennent rentables aux extremes de la derive. A 25 %, aucune - le pire rapport",
                "mesure est 0,944. Le seuil est entre 27 et 28 %.",
                "",
                "Si tu montes l'amplitude de la derive, BAISSE ce nombre. Le serveur verifie la",
                "condition au chargement et le dit dans le journal si elle est enfreinte.")
                .defineInRange("rachat_pourcent", 25, 1, 90);
        BUILDER.pop();

        BUILDER.comment(
                "La derive : les prix suivent ce que les joueurs echangent reellement.",
                "",
                "FORMULE. Soit v le volume net d'un article - les unites achetees a la boutique",
                "moins celles qui lui ont ete vendues - accumule depuis la derniere remise a zero.",
                "Soit A l'amplitude et E l'elasticite ci-dessous. Le facteur applique aux DEUX prix",
                "de l'article est :",
                "",
                "    f = 1 + A * tanh(v / E)",
                "",
                "tanh est bornee par -1 et +1 : le facteur reste donc dans [1-A ; 1+A], quel que",
                "soit le volume. Aucune derive ne peut s'emballer, pas meme apres un milliard",
                "d'achats. (En arithmetique flottante tanh sature a 1,0 au-dela de |x| ~ 19 : la",
                "borne est donc ATTEINTE, jamais depassee. Verifie hors du jeu.)",
                "",
                "LE MEME FACTEUR DES DEUX COTES. C'est le point important. Le prix d'achat et le",
                "prix de revente sont multiplies par le meme f, donc leur RAPPORT ne change jamais :",
                "la marge minimale garantie a l'enregistrement reste garantie pour toujours. Faire",
                "monter l'achat et descendre la revente aurait ete plus << realiste >> et aurait",
                "permis, dans l'autre sens, de les croiser.").push("derive");

        DRIFT = BUILDER.comment(
                "Les prix bougent-ils ? A faux, ils restent exactement ceux du catalogue.",
                "Les volumes continuent d'etre comptes : rallumer la derive ne repart pas de zero.")
                .define("active", true);
        AMPLITUDE = BUILDER.comment(
                "A, en POURCENT : de combien un prix peut s'ecarter de son ancre, au plus.",
                "50 signifie que le prix reste entre la moitie et une fois et demie celui du",
                "catalogue. C'est une borne dure, jamais approchee en pratique.")
                .defineInRange("amplitude_pourcent", 50, 0, 90);
        ELASTICITY = BUILDER.comment(
                "E, en UNITES : le volume net a partir duquel la derive est bien installee.",
                "A v = E, tanh(1) = 0,76 : on est aux trois quarts de l'amplitude. A v = E/4, on",
                "n'est qu'au quart. Monte E pour un marche lourd, ou il faut beaucoup echanger pour",
                "deplacer un prix ; baisse-le pour un marche nerveux.",
                "2048 = trente-deux piles de 64. Sur un serveur d'une dizaine de joueurs, cela",
                "represente quelques jours de commerce sur un article populaire.")
                .defineInRange("elasticite", 2048, 1, 1_000_000);
        RELAX_TICKS = BUILDER.comment(
                "Periode de DETENTE, en ticks (20 ticks = 1 seconde).",
                "Sans detente, un article massivement achete une fois resterait cher a jamais. La",
                "detente ramene lentement les volumes vers zero, donc les prix vers leur ancre.",
                "6000 ticks = 5 minutes.")
                .defineInRange("detente_ticks", 6000, 20, 1_728_000);
        RELAX_PERMILLE = BUILDER.comment(
                "Part du volume effacee a chaque detente, en POUR MILLE.",
                "60 pour mille toutes les 5 minutes donne une demi-vie d'environ 56 minutes de",
                "serveur allume. A zero, les volumes ne s'effacent jamais.",
                "Note : la detente finit toujours a ZERO EXACTEMENT, et non a un residu, parce que",
                "le dernier pas retire au moins une unite - une division entiere seule se serait",
                "arretee sur un petit volume et aurait laisse un prix legerement decale pour",
                "toujours.")
                .defineInRange("detente_pour_mille", 60, 0, 1000);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private Tariff() {}

    /**
     * Recopie les réglages dans les champs lus par les chemins chauds.
     *
     * <p>Le test sur l'identité du {@code SPEC} reprend celui de {@code core.Config} :
     * {@code ModConfigEvent} est émis pour toutes les configurations du mod, et y répondre sans
     * vérifier écraserait nos valeurs à chaque chargement d'un autre fichier.
     */
    public static void apply(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        if (!(event instanceof ModConfigEvent.Loading) && !(event instanceof ModConfigEvent.Reloading)) {
            return;
        }
        active = ACTIVE.get();
        String wanted = SYMBOL.get();
        symbol = wanted == null || wanted.isBlank() ? "¤"
                : wanted.length() > 3 ? wanted.substring(0, 3) : wanted;
        start = (long) START.get() * Coin.UNIT;
        batch = BATCH.get();
        margin = MARGIN.get();
        buyback = BUYBACK.get();
        pay = PAY.get();
        boardSize = BOARD_SIZE.get();
        genFactor = GEN_FACTOR.get();
        genSell = GEN_SELL.get();
        drift = DRIFT.get();
        amplitude = AMPLITUDE.get();
        elasticity = ELASTICITY.get();
        relaxTicks = RELAX_TICKS.get();
        relaxPermille = RELAX_PERMILLE.get();
        warnIfUnsafe();
    }

    /**
     * Vérifie la condition qui interdit les boucles d'arbitrage, et le dit si elle est enfreinte.
     *
     * <h2>D'où vient cette inégalité</h2>
     *
     * <p>Un joueur achète les ingrédients et revend le produit. Il gagne
     * {@code rachat × prix(produit)} et dépense {@code somme(prix des ingrédients)}. Comme le
     * générateur pose {@code prix(produit) ≥ somme × facteur}, le rapport vaut
     * {@code rachat × facteur} — inférieur à 1 dès que le rachat est raisonnable.
     *
     * <p><b>La dérive change cela.</b> Deux articles dérivent indépendamment : les ingrédients
     * peuvent être au plancher {@code (1 − A)} pendant que le produit est au plafond
     * {@code (1 + A)}. Le rapport devient {@code rachat × facteur × (1 + A)/(1 − A)}, et c'est lui
     * qui doit rester sous 1.
     *
     * <p>L'avertissement n'empêche rien : un administrateur qui sait ce qu'il fait — parce qu'il a
     * coupé la dérive, par exemple — n'a pas à voir son serveur refuser de démarrer. Mais il doit
     * l'avoir lu.
     */
    private static void warnIfUnsafe() {
        if (!drift || amplitude <= 0) {
            return;
        }
        double spread = (100.0 + amplitude) / (100.0 - amplitude);
        double ratio = genSell / 100.0 * (genFactor / 100.0) * spread;
        if (ratio >= 1.0) {
            fr.clubcitrouille.lanterne.Lanterne.LOG.warn(
                    "[BOUTIQUE] réglages dangereux : rachat {} % × facteur {} % × dérive {} donne "
                            + "{} — au-delà de 1, un joueur peut acheter des ingrédients au plancher "
                            + "et revendre le produit au plafond avec bénéfice. Baisse "
                            + "generation.rachat_pourcent ou derive.amplitude_pourcent, puis "
                            + "« /boutique generer » et « /boutique verifier ».",
                    genSell, genFactor, String.format(java.util.Locale.ROOT, "%.2f", spread),
                    String.format(java.util.Locale.ROOT, "%.3f", ratio));
        }
    }

    /** Le rapport de l'inégalité de sûreté. Strictement sous 1, aucune boucle n'est possible. */
    public static double safety() {
        if (!drift || amplitude <= 0) {
            return genSell / 100.0 * (genFactor / 100.0);
        }
        return genSell / 100.0 * (genFactor / 100.0) * ((100.0 + amplitude) / (100.0 - amplitude));
    }

    public static boolean active() {
        return active;
    }

    public static String symbol() {
        return symbol;
    }

    /** Solde de départ, en centimes. */
    public static long start() {
        return start;
    }

    public static int batch() {
        return batch;
    }

    public static int margin() {
        return margin;
    }

    public static boolean buyback() {
        return buyback;
    }

    public static boolean pay() {
        return pay;
    }

    public static int boardSize() {
        return boardSize;
    }

    /** Le facteur de valeur ajoutée, en pourcent. */
    public static int genFactor() {
        return genFactor;
    }

    /** Le rachat des articles engendrés, en pourcent du prix d'achat. */
    public static int genSell() {
        return genSell;
    }

    public static boolean drift() {
        return drift;
    }

    public static int amplitude() {
        return amplitude;
    }

    public static int elasticity() {
        return elasticity;
    }

    public static int relaxTicks() {
        return relaxTicks;
    }

    public static int relaxPermille() {
        return relaxPermille;
    }
}
