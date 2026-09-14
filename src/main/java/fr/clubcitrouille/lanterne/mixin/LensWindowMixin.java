package fr.clubcitrouille.lanterne.mixin;

import com.mojang.blaze3d.platform.Window;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import fr.clubcitrouille.lanterne.core.Lens;

/**
 * La taille du cadre de rendu, minorée — et l'interface, compensée.
 *
 * <p>Le raisonnement, les préréglages et le compromis sont dans {@link Lens}.
 *
 * <h2>Pourquoi mentir sur la taille de la fenêtre plutôt que redimensionner la cible</h2>
 *
 * <p>La voie directe serait d'appeler {@code RenderTarget.resize} sur la cible principale. Elle
 * suppose de savoir <em>où</em> le jeu la redimensionne — et 26.1 n'a plus de {@code resizeDisplay}
 * dans {@code Minecraft} : ce point est passé dans le graphe d'images, dont les sources ne sont pas
 * décompilées dans ce dépôt.
 *
 * <p>Or tout ce qui dimensionne une cible de rendu finit par demander à la fenêtre sa taille.
 * Répondre plus petit suffit donc à réduire la cible, <b>sans avoir à trouver l'endroit</b>. Le jeu
 * étire ensuite lui-même l'image jusqu'à l'écran, puisque la présentation, elle, utilise la taille
 * réelle du système.
 *
 * <p>C'est aussi la méthode du mod qui traite déjà ce sujet en 26.1 — lue, comprise, et retenue
 * parce qu'elle est la seule qui ne dépende d'aucun détail interne du pipeline.
 *
 * <h2>Les deux réponses vont en sens inverse, et c'est voulu</h2>
 *
 * <p>{@code getWidth} et {@code getHeight} sont <b>minorées</b> : c'est le cadre où le monde est
 * peint. {@code getGuiScaledWidth} et {@code getGuiScaledHeight} sont <b>majorées</b> du même
 * facteur : l'interface est dessinée dans ce cadre rétréci, et sans cette compensation elle
 * rapetisserait avec lui. En les corrigeant, le HUD garde sa taille apparente une fois l'image
 * étirée.
 */
@Mixin(Window.class)
public abstract class LensWindowMixin {
    @Inject(method = "getWidth", at = @At("RETURN"), cancellable = true)
    private void lanterne$narrowWidth(CallbackInfoReturnable<Integer> callback) {
        if (Lens.active()) {
            callback.setReturnValue(Lens.shrink(callback.getReturnValue()));
        }
    }

    @Inject(method = "getHeight", at = @At("RETURN"), cancellable = true)
    private void lanterne$narrowHeight(CallbackInfoReturnable<Integer> callback) {
        if (Lens.active()) {
            callback.setReturnValue(Lens.shrink(callback.getReturnValue()));
        }
    }

    @Inject(method = "getGuiScaledWidth", at = @At("RETURN"), cancellable = true)
    private void lanterne$keepGuiWidth(CallbackInfoReturnable<Integer> callback) {
        if (Lens.active()) {
            callback.setReturnValue(Lens.stretch(callback.getReturnValue()));
        }
    }

    @Inject(method = "getGuiScaledHeight", at = @At("RETURN"), cancellable = true)
    private void lanterne$keepGuiHeight(CallbackInfoReturnable<Integer> callback) {
        if (Lens.active()) {
            callback.setReturnValue(Lens.stretch(callback.getReturnValue()));
        }
    }
}
