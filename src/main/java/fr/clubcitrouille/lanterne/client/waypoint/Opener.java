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
        if (!asked) {
            return;
        }
        // <h2>Le carnet est un objet, pas une touche</h2>
        //
        // Sans lui, la touche ne fait rien. C'est ce qui distingue un repère d'une fonctionnalité
        // d'interface : on le fabrique, on le perd à la mort, on peut le donner. Un carnet gratuit
        // n'aurait aucun poids, et un système de repères sans poids finit en liste de courses.
        //
        // Le contrôle est FAIT CÔTÉ CLIENT pour l'ouverture seule — il n'a pas à être sûr, il a à
        // être clair. Le serveur, lui, ne fait confiance à rien : c'est lui qui refusera la pose.
        if (!hasBook(client)) {
            client.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(
                    "Il te faut un Carnet de repères.")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            return;
        }
        client.setScreen(new Board());
    }

    /** Le carnet est-il quelque part dans l'inventaire ? Le tenir en main n'est pas exigé. */
    private static boolean hasBook(Minecraft client) {
        if (client.player == null) {
            return false;
        }
        if (client.player.isCreative()) {
            return true;
        }
        for (var stack : client.player.getInventory().getNonEquipmentItems()) {
            if (stack.is(fr.clubcitrouille.lanterne.content.Contents.CARNET.get())) {
                return true;
            }
        }
        return false;
    }
}
