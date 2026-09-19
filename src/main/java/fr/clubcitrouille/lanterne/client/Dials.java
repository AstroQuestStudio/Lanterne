package fr.clubcitrouille.lanterne.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.lwjgl.sdl.SDLScancode;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import fr.clubcitrouille.lanterne.client.screen.Palette;
import fr.clubcitrouille.lanterne.client.upscale.Nuancier;
import fr.clubcitrouille.lanterne.client.upscale.Pivot;
import fr.clubcitrouille.lanterne.client.upscale.Rival;
import fr.clubcitrouille.lanterne.client.upscale.Upscale;
import fr.clubcitrouille.lanterne.core.ClientConfig;
import fr.clubcitrouille.lanterne.core.Config;
import fr.clubcitrouille.lanterne.core.Lens;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Les cadrans : l'écran de réglages du mod, en trois colonnes.
 *
 * <h2>Pourquoi trois colonnes et non une liste</h2>
 *
 * <p>La première version empilait huit réglages dans un panneau unique, avec une zone d'explication
 * au bas. Deux défauts sautaient aux yeux dès qu'on s'en servait : on ne savait pas ce qui
 * appartenait à quoi, et l'explication changeait sous la souris au moindre mouvement.
 *
 * <p>La disposition retenue est celle qui a fait ses preuves sur l'autre mod de ce projet : une
 * <b>barre de familles</b> à gauche, la <b>liste des réglages</b> de la famille choisie au centre,
 * et un <b>panneau de détail</b> à droite. Chaque zone répond à une question différente — où
 * suis-je, que puis-je régler, qu'est-ce que cela fait — et aucune ne bouge quand ce n'est pas la
 * sienne.
 *
 * <h2>Le compromis est toujours écrit</h2>
 *
 * <p>Aucun de ces réglages n'est gratuit. Les coffres perdent leur couvercle animé, les piles au sol
 * perdent leur indice de quantité. Le panneau de détail dit donc trois choses dans cet ordre : ce
 * que le module fait, <b>ce qu'il coûte</b>, et quand l'effet est visible. Un écran qui ne vanterait
 * que les gains pousserait à tout activer, puis à se demander pourquoi le jeu a changé.
 *
 * <h2>Deux fichiers, deux autorités — et la moitié de cet écran ne se règle pas ici</h2>
 *
 * <p>{@link ClientConfig} est de type {@code CLIENT} : il suit le joueur, il ne regarde que son
 * écran et ses haut-parleurs, et il se change librement — y compris en multijoueur. {@link Config}
 * est de type {@code SERVER} : il décide du travail que le serveur fait pour <b>tout le monde</b>,
 * et son mot appartient à l'administrateur.
 *
 * <p>Les quatre premières familles sont donc modifiables, les quatre dernières sont en
 * <b>lecture seule</b>, et c'est un choix qu'il faut justifier. L'autre voie possible était un
 * paquet réseau réservé aux opérateurs. Elle a été écartée pour trois raisons :
 *
 * <ol>
 *   <li>Un opérateur qui règle un serveur a déjà un accès au fichier et à la console. Lui offrir un
 *       second chemin, c'est ouvrir une surface d'attaque pour un confort qu'il n'a pas demandé.</li>
 *   <li>Plusieurs de ces réglages <b>ne peuvent pas</b> s'appliquer à chaud : les moules sont lus
 *       pendant le chargement des registres, la compression est fixée à la connexion, le moteur de
 *       redstone change le comportement d'un circuit déjà bâti. Un écran qui les laisserait basculer
 *       mentirait sur leur effet.</li>
 *   <li>Un client peut envoyer n'importe quel paquet. Un tel dispositif obligerait le serveur à
 *       revalider la permission à la réception — donc à écrire, tester et maintenir un chemin de
 *       confiance de plus, pour remplacer une ligne de fichier.</li>
 * </ol>
 *
 * <p>Ce qui reste dû au joueur, et que cet écran donne : <b>voir</b> ce que le serveur a décidé,
 * en clair, avec ce que chaque module coûte et rapporte. Une valeur qu'on ne peut pas changer mais
 * qu'on peut lire n'est plus une décision subie.
 */
public final class Dials extends Screen {
    private static final int SCRIM = 0xC8000000;
    private static final int PANEL = 0xF2141418;
    private static final int SIDE = 0xFF101014;
    private static final int EDGE = 0xFF2A2A32;
    private static final int RAIL = 0xFF1C1C22;
    private static final int AMBER = 0xFFFFC857;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int DIM = 0xFF8A8A92;
    private static final int FAINT = 0xFF5A5A62;
    private static final int HOVER = 0x20FFC857;
    private static final int PICKED = 0x2EFFC857;
    private static final int ON = 0xFF6BCB77;
    private static final int OFF = 0xFF44444E;

    private static final int WIDTH = 560;
    private static final int HEIGHT = 292;
    private static final int HEADER = 34;
    private static final int FOOTER = 24;
    private static final int SIDEBAR = 132;
    private static final int DETAIL = 184;
    private static final int ROW = 24;

    /**
     * La hauteur d'un onglet.
     *
     * <p>Elle valait trente quand il y avait quatre familles. Il y en a huit, et huit fois trente
     * dépassent la colonne de six pixels : c'est le genre de débordement qui ne se voit qu'une fois
     * la huitième famille écrite, c'est-à-dire trop tard pour s'en souvenir.
     */
    private static final int TAB = 28;

    /** Lignes de réglage visibles à la fois. Au-delà, la molette. */
    private static final int VISIBLE = 9;

    /** La phrase qui clôt le détail de tout réglage que ce joueur ne peut pas changer. */
    private static final String SERVER_NOTE =
            "L'administrateur décide : config/lanterne-server.toml.";

    /** Idem, pour les trois modules qui n'ont pas de ligne dans le fichier. */
    private static final String ENV_NOTE =
            "Sans ligne dans le fichier : s'arme par LANTERNE_MODULES.";

    /**
     * Le nom de la porte, écrit une fois pour les deux qui y mènent.
     *
     * <p>Il y a deux chemins vers cet écran, et il n'y en aura jamais qu'un d'ouvert à la fois :
     * {@code mixin/VideoOptionsMixin} quand l'écran vidéo est celui de vanilla,
     * {@code client/sodium/Graft} quand Sodium l'a remplacé. Deux littéraux à tenir pour un seul
     * intitulé, c'est l'occasion d'en corriger un et pas l'autre — et le joueur qui change de mod de
     * rendu verrait alors le nom de la porte changer sous lui sans raison.
     */
    public static final Component DOOR = Component.literal("🎃 Lanterne — rendu");

    private final Screen parent;
    private final List<Family> families = new ArrayList<>();
    private int picked;
    private int hovered = -1;
    private int scroll;

    private int panelX;
    private int panelY;

    /** Le dernier mot de l'écran, et l'instant où il a été dit. Voir {@link #say(String)}. */
    private String flash = "";
    private long flashAt;

    /**
     * L'ajusteur : en echelle d'interface automatique, 560 x 292 ne tient pas dans les 480 x 270
     * qu'une fenetre de 1920 x 1080 laisse. Voir {@link Fit}.
     */
    private final Fit fit = new Fit();

    public Dials(Screen parent) {
        this(parent, 0);
    }

    /**
     * Ouvre directement sur une famille donnée, plutôt que « Le Voile » par défaut.
     *
     * <p>Réservé aux vérifications automatiques (même esprit que
     * {@code client.screen.Palette#testClick}) : une capture d'écran qui doit montrer la famille
     * « L'image » n'a pas de souris pour cliquer l'onglet. La porte réelle,
     * {@code mixin.VideoOptionsMixin}, n'appelle jamais que le constructeur à un argument ci-dessus,
     * donc un joueur ouvre toujours sur « Le Voile » comme avant — ce second constructeur ne change
     * rien pour lui.
     */
    public Dials(Screen parent, int startFamily) {
        super(Component.literal("Lanterne"));
        this.parent = parent;
        this.picked = Math.max(0, startFamily);
    }

    @Override
    protected void init() {
        this.fit.measure(this.width, this.height, WIDTH, HEIGHT);
        // La place disponible APRES reduction, et non la largeur reelle : centrer sur cette
        // derniere decalerait le panneau de tout le facteur de reduction.
        this.panelX = (this.fit.viewWidth() - WIDTH) / 2;
        this.panelY = Math.max(10, (this.fit.viewHeight() - HEIGHT) / 2);
        if (!this.families.isEmpty()) {
            return;
        }

        buildVeil();
        buildScenery();
        buildImage();
        buildSound();
        buildCreatures();
        buildWorld();
        buildMachine();
        buildExtras();
    }

    // --- Ce qui se règle ici : le fichier client ---------------------------

