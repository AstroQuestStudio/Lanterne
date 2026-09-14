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

    public static final ModConfigSpec.BooleanValue QUOTA;
    public static final ModConfigSpec.IntValue QUOTA_ALLOWANCE;
    public static final ModConfigSpec.IntValue QUOTA_MOST;
    public static final ModConfigSpec.BooleanValue DEBIT;
    public static final ModConfigSpec.IntValue DEBIT_ALLOWANCE;
    public static final ModConfigSpec.IntValue DEBIT_MOST;
    public static final ModConfigSpec.IntValue TOLL_RELAX_PERMILLE;
    public static final ModConfigSpec.IntValue PAY_FEE;

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

    private static boolean quota = true;
    private static long quotaAllowance = 4_000L * Coin.UNIT;
    private static int quotaMost = 90;
    private static boolean debit = true;
    private static long debitAllowance = 20_000L * Coin.UNIT;
    private static int debitMost = 60;
    private static int tollRelaxPermille = 2;
    private static int payFee;

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

        BUILDER.comment(
                "LES PUITS : les endroits ou l'argent CESSE D'EXISTER.",
                "",
                "La derive protege les PRIX. Elle ne protege pas de l'ACCUMULATION, et c'est une",
                "chose que l'on decouvre toujours trop tard. Une ferme a fer automatique ne triche",
                "pas : elle verse, heure apres heure, une somme qu'un joueur ordinaire ne peut pas",
                "approcher. La derive amortit le choc jusqu'a moins cinquante pour cent, puis plus",
                "rien : tanh sature, c'est une borne, pas une pente.",
                "",
                "MESURE hors du jeu (tools/Bourse.java : 20 joueurs, 37 jours, les debits de fermes",
                "reelles). Avec la derive seule :",
                "    masse monetaire            3 790 970 ¤",
                "    le plus riche              1 225 989 ¤   soit 1 599 fois la mediane",
                "    l'exploitant de fermes     60 fois la recette horaire d'un joueur occasionnel",
                "Avec les puits ci-dessous :",
                "    masse monetaire            1 027 091 ¤   (27 %)",
                "    le plus riche                150 494 ¤   soit 196 fois la mediane",
                "    l'exploitant de fermes     9 fois la recette horaire d'un joueur occasionnel",
                "    le nouveau venu            EXACTEMENT LA MEME CHOSE qu'avant : 208 ¤ dans sa",
                "                               premiere semaine, au centime pres.",
                "",
                "LE PRINCIPE. Chaque joueur porte deux compteurs de RECETTES RECENTES, en centimes,",
                "qui montent a chaque vente et redescendent tout seuls. Tant qu'un compteur reste",
                "sous sa FRANCHISE, la boutique paie le plein tarif, sans exception. Au-dela, elle",
                "paie de moins en moins :",
                "",
                "    retenue = retenue_max * tanh( (compteur - franchise) / franchise )",
                "",
                "LA FRANCHISE EST UNE VALEUR, PAS UN NOMBRE D'OBJETS. Le catalogue contient le",
                "diamant a 240 ¤ et le pave a 0,12 ¤ : un quota en unites laisserait passer une ferme",
                "a diamants et etranglerait un batisseur qui revend ses gravats. Un seul nombre vaut",
                "donc pour les mille trois cents articles.",
                "",
                "CES REGLAGES NE PEUVENT PAS OUVRIR DE BOUCLE D'ARBITRAGE. Une retenue ne fait que",
                "BAISSER un prix de rachat, donc l'inegalite de surete du bloc [generation] en est",
                "d'autant plus respectee. Il n'y a rien a reverifier apres les avoir changes.").push("puits");

        QUOTA = BUILDER.comment(
                "LE QUOTA : ce que la boutique reprend a un joueur d'un MEME article.",
                "C'est la reponse directe aux fermes automatiques, et c'est le puits principal.")
                .define("quota_actif", true);
        QUOTA_ALLOWANCE = BUILDER.comment(
                "La franchise du quota, en PIECES, par joueur et par article.",
                "4000 ¤ representent environ 1 100 lingots de fer, 16 diamants ou 33 000 paves.",
                "Une journee de ferme les depasse ; une semaine de jeu ordinaire, jamais.",
                "Le compteur redescend tout seul : voir detente_pour_mille ci-dessous.")
                .defineInRange("quota_franchise", 4000, 1, 10_000_000);
        QUOTA_MOST = BUILDER.comment(
                "La retenue maximale du quota, en POURCENT.",
                "90 signifie : tres au-dela de la franchise, la boutique paie un dixieme du cours.",
                "Le message au joueur est << va vendre autre chose >>, et il lui reste mille trois",
                "cents articles a plein tarif pour le faire.")
                .defineInRange("quota_retenue_max", 90, 0, 100);

        DEBIT = BUILDER.comment(
                "LE DEBIT : ce que la boutique reprend a un joueur EN TOUT, articles confondus.",
                "",
                "Il existe parce que le quota seul se contourne en diversifiant : dix fermes",
                "differentes, c'est dix fois la franchise au plein tarif. La mesure le dit -",
                "avec le quota seul, l'exploitant ressort encore a SEIZE fois le joueur",
                "occasionnel ; avec les deux, a NEUF.")
                .define("debit_actif", true);
        DEBIT_ALLOWANCE = BUILDER.comment(
                "La franchise du debit, en PIECES, par joueur, tous articles confondus.",
                "20000 ¤ de recettes recentes correspondent a environ 11 500 ¤ gagnes par jour.",
                "Dans la simulation, le mineur regulier - trois heures par jour, du minerai - reste",
                "SOUS cette franchise et ne subit AUCUNE retenue. Baisse-la si tes joueurs vendent",
                "davantage que les miens.")
                .defineInRange("debit_franchise", 20_000, 1, 100_000_000);
        DEBIT_MOST = BUILDER.comment(
                "La retenue maximale du debit, en POURCENT.")
                .defineInRange("debit_retenue_max", 60, 0, 100);

        TOLL_RELAX_PERMILLE = BUILDER.comment(
                "Part des compteurs de puits effacee a chaque detente, en POUR MILLE.",
                "",
                "C'EST LE REGLAGE LE MOINS VISIBLE ET LE PLUS DECISIF : il fixe la PERIODE sur",
                "laquelle une franchise compte.",
                "    20 pour mille -> demi-vie de 3 heures. Le compteur est remis a neuf entre deux",
                "                     soirees : la franchise devient << par session >>, et le",
                "                     dispositif est invisible pour qui joue tous les jours,",
                "                     c'est-a-dire pour celui qu'il vise.",
                "     2 pour mille -> demi-vie de 29 heures. La franchise devient << par jour >> et",
                "                     s'accumule sur celui qui vend tous les jours. C'est ce regime",
                "                     qui repond aux fermes, et c'est le defaut.",
                "A zero, les compteurs ne s'effacent jamais : la premiere ferme condamne son",
                "proprietaire a vie. Ne mets pas zero.",
                "La periode est celle de derive.detente_ticks - les deux detentes ont lieu au meme",
                "instant, a des rythmes differents.")
                .defineInRange("detente_pour_mille", 2, 0, 1000);

        PAY_FEE = BUILDER.comment(
                "FRAIS DE VIREMENT entre joueurs, en POURCENT du montant. ETEINT PAR DEFAUT.",
                "",
                "Il ferme la seule porte de sortie des deux puits ci-dessus : un exploitant peut",
                "payer trois amis pour vendre a sa place, et multiplier sa franchise par quatre.",
                "",
                "Il est eteint quand meme, et c'est un choix, pas un oubli. Sur un serveur de vingt",
                "personnes qui se connaissent, l'entraide est ce qui fait tenir le groupe, et la",
                "taxer pour fermer une porte que presque personne n'emprunte coute plus qu'elle ne",
                "rapporte. Allume-le le jour ou tu verras le contournement, pas avant.",
                "",
                "Les frais sont pris sur ce qui ARRIVE : celui qui tape 100 voit bien 100 quitter",
                "son compte, et le destinataire recoit 100 moins les frais.")
                .defineInRange("virement_frais_pourcent", 0, 0, 50);
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
        quota = QUOTA.get();
        quotaAllowance = (long) QUOTA_ALLOWANCE.get() * Coin.UNIT;
        quotaMost = QUOTA_MOST.get();
        debit = DEBIT.get();
        debitAllowance = (long) DEBIT_ALLOWANCE.get() * Coin.UNIT;
        debitMost = DEBIT_MOST.get();
        tollRelaxPermille = TOLL_RELAX_PERMILLE.get();
        payFee = PAY_FEE.get();
        warnIfUnsafe();
        warnIfLeaky();
    }

    /**
     * Prévient si les puits sont allumés mais ne peuvent rien retenir.
     *
     * <p>Deux réglages se contredisent silencieusement, et c'est le genre de chose qu'on ne voit
     * jamais : une détente à zéro condamne le premier joueur qui vend beaucoup à une retenue
     * perpétuelle, et une retenue maximale à zéro allume un dispositif qui ne fait rien. Aucun des
     * deux n'est une erreur de syntaxe ; les deux sont des erreurs de sens.
     */
    private static void warnIfLeaky() {
        if (!quota && !debit) {
            return;
        }
        if (tollRelaxPermille <= 0) {
            fr.clubcitrouille.lanterne.Lanterne.LOG.warn(
                    "[BOUTIQUE] puits.detente_pour_mille vaut zéro : les compteurs de quota ne"
                            + " redescendront JAMAIS. Le premier joueur qui vend beaucoup gardera sa"
                            + " retenue pour toujours. Mets 2.");
        }
        if (quota && quotaMost <= 0 && debit && debitMost <= 0) {
            fr.clubcitrouille.lanterne.Lanterne.LOG.warn(
                    "[BOUTIQUE] les puits sont allumés mais leurs deux retenues maximales valent"
                            + " zéro : ils ne retiennent rien. Éteins-les, ou donne-leur une"
                            + " retenue.");
        }
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

    // --- Les puits ---------------------------------------------------------

    public static boolean quotaOn() {
        return quota && quotaMost > 0;
    }

    /** La franchise du quota, en centimes, par joueur et par article. */
    public static long quotaAllowance() {
        return quotaAllowance;
    }

    public static int quotaMost() {
        return quotaMost;
    }

    public static boolean debitOn() {
        return debit && debitMost > 0;
    }

    /** La franchise du débit, en centimes, par joueur, tous articles confondus. */
    public static long debitAllowance() {
        return debitAllowance;
    }

    public static int debitMost() {
        return debitMost;
    }

    /** Vrai si l'un des deux puits peut retenir quelque chose. */
    public static boolean tollOn() {
        return quotaOn() || debitOn();
    }

    public static int tollRelaxPermille() {
        return tollRelaxPermille;
    }

    /** Les frais de virement, en pourcent. Zéro par défaut. */
    public static int payFee() {
        return payFee;
    }
}
