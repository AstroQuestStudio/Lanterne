package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.item.ItemEntity;

/**
 * L'âge d'un objet au sol, que le jeu garde pour lui.
 *
 * <h2>Pourquoi il faut y accéder</h2>
 *
 * <p>Un objet posé par terre disparaît au bout de cinq minutes, et c'est la seule chose qu'il fasse de
 * sa propre initiative. Le compteur qui le décide — {@code age} — est privé, et il n'est incrémenté
 * qu'à l'intérieur de {@code ItemEntity.tick()}.
 *
 * <p>Ce mod annule ce tick pour les objets lointains. Sans accès à ce champ, il ne peut donc pas tenir
 * l'horloge, et l'épreuve des objets l'a chiffré sans détour : durée de vie demandée cent vingt ticks,
 * <b>obtenue cent cinquante-deux</b>, sur les quarante objets mesurés. Un sol qui met trente pour cent
 * plus longtemps à se vider n'est pas une optimisation, c'est une fuite de mémoire à retardement.
 *
 * <h2>Pourquoi un accesseur plutôt que l'exemption</h2>
 *
 * <p>La solution simple existait : exempter les objets de toute cadence, et laisser leur sommeil faire
 * le travail. Elle a été écrite et mesurée. <b>Vingt-neuf millisecondes par tick contre huit</b>, et un
 * débit d'allocation passé de huit cents mégaoctets à sept virgule sept gigaoctets — c'est-à-dire plus
 * que sans le mod du tout.
 *
 * <p>Renoncer à un facteur trois et demi pour tenir un compteur d'entier n'avait pas de sens. On tient
 * donc le compteur, et cet accesseur est tout ce qu'il fallait pour cela.
 *
 * <p>Un accesseur de champ est le mixin le plus inoffensif qui existe : il n'altère aucun corps de
 * méthode et n'entre en conflit avec rien. S'il cesse de correspondre à une version future, le
 * chargement échoue bruyamment — ce qui est la bonne façon de casser.
 */
@Mixin(ItemEntity.class)
public interface ItemAgeAccessor {
    @Accessor("age")
    int lanterne$age();

    @Accessor("age")
    void lanterne$setAge(int age);
}