    private void buildVeil() {
        Family veil = new Family("Le Voile", false, "ce qu'un mur cache n'est pas dessiné");
        veil.add(Dial.toggle("Créatures", Settings::shroud, ClientConfig.SHROUD,
                "Une créature qu'un mur cache n'est pas préparée pour le rendu.",
                "1 000 vaches sous un toit : 29,9 → 238,8 im/s, soit ×7,98.",
                "Effet immédiat."));
        veil.add(Dial.toggle("Blocs-entités", Settings::veilBlockEntities,
                ClientConfig.VEIL_BLOCK_ENTITIES,
                "Coffres, panneaux et fourneaux derrière un mur ne sont pas préparés.",
                "1 200 coffres : 71,2 → 189,0 im/s, soit ×2,65. Un bloc en cours de cassage n'est"
                        + " jamais voilé : sa fissure est le retour visuel du minage.",
                "Effet immédiat. Demande le voile des créatures actif."));
        veil.add(Dial.toggle("Particules", Settings::veilParticles, ClientConfig.VEIL_PARTICLES,
                "Une particule née derrière un mur ne naît pas : ni création, ni tick, ni rendu.",
                "Vanilla ne filtre que la distance, et n'écarte que le dessin. Les particules que le"
                        + " jeu tient à montrer coûte que coûte ne sont jamais refusées.",
                "Effet immédiat. Demande le voile des créatures actif."));
        veil.add(Dial.number("Délai de réexamen", ClientConfig.SHROUD_DELAY, 0, 1000, 10, " ms",
                "Temps avant de réexaminer une même créature.",
                "100 ms, c'est une vérification toutes les six images à 60 im/s. Les échéances sont"
                        + " ÉTALÉES d'une bête à l'autre — sans cela, mille d'entre elles expirent à"
                        + " la même image et le gain part en à-coups.",
                "Plus court : plus juste et plus cher. Effet immédiat."));
        veil.add(Dial.number("Zone franche", ClientConfig.SHROUD_NEAR, 0, 64, 1, " blocs",
                "En deçà de cette distance, aucune créature n'est jamais voilée.",
                "Sous quelques blocs, l'erreur d'un rayon — un coin de mur, une marche — se verrait"
                        + " tout de suite, et le gain est nul : une bête proche est rarement cachée"
                        + " longtemps.",
                "Effet immédiat."));
        veil.add(Dial.number("Rayons par image", ClientConfig.SHROUD_BUDGET, 1, 1024, 8, "",
                "Plafond de rayons lancés par image.",
                "Au-delà, les créatures non encore examinées gardent leur dernier état — VISIBLE par"
                        + " défaut, jamais l'inverse : un plafond atteint ne doit pas faire"
                        + " disparaître une bête.",
                "Effet immédiat."));
        veil.add(Dial.number("Portée", ClientConfig.SHROUD_FAR, 16, 512, 16, " blocs",
                "Au-delà de cette distance, plus aucun rayon n'est lancé.",
                "Un rayon long coûte cher pour une créature que la distance de rendu d'entités"
                        + " écarte souvent déjà d'elle-même.",
                "Effet immédiat."));
        veil.add(Dial.number("Taille maximale", ClientConfig.SHROUD_BULKY, 1, 64, 1, " blocs",
                "Au-delà de cette taille de boîte, une créature n'est jamais voilée.",
                "Cinq points sondés décrivent mal un dragon ou une baleine de mod : une bête dont la"
                        + " boîte dépasse largement le bloc dépasse aussi du mur.",
                "Effet immédiat."));
        this.families.add(veil);
    }

    private void buildScenery() {
        Family decor = new Family("Le décor", false, "coffres, feuilles, objets au sol");
        decor.add(Dial.toggle("Coffres statiques", Settings::staticChests,
                ClientConfig.STATIC_CHESTS,
                "Un coffre rendu comme un bloc ordinaire, maillé une fois dans son chunk.",
                "1 200 coffres : ×1,45. Prix : le couvercle ne s'anime plus. Les boîtes de shulker"
                        + " ne sont pas concernées — leur couvercle est une information, pas une"
                        + " décoration.",
                "Visible au prochain chargement."));
        decor.add(Dial.cycle("Feuilles masquées", () -> leafLabel(Settings.leafCulling()),
                () -> Settings.leafCulling() == ClientConfig.LeafCulling.JAMAIS ? DIM : AMBER,
                Dials::cycleLeaves,
                "Masque les faces que deux feuilles collées se cachent l'une l'autre.",
                "12 167 feuilles : ×1,42. AUTO suit ton option « Feuilles ajourées » et ne peut"
                        + " donc rien trouer ; TOUJOURS gagne plus et laisse voir des trous de près.",
                "Visible au prochain chargement."));
        decor.add(Dial.cycle("Objets au sol", () -> Settings.itemCopies() + " exempl.",
                () -> Settings.itemCopies() < 4 ? AMBER : DIM,
                Dials::cycleItems,
                "Vanilla dessine une pile au sol jusqu'à QUATRE fois, légèrement décalée.",
                "1 500 piles pleines : ×2,35. Prix : une pile de 64 paraît seule.",
                "Effet immédiat."));
        this.families.add(decor);
    }

