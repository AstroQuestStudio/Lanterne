package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.phys.Vec3;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Shroud;

/**
 * Le voile étendu aux particules : ce qu'un mur cache ne naît pas.
 *
 * <h2>Ce que vanilla filtre déjà, et ce qu'il laisse passer</h2>
 *
 * <p>{@code ClientLevel.doAddParticle} écarte deux choses : ce qui est à plus de trente-deux blocs
 * de la caméra, et une fraction des particules selon l'option {@code particles}. C'est tout. Une
 * particule née <b>derrière un mur</b>, à dix blocs, traverse ce filtre sans encombre.
 *
 * <p>Elle est alors créée, puis tickée à chaque image tant qu'elle vit — {@code ParticleEngine.tick}
 * parcourt tous les groupes sans considérer ni distance ni visibilité — et enfin écartée du dessin
 * par le champ de vision, si elle en sort. Trois coûts, dont deux payés en entier pour rien.
 *
 * <h2>Pourquoi refuser la naissance plutôt que le dessin</h2>
 *
 * <p>Écarter au rendu n'économiserait que le dessin, et vanilla le fait déjà. Refuser la naissance
 * supprime les trois coûts d'un coup, et supprime aussi la particule de la mémoire — une ferme qui
 * tourne sous une colline cesse purement et simplement d'en accumuler.
 *
 * <p>C'est la différence avec les créatures : une bête voilée continue d'exister côté serveur, on
 * n'économise que son état de rendu. Une particule refusée n'existe nulle part.
 *
 * <h2>Ce qui n'est jamais refusé</h2>
 *
 * <p>Le paramètre {@code overrideLimiter} marque les particules que le jeu tient à montrer coûte
 * que coûte — vanilla leur fait déjà sauter le filtre de distance. On ne les touche pas : ce sont
 * précisément celles dont l'absence se remarquerait.
 *
 * <p>Et comme partout dans ce module, la réponse par défaut est <b>oui, qu'elle naisse</b> : budget
 * de rayons épuisé, cache absent, caméra introuvable, doute quelconque — la particule est créée.
 */
@Mixin(ClientLevel.class)
public abstract class ParticleVeilMixin {
    @Inject(method = "doAddParticle", at = @At("HEAD"), cancellable = true)
    private void lanterne$refuseHidden(ParticleOptions particle, boolean overrideLimiter,
            boolean alwaysShowParticles, double x, double y, double z, double xd, double yd,
            double zd, CallbackInfo callback) {
        if (!Settings.shroud() || !Settings.veilParticles() || overrideLimiter) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera == null) {
            return;
        }
        Vec3 eye = camera.position();
        if (Shroud.spotHidden((ClientLevel) (Object) this, x, y, z, eye.x, eye.y, eye.z)) {
            callback.cancel();
        }
    }
}
