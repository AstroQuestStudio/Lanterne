package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Meche;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le branchement réseau de {@link Meche} — enregistrement des trois paquets, l'annonce à la connexion,
 * le nettoyage au départ, et le pompage à chaque tick. Même charpente que {@code SouvenirNet} : voir
 * sa Javadoc de classe pour la preuve, lue dans {@code NetworkRegistry.checkPacket} via
 * {@code NetworkRegistryMixin}, qu'envoyer à une connexion un paquet dont le canal n'a pas été négocié
 * lève une {@code UnsupportedOperationException} avant tout envoi.
 *
 * <h2>Pourquoi {@link #onJoin} garde son essai malgré un mod obligatoire</h2>
 *
 * <p>Un premier réflexe dirait que cette garde ne sert à rien ici : Lanterne est un mod client+serveur
 * OBLIGATOIRE, donc un joueur connecté le fait forcément tourner. C'est vrai — et c'est <em>hors
 * sujet</em>. Le client peut très bien faire tourner Lanterne dans une version <b>antérieure à
 * l'ajout de ce mécanisme</b>, qui n'enregistre donc PAS ces trois paquets. C'est précisément le
 * client que ce mécanisme existe pour aider — et il serait absurde qu'il le fasse planter à la
 * connexion au lieu de le laisser simplement jouer sans être prévenu, une fois de plus, jusqu'à ce
 * qu'il installe une version qui comprend {@code MecheManifest}.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class MecheNet {
    /**
     * Version du protocole. Voir la Javadoc de {@link MecheManifest} : ce paquet précis doit rester
     * lisible par un client ancien plus longtemps que le reste — c'est lui qui doit pouvoir dire à ce
     * client qu'il est en retard.
     */
    private static final String PROTOCOL = "1";

    private MecheNet() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(MecheNet::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        registrar.playToClient(MecheManifest.TYPE, MecheManifest.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        fr.clubcitrouille.lanterne.client.MecheClient.acceptManifest(payload)));
        registrar.playToClient(MecheChunk.TYPE, MecheChunk.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        fr.clubcitrouille.lanterne.client.MecheClient.acceptChunk(payload)));
        registrar.playToServer(MecheRequest.TYPE, MecheRequest.STREAM_CODEC, MecheNet::onRequest);
    }

    private static void onRequest(MecheRequest request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                Meche.onRequest(player, request.modId());
            }
        });
    }

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !Settings.meche()) {
            return;
        }
        for (String modId : Meche.managedIds()) {
            var manifest = Meche.manifestFor(modId);
            if (manifest == null) {
                continue;
            }
            try {
                player.connection.send(manifest);
            } catch (RuntimeException clientWithoutChannel) {
                // Voir la Javadoc de classe : un client sur une version antérieure à ce mécanisme ne
                // reçoit rien — il continue exactement comme si ce module n'existait pas.
            }
        }
    }

    @SubscribeEvent
    public static void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        Meche.forget(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        Meche.clearAll();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (Settings.meche()) {
            Meche.pump(event.getServer());
        }
    }
}
