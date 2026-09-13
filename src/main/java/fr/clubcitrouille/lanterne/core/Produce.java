package fr.clubcitrouille.lanterne.core;

import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.chicken.Chicken;

/**
 * Les horloges de production, tenues à jour pendant que la créature dort.
 *
 * <h2>Ce que l'annulation d'un tick arrêtait sans le dire</h2>
 *
 * <p>Ce mod ralentit les créatures en annulant leur tick. C'est la façon la moins coûteuse de ne rien
 * faire, et c'est pour cela qu'elle a été choisie. Elle a un défaut qu'il a fallu lire dans le code du
 * jeu pour découvrir : <b>les compteurs qui font le rendement d'une ferme sont à l'intérieur du
 * tick</b>, mêlés à l'intelligence.
 *
 * <pre>
 * Chicken.aiStep    : if (--this.eggTime &lt;= 0) { ... pond un œuf ... }
 * AgeableMob.aiStep : if (this.canAgeUp()) this.setAge(++age); else if (age &gt; 0) this.setAge(--age);
 * Animal.aiStep     : if (this.inLove &gt; 0) this.inLove--;
 * </pre>
 *
 * <p>Une poule tickée une fois sur sept pondait donc sept fois moins d'œufs. Un veau grandissait sept
 * fois plus lentement. Le mod tenait sa promesse de vitesse en prélevant la différence sur la
 * production — sans le savoir, et sans qu'aucune de ses trois épreuves ne puisse le voir.
 *
 * <h2>Pourquoi ne pas simplement tout ticker</h2>
 *
 * <p>La solution évidente a été essayée, écrite, et mesurée : laisser le tick s'exécuter et n'en
 * retirer que l'intelligence et le déplacement. C'est plus élégant, c'est général, et cela couvre
 * automatiquement les créatures des mods.
 *
 * <p>Le banc a tranché : <b>14,31 ms contre 10,01</b> sur la même charge. Le gain passait de ×3,01 à
 * ×1,71. Ce qui coûte dans le tick d'une créature n'est pas seulement son intelligence : c'est aussi
 * {@code baseTick()} — portails, fluides de la boîte englobante, natation, monde d'en dessous — et la
 * longue suite d'effets de {@code LivingEntity.tick()}. En garder la moitié coûte la moitié.
 *
 * <p>Renoncer à quarante pour cent du gain pour préserver trois compteurs qu'on peut tenir à la main
 * en dix lignes n'était pas défendable. On tient donc les compteurs à la main.
 *
 * <h2>Deux mécaniques différentes, deux traitements</h2>
 *
 * <p><b>Ce qui se compense</b> — l'âge, le délai de reproduction. Ce sont des compteurs dont la valeur
 * seule importe ; les avancer d'un pas pendant un tick sauté donne rigoureusement le même résultat
 * qu'un tick réel. {@code setAge} déclenche lui-même le passage de petit à adulte au franchissement de
 * zéro, donc même la transition est exacte.
 *
 * <p><b>Ce qui a une échéance</b> — la ponte. Faire arriver {@code eggTime} à zéro ici ne suffirait
 * pas : l'œuf sort d'une table de butin qu'on ne peut pas tirer correctement depuis l'extérieur. On
 * décompte donc pendant le sommeil, et l'on <b>réveille la poule juste avant l'échéance</b> pour que la
 * ponte ait lieu dans le vrai code, au tick exact. C'est le principe déjà retenu pour les fours, et il
 * ne concède rien.
 *
 * <h2>La limite, dite franchement</h2>
 *
 * <p>Cette liste couvre les compteurs de production du jeu de base. Elle ne couvre pas ceux qu'un mod
 * aurait placés dans son propre {@code aiStep} — et il n'existe aucun moyen de les découvrir, puisque
 * rien ne les distingue du reste du comportement.
 *
 * <p>Un éleveur dont les créatures viennent d'un mod a donc un réglage pour lui :
 * {@code LANTERNE_MODULES=…,strict} rétablit l'architecture sélective, qui préserve <em>tous</em> les
 * compteurs de <em>toutes</em> les créatures, au prix des quarante pour cent de gain mesurés
 * ci-dessus. Le choix lui appartient ; ce qui nous revient est de le chiffrer honnêtement et de ne pas
 * décider à sa place.
 */
public final class Produce {
    /**
     * Ticks avant l'échéance auxquels on cesse de dormir.
     *
     * <p>Un seul suffit : au tick où {@code eggTime} vaut un, on laisse le tick réel s'exécuter, son
     * propre {@code --eggTime} l'amène à zéro, et la ponte a lieu exactement quand elle devait.
     */
    private static final int WAKE_BEFORE = 1;

