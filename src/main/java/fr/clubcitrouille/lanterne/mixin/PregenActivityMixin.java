package fr.clubcitrouille.lanterne.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.minecraft.server.MinecraftServer;

import fr.clubcitrouille.lanterne.lab.Pregen;

/**
 * Empêche la pause automatique de vanilla ({@code pause_a_l_absence}) de couper une
 * pré-génération en cours.
 *
 * <p>Le raisonnement complet est dans {@link Pregen#keepServerAwake(int)} : {@code tickServer}
 * lit {@code PlayerList.getPlayerCount()} une seule fois pour décider s'il doit compter un tick
 * vide, et s'arrête avant même le tick des mondes si le seuil est franchi. On intercepte cette
 * seule lecture — vérifiée par bytecode comme n'étant utilisée qu'à cet endroit de la méthode.
 */
@Mixin(MinecraftServer.class)
public abstract class PregenActivityMixin {

    @ModifyExpressionValue(method = "tickServer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/players/PlayerList;getPlayerCount()I"))
    private int lanterne$keepAliveForPregen(int original) {
        return Pregen.keepServerAwake(original);
    }
}
