package fr.clubcitrouille.lanterne.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import fr.clubcitrouille.lanterne.core.Crowd;
import fr.clubcitrouille.lanterne.core.Jam;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le coût quadratique des entités entassées.
 *
 * <h2>Ce que fait le jeu</h2>
 *
 * <pre>
 * protected void pushEntities() {
 *     List&lt;Entity&gt; pushableEntities = this.level().getPushableEntities(this, this.getBoundingBox());
 *     ...
 *     for (Entity other : pushableEntities) {
 *         this.doPush(other);
 *     }
 * }
 * </pre>
 *
 * <p>Chaque créature cherche toutes ses voisines poussables, puis les pousse une à une. Avec
 * cinquante vaches dans un même chunk, cela fait cinquante recherches spatiales et deux mille cinq
 * cents poussées <b>par tick</b>. Le coût croît avec le <em>carré</em> du nombre d'entités : c'est
 * la raison pour laquelle une ferme qui fonctionne bien à trois cents mobs s'effondre à six cents.
 *
 * <p>Le profileur le confirme sur le banc : {@code EntitySection.getEntities} pèse sept virgule huit
 * pour cent du temps, et c'est cette recherche-là.
 *
 * <h2>Le plafond, et pourquoi il est acceptable</h2>
 *
 * <p>Paper plafonne ce nombre depuis des années — {@code max-entity-collisions}, valeur par défaut
 * huit — sur des dizaines de milliers de serveurs. Ce n'est donc pas une idée neuve mais une
 * pratique établie, et la raison en est simple : <b>au-delà de quelques voisines, une poussée de
 * plus ne change rien à la position finale</b>. Les forces s'annulent, la créature est coincée de
 * toute façon.
 *
 * <h2>Où ce mod diffère</h2>
 *
 * <p>Paper applique son plafond en permanence, à toutes les entités. Ici il ne s'applique
 * <b>qu'au-delà du seuil de foule</b> : en deçà, la liste est rendue intacte et le comportement est
 * celui du jeu, au geste près.
 *
 * <p>Deux ou trois cochons qui se bousculent dans un enclos se poussent donc exactement comme
 * d'habitude. Seul le tas de cinquante voit sa liste tronquée — et dans un tas de cinquante,
 * personne ne peut dire quelle vache a poussé quelle autre.
 *
 * <h2>Ce qui n'est pas touché</h2>
 *
 * <p>Le plafond retenu reste au-dessus de la règle d'entassement ({@code maxEntityCramming}, vingt-
 * quatre par défaut). Le comptage qui inflige des dégâts d'écrasement lit la même liste : la
 * tronquer trop court supprimerait silencieusement une mécanique du jeu, et les fermes qui
 * <em>reposent</em> sur ces dégâts cesseraient de produire.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    /**
     * Plafond de voisines considérées.
     *
     * <p>Choisi juste au-dessus de la règle d'entassement par défaut, pour que les dégâts
     * d'écrasement continuent de se déclencher exactement quand le jeu le prévoit.
     */
    private static final int PUSH_LIMIT = 26;

    /**
     * Seuil au-delà duquel le plafond s'applique.
     *
     * <p>Identique au seuil de foule : la même situation, jugée de la même façon. En deçà, on ne
     * touche à rien.
     */
    private static final int CROWD_THRESHOLD = 32;

    /**
     * Court-circuite entièrement la bousculade d'un amas coincé.
     *
     * <p>Ce n'est pas un plafond mais une suppression : ni recherche de voisines, ni poussées. On ne
     * réduit pas le nombre d'opérations d'un calcul quadratique, <b>on l'annule</b> — et sans rien
     * changer au résultat, puisque ce résultat était l'immobilité.
     */
    @Inject(method = "pushEntities", at = @At("HEAD"), cancellable = true)
    private void lanterne$skipJammed(CallbackInfo callback) {
        if (!Settings.jam()) {
            return;
        }
        LivingEntity self = (LivingEntity) (Object) this;
        if (Jam.jammed(self, Crowd.neighbours(self), self.level().getGameTime())) {
            callback.cancel();
        }
    }

    @Redirect(
            method = "pushEntities",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/level/Level;getPushableEntities"
                            + "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)"
                            + "Ljava/util/List;"))
    private List<Entity> lanterne$capPushables(Level level, Entity pusher, AABB box) {
        List<Entity> found = level.getPushableEntities(pusher, box);
        if (!Settings.collisions() || found.size() <= CROWD_THRESHOLD) {
            return found; // cas ordinaire : le jeu tel quel, sans un geste de moins
        }
        // subList rend une vue, et non une copie : on tronque sans rien allouer.
        return found.subList(0, PUSH_LIMIT);
    }
}
