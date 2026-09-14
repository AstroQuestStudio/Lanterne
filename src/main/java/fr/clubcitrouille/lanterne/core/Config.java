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
    public static final ModConfigSpec.BooleanValue SHROUD;
    public static final ModConfigSpec.IntValue SHROUD_DELAY;
    public static final ModConfigSpec.IntValue SHROUD_NEAR;
    public static final ModConfigSpec.IntValue SHROUD_BUDGET;
    public static final ModConfigSpec.IntValue SHROUD_FAR;
    public static final ModConfigSpec.IntValue SHROUD_BULKY;
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
                "Intelligence : les comportements composites parcourus sans streams.",
                "600 villageois : x1,08 pour ce module seul.")
                .define("intelligence", true);
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
        SHROUD = BUILDER.comment(
                "LE VOILE - cote CLIENT : une creature cachee par un mur n'est pas preparee.",
                "Vanilla ecarte deja ce qui est hors du champ de vision. Il n'ecarte pas ce qui est",
                "dans le champ mais derriere de la pierre : ferme sous une colline, enclos vu depuis",
                "l'autre cote de sa grange, donjon. Chacune de ces betes paie extractEntity en entier",
                "- pose, animations, equipement, eclairage, texture - pour finir derriere un bloc.",
                "",
                "Sans effet sur le serveur : ce module ne touche qu'au rendu.")
                .define("voile", true);
        SHROUD_DELAY = BUILDER.comment(
                "Delai avant de reexaminer une meme creature, en millisecondes.",
                "100 (defaut) : une verification toutes les six images a 60 images par seconde.",
                "Plus bas = reaction plus vive quand une bete sort de derriere un mur, plus de rayons.",
                "Plus haut = moins de rayons, et jusqu'a ce delai de retard a l'apparition.")
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
                "boite depasse largement le bloc depasse aussi du mur, et la faire disparaitre se",
                "verrait de loin.")
                .defineInRange("voile_taille_max_blocs", 4, 1, 64);
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
