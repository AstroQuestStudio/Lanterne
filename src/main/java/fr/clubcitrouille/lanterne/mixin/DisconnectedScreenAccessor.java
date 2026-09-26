package fr.clubcitrouille.lanterne.mixin;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lire la raison et l'écran parent d'un écran de déconnexion — voir {@code client.MecheFilet}. */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {
    @Accessor("details")
    DisconnectionDetails lanterne$details();

    @Accessor("parent")
    Screen lanterne$parent();
}
