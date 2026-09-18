package fr.clubcitrouille.lanterne.core;

import java.util.List;

import net.minecraft.resources.Identifier;
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
    public static final ModConfigSpec.BooleanValue CALQUE;
    public static final ModConfigSpec.BooleanValue DIGUE;
    public static final ModConfigSpec.BooleanValue ELASTIQUE;
    public static final ModConfigSpec.BooleanValue SOMMAIRE;
    public static final ModConfigSpec.BooleanValue REDSTONE;
    public static final ModConfigSpec.BooleanValue IMPASSE;
    public static final ModConfigSpec.BooleanValue SOUVENIR;
    public static final ModConfigSpec.IntValue SOUVENIR_BUDGET_MB;
    public static final ModConfigSpec.BooleanValue MOULDS;
    public static final ModConfigSpec.BooleanValue DECAY;
    public static final ModConfigSpec.IntValue DECAY_DELAY;
    public static final ModConfigSpec.IntValue NEAR_RADIUS;
    public static final ModConfigSpec.BooleanValue MINING;
    public static final ModConfigSpec.BooleanValue BURIN;
    public static final ModConfigSpec.BooleanValue ECLUSE;
    public static final ModConfigSpec.BooleanValue RECLAME;
    public static final ModConfigSpec.BooleanValue CIEL;
    public static final ModConfigSpec.BooleanValue CLIMAT;
    public static final ModConfigSpec.BooleanValue EMBALLAGE;
    public static final ModConfigSpec.BooleanValue COLONNE;
    public static final ModConfigSpec.BooleanValue REMBLAI;
    public static final ModConfigSpec.BooleanValue FOULE;
    public static final ModConfigSpec.IntValue TIDE_MIN_VIEW;
    public static final ModConfigSpec.IntValue TIDE_MAX_VIEW;
    public static final ModConfigSpec.IntValue TIDE_MIN_SIMULATION;
    public static final ModConfigSpec.IntValue TIDE_MAX_SIMULATION;
    public static final ModConfigSpec.IntValue TIDE_START_VIEW;
    public static final ModConfigSpec.IntValue TIDE_START_SIMULATION;

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
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BROOM_ITEM_WHITELIST;

    public static final ModConfigSpec.BooleanValue PREGEN_PAUSE_ON_JOIN;
    public static final ModConfigSpec.IntValue PREGEN_BUDGET_MS;
    public static final ModConfigSpec.IntValue PREGEN_IN_FLIGHT;
    public static final ModConfigSpec.BooleanValue PREGEN_SERPENTINE;
    public static final ModConfigSpec.BooleanValue PREGEN_ASYNC;
    public static final ModConfigSpec.BooleanValue PREGEN_CONTINUOUS;

    public static final ModConfigSpec.BooleanValue TREVE_BLUEMAP;

    public static final ModConfigSpec.BooleanValue ETALON_AUTO;

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
        CALQUE = BUILDER.comment(
                "CALQUE DU ROUTEUR DE BRUIT : l'arbre des fonctions de densite, decalque une fois",
                "par chunk au lieu de trois.",
                "",
                "Avant de fabriquer le relief d'un chunk, le jeu 'decalque' son arbre de fonctions de",
                "densite : il le parcourt en entier pour y glisser les caches propres a ce chunk.",
                "Le constructeur de NoiseChunk le fait deux fois. Puis l'etape des BIOMES en demande",
                "SIX DE PLUS, une par dimension du climat - temperature, vegetation, continents,",
                "erosion, profondeur, cretes.",
                "",
                "Ces six-la sont deja calculees : le constructeur les avait entre les mains et les a",
                "jetees. Ce module les garde.",
                "",
                "POURQUOI CE PARCOURS COUTE. Les fonctions de densite sont des records, donc leur",
                "hashCode est structurel et RECURSIF, et rien ne le memorise. Le decalque range",
                "chaque noeud dans une table de hachage : hacher un noeud parcourt tout son",
                "sous-arbre. Le cout total est donc quadratique en la taille de l'arbre, pour un",
                "routeur de surmonde qui compte plusieurs centaines de noeuds - et c'est refait a",
                "chaque chunk. Le profileur le voyait sans qu'on sache le lire : Objects.hashCode a",
                "2,7 % d'un profil de generation de terrain.",
                "",
                "RESULTAT IDENTIQUE, et c'est demontrable : decalquer deux fois le meme arbre avec la",
                "meme table rend LES MEMES OBJETS, parce que la table reconnait les noeuds",
                "reconstruits. Ce module ne change ni une valeur, ni un ordre, ni un tirage. Il ne",
                "sert le calque que si le routeur demande est celui qu'il a decalque ; sinon il rend",
                "la main.",
                "",
                "N'agit QUE pendant la generation de terrain. Un monde deja explore n'y passe plus.")
                .define("calque_routeur_bruit", true);
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
        IMPASSE = BUILDER.comment(
                "IMPASSE : ne pas rechercher deux fois de suite un chemin qu'on vient de prouver",
                "impossible.",
                "",
                "Un mob bloque - un loup qui ne peut pas rejoindre son maitre par une porte fermee,",
                "un golem qui vise une position murée, un raid qui vise un point du village hors",
                "d'atteinte - redemande le MEME chemin a chaque fois que son IA se reconsidere,",
                "parfois a chaque tick. Chaque demande relance une recherche A* complete : creation",
                "d'une region de lecture, exploration de noeuds, pour retrouver exactement le NON",
                "deja rendu l'instant d'avant.",
                "",
                "Ce module retient, PAR MOB et non partage, le dernier point de depart et le dernier",
                "ensemble de cibles pour lesquels la recherche a echoue, avec l'instant de l'echec. Si",
                "le meme mob redemande EXACTEMENT le meme depart et les memes cibles dans la seconde",
                "qui suit, on rend NUL sans relancer la recherche - la reponse est celle qu'on vient",
                "de prouver.",
                "",
                "Rien n'est perdu, seulement retarde. Un obstacle qui disparait vraiment n'est visible",
                "au plus qu'une seconde plus tard - le temps que l'entree perimee cesse de compter.",
                "Aucun chemin n'est partage entre mobs de gabarits ou de capacites differents : la clef",
                "vit sur l'instance de navigation elle-meme, jamais dans une table commune.",
                "",
                "Eteint par defaut : correct par construction (lu dans le bytecode reel, pas devine),",
                "mais jamais passe par le banc lab/ qui verifie la conformite avant la vitesse, comme",
                "l'exige la premiere regle de ce depot. Un module non mesure n'existe pas.")
                .define("chemin_impasse", false);
        SOUVENIR = BUILDER.comment(
                "SOUVENIR : le client garde le monde deja vu, d'une session a l'autre.",
                "",
                "Un chunk qui n'a pas change depuis la derniere fois qu'un joueur l'a vu n'a pas",
                "besoin d'etre renvoye en entier a sa reconnexion : le client compare la revision",
                "qu'il connait a celle du serveur, et un chunk inchange se contente d'un accuse",
                "minuscule au lieu du paquet complet.",
                "",
                "L'identite du monde envoyee au client n'est JAMAIS la seed ni rien qui en derive :",
                "un identifiant aleatoire tire une seule fois a la creation du monde. Un client qui",
                "mentirait sur sa revision ne gagnerait qu'un decor visuellement en retard - toute",
                "collision, tout coup, toute interaction restent arbitres par le serveur sur l'etat",
                "reel, cache ou pas. Voir la javadoc de Souvenir pour le detail complet.",
                "",
                "Eteint par defaut : correct par construction, jamais passe au banc lab/ qui tranche",
                "conformite puis vitesse, comme l'exige la premiere regle de ce depot.")
                .define("souvenir", false);
        SOUVENIR_BUDGET_MB = BUILDER.comment(
                "SOUVENIR : plafond du cache de disque cote client, en mebioctets.",
                "",
                "Aucune eviction n'est encore ecrite au-dela de ce plafond dans cette version : on",
                "cesse simplement d'ecrire de nouvelles entrees. Voir SouvenirVault.")
                .defineInRange("souvenir_budget_mo", 256, 16, 4096);
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
        BURIN = BUILDER.comment(
                "Cassage robuste a une mauvaise connexion.",
                "CORRIGE DEUX REFUS DE VANILLA que le reseau provoque, et qui rendent le bloc au",
                "client (le « bloc fantome ») :",
                "1. La portee du bras est jugee sur la position que le SERVEUR croit au joueur, qui",
                "   a l'age du dernier paquet de mouvement recu. A 400 ms d'aller-retour, un joueur",
                "   qui marche est 1,1 bloc en arriere de la ou il se voit - au-dela de la marge",
                "   d'un bloc de vanilla. L'action est alors jetee EN SILENCE.",
                "2. Le rattrapage (la « destruction differee ») n'a qu'UNE place, et son tick passe",
                "   avant celui du cassage en cours. Un seul refus, et les blocs suivants n'ont plus",
                "   de filet : c'est « ca veut pas TOUT casser ».",
                "Ce n'est PAS un assouplissement du seuil de 0,7, et la marge n'est JAMAIS elargie",
                "sur la foi d'un symptome - seule la latence mesuree par le serveur peut l'ouvrir,",
                "elle est plafonnee a 2 blocs, et elle revient au chiffre de vanilla des que la",
                "ligne redevient bonne. Casser a distance reste refuse.")
                .define("cassage_robuste_reseau", true);
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

        ECLUSE = BUILDER.comment(
                "L'ECLUSE : la livraison des chunks passe apres le reste du tick.",
                "",
                "ETEINT PAR DEFAUT, et ce n'est pas un oubli : la mesure ne lui donne pas raison.",
                "",
                "L'idee etait de lisser le pic d'une arrivee. Le banc du seuil a montre que le pic",
                "n'est PAS la ou on le croyait. A distance de vue 10, sur un monde deja ecrit :",
                "  arrivee a FROID (chunks a lire)      : pire tick 106 ms de moyenne",
                "  arrivee a CHAUD (chunks deja en RAM) : pire tick  34 ms",
                "Les trois quarts du pic sont donc du CHARGEMENT, pas de l'envoi. Une ecluse posee",
                "a l'entree de l'envoi ne peut rien contre eux, et la mesure le confirme :",
                "  pire tick sans ecluse : 96,0 / 94,6 / 128,7 / 105,1 ms  (moyenne 106)",
                "  pire tick avec ecluse : 117,5 / 75,8 / 132,9 ms         (moyenne 109)",
                "Aucune difference, et le module allonge l'arrivee de 11 %.",
                "",
                "Le seul signe favorable est sur le changement de dimension - pire tick 77 ms sans,",
                "64 ms avec - mais la dispersion y est du meme ordre que l'ecart. Deux echantillons",
                "de chaque cote ne suffisent pas, et la regle du projet est qu'un module non prouve",
                "reste eteint.",
                "",
                "Le code est garde parce qu'il est juste et qu'il coute une comparaison quand il est",
                "eteint. A rallumer si vous voulez l'eprouver sur VOTRE machine - et merci de",
                "publier le chiffre.")
                .define("ecluse", false);

        TIDE_MIN_VIEW = BUILDER.comment(
                "Distance de VUE minimale : la maree ne descend jamais en dessous.",
                "",
                "Sous 3, un joueur ne voit plus assez loin pour se deplacer sans que le monde",
                "apparaisse devant lui. Si le serveur rame encore a ce plancher, ce n'est plus un",
                "probleme de distance et la maree cesse d'agir - elle le dit dans le journal.")
                .defineInRange("maree_vue_min", 3, 2, 32);
        TIDE_MAX_VIEW = BUILDER.comment(
                "Distance de VUE maximale : la maree ne monte jamais au-dessus.",
                "",
                "0 (defaut) : le plafond est ce que server.properties demande.",
                "",
                "Une valeur non nulle PRIME sur server.properties, dans les deux sens. C'est donc le",
                "moyen d'autoriser la maree a depasser ce que le fichier du serveur annonce, quand",
                "la machine le permet - ou de la brider en dessous sans toucher au fichier.")
                .defineInRange("maree_vue_max", 0, 0, 32);
        TIDE_MIN_SIMULATION = BUILDER.comment(
                "Distance de SIMULATION minimale. Meme principe que maree_vue_min.",
                "",
                "Attention : la simulation decide ce qui VIT. Trop bas, les fermes s'arretent des",
                "que le joueur s'eloigne un peu. C'est le reglage a ne pas descendre a la legere.")
                .defineInRange("maree_simulation_min", 3, 2, 32);
        TIDE_MAX_SIMULATION = BUILDER.comment(
                "Distance de SIMULATION maximale. 0 : ce que server.properties demande.",
                "",
                "Meme regle que maree_vue_max - une valeur non nulle prime dans les deux sens.")
                .defineInRange("maree_simulation_max", 0, 0, 32);
        TIDE_START_VIEW = BUILDER.comment(
                "Distance de VUE au demarrage. La maree monte ensuite si le tick le permet.",
                "",
                "La maree descendait depuis ce que server.properties demande. Elle part desormais",
                "d'ici et MONTE, sans jamais depasser server.properties.",
                "",
                "Pourquoi partir d'en bas - mesure du banc du seuil, une arrivee de joueur :",
                "  vue 10 : 21 x 21 = 441 chunks livres, 2197 ms de tick cumule",
                "  vue  5 : 11 x 11 = 121 chunks             3,6 fois moins",
                "",
                "Un serveur qui demarre bas est fluide TOUT DE SUITE et gagne de l'horizon quand il",
                "constate qu'il peut. L'inverse fait payer a tout le monde une distance que la",
                "machine ne tenait pas.",
                "",
                "0 : ancien comportement, on demarre au plafond.")
                .defineInRange("maree_depart_vue", 5, 0, 32);
        TIDE_START_SIMULATION = BUILDER.comment(
                "Distance de SIMULATION au demarrage. Meme principe que maree_depart_vue.",
                "",
                "0 : ancien comportement, on demarre au plafond.")
                .defineInRange("maree_depart_simulation", 5, 0, 32);
        REMBLAI = BUILDER.comment(
                "LE REMBLAI : poser ou casser beaucoup de blocs d'un coup.",
                "",
                "/fill, /clone, /setblock en rafale, et surtout le MINAGE INTENSIF - c'est la meme",
                "mecanique. Poser un bloc coute bien plus que l'ecrire : notification des voisins,",
                "recalcul de lumiere, carte des hauteurs, marquage pour le client.",
                "",
                "A mesurer avant de croire quoi que ce soit.")
                .define("remblai", true);
        FOULE = BUILDER.comment(
                "LA FOULE : ce qui reste QUADRATIQUE quand les entites s'entassent.",
                "",
                "Le socle traite deja le cout par entite. Ce module vise ce qui croit avec le CARRE",
                "de leur nombre - la ou mille betes coutent cent fois plus que cent.",
                "",
                "A mesurer avant de croire quoi que ce soit.")
                .define("foule", true);
        COLONNE = BUILDER.comment(
                "LA COLONNE : deux chunks voisins ne calculent plus deux fois le meme bruit.",
                "",
                "Le bruit du terrain n'est pas evalue a chaque bloc : il l'est aux COINS des",
                "cellules, puis interpole. Cela fait une grille de 5 x 5 colonnes par chunk. A",
                "l'interieur d'un chunk, le jeu reutilise correctement. Entre deux chunks, non :",
                "",
                "  chunk (0,0) calcule les colonnes cellX = 0 1 2 3 4",
                "  chunk (1,0) calcule les colonnes cellX =         4 5 6 7 8",
                "",
                "La colonne 4 est calculee deux fois, et les coins quatre fois. Pour N x N chunks,",
                "le jeu calcule 25 N^2 colonnes la ou il n'en existe que (4N+1)^2, soit 16 N^2.",
                "25/16 = 1,5625 : 36 % du bruit interpole est recalcule a l'identique.",
                "",
                "C'est exact par nature, pas par approximation : une fonction de densite est PURE,",
                "la meme position rend toujours la meme valeur. Rendre une valeur deja calculee,",
                "c'est rendre le meme double, au bit pres.",
                "",
                "Ne rend rien si les chunks voisins ne sont pas engendres a peu pres en meme temps.",
                "C'est le cas d'une pre-generation, et d'un joueur qui explore.",
                "",
                "Environ 6 Mo de memoire pour 4096 colonnes - a comparer aux 18 Mo qu'alloue la",
                "generation d'UN SEUL chunk.")
                .define("colonne", true);
        CIEL = BUILDER.comment(
                "LE CIEL : une cellule de terrain entierement vide n'est pas remplie bloc par bloc.",
                "",
                "Un chunk du surmonde fait 384 blocs de haut, soit 48 cellules de 8. La surface est",
                "vers la cellule 17 : DEUX TIERS des cellules sont en plein ciel. Pour chacune, le jeu",
                "fait 4x8x4 = 128 evaluations - interpolation, aquifere, regles de materiaux - pour",
                "aboutir a de l'air, qui est jete.",
                "",
                "L'interpolation trilineaire est une combinaison CONVEXE des huit coins de la cellule.",
                "Si les huit sont negatifs, tout l'interieur l'est : c'est un theoreme, pas une",
                "heuristique. Combine au chemin rapide de l'aquifere (au-dessus de skipSamplingAboveY,",
                "le resultat est rendu sans echantillonnage), la cellule est de l'air PARTOUT.",
                "",
                "Le terrain engendre est bit-identique. Un terrain plus rapide mais different serait",
                "un monde casse : les structures ne tomberaient plus ou les cartes les annoncent.")
                .define("ciel", true);
        CLIMAT = BUILDER.comment(
                "LE CLIMAT : la descente de l'arbre des biomes, sans ses sauts de pointeur.",
                "",
                "L'etape des biomes pese 22,6 % du temps de generation sur un seul fil, et",
                "Climate.RTree.search en est le coeur. La vraie boucle chaude est Node.distance :",
                "la machine virtuelle incorpore distance dans search, d'ou le nom que rend",
                "l'echantillonneur.",
                "",
                "Son defaut n'est pas arithmetique, il est MEMORIEL. Un noeud porte ses bornes dans",
                "un Parameter[7] - un tableau d'objets, donc sept sauts de pointeur par noeud visite",
                "  vanilla : 272 octets en 8 objets",
                "  ici     : 128 octets en 1 objet, deux lignes de cache",
                "Les quatorze bornes sont aplaties une fois, a la construction de l'arbre.",
                "",
                "Aucune operation arithmetique, aucun ordre de parcours, aucune borne ne change.",
                "",
                "CE QUI A ETE ESSAYE ET ECARTE, pour qu'on ne le cherche pas deux fois :",
                "  - Memoiser search : ELLE N'EST PAS PURE. Elle passe le dernier resultat du fil",
                "    comme borne initiale, et l'elagage est strict - a egalite de distance, c'est la",
                "    feuille heritee de l'appel precedent qui est rendue.",
                "  - Et la cle ne se repeterait jamais : depth varie de 312,5 unites quantifiees par",
                "    pas de quart. Un chunk produit 1536 cles distinctes. Taux de succes : zero.",
                "  - Hisser le ThreadLocal hors de la boucle : 3072 operations par chunk, soit 15 us",
                "    contre 25,1 ms pour l'etape. Cent fois sous le seuil de derive du laboratoire.",
                "",
                "PLAFOND : la descente d'arbre pese 4,2 % de la generation. Meme rendue gratuite",
                "elle ne rendrait pas plus. Le banc de debit ne verra pas ce module ; l'instrument",
                "est LANTERNE_FILON=1, poste BIOMES.")
                .define("climat", true);
        EMBALLAGE = BUILDER.comment(
                "L'EMBALLAGE : le paquet d'un chunk n'est pas reconstruit a chaque envoi.",
                "",
                "Mesure du banc du seuil, distance de vue 10, monde deja ecrit :",
                "  arrivee a froid : 2197 ms cumulees (chunks a lire PUIS emballer)",
                "  arrivee a chaud :  939 ms cumulees (chunks deja en memoire : emballage SEUL)",
                "",
                "43 % du cout d'une arrivee est donc de l'emballage pur, et il est repaye",
                "INTEGRALEMENT a chaque connexion, chaque changement de dimension, chaque",
                "aller-retour. Aucune pre-generation ne l'enleve.",
                "",
                "Le cache est invalide des qu'un bloc du chunk change : un paquet perime montrerait",
                "au joueur un monde qui n'existe plus.")
                .define("emballage", true);
                RECLAME = BUILDER.comment(
                "La RECLAME : retire les messages publicitaires que l'hebergeur injecte.",
                "",
                "Un hebergement gratuit se paie autrement : le demon ecrit de temps a autre une",
                "commande sur l'entree standard du serveur, comme si un administrateur l'avait",
                "tapee. Le message part alors a TOUS les joueurs, plusieurs fois par heure.",
                "",
                "Ce module ne rend RIEN en performances, et il ne pretend pas le contraire : un",
                "tellraw toutes les deux minutes est indetectable. Ce qu'il retire est un message",
                "que le joueur n'a pas demande, pas un cout.",
                "",
                "La regle est etroite : on n'annule que si la commande ne vient PAS d'un joueur ET",
                "qu'elle nomme le site d'un hebergeur connu (liste en clair dans core/Reclame).",
                "Un administrateur connecte en jeu qui ecrit lui-meme un tel message le verra",
                "partir : c'est SA decision.",
                "",
                "ATTENTION : c'est la contrepartie d'un hebergement gratuit, et la retirer peut",
                "contrevenir aux conditions de votre hebergeur. A vous de voir - d'ou ce reglage.")
                .define("reclame", true);

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
                "Minutes entre deux passages. Les joueurs sont prevenus 60 s, 10 s puis 3 s avant.")
                .defineInRange("periode_minutes", 15, 1, 720);
        BROOM_ITEMS = BUILDER.comment("Effacer les objets au sol.")
                .define("objets_au_sol", true);
        BROOM_MOBS = BUILDER.comment(
                "Effacer les creatures hostiles. Les apprivoisees, nommees et montees sont gardees.")
                .define("creatures_hostiles", false);
        BROOM_ARROWS = BUILDER.comment("Effacer les projectiles plantes.")
                .define("projectiles", true);
        BROOM_ITEM_WHITELIST = BUILDER.comment(
                "Objets jamais effaces par le balai, meme sans nom et loin de tout joueur.",
                "Un identifiant d'objet (\"minecraft:diamond\") ou un tag precede de # (\"#minecraft:ores\").",
                "Retirer une ligne pour l'exposer au balai ; en ajouter pour proteger autre chose.")
                .defineList("liste_blanche_objets", List.of(
                        "#minecraft:ores",
                        "#minecraft:head_armor",
                        "#minecraft:chest_armor",
                        "#minecraft:leg_armor",
                        "#minecraft:foot_armor",
                        "minecraft:diamond",
                        "minecraft:emerald",
                        "minecraft:raw_iron",
                        "minecraft:raw_gold",
                        "minecraft:raw_copper",
                        "minecraft:iron_ingot",
                        "minecraft:gold_ingot",
                        "minecraft:copper_ingot",
                        "minecraft:netherite_ingot",
                        "minecraft:netherite_scrap",
                        "minecraft:ancient_debris",
                        "minecraft:coal",
                        "minecraft:redstone",
                        "minecraft:lapis_lazuli",
                        "minecraft:quartz",
                        "minecraft:nether_star",
                        "minecraft:totem_of_undying"),
                        entry -> entry instanceof String s
                                && Identifier.tryParse(s.startsWith("#") ? s.substring(1) : s) != null);

        BUILDER.pop();
        BUILDER.pop();

        BUILDER.comment(
                "La pre-generation : /lanterne pregen <rayon en chunks>.",
                "",
                "Fabriquer un chunk devant le joueur coute une QUINZAINE de fois plus cher que de",
                "le servir depuis le disque (13-17 contre 155-209 chunks par seconde, mesure).",
                "Pre-generer ne repartit rien sur d'autres coeurs : cela SUPPRIME le travail du",
                "moment ou il gene. C'est le seul levier de generation dont le gain ne depende pas",
                "du nombre de coeurs, et donc le bon levier pour un hebergement a un coeur.",
                "",
                "Ce qui existe deja est saute sans etre charge : interrompre ne perd rien, et la",
                "reprise est gratuite.").push("pregeneration");

        PREGEN_PAUSE_ON_JOIN = BUILDER.comment(
                "Suspendre la pre-generation des qu'un joueur est connecte.",
                "",
                "Un joueur et une pre-generation se disputent le meme coeur. Suspendre rend au",
                "joueur la totalite du serveur, et la pre-generation reprend seule au depart du",
                "dernier. Rien n'est perdu : les chunks deja en vol aboutissent, et le temps passe",
                "en pause est RETIRE du calcul de debit - sans quoi le chiffre annonce a la fin",
                "mesurerait surtout le temps ou l'on n'a rien fait.",
                "",
                "ATTENTION : si vous lancez la commande depuis le jeu, vous etes vous-meme un",
                "joueur connecte. La pre-generation attendra donc votre deconnexion, et elle le dit",
                "en clair au lancement. Mettre a false pour generer pendant que vous jouez.")
                .define("pause_a_la_connexion", true);
        PREGEN_BUDGET_MS = BUILDER.comment(
                "Part d'un tick que la pre-generation s'autorise, en millisecondes.",
                "",
                "Un tick dure 50 ms. 30 (defaut) laisse de la marge au reste du serveur tout en",
                "avancant vite sur un serveur vide. Baisser si vous generez pendant que des joueurs",
                "jouent ET que pause_a_la_connexion est a false.",
                "",
                "Le budget est relu a CHAQUE chunk soumis, jamais une fois par tick.",
                "",
                "HISTOIRE DE CE REGLAGE, parce qu'elle explique pourquoi il n'a longtemps rien fait :",
                "la soumission passait par getChunkFuture, dont le nom ment. Appelee depuis un tick,",
                "elle BLOQUE jusqu'a ce que le chunk soit entierement fabrique. Une seule soumission",
                "coutait donc le prix entier d'un chunk - environ 89 ms sur la machine visee, soit",
                "pres du double d'un tick. Regler ce nombre de 1 a 45 ne changeait rigoureusement",
                "rien : la boucle sortait toujours apres exactement un chunk.",
                "",
                "Le debit mesure alors, 11,2 chunks par seconde, est exactement l'inverse d'un tick",
                "ainsi allonge. C'etait la signature du blocage, pas la vitesse de la machine.",
                "",
                "Depuis que la soumission est asynchrone, ce reglage borne ce qu'il annonce.")
                .defineInRange("budget_ms_par_tick", 30, 1, 45);
        PREGEN_IN_FLIGHT = BUILDER.comment(
                "Combien de chunks la pre-generation demande sans attendre les precedents.",
                "",
                "Sur un coeur unique, en demander plus ne peut PAS generer plus vite : le calcul",
                "est le meme et il n'y a qu'un coeur pour le faire. Le seul interet est de ne",
                "jamais laisser le coeur inoccupe entre deux etapes d'un chunk - des trous de",
                "quelques dizaines de microsecondes, face a des dizaines de millisecondes de calcul",
                "par chunk. Deux ou trois suffisent donc deja a les boucher.",
                "",
                "Au-dela, on ne paie que : chaque chunk en vol retient le cone de voisins que son",
                "statut FULL exige, chaque soumission recopie la table des chunks residents, et il",
                "a ete mesure 18 Mo alloues par chunk sur un tas de 4 Go.",
                "",
                "8 par defaut. Monter si la mesure montre un coeur qui s'ennuie ; descendre si la",
                "memoire serre. Ce reglage n'existait pas avant, et l'ancienne constante de 24 ne",
                "servait a rien : la soumission etait bloquante, il n'y a jamais eu qu'un chunk en",
                "vol a la fois.")
                .defineInRange("chunks_en_vol", 8, 1, 64);
        PREGEN_SERPENTINE = BUILDER.comment(
                "Parcourir le carre ligne par ligne plutot qu'en anneaux autour de l'origine.",
                "",
                "Un chunk au statut FULL exige de ses voisins des statuts intermediaires. Les deux",
                "parcours enchainent des positions voisines, mais pas avec le meme FRONT : pour un",
                "carre de cote S, le serpentin avance une ligne (S chunks), la spirale un anneau",
                "(4S). A couverture egale la spirale garde quatre fois plus de voisinage a portee,",
                "donc en relit davantage depuis le disque.",
                "",
                "Ce que le serpentin perd, et c'est serieux : arrete a mi-course il a fabrique une",
                "bande et non un disque autour du point d'apparition. La spirale reste donc le",
                "defaut. Mettre a true pour une pre-generation qu'on sait mener a son terme d'un",
                "coup, sur un monde neuf.",
                "",
                "Ni l'un ni l'autre ne change le terrain : l'ordre des demandes n'entre dans aucune",
                "graine. Le reglage est lu AU LANCEMENT et fige pour toute la duree - les deux",
                "parcours n'ont pas le meme curseur.")
                .define("parcours_en_serpentin", false);
        PREGEN_ASYNC = BUILDER.comment(
                "Demander les chunks sans bloquer le fil principal.",
                "",
                "MESURE sur la machine visee - MyBox Free, un coeur dedie, SerialGC - a travail",
                "rigoureusement identique : 1872 chunks generes, meme graine, meme rayon 24.",
                "",
                "  avant : 2 min 48 s, soit 11,2 chunks par seconde",
                "  apres : 2 min 25 s, soit 12,9 chunks par seconde       x1,152",
                "",
                "Et la preuve que le correctif agit est dans le journal de la pre-generation : le",
                "compteur « en vol » valait TOUJOURS zero auparavant. Il vaut desormais ce qu'on lui",
                "demande.",
                "",
                "Attention : ces 15 % couvrent aussi les modules ciel, climat et colonne, actifs",
                "pendant la meme mesure. Leur part respective n'est pas encore separee.",
                "",
                "Laisser a true. Ce reglage n'existe que pour pouvoir mesurer l'ancienne voie plutot",
                "que d'avoir a croire sur parole ce qui suit.",
                "",
                "getChunkFuture() ment sur son nom : appelee depuis le fil principal - et un",
                "gestionnaire de tick l'est toujours - elle ne rend la main qu'une fois le chunk",
                "entierement fabrique. La pre-generation etait donc strictement serielle, le budget",
                "par tick etait depasse d'un chunk entier a chaque fois (89 ms mesurees contre 30",
                "demandees), et le serveur restait en retard en permanence. Or c'est le retard qui",
                "coute le plus cher : tant que le tick deborde, le jeu refuse de decharger et",
                "d'ecrire les chunks finis, et il en accumule des milliers - que chaque nouvelle",
                "soumission repaie ensuite en recopiant la table.",
                "",
                "A true, on passe par la voie asynchrone que le jeu utilise lui-meme. Le terrain",
                "produit est identique : les deux chemins finissent sur le meme appel de generation",
                "et sur le meme niveau de ticket.")
                .define("soumission_asynchrone", true);
        PREGEN_CONTINUOUS = BUILDER.comment(
                "Pre-generer en continu, juste au-dela de ce que chaque joueur voit deja - sans",
                "commande, sans rayon fixe, sans jamais s'arreter. Voir lab/Lisiere.java.",
                "",
                "Ce n'est PAS la meme chose que la pre-generation manuelle ci-dessus, qui reste",
                "prioritaire : tant qu'elle tourne, ce reglage ne soumet rien, pour ne jamais se",
                "disputer les memes ressources.",
                "",
                "Ce module ne PEUT PAS savoir avec certitude qu'un coeur supplementaire (« flex »,",
                "offre par certains hebergeurs) existe reellement a un instant donne - aucune JVM ne",
                "le peut. Il observe seulement que le tick reste large (TickBudget.pressure() bas) et",
                "en deduit qu'il y a de la marge, quelle qu'en soit la cause. Des qu'elle se resserre,",
                "il s'arrete IMMEDIATEMENT ; il ne la reprend que lentement, une fois la marge",
                "confirmee durable - la meme asymetrie que TickBudget applique a lui-meme.",
                "",
                "Sur un coeur unique CONFIRME (Quota.cores() = 1), ce module reste aussi prudent que",
                "la pause manuelle ci-dessus : rien n'est soumis tant qu'un seul joueur est connecte,",
                "quelle que soit la pression du tick a l'instant - une accalmie momentanee n'est pas",
                "une marge reelle sur une machine qui n'a litteralement rien d'autre a donner.",
                "",
                "Eteint par defaut : correct par construction, jamais mesure sur un vrai hebergement",
                "mutualise. Un module non mesure n'existe pas.")
                .define("continu", false);

        BUILDER.pop();

        BUILDER.comment(
                "LA TREVE : suspendre le rendu BlueMap tant qu'un joueur est connecte.",
                "",
                "Constate en conditions reelles sur cette base : BlueMap actif en fond (2 fils de",
                "rendu) a fait tomber le serveur a 2068 ms (41 ticks) de retard 44 s apres la",
                "connexion d'un joueur - le client a alors recu d'un coup les paquets accumules",
                "pendant le retard, vu comme un arret quasi complet du rendu (0,4 image/seconde sur",
                "une fenetre d'une seconde, mesure par Radiographie). BlueMap n'est pas fautif : il",
                "fait ce qu'on lui demande, juste au mauvais moment.",
                "",
                "La treve suspend le rendu des la premiere connexion et le relance des le dernier",
                "depart. Rien n'est perdu : BlueMap reprend une region deja rendue sans la refaire.")
                .push("treve_bluemap");

        TREVE_BLUEMAP = BUILDER.comment(
                "Allumer la treve. Sans effet si BlueMap n'est pas installe.")
                .define("actif", true);

        BUILDER.pop();

        BUILDER.comment(
                "ETALON : accorder ce qui peut l'etre au verdict de Machine, pas au nombre de",
                "coeurs affiche par la JVM.",
                "",
                "Machine.appraise() ne se contente pas de lire availableProcessors() : elle CHRONOMETRE",
                "un travail identique seul puis a plusieurs, au demarrage, et en tire un gain reel et",
                "un verdict (voir sa Javadoc de classe pour l'incident qui l'a fait passer d'une",
                "mesure unique a une mediane de cinq essais - un facteur douze entre deux demarrages",
                "identiques en production, sur la meme VM 4 OCPU).",
                "",
                "Ce reglage ne branche AUJOURD'HUI qu'une seule chose : le bassin de decodage de",
                "ChunkDecode. Si Machine juge que paralleliser ne vaut pas son ordonnancement sur",
                "cette machine, le bassin reste a un seul fil - quel que soit ce que Quota.cores()",
                "aurait sinon accorde - et le journal dit pourquoi. Les autres bassins de ce depot ne",
                "sont PAS concernes : ChunkDecode.POOL suivait deja Quota.cores() - 1 sans jamais",
                "consulter Machine ; les trois bassins a un seul fil (magot des tableaux, plateau des",
                "disques, courses de telechargement) sont a un fil par construction - contention de",
                "disque ou d'ecran unique, pas faute de coeurs - et les grossir ne les accelererait",
                "pas ; et Threads (le budget de Util.BACKGROUND_EXECUTOR de vanilla) a deja essaye une",
                "formule automatique et l'a retiree APRES l'avoir mesuree inutile ou nuisible - voir sa",
                "Javadoc de classe. Ce reglage ne les reintroduit pas.",
                "",
                "A false, ce module ne touche plus a rien : ChunkDecode.POOL redevient exactement ce",
                "que Quota.cores() - 1 dicte, comme avant que Machine soit consulte. C'est le moyen de",
                "garder la main quand on sait, sur son propre hebergeur, que ce banc s'est trompe.")
                .push("etalon");

        ETALON_AUTO = BUILDER.comment(
                "Laisser Machine reduire un bassin de fils si elle juge la parallelisation",
                "contre-productive sur cette machine. Vrai par defaut.",
                "A false : la valeur explicite (ou, a defaut, Quota.cores()) prime sans etre corrigee -",
                "aucune surprise silencieuse, et le journal dit dans quel cas on se trouve.")
                .define("auto", true);

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
        // La spec, et non le type. Le mod enregistre TROIS fichiers de type SERVER —
        // « lanterne-server.toml », « lanterne-boutique.toml » et « lanterne-projecteur.toml ». Un
        // filtre sur le type les laisse donc passer tous les trois, et ce gestionnaire s'exécutait
        // trois fois par démarrage, en annonçant trois fois la même chose à six millisecondes
        // d'intervalle. C'est ce que l'utilisateur voyait « en triple » dans le journal de son
        // hébergeur, et il avait raison de trouver cela suspect : le message décrivait un évènement
        // qui n'avait eu lieu qu'une fois.
        //
        // Le filtre par type était juste le jour où il n'existait qu'un seul fichier par type. Il
        // est devenu faux en silence, sans qu'aucune ligne de ce fichier ne change — le genre de
        // régression qu'un ajout parfaitement innocent provoque ailleurs.
        if (event.getConfig().getSpec() != SPEC) {
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