    private void buildImage() {
        Family image = new Family("L'image", false, "échelle de rendu et mesure");
        // « Upscaling » et non « FSR » : le joueur veut savoir CE QU'IL OBTIENT, pas quelle
        // bibliothèque a gagné le pari. Le nom du procédé appartient à la colonne de détail, qui a
        // la place de le nommer et de créditer son auteur.
        image.add(Dial.cycle("Upscaling", Dials::scaleLabel,
                () -> Upscale.broken() ? FAINT : Upscale.active() ? AMBER : DIM,
                Dials::cycleScale,
                "Le monde est rendu plus petit, puis remonté à la taille de l'écran ; l'interface"
                        + " reste native. Procédé : SPATIAL, d'après FSR 1.0 d'AMD.",
                "Un pack de SHADERS NE DONNE PAS d'images par seconde : il en COÛTE. C'est le prix"
                        + " de la lumière, des ombres et de l'eau. Ce réglage est ce qui les rend"
                        + " jouables — on économise 55 % des pixels à 67 % par dimension, et on"
                        + " dépense cette économie en shaders.",
                "Le gain vient du NOMBRE DE PIXELS RENDUS, pas du remonteur : sur une carte NVIDIA,"
                        + " FSR donne donc quasiment les mêmes images/s que DLSS ; seule la tenue"
                        + " des fins détails en mouvement diffère un peu. Effet immédiat, et se"
                        + " juge à l'œil : la netteté baisse avec le facteur."));
        // Un écran à part plutôt qu'un sélecteur de plus : la liste est de longueur variable — zéro
        // nuancier déposé au premier lancement, un ou deux ensuite — et un simple cycle ne dirait ni
        // combien il y en a, ni lequel est actif sans les faire tous défiler à l'aveugle. Même
        // mécanique que « /lanterne nuancier » (voir client.upscale.Nuancier) : cette ligne-ci se
        // contente d'ouvrir client.screen.Palette, qui appelle exactement les mêmes deux méthodes,
        // Nuancier.listNames() et Upscale.setNuancier(String).
        image.add(Dial.cycle("Nuanciers", Dials::nuancierLabel,
                () -> Upscale.nuancierActif().isBlank() ? DIM : AMBER,
                direction -> openPalette(),
                "Une liste des nuanciers déposés sous config/lanterne/shaderpacks/, avec lequel est"
                        + " actif — le même contenu que « /lanterne nuancier liste », en écran.",
                "Un nuancier déposé REMPLACE la chaîne calculée par netteté et anticrénelage : les"
                        + " deux réglages restent en mémoire mais n'agissent plus tant qu'un nuancier"
                        + " est actif.",
                "S'applique au clic, sans redémarrage — la prochaine image dessinée le montre déjà."));
        image.add(Dial.cycle("Anticrénelage", () -> Upscale.antialias() ? "actif" : "coupé",
                () -> !Upscale.active() ? FAINT : Upscale.antialias() ? ON : OFF,
                Dials::toggleAntialias,
                "Lisse les contours AVANT la remontée d'échelle. C'est ce qui empêche de « voir les"
                        + " pixels ».",
                "AMD en fait une CONDITION d'emploi de FSR : « Image should already be well"
                        + " anti-aliased ». Minecraft n'anticrénèle rien, donc sans cette passe EASU"
                        + " reçoit des marches d'escalier et les AGRANDIT au lieu de les lisser —"
                        + " puis la netteté ci-dessous les raffermit. Le défaut se voit surtout"
                        + " sous 67 % (Équilibré et en dessous).",
                "Effet immédiat. Prix : une passe de plus, mais à la résolution RÉDUITE — donc sur"
                        + " 44 % des pixels au préréglage Qualité. À couper seulement pour"
                        + " comparer."));
        image.add(Dial.cycle("Netteté", () -> Upscale.edge().label(),
                () -> Upscale.active() ? AMBER : FAINT,
                Dials::cycleEdge,
                "La passe RCAS d'AMD rend aux contours le mordant que l'agrandissement enlève.",
                "Trop forte, elle fait scintiller le ciel et le sable. Le défaut est « moyenne ».",
                "Effet immédiat, et retenu d'une partie à l'autre."));
        image.add(Dial.cycle("Houle (adaptatif)", () -> Upscale.swell() ? "active" : "arrêtée",
                () -> Upscale.swell() ? AMBER : DIM,
                Dials::toggleSwell,
                "L'échelle suit le taux d'images au lieu d'être choisie une fois pour toutes.",
                "Le préréglage ci-dessus devient le PLANCHER : la houle ne descend jamais plus bas.",
                "Dix secondes de grâce, puis un palier toutes les quatre secondes au plus."));
        // Le backend suit immédiatement l'upscaling, parce que c'est de lui que dépend ce que
        // l'upscaling pourra faire demain.
        image.add(Dial.cycle("Vulkan", () -> Pivot.state().label(), Dials::pivotColour,
                Dials::cyclePivot,
                "Le backend graphique du jeu, à la place d'OpenGL.",
                "Le clic écrit DEUX fichiers : options.txt, et config/fml.toml où il COUPE l'écran"
                        + " de chargement de NeoForge. Sans cela le jeu refuse de démarrer sous"
                        + " Vulkan — NeoForge lui cède une fenêtre OpenGL. Prix : plus de barre de"
                        + " progression au lancement, donc quelques secondes d'écran vide.",
                "Au prochain lancement. Si Vulkan échoue, le jeu retombe DE LUI-MÊME sur OpenGL,"
                        + " dans le même lancement : on ne peut pas se coincer. Mojang marque"
                        + " Vulkan « expérimental » et ne promet aucun gain — ni nous."));
        // La ligne « DLSS » a été RETIRÉE de cet écran, et c'est un gain de clarté. Elle ne pouvait
        // que dire « non disponible » : la licence de NVIDIA interdit nommément à une œuvre sous
        // licence libre d'embarquer son modèle. Afficher un nom de marque pour annoncer une absence
        // n'apprend rien à personne et encombre une famille qui a mieux à montrer.
        //
        // Le diagnostic, lui, reste entier : Scene.give() appelle Deep.probe() à la première image,
        // qui écrit au journal ce qui manque exactement — backend, carte, chargeur ou modèle. Voir
        // notes/dlss-panama.md pour l'enquête et son verdict.
        image.add(Dial.toggle("Tampon mutable", Settings::tampon, ClientConfig.TAMPON,
                "Un tampon immuable oblige le pilote à ATTENDRE quand le jeu réécrit dedans pendant"
                        + " que la carte s'en sert. Sur NVIDIA et Intel Gen7, cette attente se voit :"
                        + " l'image se pose, puis saute.",
                "NON MESURÉ ICI, et c'est la seule exception du mod à cette règle : c'est le"
                        + " correctif que Mojang a écrit lui-même en 26.3, et que la 26.2 n'a pas."
                        + " Sans effet sous Vulkan, ni sur une autre carte.",
                "Au prochain lancement : la décision est prise une seule fois, à la création du"
                        + " périphérique graphique."));
        image.add(Dial.toggle("Jauge de performance", Settings::gauge, ClientConfig.GAUGE,
                "Images/s, centile le plus lent et créatures voilées, dans un coin de l'écran.",
                "F3 donne une moyenne arrondie ; la jauge donne les à-coups, qui sont ce qu'on"
                        + " ressent.",
                "Effet immédiat. Fenêtre glissante de deux secondes."));
        image.add(Dial.toggle("Le Regard", Settings::regard, ClientConfig.REGARD,
                "En visant un bloc ou une entité : nom, contenu réel d'un conteneur, vie, équipement —"
                        + " dans l'esprit de Jade/HWYLA.",
                "Relié aux vrais modules de Lanterne : cadence de simulation d'une créature, vrai"
                        + " débit d'un entonnoir, vraie portée de fusion d'un tas d'objets — ce"
                        + " qu'un mod d'inspection générique ne peut pas savoir.",
                "Effet immédiat. Une petite requête réseau, au plus toutes les dix images, et"
                        + " seulement en visant un vrai conteneur."));
        this.families.add(image);
    }

    private void buildSound() {
        Family sound = new Family("Les sons", false, "ce que l'oreille ne distingue pas");
        sound.add(Dial.toggle("Sons superposés", Settings::din, ClientConfig.DIN,
                "Au-delà d'un certain nombre, un même son au même endroit n'apporte plus rien.",
                "Confort, pas performance : le son vit sur son propre fil. Musique, interface et"
                        + " voix ne sont jamais concernées.",
                "Dans une ferme, la bouillie redevient un son."));
        sound.add(Dial.number("Taille d'une région", ClientConfig.DIN_REGION, 1, 64, 1, " blocs",
                "Côté du cube dans lequel deux sons comptent comme « au même endroit ».",
                "Grand, on regroupe une ferme entière ; petit, chaque enclos compte à part.",
                "Effet immédiat."));
        sound.add(Dial.number("Tolérance", ClientConfig.DIN_ALLOWANCE, 1, 32, 1, " sons",
                "Sons identiques tolérés dans une région pendant la fenêtre.",
                "3 : un son isolé, ou deux, ou trois, passent toujours. Il faut une RAFALE pour que"
                        + " ce module agisse.",
                "Effet immédiat."));
        sound.add(Dial.number("Fenêtre", ClientConfig.DIN_WINDOW, 20, 5000, 50, " ms",
                "Durée de la fenêtre glissante qui compte les répétitions.",
                "Longue, elle attrape les rafales lentes ; courte, elle ne voit que les salves.",
                "Effet immédiat."));
        this.families.add(sound);
    }

    // --- Ce qui se lit seulement : le fichier serveur -----------------------

    private void buildCreatures() {
        Family mobs = new Family("Les créatures", true,
                "ce qui réfléchit, se déplace et se bouscule");
        mobs.add(Dial.serverFlag("Niveau de détail", Config.LOD,
                "Une créature lointaine réfléchit moins souvent. Rien n'est dégradé à moins de 32"
                        + " blocs d'un joueur.",
                "Élevage de 1 000 vaches, socle complet : 22,40 ms → 4,47 ms. C'est le cœur du mod."));
        mobs.add(Dial.serverFlag("Densité", Config.DENSITY,
                "Ce qui est noyé dans le nombre se distingue moins : dans un tas de mille bêtes, une"
                        + " bête décide jusqu'à 32 fois moins souvent.",
                "La production — croissance, ponte, reproduction — reste à l'heure exacte."));
        mobs.add(Dial.serverFlag("Collisions", Config.COLLISIONS,
                "Presque rien ne peut bloquer quoi que ce soit : seuls le bateau, le shulker et le"
                        + " ghast apprivoisé redéfinissent canBeCollidedWith.",
                "8 000 objets au sol : ×12,04."));
        mobs.add(Dial.serverFlag("Enclos", Config.PASTURE,
                "Une bête qui n'a pas parcouru 2 blocs en 5 secondes cesse de décider d'aller se"
                        + " promener — une décision qui lui faisait calculer un chemin pour finir au"
                        + " même endroit.",
                "Le résultat observable est identique ; seul le calcul disparaît. Jamais appliqué à"
                        + " ce qui porte un nom, est apprivoisé, monté ou tenu en laisse."));
        mobs.add(Dial.serverFlag("Intelligence", Config.MIND,
                "Le cerveau des créatures cesse de redécouvrir à chaque tick ce qu'il savait déjà :"
                        + " comportements en cours, mémoires à expirer.",
                "600 villageois : ×1,32 pour ce module seul, et 153 Mo alloués en moins."));
        mobs.add(Dial.serverFlag("Boîtes partagées", Config.BOXES,
                "Les conditions d'entrée des comportements emballent une valeur absente dans un"
                        + " objet neuf à chaque essai. L'objet est immuable, donc partageable.",
                "600 villageois : 1,88 Go alloués pour ranger du vide, soit 10,5 % du total."));
        mobs.add(Dial.serverFlag("Capteur de joueurs", Config.SENSES,
                "Le même travail, sans ses trois pipelines de flux — dont le premier comporte un tri."
                        + " Ce capteur équipe toute créature à cerveau.",
                "Le profileur désignait la machinerie de flux comme premier poste du serveur, à 24 %."
                        + " Même ordre de tri, mêmes filtres, mêmes mémoires écrites."));
        mobs.add(Dial.serverFlag("Mémoire des conditions", Config.RECALL,
                "Ne pas retester ce qui n'a pas changé : un compteur sur le cerveau s'incrémente à"
                        + " toute écriture, et tant qu'il ne bouge pas la réponse précédente vaut.",
                "MESURE HONNÊTE : ×1,01 sur 600 villageois, sous le plancher du banc. Gardé parce"
                        + " qu'il ne coûte rien, et SANS chiffre de gain."));
        mobs.add(Dial.serverFlag("Projectiles", Config.PROJECTILES,
                "Une flèche ne peut pas toucher une flèche.",
                "6 000 flèches en vol : 97,25 ms → 32,50 ms, soit ×2,99. Une épreuve de tir vérifie"
                        + " qu'une flèche touche encore une créature."));
        mobs.add(Dial.serverFlag("Amas immobiles", Config.JAM,
                "Court-circuit de bousculade quand un tas de bêtes ne bouge plus.",
                "Il ne vaut que si l'Enclos tourne : tant que les bêtes décident d'errer, rien ne"
                        + " s'immobilise, donc rien ne peut se court-circuiter."));
        mobs.add(Dial.serverFlag("Rendement strict", Config.STRICT_YIELD,
                "Pour les créatures venues de mods : le tick s'exécute et l'on n'en retire que"
                        + " l'intelligence et le déplacement, au lieu de l'annuler et de rattraper"
                        + " les compteurs à la main.",
                "Plus sûr et plus lent : 14,31 ms contre 10,01 sur la même charge. Inutile en"
                        + " vanilla, où les compteurs sont déjà rattrapés exactement."));
        this.families.add(mobs);
    }

