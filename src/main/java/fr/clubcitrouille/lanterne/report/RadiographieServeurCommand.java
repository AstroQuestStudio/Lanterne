package fr.clubcitrouille.lanterne.report;

import java.nio.file.Path;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.lab.RadiographieServeur;

/**
 * {@code /lanterne profil-serveur demarrer|arreter} — l'équivalent serveur de
 * {@code client.profil.Loupe}, voir {@link RadiographieServeur} pour le pourquoi.
 *
 * <p>Contrairement à la radiographie cliente, celle-ci ne démarre jamais toute seule (pas
 * d'équivalent serveur à "un monde vu/quitté" qui aurait du sens sur un serveur qui tourne 24/7) —
 * uniquement manuelle, réservée aux opérateurs.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class RadiographieServeurCommand {
    private RadiographieServeurCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lanterne")
                .then(Commands.literal("profil-serveur")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("demarrer").executes(context -> {
                            boolean started = RadiographieServeur.start(context.getSource().getServer());
                            tell(context.getSource(), started
                                    ? "Radiographie serveur démarrée."
                                    : "Déjà en cours — voir « /lanterne profil-serveur arreter » d'abord.",
                                    started);
                            return 1;
                        }))
                        .then(Commands.literal("arreter").executes(context -> {
                            Path written = RadiographieServeur.stop(context.getSource().getServer(), "arrêt manuel");
                            tell(context.getSource(), written != null
                                    ? "Rapport écrit : " + written
                                    : "Rien n'était en cours.",
                                    written != null);
                            return 1;
                        }))));
    }

    private static void tell(CommandSourceStack source, String message, boolean ok) {
        source.sendSuccess(() -> Component.literal(message)
                .withStyle(ok ? ChatFormatting.GOLD : ChatFormatting.GRAY), false);
    }
}
