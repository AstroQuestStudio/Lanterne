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
 * <h2>Protocole de vérification</h2>
 *
 * <p>{@code LANTERNE_VK_PRESENT_DIAG=1} avec {@code LANTERNE_GLASS=1} et
 * {@code LANTERNE_MODULES=horizon} (mêmes réglages que le banc RING qui a produit ×3,71 sous
 * Vulkan). Une ligne {@code [LANTERNE][VK-DIAG] Swapchain configuré} apparaît à chaque
 * (re)configuration — rare, seulement au démarrage et lors d'un redimensionnement — et donne
 * le mode demandé et l'ensemble des modes que le pilote rapporte comme supportés. Une ligne
 * {@code [LANTERNE][VK-DIAG] acquireNextTexture} apparaît toutes les trois secondes pendant la
 * mesure, avec la moyenne et le pire temps passé dans cet appel sur la fenêtre écoulée : si ce
 * chiffre s'approche des ~25,92 ms de « hors rendu » mesurés par {@code Glass} avec
 * {@code horizon} actif, la cause est confirmée à l'endroit précis prévu par cette javadoc.
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
}
