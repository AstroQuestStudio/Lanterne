package fr.clubcitrouille.lanterne.core;

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
    public static final ModConfigSpec.BooleanValue HORIZON;
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
    public static final ModConfigSpec.BooleanValue REGARD;
    public static final ModConfigSpec.BooleanValue LENS;
    public static final ModConfigSpec.EnumValue<Lens.Preset> LENS_PRESET;
    public static final ModConfigSpec.EnumValue<Upscale.Edge> LENS_EDGE;
    public static final ModConfigSpec.BooleanValue LENS_AA;
    public static final ModConfigSpec.BooleanValue LENS_SWELL;
    public static final ModConfigSpec.BooleanValue LENS_TEMPORAL;
    public static final ModConfigSpec.ConfigValue<String> LENS_NUANCIER;
    public static final ModConfigSpec.BooleanValue TAMPON;
    public static final ModConfigSpec.BooleanValue DIN;
    public static final ModConfigSpec.IntValue DIN_REGION;
    public static final ModConfigSpec.IntValue DIN_ALLOWANCE;
    public static final ModConfigSpec.IntValue DIN_WINDOW;
    public static final ModConfigSpec.BooleanValue EMPREINTE;
    public static final ModConfigSpec.BooleanValue GUET;
    public static final ModConfigSpec.BooleanValue SONDE_INDEXATION;

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
        HORIZON = BUILDER.comment(
                "L'HORIZON : au-dela d'une certaine foule, les creatures les plus LOINTAINES ne sont",
                "plus preparees. Le voile n'ecarte que ce qu'un mur cache ; il ne peut rien contre",
                "dix mille vaches a decouvert, et c'est ce cas-la que l'horizon vise.",
                "",
                "En deca de 1500 creatures preparees, il ne fait RIEN. Au-dela, il rapproche sa",
                "limite jusqu'a revenir dans le budget, sans jamais descendre sous 32 blocs - une",
                "bete avec laquelle on peut interagir ne disparait donc jamais.",
                "",
                "MESURE EN JEU le 19 septembre 2026 (banc lab/Glass.java, scene RING, 10000 vaches",
                "a decouvert, mesure AVEC/SANS sur la meme session) :",
                "  - OpenGL : 7,4 -> 92,1 images/seconde, soit x12,41.",
                "  - Vulkan (backend reellement force, pas suppose) : 8,1 -> 30,1 images/seconde,",
                "    soit x3,71 - le gain brut de rendu est comparable (x13 environ dans les deux",
                "    cas), mais une fois le rendu debloque, le tick serveur de dix mille creatures",
                "    devient le nouveau plafond sous Vulkan. Piste ouverte, pas encore chiffree :",
                "    Cadence/Foule visent deja la decision d'IA, pas le tick brut de mouvement.",
                "ACTIVE PAR DEFAUT sur la base de cette mesure.")
                .define("horizon", true);
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
        REGARD = BUILDER.comment(
                "LE REGARD : en visant un bloc ou une entite, une tooltip dit ce qu'il y a vraiment",
                "dedans - dans l'esprit de Jade/HWYLA, mais construite avec les API de ce moteur et",
                "reliee aux VRAIS modules de Lanterne plutot que de deviner.",
                "",
                "BLOC : nom, mod d'origine, etat de redstone si pertinent, apercu du contenu pour un",
                "conteneur (demande au serveur - voir content.regard.Regard - un client ne connait",
                "JAMAIS le contenu d'un coffre qu'il n'a pas ouvert), barre de cuisson/combustible",
                "pour un four, et le VRAI debit d'un entonnoir selon que \"Entonnoirs par lots\" est",
                "actif sur ce serveur.",
                "",
                "ENTITE : nom, vie avec barre, equipement visible, et pour une creature non protegee,",
                "la cadence de simulation ESTIMEE que \"Niveau de detail\" lui applique - une fenetre",
                "sur ce que Lanterne decide, qu'aucun mod d'inspection generaliste ne peut montrer.",
                "",
                "OBJET AU SOL : la VRAIE portee et frequence de fusion appliquees par \"Fusion des",
                "objets\" sur ce serveur, au lieu du chiffre vanilla si ce module est actif.",
                "",
                "Coute une lecture de blocstate/entite par image quand actif, et au plus une petite",
                "requete reseau toutes les quelques images UNIQUEMENT quand la cible est un conteneur",
                "reel - voir Radiographie pour la mesure. Desactive : cout nul, rien n'est evalue.")
                .define("regard", true);
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
        LENS_AA = BUILDER.comment(
                "L'ANTICRENELAGE, applique AVANT la remontee d'echelle.",
                "",
                "La documentation d'AMD pose une condition d'emploi a FSR 1.0, sous le titre",
                "\"Expected input\" : \"Image should already be well anti-aliased by a technique",
                "like TAA, MSAA etc.\" Ce n'est pas une recommandation, c'est une precondition.",
                "",
                "Or Minecraft n'anticrenele rien par defaut. Sans cette passe, EASU recoit des",
                "marches d'escalier et ne les lisse pas : il les AGRANDIT, et RCAS les raffermit",
                "ensuite. C'est ce qui fait \"voir les pixels\", d'autant plus que le facteur est",
                "fort.",
                "",
                "Prix : une passe plein ecran a la resolution REDUITE, donc sur 44 % des pixels au",
                "prereglage Qualite. Les zones plates sortent au premier test.",
                "",
                "A couper seulement pour comparer : le defaut est actif parce que l'auteur de",
                "l'algorithme le demande.")
                .define("lentille_anticrenelage", true);
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
        LENS_TEMPORAL = BUILDER.comment(
                "ACCUMULATION TEMPORELLE : une premiere brique de remontee facon FSR 2/DLSS,",
                "posee AVANT EASU/RCAS plutot qu'a la place.",
                "",
                "FSR 1.0 (ci-dessus) ne regarde qu'UNE image : sa nettete est plafonnee par ce",
                "qu'un seul instant contient. Ce module accumule plusieurs images basse",
                "resolution decalees (suite de Halton) en reprojetant l'historique sur l'image",
                "courante, pour reconstituer plus de details qu'une seule image n'en porte.",
                "",
                "VERIFIE EN JEU REEL (monde importe, capture automatique via Snap, camera mise",
                "en mouvement par Vertige - un client sans main humaine derriere n'a sinon aucun",
                "mouvement a photographier). Le premier essai melangeait 90% d'historique sans",
                "aucun rejet : un panoramique laissait un flou/une trainee nette sur les bords a",
                "fort contraste, absente en EASU/RCAS seuls - defaut reel, pas suppose. Corrige",
                "par un serrage de voisinage (rejette un historique carrement faux) ET un poids",
                "adaptatif au mouvement apparent (l'historique valide mais trop souvent",
                "reechantillonne s'efface progressivement quand la camera bouge). A l'arret, ou",
                "sur un panoramique rapide mais plausible (90 deg/s), le resultat est desormais",
                "indiscernable d'EASU/RCAS seuls a l'oeil. Sur une rotation deliberement extreme",
                "(220 deg/s, une toupie, pas un joueur), un flou residuel leger subsiste -",
                "limite connue et acceptee plutot que masquee.",
                "",
                "ACTIVE PAR DEFAUT depuis cette verification, independant du reglage FSR",
                "ci-dessus (n'a d'effet que si lentille l'est aussi).")
                .define("lentille_temporelle", true);
        LENS_NUANCIER = BUILDER.comment(
                "NUANCIER ACTIF : le nom d'un dossier depose sous config/lanterne/shaderpacks/",
                "dont la chaine de post-traitement remplace celle calculee depuis la nettete et",
                "l'anticrenelage ci-dessus.",
                "",
                "Avant ce reglage, un nuancier depose ne devenait actif QUE si le nom de son JSON",
                "tombait par hasard sur l'identifiant que la nettete courante aurait demande",
                "(\"upscale_aa_moyenne\", par exemple) - un mecanisme reel mais indirect, qui ne",
                "permettait pas de choisir un nuancier PAR NOM. Celui-ci le permet : la chaine",
                "demandee est \"lanterne:<ce que tu ecris ici>\", quels que soient par ailleurs les",
                "reglages de nettete et d'anticrenelage ci-dessus (qui ne servent alors plus qu'a",
                "la chaine integree, ignoree tant que ce reglage n'est pas vide).",
                "",
                "VIDE (defaut) : rien ne change, la chaine integree au mod reste utilisee.",
                "\"club_citrouille\" : le nuancier de demonstration ecrit automatiquement au premier",
                "lancement dans config/lanterne/shaderpacks/club_citrouille/ - occlusion ambiante",
                "ecran-espace depuis lanterne:normal, posee en quatrieme passe derriere le meme",
                "aa_edge/fsr_easu/fsr_rcas que la chaine par defaut. Voir Nuancier.java.",
                "",
                "N'a d'effet que si la lentille (ci-dessus) est active : ce reglage choisit QUELLE",
                "chaine tourne, pas si la mise a l'echelle tourne du tout.")
                .define("lentille_nuancier_actif", "");
        TAMPON = BUILDER.comment(
                "LE TAMPON : immuable chez Mojang, mutable chez NVIDIA.",
                "",
                "GL_ARB_buffer_storage alloue un tampon IMMUABLE : sa taille et son emplacement ne",
                "bougent plus. Quand le jeu reecrit dedans alors que la carte s'en sert encore, le",
                "pilote n'a pas le droit de lui donner une autre zone memoire - il n'a que le choix",
                "d'ATTENDRE. Sur pilote NVIDIA et sur les Intel de septieme generation, cette",
                "attente se voit : l'image se pose, puis saute. Avec un tampon mutable, le pilote",
                "abandonne l'ancienne zone et en prend une neuve.",
                "",
                "CE MODULE N'EST PAS MESURE ICI, et c'est la seule exception du mod a cette regle.",
                "Ce n'est pas une trouvaille de ce laboratoire : c'est le correctif que Mojang a",
                "ecrit lui-meme en 26.3, ou GlHeuristics gagne isNvidia() et couldBeIntelGen7().",
                "La 26.2 ne l'a pas. La mesure qui le justifie est celle de Mojang, pas la notre.",
                "",
                "SANS EFFET SOUS VULKAN : tout com.mojang.blaze3d.opengl.* est inerte quand le",
                "moteur Vulkan de la 26.2 est choisi. Sans effet non plus si votre carte n'est ni",
                "NVIDIA ni Intel Gen7 : le module lit le nom du pilote et s'abstient.",
                "",
                "QUAND CE REGLAGE EST LU : UNE SEULE FOIS, a la creation du peripherique graphique,",
                "c'est-a-dire au demarrage du jeu. Le changer prend effet au prochain lancement.",
                "C'est aussi pourquoi il est range ici et non dans le fichier serveur : celui-ci",
                "n'est lu qu'au chargement d'un monde, donc bien trop tard pour cette decision.")
                .define("tampon_mutable", true);
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

        EMPREINTE = BUILDER.comment(
                "L'EMPREINTE : le cache de pipeline Vulkan (VkPipelineCache) garde d'un lancement a",
                "l'autre, pour que le pilote reutilise le travail de compilation deja fait au lieu de",
                "tout refaire a froid.",
                "",
                "CE QUE CE MODULE CORRIGE. Verifie par javap sur le vrai jar patche : ce moteur appelle",
                "vkCreateGraphicsPipelines avec VK_NULL_HANDLE en cache pour LES TROIS variantes de",
                "chaque pipeline (avec profondeur+stencil, avec profondeur seule, sans profondeur) -",
                "pour vanilla comme pour Lanterne, RenderPipeline est le seul chemin de compilation de",
                "ce moteur entier. Rien n'est jamais garde d'un lancement au suivant : chaque pipeline",
                "est recompile a froid, a chaque fois. C'est ce qui a gele le rendu la nuit ou la",
                "premiere compilation reelle du Gbuffer d'Echelle a pris plusieurs minutes sur un",
                "pilote qui partait d'une feuille blanche (voir Config du meme depot, chantier",
                "Echelle) - corrige ce soir-la par un delai borne et un thread d'arriere-plan, mais",
                "le probleme de fond restait entier pour tous les pipelines du mod.",
                "",
                "ROBUSTESSE. Un cache absent, tronque ou ecrit par un autre pilote (ou une autre",
                "version de pilote) est traite par Vulkan lui-meme, pas reinvente ici : la",
                "specification impose au pilote de verifier l'en-tete du cache et d'ignorer",
                "silencieusement ce qui ne correspond pas, plutot que d'echouer. Le fichier est en",
                "plus nomme d'apres l'identifiant de pilote que Vulkan expose exactement pour cet",
                "usage (pipelineCacheUUID), donc un iGPU et un GPU dedie sur la meme machine, ou un",
                "pilote mis a jour, obtiennent chacun leur propre fichier au lieu de se marcher",
                "dessus.",
                "",
                "OU C'EST RANGE. config/lanterne/empreinte/<identifiant-pilote>.cache - jamais dans la",
                "sauvegarde d'un monde, jamais partage entre deux machines differentes : un fichier",
                "invalide pour CETTE machine est simplement ignore par le pilote, jamais un risque.",
                "",
                "ETEINT PAR DEFAUT : correct par construction (verifie par javap sur le vrai jar",
                "patche, comme l'exige la premiere regle de ce depot), mais jamais passe par un vrai",
                "lancement client - interdit a qui a ecrit ce module, reserve au coordinateur. Voir",
                "Empreinte.java (package client) pour le protocole de verification en jeu : comparer,",
                "avec et sans fichier de cache present au demarrage, le temps entre \"Vulkan actif et",
                "confirme\" et la ligne de compilation du premier pipeline d'Echelle (Gbuffer) au",
                "journal.")
                .define("empreinte", false);

        GUET = BUILDER.comment(
                "LE GUET : mesure, SANS RIEN CHANGER AU RENDU, si la liste des sections de chunk",
                "visibles reste identique d'une image a l'autre.",
                "",
                "CONTEXTE VERIFIE PAR JAVAP SUR LE VRAI JAR PATCHE (jamais suppose) : ce moteur",
                "bascule DEJA sur un vrai vkCmdDrawIndexedIndirect par groupe de sections partageant",
                "un meme tampon GPU - LevelRenderer.prepareChunkRendersIndirect /",
                "ChunkSectionsToRender.DrawIndirect / VulkanRenderPass.drawIndexedIndirect, confirmes",
                "un a un par lecture de bytecode reelle, PAS un appel de dessin par section. Le cout",
                "mesure dans extractSectionDrawGroups (6,3% de temps propre dans une session en jeu",
                "reelle du 19 septembre 2026, voir config/lanterne/profil-2026-09-19_21-04-36.txt) est",
                "donc du travail CPU de regroupement par image, pas des appels de dessin en trop.",
                "",
                "CE QUE CE MODULE FAIT : rien que lire LevelRenderer.visibleSections() (deja public,",
                "aucun mixin) chaque image et comparer une signature legere (taille + identite de",
                "chaque section ET de son maillage compile) a celle de l'image precedente. N'ecrit",
                "jamais dans le pipeline de rendu, ne peut donc pas le casser.",
                "",
                "CE QUE CE MODULE NE FAIT PAS : aucune tentative de mise en cache du regroupement",
                "n'a ete faite. La piste existe (ne reconstruire les groupes de dessin que si cette",
                "signature change) mais couple aujourd'hui, dans le meme calcul, une part STATIQUE",
                "(quelles sections partagent quel tampon) et une part ANIMEE par image (fondu de",
                "visibilite par section, et l'index qui la relie a son groupe de dessin) - une",
                "refonte reelle du chemin de rendu de terrain, pas une extension sure a l'aveugle",
                "sans pouvoir lancer un client ici. Ce module produit la mesure qui dira si cette",
                "refonte vaut le risque, voir son rapport dans Radiographie.",
                "",
                "NON MESURE : eteint par defaut, comme tout module non mesure dans ce depot.")
                .define("guet", false);

        SONDE_INDEXATION = BUILDER.comment(
                "LA SONDE : constate, SANS RIEN CHANGER AU RENDU, si le pilote de CETTE machine",
                "supporte reellement les quatre bits Vulkan qu'exigerait un rendu de textures",
                "bindless (indexation de descripteurs, VK_EXT_descriptor_indexing) :",
                "shaderSampledImageArrayNonUniformIndexing, descriptorBindingPartiallyBound,",
                "runtimeDescriptorArray, descriptorBindingSampledImageUpdateAfterBind.",
                "",
                "POURQUOI CE MODULE EXISTE SANS RENDU BINDLESS DERRIERE. Enquete complete, verifiee",
                "par lecture de bytecode/code reel (jamais suppose) : ce moteur n'a CE SOIR aucun",
                "point d'application ou le bindless apporterait un gain net mesurable en theorie.",
                "Le terrain lie l'atlas de blocs comme UNE SEULE texture pour tout le rendu",
                "solide/decoupe/translucide (RenderTypes.java, vanilla) et ses sections sont deja",
                "regroupees en vkCmdDrawIndexedIndirect par lots (voir Guet ci-dessus) - rien a",
                "indexer dynamiquement, rien a regrouper de plus. Les entites/objets lient deja leur",
                "texture au RenderType/pipeline lui-meme (RenderSetup, vanilla) : en changer appelle",
                "deja VulkanRenderPass.setPipeline, qui force un descriptor set entierement neuf",
                "QUOI QU'IL ARRIVE - le bindless n'evite donc pas cet appel, il n'en reduit que la",
                "taille. Et le mecanisme actuel (VulkanRenderPass.pushDescriptors, verifie par",
                "lecture de code) tourne deja sur VK_KHR_push_descriptor - extension REQUISE de ce",
                "moteur - PAS sur un VkDescriptorPool classique : aucun vkAllocateDescriptorSets ni",
                "vkCreateDescriptorPool n'existe nulle part dans ce moteur pour les descripteurs de",
                "rendu, donc le cout que le bindless supprime habituellement (allocation/pool/rebind)",
                "est deja structurellement absent ici.",
                "",
                "CE QUE CE MODULE NE FAIT PAS : aucun tableau de descripteurs, aucune modification de",
                "VulkanRenderPipeline.compile (le pipeline layout PARTAGE par chaque pipeline du",
                "moteur), aucun shader touche, aucune extension ni fonctionnalite demandee a la",
                "creation du device. Une requete vkGetPhysicalDeviceFeatures2 separee et jetable, sur",
                "une structure allouee sur la pile - voir Sonde.java (package client).",
                "",
                "NON MESURE, sans consommateur en aval : eteint par defaut, comme tout module non",
                "mesure dans ce depot.")
                .define("sonde_indexation", false);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private ClientConfig() {}

    /** Applique le fichier client aux réglages, sauf si l'environnement a déjà parlé. */
    public static void apply(ModConfigEvent event) {
        // La spec, et non le type — voir {@link Config#apply}, qui souffrait du meme defaut. Il y a
        // DEUX fichiers de type CLIENT, celui-ci et « lanterne-projecteur-client.toml », si bien que
        // ce gestionnaire s'executait deux fois par demarrage du jeu.
        if (event.getConfig().getSpec() != SPEC) {
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
