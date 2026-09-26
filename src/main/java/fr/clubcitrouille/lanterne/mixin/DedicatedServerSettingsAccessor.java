package fr.clubcitrouille.lanterne.mixin;

import java.nio.file.Path;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.dedicated.DedicatedServerSettings;

/**
 * Le fichier {@code server.properties} et sa copie en mémoire. Voir
 * {@link fr.clubcitrouille.lanterne.core.DifficultePersistante}.
 */
@Mixin(DedicatedServerSettings.class)
public interface DedicatedServerSettingsAccessor {
    @Accessor("source")
    Path lanterne$source();

    @Accessor("properties")
    void lanterne$setProperties(DedicatedServerProperties properties);
}
