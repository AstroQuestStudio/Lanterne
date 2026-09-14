package fr.clubcitrouille.lanterne.core;

import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import fr.clubcitrouille.lanterne.Lanterne;

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
