package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;

import fr.clubcitrouille.lanterne.core.Din;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le même son, au même endroit, trente fois dans la seconde : une fois suffit.
 *
 * <p>Le raisonnement est dans {@link Din}, y compris l'aveu qu'il s'agit d'un module de confort et
 * non de performance mesurable.
 *
 * <h2>Ce qui n'est jamais étouffé</h2>
 *
 * <ul>
 *   <li><b>Ce qui n'a pas de position.</b> La musique, les sons d'interface et la voix du narrateur
 *       sont joués « sur le joueur ». Les regrouper par région n'aurait aucun sens, et les limiter
 *       couperait la musique.</li>
 *   <li><b>La catégorie MUSIC et MASTER.</b> Même raison, formulée du côté du jeu.</li>
 *   <li><b>Les sons d'un seul événement.</b> Le seuil par défaut est de trois : un son isolé, ou
 *       deux, ou trois, passent toujours. Il faut une rafale pour que ce module agisse.</li>
 * </ul>
 */
@Mixin(SoundEngine.class)
public abstract class SoundLimitMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void lanterne$hushRepeats(SoundInstance instance,
            CallbackInfoReturnable<SoundEngine.PlayResult> callback) {
        if (!Settings.din() || instance == null) {
            return;
        }
        SoundSource source = instance.getSource();
        if (source == SoundSource.MUSIC || source == SoundSource.MASTER
                || source == SoundSource.VOICE) {
            return;
        }
        double x = instance.getX();
        double y = instance.getY();
        double z = instance.getZ();
        // L'origine exacte est la marque d'un son sans position dans le monde — interface, musique.
        if (x == 0.0d && y == 0.0d && z == 0.0d) {
            return;
        }
        if (Din.tooMany(instance.getIdentifier().hashCode(), x, y, z)) {
            callback.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
        }
    }
}