    private void buildWorld() {
        Family world = new Family("Le monde", true, "blocs, objets, végétation");
        world.add(Dial.serverFlag("Sommeil des blocs", Config.SLEEP,
                "Un four qui cuit sait quand il aura fini, et n'a rien à faire d'ici là.",
                "S'applique aux blocs-entités dont l'échéance est connue d'avance."));
        world.add(Dial.serverFlag("Entonnoirs par lots", Config.BULK,
                "Seize objets par recherche de conteneur, au lieu d'un.",
                "La recharge suit le lot : le débit moyen est EXACTEMENT celui de vanilla. Ce n'est"
                        + " pas un ralentissement — vanilla fait déjà cela pour un objet ramassé."));
        world.add(Dial.serverFlag("Fusion des objets", Config.GATHER,
                "Fusion sur 2 blocs à l'horizontale et 1 à la verticale, contre 0,5 et ZÉRO en"
                        + " vanilla — deux objets empilés ne s'y rejoignent jamais.",
                "Un objet posé cherche ses voisins tous les 10 ticks au lieu de 40. Le plafond d'âge"
                        + " de 6 000 ticks est conservé."));
        world.add(Dial.serverFlag("Chute des feuilles", Config.DECAY,
                "Un arbre abattu ne laisse pas sa couronne suspendue dans le vide : la chute est"
                        + " replanifiée au lieu d'attendre un tick aléatoire, d'espérance 1 360 ticks.",
                "Ne touche PAS aux feuilles posées à la main. Sans rapport avec le masquage des"
                        + " feuilles, qui est un réglage client — voir « Le décor »."));
        world.add(Dial.serverCount("Délai de chute", Config.DECAY_DELAY, " ticks",
                "Ticks entre le détachement d'une feuille et sa chute.",
                "5, soit un quart de seconde : la cascade reste progressive mais se compte en"
                        + " secondes. 1 : quasi instantané. 40 : plus contemplatif."));
        world.add(Dial.serverFlag("Moules de collision", Config.MOULDS,
                "Les formes de collision partagées entre états de blocs identiques. Une clôture ou"
                        + " un tuyau de mod ont des centaines d'états qui décrivent le même volume.",
                "Tout le travail est fait au CHARGEMENT. 11 413 formes effectivement partagées, mais"
                        + " l'écart de mémoire (9,2 Mo) est du même ordre que le bruit du banc."));
        world.add(Dial.serverFlag("Calque du routeur de bruit", Config.CALQUE,
                "Avant de fabriquer le relief d'un chunk, le jeu parcourt son arbre de fonctions de"
                        + " densité pour y glisser des caches. L'étape des biomes en redemande SIX"
                        + " parcours complets — alors que le constructeur les avait déjà faits.",
                "Résultat identique, et c'est démontré : le contrôle compare les RÉFÉRENCES et"
                        + " trouve les mêmes objets, donc aucun écart n'est possible en aucun point."
                        + " Gain mesuré ×1,00 à ×1,07 selon la charge de la machine : sous le seuil"
                        + " du laboratoire, donc gardé SANS chiffre de gain."));
        world.add(Dial.serverFlag("Cadastre des structures", Config.CADASTRE,
                "Le placeur Jigsaw teste chaque pièce contre une forme de voxels qui grossit à"
                        + " chaque pièce posée — son coût croît comme le CUBE du nombre de pièces."
                        + " Ici la place prise est tenue dans un index de boîtes.",
                "N'agit QUE pendant la génération de terrain ; un monde déjà exploré n'y passe plus."
                        + " Idée de StructureLayoutOptimizer (MIT), implémentation différente."));
        world.add(Dial.serverFlag("Moteur de redstone", Config.REDSTONE,
                "Celui que Mojang a écrit puis laissé éteint : il traite le réseau ENTIER et n'émet"
                        + " que les mises à jour finales, au lieu de recalculer brin par brin.",
                "ÉTEINT PAR DÉFAUT, et ce n'est pas de la prudence : il CHANGE LE COMPORTEMENT DU"
                        + " JEU. Il corrige MC-11193, et peut casser un circuit bâti sur l'ordre"
                        + " d'origine."));
        world.add(Dial.serverFlag("Points d'intérêt", Config.POI,
                "Parcourus sans emballer d'entiers : un villageois qui cherche un lit à 48 blocs"
                        + " balaie 81 chunks, soit 1 944 entiers emballés par recherche.",
                "Même paresse, même ordre, même contenu."));
        world.add(Dial.serverFlag("Effets traversés", Config.SPILL,
                "Ne pas fabriquer un tableau vide pour ne rien copier.",
                "600 villageois : 2,4 millions de tableaux évités, 20 Mo sur 2 900. Modeste et"
                        + " gratuit — un serveur rapide est fait de la somme de ces gains-là."));
        world.add(Dial.serverFlag("Explosions", Config.EXPLOSIONS,
                "Le tracé des rayons, sans ses 20 000 objets jetables par tir.",
                "6 000 TNT : ×1,11 pour ce module seul. Deux autres modules « explosions » ont été"
                        + " retirés après mesure : le cache de chunk du jeu absorbait déjà leur gain."));
        world.add(Dial.serverFlag("Positions réutilisées", Config.SCRATCH_POS,
                "Une position par tirage aléatoire de bloc, et le spawner en tire beaucoup.",
                "Aucun effet sur le temps, et ×3,01 SUR LA MÉMOIRE (4,08 Go → 1,36 Go). Sur une"
                        + " machine à un cœur, un ramassage ne s'exécute pas en parallèle : il fige."));
        world.add(Dial.serverFlag("Minage à l'horloge", Config.MINING,
                "Le minage compté à l'horloge réelle plutôt qu'en ticks serveur.",
                "CORRIGE UN DÉFAUT DE VANILLA : à 15 TPS, le client accumule 20 unités là où le"
                        + " serveur en compte 15, et le bloc revient. On corrige une unité de"
                        + " mesure, pas une tolérance."));
        world.add(Dial.serverFlag("Cassage sur mauvaise ligne", Config.BURIN,
                "Le cassage rendu robuste à une connexion lente ou irrégulière.",
                "CORRIGE DEUX REFUS DE VANILLA : la portée du bras jugée sur une position vieille"
                        + " d'une demi-latence, et le rattrapage qui n'a qu'UNE place et se coince"
                        + " au premier refus — après quoi plus rien ne casse. La marge ne s'ouvre"
                        + " qu'en proportion de la latence mesurée, plafonnée à 2 blocs, et revient"
                        + " au chiffre de vanilla dès que la ligne redevient bonne."));
        this.families.add(world);
    }

