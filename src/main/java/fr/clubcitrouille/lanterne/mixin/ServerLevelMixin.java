package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import fr.clubcitrouille.lanterne.core.EntityThrottle;

/**
 * Court-circuiter avant l'allocation, et non après.
 *
 * <h2>Ce que coûtait le chemin élégant</h2>
 *
 * <p>La première version passait par {@code EntityTickEvent.Pre}, l'évènement annulable que NeoForge
 * fournit. C'était le bon choix au départ : aucun mixin sur le chemin le plus chaud du serveur, donc
 * aucun risque de conflit avec les autres mods.
 *
 * <p>Le profileur a montré ce que ce choix coûtait. Voici le hook, tel quel :
 *
 * <pre>
 * public static EntityTickEvent.Pre fireEntityTickPre(Entity entity) {
 *     return NeoForge.EVENT_BUS.post(new EntityTickEvent.Pre(entity));
 * }
 * </pre>
 *
 * <p><b>Un objet alloué par entité et par tick.</b> À huit mille entités, cela fait cent soixante
 * mille allocations par seconde — pour, dans quatre-vingt-dix pour cent des cas, annuler aussitôt le
 * tick. On payait l'objet, la distribution à tous les abonnés, et le parcours de la chaîne
 * d'appels, avant de décider de ne rien faire.
 *
 * <p>L'injection en tête de {@code tickNonPassenger} décide <em>avant</em> tout cela. Le gain n'est
 * pas dans le code qu'on exécute, il est dans celui qu'on n'atteint plus.
 *
 * <h2>Où exactement, et pourquoi pas plus haut</h2>
 *
 * <p>La tentation serait de filtrer plus tôt, dans {@code EntityTickList.forEach} : on économiserait
 * alors aussi le parcours. Ce serait une faute, et la boucle de {@code ServerLevel.tick} dit
 * pourquoi :
 *
 * <pre>
 * this.entityTickList.forEach(entity -&gt; {
 *     if (!entity.isRemoved()) {
 *         if (!tickRateManager.isEntityFrozen(entity)) {
 *             entity.checkDespawn();                 // ← doit continuer de tourner
 *             if (entity instanceof ServerPlayer || inEntityTickingRange(...)) {
 *                 this.guardEntityTick(this::tickNonPassenger, entity);
 *             }
 * </pre>
 *
 * <p>{@code checkDespawn()} s'exécute <b>pour toutes les entités</b>, en amont. Filtrer au-dessus le
 * supprimerait, et les créatures cesseraient de disparaître — un monde qui se remplit lentement de
 * mobs que plus rien n'efface, découvert des semaines plus tard.
 *
 * <p>{@code tickCount++} se trouve en revanche <em>dans</em> {@code tickNonPassenger}, avant notre
 * point d'injection : le vieillissement des entités continue donc de courir, et les compteurs de
 * disparition avec lui.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void lanterne$skipBeforeEvent(Entity entity, CallbackInfo callback) {
        // <h2>Pourquoi les créatures ne passent plus par ici</h2>
        //
        // Annuler le tick entier d'une créature est grossier, et le prix en a été découvert tard.
        // Les compteurs qui font le <b>rendement</b> d'une ferme vivent dans les {@code aiStep()} des
        // sous-classes :
        //
        // <pre>
        // AgeableMob.aiStep : if (this.canAgeUp()) this.setAge(++age);   // la croissance
        // Animal.aiStep     : if (this.inLove > 0) this.inLove--;        // la reproduction
        // Chicken.aiStep    : if (--this.eggTime <= 0) ... pond un œuf   // la ponte
        // </pre>
        //
        // Une poule tickée une fois sur huit pondait donc <b>huit fois moins d'œufs</b>, et un veau
        // grandissait huit fois plus lentement. Personne ne l'aurait relié au mod : la ferme
        // fonctionne, elle rend simplement moins, et on accuse la chance.
        //
        // Les créatures sont donc traitées un cran plus bas, dans {@code LivingEntity.aiStep}, où l'on
        // peut retirer ce qui coûte — l'intelligence et le déplacement — en laissant tourner ce qui
        // produit. Voir LivingEntityMixin.
        // Les créatures sont donc traitées un cran plus bas, dans {@code LivingEntity.aiStep} — mais
        // seulement si l'on a demandé la préservation stricte du rendement. Par défaut, on garde
        // l'annulation totale, qui est bien plus rapide (10,01 ms contre 14,31 sur la même charge), et
        // l'on rattrape les compteurs à la main. Voir Produce, qui chiffre les deux voies.
        if (fr.clubcitrouille.lanterne.core.Settings.strictYield()
                && entity instanceof net.minecraft.world.entity.LivingEntity) {
            return;
        }
        if (EntityThrottle.shouldTick(entity)) {
            return;
        }

        // Les horloges de production avancent même quand la créature ne fait rien d'autre : un veau
        // grandit, un délai de reproduction s'écoule, une poule approche de sa ponte. Sans cette
        // ligne, une ferme éloignée rendait proportionnellement moins, et rien ne le signalait.
        fr.clubcitrouille.lanterne.core.Produce.compensate(entity);

        // <h2>Deux effets à reproduire, et le second est vital</h2>
        //
        // L'injection se place avant ces deux lignes du jeu :
        //
        // <pre>
        // entity.setOldPosAndRot();
        // entity.tickCount++;
        // </pre>
        //
        // La seconde est celle qui compte. {@code tickCount} est l'âge de l'entité, et c'est lui qui
        // commande la disparition des objets au sol — six mille ticks — comme quantité d'autres
        // mécaniques de fond. Le figer remplirait lentement le monde d'objets que plus rien
        // n'efface, et le défaut ne se verrait qu'au bout de plusieurs semaines de jeu.
        //
        // La première tient l'ancienne position, dont le client se sert pour interpoler. La
        // reproduire évite qu'une créature réveillée après une longue pause paraisse surgir depuis
        // une position périmée.
        //
        // C'est tout ce que l'ancien point d'accroche — l'évènement de NeoForge — nous offrait
        // gratuitement, et tout ce qu'il fallait reprendre en descendant d'un cran.
        entity.setOldPosAndRot();
        entity.tickCount++;
        callback.cancel();
    }
}
