package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * La progression de cuisson et de combustible d'un four, que le jeu garde pour lui.
 *
 * <h2>Pourquoi il faut y accéder</h2>
 *
 * <p>{@code content.regard.Regard} doit pouvoir dire, pour un four qu'un joueur REGARDE sans l'avoir
 * ouvert, où en est sa cuisson. Les quatre compteurs — {@code cookingTimer}, {@code cookingTotalTime},
 * {@code litTimeRemaining}, {@code litTotalTime} — sont privés, et {@code AbstractFurnaceMenu} ne les
 * expose qu'à un joueur qui a le menu OUVERT, via un {@code ContainerData} synchronisé à chaque tick
 * pendant que ce menu l'est. Imposer d'ouvrir un four pour savoir s'il cuit encore est précisément ce
 * que ce module existe pour éviter.
 *
 * <p>{@code FurnaceMixin} (voir sa Javadoc) shadow déjà trois de ces quatre champs, pour une raison
 * entièrement différente — endormir le bloc entre deux échéances connues — et depuis le paquet
 * {@code mixin}, où {@code content.regard} n'a pas sa place (il vit des deux côtés, client compris).
 * Cet accesseur ne duplique aucune logique : il donne simplement, en lecture seule, ce que
 * {@code content.regard.Regard} ne pourrait obtenir autrement sans reconstruire un menu complet pour
 * quatre entiers.
 *
 * <p>Un accesseur de champ est le mixin le plus inoffensif qui existe : il n'altère aucun corps de
 * méthode et n'entre en conflit avec rien, {@code FurnaceMixin} compris — Mixin autorise sans
 * difficulté plusieurs mixins sur la même cible tant qu'aucun ne réécrit le même corps de méthode.
 * Voir {@link ItemAgeAccessor} et {@link ExperienceOrbAccessor}, même patron.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface FurnaceProgressAccessor {
    @Accessor("cookingTimer")
    int lanterne$cookingTimer();

    @Accessor("cookingTotalTime")
    int lanterne$cookingTotalTime();

    @Accessor("litTimeRemaining")
    int lanterne$litTimeRemaining();

    @Accessor("litTotalTime")
    int lanterne$litTotalTime();
}
