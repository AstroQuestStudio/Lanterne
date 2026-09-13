package fr.clubcitrouille.lanterne.mixin;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;

import fr.clubcitrouille.lanterne.core.Poi;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Vingt-quatre entiers emballés par chunk, pour rien.
 *
 * <h2>Le poste, et l'arithmétique posée AVANT d'écrire</h2>
 *
 * <pre>
 * public Stream&lt;PoiRecord&gt; getInChunk(…) {
 *     return IntStream.rangeClosed(minSectionY, maxSectionY)
 *         .boxed()                                       // &lt;- 24 Integer alloués
 *         .map(sectionY -&gt; this.getOrLoad(…))
 *         .filter(Optional::isPresent)
 *         .flatMap(section -&gt; section.get().getRecords(…));
 * }
 * </pre>
 *
 * <p>{@code getInSquare} appelle cette méthode <b>pour chaque chunk d'un carré</b>. Un villageois qui
 * cherche un lit ou un établi à quarante-huit blocs balaie neuf chunks de côté, soit
 * <b>quatre-vingt-un pipelines</b>, et vingt-quatre entiers emballés dans chacun.
 *
 * <p>Le relevé d'allocations attribue à ce chemin sept cent cinquante-cinq mégaoctets sur une charge
 * de six cents villageois. Ce projet vient d'apprendre à ne pas croire ce chiffre sur parole — le
 * profileur d'allocations extrapole, et surévalue les petits objets très nombreux d'un facteur
 * soixante-dix. On pose donc la multiplication :
 *
 * <pre>
 * 81 chunks × (24 entiers × 16 o + 7 étages × 48 o) ≈ 58 Ko par recherche
 * 600 villageois × 1 recherche/s × 25 s × 58 Ko     ≈ 875 Mo
 * </pre>
 *
 * <p><b>L'ordre de grandeur tient.</b> C'est la différence avec le tableau vide des effets traversés,
 * annoncé à 2,70 Go et qui n'en valait que 39 : ici le calcul et le relevé se rejoignent, donc le
 * poste est probablement réel.
 *
 * <h2>La correction, et ce qu'elle ne change pas</h2>
 *
 * <p>{@code mapToObj} fait exactement ce que {@code boxed().map()} fait, sans l'étape d'emballage :
 * un {@code IntStream} qui produit directement des objets. Même paresse, même ordre, même contenu —
 * vingt-quatre allocations et un étage de pipeline en moins par chunk.
 *
 * <p><b>La paresse est le point à ne pas perdre.</b> Certains appelants ne prennent que le premier
 * résultat ; matérialiser les enregistrements dans une liste — ce qui aurait supprimé le pipeline
 * entier — les aurait fait travailler bien davantage. On ne touche donc qu'à l'emballage.
 */
@Mixin(PoiManager.class)
public abstract class PoiStreamMixin {
    @Inject(method = "getInChunk", at = @At("HEAD"), cancellable = true)
    private void lanterne$withoutBoxing(Predicate<Holder<PoiType>> predicate, ChunkPos chunkPos,
                                        PoiManager.Occupancy occupancy,
                                        CallbackInfoReturnable<Stream<PoiRecord>> callback) {
        if (!Settings.poi()) {
            return;
        }
        Poi.noteChunk();
        callback.setReturnValue(IntStream
                .rangeClosed(((SectionStorageAccessor) this).lanterne$heights().getMinSectionY(),
                        ((SectionStorageAccessor) this).lanterne$heights().getMaxSectionY())
                .mapToObj(sectionY -> ((SectionStorageAccessor) this)
                        .lanterne$getOrLoad(SectionPos.of(chunkPos, sectionY).asLong()))
                .filter(Optional::isPresent)
                .flatMap(section -> ((PoiSection) section.get()).getRecords(predicate, occupancy)));
    }
}
