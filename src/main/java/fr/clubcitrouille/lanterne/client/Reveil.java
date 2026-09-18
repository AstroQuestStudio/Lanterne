package fr.clubcitrouille.lanterne.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.neoforged.fml.loading.FMLPaths;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le réveil : ce qui se passe la toute première fois que ce client existe, avant que vanilla
 * n'ait eu l'occasion de choisir à sa place.
 *
 * <h2>Le défaut vanilla, vérifié en bytecode réel</h2>
 *
 * <p>{@code Options.<init>} — vérifié par {@code javap} contre le vrai jar
 * {@code minecraft-client-patched-26.3.0.3-beta.jar}, jamais contre {@code .mcsrc} qui ne
 * décompile que l'ancien package {@code com.mojang.blaze3d} et ne décrit plus ce moteur — construit
 * {@code enableVsync} avec {@code OptionInstance.createBoolean("options.vsync", true, ...)}.
 * <b>VÉRIFIÉ</b> : la synchronisation verticale est activée PAR DÉFAUT dans ce moteur, exactement
 * comme dans vanilla.
 *
 * <p>Un mod de performance qui laisserait ce défaut en place livrerait la moitié de son travail :
 * la synchronisation verticale plafonne le temps d'image sur la fréquence de l'écran, et c'est
 * précisément la butée que {@link fr.clubcitrouille.lanterne.lab.Glass} retire avant de mesurer
 * quoi que ce soit — voir sa Javadoc de classe, section « Le banc mesurait l'écran, pas le mod ».
 * {@code Glass} ne touche cependant jamais qu'à {@code run/options.txt}, jamais à celui d'un
 * joueur ; cette classe-ci est la seule qui s'adresse à une vraie installation.
 *
 * <h2>Pourquoi « première fois » seulement, et comment on le sait sans jamais lire le fichier</h2>
 *
 * <p>Changer la valeur EN MÉMOIRE ne servirait à rien ici : ce code s'exécute pendant la
 * construction du mod, sur un thread de chargement FML, largement avant que
 * {@code Minecraft.<init>} ne construise {@code Options} et ne lise {@code options.txt} — le
 * journal réel le montre : la bannière « NeoForge mod loading » et la lecture de
 * {@code ClientConfig} précèdent de plusieurs lignes « Setting user » et la création de la
 * fenêtre. {@code Minecraft.getInstance()} n'existe donc pas encore à ce moment ;
 * {@code FMLPaths.GAMEDIR} si, et c'est lui qu'on utilise.
 *
 * <p>La seule question honnête à se poser est : <b>ce fichier existait-il avant que ce mod n'y
 * touche ?</b> S'il existe déjà, il porte forcément un choix — celui d'un joueur qui a lancé le
 * jeu au moins une fois, qu'il ait ou non ouvert les réglages vidéo, et quelle que soit la valeur
 * qu'on y trouverait. On n'ouvre JAMAIS ce fichier pour le lire ni le modifier dans ce cas, pas
 * même pour y corriger une seule ligne. Un joueur qui a remis la synchronisation à VRAI a le droit
 * d'être cru sur parole : une fois le fichier écrit, rien ne distingue plus « jamais touché » de
 * « remis exprès ». La seule distinction sûre est l'existence même du fichier, avant que quiconque
 * n'y écrive quoi que ce soit — c'est pourquoi ce module ne fait jamais qu'un {@code exists()}.
 *
 * <p>S'il n'existe PAS, {@code Options.load()} s'apprête à ne rien trouver et à tout construire sur
 * ses propres défauts — dont {@code enableVsync = true}. On dépose alors un fichier minimal avant
 * que ce chargement n'ait lieu : {@code Options.load()} le lira comme n'importe quel
 * {@code options.txt} écrit à la main, n'y trouvera de valeur EXPLICITE que pour les clés posées
 * ici, et complétera tout le reste — langue, touches, curseur de son — avec ses propres défauts,
 * exactement comme si le fichier n'avait jamais existé. Rien d'autre n'est jamais écrit ici : avoir
 * une opinion sur un réglage qu'on n'a pas de raison de connaître mieux que Mojang serait le défaut
 * inverse.
 *
 * <h2>Deuxième clé : {@code preferredGraphicsBackend}, et pourquoi « default » ne suffit pas</h2>
 *
 * <p>Vérifié en lisant {@code net.minecraft.client.PreferredGraphicsApi#getBackendsToTry} dans les
 * sources décompilées du jar patché : pour toute valeur AUTRE que {@code VULKAN}, la liste des
 * backends essayés est {@code {GlBackend, VulkanBackend}} — OpenGL en tête. Autrement dit,
 * {@code preferredGraphicsBackend:"default"} n'essaie JAMAIS Vulkan en premier, même sur une machine
 * où un GPU Vulkan capable existe et même quand le lancement précédent s'est terminé proprement. Ce
 * n'est pas le garde-fou {@code lastStartWasClean} (voir {@link fr.clubcitrouille.lanterne.client.
 * Vigie} pour son vrai rôle, plus restreint) : c'est simplement l'ordre par défaut de la liste.
 *
 * <p>Trois sessions journalisées cette même investigation l'ont confirmé en conditions réelles : la
 * sonde {@code VulkanBackend.checkBackendAvailable()} repère bien la vraie carte dédiée
 * (« Preferring discrete GPU: NVIDIA GeForce RTX 3080 Laptop GPU »), mais le jeu démarre quand même
 * sur OpenGL et l'iGPU AMD, sans la moindre erreur. Sur un tout premier lancement, {@code default}
 * livrerait donc silencieusement la moitié des gains de ce mod : voir {@link fr.clubcitrouille.
 * lanterne.lab.Glass} pour un raisonnement jumeau sur {@code enableVsync}, ici appliqué au backend
 * graphique plutôt qu'à la synchronisation verticale.
 *
 * <p>{@code getBackendsToTry} montre aussi la sortie : {@code VULKAN} explicitement demandé inverse
 * l'ordre en {@code {VulkanBackend, GlBackend}}. Si la création Vulkan échoue malgré tout, la même
 * boucle retombe automatiquement sur OpenGL DANS la même session (voir la boucle de
 * {@code Minecraft.<init>} qui capture {@code BackendCreationException} et continue au backend
 * suivant) — poser {@code "vulkan"} ne sacrifie donc aucun filet de sécurité, il change seulement
 * quel backend est tenté en premier. Et le garde-fou {@code lastStartWasClean} continue de
 * s'appliquer ensuite, normalement, si une vraie session plantée suit : voir {@link
 * fr.clubcitrouille.lanterne.client.Vigie}.
 *
 * <h2>Ce qui n'est délibérément PAS fait</h2>
 *
 * <ul>
 *   <li>Aucune tentative de « corriger » un {@code options.txt} déjà présent, même trouvé à VRAI ou
 *       à {@code "default"}. Voir plus haut : la distinction entre « jamais réglé » et « réglé
 *       sciemment » ne survit pas à l'écriture, et se tromper dans ce sens écraserait un choix du
 *       joueur — y compris un choix DÉLIBÉRÉ de rester sur OpenGL (matériel fragile sous Vulkan,
 *       pilote à jour manquant, etc.). C'est exactement pour cette installation déjà réglée que
 *       {@link fr.clubcitrouille.lanterne.client.Vigie} existe : rendre visible, jamais réécrire.</li>
 *   <li>Aucune limite d'images masquée ailleurs dans ce mod pour compenser un vsync resté actif.
 *       Recherche faite dans {@code mixin/} et {@code client/} :
 *       {@link fr.clubcitrouille.lanterne.mixin.UpscaleGameRendererMixin} LIT
 *       {@code gameRenderState().framerateLimit} pour une heuristique de mise à l'échelle
 *       adaptative — il ne le POSE jamais, et n'appelle aucun équivalent logiciel d'un
 *       {@code SwapInterval}. Le seul autre endroit du dépôt qui touche {@code enableVsync} est
 *       {@link fr.clubcitrouille.lanterne.lab.Glass}, un banc de mesure qui ne s'arme que par
 *       variable d'environnement et ne touche jamais au fichier d'un joueur réel.</li>
 * </ul>
 */
