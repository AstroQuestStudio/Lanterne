package fr.clubcitrouille.lanterne.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Shroud;

/**
 * Le voile, posé à l'unique endroit où il économise autre chose que le dessin.
 *
 * <p>Le raisonnement complet est dans {@link Shroud}. Ce fichier ne fait que le brancher.
 *
 * <h2>Pourquoi envelopper l'appel plutôt qu'injecter dans la méthode appelée</h2>
 *
 * <p>{@code EntityRenderDispatcher.shouldRender} est appelé depuis plusieurs endroits du jeu —
 * l'aperçu d'entité d'une interface, le rendu d'un cadre, les vues hors monde. Un mixin posé sur la
 * méthode elle-même s'appliquerait à tous, y compris là où la notion de « caché par un mur » n'a
 * aucun sens et où la caméra passée n'est pas celle du joueur.
 *
 * <p>Envelopper l'appel <em>depuis {@code extractVisibleEntities}</em> restreint l'effet au seul
 * chemin visé : la boucle de rendu du monde, celle dont la ligne suivante appelle
 * {@code extractEntity}.
 *
 * <h2>L'ordre des deux tests n'est pas indifférent</h2>
 *
 * <p>Le test vanilla est évalué <b>en premier</b>, et on ne lance aucun rayon s'il refuse déjà. Le
 * jeu élimine ainsi tout ce qui est hors champ — la grande majorité des entités d'un monde chargé —
 * pour un coût de quelques comparaisons de boîte, bien moindre qu'un parcours de grille. Inverser
 * les deux ferait payer un lancer de rayon à des créatures situées derrière le joueur.
 */
@Mixin(LevelRenderer.class)
public abstract class EntityCullMixin {
    @Inject(method = "extractVisibleEntities", at = @At("HEAD"))
    private void lanterne$openFrame(Camera camera, Frustum frustum, DeltaTracker deltaTracker,
            LevelRenderState output, CallbackInfo callback) {
        if (Settings.shroud()) {
            Shroud.openFrame();
        }
    }

    @WrapOperation(
            method = "extractVisibleEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;"
                            + "shouldRender(Lnet/minecraft/world/entity/Entity;"
                            + "Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
    private boolean lanterne$veil(EntityRenderDispatcher dispatcher, Entity entity, Frustum frustum,
            double camX, double camY, double camZ, Operation<Boolean> original) {
        if (!original.call(dispatcher, entity, frustum, camX, camY, camZ)) {
            return false;
        }
        if (!Settings.shroud()) {
            return true;
        }
        return !Shroud.hidden(entity, camX, camY, camZ);
    }
}
