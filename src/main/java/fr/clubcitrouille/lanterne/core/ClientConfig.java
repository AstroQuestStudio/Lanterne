package fr.clubcitrouille.lanterne.core;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.client.upscale.Upscale;

/**
 * Les réglages qui ne regardent que l'écran du joueur.
 *
 * <h2>Pourquoi un second fichier, et pourquoi il fallait le faire tout de suite</h2>
 *
 * <p>{@link Config} est enregistré en {@code ModConfig.Type.SERVER}. C'est le bon choix pour tout ce
 * qu'il contenait jusqu'ici : le niveau de détail des créatures, la compression des paquets, le
 * Balai — autant de décisions qui appartiennent à l'administrateur, et qui doivent valoir pour tout
 * le monde.
 *
 * <p>Les modules de rendu n'ont rien à y faire. Le Voile et les coffres statiques ne changent que ce
 * que <b>ce</b> joueur voit sur <b>son</b> écran ; les y laisser aurait eu deux conséquences, et les
 * deux sont fausses : un joueur en multijoueur n'aurait pas pu les régler, et un serveur aurait pu
 * lui imposer la façon dont son ordinateur dessine des coffres.
 *
 * <p>Le fichier client s'appelle {@code config/lanterne-client.toml} et suit le joueur, pas la
 * partie.
 *
 * <h2>Un changement ne prend pas toujours effet tout de suite</h2>
 *
 * <p>La géométrie d'un chunk est calculée une fois puis conservée. Les réglages qui décident de la
 * <em>manière de mailler</em> — les coffres, les feuilles — ne se voient donc qu'après
 * reconstruction, c'est-à-dire au prochain chargement du monde. Le Voile, lui, est consulté à chaque
 * image : il répond immédiatement. C'est écrit dans le commentaire de chaque réglage, parce qu'un
 * joueur qui change une option et ne voit rien conclut que l'option ne sert à rien.
 */
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue SHROUD;
    public static final ModConfigSpec.IntValue SHROUD_DELAY;
    public static final ModConfigSpec.IntValue SHROUD_NEAR;
    public static final ModConfigSpec.IntValue SHROUD_BUDGET;
    public static final ModConfigSpec.IntValue SHROUD_FAR;
    public static final ModConfigSpec.IntValue SHROUD_BULKY;
    public static final ModConfigSpec.BooleanValue STATIC_CHESTS;
    public static final ModConfigSpec.EnumValue<LeafCulling> LEAF_CULLING;
    public static final ModConfigSpec.IntValue ITEM_COPIES;
    public static final ModConfigSpec.BooleanValue VEIL_PARTICLES;
    public static final ModConfigSpec.BooleanValue VEIL_BLOCK_ENTITIES;
    public static final ModConfigSpec.BooleanValue GAUGE;
    public static final ModConfigSpec.BooleanValue LENS;
    public static final ModConfigSpec.EnumValue<Lens.Preset> LENS_PRESET;
    public static final ModConfigSpec.EnumValue<Upscale.Edge> LENS_EDGE;
    public static final ModConfigSpec.BooleanValue LENS_SWELL;
    public static final ModConfigSpec.BooleanValue DIN;
    public static final ModConfigSpec.IntValue DIN_REGION;
    public static final ModConfigSpec.IntValue DIN_ALLOWANCE;
    public static final ModConfigSpec.IntValue DIN_WINDOW;

    public static final ModConfigSpec SPEC;

    /**
     * Quand masquer les faces de feuilles qui se touchent.
     *
     * <h2>Pourquoi ce n'est pas un simple oui ou non</h2>
     *
     * <p>Deux feuilles collées cachent mutuellement la face qu'elles partagent — sauf si les
     * feuilles sont ajourées, auquel cas on voit à travers, et masquer cette face laisse un trou
     * dans l'arbre. Or c'est exactement ce que règle l'option vidéo {@code cutoutLeaves} de 26.1.
     *
     * <p>Un interrupteur unique aurait donc forcé le joueur à choisir entre un arbre correct et un
     * gain de performance, alors que les deux sont compatibles dès qu'on regarde le réglage
     * graphique qu'il a déjà posé.
     */
    public enum LeafCulling {
        /** Masquer seulement quand les feuilles sont opaques — aucun artefact possible. */
        AUTO,
        /** Masquer toujours. Le gain est maximal, au prix de trous visibles en graphismes détaillés. */
        TOUJOURS,
        /** Ne jamais masquer. Le rendu de vanilla, à l'identique. */
        JAMAIS
    }

    static {
        BUILDER.comment(
                "Lanterne - les reglages qui ne regardent que TON ecran.",
                "",
                "Rien ici ne touche au serveur. Un serveur ne peut pas t'imposer ces valeurs, et",
                "tu peux les changer en multijoueur.",
                "",
                "Les reglages marques RECONSTRUCTION ne se voient qu'au prochain chargement du",
                "monde : la geometrie d'un chunk est calculee une fois puis gardee.").push("rendu");

        SHROUD = BUILDER.comment(
                "LE VOILE : une creature cachee par un mur n'est pas preparee pour le rendu.",
                "Vanilla ecarte deja ce qui est hors du champ de vision. Il n'ecarte pas ce qui est",
                "dans le champ mais derriere de la pierre : ferme sous une colline, enclos vu depuis",
                "l'autre cote de sa grange, donjon.",
                "",
                "1000 vaches sous un toit : 29,9 -> 238,8 images par seconde, soit x7,98.",
                "Effet immediat, sans rechargement.")
                .define("voile", true);
        SHROUD_DELAY = BUILDER.comment(
                "Delai avant de reexaminer une meme creature, en millisecondes.",
                "100 (defaut) : une verification toutes les six images a 60 images par seconde.",
                "Les echeances sont ETALEES d'une bete a l'autre - sans cela, mille d'entre elles",
                "expirent a la meme image et le gain part en a-coups.")
                .defineInRange("voile_delai_ms", 100, 0, 1000);
        SHROUD_NEAR = BUILDER.comment(
                "En deca de cette distance, aucune creature n'est jamais voilee.",
                "Sous quelques blocs, l'erreur d'un rayon - un coin de mur, une marche - se verrait",
                "immediatement, et le gain est nul : une bete proche est rarement cachee longtemps.")
                .defineInRange("voile_zone_franche_blocs", 4, 0, 64);
        SHROUD_BUDGET = BUILDER.comment(
                "Rayons autorises par image. Au-dela, les creatures non encore examinees gardent",
                "leur dernier etat - VISIBLE par defaut, jamais l'inverse : un plafond atteint ne",
                "doit pas faire disparaitre une bete.")
                .defineInRange("voile_rayons_par_image", 64, 1, 1024);
        SHROUD_FAR = BUILDER.comment(
                "Au-dela de cette distance, plus aucun rayon n'est lance.",
                "Un rayon long coute cher pour une creature que la distance de rendu d'entites",
                "ecarte souvent deja d'elle-meme.")
                .defineInRange("voile_portee_blocs", 128, 16, 512);
        SHROUD_BULKY = BUILDER.comment(
                "Au-dela de cette taille de boite, une creature n'est jamais voilee.",
                "Cinq points sondes decrivent mal un dragon ou une baleine de mod : une bete dont la",
                "boite depasse largement le bloc depasse aussi du mur.")
                .defineInRange("voile_taille_max_blocs", 4, 1, 64);

        STATIC_CHESTS = BUILDER.comment(
                "COFFRES STATIQUES : un coffre rendu comme un bloc ordinaire.",
                "Un bloc ordinaire est maille UNE FOIS dans son chunk puis dessine avec lui. Un",
                "coffre porte son propre dessinateur, reexecute A CHAQUE IMAGE pour animer un",
                "couvercle - meme ferme, meme a quarante blocs.",
                "",
                "1200 coffres dans le champ : 138,0 -> 200,3 images par seconde, soit x1,45.",
                "",
                "PRIX A PAYER : le couvercle ne s'anime plus a l'ouverture.",
                "Les boites de shulker ne sont PAS concernees : leur couvercle est un retour",
                "d'information utile, pas une decoration.",
                "RECONSTRUCTION.")
                .define("coffres_statiques", true);

        LEAF_CULLING = BUILDER.comment(
                "FEUILLES : masquer les faces que deux feuilles collees se cachent l'une l'autre.",
                "",
                "AUTO     (defaut) : suit l'option video \"Feuilles ajourees\" du jeu. Si elle est",
                "                    COUPEE, les feuilles sont pleines et le masquage est exact.",
                "                    Si elle est ACTIVE, on ne masque rien. Aucun artefact possible.",
                "TOUJOURS          : masquer meme avec les feuilles ajourees. Gain maximal, mais on",
                "                    voit alors des trous dans l'arbre de pres.",
                "JAMAIS            : le rendu de vanilla, a l'identique.",
                "",
                "Sans rapport avec la chute rapide des feuilles, qui est un reglage SERVEUR.",
                "RECONSTRUCTION.")
                .defineEnum("feuilles_masquees", LeafCulling.AUTO);

        ITEM_COPIES = BUILDER.comment(
                "OBJETS AU SOL : exemplaires dessines par pile.",
                "",
                "Vanilla en dessine jusqu'a QUATRE, legerement decales, pour montrer qu'il y en a",
                "plusieurs. Chacun est un rendu complet du modele - meme texture, meme travail, a",
                "quelques centimetres pres. Une ferme qui crache des piles pleines paie donc quatre",
                "fois ce que le sol parait couter.",
                "",
                "1 (defaut) : un seul exemplaire. Une pile de 64 ressemble a un objet seul.",
                "4          : le rendu de vanilla, a l'identique.",
                "",
                "Effet immediat, sans rechargement.")
                .defineInRange("objets_exemplaires", 1, 1, 4);

        VEIL_PARTICLES = BUILDER.comment(
                "LE VOILE ETENDU AUX PARTICULES : celles qu'un mur cache ne naissent pas.",
                "",
                "Vanilla n'ecarte que ce qui est a plus de 32 blocs, plus une fraction selon",
                "l'option 'particules'. Une particule nee DERRIERE UN MUR, a dix blocs, passe. Elle",
                "est alors creee, tickee a chaque image tant qu'elle vit, puis ecartee du dessin si",
                "elle sort du champ - trois couts, dont deux payes en entier pour rien.",
                "",
                "Refuser sa naissance supprime les trois d'un coup, et la supprime aussi de la",
                "memoire : une ferme qui tourne sous une colline cesse d'en accumuler.",
                "",
                "Les particules que le jeu tient a montrer coute que coute (overrideLimiter) ne",
                "sont jamais refusees. Demande le voile actif.")
                .define("voile_particules", true);

        VEIL_BLOCK_ENTITIES = BUILDER.comment(
                "LE VOILE ETENDU AUX COFFRES, PANNEAUX ET AUTRES BLOCS-ENTITES.",
                "",
                "tryExtractRenderState ecarte deja ce qui sort du champ de vision, jamais ce qui est",
                "dans le champ mais derriere de la pierre. Un entrepot de 300 coffres vu depuis",
                "l'exterieur de son batiment les prepare tous les 300, a chaque image, pour n'en",
                "montrer aucun.",
                "",
                "Un bloc en cours de cassage n'est jamais voile : sa fissure est le retour visuel du",
                "minage. Demande le voile actif.")
                .define("voile_blocs_entites", true);

        GAUGE = BUILDER.comment(
                "LA JAUGE : ce que le mod fait, lisible pendant qu'on joue.",
                "",
                "F3 donne une moyenne d'images par seconde, arrondie, recalculee une fois par",
                "seconde. C'est assez pour savoir si le jeu tourne bien ; pas pour COMPARER deux",
                "prereglages, dont la difference se lit dans les a-coups autant que dans la moyenne.",
                "",
                "La jauge ajoute le CENTILE LE PLUS LENT - ce que le joueur ressent comme une",
                "saccade - le prereglage d'echelle en cours, et le nombre de creatures que le Voile",
                "vient d'ecarter. Releve sur une fenetre glissante de deux secondes.")
                .define("jauge", false);
        LENS = BUILDER.comment(
                "LA LENTILLE : rendre le monde plus petit que l'ecran, puis l'y etaler.",
                "",
                "Le cout d'une image est proportionnel au nombre de pixels. Rendre a deux tiers de",
                "chaque dimension, ce n'est pas deux tiers du travail : c'est 45 % de pixels EN",
                "MOINS. A la moitie de chaque dimension, on en supprime les trois quarts.",
                "",
                "C'est le principe de DLSS (NVIDIA), FSR (AMD) et XeSS (Intel). Ils ne different que",
                "par la FACON de remonter l'image, pas par l'origine du gain.",
                "",
                "FSR N'EST PAS RESERVE AUX CARTES AMD : c'est un shader ordinaire, qui tourne sur",
                "n'importe quel processeur graphique. DLSS, lui, exige une NVIDIA RTX, la",
                "bibliotheque du constructeur - non redistribuable - et un contexte Vulkan que",
                "Minecraft n'a pas.",
                "",
                "PRIX A PAYER : l'interface est peinte dans le meme cadre que le monde. Sa TAILLE est",
                "corrigee automatiquement, mais sa NETTETE baisse avec le facteur choisi.",
                "",
                "DESACTIVE PAR DEFAUT : ce module echange de la qualite contre de la vitesse, et cet",
                "arbitrage appartient au joueur.")
                .define("lentille", false);
        LENS_PRESET = BUILDER.comment(
                "Le prereglage, aux noms employes par DLSS comme par FSR.",
                "Le facteur divise CHAQUE DIMENSION ; la part de pixels rendus est son carre inverse.",
                "",
                "NATIF             : 100 % - le jeu tel quel.",
                "ULTRA_QUALITE     :  77 % par dimension, 59 % des pixels. A peine visible.",
                "QUALITE           :  67 %, 44 % des pixels. Le reglage recommande par AMD.",
                "EQUILIBRE         :  59 %, 35 % des pixels.",
                "PERFORMANCE       :  50 %, 25 % des pixels. Un quart du travail de rendu.",
                "ULTRA_PERFORMANCE :  33 %, 11 % des pixels. Pour depanner une machine a bout.")
                .defineEnum("lentille_prereglage", Lens.Preset.QUALITE);
        LENS_EDGE = BUILDER.comment(
                "La nettete appliquee apres la remontee d'echelle.",
                "",
                "Etaler une image la rend floue : c'est mecanique, pas un defaut de reglage. RCAS",
                "raffermit les contours sans reintroduire d'escalier. Chaque cran est une chaine de",
                "post-traitement distincte, parce qu'un uniforme de PostChain est fige a la",
                "construction de la passe.",
                "",
                "AUCUNE  : EASU seul, aucun raffermissement.",
                "DOUCE   : pour qui trouve le raffermissement voyant.",
                "MOYENNE : le defaut.",
                "FORTE   : la limite que RCAS s'autorise.")
                .defineEnum("lentille_nettete", Upscale.Edge.MOYENNE);
        LENS_SWELL = BUILDER.comment(
                "LA HOULE : le facteur suit le taux d'images au lieu d'etre choisi une fois.",
                "",
                "Meme raisonnement que la maree cote serveur, applique au rendu : on vise un taux",
                "d'images et l'on ajuste la finesse pour le tenir. Une scene chargee descend d'un",
                "cran, une scene calme remonte.",
                "",
                "Le prereglage ci-dessus devient alors un PLANCHER : la houle ne descend jamais",
                "en dessous de ce que le joueur a demande.")
                .define("lentille_houle", false);
        DIN = BUILDER.comment(
                "VACARME : au-dela d'un certain nombre, un meme son au meme endroit n'apporte rien.",
                "",
                "SoundEngine n'a AUCUNE limite sur les sons identiques. Le seul plafond est le",
                "nombre de canaux audio, et quand il est atteint les sons suivants sont perdus sans",
                "choix ni priorite. Une ferme qui tue trente creatures dans la meme seconde demande",
                "trente sons de mort au meme endroit : le joueur en entend UN, le moteur les resout",
                "tous les trente.",
                "",
                "CE MODULE NE PROMET PAS D'IMAGES PAR SECONDE. Le son vit sur son propre fil ;",
                "l'economie est du temps processeur et des canaux, pas du temps de rendu. Ce qu'il",
                "apporte est audible : dans une ferme, la bouillie redevient un son.",
                "",
                "Musique, interface et voix ne sont jamais concernees.")
                .define("vacarme", true);
        DIN_REGION = BUILDER.comment(
                "Cote d'une region, en blocs. Deux sons dans le meme cube sont 'au meme endroit'.")
                .defineInRange("vacarme_region_blocs", 8, 1, 64);
        DIN_ALLOWANCE = BUILDER.comment(
                "Sons identiques tolerés dans une region pendant la fenetre.",
                "3 (defaut) : un son isole, ou deux, ou trois, passent toujours. Il faut une RAFALE",
                "pour que ce module agisse.")
                .defineInRange("vacarme_tolerance", 3, 1, 32);
        DIN_WINDOW = BUILDER.comment(
                "Duree de la fenetre glissante, en millisecondes.")
                .defineInRange("vacarme_fenetre_ms", 250, 20, 5000);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private ClientConfig() {}

    /** Applique le fichier client aux réglages, sauf si l'environnement a déjà parlé. */
    public static void apply(ModConfigEvent event) {
        if (event.getConfig().getType() != ModConfig.Type.CLIENT) {
            return;
        }
        if (!(event instanceof ModConfigEvent.Loading) && !(event instanceof ModConfigEvent.Reloading)) {
            return;
        }
        if (Settings.environmentSpoke()) {
            return;
        }
        Settings.applyFromClientConfig();
        Lanterne.LOG.info("[RÉGLAGES] configuration client lue — rendu : {}",
                Settings.describeClient());
    }
}
