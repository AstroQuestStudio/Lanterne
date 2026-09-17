package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Souvenir;

/**
 * Le branchement réseau de {@link Souvenir} — enregistrement des quatre paquets, et les deux moments
 * qui n'ont pas leur place dans une méthode d'envoi : l'arrivée d'un joueur (on lui dit qui est ce
 * monde) et son départ (on oublie ce qu'il prétendait connaître).
 *
 * <h2>Un protocole optionnel, et ce que cela veut dire ici</h2>
 *
 * <p>Un client qui n'a pas ce mod ne comprendra jamais {@link SouvenirHello} : NeoForge refuse
 * d'envoyer à une connexion un paquet dont le canal n'a pas été négocié — voir
 * {@code NetworkRegistryMixin} pour la preuve, lue dans {@code NetworkRegistry.checkPacket}, que ce
 * refus lève une {@code UnsupportedOperationException} avant tout envoi. On s'en protège donc : un
 * échec à l'arrivée ne doit jamais empêcher le joueur de continuer, seulement le laisser sans ce
 * module — c'est-à-dire exactement son expérience vanilla, cache ou pas.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class SouvenirNet {
    /** Version du protocole — même convention que {@code Waypoints.PROTOCOL}. */
    private static final String PROTOCOL = "1";

    private SouvenirNet() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(SouvenirNet::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        registrar.playToClient(SouvenirHello.TYPE, SouvenirHello.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        fr.clubcitrouille.lanterne.client.SouvenirClient.acceptHello(payload.worldId())));
        registrar.playToClient(SouvenirKept.TYPE, SouvenirKept.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        fr.clubcitrouille.lanterne.client.SouvenirClient.acceptKept(payload)));
        registrar.playToClient(SouvenirRevision.TYPE, SouvenirRevision.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        fr.clubcitrouille.lanterne.client.SouvenirClient.acceptRevision(payload)));
        registrar.playToServer(SouvenirManifest.TYPE, SouvenirManifest.STREAM_CODEC, SouvenirNet::onManifest);
    }

    private static void onManifest(SouvenirManifest manifest, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !Settings.souvenir()) {
                return;
            }
            Souvenir.recordManifest(player.getUUID(), manifest.dimension(), manifest.entries());
        });
    }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !Settings.souvenir()) {
            return;
        }
        var server = player.level().getServer();
        if (server == null) {
            return;
        }
        try {
            PacketDistributor.sendToPlayer(player, new SouvenirHello(Souvenir.worldId(server)));
        } catch (RuntimeException clientWithoutChannel) {
            // Voir la javadoc de classe : un client vanilla, ou une autre version du protocole, ne
            // reçoit rien — il continue exactement comme si ce module n'existait pas.
        }
    }

    @SubscribeEvent
    public static void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Souvenir.forgetPlayer(player.getUUID());
        }
    }
}
