package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Sleep;

/**
 * L'entonnoir qui dort pendant sa recharge — <b>désactivé, et voici pourquoi</b>.
 *
 * <h2>Ce que l'épreuve a dit</h2>
 *
 * <p>Le raisonnement paraissait solide, et il l'était presque : pendant la recharge, le tick ne fait
 * que décrémenter un compteur, donc on peut dormir. L'épreuve de débit a répondu sans appel —
 * une chaîne de six entonnoirs a livré <b>quarante-sept objets sans le mod, vingt-deux avec</b>.
 * Le débit divisé par deux.
 *
 * <p>La cause tient au décalage d'un ou deux ticks à chaque réveil, qui s'accumule à chaque
 * transfert et se compose le long de la chaîne. Contrairement au four, dont l'échéance est un
 * évènement unique et lointain, l'entonnoir en enchaîne une toutes les huit ticks : une erreur
 * minuscule y devient un facteur.
 *
 * <p>Ce mixin n'est donc <b>pas enregistré</b>. Il est conservé comme trace de la tentative et de sa
 * réfutation, et parce que la bonne réponse est probablement ailleurs : transférer <em>plus d'objets
 * à la fois, moins souvent</em>, ce qui préserve le débit moyen au lieu de le décaler. Cette piste a
 * son propre danger — les horloges à entonnoir comptent les objets un par un — et devra être
 * éprouvée avant d'être livrée.
 *
 * <h2>La version qui a échoué</h2>
 *
 * <h2>Sept ticks sur huit ne font rien</h2>
 *
 * <p>Le tick d'un entonnoir se lit en quatre lignes :
 *
 * <pre>
 * public static void pushItemsTick(Level level, BlockPos pos, BlockState state, HopperBlockEntity entity) {
 *     entity.cooldownTime--;
 *     entity.tickedGameTime = level.getGameTime();
 *     if (!entity.isOnCooldown()) {
 *         entity.setCooldown(0);
 *         tryMoveItems(level, pos, state, entity, () -&gt; suckInItems(level, entity));
 *     }
 * }
 * </pre>
 *
 * <p>Après chaque transfert réussi, {@code setCooldown(8)} impose huit ticks de recharge. Pendant
 * ces huit ticks, le tick <b>ne fait qu'une chose</b> : décrémenter un compteur. Aucun objet ne
 * bouge, aucun inventaire n'est lu, aucune décision n'est prise.
 *
 * <p>Sept ticks sur huit d'un entonnoir en marche sont donc entièrement prévisibles. On les dort, et
 * l'on rattrape le compteur au réveil — le transfert suivant a lieu au même tick qu'il aurait eu
 * lieu, et le débit est identique.
 *
 * <h2>Ce qu'on ne touche pas, et pourquoi</h2>
 *
 * <p>Un entonnoir <b>bloqué</b> — plein, ou n'ayant rien à prendre — ne pose aucune recharge : il
 * réessaie à chaque tick. C'est son cas le plus coûteux, et c'est précisément celui qu'on laisse
 * intact.
 *
 * <p>La raison est une question d'exactitude, pas de prudence. Le jeu ne prévient pas quand un objet
 * tombe devant un entonnoir ; endormir un entonnoir en attente retarderait la prise du premier objet
 * qui se présente, et une chaîne de tri perdrait son cadencement. On ne dort que là où l'on sait
 * avec certitude que rien ne peut arriver — le compte à rebours — et nulle part ailleurs.
 *
 * <p>C'est la même règle que pour les créatures qui tombent : <b>l'exactitude d'abord, le gain
 * ensuite</b>.
 */
// LANTERNE_MIXIN_DESACTIVE : absence de lanterne.mixins.json volontaire, voir la javadoc
// ci-dessus. tools/verifie_mixins.py reconnaît ce jeton et n'en fait pas un échec.
@Mixin(HopperBlockEntity.class)
public abstract class HopperMixin {
    @Shadow
    private int cooldownTime;
    @Shadow
    private long tickedGameTime;

    @Inject(method = "pushItemsTick", at = @At("HEAD"), cancellable = true)
    private static void lanterne$sleepDuringCooldown(Level level, BlockPos pos, BlockState state,
                                                     HopperBlockEntity entity,
                                                     CallbackInfo callback) {
        if (!Settings.sleep()) {
            return;
        }

        HopperMixin self = (HopperMixin) (Object) entity;
        long now = level.getGameTime();

        int skipped = Sleep.due(pos, now);
        if (skipped < 0) {
            callback.cancel();
            return;
        }
        if (skipped > 0) {
            // Le compteur a couru pendant le sommeil ; il doit en porter la trace exacte. On ne
            // descend jamais sous zéro : au-delà, l'entonnoir est prêt, et c'est au jeu de le
            // constater.
            self.cooldownTime = Math.max(0, self.cooldownTime - skipped);
            self.tickedGameTime = now;
        }

        // Le tick vanilla va décrémenter une fois de plus, et agir si la recharge est finie. On
        // dort donc jusqu'à l'avant-dernier tick de recharge, pour que ce soit bien lui qui
        // franchisse la ligne.
        Sleep.plan(pos, now, Math.max(0, self.cooldownTime - 2));
    }
}
