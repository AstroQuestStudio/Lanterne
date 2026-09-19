package fr.clubcitrouille.lanterne.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import net.minecraft.util.Util;
import net.neoforged.fml.loading.FMLPaths;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.ClientConfig;

/**
 * L'EMPREINTE : le travail de compilation Vulkan qu'un pilote a déjà fait, gardé d'un lancement à
 * l'autre plutôt que refait à froid à chaque fois.
 *
 * <h2>Le défaut vérifié — par bytecode, pas par supposition</h2>
 *
 * <p>{@code javap -p -c -constants} sur le vrai jar patché ({@code minecraft-patched-26.3.0.3-beta
 * .jar}, jamais {@code .mcsrc}) montre que {@code VulkanRenderPipeline.compile(VulkanDevice,
 * BackendRenderPipeline.CreateInfo)} appelle {@code VK12.vkCreateGraphicsPipelines} à TROIS reprises
 * (les variantes avec profondeur+stencil, avec profondeur seule, et sans profondeur), et qu'à chacun
 * des trois appels le bytecode pousse {@code lconst_0} — la constante longue zéro, c'est-à-dire
 * {@code VK_NULL_HANDLE} — comme second argument, juste avant {@code VkGraphicsPipelineCreateInfo
 * .Buffer}. Aucun champ de {@link com.mojang.renderpearl.backend.vulkan.VulkanDevice}, aucun
 * paramètre de {@code GpuDevice.compilePipeline}/{@code PipelineBuilder}/{@code FrontendGpuDevice} ne
 * porte de {@code VkPipelineCache} nulle part dans ce moteur — vérifié par la même lecture, pas
 * seulement l'absence d'un mot-clé grep. {@code com.mojang.blaze3d.pipeline.PipelineCache} (qui
 * existe bel et bien, {@code RenderSystem.setFallbackPipelineCache}) n'est PAS ce que son nom
 * suggère : c'est une simple {@code Map<RenderPipeline, CompiledRenderPipeline>} en mémoire, pour ne
 * pas recompiler DEUX FOIS le même objet {@code RenderPipeline} DANS LA MÊME SESSION — rien qui
 * survive un redémarrage, rien qui parle au pilote. Ce moteur ne fait donc, structurellement,
 * absolument RIEN pour épargner au pilote une recompilation complète à chaque lancement, ni pour
 * TERRAIN/OIT/GENERIC_BLOCKS (vanilla), ni pour {@link fr.clubcitrouille.lanterne.client.upscale
 * .Gbuffer}/{@link fr.clubcitrouille.lanterne.client.upscale.Accumulate} (Lanterne).
 *
 * <p>C'est exactement ce qui a gelé le rendu la nuit du 567e592 : la toute première compilation de
 * {@code Gbuffer}, jamais exercée avant que {@code club_citrouille} ne tourne pour de vrai, a pris
 * plusieurs minutes sur un pilote qui partait d'une feuille blanche. Le correctif de cette nuit-là
 * (thread d'arrière-plan + délai borné) évite que ÇA gèle le rendu ; il ne change rien au fait que le
 * pilote recompile tout, encore, à chaque lancement suivant. Cette classe s'attaque à la cause.
 *
 * <h2>Ce que Vulkan garantit, vérifié plutôt que supposé</h2>
 *
 * <p>Un {@code VkPipelineCache} sérialisé porte un en-tête {@code VkPipelineCacheHeaderVersionOne}
 * (présent dans les classes LWJGL réelles de ce dépôt, {@code org.lwjgl.vulkan
 * .VkPipelineCacheHeaderVersionOne} — {@code headerSize}, {@code headerVersion}, {@code vendorID},
 * {@code deviceID}, {@code pipelineCacheUUID}). La spécification Vulkan impose au PILOTE de comparer
 * cet en-tête à sa propre identité avant d'utiliser quoi que ce soit du contenu, et de traiter
 * silencieusement des données absentes/tronquées/étrangères comme un cache VIDE plutôt que d'échouer
 * — {@code vkCreatePipelineCache} n'est pas autorisé à planter sur des données invalides. Cette classe
 * ne réimplémente donc AUCUNE validation de compatibilité : les octets relus du disque sont transmis
 * tels quels en {@code pInitialData}, et c'est le pilote qui décide de les honorer ou de les ignorer.
 * La seule robustesse ajoutée ici est une CEINTURE, pas une réinvention de la BOUCLE : si un pilote
 * particulier refusait malgré tout la création (retour différent de {@code VK_SUCCESS} — jamais vu en
 * pratique, mais non exclu par un pilote non conforme), {@link #create} retente une fois SANS données
 * initiales avant d'abandonner pour la session.
 *
 * <h2>Nommage du fichier : une empreinte par pilote, pas une seule pour la machine</h2>
 *
 * <p>Le fichier est nommé d'après {@code VkPhysicalDeviceProperties.pipelineCacheUUID} — le champ que
 * Vulkan définit précisément pour distinguer une combinaison device+pilote d'une autre (il change à
 * une mise à jour de pilote qui casse la compatibilité binaire). Une machine avec iGPU ET GPU dédié,
 * ou un pilote mis à jour, obtient donc CHACUN son propre fichier au lieu de se marcher dessus : pas
 * besoin de valider nous-mêmes la compatibilité, il suffit de ne jamais faire lire à un pilote le
 * fichier d'un autre.
 *
 * <h2>Le point d'accroche pour le rechargement, avant toute compilation</h2>
 *
 * <p>{@link fr.clubcitrouille.lanterne.mixin.VulkanDeviceMixin} appelle {@link #onDeviceReady} à la
 * toute fin du constructeur de {@code VulkanDevice} — avant qu'aucun pipeline (vanilla ou Lanterne)
 * n'ait pu être compilé, puisque {@code compilePipeline} est une méthode d'INSTANCE qui exige un
 * {@code VulkanDevice} déjà construit. Le cache est donc systématiquement prêt et rechargé avant le
 * premier appel à {@code vkCreateGraphicsPipelines} de la session.
 *
 * <h2>Le point d'accroche pour l'utilisation, à chaque compilation</h2>
 *
 * <p>{@link fr.clubcitrouille.lanterne.mixin.VulkanRenderPipelineMixin} redirige les trois appels à
 * {@code VK12.vkCreateGraphicsPipelines} identifiés ci-dessus pour leur passer {@link #handle()} au
 * lieu de {@code VK_NULL_HANDLE} — pour TOUS les pipelines de ce moteur, vanilla comme Lanterne,
 * puisque {@code compile} est le seul point de fabrication Vulkan du moteur entier (vérifié : aucun
 * autre appel à {@code vkCreateGraphicsPipelines} n'existe dans le jar patché).
 *
 * <h2>Synchronisation : ce que la spécification Vulkan exige, pas une prudence de principe</h2>
 *
 * <p>La spécification Vulkan classe {@code vkCreateGraphicsPipelines}, {@code vkGetPipelineCacheData}
 * et {@code vkDestroyPipelineCache} comme exigeant un accès hôte EXTERNELLEMENT synchronisé sur le
 * MÊME {@code VkPipelineCache} — le pilote a le droit de corrompre son état si deux fils l'utilisent
 * en même temps sans verrou. Un pipeline peut se compiler sur le fil de rendu (les pipelines vanilla,
 * au chargement des ressources) EN MÊME TEMPS qu'{@link #flush} tourne sur {@code Util
 * .backgroundExecutor()} (le délai d'écriture ci-dessous) : {@link #LOCK} est le même moniteur des
 * deux côtés, exactement pour empêcher ce chevauchement.
 *
 * <h2>Quand l'écriture sur disque a lieu, et pourquoi pas à chaque pipeline</h2>
 *
 * <p>Écrire après CHAQUE compilation coûterait un {@code vkGetPipelineCacheData} (qui sérialise TOUT
 * le cache, pas seulement l'ajout) à chacun des dizaines de pipelines compilés en rafale au démarrage
 * — inutilement cher. {@link #noteCompile} arme un délai unique de {@value #FLUSH_DELAY_MS} ms sur
 * {@code Util.backgroundExecutor()} ; toute compilation supplémentaire pendant ce délai ne réarme
 * rien ({@link #flushScheduled} coalesce), si bien qu'une rafale de démarrage ne produit qu'UNE seule
 * écriture, peu après qu'elle s'est calmée. {@link fr.clubcitrouille.lanterne.mixin
 * .VulkanDeviceMixin#lanterne$sauverEmpreinte} appelle en plus {@link #onDeviceClosing} à la fermeture
 * normale du device ({@code RenderSystem.shutdownRenderer()}, vérifié par bytecode) : un filet de
 * sécurité, pas le mécanisme principal — une session tuée brutalement (plantage, processus forcé)
 * perd au pire les pipelines compilés dans la dernière fenêtre de {@value #FLUSH_DELAY_MS} ms, jamais
 * plus.
 *
 * <h2>Interrupteur</h2>
 *
 * <p>{@link ClientConfig#EMPREINTE}, ÉTEINT par défaut : correct par construction (vérifié par
 * {@code javap} sur le vrai jar patché, comme l'exige la première règle de ce dépôt), mais jamais
 * passé par un vrai lancement client — interdit à celui qui écrit cette classe, réservé au
 * coordinateur. Voir le protocole de vérification dans le Javadoc de {@link ClientConfig#EMPREINTE}.
 */
