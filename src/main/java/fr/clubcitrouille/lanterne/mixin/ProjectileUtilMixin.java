package fr.clubcitrouille.lanterne.mixin;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import fr.clubcitrouille.lanterne.core.Quarry;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le raccourci posé sur la recherche de cible d'un projectile.
 *
 * <h2>La bonne méthode, trouvée par le profileur et non par la lecture</h2>
 *
 * <p>La première version de ce mixin visait {@code getEntityHitResult}, qui est le nom qu'on attend
 * et celui que la lecture du code suggère. Elle n'a rien donné — et le compteur seul n'aurait pas
 * suffi à dire pourquoi. Le profileur, interrogé sur qui demande les recherches d'entités, a répondu
 * sans ambiguïté :
 *
 * <pre>
 * 68,5 %  ProjectileUtil.getManyEntityHitResult
 * </pre>
 *
 * <p>C'est {@code getManyEntityHitResult} que le jeu emprunte — la variante qui collecte <em>toutes</em>
 * les entités touchées, et non la première. Viser l'autre revenait à poser un raccourci sur un chemin
 * que personne ne prend.
 *
 * <h2>Ce que le raccourci rend</h2>
 *
 * <p>Le raisonnement complet est dans {@link Quarry} : si aucune entité sélectionnable ne se trouve
 * dans la zone examinée, aucune ne peut satisfaire le prédicat, et la réponse est la collection vide
 * — celle que le parcours complet aurait rendue, obtenue sans le faire.
 */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {
    @WrapMethod(method = "getManyEntityHitResult(Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;"
            + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;"
            + "Ljava/util/function/Predicate;FLnet/minecraft/world/level/ClipContext$Block;Z)"
            + "Ljava/util/Collection;")
    private static Collection<EntityHitResult> lanterne$skipWhenNothingToHit(Level level,
            Entity source, Vec3 from, Vec3 to, AABB targetSearchArea, Predicate<Entity> matching,
            float entityMargin, ClipContext.Block clipType, boolean includeFromEntity,
            Operation<Collection<EntityHitResult>> original) {
        if (Settings.projectiles() && Quarry.noTargetIn(targetSearchArea)) {
            return List.of();
        }
        return original.call(level, source, from, to, targetSearchArea, matching, entityMargin,
                clipType, includeFromEntity);
    }
}