public final class Reveil {
    private Reveil() {}

    /** Nom du fichier vanilla, tel que {@code Minecraft.java} le cherche à la racine du dossier de jeu. */
    private static final String OPTIONS_FILE = "options.txt";

    /**
     * Dépose un {@code options.txt} minimal si — et seulement si — ce dossier de jeu n'en a encore
     * jamais eu. Sans effet, silencieusement, dans tous les autres cas : fichier déjà présent,
     * dossier illisible, ou toute autre panne d'écriture. Une erreur ici ne doit JAMAIS empêcher le
     * jeu de démarrer pour un simple réglage de confort — voir le principe déjà tenu par
     * {@link fr.clubcitrouille.lanterne.core.Hush}.
     *
     * <p>Deux clés, deux justifications indépendantes ci-dessus (voir la Javadoc de classe) :
     * {@code enableVsync:false} et {@code preferredGraphicsBackend:"vulkan"}. Les deux ne s'écrivent
     * que dans le même cas — fichier absent — et jamais séparément : il n'y a qu'un seul « premier
     * lancement ».
     *
     * <p>À appeler une seule fois, tôt dans la construction du mod, et seulement côté client — voir
     * la garde de distribution dans {@code Lanterne.java}. {@link Vigie#capture()} doit être appelé
     * juste après, pour lire l'état final que cette méthode vient éventuellement de poser.
     */
    public static void install() {
        Path options = FMLPaths.GAMEDIR.get().resolve(OPTIONS_FILE);
        try {
            if (Files.exists(options)) {
                // Un fichier existe déjà : quelqu'un — ce joueur, une installation précédente, un
                // pack qui l'a fourni — a déjà une opinion écrite. On ne la lit même pas.
                return;
            }
            Files.writeString(options,
                    "enableVsync:false\npreferredGraphicsBackend:\"vulkan\"\n",
                    StandardCharsets.UTF_8);
            Lanterne.LOG.info("[RÉVEIL] Premier lancement détecté : synchronisation verticale "
                    + "désactivée et backend graphique préféré réglé sur Vulkan par défaut ({} créé, "
                    + "deux lignes) — voir la Javadoc de cette classe pour pourquoi \"default\" ne "
                    + "suffit pas. Un fichier déjà présent n'est jamais modifié.", OPTIONS_FILE);
        } catch (IOException problem) {
            Lanterne.LOG.warn("[RÉVEIL] Impossible d'écrire {} au premier lancement — vanilla "
                    + "prendra ses propres défauts (synchronisation verticale activée, OpenGL "
                    + "essayé avant Vulkan).", options, problem);
        }
    }
}
