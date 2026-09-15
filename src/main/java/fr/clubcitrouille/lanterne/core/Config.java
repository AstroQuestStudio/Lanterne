package fr.clubcitrouille.lanterne.core;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le fichier de réglages : un interrupteur par module, en clair.
 *
 * <h2>Pourquoi une variable d'environnement ne suffisait pas</h2>
 *
 * <p>{@code LANTERNE_MODULES} existe depuis le début et reste : c'est l'outil des bancs, qui doivent
 * pouvoir n'allumer qu'un module pour lui attribuer un gain. Mais demander à un administrateur de
 * poser une variable d'environnement sur un hébergement partagé, c'est lui demander l'impossible.
 *
 * <p>Ce fichier fait la même chose, en clair, dans {@code config/lanterne-server.toml}, avec une
 * ligne par module et un commentaire qui dit ce que chacun fait <b>et ce qu'il a rendu à la
 * mesure</b>. Un réglage dont on ne connaît pas le prix ne se règle pas : il se subit.
 *
 * <h2>Qui gagne quand les deux parlent</h2>
 *
 * <p>La variable d'environnement <b>prime</b>. Elle n'est employée que par les bancs de ce projet, et
 * un banc qui verrait ses réglages écrasés par un fichier de configuration mesurerait autre chose que
 * ce qu'on lui demande — c'est exactement le genre de faux rapport que ce projet passe son temps à
 * traquer.
 *
 * <h2>Le socle et les ajouts</h2>
 *
 * <p>Les réglages sont rangés en deux sections, et la distinction compte. Le <b>socle</b> ne change
 * rien au jeu : il enlève du travail inutile, et son seul effet visible est que le serveur va plus
 * vite. Les <b>ajouts</b> changent le jeu — l'ancre est un bloc nouveau, la fusion des orbes se voit
 * à l'œil. Un administrateur doit pouvoir prendre le premier sans le second.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOD;
    public static final ModConfigSpec.BooleanValue NETWORK;
    public static final ModConfigSpec.BooleanValue PROFILER;
    public static final ModConfigSpec.BooleanValue DENSITY;
    public static final ModConfigSpec.BooleanValue COLLISIONS;
    public static final ModConfigSpec.BooleanValue PROJECTILES;
    public static final ModConfigSpec.BooleanValue EXPLOSIONS;
    public static final ModConfigSpec.BooleanValue MIND;
    public static final ModConfigSpec.BooleanValue TIDE;
    public static final ModConfigSpec.BooleanValue BOXES;
    public static final ModConfigSpec.BooleanValue SAVE;
    public static final ModConfigSpec.BooleanValue JAM;
    public static final ModConfigSpec.BooleanValue SLEEP;
    public static final ModConfigSpec.BooleanValue BULK;
    public static final ModConfigSpec.BooleanValue RATIONING;
    public static final ModConfigSpec.BooleanValue SCRATCH_POS;
    public static final ModConfigSpec.BooleanValue STRICT_YIELD;

    public static final ModConfigSpec.BooleanValue GATHER;
    public static final ModConfigSpec.BooleanValue PASTURE;
    public static final ModConfigSpec.BooleanValue WIRE;
    public static final ModConfigSpec.BooleanValue POI;
    public static final ModConfigSpec.BooleanValue SPILL;
    public static final ModConfigSpec.BooleanValue SENSES;
    public static final ModConfigSpec.BooleanValue RECALL;
    public static final ModConfigSpec.BooleanValue CADASTRE;
    public static final ModConfigSpec.BooleanValue DIGUE;
    public static final ModConfigSpec.BooleanValue ELASTIQUE;
    public static final ModConfigSpec.BooleanValue SOMMAIRE;
    public static final ModConfigSpec.BooleanValue REDSTONE;
    public static final ModConfigSpec.BooleanValue MOULDS;
    public static final ModConfigSpec.BooleanValue DECAY;
    public static final ModConfigSpec.IntValue DECAY_DELAY;
    public static final ModConfigSpec.IntValue NEAR_RADIUS;
    public static final ModConfigSpec.BooleanValue MINING;

    public static final ModConfigSpec.BooleanValue CLUMP;
    public static final ModConfigSpec.BooleanValue ANCHOR;
    public static final ModConfigSpec.BooleanValue VIGIL;
    public static final ModConfigSpec.BooleanValue WAYPOINTS;
    public static final ModConfigSpec.IntValue WAYPOINT_QUOTA;
    public static final ModConfigSpec.IntValue WAYPOINT_SHARED_CAP;
    public static final ModConfigSpec.BooleanValue BROOM;
    public static final ModConfigSpec.IntValue BROOM_PERIOD;
    public static final ModConfigSpec.BooleanValue BROOM_ITEMS;
    public static final ModConfigSpec.BooleanValue BROOM_MOBS;
    public static final ModConfigSpec.BooleanValue BROOM_ARROWS;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment(
                "Lanterne - un interrupteur par optimisation.",
                "",
                "Chaque module a ete mesure SEUL, sur un banc reproductible. Les chiffres cites sont",
                "ceux de ce banc, sur la charge ou le module compte le plus : ils disent ce qu'il",
                "apporte quand il sert, pas ce qu'il apporte toujours.",
                "",
                "Tout couper laisse le jeu exactement tel que Mojang l'a ecrit.").push("socle");

        LOD = BUILDER.comment(
                "Niveau de detail : une creature lointaine reflechit moins souvent.",
                "Rien n'est degrade en deca de 32 blocs d'un joueur.",
                "Elevage de 1000 vaches, socle complet : 22,40 ms -> 4,47 ms.")
                .define("lod", true);
        DENSITY = BUILDER.comment(
                "Densite : ce qui est noye dans le nombre se distingue moins.",
                "Dans un tas de mille betes, une bete decide jusqu'a 32 fois moins souvent.",
                "La production - croissance, ponte, reproduction - reste a l'heure exacte.")
                .define("densite", true);
        COLLISIONS = BUILDER.comment(
                "Collisions d'entites : presque rien ne peut bloquer quoi que ce soit.",
                "canBeCollidedWith rend faux par defaut ; seuls le bateau, le shulker et le",
                "ghast apprivoise le redefinissent. 8000 objets au sol : x12,04.")
                .define("collisions", true);
        PROJECTILES = BUILDER.comment(
                "Projectiles : une fleche ne peut pas toucher une fleche.",
                "6000 fleches en vol : 97,25 ms -> 32,50 ms, soit x2,99.",
                "Une epreuve de tir verifie qu'une fleche touche encore une creature.")
                .define("projectiles", true);
        EXPLOSIONS = BUILDER.comment(
                "Explosions : le trace des rayons, sans ses 20 000 objets jetables par tir.",
                "6000 TNT : x1,11 pour ce module seul.")
                .define("explosions", true);
        MIND = BUILDER.comment(
                "Intelligence : le cerveau des creatures, qui cesse de redecouvrir a chaque",
                "tick ce qu'il savait deja (comportements en cours, memoires a expirer).",
                "600 villageois : x1,32 pour ce module seul, et 153 Mo alloues en moins.")
                .define("intelligence", true);
        TIDE = BUILDER.comment(
                "Maree : ajuste automatiquement les distances de vue et de simulation selon",
                "le temps de tick reel. Elle ne depasse JAMAIS ce que server.properties",
                "demande : elle descend quand le serveur souffre, et remonte ensuite.",
                "C'est le seul module qui change ce que voit le joueur. Il est actif par",
                "defaut parce qu'un horizon qui recule quelques secondes vaut mieux qu'un",
                "serveur qui rame ; le mettre a false rend les distances fixes.")
                .define("maree", true);
        BOXES = BUILDER.comment(
                "Boite vide partagee : les conditions d'entree des comportements",
                "emballent une valeur absente dans un objet neuf a chaque essai.",
                "600 villageois : 1,88 Go alloues pour ranger du vide, soit 10,5 %",
                "du total. L'objet est immuable (verifie par javap), donc partageable.")
                .define("boites", true);
        PROFILER = BUILDER.comment(
                "Cache du profileur : Profiler.get() consulte une variable de fil a chaque",
                "deplacement d'entite. 6000 TNT : x1,10 pour ce module seul.")
                .define("profileur", true);
        NETWORK = BUILDER.comment(
                "Reseau : les paquets de position espaces pour les entites lointaines.")
                .define("reseau", true);
        SCRATCH_POS = BUILDER.comment(
                "Positions reutilisees pour les tirages de blocs.",
                "Aucun effet sur le temps, et x3,01 SUR LA MEMOIRE ALLOUEE (4,08 Go -> 1,36 Go).",
                "C'est le premier contributeur du mod sur la memoire, donc sur les a-coups :",
                "sur une machine a un coeur, un ramassage ne s'execute pas en parallele, il fige.")
                .define("positions", true);
        JAM = BUILDER.comment("Court-circuit de bousculade dans les amas immobiles.")
                .define("amas", true);
        SLEEP = BUILDER.comment(
                "Sommeil des blocs-entites dont l'echeance est connue : un four qui cuit sait",
                "quand il aura fini, et n'a rien a faire d'ici la.")
                .define("sommeil", true);
        BULK = BUILDER.comment(
                "Entonnoirs : seize objets par recherche de conteneur, au lieu d'un.",
                "La recharge suit le lot - seize objets coutent 8x16 ticks - donc le debit moyen",
                "est EXACTEMENT celui de vanilla. Ce n'est pas un ralentissement.",
                "Vanilla fait deja cela pour un objet ramasse au sol : une pile entiere de 64",
                "entre d'un coup, pour la meme recharge de 8 ticks.")
                .define("entonnoirs", true);
        GATHER = BUILDER.comment(
                "Objets au sol : fusion sur 2 blocs a l'horizontale et 1 a la verticale,",
                "contre 0,5 et ZERO en vanilla - deux objets empiles ne se rejoignent jamais.",
                "Un objet pose cherche ses voisins tous les 10 ticks au lieu de 40.",
                "Le plafond d'age de 6000 ticks est CONSERVE : sans lui, un tas mourant",
                "redeviendrait eternel des qu'on lui jette un objet neuf a cote.")
                .define("fusion_objets", true);
        PASTURE = BUILDER.comment(
                "Enclos : une bete qui n'a pas parcouru 2 blocs en 5 secondes cesse de decider",
                "d'aller se promener. Dans un tas, une telle decision fait calculer un chemin,",
                "suivre ce chemin, et se faire repousser par les voisines - pour finir au meme",
                "endroit. Le resultat observable est identique ; seul le calcul disparait.",
                "Le verdict se refait toutes les 5 secondes : des que la barriere s'ouvre et que",
                "la bete parcourt ses deux blocs, elle recommence a decider.",
                "Jamais applique a ce qui porte un nom, est apprivoise, monte ou tenu en laisse,",
                "ni a autre chose qu'un animal.")
                .define("enclos", true);
        WIRE = BUILDER.comment(
                "Compression des paquets : niveau 1 au lieu de 6.",
                "Vanilla compresse CHAQUE paquet de chunk au niveau six. Un joueur qui arrive avec",
                "32 chunks de vue en recoit 441 d'affilee - sur un coeur unique, les fils reseau",
                "disputent le processeur au fil du serveur, et ce gel se voit.",
                "Le niveau ne change RIEN au format : un flux zlib de niveau 1 se decompresse par",
                "n'importe quel Inflater. Aucune negociation, et un client vanilla ne voit rien.",
                "Prix : des paquets 10 a 15 % plus gros, compresses 3 a 5 fois plus vite.",
                "Pris en compte a la CONNEXION : changer ce reglage n'affecte que les suivantes.")
                .define("compression_reseau", true);
        POI = BUILDER.comment(
                "Points d'interet parcourus sans emballer d'entiers.",
                "getInChunk fait IntStream.rangeClosed(...).boxed() : 24 Integer par chunk. Et",
                "getInSquare l'appelle POUR CHAQUE CHUNK d'un carre - un villageois qui cherche un",
                "lit a 48 blocs balaie 81 chunks, soit 1944 entiers emballes par recherche.",
                "mapToObj fait la meme chose sans l'emballage : meme paresse, meme ordre, meme",
                "contenu. La paresse est conservee - certains appelants ne prennent que le premier",
                "resultat, et materialiser une liste les ferait travailler davantage.")
                .define("points_interet", true);
        SPILL = BUILDER.comment(
                "Effets de blocs traverses : ne pas fabriquer un tableau vide pour ne rien copier.",
                "ArrayList.addAll appelle toArray() AVANT de regarder si la source est vide.",
                "600 villageois : 2,4 millions de tableaux evites, 20 Mo sur 2900.",
                "Modeste et gratuit - un serveur rapide est fait de la somme de ces gains-la.")
                .define("effets_traverses", true);
        SENSES = BUILDER.comment(
                "CAPTEUR DE JOUEURS : le meme travail, sans ses trois pipelines de flux.",
                "",
                "PlayerSensor.doTick construit TROIS pipelines successifs, dont le premier comporte",
                "un TRI. Chacun alloue son objet de flux, ses maillons, ses lambdas et sa liste de",
                "sortie.",
                "",
                "Ce capteur equipe TOUTE creature a cerveau - villageois, piglins, axolotls,",
                "gardiens - et se releve toutes les quelques ticks. Sur 600 villageois : mille huit",
                "cents pipelines par releve.",
                "",
                "Le profileur a designe la machinerie de flux comme premier poste du serveur, a 24 %",
                "du temps. Trois tentatives sur le CERVEAU n'avaient rien rendu - 26.1 en a deja",
                "retire les flux. Les CAPTEURS, eux, en ont garde.",
                "",
                "Rien ne change au comportement : meme ordre de tri, memes filtres dans le meme",
                "ordre, memes quatre memoires ecrites avec les memes valeurs.")
                .define("capteur_joueurs", true);
        RECALL = BUILDER.comment(
                "MEMOIRE DES CONDITIONS : ne pas retester ce qui n'a pas change.",
                "",
                "Behavior.tryStart commence par verifier les conditions d'entree du comportement :",
                "deux a cinq memoires interrogees, pour chacun des quinze comportements d'un",
                "villageois. Cinquante interrogations par creature et par tick ; sur 600",
                "villageois, trente mille par tick.",
                "",
                "Et la plupart du temps RIEN N'A CHANGE depuis le tick precedent : la reponse est la",
                "meme, recalculee pour rien.",
                "",
                "Un compteur sur le cerveau s'incremente a toute ecriture, tout effacement et a",
                "chaque passage du ramasseur de memoires expirees. Tant qu'il ne bouge pas, la",
                "reponse precedente vaut toujours.",
                "",
                "Aucun effet sur le comportement : la reponse rendue est identique, seul le moment",
                "ou elle est calculee change.",
                "",
                "MESURE HONNETE : x1,01 sur 600 villageois - 33,91 ms contre 33,53. Sous le plancher",
                "de ce banc, donc NON PROUVE. C'est la TROISIEME tentative sur le cerveau des",
                "villageois, apres le cache de la liste plate (x1,01) et le brassage des positions",
                "(x0,91), et la troisieme a ne rien rendre.",
                "",
                "La conclusion qu'il faut en tirer : startEachNonRunningBehavior porte 28,9 % de la",
                "pile, mais ce temps est dans le TRAVAIL des comportements, pas dans les",
                "verifications qui les precedent. Il n'y a rien a y gratter par mise en cache.",
                "",
                "Garde parce qu'il ne coute rien et va dans le bon sens. Sans chiffre de gain.")
                .define("memoire_conditions", true);
        CADASTRE = BUILDER.comment(
                "CADASTRE DES STRUCTURES : la place deja prise, tenue dans un index de boites.",
                "",
                "Un village, un avant-poste ou une cite ancienne se composent de PIECES que le",
                "placeur Jigsaw ajoute une par une. Avant chaque ajout il demande : cette piece",
                "tient-elle dans ce qui reste libre ? Vanilla repond en soustrayant chaque piece",
                "posee d'une FORME DE VOXELS.",
                "",
                "Le prix de cette forme n'est pas constant. A chaque soustraction, Shapes",
                ".joinUnoptimized fusionne les coordonnees des deux formes puis remplit une grille",
                "de la taille du produit : apres N pieces, la grille fait environ (2N) au cube.",
                "Le cout d'une seule soustraction croit donc comme le CUBE du nombre de pieces, et",
                "le total comme sa PUISSANCE QUATRIEME. C'est ce qui fige un serveur quand un",
                "joueur explore vers un village.",
                "",
                "Le cadastre garde la meme reponse en tenant la liste des boites occupees dans une",
                "grille de hachage : une piece tient si elle est dans la region de depart - question",
                "posee a vanilla, sur une forme qui ne grossit JAMAIS - et si elle ne coupe aucune",
                "boite deja posee.",
                "",
                "STRICTEMENT LE MEME RESULTAT, et c'est verifiable : les boites de structure ont des",
                "coordonnees entieres et la boite testee est retrecie d'un quart de bloc, si bien",
                "qu'un recouvrement vaut zero ou au moins un quart. Il n'y a pas de zone grise ou",
                "les deux methodes pourraient diverger.",
                "",
                "N'agit QUE pendant la generation de terrain. Un monde deja explore n'y passe plus,",
                "et le gain n'est pas des TPS : c'est de la latence d'exploration.",
                "Idee de StructureLayoutOptimizer (MIT) ; l'implementation est differente - voir",
                "NOTICE.md pour ce qui n'a PAS ete repris, et pourquoi.")
                .define("cadastre_structures", true);
        DIGUE = BUILDER.comment(
                "DIGUE : le tick du serveur ne descend jamais au disque.",
                "",
                "Une poignee de chemins du jeu demandent un chunk TOUT DE SUITE, au milieu du tick.",
                "Le fil du serveur cesse alors de ticker et fait tourner la file de generation",
                "jusqu'a ce que le chunk existe : lire une region, la decompresser, ou pire la",
                "GENERER, coute de l'ordre de la centaine de millisecondes - deux a quatre ticks",
                "perdus d'un coup, et le joueur le voit.",
                "",
                "Sur un VPS a un seul coeur il n'y a aucun autre coeur pour absorber ce travail :",
                "le chargement 'asynchrone' du jeu n'est asynchrone que sur une machine qui a de",
                "quoi l'etre.",
                "",
                "CE MODULE NE REND RIEN PLUS RAPIDE : IL REFUSE. Il repond 'ce chunk n'est pas la'",
                "au lieu d'aller le chercher, et le jeu suit sa branche 'pas la'. C'est le SEUL",
                "module de ce mod qui retire une information au jeu plutot que de la calculer moins",
                "cher, et le prix est visible :",
                "  - une carte laisse une case blanche au-dela du charge ;",
                "  - une abeille ne retrouve pas sa ruche si son chunk est parti (une garde de",
                "    memoire l'evite ici, contrairement au mod dont l'idee vient) ;",
                "  - un villageois ne se couche pas si son lit est hors du charge ;",
                "  - un ecouteur d'evenement de jeu ne s'enregistre pas.",
                "",
                "A couper si vous preferez un gel de 200 ms a une case de carte blanche. Sur une",
                "machine a plusieurs coeurs et un disque rapide, c'est un arbitrage defendable.")
                .define("digue", true);
        ELASTIQUE = BUILDER.comment(
                "ELASTIQUE : le joueur qu'on renvoie en arriere alors qu'il n'a rien fait.",
                "",
                "CE MODULE NE REND RIEN PLUS RAPIDE. Il ne fait gagner aucun TPS, aucune",
                "milliseconde. Un serveur qui rame ramera exactement autant apres qu'avant. Tout le",
                "reste de ce mod s'attaque a la CAUSE du lag ; celui-ci cache l'un de ses",
                "SYMPTOMES. C'est un pansement, et il est eteint par defaut : on ne le pose",
                "qu'apres avoir constate que tout le reste ne suffisait pas.",
                "",
                "LE SYMPTOME. On court, et l'on se retrouve trois metres en arriere. On contourne",
                "un angle, et l'on revient d'ou l'on vient. Le serveur REFUSE la position annoncee",
                "et teleporte le joueur la ou lui croit qu'il est.",
                "",
                "LA CAUSE. handleMovePlayer juge le joueur sur trois seuils (100.0F, 300.0F et",
                "0.0625), tous mesures ENTRE DEUX TICKS SERVEUR. Le client, lui, ne ralentit",
                "jamais : il envoie vingt pas par seconde quoi qu'il arrive. Si un tick dure 200",
                "ms, le joueur a legitimement fait quatre pas la ou le serveur en attendait un.",
                "Pire : le serveur applique les quatre d'un seul coup, et un gros pas heurte le",
                "coin que quatre petits contournaient. C'est un faux positif : le joueur n'a pas",
                "triche, c'est la regle qui a retreci.",
                "",
                "CE QU'ON FAIT. Ces trois nombres sont des distances AU CARRE. Une distance",
                "legitime croit avec le temps ; son carre croit avec le CARRE du temps. On",
                "multiplie donc les trois seuils par le carre du retard reellement constate, mesure",
                "entre resetPosition() et l'instant du paquet. Rien d'empirique la-dedans : c'est",
                "la seule mise a l'echelle homogene avec ce qu'on compare.",
                "",
                "UN SERVEUR SAIN EST VANILLA AU BIT PRES. En dessous de 1,5 tick de retard (75",
                "ms), la constante d'origine est rendue TELLE QUELLE, sans la moindre operation",
                "flottante. Pas 'a peu pres vanilla' : le meme float, le meme double, le meme bit.",
                "L'epreuve LANTERNE_AMARRE=1 le verifie et echoue sinon.",
                "",
                "CE N'EST PAS UNE PORTE OUVERTE AUX TRICHEURS. La destination annoncee reste",
                "confrontee aux collisions du monde par isEntityCollidingWithAnythingNew, qui n'est",
                "PAS touche et qui s'applique meme quand le controle de coherence passe : on ne",
                "traverse pas un mur, on ne finit pas dans un bloc. Ce qui passe en plus, c'est une",
                "coupe d'angle, bornee a un bloc. La tolerance est indexee sur l'horloge du",
                "SERVEUR, jamais sur une affirmation du client, et la garde anti-inondation de",
                "vanilla (deltaPackets ramene a 1 au-dela de cinq) est laissee intacte.",
                "",
                "A ALLUMER si vos joueurs se plaignent d'elastique malgre le reste du mod, et",
                "surtout sur une machine a un seul coeur ou une pause du ramasse-miettes vole le",
                "tick sans prevenir. A LAISSER ETEINT sur un serveur qui tient ses ticks : il n'y",
                "aurait rien a corriger, et il masquerait le signal qu'un serveur va mal.")
                .define("elastique", false);
        SOMMAIRE = BUILDER.comment(
                "SOMMAIRE DES ZIP : un paquet se lit une fois, pas deux cent cinquante fois.",
                "",
                "FilePackResources.getNamespaces et .listResources appellent zipFile.entries() et",
                "balayent le fichier ENTIER a chaque appel, sans rien garder. Au-dessus, un",
                "rechargement de datapacks appelle listResources une fois par registre dynamique",
                "- quarante-sept - puis une fois par registre pour les etiquettes - une centaine -",
                "plus les recettes, les avancements, les fonctions et les tables de butin.",
                "",
                "Le sommaire construit UNE FOIS un ensemble trie des noms d'entrees : une demande",
                "devient une recherche dichotomique au lieu d'un balayage complet.",
                "",
                "OU LE GAIN SE TROUVE, ET OU IL NE SE TROUVE PAS. Un serveur dedie ne charge aucun",
                "paquet de RESSOURCES : seul le client le fait. Cote serveur, seuls les datapacks",
                "en .zip passent par cette classe - un datapack range en DOSSIER est servi par",
                "PathPackResources, qui n'a pas ce defaut. Si vos datapacks sont des dossiers, ce",
                "reglage ne changera rien chez vous, et c'est normal.",
                "",
                "QUAND CE REGLAGE EST LU : au chargement d'un paquet. Le fichier de configuration",
                "est lu avant le premier /reload mais APRES le chargement initial des datapacks -",
                "le couper ne reprend donc la main qu'au rechargement suivant.")
                .define("sommaire_zip", true);
        REDSTONE = BUILDER.comment(
                "MOTEUR DE REDSTONE : celui que Mojang a ecrit, puis laisse eteint.",
                "",
                "26.1 contient DEUX calculateurs de puissance pour la poussiere :",
                "  Default      (42 lignes)  chaque brin recalcule SEUL, sans savoir ce que font",
                "                            ses voisins. Dans un reseau, un brin change de valeur",
                "                            une demi-douzaine de fois avant de se fixer - et chaque",
                "                            changement emet 42 mises a jour de bloc.",
                "  Experimental (221 lignes) traite le reseau ENTIER avec des files, et n'emet que",
                "                            les mises a jour finales.",
                "",
                "Le second est exactement l'algorithme du mod de reference sur ce sujet. Mojang l'a",
                "ecrit, puis place derriere un drapeau qu'il faut cocher a la creation du monde et",
                "que presque personne ne coche. Par defaut, c'est le PREMIER qui tourne.",
                "",
                "ETEINT PAR DEFAUT, et ce n'est pas de la prudence excessive : ce reglage CHANGE LE",
                "COMPORTEMENT DU JEU, pas seulement sa vitesse. L'ordre des mises a jour cesse de",
                "dependre des coordonnees pour devenir previsible - ce qui corrige MC-11193, mais",
                "peut faire se comporter differemment un circuit bati sur l'ordre d'origine.",
                "",
                "Un mod d'optimisation qui casse une ferme a echoue, meme s'il double les ticks.")
                .define("moteur_redstone", false);
        MOULDS = BUILDER.comment(
                "MOULES : les formes de collision partagees entre etats de blocs identiques.",
                "",
                "Chaque etat de bloc garde SA copie de sa forme de collision et de son tableau de",
                "faces porteuses. Un bloc a six proprietes booleennes a 64 etats ; une cloture, un",
                "mur ou un tuyau de mod en ont plusieurs centaines - et presque tous decrivent",
                "EXACTEMENT le meme volume. Vanilla n'en sait rien et les duplique.",
                "",
                "Tout le travail est fait au CHARGEMENT, une fois par etat. En pleine partie ce",
                "module n'execute plus une seule instruction.",
                "",
                "MESURE HONNETE. En vanilla, 11 413 formes et 30 674 motifs de faces sont",
                "effectivement partages - pour seulement 317 + 57 distincts retenus. Le partage est",
                "donc bien reel et massif. Mais 317 formes distinctes pesent trop peu pour que la",
                "balance se voie : l'ecart mesure (9,2 Mo) est du meme ordre que le bruit du banc",
                "lui-meme (3,6 Mo entre deux phases IDENTIQUES).",
                "",
                "Ce module est donc garde pour ce qu'il fait - le compteur le prouve - et SANS",
                "chiffre de gain. Sur un modpack ou le compte de blocs est dix fois superieur, il",
                "devrait compter ; ici, il ne se mesure pas.")
                .define("moules", true);
        DECAY = BUILDER.comment(
                "CHUTE RAPIDE DES FEUILLES - cote SERVEUR : un arbre abattu ne laisse pas sa",
                "couronne suspendue dans le vide pendant une minute.",
                "",
                "Vanilla attend un TICK ALEATOIRE pour chaque feuille detachee : trois positions",
                "tirees par section et par tick sur 4096, soit une esperance d'environ 1360 ticks.",
                "Ce module replanifie la chute au lieu d'attendre le hasard.",
                "",
                "NE TOUCHE PAS aux feuilles POSEES A LA MAIN : vanilla leur met persistent=true a",
                "la pose, et elles n'ont jamais ete concernees par la decomposition. Un toit de",
                "feuillage dans une construction ne risque rien.",
                "",
                "Sans rapport avec le masquage des feuilles, qui est un reglage CLIENT.")
                .define("chute_feuilles", true);
        DECAY_DELAY = BUILDER.comment(
                "Ticks entre le detachement d'une feuille et sa chute.",
                "5 (defaut) : un quart de seconde. La cascade reste progressive - les feuilles",
                "proches du tronc se detachent avant les autres - mais se compte en secondes.",
                "1 : chute quasi instantanee. 40 : deux secondes, plus contemplatif.")
                .defineInRange("chute_feuilles_delai_ticks", 5, 1, 200);
        NEAR_RADIUS = BUILDER.comment(
                "Rayon de la zone franche, en blocs : rien n'y est degrade, ni par la distance ni",
                "par la foule. C'est le seul reglage qui arbitre un COMPROMIS et non un gain.",
                "",
                "24 (defaut) : aucune saccade sous les yeux du joueur. Sur un elevage de 1000 betes",
                "  dans 15x15, joueur au milieu, le gain tombe de x6,5 a x1,2 - mais le serveur",
                "  reste a 27 ms, tres en deca des 50 ms d'un tick. Personne ne perd rien.",
                "8  : la foule reprend ses droits pres du joueur. Les betes d'un enclos bondissent",
                "  moins nettement, et le serveur respire beaucoup plus.",
                "",
                "La PRESSION le fait rétrécir de moitie toute seule quand le serveur souffre :",
                "un serveur qui tient ses ticks ne degrade rien, un serveur qui peine se resserre.")
                .defineInRange("zone_franche_blocs", 24, 4, 128);
        MINING = BUILDER.comment(
                "Minage compte a l'horloge reelle plutot qu'en ticks serveur.",
                "CORRIGE UN DEFAUT VISIBLE DE VANILLA : quand le serveur prend du retard, les blocs",
                "cessent de casser. Le serveur compte la progression en ticks SERVEUR, le client en",
                "SES ticks, qui tournent a 20 quoi qu'il arrive. A 15 TPS, le client accumule 20",
                "unites la ou le serveur en compte 15 : 25 % de moins, souvent juste sous le seuil",
                "de 0,7 - et le bloc revient.",
                "Ce n'est PAS un assouplissement du seuil : les 0,7 restent, et la reference reste",
                "l'horloge du serveur. On corrige une unite de mesure, pas une tolerance.")
                .define("minage_horloge_reelle", true);
        RATIONING = BUILDER.comment("Rationnement : reagir pendant le tick, et non au suivant.")
                .define("ration", true);
        SAVE = BUILDER.comment(
                "Sauvegarde en lz4 plutot qu'en deflate : 153 ms -> 43 ms pour 64 chunks (x3,54),",
                "au prix de 23 % de place en plus sur le disque.",
                "Retrocompatible : un monde ecrit en deflate se relit sans conversion.")
                .define("sauvegarde", true);
        STRICT_YIELD = BUILDER.comment(
                "Preservation stricte du rendement, pour les creatures venues de mods.",
                "Plus sur et plus lent : 14,31 ms contre 10,01 sur la meme charge.",
                "Inutile en vanilla - les compteurs y sont deja rattrapes exactement.")
                .define("rendement_strict", false);

        BUILDER.pop();

        BUILDER.comment(
                "Les ajouts changent le jeu, contrairement au socle ci-dessus.",
                "Ils sont separables : on peut vouloir la vitesse sans eux.").push("ajouts");

        CLUMP = BUILDER.comment(
                "Les orbes d'experience fusionnent 40 fois plus souvent, sur 4 blocs.",
                "4000 orbes : 63,21 ms -> 14,77 ms, soit x4,28.",
                "L'experience totale est conservee exactement - c'est verifie a chaque banc.",
                "Visible en jeu : moins d'orbes, ramassees plus vite.")
                .define("fusion_des_orbes", true);
        ANCHOR = BUILDER.comment(
                "L'Ancre de chunk garde charge le chunk ou elle est posee, et lui seul.",
                "Coupee, le bloc existe toujours et reste posable - il ne garde plus rien.",
                "Il n'est jamais retire du registre : un bloc absent devient de l'air dans les",
                "mondes ou il etait pose, et un reglage ne doit pas detruire une construction.")
                .define("ancre_de_chunk", true);
        VIGIL = BUILDER.comment(
                "La Lanterne de Veille : aucun monstre n'apparait dans son chunk.",
                "C'est le SEUL ajout qui rend le serveur plus rapide au lieu de plus riche :",
                "la tentative d'apparition est refusee a l'entree, donc le tirage de position,",
                "la carte des hauteurs, la recherche du joueur le plus proche et la creation de",
                "la creature n'ont pas lieu. Un chunk garde ne coute pas moins : il ne coute rien.",
                "Ne touche ni aux generateurs de creatures, ni aux raids, ni aux animaux.",
                "Coupee, le bloc reste posable et eclaire - il ne garde simplement plus rien.")
                .define("lanterne_de_veille", true);

        BUILDER.comment(
                "",
                "LES REPERES - un carnet, et AUCUNE teleportation.",
                "",
                "Les mods de carte offrent presque tous le saut vers un repere. C'est commode pour",
                "un operateur, et cela detruit la survie : plus rien ne coute de distance, donc",
                "plus rien ne coute de temps, donc le monde cesse d'etre grand.",
                "",
                "Un repere dit OU c'est et A QUELLE DISTANCE. Le chemin reste a faire.",
                "",
                "Touche B par defaut pour le carnet. Trois reperes s'affichent en permanence,",
                "avec une fleche relative au REGARD du joueur - il ne sait pas ou est le nord,",
                "il sait ou il regarde.").push("reperes");

        WAYPOINTS = BUILDER.comment("Allumer le carnet de reperes.")
                .define("actifs", true);
        WAYPOINT_QUOTA = BUILDER.comment(
                "Reperes par joueur. Un carnet sans limite devient une liste de courses : on y",
                "jette tout, on n'y retrouve rien, et le choix de ce qui merite un repere - qui",
                "est la partie interessante - disparait.",
                "Cinq : la base, la ferme, le portail, le village, et un projet en cours.")
                .defineInRange("par_joueur", 5, 1, 64);
        WAYPOINT_SHARED_CAP = BUILDER.comment(
                "Reperes publics du serveur entier. Un repere public est visible de tous sans",
                "cesser d'appartenir a celui qui l'a pose : lui seul peut le retirer.")
                .defineInRange("publics_maximum", 32, 0, 256);

        BUILDER.pop();

        BUILDER.comment(
                "",
                "LE BALAI - le seul module qui RETIRE quelque chose au jeu.",
                "",
                "Tout le reste de ce mod fait le meme travail pour moins cher. Le balai, lui,",
                "supprime des entites : c'est un aveu d'echec, pas une optimisation, et c'est",
                "pourquoi il est ETEINT par defaut.",
                "",
                "Il existe parce qu'un administrateur qui en a besoin installera un mod de",
                "nettoyage de toute facon, et que celui-ci refuse d'effacer ce qui compte :",
                "  - tout ce qui porte un nom (une etiquette est une declaration d'intention)",
                "  - tout ce qui est apprivoise, monte, attache, ou persistant",
                "  - les villageois et les marchands ambulants",
                "  - tout ce qui est a moins de 16 blocs d'un joueur",
                "",
                "La fusion des objets au sol ci-dessus reduit deja le besoin d'y recourir :",
                "soixante-quatre objets devenus une pile n'ont plus besoin d'etre effaces.")
                .push("balai");

        BROOM = BUILDER.comment("Allumer le nettoyage periodique.")
                .define("actif", false);
        BROOM_PERIOD = BUILDER.comment(
                "Minutes entre deux passages. Les joueurs sont prevenus 10 s puis 3 s avant.")
                .defineInRange("periode_minutes", 15, 1, 720);
        BROOM_ITEMS = BUILDER.comment("Effacer les objets au sol.")
                .define("objets_au_sol", true);
        BROOM_MOBS = BUILDER.comment(
                "Effacer les creatures hostiles. Les apprivoisees, nommees et montees sont gardees.")
                .define("creatures_hostiles", false);
        BROOM_ARROWS = BUILDER.comment("Effacer les projectiles plantes.")
                .define("projectiles", true);

        BUILDER.pop();
        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private Config() {}

    /**
     * Applique le fichier aux réglages, sauf si l'environnement a déjà parlé.
     *
     * <p>La priorité donnée à {@code LANTERNE_MODULES} n'est pas un détail d'implémentation : sans
     * elle, un banc lancé avec un seul module verrait le fichier de configuration rallumer les
     * autres, et mesurerait le mod entier en croyant mesurer une pièce.
     */
    public static void apply(ModConfigEvent event) {
        if (event.getConfig().getType() != ModConfig.Type.SERVER) {
            return;
        }
        // Au DÉCHARGEMENT, les valeurs n.existent plus : les lire lève « Cannot get config value
        // before config is loaded ». On ne réagit donc qu.au chargement et au rechargement — les deux
        // moments où le fichier a quelque chose à dire.
        if (!(event instanceof ModConfigEvent.Loading) && !(event instanceof ModConfigEvent.Reloading)) {
            return;
        }
        if (Settings.environmentSpoke()) {
            Lanterne.LOG.info("[RÉGLAGES] LANTERNE_MODULES est posé : le fichier de configuration est "
                    + "ignoré. C'est voulu — un banc doit mesurer ce qu'on lui demande.");
            return;
        }
        Settings.applyFromConfig();
        Lanterne.LOG.info("[RÉGLAGES] configuration lue — modules actifs : {}", Settings.describe());
    }
}
