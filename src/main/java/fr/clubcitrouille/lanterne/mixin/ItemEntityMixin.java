package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;

import fr.clubcitrouille.lanterne.core.Churn;
import fr.clubcitrouille.lanterne.core.Litter;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le sommeil des objets au sol.
 *
 * <p>Le raisonnement complet — pourquoi ce sommeil ne dégrade rien, et comment chacune des trois
 * interactions d'un objet posé reste intacte — est dans {@link Litter}. Ce fichier n'en est que la
 * mécanique.
 *
 * <h2>Des champs, et non une table</h2>
 *
 * <p>Le reste du mod retient l'état de ses entités dans des tables indexées par identifiant. C'est le
 * bon choix quand les entités concernées se comptent par milliers et que l'état est occasionnel.
 *
 * <p>Ici, il faut trois nombres pour <b>chaque</b> objet d'un sol qui peut en porter un million. Une
 * table coûterait un calcul de dispersion à chaque accès et une cinquantaine d'octets par entrée ;
 * trois champs coûtent seize octets et un accès direct. Sur ce volume, la différence n'est pas une
 * finesse.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Shadow
    private int age;

    @Shadow
    private int pickupDelay;

    @Shadow
    public int lifespan;

    /**
     * Moment où l'immobilité a commencé, en temps de jeu.
     *
     * <h2>Un compteur qui comptait les mauvais ticks</h2>
     *
     * <p>C'était un simple compteur, incrémenté à chaque passage. Il n'a jamais atteint son seuil, et
     * le profileur montrait {@code ItemEntity.tick} à soixante-dix-sept pour cent du travail sur une
     * charge de huit mille objets — le sommeil ne s'était pas déclenché une seule fois.
     *
     * <p>La cause est une interaction entre deux modules du même mod. La dégradation par densité ralentit
     * déjà un tas d'objets serrés à <b>un tick sur seize</b>. Or ce compteur ne s'incrémente que lorsque
     * le tick a lieu : atteindre quarante-cinq demandait donc sept cent vingt ticks de jeu, soit
     * trente-six secondes — plus que la durée de la mesure.
     *
     * <p>On retient donc l'<em>instant</em> et non le nombre de passages. Un objet qui n'a pas bougé
     * depuis quarante-cinq ticks de jeu s'endort, qu'on l'ait examiné quarante-cinq fois ou trois.
     *
     * <p>Et c'est même plus juste : entre deux examens espacés, l'objet n'a pas tické, donc il n'a pas
     * pu bouger. Les ticks qu'on ne lui donne pas sont la meilleure preuve d'immobilité qu'on puisse
     * avoir.
     */
    @Unique
    private long lanterne$stillSince = -1L;

    /** État du voisinage retenu au moment de s'endormir. */
    @Unique
    private int lanterne$churn;

    @Unique
    private boolean lanterne$asleep;

    /** Position quantifiée au tick précédent, pour juger de l'immobilité réelle. */
    @Unique
    private long lanterne$where = Long.MIN_VALUE;

    /**
     * Vitesse retenue au moment de s'endormir.
     *
     * <p>Un dormeur ne touche plus à sa vitesse. Toute valeur différente de celle-ci vient donc
     * <b>forcément de l'extérieur</b> — un piston, un mod, une explosion — et c'est exactement ce
     * qu'on veut détecter. La comparaison est exacte, sans seuil à régler.
     */
    @Unique
    private double lanterne$pace;

    /**
     * Décide si ce tick a lieu.
     *
     * <p>On s'accroche en tête de {@code tick()}, avant tout le reste, et l'on annule le tick entier
     * quand l'objet dort — après avoir fait à la main le peu qui doit continuer.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void lanterne$doze(CallbackInfo callback) {
        ItemEntity self = (ItemEntity) (Object) this;
        if (!Settings.litter() || self.level().isClientSide()) {
            return;
        }

        // Le crochet des objets de mods est appelé dans tous les cas, endormi ou non. C'est la seule
        // chose qu'un objet fait de sa propre initiative en dehors de vieillir, et un mod qui s'en
        // sert ne doit pas s'apercevoir que ce mod existe. Sa valeur par défaut est « false », donc
        // l'appel ne coûte rien à personne d'autre.
        if (self.getItem().onEntityItemUpdate(self)) {
            callback.cancel();
            return;
        }

        if (lanterne$asleep) {
            // <h2>Le réveil de courtoisie ne doit pas coûter une nouvelle sieste</h2>
            //
            // Le premier relevé a montré autant de réveils que d'endormissements : sept par objet sur la
            // durée du banc. Ce n'était pas un défaut — c'est le réveil de sûreté, un tick sur deux
            // cents, qui tombait exactement ce nombre de fois.
            //
            // Mais il coûtait cher. Chaque réveil remettait le compteur d'immobilité à zéro, et l'objet
            // devait patienter quarante-cinq ticks avant de pouvoir se rendormir : éveillé un cinquième
            // du temps, pour vérifier une position qui n'avait pas bougé.
            //
            // Un objet qu'on réveille par précaution n'a rien fait de mal. On lui laisse donc son
            // ancienneté d'immobilité : il exécute son tick complet, et se rendort au suivant. Seul un
            // vrai dérangement — un bloc qui change, une poussée — remet le compteur à zéro, parce que
            // là, il faut effectivement revérifier que tout s'est calmé.
            if (lanterne$stirred(self)) {
                lanterne$asleep = false;
                lanterne$stillSince = -1L;
                Litter.woke();
                return; // tick complet, dans le tick même où le voisinage a changé
            }
            if (lanterne$courtesy(self)) {
                lanterne$asleep = false;
                Litter.woke();
                return; // tick complet, mais l'ancienneté d'immobilité est conservée
            }
            lanterne$slumber(self);
            callback.cancel();
            return;
        }

        long now = self.level().getGameTime();
        if (!lanterne$restful(self)) {
            lanterne$stillSince = -1L;
            return;
        }
        if (lanterne$stillSince < 0L) {
            lanterne$stillSince = now;
        } else if (now - lanterne$stillSince >= Litter.SETTLE) {
            lanterne$asleep = true;
            lanterne$churn = lanterne$churnHere(self);
            lanterne$pace = self.getDeltaMovement().lengthSqr();
            Litter.slept();
        }
    }

    /**
     * Les conditions du sommeil.
     *
     * <p>Chacune écarte une situation où quelque chose se passerait encore : posé (donc ne tombe pas),
     * immobile (donc ne glisse pas), hors de l'eau et de la lave (donc ne dérive ni ne brûle), hors du
     * feu (donc n'est pas en train d'être détruit).
     *
     * <p>Il n'y a volontairement <b>aucun test de bloc dangereux</b> — ni cactus, ni feu de camp, ni
     * bloc modé inconnu. C'était la première version, et elle était à la fois incomplète et
     * impossible à compléter : on ne peut pas énumérer les blocs destructeurs que les mods ajouteront.
     *
     * <p>L'exigence de quarante-cinq ticks d'immobilité règle la question autrement, et mieux : un
     * objet posé sur quoi que ce soit de destructeur est détruit bien avant de les avoir accumulés.
     * <b>L'épreuve remplace l'énumération</b>, et elle couvre ce qu'on ne connaît pas.
     */
    @Unique
    private boolean lanterne$restful(ItemEntity self) {
        if (!self.onGround()
                || self.isInWater()
                || self.isInLava()
                || self.getRemainingFireTicks() > 0) {
            return false;
        }

        // <h2>Le critère qui paraissait évident et ne se déclenchait jamais</h2>
        //
        // La première version exigeait une vitesse nulle. Elle n'a jamais endormi un seul objet, et le
        // profileur l'a montré sans détour : sur vingt mille objets posés par terre, {@code
        // ItemEntity.tick} pesait encore quatre-vingt-douze pour cent du travail du serveur.
        //
        // La raison est dans la physique du jeu, et elle ne se devine pas. Un objet au repos n'a
        // <b>jamais</b> une vitesse nulle : {@code applyGravity()} lui retire quatre centièmes en y à
        // chaque tick, et le sol ne les lui rend qu'un tick sur quatre, quand {@code move()} a
        // effectivement lieu. Sa vitesse oscille donc perpétuellement, à plus de mille fois le seuil
        // qu'on lui demandait de respecter.
        //
        // Ce qu'il faut mesurer n'est pas la vitesse mais le <b>déplacement</b> : un objet qui reste à
        // la même place est immobile, quelle que soit l'agitation de ses nombres. Le mod fait déjà ce
        // raisonnement pour les amas de créatures ; il valait aussi ici.
        //
        // Le seuil horizontal, lui, est repris du jeu : {@code ItemEntity.tick} utilise exactement
        // « 1.0E-5 » pour décider qu'un objet ne glisse plus. Reprendre sa constante plutôt qu'en
        // inventer une garantit qu'on s'endort au moment où le jeu lui-même cesse de le déplacer.
        if (self.getDeltaMovement().horizontalDistanceSqr() > 1.0E-5d) {
            return false;
        }

        long here = lanterne$quantised(self);
        boolean settled = here == lanterne$where;
        lanterne$where = here;
        return settled;
    }

    /**
     * Position réduite au centième de bloc, empaquetée dans un seul entier.
     *
     * <p>Comparer des nombres à virgule à l'identique ne dirait rien : un objet posé bouge toujours
     * d'un cheveu. La réduction <em>est</em> le seuil d'immobilité, et elle rend la comparaison exacte
     * — un entier contre un entier, sans tolérance à choisir.
     */
    @Unique
    private long lanterne$quantised(ItemEntity self) {
        long x = (long) (self.getX() * 100d);
        long y = (long) (self.getY() * 100d);
        long z = (long) (self.getZ() * 100d);
        return (x & 0x1FFFFFL) << 42 | (y & 0x1FFFFFL) << 21 | (z & 0x1FFFFFL);
    }

    /** Un vrai dérangement : quelque chose a changé, et il faut tout revérifier. */
    @Unique
    private boolean lanterne$stirred(ItemEntity self) {
        // Quelque chose l'a poussé — un piston, un mod, n'importe quoi. On ne cherche pas qui : on
        // compare sa vitesse à celle qu'il avait en s'endormant. Comme il n'y touche plus lui-même,
        // tout écart vient du dehors, et la comparaison n'a besoin d'aucun seuil.
        if (self.getDeltaMovement().lengthSqr() != lanterne$pace) {
            return true;
        }
        // Un bloc a changé autour : c'est le réveil qui compte, et il est immédiat.
        return lanterne$churnHere(self) != lanterne$churn;
    }

    /**
     * Le réveil de sûreté.
     *
     * <p>Il ne corrige aucun cas connu : il existe pour les cas <em>inconnus</em>. Le raisonnement qui
     * autorise ce sommeil peut avoir un trou qu'on n'a pas vu, et ce projet a déjà eu tort quatre fois
     * avec autant d'assurance. Un réveil sur deux cents borne toute erreur future à dix secondes au lieu
     * de l'éternité.
     *
     * <p>Le décalage par identifiant évite de réveiller tout le sol dans le même tick — ce qui
     * produirait exactement l'à-coup que ce mod passe son temps à éviter.
     */
    @Unique
    private boolean lanterne$courtesy(ItemEntity self) {
        return (self.level().getGameTime() + self.getId()) % Litter.SAFETY == 0L;
    }

    @Unique
    private int lanterne$churnHere(ItemEntity self) {
        return Churn.around(self.getBlockX(), self.getBlockY(), self.getBlockZ());
    }

    /**
     * Ce qu'un dormeur continue de faire.
     *
     * <p>Deux additions et une comparaison. C'est la reproduction fidèle des seules lignes de
     * {@code ItemEntity.tick()} qui ont un effet observable sur un objet immobile — le décompte du
     * délai de ramassage et celui de la durée de vie.
     *
     * <p>Les constantes sont reprises telles quelles du jeu : {@code 32767} marque un délai de
     * ramassage infini, {@code -32768} un âge gelé. Les recopier plutôt que de les deviner est ce qui
     * fait la différence entre un objet qui disparaît au tick exact et un objet qui ne disparaît
     * jamais.
     */
    @Unique
    private void lanterne$slumber(ItemEntity self) {
        if (pickupDelay > 0 && pickupDelay != 32767) {
            pickupDelay--;
        }
        if (age != -32768) {
            age++;
        }
        if (age >= lifespan) {
            // Le crochet de NeoForge permet à un mod de prolonger un objet au moment où il va
            // expirer. Le sauter ferait disparaître des objets que ce mod protège — une régression
            // invisible jusqu'au jour où l'on cherche pourquoi un coffre s'est vidé.
            lifespan = Mth.clamp(
                    lifespan + net.neoforged.neoforge.event.EventHooks.onItemExpire(self),
                    0, Short.MAX_VALUE - 1);
            if (age >= lifespan) {
                self.discard();
                return;
            }
        }
        if (self.getItem().isEmpty() && !self.isRemoved()) {
            self.discard();
        }
    }
}
