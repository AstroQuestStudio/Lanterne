package fr.clubcitrouille.lanterne.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import fr.clubcitrouille.lanterne.core.Digue;

/**
 * La carte qui ne fait plus générer le terrain qu'elle dessine.
 *
 * <h2>Le chargement</h2>
 *
 * <p>{@code MapItem.update} (ligne 108 de {@code MapItem.java}) appelle
 * {@code level.getChunk(x, z)} — sans variante « seulement si présent ». Cette surcharge vaut
 * {@code getChunk(x, z, ChunkStatus.FULL, true)} : le dernier argument est <b>vrai</b>, donc le jeu
 * charge <em>et génère</em> ce qui manque, en bloquant le tick.
 *
 * <p>La boucle balaie cent vingt-huit blocs autour du porteur, quelle que soit l'échelle de la
 * carte, soit huit chunks de rayon. En régime établi ils sont tous chargés et ce mixin ne se
 * déclenche jamais. Le cas qui compte est l'autre : un joueur qui <b>explore</b>, carte en main. Les
 * chunks devant lui sont en cours de génération — leur ticket est posé, leur contenu non — et la
 * carte va chercher elle-même chacun d'eux, un par un, au milieu du tick. C'est le gel de plusieurs
 * centaines de millisecondes que décrit {@code ServerCore}, et la cadence de déplacement du joueur
 * fait qu'il se répète.
 *
 * <h2>Ce qu'on perd</h2>
 *
 * <p>Une case de carte reste blanche tant que son chunk n'est pas là. Elle se remplira au passage
 * suivant : {@code holdingPlayer.step} balaie une colonne sur seize à chaque tick, et le joueur qui
 * explore repasse. <b>Rien n'est écrit de faux</b> — la couleur n'est simplement pas encore écrite.
 *
 * <h2>Pourquoi deux redirections et non une</h2>
 *
 * <p>Le jeu écrit {@code LevelChunk chunk = level.getChunk(…); if (!chunk.isEmpty())}. Rendre
 * {@code null} au premier appel ferait éclater le second sur un {@code NullPointerException}. La
 * seconde redirection donne à {@code null} le sens qu'il doit avoir ici : « vide », donc « passe ton
 * chemin ». C'est la branche que le jeu emprunte déjà pour un chunk sans blocs.
 */
@Mixin(MapItem.class)
public abstract class MapChunkMixin {
    @Redirect(
            method = "update",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getChunk(II)"
                            + "Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private @Nullable LevelChunk lanterne$onlyIfLoaded(Level level, int chunkX, int chunkZ) {
        if (!Digue.guards(level)) {
            return level.getChunk(chunkX, chunkZ);
        }
        LevelChunk chunk = Digue.now(level, chunkX, chunkZ);
        if (chunk == null) {
            Digue.noteMap();
        }
        return chunk;
    }

    @Redirect(
            method = "update",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/LevelChunk;isEmpty()Z"))
    private boolean lanterne$absentCountsAsEmpty(@Nullable LevelChunk chunk) {
        // Sans le test de module : si la digue vient d'être éteinte entre les deux redirections, la
        // première a déjà pu rendre null. Un NullPointerException pour un interrupteur basculé au
        // mauvais tick serait une panne inexplicable — et le banc bascule le maître vingt fois par
        // mesure.
        return chunk == null || chunk.isEmpty();
    }
}
