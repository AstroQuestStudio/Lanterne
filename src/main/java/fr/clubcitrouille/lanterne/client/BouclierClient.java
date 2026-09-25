package fr.clubcitrouille.lanterne.client;

import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Bouclier;
import fr.clubcitrouille.lanterne.core.network.BouclierRegle;

/**
 * Le côté client du bouclier : applique la règle du serveur, recharge les ressources si (et seulement
 * si) le bouclier était coupé localement alors que le serveur l'impose, et prévient une fois le joueur
 * quand un de ses packs a été partiellement ignoré.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class BouclierClient {
    private static boolean prevenu;

    private BouclierClient() {}

    public static void accept(BouclierRegle regle) {
        Bouclier.imposer(regle.obligatoire());
        Minecraft mc = Minecraft.getInstance();
        if (Bouclier.voulu() && !Bouclier.actif()) {
            Lanterne.LOG.info("[BOUCLIER] imposé par le serveur alors qu'il était coupé ici : "
                    + "rechargement des ressources.");
            mc.reloadResourcePacks();
        }
        Set<String> refuses = Bouclier.packsRefuses();
        if (!prevenu && !refuses.isEmpty() && mc.player != null) {
            prevenu = true;
            mc.player.sendSystemMessage(Component.literal("[Bouclier] "
                    + (refuses.size() == 1 ? "Ton pack " : "Tes packs ") + String.join(", ", refuses)
                    + " rendai" + (refuses.size() == 1 ? "t" : "ent")
                    + " des blocs de terrain transparents : ces éléments sont ignorés "
                    + "(le reste du pack reste actif).")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        Bouclier.imposer(false);
        prevenu = false;
    }
}
