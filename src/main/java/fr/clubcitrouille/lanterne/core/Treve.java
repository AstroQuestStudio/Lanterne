package fr.clubcitrouille.lanterne.core;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Suspend le rendu BlueMap tant qu'un joueur est connecté.
 *
 * <h2>Ce qui a été mesuré, pas supposé</h2>
 *
 * <p>BlueMap actif en fond (2 fils de rendu, déjà réduit du défaut de 1) a fait tomber le tick
 * serveur à 2068 ms (41 ticks) de retard, 44 secondes après la connexion d'un joueur — trouvé dans
 * {@code journalctl -u minecraft} : {@code Can't keep up! Is the server overloaded? Running 2068ms
 * or 41 ticks behind}. Le client a alors reçu d'un coup les paquets accumulés pendant le retard, ce
 * que le profileur {@code Radiographie} du mod a enregistré comme un quasi-arrêt du rendu — 0,4
 * image par seconde sur une fenêtre d'une seconde, contre une moyenne de 523,8 sur toute la session.
 *
 * <p>BlueMap n'est pas en cause : il fait exactement ce qu'on lui demande. Le problème est le
 * moment — rendre pendant qu'un joueur compte sur chaque tick est le pire moment possible pour un
 * travail de fond qui peut attendre indéfiniment sans rien perdre.
 *
 * <h2>Le mécanisme</h2>
 *
 * <p>{@code /bluemap stop} et {@code /bluemap start} sont les deux commandes déjà exposées par
 * BlueMap lui-même (vérifiées manuellement en production) — celle-ci ne fait qu'appeler l'une à la
 * première connexion, l'autre au dernier départ, via le même {@link CommandSourceStack} console que
 * {@code Terrassement} utilise déjà pour piloter la pré-génération. BlueMap reprend une région déjà
 * rendue sans la refaire : rien n'est perdu à l'arrêt.
 */
public final class Treve {

    private Treve() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.register(Treve.class);
    }

    private static boolean blueMapPresent() {
        return ModList.get().isLoaded("bluemap");
    }

    private static void dispatch(MinecraftServer serveur, String commande) {
        CommandSourceStack source = serveur.createCommandSourceStack().withSuppressedOutput();
        serveur.getCommands().performPrefixedCommand(source, commande);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!Config.TREVE_BLUEMAP.get() || !blueMapPresent()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer serveur = player.level().getServer();
        if (serveur == null || serveur.getPlayerList().getPlayerCount() != 1) {
            return; // pas le premier : la treve est deja posee
        }
        dispatch(serveur, "bluemap stop");
        Lanterne.LOG.info("[TRÊVE] BlueMap suspendu — un joueur vient de se connecter.");
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!Config.TREVE_BLUEMAP.get() || !blueMapPresent()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer serveur = player.level().getServer();
        if (serveur == null || serveur.getPlayerList().getPlayerCount() != 0) {
            return; // il reste du monde : la treve continue
        }
        dispatch(serveur, "bluemap start");
        Lanterne.LOG.info("[TRÊVE] BlueMap relancé — le serveur est de nouveau vide.");
    }
}
