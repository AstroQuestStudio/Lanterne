package fr.clubcitrouille.lanterne.client.profil;

import java.nio.file.Path;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.lab.Radiographie;

/**
 * La commande de secours pour {@link Radiographie} : {@code /lanterne profil demarrer|arreter}.
 *
 * <p>Le chemin par défaut est automatique — voir {@link Radiographie} — donc cette commande ne sert
 * qu'à deux cas : rejouer une session ciblée sans redémarrer le jeu (arrêter, se déplacer ailleurs,
 * redémarrer), ou couper l'échantillonnage en cours de partie si jamais son coût dérangeait, sans
 * quitter le monde pour autant.
 *
 * <p>Commande cliente, comme {@code client.ponder.Usher} : aucun paquet, aucun risque pour un
 * serveur dédié.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class Loupe {
    private Loupe() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lanterne")
                .then(Commands.literal("profil")
                        .then(Commands.literal("demarrer").executes(context -> {
                            boolean started = Radiographie.start();
                            tell(started
                                    ? "Radiographie démarrée."
                                    : "Déjà en cours — voir « /lanterne profil arreter » d'abord.",
                                    started);
                            return 1;
                        }))
                        .then(Commands.literal("arreter").executes(context -> {
                            Path written = Radiographie.stop("arrêt manuel");
                            tell(written != null
                                    ? "Rapport écrit : " + written
                                    : "Rien n'était en cours.",
                                    written != null);
                            return 1;
                        }))));
    }

    private static void tell(String message, boolean ok) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        player.sendSystemMessage(Component.literal(message)
                .withStyle(ok ? ChatFormatting.GOLD : ChatFormatting.GRAY));
    }
}
