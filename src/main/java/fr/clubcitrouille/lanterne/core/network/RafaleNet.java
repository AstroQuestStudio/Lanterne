package fr.clubcitrouille.lanterne.core.network;

import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Rafale;

/**
 * Le branchement réseau de {@link Rafale} — même charpente que {@code MecheNet} : un seul paquet
 * client → serveur, enregistré une fois, traité sur le fil du serveur.
 *
 * <h2>Pourquoi la garde est revérifiée ici, et pas seulement dans {@link Rafale}</h2>
 *
 * <p>{@code registrar.playToServer} n'exécute ce gestionnaire QUE pour un client qui a réellement
 * négocié ce canal à la connexion — mais négocier le canal, ce n'est pas la même chose qu'avoir le
 * réglage serveur allumé : un client peut porter Lanterne (donc le canal existe des deux côtés) sans
 * qu'AutoMiner soit son partenaire. {@link Rafale#autorise} est donc revérifié pour CHAQUE requête,
 * pas seulement à la négociation — c'est {@link AutominerPresence} qui prouve la présence
 * d'AutoMiner, indépendamment du canal {@code rafale_demande} lui-même.
 */
public final class RafaleNet {
    private static final String PROTOCOL = "1";

    private RafaleNet() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(RafaleNet::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        registrar.playToServer(RafaleRequest.TYPE, RafaleRequest.STREAM_CODEC, RafaleNet::onRequest);
    }

    private static void onRequest(RafaleRequest request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !Rafale.autorise(player)) {
                return;
            }
            Rafale.traiter(player, request);
        });
    }
}