    /**
     * Casse la compensation, exprès, pour vérifier que l'épreuve sait la voir.
     *
     * <h2>Un test qui ne peut pas échouer ne prouve rien</h2>
     *
     * <p>L'épreuve de rendement a rendu « cent pour cent, verdict : intact » du premier coup. C'est le
     * résultat qu'on espérait, et c'est exactement pour cela qu'il ne fallait pas s'en contenter : un
     * test mal branché, qui compare deux fois la même chose ou qui mesure autre chose que ce qu'il
     * croit, rend « intact » avec le même aplomb.
     *
     * <p>Ce projet en a déjà fait les frais. L'épreuve de cuisson annonçait « exact au tick près » en
     * comparant deux fours qui n'avaient cuit ni l'un ni l'autre, parce qu'ils étaient hors du rayon de
     * simulation. Le verdict était juste au sens littéral, et complètement vide.
     *
     * <p>{@code LANTERNE_BREAK_YIELD=1} retire la compensation. L'épreuve <b>doit</b> alors annoncer une
     * chute de rendement. Si elle continue de dire « intact », c'est elle qu'il faut réparer, pas le
     * mod — et on le saura avant d'avoir publié un chiffre de plus.
     */
    private static final boolean BROKEN_ON_PURPOSE =
            "1".equals(System.getenv("LANTERNE_BREAK_YIELD"));

    private static long compensated;

    private Produce() {}

    /**
     * Cette créature doit-elle être tickée parce qu'une échéance de production arrive ?
     *
     * <p>Appelé pour chaque entité, à chaque tick : un {@code instanceof} et une comparaison d'entier
     * dans le cas courant.
     */
    public static boolean dueSoon(Entity entity) {
        return !BROKEN_ON_PURPOSE && entity instanceof Chicken hen && hen.eggTime <= WAKE_BEFORE;
    }

    /**
     * Avance les horloges de production d'un tick sauté.
     *
     * <p>Reproduit, à l'identique, ce que les {@code aiStep()} des sous-classes auraient fait — en
     * recopiant leurs conditions plutôt qu'en les devinant, y compris {@code isAlive()} qui les garde
     * toutes.
     */
    public static void compensate(Entity entity) {
        if (BROKEN_ON_PURPOSE) {
            return;
        }

        // Les objets au sol ont leur propre horloge, et c'est la seule chose qu'ils fassent d'eux-mêmes.
        if (entity instanceof net.minecraft.world.entity.item.ItemEntity item) {
            age(item);
            return;
        }

        if (!(entity instanceof AgeableMob ageable) || !ageable.isAlive()) {
            return;
        }
        compensated++;

        int age = ageable.getAge();
        if (ageable.canAgeUp()) {
            ageable.setAge(age + 1);
        } else if (age > 0) {
            ageable.setAge(age - 1);
        }

        if (entity instanceof Animal animal) {
            int love = animal.getInLoveTime();
            if (love > 0) {
                animal.setInLoveTime(love - 1);
            }
        }

        if (entity instanceof Chicken hen && !hen.isBaby() && !hen.isChickenJockey()) {
            // On décompte sans jamais atteindre zéro : « dueSoon » aura forcé un tick réel avant, et
            // c'est lui qui pondra. Descendre ici jusqu'à zéro produirait une poule dont l'échéance
            // est passée et qui ne pond pas — le pire des deux mondes.
            hen.eggTime--;
        }
    }

    /**
     * Fait vieillir un objet dont le tick a été sauté, et le fait disparaître à l'heure.
     *
     * <p>La mécanique est reproduite à la lettre depuis {@code ItemEntity.tick()}, constantes comprises :
     * {@code -32768} marque un âge gelé — c'est ainsi que le jeu protège un objet de la disparition — et
     * le crochet {@code onItemExpire} permet à un mod de prolonger la vie d'un objet au moment où elle
     * s'achève. Sauter ce crochet ferait disparaître des objets que d'autres mods protègent, et le
     * coffre vidé ne serait relié à rien.
     */
    private static void age(net.minecraft.world.entity.item.ItemEntity item) {
        var access = (fr.clubcitrouille.lanterne.mixin.ItemAgeAccessor) (Object) item;
        int age = access.lanterne$age();
        if (age == -32768) {
            return; // durée de vie illimitée : le jeu ne le vieillit pas non plus
        }
        compensated++;
        age++;
        access.lanterne$setAge(age);

        if (age < item.lifespan) {
            return;
        }
        item.lifespan = net.minecraft.util.Mth.clamp(
                item.lifespan + net.neoforged.neoforge.event.EventHooks.onItemExpire(item),
                0, Short.MAX_VALUE - 1);
        if (age >= item.lifespan) {
            item.discard();
        }
    }

    /** Ticks de production rattrapés depuis le démarrage, pour le rapport. */
    public static long compensated() {
        return compensated;
    }

    public static void reset() {
        compensated = 0;
    }
}
