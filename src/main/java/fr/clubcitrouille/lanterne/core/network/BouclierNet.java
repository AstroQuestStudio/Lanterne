package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Config;

/**
 * Le branchement réseau du bouclier : un seul paquet, OPTIONNEL (un client d'avant ce paquet doit
 * pouvoir se connecter, et donc se mettre à jour par la Mèche), annoncé à chaque connexion.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class BouclierNet {
    private static final String PROTOCOL = "1";

    private BouclierNet() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(BouclierNet::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar(PROTOCOL).optional().playToClient(BouclierRegle.TYPE, BouclierRegle.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        fr.clubcitrouille.lanterne.client.BouclierClient.accept(payload)));
    }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        try {
            player.connection.send(new BouclierRegle(Config.BOUCLIER_OBLIGATOIRE.get()));
        } catch (RuntimeException clientSansCanal) {
            // Client d'avant ce paquet : il garde son propre réglage, rien de plus à faire.
        }
    }
}
