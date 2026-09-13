package fr.clubcitrouille.lanterne.client.waypoint;

import net.minecraft.client.Minecraft;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La touche qui ouvre le carnet.
 *
 * <p>{@code consumeClick} dépile les appuis en attente : l'interroger dans une boucle tant qu'il rend
 * vrai est ce qui empêche un appui maintenu d'ouvrir et refermer l'écran vingt fois par seconde.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class Opener {
    private Opener() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.screen != null) {
            return;
        }
        boolean asked = false;
        while (Compass.OPEN.consumeClick()) {
            asked = true;
        }
        if (asked) {
            client.setScreen(new Board());
        }
    }
}
