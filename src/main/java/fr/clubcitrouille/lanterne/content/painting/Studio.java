package fr.clubcitrouille.lanterne.content.painting;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * L'atelier : les réglages des deux ajouts de confort — les Tableaux et les Disques.
 *
 * <h2>Pourquoi un seul fichier pour deux fonctionnalités</h2>
 *
 * <p>Les tableaux et les disques n'ont rien de commun techniquement : l'un dessine, l'autre joue du
 * son. Ils partagent pourtant exactement la même nature : ce sont deux <b>importateurs de fichiers
 * du joueur</b>. Les mêmes questions se posent aux deux — quel poids maximum accepter, combien par
 * personne, à quel débit transmettre — et les réponses se règlent ensemble ou pas du tout. Deux
 * fichiers de six lignes chacun auraient obligé à ouvrir les deux pour comprendre une seule
 * décision.
 *
 * <p>Le fichier s'appelle {@code config/lanterne-atelier.toml} et il est de type
 * {@link ModConfig.Type#COMMON} : les limites d'import engagent le serveur (qui les fait respecter)
 * autant que le client (qui prépare les fichiers). Un réglage purement visuel — la taille de la
 * mosaïque, le nombre de niveaux de mipmap — y figure aussi, parce qu'un serveur dédié le charge
 * sans jamais le lire, et que cela coûte moins qu'un troisième fichier.
 *
 * <h2>Les valeurs sont recopiées, pas relues</h2>
 *
 * <p>{@code ModConfigSpec.IntValue.get()} lève une exception tant que le fichier n'est pas chargé,
 * et coûte une consultation de table à chaque appel. Les chemins chauds — le remplissage de la
 * mosaïque, le découpage des segments — lisent donc des champs {@code static} ordinaires, recopiés
 * une fois par {@link #apply}. Tant que le chargement n'a pas eu lieu, ce sont les valeurs par
 * défaut écrites ici qui servent, et elles sont les mêmes que celles déclarées au constructeur de
 * chaque réglage : il n'y a pas deux vérités.
 */
public final class Studio {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // --- Tableaux ---------------------------------------------------------

    public static final ModConfigSpec.IntValue PAINTING_MAX_SIDE;
    public static final ModConfigSpec.IntValue PAINTING_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue PAINTING_MAX_SOURCE_KIB;
    public static final ModConfigSpec.IntValue PAINTING_QUOTA;
    public static final ModConfigSpec.IntValue MOSAIC_SIDE;
    public static final ModConfigSpec.IntValue MOSAIC_MIPS;
    public static final ModConfigSpec.IntValue SHARD_BYTES;
    public static final ModConfigSpec.IntValue SHARDS_PER_TICK;
    public static final ModConfigSpec.BooleanValue LINKS;
    public static final ModConfigSpec.IntValue LINK_TIMEOUT;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> DOMAINS;
    public static final ModConfigSpec.BooleanValue FREE_EDIT;

    // --- Disques ----------------------------------------------------------

    public static final ModConfigSpec.IntValue DISC_MAX_MIB;
    public static final ModConfigSpec.IntValue DISC_MAX_SECONDS;
    public static final ModConfigSpec.IntValue DISC_QUOTA;
    public static final ModConfigSpec.BooleanValue DISC_CONVERT;
    public static final ModConfigSpec.IntValue DISC_SLOTS;
    public static final ModConfigSpec.IntValue DISC_BURN;

    public static final ModConfigSpec SPEC;

    /** Côté maximal, en pixels, d'une image après ré-échantillonnage. */
    private static int paintingMaxSide = 1024;

    /** Côté maximal, en blocs, d'un tableau posé. */
    private static int paintingMaxBlocks = 8;

    /** Poids maximal, en kibioctets, du fichier source accepté. */
    private static int paintingMaxSourceKib = 8192;

    /** Nombre maximal d'images importées. */
    private static int paintingQuota = 64;

    /** Côté de la mosaïque, en pixels. */
    private static int mosaicSide = 4096;

    /** Niveaux de mipmap de la mosaïque, en plus du niveau zéro. */
    private static int mosaicMips = 4;

    /** Taille utile d'un segment d'image, en octets. */
    private static int shardBytes = 24576;

    /** Segments envoyés par tick et par joueur. */
    private static int shardsPerTick = 4;

    /** Le joueur peut-il charger une image depuis une adresse ? */
    private static boolean links = true;

    /** Délai d'attente d'un téléchargement, en secondes. */
    private static int linkTimeout = 20;

    /** Domaines autorisés. Vide : tous. Voir {@link #domains()}. */
    private static java.util.List<String> domains = java.util.List.of();

    /** N'importe qui peut-il modifier n'importe quelle toile ? */
    private static boolean freeEdit = true;

    /** Poids maximal, en mébioctets, d'un fichier audio accepté. */
    private static int discMaxMib = 24;

    /** Durée maximale, en secondes, d'un disque. */
    private static int discMaxSeconds = 900;

    /** Nombre maximal de disques importés. */
    private static int discQuota = 64;

    /** Tenter la conversion des formats non-OGG par ffmpeg, s'il est installé. */
    private static boolean discConvert = true;

    /** Nombre de sillons gravables. Voir {@code content.disc.Slots}. */
    private static int discSlots = 64;

    /** Durée d'une gravure, en secondes. */
    private static int discBurn = 6;

    static {
        BUILDER.comment(
                "Lanterne - l'atelier : tes propres tableaux, tes propres disques.",
                "",
                "Deux dossiers a remplir, tous deux crees au premier demarrage :",
                "  config/lanterne/tableaux/   images PNG ou JPEG",
                "  config/lanterne/disques/    fichiers audio OGG (ou autres, voir 'convertir')",
                "",
                "Les commandes /tableau et /disque listent ce qui a ete reconnu et donnent les",
                "objets correspondants.").push("tableaux");

        PAINTING_MAX_SIDE = BUILDER.comment(
                "Cote maximal, en PIXELS, d'une image apres import.",
                "L'image est reduite UNE FOIS, a l'import, et jamais ensuite : le rendu ne",
                "re-echantillonne rien. Au-dela de 1024 le gain visuel est nul a distance de jeu et",
                "la mosaique se remplit vite - un tableau de 2048 occupe quatre fois la surface d'un",
                "tableau de 1024.")
                .defineInRange("pixels_max", 1024, 64, 4096);
        PAINTING_MAX_BLOCKS = BUILDER.comment(
                "Cote maximal, en BLOCS, d'un tableau pose.",
                "Un tableau de 8x8 couvre deja un mur entier. Au-dela, la boite de collision devient",
                "difficile a viser et la verification de support coute cher a chaque tentative.")
                .defineInRange("blocs_max", 8, 1, 16);
        PAINTING_MAX_SOURCE_KIB = BUILDER.comment(
                "Poids maximal, en kibioctets, du FICHIER SOURCE accepte.",
                "Un refus au-dela est propre : le fichier est ignore, nomme dans le journal, et",
                "l'import continue avec les autres. Rien n'est jamais decode avant ce test - une",
                "image de 200 Mio ne doit pas pouvoir faire tomber le serveur par sa seule presence",
                "dans le dossier.")
                .defineInRange("source_max_kio", 8192, 64, 65536);
        PAINTING_QUOTA = BUILDER.comment(
                "Nombre maximal d'images importees.",
                "Chaque image occupe une case de la mosaique ; au-dela du quota la mosaique deborde",
                "et les images en trop ne seraient pas affichables. Le refus est annonce plutot que",
                "subi.")
                .defineInRange("quota", 64, 1, 1024);
        MOSAIC_SIDE = BUILDER.comment(
                "Cote de la MOSAIQUE, en pixels - l'atlas unique ou toutes les images sont rangees.",
                "C'est le coeur de l'optimisation : une seule texture pour tous les tableaux du",
                "monde, donc un seul changement de texture par image rendue, donc un seul lot de",
                "dessin quel que soit le nombre de tableaux visibles.",
                "4096 donne 16 cases de 1024, ou 64 cases de 512, et coute 64 Mio de memoire video",
                "niveau zero compris. Reduis si ta carte est petite.",
                "RECHARGEMENT : effet au prochain chargement de monde.")
                .defineInRange("mosaique_cote", 4096, 1024, 8192);
        MOSAIC_MIPS = BUILDER.comment(
                "Niveaux de reduction (mipmaps) de la mosaique, en plus du niveau zero.",
                "Sans eux, un tableau vu de loin scintille : l'ecran echantillonne un pixel sur huit",
                "et le motif saute d'une image a l'autre. Avec quatre niveaux, un tableau a trente",
                "blocs est lisse et stable.",
                "Chaque niveau ajoute un quart de la memoire du precedent ; quatre niveaux coutent",
                "donc environ 33% de plus que zero.",
                "RECHARGEMENT : effet au prochain chargement de monde.")
                .defineInRange("mosaique_mipmaps", 4, 0, 6);
        SHARD_BYTES = BUILDER.comment(
                "Taille utile d'un segment d'image, en octets.",
                "Une image ne tient pas dans un paquet : elle est decoupee. 24576 laisse de la marge",
                "sous la limite de paquet de Minecraft une fois l'entete ajoute.")
                .defineInRange("segment_octets", 24576, 4096, 32768);
        SHARDS_PER_TICK = BUILDER.comment(
                "Segments envoyes par tick et par joueur.",
                "4 segments a 24 Kio font environ 2 Mio par seconde : une image de 1 Mio arrive en",
                "une demi-seconde, et la bande passante restante suffit au jeu. Monter ce nombre",
                "accelere l'arrivee des tableaux au prix de saccades pour les joueurs a faible debit.",
                "Et surtout : une image n'est envoyee QU'UNE FOIS. Le client la garde sur son disque",
                "et ne la redemandera jamais - il compare les empreintes, pas les noms.")
                .defineInRange("segments_par_tick", 4, 1, 32);
        LINKS = BUILDER.comment(
                "Autoriser le chargement d'une image depuis une ADRESSE collee dans l'ecran",
                "d'edition.",
                "",
                "C'est le CLIENT qui telecharge, jamais le serveur. La difference n'est pas de",
                "commodite : si le serveur allait chercher une adresse fournie par un joueur,",
                "n'importe qui pourrait lui faire interroger son reseau interne - une base de",
                "donnees, un panneau d'administration, un service qui ne repond qu'en local. C'est",
                "la faille dite SSRF, et elle se referme en ne telechargeant jamais cote serveur.",
                "",
                "Le serveur ne voit donc que des PIXELS deja reduits, et il les borne comme si le",
                "joueur les avait deposes a la main.")
                .define("liens", true);
        LINK_TIMEOUT = BUILDER.comment(
                "Delai d'attente d'un telechargement, en secondes. Au-dela, abandon annonce.")
                .defineInRange("lien_delai_s", 20, 3, 120);
        DOMAINS = BUILDER.comment(
                "Domaines autorises pour les liens. Liste vide : tous.",
                "Exemple : [\"i.imgur.com\", \"cdn.discordapp.com\"]",
                "",
                "HONNETEMENT : cette liste est appliquee par le CLIENT, puisque c'est lui qui",
                "telecharge. Un client modifie peut l'ignorer. Ce qu'elle empeche vraiment, c'est",
                "qu'un joueur ordinaire colle une adresse imprevue - pas qu'un joueur determine",
                "contourne la regle. Ce que le serveur fait RESPECTER, ce sont les limites de",
                "taille, de quota et de debit, qu'il verifie sur les octets recus.")
                .defineList("lien_domaines", java.util.List.of(),
                        () -> "", entry -> entry instanceof String);
        FREE_EDIT = BUILDER.comment(
                "N'importe qui peut modifier n'importe quelle toile.",
                "Vrai par defaut, par coherence avec vanilla : n'importe qui peut deja casser un",
                "tableau. A faux, seuls celui qui a pose la toile et les operateurs peuvent la",
                "changer.")
                .define("edition_libre", true);
        BUILDER.pop();

        BUILDER.comment(
                "Les disques personnalises. Depose des fichiers dans config/lanterne/disques/.").push("disques");

        DISC_MAX_MIB = BUILDER.comment(
                "Poids maximal, en mebioctets, d'un fichier audio accepte.",
                "24 Mio en Vorbis correspondent a environ une heure de musique de qualite correcte.",
                "Le fichier n'est JAMAIS charge entier en memoire : il est lu en flux depuis le",
                "disque pendant la lecture. Cette limite protege le dossier, pas la memoire.")
                .defineInRange("poids_max_mio", 24, 1, 256);
        DISC_MAX_SECONDS = BUILDER.comment(
                "Duree maximale, en secondes, d'un disque.",
                "Le jukebox de Minecraft s'arrete de lui-meme a la fin annoncee du morceau ; une",
                "duree fausse ou absurde laisserait le disque tourner dans le vide.")
                .defineInRange("duree_max_s", 900, 10, 7200);
        DISC_QUOTA = BUILDER.comment(
                "Nombre maximal de disques importes.")
                .defineInRange("quota", 64, 1, 512);
        DISC_CONVERT = BUILDER.comment(
                "Tenter de convertir les fichiers non-OGG (mp3, wav, flac...) vers OGG/Vorbis.",
                "",
                "POURQUOI OGG/VORBIS ET RIEN D'AUTRE : le moteur sonore de Minecraft ne sait lire",
                "que cela. Il n'y a pas de decodeur mp3 dans le jeu, et en ajouter un supposerait de",
                "doubler tout le chemin de lecture - pour un resultat plus lourd, moins teste, et",
                "incompatible avec le mode flux qui evite justement de charger le morceau entier.",
                "",
                "La conversion est deleguee a ffmpeg s'il est installe et joignable depuis le PATH.",
                "Lanterne n'embarque PAS d'encodeur Vorbis : il n'en existe pas en Java pur qui",
                "vaille la peine d'etre distribue, et en embarquer un natif pour trois plateformes",
                "alourdirait le mod de plusieurs mebioctets pour une commodite.",
                "Sans ffmpeg, les fichiers non-OGG sont ignores avec un message clair disant quoi",
                "faire - ce qui vaut mieux qu'un silence.")
                .define("convertir", true);
        DISC_SLOTS = BUILDER.comment(
                "Nombre de SILLONS gravables en cours de partie.",
                "",
                "Un disque grave au graveur ne peut pas creer une entree de registre : les registres",
                "de donnees sont scelles au chargement du monde, et en forcer un rechargement",
                "PERIMERAIT tous les disques deja dans les inventaires - leur composant serait perdu",
                "a la sauvegarde. Le mod reserve donc a l'avance un jeu de sillons vides, et une",
                "gravure en occupe un.",
                "",
                "Chaque sillon a une duree maximale differente, repartie geometriquement : la gravure",
                "prend le plus petit sillon libre assez long. Monter ce nombre coute quelques",
                "kilo-octets de donnees a la connexion, rien d'autre.")
                .defineInRange("sillons", 64, 8, 256);
        DISC_BURN = BUILDER.comment(
                "Duree d'une gravure, en secondes.",
                "Le graveur tourne, fume et grince pendant ce temps. Ce n'est pas du remplissage :",
                "l'envoi du morceau au serveur prend deja quelques secondes, et une barre qui avance",
                "vaut mieux qu'un gel sans explication.")
                .defineInRange("gravure_s", 6, 1, 60);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private Studio() {}

    /**
     * Recopie les réglages dans les champs lus par les chemins chauds.
     *
     * <p>Le test sur le type et sur la nature de l'évènement reprend celui de
     * {@code core.ClientConfig} : {@code ModConfigEvent} est émis aussi pour des configurations qui
     * ne sont pas la nôtre, et y répondre écraserait nos valeurs par celles d'un autre fichier.
     */
    public static void apply(ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        if (!(event instanceof ModConfigEvent.Loading) && !(event instanceof ModConfigEvent.Reloading)) {
            return;
        }
        paintingMaxSide = PAINTING_MAX_SIDE.get();
        paintingMaxBlocks = PAINTING_MAX_BLOCKS.get();
        paintingMaxSourceKib = PAINTING_MAX_SOURCE_KIB.get();
        paintingQuota = PAINTING_QUOTA.get();
        mosaicSide = MOSAIC_SIDE.get();
        mosaicMips = MOSAIC_MIPS.get();
        shardBytes = SHARD_BYTES.get();
        shardsPerTick = SHARDS_PER_TICK.get();
        links = LINKS.get();
        linkTimeout = LINK_TIMEOUT.get();
        domains = java.util.List.copyOf(DOMAINS.get());
        freeEdit = FREE_EDIT.get();
        discMaxMib = DISC_MAX_MIB.get();
        discMaxSeconds = DISC_MAX_SECONDS.get();
        discQuota = DISC_QUOTA.get();
        discConvert = DISC_CONVERT.get();
        discSlots = DISC_SLOTS.get();
        discBurn = DISC_BURN.get();
    }

    public static int paintingMaxSide() {
        return paintingMaxSide;
    }

    public static int paintingMaxBlocks() {
        return paintingMaxBlocks;
    }

    public static long paintingMaxSourceBytes() {
        return (long) paintingMaxSourceKib * 1024L;
    }

    public static int paintingQuota() {
        return paintingQuota;
    }

    public static int mosaicSide() {
        return mosaicSide;
    }

    public static int mosaicMips() {
        return mosaicMips;
    }

    public static int shardBytes() {
        return shardBytes;
    }

    public static int shardsPerTick() {
        return shardsPerTick;
    }

    public static boolean links() {
        return links;
    }

    public static int linkTimeout() {
        return linkTimeout;
    }

    /**
     * Les domaines autorisés, ou une liste vide si tous le sont.
     *
     * <p>Voir le commentaire du réglage : cette liste est une <b>politique</b> annoncée au client,
     * et non une barrière. La barrière, ce sont les limites que le serveur vérifie sur les octets
     * qu'il reçoit — et celles-là, aucun client ne les contourne.
     */
    public static java.util.List<String> domains() {
        return domains;
    }

    public static boolean freeEdit() {
        return freeEdit;
    }

    public static long discMaxBytes() {
        return (long) discMaxMib * 1024L * 1024L;
    }

    public static int discMaxSeconds() {
        return discMaxSeconds;
    }

    public static int discQuota() {
        return discQuota;
    }

    public static boolean discConvert() {
        return discConvert;
    }

    public static int discSlots() {
        return discSlots;
    }

    public static int discBurn() {
        return discBurn;
    }
}
