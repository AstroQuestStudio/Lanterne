package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.pipeline.RenderTarget;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;

import fr.clubcitrouille.lanterne.client.upscale.Scene;
import fr.clubcitrouille.lanterne.client.upscale.Swell;
import fr.clubcitrouille.lanterne.client.upscale.Upscale;

/**
 * L'échange : le monde dans une toile réduite, l'interface sur la vraie cible.
 *
 * <h2>La couture, et pourquoi elle est exactement là</h2>
 *
 * <p>{@code GameRenderer.render} est une méthode linéaire dont la lecture donne la place des deux
 * points d'accroche sans avoir à chercher :
 *
 * <pre>
 * si la fenêtre a changé de taille  →  resize(cible principale)     ← doit voir la VRAIE cible
 * nettoyage couleur et profondeur   →  cible principale             ← la vraie : elle portera l'ATHU
 * si un monde est chargé {
 *     renderLevel(…)                                                ← ici commence la toile réduite
 *     contour des créatures lumineuses
 *     effet de vue (creeper, araignée, enderman)
 * }
 * fogRenderer.endFrame()                                            ← ici finit la toile réduite
 * nettoyage de la profondeur
 * guiRenderer.render()                                              ← l'ATHU, en résolution native
 * </pre>
 *
 * <p>L'échange encadre donc le bloc « monde », et rien d'autre. Ce découpage n'a pas été choisi : il
 * était déjà écrit dans le code, il suffisait de le lire.
 *
 * <h2>Pourquoi échanger le champ et non détourner les lectures</h2>
 *
 * <p>{@code mainRenderTarget} est lu à une vingtaine d'endroits, dans neuf classes — le rendu de
 * niveau, les nuages, la bordure du monde, le réticule de débogage, les particules, le groupe de
 * couches de chunk, la capture d'écran, l'interface, la présentation à l'écran. Détourner ces
 * lectures une par une demanderait autant de mixins, dont plusieurs sur des chemins très chauds, et
 * chacun serait une occasion d'en oublier un.
 *
 * <p>Échanger le champ le temps du monde les traite <b>tous à la fois, et correctement</b> : chacun
 * reçoit la cible qui convient à la phase où il s'exécute. C'est aussi ce qui rend le module
 * indifférent aux mods qui lisent cette même cible.
 *
 * <h2>La restauration en tête de méthode, et ce qu'elle rattrape</h2>
 *
 * <p>Un mixin ne peut pas envelopper du code de vanilla dans un {@code try / finally}. Si
 * {@code renderLevel} lève une exception, la seconde accroche n'est jamais atteinte et le champ
 * reste sur la toile réduite — le jeu présenterait alors une image sans interface, ou une image
 * d'une taille que la fenêtre n'attend pas.
 *
 * <p>La restauration en tête d'image rend ce cas borné à <b>une seule image</b>, et elle est
 * gratuite le reste du temps : une comparaison de référence à nul.
 */
@Mixin(GameRenderer.class)
public abstract class UpscaleGameRendererMixin {
    @Shadow
    @Final
    @Mutable
    private RenderTarget mainRenderTarget;

    @Shadow
    public abstract GameRenderState gameRenderState();

    /** La vraie cible, mise de côté pendant que le monde se dessine. Nulle le reste du temps. */
    @Unique
    private RenderTarget lanterne$screen;

    @Unique
    private long lanterne$lastFrame;

    @Inject(method = "render", at = @At("HEAD"))
    private void lanterne$restoreAndBeat(DeltaTracker deltaTracker, boolean advanceGameTime,
            CallbackInfo callback) {
        if (this.lanterne$screen != null) {
            this.mainRenderTarget = this.lanterne$screen;
            this.lanterne$screen = null;
        }

        long now = System.nanoTime();
        if (Upscale.swell() && this.lanterne$lastFrame != 0L) {
            Swell.beat(now - this.lanterne$lastFrame, lanterne$targetFrameTime());
        }
        this.lanterne$lastFrame = now;
    }

    /**
     * La durée d'image visée par la houle.
     *
     * <h2>Pourquoi soixante par défaut, et pourquoi le plafond du joueur prime</h2>
     *
     * <p>Un joueur qui a posé un plafond de taux d'images a déjà dit ce qu'il voulait ; viser plus
     * haut ferait dégrader sa résolution pour des images que le limiteur jette. Sans plafond — la
     * valeur 260 signifie « illimité » dans {@code Minecraft.renderFrame} — on vise soixante, parce
     * que c'est le seuil au-delà duquel un joueur ne troque plus volontiers de la netteté contre de
     * la fluidité.
     *
     * <p>Conséquence assumée : sur un écran à 144 Hz sans plafond déclaré, la houle ne s'armera que
     * sous soixante images par seconde. C'est le sens d'erreur qu'on préfère — ne rien dégrader
     * quand ce n'était pas nécessaire.
     */
    @Unique
    private double lanterne$targetFrameTime() {
        int limit = this.gameRenderState().framerateLimit;
        if (limit <= 0 || limit >= 260) {
            return 1000.0d / 60.0d;
        }
        return 1000.0d / limit;
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void lanterne$shrink(DeltaTracker deltaTracker, boolean advanceGameTime,
            CallbackInfo callback) {
        RenderTarget scene = Scene.borrow(this.mainRenderTarget);
        if (scene == null) {
            return;
        }
        this.lanterne$screen = this.mainRenderTarget;
        this.mainRenderTarget = scene;
    }

    /**
     * Fin du monde, retour à la vraie cible, puis remontée d'échelle.
     *
     * <p>Le nettoyage de la profondeur et l'interface viennent <b>après</b> cette accroche : ils
     * travaillent donc sur la cible principale, à la taille de la fenêtre, comme si de rien n'était.
     * La profondeur du monde, elle, reste dans la toile et n'est pas remontée — personne ne la lit
     * après cet endroit, le jeu l'efface à la ligne suivante.
     */
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void lanterne$grow(DeltaTracker deltaTracker, boolean advanceGameTime,
            CallbackInfo callback) {
        RenderTarget screen = this.lanterne$screen;
        if (screen == null) {
            return;
        }
        RenderTarget scene = this.mainRenderTarget;
        this.mainRenderTarget = screen;
        this.lanterne$screen = null;
        Scene.give(screen, scene);
    }
}