    private void buildMachine() {
        Family machine = new Family("La machine", true, "réseau, disque, cadence");
        machine.add(Dial.serverFlag("Marée", Config.TIDE,
                "Ajuste les distances de vue et de simulation selon le temps de tick réel.",
                "Elle ne dépasse JAMAIS ce que server.properties demande : elle descend quand le"
                        + " serveur souffre et remonte ensuite. C'est le seul module qui change ce"
                        + " que voit le joueur."));
        machine.add(Dial.serverFlag("Élastique", Config.ELASTIQUE,
                "Les seuils anti-triche du mouvement mesurés à l'horloge plutôt qu'en ticks.",
                "NE REND RIEN PLUS RAPIDE : le seul module qui cache un symptôme au lieu de traiter"
                        + " une cause, et le seul livré ÉTEINT. Sous les 75 ms de tick, il rend les"
                        + " constantes du jeu au bit près ; au-delà, il les élargit du carré du"
                        + " retard. La destination reste confrontée aux collisions du monde."));
        machine.add(Dial.serverCount("Zone franche", Config.NEAR_RADIUS, " blocs",
                "Rayon dans lequel rien n'est dégradé, ni par la distance ni par la foule.",
                "Le seul réglage qui arbitre un COMPROMIS et non un gain. À 24, aucune saccade sous"
                        + " les yeux du joueur ; à 8, la foule reprend ses droits et le serveur"
                        + " respire. La pression le rétrécit de moitié toute seule quand il faut."));
        machine.add(Dial.serverFlag("Réseau", Config.NETWORK,
                "Les paquets de position espacés pour les entités lointaines.",
                "Ce qu'on ne distingue pas à cent blocs ne mérite pas vingt paquets par seconde."));
        machine.add(Dial.serverFlag("Compression", Config.WIRE,
                "Niveau 1 au lieu de 6. Un joueur qui arrive avec 32 chunks de vue reçoit 441"
                        + " paquets de chunk d'affilée, tous compressés au niveau six.",
                "Prix : des paquets 10 à 15 % plus gros, compressés 3 à 5 fois plus vite. Le format"
                        + " ne change pas ; un client vanilla ne voit rien. Pris à la CONNEXION."));
        machine.add(Dial.serverFlag("Sauvegarde lz4", Config.SAVE,
                "153 ms → 43 ms pour 64 chunks, soit ×3,54.",
                "Prix : 23 % de place en plus sur le disque. Rétrocompatible — un monde écrit en"
                        + " deflate se relit sans conversion."));
        machine.add(Dial.serverFlag("Cache du profileur", Config.PROFILER,
                "Profiler.get() consulte une variable de fil à chaque déplacement d'entité.",
                "6 000 TNT : ×1,10 pour ce module seul."));
        machine.add(Dial.serverFlag("Rationnement", Config.RATIONING,
                "Réagir pendant le tick, et non au suivant.",
                "Un serveur qui attend le tick d'après pour se défendre a déjà perdu celui-ci."));
        machine.add(Dial.serverFlag("Digue", Config.DIGUE,
                "Le refus des chargements de chunk synchrones. Le fil du serveur cesse de ticker"
                        + " pendant qu'un chunk se lit ou se génère : deux à quatre ticks perdus"
                        + " d'un coup, et sur un cœur unique aucun autre cœur ne l'absorbe.",
                "Seul module qui RETIRE une information au jeu plutôt que de la calculer moins cher :"
                        + " une carte laisse une case blanche, un villageois ne se couche pas."));
        machine.add(Dial.serverFlag("Sommaire des zip", Config.SOMMAIRE,
                "listResources balaie le zip ENTIER à chaque appel, et un rechargement de datapacks"
                        + " l'appelle une fois par registre — quarante-sept, plus une centaine pour"
                        + " les étiquettes.",
                "Sans objet si vos datapacks sont des dossiers : PathPackResources n'a pas ce"
                        + " défaut. Le gros du gain est au démarrage du CLIENT, sur les paquets de"
                        + " ressources."));
        machine.add(Dial.serverState("Filtre du journal", Settings::hush,
                "Écarte les avertissements dont l'innocuité est démontrée.",
                "Éteint par défaut : sa première version a fait taire la TOTALITÉ des messages du"
                        + " mod. Un mod d'optimisation qui casse un journal rend la panne suivante"
                        + " indéchiffrable.",
                ENV_NOTE));
        machine.add(Dial.serverState("Interrupteur général", Settings::enabled,
                "Coupe tous les modules du socle d'un coup.",
                "Sert au banc et au diagnostic : éteint, RIEN n'est dégradé, et l'on mesure deux"
                        + " fois le même monde plutôt que deux mondes différents.",
                ENV_NOTE));
        this.families.add(machine);
    }

    private void buildExtras() {
        Family extras = new Family("Ce qui s'ajoute", true,
                "l'ancre, la veille, les repères, le balai");
        extras.add(Dial.serverFlag("Fusion des orbes", Config.CLUMP,
                "Les orbes d'expérience fusionnent 40 fois plus souvent, sur 4 blocs.",
                "4 000 orbes : 63,21 ms → 14,77 ms, soit ×4,28. L'expérience totale est conservée"
                        + " exactement. Visible en jeu : moins d'orbes, ramassées plus vite."));
        extras.add(Dial.serverFlag("Ancre de chunk", Config.ANCHOR,
                "Garde chargé le chunk où elle est posée, et lui seul.",
                "Coupée, le bloc existe toujours et reste posable — il ne garde plus rien. Il n'est"
                        + " jamais retiré du registre : un bloc absent devient de l'air dans les"
                        + " mondes où il était posé."));
        extras.add(Dial.serverFlag("Lanterne de veille", Config.VIGIL,
                "Aucun monstre n'apparaît dans son chunk.",
                "Le SEUL ajout qui rend le serveur plus rapide : la tentative d'apparition est"
                        + " refusée à l'entrée, donc le tirage de position, la carte des hauteurs et"
                        + " la création de la créature n'ont pas lieu."));
        extras.add(Dial.serverFlag("Carnet de repères", Config.WAYPOINTS,
                "Un carnet, et AUCUNE téléportation.",
                "Les mods de carte offrent presque tous le saut vers un repère : plus rien ne coûte"
                        + " de distance, donc plus rien ne coûte de temps, donc le monde cesse"
                        + " d'être grand. Un repère dit OÙ ; le chemin reste à faire."));
        extras.add(Dial.serverCount("Repères par joueur", Config.WAYPOINT_QUOTA, "",
                "Combien de repères un joueur peut poser.",
                "Un carnet sans limite devient une liste de courses : on y jette tout, on n'y"
                        + " retrouve rien, et le choix de ce qui mérite un repère — la partie"
                        + " intéressante — disparaît."));
        extras.add(Dial.serverCount("Repères publics", Config.WAYPOINT_SHARED_CAP, "",
                "Plafond de repères publics pour le serveur entier.",
                "Un repère public est visible de tous sans cesser d'appartenir à celui qui l'a"
                        + " posé : lui seul peut le retirer."));
        extras.add(Dial.serverFlag("Balai", Config.BROOM,
                "Le nettoyage périodique — le seul module qui RETIRE quelque chose au jeu.",
                "C'est un aveu d'échec, pas une optimisation, et c'est pourquoi il est éteint par"
                        + " défaut. Il refuse d'effacer ce qui porte un nom, ce qui est apprivoisé,"
                        + " les villageois, et tout ce qui est à moins de 16 blocs d'un joueur."));
        extras.add(Dial.serverCount("Période du balai", Config.BROOM_PERIOD, " min",
                "Minutes entre deux passages.",
                "Les joueurs sont prévenus 10 s puis 3 s avant. Sans objet si le balai est coupé."));
        extras.add(Dial.serverFlag("Balai : objets au sol", Config.BROOM_ITEMS,
                "Effacer les objets au sol lors du passage.",
                "La fusion des objets réduit déjà le besoin d'y recourir : soixante-quatre objets"
                        + " devenus une pile n'ont plus besoin d'être effacés."));
        extras.add(Dial.serverFlag("Balai : hostiles", Config.BROOM_MOBS,
                "Effacer les créatures hostiles lors du passage.",
                "Les apprivoisées, nommées et montées sont gardées quoi qu'il arrive."));
        extras.add(Dial.serverFlag("Balai : projectiles", Config.BROOM_ARROWS,
                "Effacer les projectiles plantés lors du passage.",
                "Une flèche plantée dans un mur est une entité comme une autre, et elle y reste."));
        this.families.add(extras);
    }

    // --- Les actions -------------------------------------------------------

    /**
     * Écrit le fichier client, et pas seulement la valeur en mémoire.
     *
     * <p>{@code ConfigValue.set} ne touche qu'une table en mémoire : le fichier de NeoForge n'est
     * <b>pas</b> en écriture automatique. Sans cet appel, chaque réglage de cet écran se perdait au
     * redémarrage — un défaut d'autant plus déroutant qu'il ne se voit qu'une fois le jeu relancé,
     * c'est-à-dire trop tard pour le relier à son geste.
     */
    private static void commit() {
        Settings.applyFromClientConfig();
        ClientConfig.SPEC.save();
    }

    private static void cycleLeaves(int step) {
        ClientConfig.LeafCulling[] all = ClientConfig.LeafCulling.values();
        int from = Settings.leafCulling().ordinal();
        ClientConfig.LEAF_CULLING.set(all[Math.floorMod(from + step, all.length)]);
        commit();
    }

    private static void cycleItems(int step) {
        int next = Settings.itemCopies() + step;
        if (next < 1) {
            next = 4;
        } else if (next > 4) {
            next = 1;
        }
        ClientConfig.ITEM_COPIES.set(next);
        commit();
    }

    private static void cycleEdge(int step) {
        Upscale.cycleEdge(step);
        commit();
    }

