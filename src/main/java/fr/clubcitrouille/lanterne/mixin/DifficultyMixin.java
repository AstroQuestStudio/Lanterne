package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;

import fr.clubcitrouille.lanterne.core.DifficultePersistante;

/**
 * Le point où {@code /difficulty} (et tout autre chemin) change la difficulté du monde. Pourquoi un
 * mixin et pas {@code DifficultyChangeEvent} : voir {@link DifficultePersistante}.
 */
@Mixin(MinecraftServer.class)
public abstract class DifficultyMixin {
    @Inject(method = "setDifficulty(Lnet/minecraft/world/Difficulty;Z)V", at = @At("TAIL"))
    private void lanterne$retenirLaDifficulte(Difficulty demandee, boolean forcee, CallbackInfo callback) {
        MinecraftServer self = (MinecraftServer) (Object) this;
        DifficultePersistante.noter(self, self.getWorldData().getDifficulty());
    }
}
