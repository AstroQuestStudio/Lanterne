package fr.clubcitrouille.lanterne.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import fr.clubcitrouille.lanterne.core.Loterie;

/**
 * Le point d'entrée du tirage aléatoire, détourné section par section.
 *
 * <p>Le raisonnement complet — pourquoi ce détour préserve exactement la probabilité de tirage de
 * vanilla — vit dans {@link Loterie}. Ce fichier ne fait que poser le point d'accroche, et le poser
 * juste.
 *
 * <h2>Pourquoi ce point précis, vérifié par {@code javap -v} sur le jar patché 26.3</h2>
 *
 * <p>{@code ServerLevel.tickChunk(LevelChunk, int)} porte DEUX boucles : celle de la neige et de la
 * pluie (variable locale 7, alias inutile ici), puis celle du tirage aléatoire par section (variable
 * locale 12, jamais nommée : le jar patché ne publie aucun {@code LocalVariableTable} pour cette
 * méthode — {@code javap -v} le confirme). La seconde boucle est encadrée, dans le bytecode, par deux
 * repères stables :
 *
 * <pre>
 * ... SectionPos.sectionToBlockCoord(I)I     ← juste avant l'initialisation du compteur de boucle
 * iconst_0                                    ← LE compteur qu'on détourne (une seule occurrence ici)
 * istore  12
 * ...
 * ServerLevel.getBlockRandomPos(IIII)BlockPos ← DEUXIÈME occurrence dans la méthode (la première sert
 *                                                à la neige et à la pluie, plus haut)
 * </pre>
 *
 * <p>La tranche ({@code @Slice}) borne la recherche de la constante à cette seule fenêtre : sans elle,
 * {@code @ModifyExpressionValue} trouverait aussi le {@code iconst_0} de la boucle de neige et
 * détournerait la mauvaise boucle.
 *
 * <h2>Comment on détourne une boucle {@code for} sans y entrer</h2>
 *
 * <p>Vanilla écrit {@code for (int p = 0; p < cadence; p++) { ... }}. Remplacer la valeur du
 * {@code 0} initial par {@code cadence} rend le test d'entrée faux dès le premier tour — la boucle ne
 * s'exécute jamais, sans qu'aucune de ses instructions n'ait été touchée. C'est la même technique que
 * Lithium emploie sur ce point d'accroche depuis des années en production ({@code mixin.world
 * .chunk_ticking.random_block_ticking}) ; elle n'est pas réinventée ici, seulement revérifiée contre CE
 * jar.
 *
 * <p>{@link Loterie#tick} a déjà fait, à ce moment, les {@code cadence} tirages elle-même — ou aucun,
 * si elle a jugé la section trop dense pour son détour (voir son Javadoc). Dans ce second cas, on rend
 * la constante d'origine et la boucle de vanilla s'exécute normalement, au bit près.
 *
 * <h2>Pourquoi trois locales seulement, aucune par ordinal</h2>
 *
 * <p>Sans table de variables locales, capturer une locale par ordinal parmi plusieurs du même type
 * ({@code int}) est le genre d'erreur qui ne casse rien à la compilation ni au chargement, et lie
 * silencieusement la mauvaise valeur. Ce mixin n'en capture aucune de cette façon : {@code chunk} et
 * {@code cadence} sont les DEUX SEULS paramètres de la méthode ({@code argsOnly = true}, un match
 * chacun, sans ambiguïté possible) ; {@code section} est la SEULE locale de type
 * {@code LevelChunkSection} vivante à ce point du programme (le tableau {@code LevelChunkSection[]}
 * voisin est un type différent). Tout le reste dont {@link Loterie#tick} a besoin — les coordonnées du
 * chunk, l'altitude de la section — est recalculé à partir de ces trois-là, par des chemins publics,
 * plutôt que capturé.
 */
@Mixin(ServerLevel.class)
public abstract class LoterieMixin {
    @ModifyExpressionValue(
            method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V",
            at = @At(value = "CONSTANT", args = "intValue=0"),
            slice = @Slice(
                    from = @At(value = "INVOKE",
                            target = "Lnet/minecraft/core/SectionPos;sectionToBlockCoord(I)I"),
                    to = @At(value = "INVOKE",
                            target = "Lnet/minecraft/server/level/ServerLevel;"
                                    + "getBlockRandomPos(IIII)Lnet/minecraft/core/BlockPos;",
                            ordinal = 1)))
    private int lanterne$loterie(int depart,
            @Local(argsOnly = true) LevelChunk chunk,
            @Local(argsOnly = true) int cadence,
            @Local LevelChunkSection section) {
        if (Loterie.tick((ServerLevel) (Object) this, chunk, section, cadence)) {
            // Le compteur de boucle part déjà à la borne : `p < cadence` est faux au premier tour,
            // et le corps de la boucle vanille — désormais redondant, ce module a fait le travail —
            // ne s'exécute jamais.
            return cadence;
        }
        // Section trop dense pour ce détour (voir Loterie.SEUIL) : 0, inchangé. La boucle de vanilla
        // s'exécute normalement.
        return depart;
    }
}
