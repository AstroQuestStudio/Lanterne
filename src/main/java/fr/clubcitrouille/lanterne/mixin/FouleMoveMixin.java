package fr.clubcitrouille.lanterne.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;

import fr.clubcitrouille.lanterne.core.Foule;

/**
 * Le seul endroit d'où l'on peut voir passer <em>tous</em> les déplacements.
 *
 * <h2>Pourquoi une grille doit être tenue à la position près</h2>
 *
 * <p>La subdivision de {@link Foule} range les entités par cellule de deux blocs. Une cellule n'a de
 * valeur que si elle dit la vérité : une bête qui a changé de cellule sans qu'on le sache serait
 * cherchée là où elle n'est plus, et deux vaches cesseraient de se pousser — le genre de dégât qui
 * ne se voit pas et ne se répare pas.
 *
 * <p>Reconstruire la grille une fois par tick ne marche pas, et l'échec mérite d'être dit : dans un
 * tick, déplacements et requêtes sont <b>entrelacés</b>. Une grille invalidée par tout déplacement
 * serait rebâtie autant de fois qu'il y a d'entités, en {@code O(n)} chacune — soit exactement le
 * terme quadratique qu'on voulait supprimer. L'entretien ne peut donc être qu'incrémental.
 *
 * <h2>{@code onMove}, et pourquoi il n'y a rien d'autre</h2>
 *
 * <p>{@code Entity.setPosRaw} est {@code final}, le champ {@code position} est privé, et
 * {@code setPosRaw} appelle {@code levelCallback.onMove()} dès que la position change. C'est donc le
 * <b>seul</b> chemin par lequel une entité peut se déplacer, dans le jeu comme dans un mod tiers.
 * Une grille tenue ici ne peut pas rater un pas.
 *
 * <p>Vanilla n'y fait quelque chose que lorsque la section change ; on s'accroche à la queue, où
 * {@code currentSection} désigne la section d'arrivée dans les deux cas. Si elle a changé, le jeu a
 * déjà fait le retrait et l'ajout — et les points d'accroche de {@code FouleSectionMixin} ont suivi.
 * Si elle n'a pas changé, c'est ici et nulle part ailleurs que la cellule est corrigée.
 *
 * <h2>{@code @WrapMethod} et non {@code @Inject}</h2>
 *
 * <p>Une injection classique alloue un {@code CallbackInfo} <b>à chaque appel</b>, qu'elle agisse ou
 * non. Sur un chemin emprunté une à trois fois par entité et par tick, cela fait des milliers
 * d'objets par tick pour ne rien faire dans l'immense majorité des cas. {@code @WrapMethod} donne le
 * même contrôle sans rien créer, et {@link Foule#replace} sort sur une lecture de champ statique
 * tant qu'aucune grille n'existe — c'est-à-dire presque toujours.
 *
 * <h2>Le second usage : le gabarit</h2>
 *
 * <p>{@code Entity} ne connaît pas sa section ; il ne connaît que son rappel. {@code Foule.Suivi},
 * implémentée ici, est le fil par lequel {@code FouleEntityMixin} rejoint la grille quand une
 * créature change de posture — car une boîte qui grandit sans que la position bouge élargirait la
 * marge de recherche sans que rien ne le signale.
 *
 * <p>La même interface, absente du rappel client, sert de garantie de côté : un
 * {@code TransientEntitySectionManager$Callback} n'est pas un {@code Foule.Suivi}, et rien ne se
 * passe.
 */
@Mixin(targets = "net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback")
public abstract class FouleMoveMixin implements Foule.Suivi {
    @Shadow
    @Final
    private EntityAccess entity;

    @Shadow
    private EntitySection<EntityAccess> currentSection;

    @WrapMethod(method = "onMove")
    private void lanterne$suitLePas(Operation<Void> original) {
        original.call();
        Foule.replace(this.currentSection, this.entity);
    }

    @Override
    public void lanterne$replace() {
        Foule.replace(this.currentSection, this.entity);
    }
}
