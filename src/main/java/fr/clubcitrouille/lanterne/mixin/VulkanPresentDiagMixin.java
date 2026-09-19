package fr.clubcitrouille.lanterne.mixin;

import java.util.Locale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Diagnostic temporaire, jamais un correctif : mesure où va le temps du compartiment
 * « hors rendu » que {@code Glass} a mesuré ×57 plus grand sous Vulkan qu'OpenGL avec
 * {@code horizon} actif (30,1 im/s contre 92,1 — voir le rapport de banc RING, dix mille
 * vaches, 19/09/2026).
 *
 * <h2>Ce que la lecture du vrai code a déjà établi, sans lancer aucun client</h2>
 *
 * <p>{@code Minecraft.renderFrame()} (vrai jar patché, {@code minecraft-patched-26.3.0.3-beta
 * -merged.jar}, sources embarquées — jamais {@code .mcsrc}) capture {@code renderStartTimer}
 * <b>après</b> {@code this.windowSurface.acquireNextTexture()} et calcule {@code frameTimeNs}
 * <b>avant</b> {@code this.windowSurface.present()}. Le diagnostic « rendu seul » de
 * {@code Glass} (issu de {@code getFrameTimeNs()}) ne peut donc structurellement jamais voir
 * un blocage à l'intérieur de {@code acquireNextTexture()} — alors que l'intervalle réel entre
 * deux images que {@code Glass} utilise comme verdict, lui, le voit forcément. Ce n'est pas un
 * artefact du banc : {@code Glass} mesure exactement ce qu'il prétend mesurer, et la ligne de
 * partage entre « rendu » et « hors rendu » tombe très exactement à la frontière de
 * {@code acquireNextTexture()}.
 *
 * <p>{@code VulkanGpuSurface} (sources réelles, même jar) demande un swapchain à AU MOINS trois
 * images ({@code Math.max(3, surfaceCapabilities.minImageCount())}) — donc pas un défaut de
 * double-tampon. Le mode de présentation, lui, vient de {@code GpuSurface.Configuration
 * .presentMode()}, choisi par {@code Minecraft.renderFrame()} via {@code GpuSurface.PresentMode
 * .getSupportedVsyncMode(supportedModes, options.enableVsync().get())} — qui préfère
 * IMMEDIATE puis MAILBOX puis FIFO quand la synchronisation verticale est coupée, et qui ne
 * retombe sur FIFO que si le pilote ne rapporte AUCUN autre mode dans
 * {@code supportedPresentModes()}. {@code OptionInstance.set()} (vérifié : appelle
 * {@code onValueUpdate.valueChanged(...)} de façon synchrone dès que le jeu tourne) confirme que
 * {@code Glass#unchain} — qui appelle {@code enableVsync().set(false)} — déclenche bien
 * {@code invalidateSurfaceConfiguration()} et donc une reconfiguration réelle du swapchain avant
 * la mesure. La chaîne de code, de bout en bout, est câblée correctement et s'adapte déjà au
 * matériel : rien ici ne force un mode figé pour une carte donnée.
 *
 * <h2>Ce qui reste à confirmer EN JEU, et que ce mixin sert à observer</h2>
 *
 * <p>Aucune ligne du moteur ne journalise jamais le mode de présentation réellement choisi, ni
 * le temps passé dans {@code acquireNextTexture()}. Deux hypothèses restent possibles et ce
 * mixin les départage sans rien changer au comportement :
 *
 * <ul>
 *   <li>Le pilote de la carte utilisée pour le banc RING ne rapporte que FIFO dans
 *       {@code supportedPresentModes()} — auquel cas le blocage est un vrai plancher matériel
 *       de CETTE carte, et rien côté Lanterne ne peut l'éviter sans changer de mode de
 *       présentation par un autre biais (voir plus bas).</li>
 *   <li>Le pilote rapporte bien IMMEDIATE ou MAILBOX comme supportés, {@code presentMode()}
 *       vaut l'un des deux, et {@code acquireNextTexture()} bloque quand même — ce qui pointerait
 *       vers la composition de fenêtre du système d'exploitation (DWM sous Windows recompose
 *       une fenêtre non plein écran à sa propre cadence, INDÉPENDAMMENT du mode de présentation
 *       Vulkan demandé ; {@code run-cheptel/options.txt} et {@code run-vitrage/options.txt}
 *       ont tous deux {@code fullscreen:false}) plutôt que vers un mauvais choix de mode dans
 *       ce dépôt.</li>
 * </ul>
 *
 * <p>La distinction compte pour la suite : un vrai plancher matériel se contourne différemment
 * (accepter FIFO, ou réduire le travail par image comme {@code horizon} le fait déjà) qu'une
 * composition de fenêtre du système (plein écran exclusif, ou une réconciliation avec le
 * pilote). Le mixin ne tranche pas à l'avance entre les deux : il rapporte ce que CETTE machine,
 * avec CETTE carte, fait réellement — NVIDIA discret, AMD discret, ou iGPU auront chacun leurs
 * propres {@code supportedPresentModes()}, et rien ici ne suppose laquelle est en jeu.
 *
 * <h2>Pourquoi ce n'est PAS le correctif</h2>
 *
 * <p>Forcer un mode de présentation sans savoir lequel des deux cas ci-dessus s'applique serait
 * spéculatif : IMMEDIATE peut introduire du déchirement (tearing) que MAILBOX évite, une carte
 * plus modeste ou un pilote plus ancien peut ne supporter aucun des deux, et rien ici ne doit
 * jamais figer un choix qui ne s'adapte pas au matériel réel. Ce mixin ne modifie AUCUN
 * comportement de rendu ni de présentation — uniquement de la lecture et un journal, désactivé
 * par défaut.
 *
 * <h2>Relance de l'investigation — {@code acquireNextTexture()} disculpé, nouvelle cible</h2>
 *
 * <p>Le diagnostic ci-dessus a été exécuté : {@code acquireNextTexture()} coûte 0,02-0,04 ms en
 * moyenne, donc CE N'EST PAS lui. L'hypothèse initiale (blocage caché entre l'acquisition et le
 * chronomètre de rendu) est réfutée. Reste à expliquer où passent les ~25 ms restants du
 * compartiment « hors rendu ». Lecture de {@code Minecraft.renderFrame()} (mêmes sources
 * embarquées, jamais {@code .mcsrc}) : l'ordre réel des appels est
 * {@code acquireNextTexture()} → (chrono rendu démarre) → tick + {@code gameRenderer.render()}
 * + {@code blitFromTexture()} → (chrono rendu s'arrête, {@code frameTimeNs} calculé) →
 * {@code RenderSystem.getDevice().createCommandEncoder().submit()} → {@code windowSurface
 * .present()}. Donc DEUX appels, pas un seul, sont hors du chrono de rendu ET hors de
 * {@code acquireNextTexture()} : {@code submit()} et {@code present()}.
 *
 * <p>Lecture de {@code VulkanCommandEncoder.submit()} (mêmes sources, vérifié par
 * {@code javap -p} sur le jar patché : {@code public void submit()}, aucune surcharge) : cette
 * méthode signale un sémaphore <b>timeline</b> ({@code this.submitSemaphore}, créé avec
 * {@code VkSemaphoreTypeCreateInfo.semaphoreType(1)} = {@code VK_SEMAPHORE_TYPE_TIMELINE}, champ
 * confirmé par lecture directe du constructeur), soumet la file via
 * {@code vkQueueSubmit2KHR(..., VK_NULL_HANDLE)} — AUCUNE fence n'est utilisée nulle part dans
 * {@code VulkanQueue.Submission.close()}, vérifié par {@code javap} — puis appelle
 * {@code this.awaitSubmitCompletion(this.currentSubmitIndex - 2L, 5_000_000_000L)}
 * ({@code MAX_SUBMITS_IN_FLIGHT = 2}). Cette dernière méthode ({@code private boolean
 * awaitSubmitCompletion(long, long)}, signature confirmée par {@code javap -p}) appelle
 * {@code vkWaitSemaphores(...)} — un vrai appel Vulkan BLOQUANT côté CPU — pour attendre que le
 * GPU ait fini la soumission d'il y a deux images, avant de réinitialiser le pool de commandes
 * courant. <b>Ce moteur utilise déjà un sémaphore timeline pour cette synchronisation CPU/GPU</b>
 * — la feature Vulkan {@code timelineSemaphore} (Vulkan 1.2 core) est même dans
 * {@code VulkanFeatureSets.REQUIRED_FEATURESET}, donc obligatoire sur toute carte qui fait
 * tourner ce moteur, vérifié par lecture directe de {@code VulkanFeatureSets.java}. Il n'y a
 * AUCUNE fence ni sémaphore binaire à remplacer ici : la mission de remplacer « les fences
 * binaires classiques » par un sémaphore timeline autour de la présentation n'a pas de cible,
 * parce que ce point de synchronisation précis n'a jamais utilisé de fence binaire — seuls les
 * sémaphores {@code acquireSemaphores}/{@code presentSemaphores} de {@code VulkanGpuSurface} sont
 * binaires, et ils DOIVENT l'être : {@code vkAcquireNextImageKHR} et {@code vkQueuePresentKHR}
 * n'acceptent structurellement que des sémaphores binaires pour la synchronisation liée au
 * swapchain (extension {@code VK_KHR_swapchain} de base, sans {@code VK_KHR_present_wait} ni
 * {@code VK_EXT_swapchain_maintenance1}) — aucune substitution par un sémaphore timeline n'est
 * possible à cet endroit précis sans changer d'extension Vulkan, ce qui sort du périmètre de
 * cette relance.
 *
 * <p>Le vrai suspect devient donc {@code submit()} — plus précisément l'attente CPU explicite
 * dans {@code awaitSubmitCompletion()} — et non {@code present()} lui-même :
 * {@code VulkanGpuSurface.present()} (vérifié par {@code javap -c}) ne contient qu'un seul appel
 * natif, {@code vkQueuePresentKHR}, sans aucune synchronisation CPU explicite autour. Si le GPU
 * est structurellement plus lent que le CPU sous Vulkan pour cette scène (RING, dix mille vaches,
 * {@code horizon} actif), {@code awaitSubmitCompletion()} bloquera le thread de rendu jusqu'à ce
 * que le GPU rattrape la soumission d'il y a deux images — et ce blocage tombe très exactement
 * dans la fenêtre « hors rendu » que {@code Glass} mesure, hors de portée du chrono de rendu ET de
 * la mesure déjà faite sur {@code acquireNextTexture()}. Ce n'est pas un défaut de conception :
 * c'est le mécanisme de contrôle de la profondeur de file (deux soumissions en vol maximum) qui
 * fait honnêtement remonter un vrai retard GPU au thread CPU — mais ce retard doit être mesuré
 * avant de conclure quoi que ce soit.
 *
 * <p>Ce fichier ajoute donc la mesure de {@code present()} lui-même (ci-dessous), et un second
 * mixin, {@code VulkanSubmitDiagMixin} (classe séparée car cible {@code VulkanCommandEncoder},
 * pas {@code VulkanGpuSurface}), mesure {@code submit()} dans son ensemble ET isole le temps
 * passé dans {@code awaitSubmitCompletion()} — la seule façon de savoir, sans supposer, si le
 * coût vient de l'attente du sémaphore timeline (vrai retard GPU) ou d'ailleurs dans
 * {@code submit()} (empaquetage de la soumission, {@code vkQueueSubmit2KHR} lui-même, etc.).
 *
 * <h2>Protocole de vérification</h2>
 *
 * <p>{@code LANTERNE_VK_PRESENT_DIAG=1} avec {@code LANTERNE_GLASS=1}, {@code LANTERNE_GLASS_SCENE
 * =ring}, {@code LANTERNE_GLASS_HERD=10000} et {@code LANTERNE_MODULES=horizon} (mêmes réglages
 * que le banc RING qui a produit ×3,71 sous Vulkan). Une ligne
 * {@code [LANTERNE][VK-DIAG] Swapchain configuré} apparaît à chaque (re)configuration — rare,
 * seulement au démarrage et lors d'un redimensionnement — et donne le mode demandé et
 * l'ensemble des modes que le pilote rapporte comme supportés. Quatre lignes apparaissent ensuite
 * toutes les trois secondes pendant la mesure, chacune avec la moyenne et le pire temps sur la
 * fenêtre écoulée :
 *
 * <ul>
 *   <li>{@code [LANTERNE][VK-DIAG] acquireNextTexture} — déjà mesuré, sert de référence (attendu
 *       proche de zéro).</li>
 *   <li>{@code [LANTERNE][VK-DIAG] present} — le seul appel natif de {@code present()}
 *       ({@code vkQueuePresentKHR}). S'il est proche de zéro, {@code present()} est disculpé à
 *       son tour.</li>
 *   <li>{@code [LANTERNE][VK-DIAG] submit (total)} — le coût complet de {@code submit()}, appelé
 *       juste avant {@code present()} et juste après l'arrêt du chrono de rendu.</li>
 *   <li>{@code [LANTERNE][VK-DIAG] awaitSubmitCompletion} — le sous-ensemble de {@code submit()}
 *       passé dans l'attente bloquante du sémaphore timeline. Si ce chiffre s'approche des
 *       ~25,92 ms de « hors rendu » mesurés par {@code Glass} avec {@code horizon} actif (et que
 *       {@code submit (total)} lui est proche), la cause est confirmée : le GPU est en retard sur
 *       le CPU sous Vulkan pour cette scène, et le mécanisme de contrôle de la profondeur de file
 *       (deux soumissions en vol) le fait honnêtement apparaître comme un blocage CPU juste avant
 *       la présentation. Si {@code submit (total)} est grand mais {@code awaitSubmitCompletion}
 *       petit, le coût est ailleurs dans {@code submit()} (peu probable vu le code, mais à
 *       vérifier plutôt que supposer).</li>
 * </ul>
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanPresentDiagMixin {

    @Unique
    private static final boolean LANTERNE_VK_DIAG = "1".equals(System.getenv("LANTERNE_VK_PRESENT_DIAG"));

    /** Horodatage du début de l'appel en cours à {@code acquireNextTexture()}, ou zéro. */
    @Unique
    private long lanterne$acquireStartedAt;

    @Unique
    private long lanterne$accumNanos;

    @Unique
    private int lanterne$accumCount;

    @Unique
    private long lanterne$accumMaxNanos;

    /** Zéro tant qu'aucune fenêtre de trois secondes n'a encore été ouverte. */
    @Unique
    private long lanterne$lastLogAt;

    /** Horodatage du début de l'appel en cours à {@code present()}, ou zéro. */
    @Unique
    private long lanterne$presentStartedAt;

    @Unique
    private long lanterne$presentAccumNanos;

    @Unique
    private int lanterne$presentAccumCount;

    @Unique
    private long lanterne$presentAccumMaxNanos;

    /** Zéro tant qu'aucune fenêtre de trois secondes n'a encore été ouverte pour {@code present()}. */
    @Unique
    private long lanterne$presentLastLogAt;

    /**
     * Le mode demandé, et l'ensemble complet que CE pilote rapporte comme supporté — la seule
     * façon de savoir, sans supposer, si un blocage constaté vient d'un vrai plancher matériel
     * ou d'autre chose. Ne se déclenche qu'au (re)configuration du swapchain : au démarrage, et
     * lors d'un redimensionnement de fenêtre — jamais à chaque image.
     */
    @Inject(method = "configure", at = @At("RETURN"))
    private void lanterne$logPresentMode(GpuSurface.Configuration config, CallbackInfo ci) {
        if (!LANTERNE_VK_DIAG) {
            return;
        }
        VulkanGpuSurface self = (VulkanGpuSurface) (Object) this;
        Lanterne.LOG.info(
                "[LANTERNE][VK-DIAG] Swapchain configuré {}x{} — mode demandé={}, "
                        + "modes supportés par CE pilote={}.",
                config.width(), config.height(), config.presentMode(), self.supportedPresentModes());
    }

    @Inject(method = "acquireNextTexture", at = @At("HEAD"))
    private void lanterne$acquireStart(CallbackInfo ci) {
        if (!LANTERNE_VK_DIAG) {
            return;
        }
        this.lanterne$acquireStartedAt = System.nanoTime();
    }

    /**
     * N'est atteint que si {@code acquireNextTexture()} rend la main normalement — pas si elle
     * lève {@code SurfaceException} (swapchain périmé) ou {@code IllegalStateException} (timeout
     * GPU). {@code lanterne$acquireStart} se réarme de toute façon à chaque HEAD suivant, donc un
     * appel qui échoue ne fausse jamais la mesure de celui d'après.
     */
    @Inject(method = "acquireNextTexture", at = @At("RETURN"))
    private void lanterne$acquireEnd(CallbackInfo ci) {
        if (!LANTERNE_VK_DIAG || this.lanterne$acquireStartedAt == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - this.lanterne$acquireStartedAt;
        this.lanterne$acquireStartedAt = 0L;
        this.lanterne$accumNanos += elapsed;
        this.lanterne$accumCount++;
        if (elapsed > this.lanterne$accumMaxNanos) {
            this.lanterne$accumMaxNanos = elapsed;
        }

        long now = System.nanoTime();
        if (this.lanterne$lastLogAt == 0L) {
            this.lanterne$lastLogAt = now;
            return;
        }
        if (now - this.lanterne$lastLogAt < 3_000_000_000L) {
            return;
        }

        double avgMs = (this.lanterne$accumNanos / (double) this.lanterne$accumCount) / 1e6d;
        double maxMs = this.lanterne$accumMaxNanos / 1e6d;
        Lanterne.LOG.info(
                "[LANTERNE][VK-DIAG] acquireNextTexture : {} appel(s) sur les 3 dernières "
                        + "secondes, {} ms en moyenne, {} ms au pire.",
                this.lanterne$accumCount,
                String.format(Locale.ROOT, "%.3f", avgMs),
                String.format(Locale.ROOT, "%.3f", maxMs));

        this.lanterne$accumNanos = 0L;
        this.lanterne$accumCount = 0;
        this.lanterne$accumMaxNanos = 0L;
        this.lanterne$lastLogAt = now;
    }

    /**
     * Chronomètre l'unique appel natif de {@code present()} ({@code vkQueuePresentKHR}) — aucune
     * synchronisation CPU explicite n'entoure cet appel dans {@code VulkanGpuSurface.present()}
     * (vérifié par {@code javap -c} : un seul appel {@code vkQueuePresentKHR} entre le montage de
     * {@code VkPresentInfoKHR} et le traitement du code de retour). Si ce chiffre reste proche de
     * zéro, tout blocage éventuel se trouve dans {@code submit()} (voir
     * {@code VulkanSubmitDiagMixin}), pas ici.
     */
    @Inject(method = "present", at = @At("HEAD"))
    private void lanterne$presentStart(CallbackInfo ci) {
        if (!LANTERNE_VK_DIAG) {
            return;
        }
        this.lanterne$presentStartedAt = System.nanoTime();
    }

    @Inject(method = "present", at = @At("RETURN"))
    private void lanterne$presentEnd(CallbackInfo ci) {
        if (!LANTERNE_VK_DIAG || this.lanterne$presentStartedAt == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - this.lanterne$presentStartedAt;
        this.lanterne$presentStartedAt = 0L;
        this.lanterne$presentAccumNanos += elapsed;
        this.lanterne$presentAccumCount++;
        if (elapsed > this.lanterne$presentAccumMaxNanos) {
            this.lanterne$presentAccumMaxNanos = elapsed;
        }

        long now = System.nanoTime();
        if (this.lanterne$presentLastLogAt == 0L) {
            this.lanterne$presentLastLogAt = now;
            return;
        }
        if (now - this.lanterne$presentLastLogAt < 3_000_000_000L) {
            return;
        }

        double avgMs = (this.lanterne$presentAccumNanos / (double) this.lanterne$presentAccumCount) / 1e6d;
        double maxMs = this.lanterne$presentAccumMaxNanos / 1e6d;
        Lanterne.LOG.info(
                "[LANTERNE][VK-DIAG] present : {} appel(s) sur les 3 dernières secondes, {} ms "
                        + "en moyenne, {} ms au pire.",
                this.lanterne$presentAccumCount,
                String.format(Locale.ROOT, "%.3f", avgMs),
                String.format(Locale.ROOT, "%.3f", maxMs));

        this.lanterne$presentAccumNanos = 0L;
        this.lanterne$presentAccumCount = 0;
        this.lanterne$presentAccumMaxNanos = 0L;
        this.lanterne$presentLastLogAt = now;
    }
}
