package fr.clubcitrouille.lanterne.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.neoforged.fml.loading.FMLPaths;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.report.Compagnons;

/**
 * L'éclaireur : le même geste que {@link Reveil}, mais pour un fichier qui n'est pas le nôtre —
 * celui de JourneyMap, un compagnon, jamais une dépendance.
 *
 * <h2>Le défaut mesuré, vérifié en bytecode réel</h2>
 *
 * <p>Un vrai relevé du profileur en OpenGL ({@code profil-2026-09-20_20-17-48.txt}) montre
 * {@code GL30C.glBindFramebuffer} à 15,3 % du temps, et la pile d'appel de l'échantillon le plus
 * lourd remonte sans ambiguïté à {@code JmRenderDispatcher.drawElement} →
 * {@code GuiRenderToTexture.renderOffscreenOnly} → {@code MiniMap.drawMap} — la mini-carte de
 * JourneyMap redessine sa texture hors-écran bien plus souvent que nécessaire à l'œil. Le réglage
 * qui gouverne cette fréquence est {@code renderDelay}, en millisecondes, dans
 * {@code journeymap.core.config} ; le défaut de JourneyMap est {@code 500}. Le reste des fichiers
 * de config JourneyMap a été audité à la même occasion ({@code core}, {@code fullmap},
 * {@code minimap}, {@code minimap2}, {@code rendering}, {@code topo}, {@code waypoint},
 * {@code webmap}) : rien d'autre n'y est à la fois sûr et prouvé. En particulier,
 * {@code renderDistanceCaveMax}/{@code renderDistanceSurfaceMax}/{@code topoMax} à {@code 0} NE
 * veulent PAS dire « illimité » — {@code RenderDistanceIntField}/{@code TopoMaxIntField}
 * ({@code hasCustomMessage()}) montrent que {@code 0} déclenche un message localisé
 * {@code jm.common.renderdistance_synced} : c'est un synchronisme voulu avec la distance de vue
 * réelle, un défaut déjà sain, pas une fausse bonne idée à corriger.
 *
 * <h2>Pourquoi {@code 1000} et pas une autre valeur</h2>
 *
 * <p>{@code CoreProperties.<init>} — vérifié par {@code javap} — construit ce champ avec
 * {@code new IntegerField(category, "jm.common.renderdelay", 100, 60000, 500)} : bornes
 * {@code [100, 60000]}, défaut {@code 500}. Doubler à {@code 1000} reste large dans ces bornes —
 * {@code isValid()}/{@code validateFields()} ne le ramènera donc jamais au défaut par correction
 * automatique — et divise par deux la fréquence de redessin sans rendre la mini-carte perceptible
 * à l'œil comme périmée.
 *
 * <h2>Pourquoi ce geste est sûr : le chargement de CoreProperties est tardif, pas au démarrage</h2>
 *
 * <p>Remonté en bytecode réel, sur le vrai jar {@code journeymap-neoforge-26.3-6.0.9.jar} :
 * {@code JourneymapNeoForge} (constructeur de mod) construit {@code JourneymapClientNeoForge}, qui
 * construit {@code JourneymapClient()} — ni l'un ni l'autre ne touche {@code CoreProperties}, pas
 * plus que {@code commonSetup()} (n'appelle que {@code PluginHelper.preInitPlugins}). Le vrai point
 * de chargement est {@code JourneymapClient.startMapping() → loadConfigProperties() → reset() →
 * PropertiesBase.load()}, et {@code startMapping()} n'est appelé que par
 * {@code MappingMonitorTask.perform()} — une tâche du propre ordonnanceur interne de JourneyMap,
 * actif seulement une fois une vraie session en cours de jeu, jamais pendant la construction ou la
 * configuration FML. Cette classe-ci s'exécute, comme {@link Reveil}, pendant la construction du
 * mod : très largement avant. Trois preuves indépendantes convergent : la pile du profileur
 * elle-même, la lecture bytecode des trois constructeurs, et le chemin réel observé sur disque
 * ({@code journeymap/config/6.0/journeymap.core.config}, jamais un {@code run/} de repli — preuve
 * que {@code Minecraft.getInstance()} existe déjà quand {@code FileHandler} résout ce chemin en
 * pratique, donc bien après notre propre passage).
 *
 * <h2>Pourquoi un fichier minimal est sûr ici aussi, et pas seulement pour {@code options.txt}</h2>
 *
 * <p>{@code PropertiesBase.fromJsonString} — vérifié en bytecode — n'est rien d'autre qu'un
 * {@code getGson(...).fromJson(json, Class)} standard : Gson construit une instance neuve de
 * {@code CoreProperties} par son constructeur normal — qui pose déjà TOUS les défauts,
 * {@code renderDelay} inclus, comme vu ci-dessus — puis ne remplace que les clés présentes dans le
 * JSON lu. Une clé absente du fichier n'est jamais mise à {@code null} ni rejetée : elle garde
 * simplement la valeur que le constructeur lui a donnée. C'est un contrat standard de Gson, pas une
 * particularité de JourneyMap à deviner. {@code PropertiesBase.load(File, boolean)} confirme
 * qu'aucune validation ne rejette un objet partiel : {@code isValid()} ne fait que corriger des
 * champs individuellement hors bornes, jamais recaler un objet entier pour une clé manquante. Un
 * fichier ne contenant que {@code renderDelay} se comporte donc, pour JourneyMap, exactement comme
 * {@code options.txt} minimal se comporte pour vanilla dans {@link Reveil} : toutes les autres clés
 * arrivent avec les défauts de JourneyMap, pas les nôtres.
 *
 * <h2>Le chemin, et sa seule vraie fragilité honnête</h2>
 *
 * <p>{@code journeymap.client.Constants.CONFIG_DIR} — vérifié en bytecode — est composé à
 * l'exécution comme {@code "journeymap/config/" + Journeymap.JM_VERSION.toMajorMinorString()}. Pour
 * la version {@code 6.0.9} réellement installée, cela vaut {@code "6.0"}, confirmé aussi par le
 * fichier réel observé sur disque. Ce sous-dossier n'est PAS gravé en dur ici par confort mais
 * parce que le lire dynamiquement exigerait d'interroger {@code ModList} au moment du constructeur
 * de mod — un moment dont la sûreté n'est établie nulle part ailleurs dans ce dépôt pour une
 * LECTURE DE VERSION (seul {@code isLoaded()} l'est, voir {@code Compagnons.present} et
 * {@code core.network.LeurreNet.autominerCharge}, tous deux protégés par le même {@code try/catch}
 * défensif et réutilisés ci-dessous plutôt que réinventés). Si une future version de JourneyMap
 * change son numéro majeur.mineur, ce chemin figé cessera silencieusement de correspondre : ni
 * erreur ni fichier incorrect, seulement une optimisation qui ne s'applique plus tant que cette
 * classe n'est pas mise à jour — le même compromis, en moins risqué, que celui déjà assumé par
 * {@link Reveil} pour son propre moteur.
 *
 * <h2>Ce qui n'est délibérément PAS fait</h2>
 *
 * <ul>
 *   <li>Aucune autre clé de {@code journeymap.core.config}, ni d'aucun des sept autres fichiers de
 *       config JourneyMap. L'audit complet n'a trouvé qu'une seule piste à la fois sûre et prouvée —
 *       voir la Javadoc de classe ci-dessus pour pourquoi {@code renderDistanceCaveMax}/
 *       {@code renderDistanceSurfaceMax}/{@code topoMax} sont de fausses pistes.
 *   <li>Aucune tentative de corriger un fichier déjà présent — même mécanisme, même raison que
 *       {@link Reveil} : une fois écrit, rien ne distingue plus « jamais réglé » de « réglé
 *       sciemment », et {@code renderDelay} peut très bien avoir été choisi à la main par un joueur
 *       qui préfère une mini-carte plus réactive.</li>
 * </ul>
 */
