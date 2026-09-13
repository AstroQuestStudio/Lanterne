package fr.clubcitrouille.lanterne.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Solid;

/**
 * Le raccourci posé à la source, et non chez chacun de ses appelants.
 *
 * <h2>Quatre-vingt-huit pour cent du travail, sur une charge d'objets au sol</h2>
 *
 * <p>La première version de ce raccourci ne visait que {@code Entity.collide}, parce que c'est là que
 * le profil de l'enclos le désignait. Une autre charge — vingt mille objets posés par terre — a montré
 * qu'il regardait par le mauvais trou de serrure :
 *
 * <pre>
 * 91,7 %  ItemEntity.tick
 * 89,4 %    CollisionGetter.noCollision
 * 88,1 %      CollisionGetter.noEntityCollision
 * 88,1 %        EntityGetter.getEntityCollisions
 * 87,7 %          EntitySection.getEntities
 * </pre>
 *
 * <p>Le même gaspillage, atteint par un tout autre chemin : {@code noCollision} et non {@code collide}.
 * Poursuivre les appelants un par un aurait demandé autant de mixins que de chemins, et il en resterait
 * toujours un.
 *
 * <p>{@code EntityGetter.getEntityCollisions} est le <b>goulot</b> : {@code CommonLevelAccessor} y
 * délègue explicitement, et tout ce qui cherche une collision d'entité y passe. Un seul point
 * d'accroche, et la question est réglée pour tous les appelants, connus comme inconnus.
 *
 * <h2>Ce que le raccourci rend</h2>
 *
 * <p>Exactement ce que la méthode aurait rendu : {@code List.of()}. Elle rend déjà cette valeur quand
 * sa recherche ne trouve rien, et {@link Solid} garantit qu'il n'y a rien à trouver — trois classes
 * seulement peuvent bloquer dans tout Minecraft, et aucune n'est dans la zone.
 *
 * <p>Aucune concession, aucune approximation. Le seul écart possible était l'élargissement de sept
 * décimales que le jeu applique à sa zone de recherche, et {@code Solid} le reproduit.
 */
@Mixin(EntityGetter.class)
public interface EntityGetterMixin {
    @Inject(method = "getEntityCollisions", at = @At("HEAD"), cancellable = true)
    private void lanterne$skipWhenNothingBlocks(Entity source, AABB testArea,
                                                CallbackInfoReturnable<List<VoxelShape>> callback) {
        if (Settings.collisions() && Solid.noneIn(testArea)) {
            callback.setReturnValue(List.of());
        }
    }
}