public final class Empreinte {

    /** Le délai après la DERNIÈRE compilation notée avant d'écrire le cache sur disque. */
    private static final long FLUSH_DELAY_MS = 3_000L;

    /**
     * Le moniteur partagé entre cette classe et {@link fr.clubcitrouille.lanterne.mixin
     * .VulkanRenderPipelineMixin} — voir la section « Synchronisation » du Javadoc de classe pour
     * ce que la spécification Vulkan exige exactement.
     */
    public static final Object LOCK = new Object();

    /** {@code VK_NULL_HANDLE} tant qu'aucun cache n'est prêt (éteint, ou pas encore de device). */
    private static volatile long handle = VK10.VK_NULL_HANDLE;

    /** Le device sur lequel {@link #handle} a été créé — nul en même temps que {@link #handle}. */
    private static volatile VkDevice device;

    /** Le fichier associé à ce pilote précis — voir la section « Nommage » du Javadoc de classe. */
    private static volatile Path file;

    private static final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    private Empreinte() {}

    /** Le handle Vulkan à fournir à {@code vkCreateGraphicsPipelines}, ou {@code VK_NULL_HANDLE}. */
    public static long handle() {
        return handle;
    }

    /**
     * Appelé une seule fois, juste après que le constructeur de {@code VulkanDevice} a fini — voir
     * {@link fr.clubcitrouille.lanterne.mixin.VulkanDeviceMixin}. Recharge le fichier de ce pilote
     * s'il existe, ou crée un cache vierge. N'échoue jamais bruyamment : un problème quelconque laisse
     * simplement {@link #handle()} à {@code VK_NULL_HANDLE}, exactement le comportement d'avant cette
     * classe.
     */
    public static void onDeviceReady(VkDevice vkDevice, VkPhysicalDeviceProperties properties) {
        // <h2>Trop tot pour lire la config, et ce n'est pas une supposition</h2>
        //
        // VulkanDevice se construit pendant l'amorcage du moteur, bien avant que NeoForge ne
        // charge les fichiers de config (verifie par javap sur ModConfigSpec$ConfigValue.getRaw :
        // Preconditions.checkState(loadedConfig != null, "Cannot get config value before config
        // is loaded.") -- une IllegalStateException, pas un repli silencieux sur la valeur par
        // defaut). Aucun autre module de ce depot ne lit sa config aussi tot ; Empreinte est le
        // premier a le faire, precisement parce qu'il doit agir au moment ou le device nait.
        // Le contrat de cette methode ("n'echoue jamais bruyamment") s'applique aussi a ce cas :
        // config pas encore chargee equivaut a config eteinte pour CE lancement, pas a un plantage.
        boolean actif;
        try {
            actif = ClientConfig.EMPREINTE.get();
        } catch (IllegalStateException configPasEncoreChargee) {
            return;
        }
        if (!actif) {
            return;
        }
        Path target = resolveFile(properties);
        byte[] existing = readQuietly(target);
        synchronized (LOCK) {
            long created = create(vkDevice, existing);
            if (created == VK10.VK_NULL_HANDLE) {
                return; // create() a déjà journalisé la raison.
            }
            device = vkDevice;
            file = target;
            handle = created;
        }
        Lanterne.LOG.info("[LANTERNE][EMPREINTE] Cache de pipeline Vulkan {} ({} — {}).",
                (existing == null || existing.length == 0) ? "créé vierge" : "rechargé",
                target,
                (existing == null) ? "aucun fichier antérieur" : existing.length + " octet(s) relus");
    }

