package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.world.level.block.Block;

import fr.clubcitrouille.lanterne.core.Remblai;

/**
 * Un octet par bloc du jeu : réagit-il, ou peut-on l'ignorer ?
 *
 * <h2>Pourquoi un champ, et pas une table</h2>
 *
 * <p>{@link Remblai} a besoin de savoir, pour un {@code Block} donné, s'il redéfinit
 * {@code updateShape} ou {@code neighborChanged}. La réponse ne dépend que de la <b>classe</b> du
 * bloc : elle est fixée au chargement du jeu et ne bougera plus. Elle se calcule par réflexion, ce
 * qui est cher, et se consulte <b>douze fois par bloc posé</b>, ce qui l'est encore plus.
 *
 * <p>Une table de hachage à clé d'identité coûterait cinq à dix nanosecondes par consultation. Un
 * {@code ClassValue} guère moins. Un champ sur l'objet coûte une lecture. Sur un {@code /fill} de
 * seize mille blocs, soit deux cent mille consultations, l'écart se chiffre en millisecondes — sur
 * un remplissage qui en dure quelques dizaines, ce n'est pas rien.
 *
 * <p>{@code lab/Boom} raconte la version coûteuse de cette leçon : deux modules d'explosion ont été
 * retirés parce que l'interception, posée sur un chemin parcouru dix-sept mille fois par tir, coûtait
 * plus cher que l'allocation qu'elle évitait. Ici, l'interception doit rester au niveau du bruit.
 *
 * <h2>Zéro veut dire « pas encore su »</h2>
 *
 * <p>Le champ vaut zéro à la construction du bloc, ce qui est le seul état que le classement ne peut
 * jamais produire : toute valeur calculée porte le bit {@link Remblai#CLASSE}. Zéro signifie donc
 * « à calculer », sans avoir besoin d'un second champ.
 *
 * <p>L'écriture est faite sans verrou et sans {@code volatile}. Deux fils qui classeraient le même
 * bloc en même temps calculeraient la <b>même</b> valeur — la réponse ne dépend que de la classe — et
 * écriraient le même octet. La course existe et elle est bénigne ; la seule conséquence possible est
 * un classement fait deux fois.
 */
@Mixin(Block.class)
public abstract class RemblaiBlockMixin implements Remblai.Inertie {
    /**
     * Le classement de ce bloc : zéro tant qu'il n'a pas été calculé, sinon un octet portant
     * {@link Remblai#CLASSE} et, le cas échéant, {@link Remblai#FORMES} et {@link Remblai#VOISINS}.
     */
    @Unique
    private byte lanterne$classementRemblai;

    @Override
    public byte lanterne$inertie() {
        return this.lanterne$classementRemblai;
    }

    @Override
    public void lanterne$inertie(byte valeur) {
        this.lanterne$classementRemblai = valeur;
    }
}