    /** L'anticrénelage n'a que deux états : le sens du clic ne lui dit rien. */
    private static void toggleAntialias(int ignoredDirection) {
        Upscale.toggleAntialias();
        commit();
    }

    /**
     * Bascule le backend graphique demandé au prochain lancement.
     *
     * <p>Sans {@link #commit()} : ce réglage n'appartient pas au fichier du mod mais à
     * {@code options.txt}, et {@link Pivot#toggle()} l'y écrit lui-même. Appeler {@code commit}
     * ici réécrirait le fichier client pour rien.
     */
    private static void cyclePivot(int ignoredDirection) {
        Pivot.toggle();
    }

    /**
     * Ouvre {@link Palette}, en se passant elle-même comme parent — Échap y ramène ici plutôt que de
     * fermer tous les menus d'un coup. Instance et non statique : c'est {@code this} qu'il faut
     * passer, pas une classe qui ne sait pas d'où on l'a ouverte.
     */
    private void openPalette() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(new Palette(this));
        }
    }

    /** L'étiquette de la ligne « Nuanciers » : le nom de celui actif, sinon combien sont trouvés. */
    private static String nuancierLabel() {
        String actif = Upscale.nuancierActif();
        if (!actif.isBlank()) {
            return actif;
        }
        int total = Nuancier.listNames().size();
        return total == 0 ? "aucun trouvé" : total + (total > 1 ? " disponibles" : " disponible");
    }

    private static int pivotColour() {
        return switch (Pivot.state()) {
            case VULKAN -> ON;
            case VULKAN_AU_RELANCEMENT, OPENGL_AU_RELANCEMENT, VULKAN_ENTRAVE -> AMBER;
            case OPENGL -> DIM;
            case VULKAN_REFUSE, INCONNU -> FAINT;
        };
    }

    /** La houle n'a que deux états : le sens du clic ne lui dit rien, et c'est voulu. */
    private static void toggleSwell(int ignoredDirection) {
        Upscale.toggleSwell();
        commit();
    }

    /**
     * Fait tourner le préréglage d'échelle, « désactivée » comprise.
     *
     * <p>Le premier cran est {@code NATIF}, et c'est lui qui porte l'extinction : un interrupteur
     * séparé aurait obligé à deux gestes pour couper, et laissé l'écran afficher un pourcentage
     * pendant que rien ne se passe.
     */
    private static void cycleScale(int step) {
        Lens.Preset[] all = Lens.Preset.values();
        int from = Settings.lens() ? Lens.preset().ordinal() : 0;
        int next = Math.floorMod(from + step, all.length);
        ClientConfig.LENS.set(next != 0);
        ClientConfig.LENS_PRESET.set(all[next]);
        commit();
        Upscale.retune();
    }

    private static String scaleLabel() {
        if (Upscale.broken()) {
            return "en panne";
        }
        // Dit AVANT le réglage du joueur : sa valeur est intacte et reviendra d'elle-même, mais
        // afficher « Qualité 67 % » pendant qu'un pack de shaders nous tient en retrait serait un
        // mensonge — et le genre de mensonge qui fait croire que le mod ne sert à rien.
        if (Rival.stands()) {
            return Rival.describe();
        }
        if (!Settings.lens() || Lens.preset() == Lens.Preset.NATIF) {
            return "désactivée";
        }
        Lens.Preset preset = Lens.preset();
        int perSide = (int) Math.round(100d / preset.divisor());
        return switch (preset) {
            case NATIF -> "désactivée";
            case ULTRA_QUALITE -> "Ultra qual. " + perSide + " %";
            case QUALITE -> "Qualité " + perSide + " %";
            case EQUILIBRE -> "Équilibré " + perSide + " %";
            case PERFORMANCE -> "Perf. " + perSide + " %";
            case ULTRA_PERFORMANCE -> "Ultra perf. " + perSide + " %";
        };
    }

    private static String leafLabel(ClientConfig.LeafCulling mode) {
        return switch (mode) {
            case AUTO -> "Auto";
            case TOUJOURS -> "Toujours";
            case JAMAIS -> "Jamais";
        };
    }

    // --- Ce que le serveur a décidé ----------------------------------------

    /**
     * Le fichier serveur a-t-il quelque chose à dire ?
     *
     * <p>Faux depuis le menu principal — c'est de là que cet écran s'ouvre le plus souvent, puisque
     * son entrée est dans les options vidéo. Un {@code get()} y lèverait « Cannot get config value
     * before config is loaded », et l'écran de réglages serait le seul du mod à planter le jeu.
     */
    private static boolean serverSpoke() {
        return Config.SPEC.isLoaded();
    }

    private static boolean flagOf(ModConfigSpec.BooleanValue value) {
        return serverSpoke() ? value.get() : value.getDefault();
    }

    private static int countOf(ModConfigSpec.IntValue value) {
        return serverSpoke() ? value.get() : value.getDefault();
    }

    // --- Le dessin ---------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partial) {
        super.extractRenderState(graphics, mouseX, mouseY, partial);

        this.fit.open(graphics);
        // La souris arrive dans l'espace de l'ecran ; tout ce qui suit raisonne dans celui du
        // panneau. Oublier cette conversion donne le symptome le plus deroutant qui soit : les
        // boutons du haut repondent, ceux du bas ne repondent plus.
        mouseX = (int) this.fit.x(mouseX);
        mouseY = (int) this.fit.y(mouseY);

        final int x = this.panelX;
        final int y = this.panelY;
        final int right = x + WIDTH;
        final int bottom = y + HEIGHT;

        graphics.fill(0, 0, this.fit.viewWidth(), this.fit.viewHeight(), SCRIM);
        graphics.fill(x - 1, y - 1, right + 1, bottom + 1, EDGE);
        graphics.fill(x, y, right, bottom, PANEL);
        graphics.fill(x, y, x + SIDEBAR, bottom, SIDE);
        graphics.fill(x, y, right, y + 2, AMBER);

        graphics.text(this.font, "LANTERNE", x + 12, y + 13, AMBER, false);
        // L'étiquette dit à qui appartient ce qu'on regarde. C'est la seule chose qui distingue une
        // colonne où l'on peut agir d'une colonne où l'on ne peut que lire, et elle doit se voir
        // avant qu'on essaie de cliquer.
        Family family = this.families.get(this.picked);
        String tag = family.server ? "décidé par le serveur" : "à toi de régler";
        graphics.text(this.font, tag, right - 12 - this.font.width(tag), y + 13,
                family.server ? DIM : AMBER, false);

        graphics.fill(x + SIDEBAR, y + 2, x + SIDEBAR + 1, bottom, EDGE);
        graphics.fill(right - DETAIL - 1, y + HEADER, right - DETAIL, bottom - FOOTER, EDGE);
        graphics.fill(x + SIDEBAR, y + HEADER - 1, right, y + HEADER, EDGE);
        graphics.fill(x + SIDEBAR, bottom - FOOTER, right, bottom - FOOTER + 1, EDGE);

        drawFamilies(graphics, x, y, mouseX, mouseY);
        drawRows(graphics, x, y, right, mouseX, mouseY);
        drawDetail(graphics, right - DETAIL + 10, y + HEADER + 10, DETAIL - 22,
                bottom - FOOTER - 4);

        drawHint(graphics, x, right, bottom, family);
        drawGuide(graphics, right, bottom, mouseX, mouseY);

        this.fit.close(graphics);
    }

    // --- Le guide ----------------------------------------------------------
    //
    // Trois méthodes courtes, et pas une de plus : cet écran est celui des réglages, le guide n'y est
    // qu'une porte. Elle est posée au pied de la colonne de droite parce que c'est le dernier endroit
    // où le regard passe — on y arrive quand on a fini de régler, c'est-à-dire quand on se demande à
    // quoi tout cela sert.

    /** L'étiquette du guide, et sa largeur : la même formule des deux côtés, donc une seule ici. */
    private int guideLeft(int right) {
        return right - 12 - this.font.width(
                net.minecraft.network.chat.Component.translatable("lanterne.guide.bouton").getString());
    }

    private void drawGuide(GuiGraphicsExtractor graphics, int right, int bottom,
            int mouseX, int mouseY) {
        String label = net.minecraft.network.chat.Component
                .translatable("lanterne.guide.bouton").getString();
        int left = guideLeft(right);
        boolean over = inside(mouseX, mouseY, left - 4, bottom - FOOTER + 4, right - 8, bottom - 4);
        // Éteint hors partie, et pas seulement inactif : le guide met en scène de vrais objets, et en
        // 26.2 un ItemStack ne peut pas être construit tant qu'aucun monde n'a lié les composants.
        // Cet écran s'ouvre aussi depuis le menu principal, d'où ce gris — voir Guide.all().
        boolean ready = fr.clubcitrouille.lanterne.client.ponder.Guide.ready();
        graphics.text(this.font, label, left, bottom - FOOTER + 8,
                !ready ? FAINT : over ? AMBER : DIM, false);
    }

    /**
     * La ligne d'aide, et la seule chose qu'elle doit à son voisin de droite.
     *
     * <p>Elle partage la bande du bas avec l'étiquette du guide. Sa largeur se calcule donc depuis
     * {@link #guideLeft(int)} et non depuis le bord du panneau : le libellé du guide est
     * <b>traduit</b>, il fait soixante-seize points en français et quatre-vingt-quatorze en anglais,
     * et une bande dimensionnée sur le premier laisserait les deux se chevaucher dans le second.
     */
    private void drawHint(GuiGraphicsExtractor graphics, int x, int right, int bottom,
            Family family) {
        // Six secondes : assez pour être lu, assez court pour que la ligne d'aide revienne d'elle-
        // même sans qu'on se demande comment la récupérer.
        boolean fresh = !this.flash.isEmpty() && System.currentTimeMillis() - this.flashAt < 6000L;
        String hint = fresh ? this.flash
                : family.server
                        ? "lecture seule   ·   molette   ·   Échap pour fermer"
                        : "clic gauche / droit   ·   Maj : grand pas   ·   Échap";
        int left = x + SIDEBAR + 12;
        graphics.text(this.font,
                this.font.plainSubstrByWidth(hint, Math.max(8, guideLeft(right) - 10 - left)),
                left, bottom - FOOTER + 8, fresh ? AMBER : FAINT, false);
    }

    private void drawFamilies(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
        for (int i = 0; i < this.families.size(); i++) {
            int top = y + HEADER + 4 + i * TAB;
            boolean over = inside(mouseX, mouseY, x + 4, top, x + SIDEBAR - 5, top + TAB - 4);
            if (i == this.picked) {
                graphics.fill(x + 4, top, x + SIDEBAR - 5, top + TAB - 4, PICKED);
                graphics.fill(x + 4, top, x + 6, top + TAB - 4, AMBER);
            } else if (over) {
                graphics.fill(x + 4, top, x + SIDEBAR - 5, top + TAB - 4, HOVER);
            }
            Family family = this.families.get(i);
            graphics.text(this.font,
                    this.font.plainSubstrByWidth(family.name, SIDEBAR - 24), x + 14, top + 3,
                    i == this.picked ? AMBER : over ? TEXT : DIM, false);
            // « serveur » plutôt qu'un compte : ce qu'il faut savoir avant de cliquer sur un onglet
            // n'est pas combien de lignes il porte, c'est si l'on pourra y toucher.
            graphics.text(this.font,
                    family.server ? family.dials.size() + " · serveur"
                            : family.dials.size() + " réglages",
                    x + 14, top + 14, FAINT, false);
        }
    }

    private void drawRows(GuiGraphicsExtractor graphics, int x, int y, int right,
            int mouseX, int mouseY) {
        List<Dial> rows = this.families.get(this.picked).dials;
        int listLeft = x + SIDEBAR + 10;
        int listRight = right - DETAIL - 12;
        this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, rows.size() - VISIBLE)));
        this.hovered = -1;
        for (int seat = 0; seat < VISIBLE && this.scroll + seat < rows.size(); seat++) {
            int index = this.scroll + seat;
            int top = y + HEADER + 8 + seat * ROW;
            if (inside(mouseX, mouseY, listLeft, top, listRight, top + ROW - 2)) {
                this.hovered = index;
                graphics.fill(listLeft, top, listRight, top + ROW - 2, HOVER);
            }
            rows.get(index).draw(graphics, this.font, listLeft + 8, top + 7, listRight - 8);
        }
        if (rows.size() > VISIBLE) {
            drawScrollbar(graphics, listRight + 4, y + HEADER + 8, rows.size());
        }
    }

    /** Le curseur de défilement : dire qu'il reste des lignes, et où l'on en est. */
    private void drawScrollbar(GuiGraphicsExtractor graphics, int x, int top, int count) {
        int height = VISIBLE * ROW - 2;
        graphics.fill(x, top, x + 2, top + height, RAIL);
        int thumb = Math.max(12, height * VISIBLE / count);
        int offset = (height - thumb) * this.scroll / (count - VISIBLE);
        graphics.fill(x, top + offset, x + 2, top + offset + thumb, AMBER);
    }

    /**
     * Le panneau de droite, borné par le bas.
     *
     * <p>La borne n'est pas une précaution : les explications les plus longues — le moteur de
     * redstone, le capteur de joueurs — font une vingtaine de lignes dans une colonne qui en tient
     * vingt-deux, et la moindre phrase ajoutée les ferait couler <b>sous</b> le trait du bas, sur la
     * ligne d'aide. Un texte qui déborde d'un panneau est plus laid qu'un texte coupé, et coupé, on
     * voit au moins qu'il l'est.
     */
    private void drawDetail(GuiGraphicsExtractor graphics, int x, int y, int width, int limit) {
        Family family = this.families.get(this.picked);
        if (this.hovered < 0 || this.hovered >= family.dials.size()) {
            graphics.text(this.font, this.font.plainSubstrByWidth(family.name, width), x, y,
                    AMBER, false);
            int line = y + 14;
            line = paragraph(graphics, family.subtitle, x, line, width, limit, DIM) + 8;
            if (family.server && !serverSpoke()) {
                // Le cas du menu principal, et il faut le nommer : sans cette ligne, l'écran
                // affirmerait l'état d'un serveur qu'il n'a pas encore vu.
                line = paragraph(graphics, "Aucun monde chargé : ce sont les valeurs par défaut du"
                        + " mod qui s'affichent, pas celles d'un serveur.",
                        x, line, width, limit, FAINT) + 6;
            }
            if (line + 10 <= limit) {
                graphics.text(this.font, "Survole un réglage.", x, line, FAINT, false);
            }
            return;
        }
        Dial dial = family.dials.get(this.hovered);
        graphics.text(this.font, this.font.plainSubstrByWidth(dial.name, width), x, y, AMBER, false);
        int line = y + 14;
        if (!dial.editable) {
            graphics.text(this.font, family.server ? "lecture seule" : "non disponible",
                    x, line, FAINT, false);
            line += 12;
        } else {
            line += 2;
        }
        int[] colours = {TEXT, DIM, FAINT};
        for (int i = 0; i < dial.notes.length; i++) {
            if (dial.notes[i].isEmpty()) {
                continue;
            }
            line = paragraph(graphics, dial.notes[i], x, line, width, limit,
                    colours[Math.min(i, 2)]) + 4;
        }
    }

    /** Écrit un paragraphe et rend l'ordonnée suivante. S'arrête au trait du bas. */
    private int paragraph(GuiGraphicsExtractor graphics, String text, int x, int top, int width,
            int limit, int colour) {
        int line = top;
        for (String part : wrap(text, width)) {
            if (line + 10 > limit) {
                graphics.text(this.font, "…", x, line, FAINT, false);
                return line + 10;
            }
            graphics.text(this.font, part, x, line, colour, false);
            line += 10;
        }
        return line;
    }

    /** Coupe un paragraphe en lignes qui tiennent dans la colonne de détail. */
    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (this.font.width(candidate) > width && current.length() > 0) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static boolean inside(int px, int py, int left, int top, int right, int bottom) {
        return px >= left && px <= right && py >= top && py <= bottom;
    }

    private void say(String text) {
        this.flash = text;
        this.flashAt = System.currentTimeMillis();
    }

    // --- Les entrées -------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        int button = event.button();
        int mouseX = (int) this.fit.x(event.x());
        int mouseY = (int) this.fit.y(event.y());

        for (int i = 0; i < this.families.size(); i++) {
            int top = this.panelY + HEADER + 4 + i * TAB;
            if (inside(mouseX, mouseY, this.panelX + 4, top,
                    this.panelX + SIDEBAR - 5, top + TAB - 4)) {
                if (i != this.picked) {
                    this.picked = i;
                    // Le défilement appartient à la famille qu'on quitte : le garder ferait ouvrir
                    // la suivante au milieu de sa liste, sans qu'on comprenne pourquoi.
                    this.scroll = 0;
                    this.flash = "";
                }
                this.hovered = -1;
                return true;
            }
        }
        if (this.hovered >= 0 && (button == 0 || button == 1)) {
            Dial dial = this.families.get(this.picked).dials.get(this.hovered);
            if (!dial.editable) {
                say(this.families.get(this.picked).server
                        ? "Réglage du serveur : config/lanterne-server.toml."
                        : "Non disponible sur cette machine.");
                return true;
            }
            dial.click(button == 0 ? 1 : -1, shiftHeld());
            this.flash = "";
            return true;
        }
        if (button == 0 && inside(mouseX, mouseY, guideLeft(this.panelX + WIDTH) - 4,
                this.panelY + HEIGHT - FOOTER + 4,
                this.panelX + WIDTH - 8, this.panelY + HEIGHT - 4)) {
            if (fr.clubcitrouille.lanterne.client.ponder.Guide.ready()) {
                fr.clubcitrouille.lanterne.client.ponder.Theatre.open(this);
            } else {
                say(net.minecraft.network.chat.Component
                        .translatable("lanterne.guide.hors_jeu").getString());
            }
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int rows = this.families.get(this.picked).dials.size();
        if (rows > VISIBLE && scrollY != 0d) {
            this.scroll = Math.max(0, Math.min(rows - VISIBLE,
                    this.scroll - (int) Math.signum(scrollY)));
            return true;
        }
        return super.mouseScrolled(this.fit.x(mouseX), this.fit.y(mouseY), scrollX, scrollY);
    }

    /**
     * La touche Maj est-elle enfoncée ?
     *
     * <p>{@code Screen.hasShiftDown()} n'existe plus en 26.1 : les modificateurs voyagent désormais
     * dans l'évènement d'entrée. On interroge donc la fenêtre, comme le fait aussi le comptoir de la
     * boutique là où il a le même besoin.
     */
    private boolean shiftHeld() {
        if (this.minecraft == null) {
            return false;
        }
        return InputConstants.isKeyDown(SDLScancode.SDL_SCANCODE_LSHIFT)
                || InputConstants.isKeyDown(SDLScancode.SDL_SCANCODE_RSHIFT);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * À la fermeture, on reconstruit la géométrie.
     *
     * <p>Les coffres et les feuilles décident de la <em>manière de mailler</em> un chunk ; leur
     * changement ne se voit qu'après reconstruction. Sans cet appel, un joueur basculerait l'option,
     * ne verrait rien, et conclurait qu'elle est cassée.
     */
    @Override
    public void onClose() {
        if (this.minecraft != null) {
            if (this.minecraft.levelRenderer != null && this.minecraft.level != null) {
                this.minecraft.levelExtractor.allChanged();
            }
            fr.clubcitrouille.lanterne.core.Shroud.forget();
            this.minecraft.gui.setScreen(this.parent);
        }
    }

    /** Une famille de réglages — une entrée de la barre de gauche. */
    private static final class Family {
        private final String name;
        private final boolean server;
        private final String subtitle;
        private final List<Dial> dials = new ArrayList<>();

        Family(String name, boolean server, String subtitle) {
            this.name = name;
            this.server = server;
            this.subtitle = subtitle;
        }

        void add(Dial dial) {
            this.dials.add(dial);
        }
    }

    /**
     * Une ligne de réglage.
     *
     * <p>Deux formes seulement, et ce n'est pas une économie de code : c'est ce qui permet de lire
     * une colonne entière d'un coup d'œil. Un <b>interrupteur</b>, dont l'état se voit à la
     * pastille ; une <b>valeur</b>, écrite en toutes lettres.
     *
     * <p>Un réglage serveur prend la seconde forme, jamais la première : un mot — « actif » ou
     * « coupé » — se distingue d'une pastille sans qu'on ait à apprendre un code couleur, et dit
     * donc de loin ce qui se clique de ce qui se lit seulement.
     */
    private static final class Dial {
        private final String name;
        private final boolean binary;
        private final boolean editable;
        private final BooleanSupplier state;
        private final ModConfigSpec.BooleanValue backing;
        private final Supplier<String> value;
        private final IntSupplier colour;
        private final IntConsumer step;

        /**
         * Ce que la touche Maj multiplie.
         *
         * <p>Un pour un sélecteur : il n'a que trois ou six crans, et les sauter cinq par cinq ne
         * ferait pas gagner de temps — cela ferait seulement atterrir ailleurs qu'où l'on croyait.
         * Cinq pour un nombre, dont l'intervalle va parfois jusqu'à mille.
         */
        private final int leap;
        private final String[] notes;

        private Dial(String name, boolean binary, boolean editable, BooleanSupplier state,
                ModConfigSpec.BooleanValue backing, Supplier<String> value, IntSupplier colour,
                IntConsumer step, int leap, String[] notes) {
            this.name = name;
            this.binary = binary;
            this.editable = editable;
            this.state = state;
            this.backing = backing;
            this.value = value;
            this.colour = colour;
            this.step = step;
            this.leap = leap;
            this.notes = notes;
        }

        static Dial toggle(String name, BooleanSupplier state, ModConfigSpec.BooleanValue backing,
                String what, String cost, String when) {
            return new Dial(name, true, true, state, backing, () -> "", () -> AMBER, null, 1,
                    new String[] {what, cost, when});
        }

        /** Un sélecteur. {@code step} nul : la valeur s'affiche, et rien ne la change. */
        static Dial cycle(String name, Supplier<String> value, IntSupplier colour, IntConsumer step,
                String what, String cost, String when) {
            return new Dial(name, false, step != null, () -> false, null, value, colour, step, 1,
                    new String[] {what, cost, when});
        }

        /**
         * Un nombre borné : clic gauche pour monter, clic droit pour descendre.
         *
         * <p>Il ne boucle pas, contrairement au sélecteur. Un intervalle de mille valeurs qui
         * repasserait par zéro au cran suivant donnerait au joueur le sentiment d'avoir glissé.
         */
        static Dial number(String name, ModConfigSpec.IntValue backing, int low, int high,
                int stride, String unit, String what, String cost, String when) {
            return new Dial(name, false, true, () -> false, null,
                    () -> clientCount(backing) + unit, () -> AMBER,
                    direction -> bump(backing, low, high, stride * direction), 5,
                    new String[] {what, cost, when});
        }

        static Dial serverFlag(String name, ModConfigSpec.BooleanValue backing, String what,
                String cost) {
            return locked(name, () -> flagOf(backing) ? "actif" : "coupé",
                    () -> flagOf(backing) ? ON : FAINT, what, cost, SERVER_NOTE);
        }

        static Dial serverCount(String name, ModConfigSpec.IntValue backing, String unit,
                String what, String cost) {
            return locked(name, () -> countOf(backing) + unit, () -> DIM,
                    what, cost, SERVER_NOTE);
        }

        /** Un module du serveur qui n'a pas de ligne dans le fichier. Voir {@link Dials#ENV_NOTE}. */
        static Dial serverState(String name, BooleanSupplier state, String what, String cost,
                String note) {
            return locked(name, () -> state.getAsBoolean() ? "actif" : "coupé",
                    () -> state.getAsBoolean() ? ON : FAINT, what, cost, note);
        }

        private static Dial locked(String name, Supplier<String> value, IntSupplier colour,
                String what, String cost, String note) {
            return new Dial(name, false, false, () -> false, null, value, colour, null, 1,
                    new String[] {what, cost, note});
        }

        private static int clientCount(ModConfigSpec.IntValue backing) {
            return ClientConfig.SPEC.isLoaded() ? backing.get() : backing.getDefault();
        }

        private static void bump(ModConfigSpec.IntValue backing, int low, int high, int delta) {
            int was = backing.get();
            int now = Math.clamp((long) was + delta, low, high);
            // Butée atteinte : rien n'a changé, et écrire le fichier pour le réécrire à l'identique
            // ferait un accès disque par clic sur un bouton qui ne fait plus rien.
            if (now != was) {
                backing.set(now);
                commit();
            }
        }

        /**
         * Le fichier client est chargé bien avant le menu principal — mais {@code set} lève une
         * exception s'il ne l'est pas, et un écran de réglages n'a pas le droit d'être le seul
         * endroit du mod qui puisse arrêter le jeu.
         */
        void click(int direction, boolean big) {
            if (!ClientConfig.SPEC.isLoaded()) {
                return;
            }
            if (this.binary) {
                this.backing.set(!this.state.getAsBoolean());
                commit();
            } else if (this.step != null) {
                this.step.accept(big ? direction * this.leap : direction);
            }
        }

        void draw(GuiGraphicsExtractor graphics, Font font, int x, int y, int right) {
            if (this.binary) {
                boolean on = this.state.getAsBoolean();
                int track = right - 24;
                graphics.text(font, font.plainSubstrByWidth(this.name, track - x - 8), x, y,
                        TEXT, false);
                graphics.fill(track, y - 2, track + 24, y + 9, RAIL);
                graphics.fill(on ? track + 13 : track + 2, y,
                        on ? track + 22 : track + 11, y + 7, on ? ON : OFF);
                return;
            }
            String text = this.value.get();
            int width = font.width(text);
            // Le nom cède avant la valeur : c'est la valeur qu'on est venu lire, et un nombre
            // tronqué ne dit pas seulement moins — il dit faux.
            graphics.text(font, font.plainSubstrByWidth(this.name, Math.max(8, right - width - 8 - x)),
                    x, y, this.editable ? TEXT : DIM, false);
            graphics.text(font, text, right - width, y, this.colour.getAsInt(), false);
        }
    }
}
