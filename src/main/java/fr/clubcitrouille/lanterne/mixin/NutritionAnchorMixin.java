package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;

import fr.clubcitrouille.lanterne.client.regard.Nutrition;

/**
 * Capture, sans rien changer, les coordonnées que vanilla utilise pour poser la barre de faim.
 *
 * <h2>Pourquoi ce point d'accroche précis</h2>
 *
 * <p>{@code Hud.extractFood(graphics, joueur, haut, droite)} — lu par {@code javap} sur le vrai jar
 * patché — est appelée UNE FOIS par image avec les deux entiers que vanilla vient de calculer dans
 * {@code extractFoodLevel} : {@code droite = largeurEcran/2 + 91} et {@code haut = hauteurEcran -
 * rightHeight}, ce dernier dépendant d'éléments empilés au-dessus (vie de monture, niveau d'air…) et
 * donc <b>impossible à recalculer de l'extérieur sans deviner</b>. Les lire ici, au moment exact où
 * vanilla les a déjà déterminés, évite justement de deviner.
 *
 * <h2>Pur espionnage, aucune altération</h2>
 *
 * <p>Une seule injection en {@code HEAD}, qui ne lit que les arguments et ne modifie ni ne retourne
 * rien : {@link Nutrition#noteAnchor} ne fait que mémoriser trois valeurs pour le calque de rendu de
 * {@code client.regard.Nutrition}, qui dessine sa prévisualisation par-dessus à l'aide de ces mêmes
 * coordonnées — jamais de coordonnées recalculées à la main, qui auraient divergé au moindre autre
 * élément de HUD activé (monture, air, armure).
 */
@Mixin(Hud.class)
public abstract class NutritionAnchorMixin {

    @Inject(
            method = "extractFood(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/entity/player/Player;II)V",
            at = @At("HEAD"))
    private void lanterne$noteFoodAnchor(GuiGraphicsExtractor graphics, Player player, int top,
            int right, CallbackInfo ci) {
        Nutrition.noteAnchor(player, top, right);
    }
}
