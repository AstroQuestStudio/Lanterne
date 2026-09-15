package fr.clubcitrouille.lanterne.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import fr.clubcitrouille.lanterne.core.Remblai;

/**
 * Comparer deux formes de collision pour décider s'il faut prévenir des bêtes qui n'existent pas.
 *
 * <h2>Le corps de la méthode dit tout</h2>
 *
 * <pre>
 * // ServerLevel.sendBlockUpdated, appelé une fois par bloc modifié dès que UPDATE_CLIENTS est posé
 * this.getChunkSource().blockChanged(pos);
 * this.pathTypesByPosCache.invalidate(pos);
 * VoxelShape oldShape = old.getCollisionShape(this, pos);
 * VoxelShape newShape = current.getCollisionShape(this, pos);
 * if (Shapes.joinIsNotEmpty(oldShape, newShape, BooleanOp.NOT_SAME)) {
 *     List&lt;PathNavigation&gt; navigationsToUpdate = new ObjectArrayList&lt;&gt;();
 *     for (Mob navigatingMob : this.navigatingMobs) { … }
 *     …
 * }
 * </pre>
 *
 * <p>La comparaison des deux formes n'a <b>qu'un seul usage</b> : ouvrir le bloc {@code if}, dont
 * tout le contenu boucle sur {@code navigatingMobs}. Quand cet ensemble est vide, le bloc alloue une
 * liste, ne la remplit pas, ne la parcourt pas, et la jette.
 *
 * <p>Le cas n'est pas rare, il est ordinaire : une plateforme de construction, un chantier de
 * terrassement, un tunnel en cours de percement n'ont aucune bête en train de suivre un chemin.
 * {@code /fill} y appelle pourtant cette méthode une fois par bloc, et {@code joinIsNotEmpty} n'y est
 * pas gratuit — de l'air contre un cube plein sort par le chemin court, mais deux formes non vides et
 * différentes déclenchent la fusion d'index sur les trois axes.
 *
 * <h2>Ce qu'on prouve avant de couper</h2>
 *
 * <p>Rendre {@code false} quand {@code navigatingMobs} est vide donne exactement le même état final
 * que rendre la vraie valeur : dans les deux cas rien n'est écrit, rien n'est envoyé, aucun chemin
 * n'est recalculé — la seule différence est une liste vide qui n'est plus créée. {@code blockChanged}
 * et {@code invalidate}, eux, sont en amont de l'appel détourné et ne sont pas touchés.
 *
 * <h2>Ce qu'on a refusé de faire</h2>
 *
 * <p>Quand des bêtes naviguent, la boucle est en {@code O(bêtes)} <b>par bloc modifié</b> : deux
 * cents bêtes et un {@code /fill} de seize mille blocs font trois millions deux cent mille appels à
 * {@code shouldRecomputePath}. C'est tentant, et c'est un piège : reporter les recalculs à la fin de
 * l'opération changerait le <b>jeu</b> de navigations concernées, puisque
 * {@code shouldRecomputePath} interroge le chemin <em>courant</em> de la bête, lequel change dès
 * qu'un recalcul a eu lieu. Le résultat ne serait pas identique, seulement plausible. On laisse.
 */
@Mixin(ServerLevel.class)
public abstract class RemblaiNavigationMixin {
    @Shadow
    @Final
    private Set<Mob> navigatingMobs;

    @Redirect(
            method = "sendBlockUpdated",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/phys/shapes/Shapes;joinIsNotEmpty"
                            + "(Lnet/minecraft/world/phys/shapes/VoxelShape;"
                            + "Lnet/minecraft/world/phys/shapes/VoxelShape;"
                            + "Lnet/minecraft/world/phys/shapes/BooleanOp;)Z"))
    private boolean lanterne$aucuneBeteEnChemin(VoxelShape ancienne, VoxelShape nouvelle,
                                                BooleanOp operation) {
        if (Remblai.actif() && this.navigatingMobs.isEmpty()) {
            Remblai.noteNavigation();
            return false;
        }
        return Shapes.joinIsNotEmpty(ancienne, nouvelle, operation);
    }
}
