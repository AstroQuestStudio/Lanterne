package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.BoiteNoirePlantages;

/**
 * Le branchement réseau de la Boîte noire — un seul canal, client vers serveur, optionnel. Même
 * charpente que {@link MecheNet} pour la garde d'optionnalité : voir sa Javadoc de classe pour la
 * preuve qu'un canal non négocié fait échouer l'envoi, jamais la connexion.
 *
 * <h2>Aucune classe client n'est référencée ici</h2>
 *
 * <p>Contrairement à {@link MecheNet}, ce fichier ne pousse jamais de paquet VERS le client : il ne
 * fait donc référence à aucune classe de {@code client.crash} — celles-ci ne sont chargées que depuis
 * le bloc client de {@code Lanterne} et l'annotation {@code @EventBusSubscriber(value = Dist.CLIENT)}
 * de {@code BoiteNoire}. Le gestionnaire ci-dessous n'appelle que {@link BoiteNoirePlantages}, une
 * classe purement serveur qui ne nomme ni {@code Minecraft} ni {@code Screen}.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class BoiteNoireNet {
    private static final String PROTOCOL = "1";

    private BoiteNoireNet() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(BoiteNoireNet::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        // OPTIONNEL : un client ou un serveur d'avant ce mécanisme doit pouvoir se connecter l'un à
        // l'autre sans être refusé pour un canal manquant — même raison que MecheNet.
        var registrar = event.registrar(PROTOCOL).optional();
        registrar.playToServer(BoiteNoireTranche.TYPE, BoiteNoireTranche.STREAM_CODEC,
                (tranche, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        BoiteNoirePlantages.onChunk(player, tranche);
                    }
                }));
    }

    @SubscribeEvent
    public static void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        BoiteNoirePlantages.forget(event.getEntity().getUUID());
    }
}
