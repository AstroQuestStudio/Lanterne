package fr.clubcitrouille.lanterne.mixin;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;

/**
 * L'accès à {@code getOrLoad}, déclarée dans la superclasse.
 *
 * <h2>Pourquoi un accesseur plutôt qu'un simple {@code @Shadow}</h2>
 *
 * <p>La première version posait {@code @Shadow protected abstract Optional&lt;PoiSection&gt;
 * getOrLoad(long)} directement sur le mixin de {@code PoiManager}. Le serveur a refusé de démarrer :
 *
 * <pre>
 * InvalidMixinException: @Shadow method getOrLoad(J)Ljava/util/Optional; was not located
 * in the target class net.minecraft.world.entity.ai.village.poi.PoiManager
 * </pre>
 *
 * <p>{@code getOrLoad} n'est pas déclarée par {@code PoiManager} mais par sa superclasse
 * {@code SectionStorage}, et {@code @Shadow} exige que le membre existe dans la classe <b>ciblée</b>.
 * L'héritage ne suffit pas : le mixin travaille sur le bytecode d'une classe précise, pas sur la
 * vue que le compilateur Java en donne.
 *
 * <p>On pose donc l'accesseur là où la méthode est déclarée, et l'on y accède par transtypage depuis
 * le mixin de la sous-classe.
 */
@Mixin(SectionStorage.class)
public interface SectionStorageAccessor {
    @Invoker("getOrLoad")
    Optional<?> lanterne$getOrLoad(long sectionPos);

    /**
     * Le champ hérité, lui aussi inaccessible par {@code @Shadow} depuis la sous-classe.
     *
     * <p>La règle est la même que pour la méthode, et il a fallu deux démarrages ratés pour la
     * retenir : <b>{@code @Shadow} ne voit que ce que la classe ciblée déclare elle-même</b>. Ni les
     * méthodes ni les champs hérités n'y figurent, quelle que soit leur visibilité en Java.
     */
    @Accessor("levelHeightAccessor")
    LevelHeightAccessor lanterne$heights();
}
