package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.entity.Mob;

import fr.clubcitrouille.lanterne.core.Cadence;
import fr.clubcitrouille.lanterne.core.Census;
import fr.clubcitrouille.lanterne.core.Pressure;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.TickBudget;

/**
 * L'examen de disparition, à la cadence de celui qu'il examine.
 *
 * <h2>Trois cents mégaoctets pour demander « dois-je disparaître ? »</h2>
 *
 * <p>Une fois les gros allocateurs éliminés, le profileur a fait remonter celui-ci :
 *
 * <pre>
 * 8,2 %  EventHooks.checkMobDespawn → MobDespawnEvent   (309 Mo)
 * </pre>
 *
 * <p>{@code checkDespawn()} est appelé pour <b>toutes</b> les créatures, à chaque tick, depuis la
 * boucle du monde — et en amont du point où ce mod décide de sauter un tick. NeoForge y alloue un
 * évènement à chaque appel, même sans le moindre abonné.
 *
 * <p>Ce placement était délibéré et il reste juste : la disparition ne doit pas dépendre du fait
 * qu'une créature ait été simulée ou non, sans quoi le monde se remplirait lentement de créatures
 * que plus rien n'efface.
 *
 * <h2>La cadence, et rien de plus</h2>
 *
 * <p>On n'exempte donc pas l'examen : on l'aligne sur la cadence de la créature. Celle qui ne vit
 * qu'un tick sur huit vérifie sa disparition un tick sur huit. Elle continue de vieillir — le
 * compteur d'âge est incrémenté ailleurs, hors de notre portée — et disparaîtra donc au même moment,
 * à quelques ticks près.
 *
 * <p>« À quelques ticks près » se justifie ici mieux qu'ailleurs : la disparition n'est pas un
 * évènement ponctuel mais une décision répétée, fondée sur la distance au joueur et sur un tirage.
 * L'espacer en retarde l'issue d'une fraction de seconde, sur une mécanique qui se compte en
 * dizaines de secondes.
 *
 * <p>Ce qui est en pleine simulation — tout ce qui est proche d'un joueur — n'est pas touché : la
 * cadence y vaut un, et la condition est vraie à chaque tick.
 */
@Mixin(Mob.class)
public abstract class MobMixin {
    @Inject(method = "checkDespawn", at = @At("HEAD"), cancellable = true)
    private void lanterne$examineAtOwnPace(CallbackInfo callback) {
        if (!Settings.lod()) {
            return;
        }
        Mob self = (Mob) (Object) this;
        // La MEME pression qu'EntityThrottle, et non celle du seul budget de tick. Les deux
        // lisaient des nombres differents, si bien que la zone franche n'avait pas le meme rayon
        // selon qui la consultait — une garantie en deux exemplaires n'en est pas une.
        int period = Cadence.forEntity(self, Census.distanceOf(self), Pressure.now());
        if (!Cadence.actsOn(period, self.level().getGameTime(), self.getId())) {
            callback.cancel();
        }
    }
}
