package fr.clubcitrouille.lanterne.chantier;

import java.nio.file.Path;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le chantier : une API de construction pour un bâtisseur qui n'est pas en jeu.
 *
 * <h2>Pourquoi ce n'est pas du RCON</h2>
 *
 * <p>Bâtir par RCON, c'est envoyer un {@code setblock} par bloc : des dizaines de milliers d'allers-
 * retours, aucun moyen de revenir en arrière, et pour relire le monde il fallait télécharger les
 * {@code .mca} et les décoder à la main. Ce paquet ouvre à la place une petite porte HTTP/JSON
 * ({@link Porte}) sur le thread serveur : lecture d'une boîte en une requête, écriture en masse,
 * clonage avec miroir et rotation, annulation exacte, clichés comparables, un rendu PNG (plan, et 3D
 * texturé : {@link Rendu3d}) pour
 * vérifier sans client. Le compagnon naturel est le serveur MCP de {@code outils/mcp-construction}.
 *
 * <h2>Ce qu'il ne fera jamais</h2>
 *
 * <ul>
 *   <li><b>Écouter ailleurs que sur 127.0.0.1.</b> On y accède par un tunnel SSH ; une API qui peut
 *       tout réécrire n'a rien à faire sur une interface publique, jeton ou pas.</li>
 *   <li><b>S'ouvrir sans jeton.</b> Réglage vide = porte fermée, et le journal le dit.</li>
 *   <li><b>Toucher au monde hors du thread serveur.</b> Chaque requête devient un travail en file
 *       ({@link Atelier}), drainé à chaque tick sous un budget en millisecondes : un serveur mono-cœur
 *       ne doit pas voir la différence entre un chantier de 50 000 blocs et un joueur qui mine.</li>
 * </ul>
 *
 * <p>Fichier de réglages à part ({@code lanterne-chantier.toml}) — même raison que le projecteur :
 * la décision d'ouvrir une porte d'écriture n'appartient pas aux mêmes réglages que le reste.
 */
public final class Chantier {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ACTIF;
    public static final ModConfigSpec.ConfigValue<String> JETON;
    public static final ModConfigSpec.IntValue PORT;
    public static final ModConfigSpec.IntValue BUDGET_MS;
    public static final ModConfigSpec.IntValue BLOCS_MAX;
    public static final ModConfigSpec.IntValue RAYON_JOUEURS;
    public static final ModConfigSpec.IntValue CORPS_MAX_MO;
    public static final ModConfigSpec.ConfigValue<String> RENDU_JAR_CLIENT;
    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment(
                "Lanterne - l'API de construction (chantier).",
                "",
                "Une porte HTTP/JSON sur 127.0.0.1 UNIQUEMENT, pour construire et relire le monde depuis",
                "un outil (serveur MCP outils/mcp-construction) via un tunnel SSH. Eteinte par defaut.",
                "Elle ne s'ouvre que si api_construction = true ET un jeton d'au moins 16 caracteres.");

        ACTIF = BUILDER.comment("Ouvrir l'API de construction ?")
                .define("api_construction", false);
        JETON = BUILDER.comment(
                "Le jeton secret exige a chaque requete (en-tete \"Authorization: Bearer <jeton>\").",
                "16 caracteres minimum ; vide = API fermee.")
                .define("jeton", "");
        PORT = BUILDER.comment("Port d'ecoute, sur 127.0.0.1 seulement.")
                .defineInRange("port", 25590, 1024, 65535);
        BUDGET_MS = BUILDER.comment(
                "Millisecondes par tick que le chantier peut prendre au serveur (sur 50).",
                "Divise par quatre tant que le tick moyen depasse 40 ms.")
                .defineInRange("budget_ms", 10, 1, 40);
        BLOCS_MAX = BUILDER.comment("Blocs au plus par operation (lecture, ecriture, rendu).")
                .defineInRange("blocs_max", 4_000_000, 1_000, 64_000_000);
        RAYON_JOUEURS = BUILDER.comment(
                "Refuser une ecriture si un joueur est a moins de ce nombre de blocs de la boite.",
                "0 = jamais refuser (une requete peut toujours le demander elle-meme).")
                .defineInRange("rayon_joueurs", 0, 0, 4096);
        CORPS_MAX_MO = BUILDER.comment("Taille maximale d'une requete, en Mo.")
                .defineInRange("corps_max_mo", 64, 1, 512);
        RENDU_JAR_CLIENT = BUILDER.comment(
                "Jar client Minecraft d'ou le rendu 3D (/rendu3d) tire modeles et textures de blocs.",
                "Vide = celui que BlueMap a telecharge (bluemap/minecraft-client-<version>.jar, dossier",
                "\"data\" de config/bluemap/core.conf). Relatif au dossier du serveur. Absent = couleurs de carte.")
                .define("rendu_jar_client", "");
        SPEC = BUILDER.build();
    }

    private static volatile MinecraftServer serveur;

    private Chantier() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(Chantier.class);
    }

    /**
     * Appelé par {@code PregenActivityMixin} à chaque tick : la pause automatique de vanilla (serveur
     * vide depuis {@code pause-when-empty-seconds}) coupe le tick AVANT les évènements, donc avant
     * l'{@link Atelier}. Sans ceci, une requête envoyée à un serveur sans joueur — le cas normal —
     * attendait indéfiniment. Constaté au premier essai, pas supposé. On ne fait croire à un joueur
     * que tant qu'un travail est en file ; un serveur vide sans chantier se remet en veille.
     */
    public static int garderEveille(int joueurs) {
        return joueurs == 0 && Atelier.enFile() > 0 ? 1 : joueurs;
    }

    static MinecraftServer serveur() {
        return serveur;
    }

    /** Racine des fichiers du chantier (journaux, clichés), dans le dossier du monde. */
    static Path racine() {
        return serveur.getWorldPath(LevelResource.ROOT).resolve("lanterne-chantier");
    }

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        serveur = event.getServer();
        if (!SPEC.isLoaded() || !ACTIF.get()) {
            return;
        }
        if (!event.getServer().isDedicatedServer()) {
            Lanterne.LOG.info("[CHANTIER] Monde solo : l'API de construction reste fermée (serveur dédié uniquement).");
            return;
        }
        String jeton = JETON.get();
        if (jeton == null || jeton.length() < 16) {
            Lanterne.LOG.warn("[CHANTIER] api_construction = true mais le jeton fait moins de 16 caractères : "
                    + "porte FERMÉE. Renseigne « jeton » dans config/lanterne-chantier.toml.");
            return;
        }
        Porte.ouvrir(PORT.get());
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        Porte.fermer();
        Atelier.abandonnerTout("serveur en cours d'arrêt");
        serveur = null;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        Atelier.tick(event.getServer());
    }
}