public final class Eclaireur {
    private Eclaireur() {}

    /** Sous-dossier de version — voir la Javadoc de classe pour sa composition réelle et sa fragilité assumée. */
    private static final String CONFIG_RELATIVE_PATH = "journeymap/config/6.0/journeymap.core.config";

    /**
     * Dépose une valeur de {@code renderDelay} avant que JourneyMap n'ait eu l'occasion d'écrire
     * son propre fichier — sans effet si JourneyMap n'est pas chargé, si le fichier existe déjà, ou
     * en cas de panne d'écriture. Une erreur ici ne doit jamais empêcher le jeu de démarrer, même
     * principe que {@link Reveil#install()}.
     *
     * <p>À appeler une seule fois, tôt dans la construction du mod, côté client uniquement — même
     * emplacement que {@link Reveil#install()}, pour la même raison de fenêtre de sûreté.
     */
    public static void install() {
        if (!Compagnons.present(Compagnons.JOURNEYMAP)) {
            return;
        }
        Path config = FMLPaths.GAMEDIR.get().resolve(CONFIG_RELATIVE_PATH);
        try {
            if (Files.exists(config)) {
                // JourneyMap a déjà écrit son fichier, ou un joueur en a déjà un : on ne le lit
                // même pas, même principe que Reveil — voir sa Javadoc de classe.
                return;
            }
            Files.createDirectories(config.getParent());
            Files.writeString(config, "{\n  \"renderDelay\": \"1000\"\n}\n", StandardCharsets.UTF_8);
            Lanterne.LOG.info("[ÉCLAIREUR] Premier lancement détecté : renderDelay de JourneyMap "
                    + "réglé sur 1000 ms par défaut (500 ms de base, {} créé, une seule clé) — voir "
                    + "la Javadoc de cette classe pour la mesure de profileur qui le justifie. Un "
                    + "fichier déjà présent n'est jamais modifié.", CONFIG_RELATIVE_PATH);
        } catch (IOException problem) {
            Lanterne.LOG.warn("[ÉCLAIREUR] Impossible d'écrire {} au premier lancement — "
                    + "JourneyMap prendra son propre défaut (renderDelay 500 ms).",
                    CONFIG_RELATIVE_PATH, problem);
        }
    }
}
