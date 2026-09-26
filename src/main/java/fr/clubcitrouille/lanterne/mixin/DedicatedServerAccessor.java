package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerSettings;

/** Les réglages d'un serveur dédié. Voir {@link fr.clubcitrouille.lanterne.core.DifficultePersistante}. */
@Mixin(DedicatedServer.class)
public interface DedicatedServerAccessor {
    @Accessor("settings")
    DedicatedServerSettings lanterne$settings();
}
