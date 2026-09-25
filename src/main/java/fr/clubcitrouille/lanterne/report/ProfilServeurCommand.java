package fr.clubcitrouille.lanterne.report;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.ProfilServeurContinu;

/**
 * {@code /lanterne profil serveur maintenant} — force une rotation immédiate du profil de performance
 * continu du serveur ({@link ProfilServeurContinu}), sans attendre la prochaine heure pleine.
 *
 * <p>Réservée aux opérateurs, comme {@code report.RadiographieServeurCommand}. Ne démarre RIEN si le
 * profil continu n'est pas actif (réglage {@code profil_serveur=false}, ou serveur qui vient tout juste
 * de démarrer) : voir {@link ProfilServeurContinu#forcerMaintenant} qui rend alors {@code false}.
 *
 * <h2>Pourquoi la commande ne peut plus annoncer le chemin du rapport</h2>
 *
 * <p>{@link ProfilServeurContinu#forcerMaintenant} ne bloque plus le Server thread — voir sa Javadoc de
 * classe : l'écriture a lieu sur un fil d'analyse séparé, après le retour de cet appel. Cette commande
 * ne peut donc plus connaître le nom du fichier au moment de répondre ; le journal serveur
 * ({@code [PROFIL] Rapport serveur écrit : ...}) et le dossier {@code logs/lanterne-profils/} restent
 * la source de vérité.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class ProfilServeurCommand {
    private ProfilServeurCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lanterne")
                .then(Commands.literal("profil")
                        .then(Commands.literal("serveur")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.literal("maintenant").executes(context -> {
                                    boolean lance = ProfilServeurContinu.forcerMaintenant(
                                            context.getSource().getServer());
                                    tell(context.getSource(), lance
                                            ? "Rotation demandée — le rapport arrive sous peu dans "
                                                    + "logs/lanterne-profils/ (voir le journal serveur)."
                                            : "Rien n'est en cours — voir le réglage « profil_serveur ».",
                                            lance);
                                    return 1;
                                })))));
    }

    private static void tell(CommandSourceStack source, String message, boolean ok) {
        source.sendSuccess(() -> Component.literal(message)
                .withStyle(ok ? ChatFormatting.GOLD : ChatFormatting.GRAY), false);
    }
}
