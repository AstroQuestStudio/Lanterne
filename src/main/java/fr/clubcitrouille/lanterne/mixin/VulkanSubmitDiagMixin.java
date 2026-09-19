package fr.clubcitrouille.lanterne.mixin;

import java.util.Locale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Diagnostic temporaire, jamais un correctif — compagnon de {@link VulkanPresentDiagMixin}, voir
 * sa javadoc pour le contexte complet de l'enquête (banc RING, dix mille vaches, {@code horizon}
 * actif, écart ×3,71 « hors rendu » Vulkan contre OpenGL).
 *
 * <p>{@code acquireNextTexture()} a été disculpé (0,02-0,04 ms en moyenne). Lecture de
 * {@code Minecraft.renderFrame()} (sources embarquées du jar patché, jamais {@code .mcsrc}) :
 * entre l'arrêt du chrono de rendu ({@code frameTimeNs}) et {@code windowSurface.present()}, un
 * appel invisible au chrono de rendu et jamais mesuré jusqu'ici a lieu :
 * {@code RenderSystem.getDevice().createCommandEncoder().submit()}, qui résout en
 * {@code VulkanCommandEncoder.submit()} (confirmé {@code public void submit()} par
 * {@code javap -p}, aucune surcharge).
 *
 * <p>Lecture du vrai code de {@code submit()} : il signale le sémaphore <b>timeline</b>
 * {@code this.submitSemaphore} (créé avec {@code VK_SEMAPHORE_TYPE_TIMELINE}, vérifié dans le
 * constructeur), soumet via {@code vkQueueSubmit2KHR(..., VK_NULL_HANDLE)} — aucune fence,
 * vérifié par {@code javap} sur {@code VulkanQueue$Submission.close()} — puis appelle
 * {@code awaitSubmitCompletion(currentSubmitIndex - 2L, 5_000_000_000L)}
 * ({@code MAX_SUBMITS_IN_FLIGHT = 2}), qui elle-même appelle {@code vkWaitSemaphores(...)} : un
 * vrai appel Vulkan bloquant côté CPU, qui attend que le GPU ait fini la soumission d'il y a deux
 * images. Ce moteur utilise donc DÉJÀ un sémaphore timeline pour cette synchronisation — la
 * feature Vulkan {@code timelineSemaphore} est obligatoire dans
 * {@code VulkanFeatureSets.REQUIRED_FEATURESET}, vérifié par lecture directe. Il n'y a aucune
 * fence binaire à remplacer ici.
 *
 * <p>Ce mixin isole deux mesures pour savoir, sans supposer, où va le temps à l'intérieur de
 * {@code submit()} :
 *
 * <ul>
 *   <li>{@code submit (total)} : le coût complet de la méthode.</li>
 *   <li>{@code awaitSubmitCompletion} : le sous-ensemble passé dans l'attente bloquante du
 *       sémaphore timeline (méthode privée, signature {@code private boolean
 *       awaitSubmitCompletion(long, long)} confirmée par {@code javap -p}). Cette méthode est
 *       aussi appelée depuis {@code GpuFence.awaitCompletion()} ailleurs dans le moteur (lectures
 *       GPU différées, captures d'écran) — dans le protocole de banc RING ci-dessous, sans
 *       lecture ni capture en vol, les seuls appels attendus sont ceux de {@code submit()},
 *       un par image.</li>
 * </ul>
 *
 * <p>Si {@code awaitSubmitCompletion} s'approche des ~25,92 ms de « hors rendu » mesurés par
 * {@code Glass} avec {@code horizon} actif, la cause est confirmée : le GPU est en retard sur le
 * CPU sous Vulkan pour cette scène, et le contrôle de profondeur de file (deux soumissions en vol
 * maximum) fait honnêtement remonter ce retard comme un blocage CPU juste avant la présentation —
 * pas un défaut de synchronisation à corriger, mais un vrai coût GPU à réduire autrement (moins de
 * travail par image, ou tolérer une file plus profonde au prix de la latence).
 *
 * <p>Désactivé par défaut, comme {@link VulkanPresentDiagMixin} : {@code
 * LANTERNE_VK_PRESENT_DIAG=1}.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanSubmitDiagMixin {

    @Unique
    private static final boolean LANTERNE_VK_DIAG = "1".equals(System.getenv("LANTERNE_VK_PRESENT_DIAG"));

    @Unique
    private long lanterne$submitStartedAt;

    @Unique
    private long lanterne$submitAccumNanos;

    @Unique
    private int lanterne$submitAccumCount;

    @Unique
    private long lanterne$submitAccumMaxNanos;

    @Unique
    private long lanterne$submitLastLogAt;

    @Unique
    private long lanterne$awaitStartedAt;

    @Unique
    private long lanterne$awaitAccumNanos;

    @Unique
    private int lanterne$awaitAccumCount;

    @Unique
    private long lanterne$awaitAccumMaxNanos;

    @Unique
    private long lanterne$awaitLastLogAt;

    @Inject(method = "submit", at = @At("HEAD"))
    private void lanterne$submitStart(CallbackInfo ci) {
        if (!LANTERNE_VK_DIAG) {
            return;
        }
        this.lanterne$submitStartedAt = System.nanoTime();
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private void lanterne$submitEnd(CallbackInfo ci) {
        if (!LANTERNE_VK_DIAG || this.lanterne$submitStartedAt == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - this.lanterne$submitStartedAt;
        this.lanterne$submitStartedAt = 0L;
        this.lanterne$submitAccumNanos += elapsed;
        this.lanterne$submitAccumCount++;
        if (elapsed > this.lanterne$submitAccumMaxNanos) {
            this.lanterne$submitAccumMaxNanos = elapsed;
        }

        long now = System.nanoTime();
        if (this.lanterne$submitLastLogAt == 0L) {
            this.lanterne$submitLastLogAt = now;
            return;
        }
        if (now - this.lanterne$submitLastLogAt < 3_000_000_000L) {
            return;
        }

        double avgMs = (this.lanterne$submitAccumNanos / (double) this.lanterne$submitAccumCount) / 1e6d;
        double maxMs = this.lanterne$submitAccumMaxNanos / 1e6d;
        Lanterne.LOG.info(
                "[LANTERNE][VK-DIAG] submit (total) : {} appel(s) sur les 3 dernières secondes, "
                        + "{} ms en moyenne, {} ms au pire.",
                this.lanterne$submitAccumCount,
                String.format(Locale.ROOT, "%.3f", avgMs),
                String.format(Locale.ROOT, "%.3f", maxMs));

        this.lanterne$submitAccumNanos = 0L;
        this.lanterne$submitAccumCount = 0;
        this.lanterne$submitAccumMaxNanos = 0L;
        this.lanterne$submitLastLogAt = now;
    }

    /**
     * {@code awaitSubmitCompletion} est privée et renvoie {@code boolean} — la signature exacte,
     * {@code private boolean awaitSubmitCompletion(long, long)}, a été confirmée par
     * {@code javap -p} sur le jar patché avant d'écrire cette injection.
     */
    @Inject(method = "awaitSubmitCompletion", at = @At("HEAD"))
    private void lanterne$awaitStart(long submitIndex, long timeoutNS, CallbackInfoReturnable<Boolean> cir) {
        if (!LANTERNE_VK_DIAG) {
            return;
        }
        this.lanterne$awaitStartedAt = System.nanoTime();
    }

    @Inject(method = "awaitSubmitCompletion", at = @At("RETURN"))
    private void lanterne$awaitEnd(long submitIndex, long timeoutNS, CallbackInfoReturnable<Boolean> cir) {
        if (!LANTERNE_VK_DIAG || this.lanterne$awaitStartedAt == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - this.lanterne$awaitStartedAt;
        this.lanterne$awaitStartedAt = 0L;
        this.lanterne$awaitAccumNanos += elapsed;
        this.lanterne$awaitAccumCount++;
        if (elapsed > this.lanterne$awaitAccumMaxNanos) {
            this.lanterne$awaitAccumMaxNanos = elapsed;
        }

        long now = System.nanoTime();
        if (this.lanterne$awaitLastLogAt == 0L) {
            this.lanterne$awaitLastLogAt = now;
            return;
        }
        if (now - this.lanterne$awaitLastLogAt < 3_000_000_000L) {
            return;
        }

        double avgMs = (this.lanterne$awaitAccumNanos / (double) this.lanterne$awaitAccumCount) / 1e6d;
        double maxMs = this.lanterne$awaitAccumMaxNanos / 1e6d;
        Lanterne.LOG.info(
                "[LANTERNE][VK-DIAG] awaitSubmitCompletion (attente sémaphore timeline) : {} "
                        + "appel(s) sur les 3 dernières secondes, {} ms en moyenne, {} ms au pire.",
                this.lanterne$awaitAccumCount,
                String.format(Locale.ROOT, "%.3f", avgMs),
                String.format(Locale.ROOT, "%.3f", maxMs));

        this.lanterne$awaitAccumNanos = 0L;
        this.lanterne$awaitAccumCount = 0;
        this.lanterne$awaitAccumMaxNanos = 0L;
        this.lanterne$awaitLastLogAt = now;
    }
}
