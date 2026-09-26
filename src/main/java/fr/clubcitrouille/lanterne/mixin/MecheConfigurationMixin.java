package fr.clubcitrouille.lanterne.mixin;

import java.util.function.Consumer;

import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import net.neoforged.neoforge.network.ConfigurationInitialization;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import fr.clubcitrouille.lanterne.core.MecheConfig;

/**
 * Faire passer la Mèche AVANT la synchronisation des registres — voir la Javadoc de
 * {@link MecheConfig} pour l'ordre des tâches lu dans {@code runConfiguration()}.
 *
 * <p>{@code RegisterConfigurationTasksEvent} arrive après {@code SyncRegistries} et
 * {@code SynchronizeRegistriesTask} : trop tard pour un client à qui il manque un bloc. Le seul point
 * d'ancrage qui précède la synchronisation est l'appel à
 * {@code ConfigurationInitialization.configureEarlyTasks} ; on le redirige vers
 * {@link MecheConfig#planifier}, qui ajoute sa tâche puis rappelle l'original (ou le remplace, pour un
 * client ancien en compatibilité — voir {@code core.MecheCompat}).
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class MecheConfigurationMixin {
    @Redirect(method = "runConfiguration",
            at = @At(value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/network/ConfigurationInitialization;configureEarlyTasks("
                            + "Lnet/minecraft/network/protocol/configuration/ServerConfigurationPacketListener;"
                            + "Ljava/util/function/Consumer;)V"))
    private void lanterne$mecheAvantRegistres(ServerConfigurationPacketListener listener,
            Consumer<ConfigurationTask> tasks) {
        MecheConfig.planifier(listener, tasks,
                () -> ConfigurationInitialization.configureEarlyTasks(listener, tasks));
    }
}
