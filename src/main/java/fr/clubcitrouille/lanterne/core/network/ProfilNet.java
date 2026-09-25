package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.ProfilReception;

/**
 * Le branchement réseau des profils de performance — même charpente que {@link BoiteNoireNet}, voir sa
 * Javadoc pour la preuve qu'un canal OPTIONNEL non négocié échoue à l'envoi sans jamais couper la
 * connexion, et pour pourquoi ce fichier ne référence aucune classe {@code client.*}.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class ProfilNet {
    private static final String PROTOCOL = "1";

    private ProfilNet() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(ProfilNet::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL).optional();
        registrar.playToServer(ProfilTranche.TYPE, ProfilTranche.STREAM_CODEC,
                (tranche, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        ProfilReception.onChunk(player, tranche);
                    }
                }));
    }

    @SubscribeEvent
    public static void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        ProfilReception.forget(event.getEntity().getUUID());
    }
}