    /** Crée le {@code VkPipelineCache} natif. Doit être appelé sous {@link #LOCK}. */
    private static long create(VkDevice vkDevice, byte[] initialData) {
        long created = createOnce(vkDevice, initialData);
        if (created != VK10.VK_NULL_HANDLE) {
            return created;
        }
        if (initialData != null && initialData.length > 0) {
            // Ceinture, pas réinvention de la validation : voir la section correspondante du
            // Javadoc de classe. Un pilote conforme ne devrait jamais emprunter cette branche.
            Lanterne.LOG.warn("[LANTERNE][EMPREINTE] Le pilote a refusé la relecture du cache "
                    + "({} octet(s)) — nouvel essai avec un cache vierge.", initialData.length);
            created = createOnce(vkDevice, null);
        }
        if (created == VK10.VK_NULL_HANDLE) {
            Lanterne.LOG.warn("[LANTERNE][EMPREINTE] Le pilote refuse même un cache vierge — "
                    + "cette session tournera sans cache de pipeline, comme avant ce module.");
        }
        return created;
    }

    private static long createOnce(VkDevice vkDevice, byte[] initialData) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
            ByteBuffer nativeInitial = null;
            if (initialData != null && initialData.length > 0) {
                nativeInitial = MemoryUtil.memAlloc(initialData.length);
                nativeInitial.put(initialData).flip();
                info.pInitialData(nativeInitial);
            }
            try {
                var pCache = stack.mallocLong(1);
                int result = VK10.vkCreatePipelineCache(vkDevice, info, null, pCache);
                return result == VK10.VK_SUCCESS ? pCache.get(0) : VK10.VK_NULL_HANDLE;
            } finally {
                if (nativeInitial != null) {
                    MemoryUtil.memFree(nativeInitial);
                }
            }
        } catch (Throwable problem) {
            Lanterne.LOG.warn("[LANTERNE][EMPREINTE] vkCreatePipelineCache a levé une exception "
                    + "inattendue — cache désactivé pour cette session.", problem);
            return VK10.VK_NULL_HANDLE;
        }
    }

    /**
     * Appelé après chaque {@code vkCreateGraphicsPipelines} réussi qui a effectivement utilisé
     * {@link #handle()} — voir {@link fr.clubcitrouille.lanterne.mixin.VulkanRenderPipelineMixin}.
     * Arme (ou laisse courir) le délai d'écriture — voir la section « Quand l'écriture » du Javadoc
     * de classe.
     */
    public static void noteCompile() {
        if (handle == VK10.VK_NULL_HANDLE) {
            return;
        }
        if (!flushScheduled.compareAndSet(false, true)) {
            return; // Un délai court déjà : cette compilation sera couverte par son écriture.
        }
        Util.backgroundExecutor().execute(() -> {
            try {
                Thread.sleep(FLUSH_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                flushScheduled.set(false);
            }
            flush();
        });
    }

    /**
     * Sérialise le cache courant et l'écrit sur disque, en remplaçant l'ancien fichier de façon
     * atomique. Silencieux si le cache n'est pas prêt (éteint, ou déjà fermé) — appelable sans
     * précaution depuis {@link #noteCompile} comme depuis {@link #onDeviceClosing}.
     */
    private static void flush() {
        long h;
        VkDevice d;
        Path f;
        byte[] bytes;
        synchronized (LOCK) {
            h = handle;
            d = device;
            f = file;
            if (h == VK10.VK_NULL_HANDLE || d == null || f == null) {
                return;
            }
            bytes = read(d, h);
        }
        if (bytes == null) {
            return; // read() a déjà journalisé la raison.
        }
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
            Lanterne.LOG.info("[LANTERNE][EMPREINTE] Cache de pipeline Vulkan écrit — {} octet(s) "
                    + "dans {}.", bytes.length, f);
        } catch (IOException failed) {
            Lanterne.LOG.warn("[LANTERNE][EMPREINTE] Écriture de {} impossible — le cache de cette "
                    + "session sera reconstruit au prochain lancement.", f, failed);
        }
    }

    /** {@code vkGetPipelineCacheData}, en deux appels (taille, puis contenu). Doit tourner sous {@link #LOCK}. */
    private static byte[] read(VkDevice vkDevice, long cacheHandle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pSize = stack.mallocPointer(1);
            int result = VK10.vkGetPipelineCacheData(vkDevice, cacheHandle, pSize, null);
            if (result != VK10.VK_SUCCESS) {
                Lanterne.LOG.warn("[LANTERNE][EMPREINTE] vkGetPipelineCacheData (taille) a rendu {} "
                        + "— écriture ignorée cette fois.", result);
                return null;
            }
            int size = (int) pSize.get(0);
            if (size <= 0) {
                return new byte[0];
            }
            ByteBuffer data = MemoryUtil.memAlloc(size);
            try {
                result = VK10.vkGetPipelineCacheData(vkDevice, cacheHandle, pSize, data);
                if (result != VK10.VK_SUCCESS) {
                    Lanterne.LOG.warn("[LANTERNE][EMPREINTE] vkGetPipelineCacheData (contenu) a "
                            + "rendu {} — écriture ignorée cette fois.", result);
                    return null;
                }
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                return bytes;
            } finally {
                MemoryUtil.memFree(data);
            }
        } catch (Throwable problem) {
            Lanterne.LOG.warn("[LANTERNE][EMPREINTE] Lecture du cache natif impossible — écriture "
                    + "ignorée cette fois.", problem);
            return null;
        }
    }

    /**
     * Appelé à la fermeture normale du device — voir {@link fr.clubcitrouille.lanterne.mixin
     * .VulkanDeviceMixin#lanterne$sauverEmpreinte} et la section « Quand l'écriture » du Javadoc de
     * classe pour pourquoi ce n'est qu'un filet de sécurité. Écrit une dernière fois puis détruit
     * l'objet natif — le détruire SANS l'avoir d'abord lu perdrait tout le travail de la session.
     */
    public static void onDeviceClosing() {
        if (handle == VK10.VK_NULL_HANDLE) {
            return;
        }
        flush();
        synchronized (LOCK) {
            if (handle != VK10.VK_NULL_HANDLE && device != null) {
                VK10.vkDestroyPipelineCache(device, handle, null);
            }
            handle = VK10.VK_NULL_HANDLE;
            device = null;
            file = null;
        }
    }

    /** Le dossier {@code config/lanterne/empreinte/}, nommé par pilote — voir le Javadoc de classe. */
    private static Path resolveFile(VkPhysicalDeviceProperties properties) {
        ByteBuffer uuid = properties.pipelineCacheUUID();
        StringBuilder hex = new StringBuilder(uuid.remaining() * 2);
        int start = uuid.position();
        for (int i = 0; i < uuid.remaining(); i++) {
            hex.append(String.format(Locale.ROOT, "%02x", uuid.get(start + i)));
        }
        return FMLPaths.GAMEDIR.get().resolve("config").resolve(Lanterne.ID).resolve("empreinte")
                .resolve(hex + ".cache");
    }

    /** {@code null} si le fichier n'existe pas ou n'est pas lisible — jamais d'exception remontée. */
    private static byte[] readQuietly(Path target) {
        if (!Files.isRegularFile(target)) {
            return null;
        }
        try {
            return Files.readAllBytes(target);
        } catch (IOException failed) {
            Lanterne.LOG.warn("[LANTERNE][EMPREINTE] {} illisible — cache reconstruit à neuf.",
                    target, failed);
            return null;
        }
    }
}
