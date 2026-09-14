package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

import fr.clubcitrouille.lanterne.client.Dials;

/**
 * La porte d'entrée : un bouton vers les cadrans, posé dans les options vidéo.
 *
 * <h2>Pourquoi là et pas dans la liste des mods</h2>
 *
 * <p>NeoForge offre un écran de configuration atteignable depuis la liste des mods. C'est l'endroit
 * conventionnel, et c'est aussi celui que personne n'ouvre : un joueur qui trouve son jeu lent va
 * dans <b>Options → Graphismes</b>, pas dans un catalogue de mods.
 *
 * <p>Le bouton est donc posé là où la question se pose. Il porte le nom du mod et non un intitulé
 * générique, pour qu'on sache d'où viennent les réglages qu'on va changer.
 *
 * <h2>Une injection, et rien d'autre</h2>
 *
 * <p>Le mod n'enlève ni ne déplace aucune option de vanilla. Il ajoute une ligne à la fin de la
 * liste existante. Un écran de réglages qui réorganise ceux du jeu oblige son utilisateur à
 * réapprendre ce qu'il savait — et le premier à s'en plaindre serait celui qui vient seulement
 * régler sa distance de vue.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoOptionsMixin extends OptionsSubScreen {
    private VideoOptionsMixin() {
        super(null, null, null);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void lanterne$addDoor(CallbackInfo callback) {
        OptionsList list = this.list;
        if (list == null) {
            return;
        }
        list.addSmall(Button.builder(
                Component.literal("🎃 Lanterne — rendu"),
                pressed -> Minecraft.getInstance().setScreen(
                        new Dials((VideoSettingsScreen) (Object) this)))
                .width(150)
                .build(), null);
    }
}
