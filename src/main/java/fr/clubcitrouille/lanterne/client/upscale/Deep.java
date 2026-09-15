package fr.clubcitrouille.lanterne.client.upscale;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Locale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * DLSS : le relevé de ce qui manque, et l'endroit exact où le brancher.
 *
 * <h2>Pourquoi ce fichier existe alors qu'il ne fait rien</h2>
 *
 * <p>Ce module <b>ne fait pas de DLSS</b>. Il dit pourquoi, il le dit au joueur autant qu'au
 * journal, et il tient l'échelon de repli. Ce n'est pas une politesse : un mod qui affiche « DLSS »
 * sans rien faire de particulier est indiscernable, à l'œil, d'un mod qui en fait — et c'est
 * précisément ce qui rend invérifiables les trois mods propriétaires qui prétendent l'apporter à
 * Minecraft. Ce projet ne peut pas les critiquer sur ce point puis faire pareil.
 *
 * <h2>Ce qui est établi, et qui a été lu dans les sources de 26.2</h2>
 *
 * <p>Trois obstacles qu'on croyait insurmontables ne le sont pas :
 *
 * <ul>
 *   <li><b>La réflexion n'est plus nécessaire.</b> Le mod de référence sur ce sujet vole le
 *       {@code VkInstance} par réflexion sur {@code VulkanDevice}. En 26.2, {@code VulkanDevice}
 *       est public, {@code instance()} et {@code vkDevice()} aussi, et {@code VulkanInstance}
 *       expose {@code vkInstance()}. Il ne reste qu'un champ privé sur le chemin —
 *       {@code GpuDevice.backend} — qui demande un accesseur de mixin, et rien de plus.</li>
 *   <li><b>Le backend actif se lit sans rien forcer</b> : {@code DeviceInfo.backendName()} vaut
 *       {@code "Vulkan"} ou {@code "OpenGL"}, et {@code vendorName()}, {@code name()} et
 *       {@code type()} donnent de quoi reconnaître une carte. C'est ce que fait {@link #probe()}.</li>
 *   <li><b>Le décalage de projection a une place nette.</b> {@code GameRenderer.renderLevel} fait
 *       une copie locale de la matrice de projection, la modifie déjà pour le tangage et le vertige,
 *       puis appelle {@code ProjectionMatrixBuffer.getBuffer(Matrix4f)} — une surcharge qui, à la
 *       différence de {@code getBuffer(Projection)}, <b>n'a aucun cache de version</b> et réécrit le
 *       tampon à chaque appel. Un décalage d'un sous-pixel par image s'y insère sans rien casser.</li>
 * </ul>
 *
 * <h2>Ce qui manque — et le pont natif n'en fait plus partie</h2>
 *
 * <p><b>Cette classe a longtemps affirmé qu'il fallait un pont JNI compilé en C++. C'était faux</b>,
 * et la correction vaut d'être écrite ici plutôt qu'effacée. Le chargeur NGX que le pilote NVIDIA
 * installe déjà — {@code _nvngx.dll} — est une DLL ordinaire qui exporte ses 75 fonctions
 * {@code NVSDK_NGX_*} sous leurs <b>noms C non décorés</b>. Java 25 embarque Project Panama, et
 * {@code SymbolLookup.libraryLookup} les résout toutes, par chemin absolu, sans JNI, sans C++, sans
 * CMake. La preuve se relance : {@code tools/EssaiNgx.java}. Le relevé complet est dans
 * {@code notes/dlss-panama.md}.
 *
 * <p>Il reste trois obstacles, et ce sont d'autres que celui qu'on croyait :
 *
 * <ol>
 *   <li><b>Les paramètres NGX ne sont pas du C.</b> {@code NVSDK_NGX_VULKAN_AllocateParameters}
 *       rend un objet dont les {@code Set} et {@code Get} sont des méthodes <b>virtuelles C++</b> :
 *       aucun {@code NVSDK_NGX_Parameter_SetI} n'est exporté, et c'est vérifié — l'épreuve le teste
 *       comme contrôle négatif. Panama doit donc parcourir une <b>table virtuelle</b> à la main.
 *       C'est possible, et c'est la seule partie du montage qui ne repose sur aucun contrat public :
 *       l'ordre des méthodes est celui d'un en-tête que NVIDIA peut réordonner, et un mauvais index
 *       n'échoue pas proprement — il appelle autre chose, avec les mauvais arguments, chez le
 *       joueur. Rien ne doit être écrit là-dessus avant d'avoir les en-têtes du SDK sous les yeux.</li>
 *   <li><b>{@code nvngx_dlss.dll} n'est pas sur la machine du joueur.</b> Ce n'est pas une
 *       supposition : un balayage complet de {@code System32}, {@code Program Files} et
 *       {@code ProgramData} sur une machine RTX 3080 à pilote à jour n'en trouve <b>aucune</b> copie
 *       venue du pilote. Le pilote installe le chargeur et {@code nvngx_dlssg.dll} — la génération
 *       d'images — mais pas le modèle de super-résolution. Et il ne peut pas être embarqué dans
 *       l'archive : la licence de NVIDIA l'encadre, et la GPL-3.0 de ce mod l'interdirait de toute
 *       façon. La seule voie propre est le téléchargement à l'exécution, <b>sur consentement
 *       explicite</b>, avec vérification d'empreinte — la mécanique que {@code client/screen/Fetch}
 *       écrit déjà pour FFmpeg. Le chemin attendu est {@code config/lanterne/nvngx_dlss.dll} ;
 *       {@link #libraryPresent()} se contente de regarder s'il est là. Le téléchargement n'est pas
 *       écrit tant que rien ne consomme le fichier : un téléchargeur sans utilisateur, c'est une
 *       surface réseau pour zéro fonction.</li>
 *   <li><b>Les vecteurs de mouvement.</b> Un remonteur temporel en exige. Minecraft n'en produit
 *       aucun, et le tampon de profondeur — samplable en {@code D32_FLOAT}, en Z <em>inversé</em>
 *       depuis 26.2 — est effacé juste après le monde, dans {@code GameRenderer.render}, avant que
 *       l'interface ne soit peinte. Les reconstruire depuis la profondeur donne des vecteurs
 *       corrects pour la <b>caméra</b> et faux pour tout ce qui bouge par soi-même : créatures,
 *       particules, feuillage animé y laissent une traînée. C'est un défaut connu de la méthode,
 *       pas un défaut d'implémentation, et il doit être annoncé avant d'être découvert.</li>
 *   <li><b>Le décalage de projection.</b> Sans lui, un remonteur temporel reçoit la même
 *       information à chaque image et n'a rien à accumuler : il rend du flou, pas du net. Il n'est
 *       donc pas écrit non plus, parce qu'il <b>dégraderait</b> l'image tant qu'aucun remonteur
 *       temporel ne l'exploite — un tremblement d'un demi-pixel, visible et sans contrepartie.</li>
 * </ol>
 *
 * <h2>Les trois échelons, et pourquoi le dernier est le rendu de vanilla</h2>
 *
 * <p>DLSS, puis FSR 1.0, puis rien. Chaque échelon vérifie ses conditions <b>avant</b> qu'on se soit
 * engagé à dessiner le monde ailleurs que là où il sera présenté. Un joueur qui n'a pas de carte
 * RTX, ou qui est resté sur OpenGL — c'est-à-dire l'immense majorité, puisque Vulkan est marqué
 * « expérimental » en 26.2 — obtient FSR 1.0 sans s'en apercevoir, et jamais un écran noir.
 */
public final class Deep {
    /** Le modèle de super-résolution, que le pilote n'installe pas et que le joueur doit fournir. */
    private static final String LIBRARY = "nvngx_dlss.dll";

    /** Le chargeur NGX, que le pilote installe, lui. Voir {@link #loader()}. */
    private static final String LOADER = "_nvngx.dll";

    /** Ce qui empêche DLSS de tourner, dans l'ordre où la question se pose. */
    public enum Verdict {
        /** Pas encore relevé. */
        INCONNU("non relevé"),
        /** Le jeu tourne sur OpenGL. DLSS exige un contexte Vulkan. */
        SANS_VULKAN("OpenGL — DLSS exige Vulkan"),
        /** Pas de carte NVIDIA compatible. */
        SANS_RTX("carte non compatible"),
        /** Le pilote installé ne fournit pas {@code _nvngx.dll} : pilote trop ancien, ou non NVIDIA. */
        SANS_CHARGEUR("chargeur NGX introuvable"),
        /**
         * Le modèle de super-résolution manque.
         *
         * <p>C'est le cas <b>normal</b>, et non l'exception : le pilote ne l'installe pas. Voir
         * l'en-tête de cette classe.
         */
        SANS_BIBLIOTHEQUE("nvngx_dlss.dll absent — le pilote ne l'installe pas"),
        /**
         * Tout est réuni côté machine, et la liaison Panama vers la table virtuelle des paramètres
         * NGX n'est pas écrite. Voir l'en-tête pour ce qu'elle exige avant de pouvoir l'être.
         */
        SANS_PONT("liaison NGX non écrite");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    private static Verdict verdict = Verdict.INCONNU;
    private static boolean announced;

    private Deep() {}

    /**
     * DLSS peut-il servir à cette image ?
     *
     * <p>Rend {@code false} en toutes circonstances tant que le pont natif n'existe pas. Ce n'est
     * pas un bouchon : c'est la seule réponse vraie, et c'est elle qui fait tomber l'appelant sur
     * FSR 1.0.
     */
    public static boolean available() {
        return false;
    }

    /**
     * Le point de branchement.
     *
     * <p>Le jour où le pont natif existe, c'est ici que la toile part vers NGX et que le résultat
     * revient dans la cible principale — la même signature que {@code Resolve.run}, parce que c'est
     * le même contrat : « le monde est dans la première, mets-le dans la seconde ».
     *
     * @return vrai si la cible principale contient bien l'image du monde
     */
    @SuppressWarnings("unused")
    public static boolean resolve(RenderTarget scene, RenderTarget screen) {
        return false;
    }

    /**
     * Relève l'état de la machine et l'écrit une fois dans le journal.
     *
     * <p>Appelée à la première image où la mise à l'échelle s'arme. Une seule fois : répéter la même
     * ligne soixante fois par seconde est la meilleure façon de la rendre illisible, et ce relevé ne
     * change pas en cours de partie — changer de backend graphique demande un redémarrage du jeu.
     */
    public static Verdict probe() {
        if (verdict != Verdict.INCONNU) {
            return verdict;
        }
        try {
            DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
            if (!"Vulkan".equalsIgnoreCase(info.backendName())) {
                verdict = Verdict.SANS_VULKAN;
            } else if (!looksLikeRtx(info)) {
                verdict = Verdict.SANS_RTX;
            } else if (loader() == null) {
                verdict = Verdict.SANS_CHARGEUR;
            } else if (!libraryPresent()) {
                verdict = Verdict.SANS_BIBLIOTHEQUE;
            } else {
                verdict = Verdict.SANS_PONT;
            }
            announce(info);
        } catch (Throwable problem) {
            // Un relevé qui plante ne doit pas emporter l'image. On conclut au pire des cas, qui
            // est aussi le plus fréquent, et on continue.
            verdict = Verdict.SANS_VULKAN;
            Lanterne.LOG.debug("[ÉCHELLE] Relevé de la carte graphique impossible.", problem);
        }
        return verdict;
    }

    public static Verdict verdict() {
        return verdict;
    }

    /** Une ligne pour l'écran de réglages, qui doit dire la vérité et non « bientôt ». */
    public static String describe() {
        return switch (verdict) {
            case INCONNU -> "non relevé";
            case SANS_VULKAN -> "OpenGL";
            case SANS_RTX -> "carte non RTX";
            case SANS_CHARGEUR -> "sans NGX";
            case SANS_BIBLIOTHEQUE -> "modèle absent";
            case SANS_PONT -> "liaison à écrire";
        };
    }

    /**
     * La carte accepte-t-elle DLSS ?
     *
     * <p>Le test porte sur le nom rapporté par le pilote, faute de mieux : ni Vulkan ni OpenGL ne
     * dit « je sais faire tourner NGX ». La famille GTX 16xx, qui accepte DLSS sur certains titres
     * sans cœurs tenseurs, n'est <b>pas</b> reconnue ici — le relevé est volontairement strict,
     * parce qu'un faux positif conduirait à charger une bibliothèque qui échouerait plus tard, à un
     * endroit où l'échec coûte une image.
     */
    private static boolean looksLikeRtx(DeviceInfo info) {
        String vendor = info.vendorName() == null ? "" : info.vendorName().toUpperCase(Locale.ROOT);
        String name = info.name() == null ? "" : info.name().toUpperCase(Locale.ROOT);
        return (vendor.contains("NVIDIA") || name.contains("NVIDIA")) && name.contains("RTX");
    }

    /**
     * La bibliothèque de NVIDIA est-elle déposée par le joueur ?
     *
     * <p>Jamais téléchargée sans qu'on le lui demande, jamais embarquée dans l'archive. Voir
     * l'en-tête de cette classe pour ce que la licence autorise et ce qu'elle interdit.
     */
    public static boolean libraryPresent() {
        try {
            Path path = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve(Lanterne.ID).resolve(LIBRARY);
            return Files.isRegularFile(path);
        } catch (Throwable problem) {
            return false;
        }
    }

    /**
     * Où le pilote range-t-il le chargeur NGX ?
     *
     * <h2>Pourquoi ce n'est pas un chemin en dur</h2>
     *
     * <p>Les pilotes récents ne copient plus {@code _nvngx.dll} dans {@code System32} : il reste
     * dans le magasin de pilotes, sous {@code nvaci.inf} — « NVIDIA Application Compute Interface »
     * — dont le suffixe est un condensat qui <b>change à chaque version de pilote</b>. Un chemin
     * écrit en dur serait faux à la prochaine mise à jour, c'est-à-dire un mois plus tard, et
     * l'échec serait silencieux. On balaie donc, en gardant {@code System32} d'abord pour les
     * pilotes plus anciens qui l'y déposaient encore.
     *
     * <p>Ce relevé n'ouvre rien et ne charge rien : il regarde. C'est ce qui permet à l'écran de
     * réglages de dire « le chargeur est là, c'est le modèle qui manque » plutôt que de laisser le
     * joueur deviner lequel des deux fait défaut.
     *
     * @return le chemin du chargeur, ou {@code null} s'il n'y en a aucun
     */
    public static Path loader() {
        try {
            Path direct = Path.of("C:", "Windows", "System32", "_nvngx.dll");
            if (Files.isRegularFile(direct)) {
                return direct;
            }
            Path store = Path.of("C:", "Windows", "System32", "DriverStore", "FileRepository");
            if (!Files.isDirectory(store)) {
                return null;
            }
            Path newest = null;
            FileTime newestAt = null;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(store, "nv*")) {
                for (Path folder : stream) {
                    Path candidate = folder.resolve(LOADER);
                    if (!Files.isRegularFile(candidate)) {
                        continue;
                    }
                    // Plusieurs pilotes cohabitent dans le magasin : celui qui compte est le
                    // dernier posé, pas le premier trouvé.
                    FileTime at = Files.getLastModifiedTime(candidate);
                    if (newestAt == null || at.compareTo(newestAt) > 0) {
                        newest = candidate;
                        newestAt = at;
                    }
                }
            }
            return newest;
        } catch (Throwable problem) {
            // Un dossier du magasin peut refuser la lecture : c'est une raison de ne pas trouver,
            // pas une raison d'emporter le relevé.
            Lanterne.LOG.debug("[ÉCHELLE] Recherche du chargeur NGX interrompue.", problem);
            return null;
        }
    }

    private static void announce(DeviceInfo info) {
        if (announced) {
            return;
        }
        announced = true;
        Path loader = loader();
        Lanterne.LOG.info("[ÉCHELLE] DLSS indisponible — {}. Backend {}, carte « {} », "
                + "chargeur NGX {}. La mise à l'échelle se fait par FSR 1.0, qui n'exige ni Vulkan "
                + "ni carte NVIDIA.",
                verdict.label(), info.backendName(), info.name(),
                loader == null ? "introuvable" : loader);
        // Le cas le plus fréquent est aussi le seul sur lequel le joueur peut agir : une carte
        // capable, arrêtée par un réglage qu'il ignore. Le dire une fois vaut mieux que le laisser
        // conclure que son matériel est en cause.
        if (verdict == Verdict.SANS_VULKAN && looksLikeRtx(info)) {
            Lanterne.LOG.info("[ÉCHELLE] Cette carte saurait faire DLSS : c'est le backend qui "
                    + "l'en empêche. Le réglage « Vulkan » de l'écran Lanterne (famille « L'image ») "
                    + "le bascule en un clic ; il prend effet au prochain lancement, et le jeu "
                    + "retombe seul sur OpenGL si Vulkan échoue.");
        }
    }
}
