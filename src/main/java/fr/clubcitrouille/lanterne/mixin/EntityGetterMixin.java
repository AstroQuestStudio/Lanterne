package fr.clubcitrouille.lanterne.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

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
    /**
     * Le raccourci, posé sans rien allouer.
     *
     * <h2>L'injection qui coûtait plus qu'elle ne rapportait</h2>
     *
     * <p>La première version était un {@code @Inject(cancellable = true)}. C'est l'outil le plus lisible
     * de Mixin, et il a un prix que sa lisibilité cache : <b>il alloue un {@code CallbackInfoReturnable}
     * à chaque appel</b>, qu'il annule ou non.
     *
     * <p>Sur un chemin emprunté par chaque entité qui se déplace, à chaque tick, cela se paie. La charge
     * dynamite — six mille charges amorcées, mèches étalées — l'a mis au jour :
     *
     * <pre>
     * sans le mod   : 41,68 ms   5,28 Go alloués
     * module seul   : 70,68 ms  10,63 Go alloués
     * </pre>
     *
     * <p>Le même module donne ×62 sur un sol jonché d'objets. Il n'est donc pas mauvais : il était
     * <b>mal posé</b>. Là où les entités sont concentrées, le parcours qu'il supprime est énorme et son
     * coût disparaît dans le gain ; là où elles sont dispersées, le parcours vanilla est déjà court et
     * il ne reste que le coût.
     *
     * <p>{@code @WrapMethod} de MixinExtras donne le même contrôle — décider avant, ou laisser passer —
     * sans créer d'objet : la suite de la méthode est atteinte par une opération, pas par un rappel.
     *
     * <h2>Et la sortie anticipée, avant même de regarder</h2>
     *
     * <p>Le test de {@link Solid} est déjà bon marché, mais « bon marché » multiplié par des centaines
     * de milliers d'appels reste une addition. On sort donc sur la condition la moins chère qui
     * existe — un booléen — avant de toucher à la moindre coordonnée.
     */
    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "getEntityCollisions")
    private List<VoxelShape> lanterne$skipWhenNothingBlocks(Entity source, AABB testArea,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<List<VoxelShape>> original) {
        if (Settings.collisions() && Solid.noneIn(testArea)) {
            return List.of();
        }
        return original.call(source, testArea);
    }
}
