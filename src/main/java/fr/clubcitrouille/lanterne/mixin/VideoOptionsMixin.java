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
 *
 * <h2>Quand Sodium est là, cette injection ne s'exécute pas — et c'est très bien</h2>
 *
 * <p>Sodium, Chloride et leurs semblables ne <em>complètent</em> pas l'écran vidéo de vanilla : ils
 * le <b>remplacent</b>. Le mixin de Sodium se pose sur {@code OptionsScreen.lambda$init$3}, la
 * fabrique qui construit {@code VideoSettingsScreen} au clic sur « Graphismes », en {@code HEAD} et
 * {@code cancellable}. L'écran de vanilla n'est donc jamais construit, {@code addOptions} n'est
 * jamais appelé, et le bouton ci-dessous n'est jamais posé. La seconde porte est alors
 * {@code client/sodium/Graft}, qui inscrit la même entrée chez Sodium.
 *
 * <p>Il n'y a donc <b>rien à garder ici</b> : les deux portes ne peuvent pas s'ouvrir en même temps,
 * puisque chacune dépend d'un écran que l'autre exclut. Ajouter un {@code ModList.isLoaded("sodium")}
 * serait une condition qui ne change jamais de valeur — et qui casserait le seul cas où elle
 * compterait vraiment : un joueur peut désarmer le mixin d'interface de Sodium dans
 * {@code config/sodium-mixins.properties}. L'écran de vanilla revient alors, et ce bouton est la
 * seule porte qui reste.
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
                Dials.DOOR,
                pressed -> Minecraft.getInstance().gui.setScreen(
                        new Dials((VideoSettingsScreen) (Object) this)))
                .width(150)
                .build(), null);
    }
}
