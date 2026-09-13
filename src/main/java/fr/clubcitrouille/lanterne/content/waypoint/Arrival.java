package fr.clubcitrouille.lanterne.content.waypoint;

import net.minecraft.server.level.ServerPlayer;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le carnet, envoyé à qui arrive et à qui change de monde.
 *
 * <h2>Les trois moments où un client se retrouve sans carnet</h2>
 *
 * <p>Un client ne conserve rien entre deux connexions : sa copie du carnet vit en mémoire et part
 * avec la déconnexion. Il faut donc le lui renvoyer à chaque fois qu'il (re)prend place.
 *
 * <ul>
 *   <li><b>La connexion</b> — le cas évident.</li>
 *   <li><b>Le changement de dimension</b> — l'affichage ne montre que les repères du monde courant,
 *       et ce monde vient de changer.</li>
 *   <li><b>La réapparition après une mort</b> — le joueur reçoit une nouvelle entité, et l'ancienne
 *       copie part avec l'ancienne.</li>
 * </ul>
 *
 * <p>Les trois sont couverts ici. En oublier un donnerait un affichage vide qu'aucune action ne
 * remplirait, et le joueur conclurait que le mod est cassé — ce qu'il serait.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class Arrival {
    private Arrival() {}

    @SubscribeEvent
    public static void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        send(event.getEntity());
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        send(event.getEntity());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        send(event.getEntity());
    }

    private static void send(net.minecraft.world.entity.player.Player who) {
        if (!(who instanceof ServerPlayer player) || !Settings.waypoints()) {
            return;
        }
        var server = player.level().getServer();
        if (server != null) {
            Atlas.of(server).syncTo(player);
        }
    }
}
