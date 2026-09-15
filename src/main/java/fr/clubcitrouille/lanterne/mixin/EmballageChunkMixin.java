package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import fr.clubcitrouille.lanterne.core.Emballage;

/**
 * Les prises d'invalidation : tout ce qui rend périmé le paquet d'un chunk.
 *
 * <p>C'est le fichier qui décide si le module est juste ou dangereux, et l'énumération des chemins
 * est dans {@link Emballage}. Ici, seulement les cinq points d'accroche et la raison de chacun.
 *
 * <h2>1. {@code markUnsaved} : l'entonnoir</h2>
 *
 * <p>Un chunk « non sauvegardé » est par définition un chunk qui ne ressemble plus à ce qui est sur
 * le disque. C'est exactement la question qu'on se pose. Cette seule prise attrape les blocs, le
 * contenu des entités de bloc via {@code BlockEntity.setChanged}, <b>la lumière</b> via
 * {@code ChunkHolder.sectionLightChanged}, les biomes repeints par {@code /fillbiome}, les
 * structures, les attachements NeoForge et la lumière auxiliaire.
 *
 * <p>C'est aussi le chemin le plus chaud du module — une pose de bloc y passe, un objet qui traverse
 * un entonnoir aussi. {@link Emballage#oublie} s'arrête donc sur une lecture de champ volatile
 * quand le cache est vide.
 *
 * <h2>2. {@code setBlockState} : le trou de l'entonnoir</h2>
 *
 * <p>{@code setBlockState} appelle bien {@code markUnsaved()} — mais à sa dernière ligne, et il a
 * une sortie anticipée qui la saute :
 *
 * <pre>
 * BlockState oldState = section.setBlockState(localX, localY, localZ, state);   // la section a change
 * ...
 * if (!section.getBlockState(localX, localY, localZ).is(newBlock)) {
 *     return null;                                                              // sans markUnsaved
 * }
 * </pre>
 *
 * <p>Ce cas arrive quand {@code affectNeighborsAfterRemoval} a, en cascade, remplacé le bloc qu'on
 * venait de poser. Il est rare ; il n'est pas impossible. Une prise en <b>tête</b> de la méthode le
 * rend sans effet pour nous : on a déjà oublié le colis avant que quoi que ce soit ne change.
 *
 * <p>Oui, cela fait deux retraits pour une pose de bloc ordinaire. Le second ne trouve rien, ce qui
 * est le cas le moins cher d'une table de hachage. C'est le prix d'une garantie qui ne dépend pas
 * d'une lecture fine du flot de contrôle de Mojang, et il est bon marché.
 *
 * <h2>3 et 4. Les entités de bloc, qui ne marquent rien</h2>
 *
 * <p>{@code setBlockEntity} et {@code removeBlockEntity} n'appellent <b>pas</b> {@code markUnsaved}.
 * Le plus souvent elles sont atteintes depuis {@code setBlockState}, déjà couvert — mais pas
 * toujours :
 *
 * <ul>
 *   <li>{@code getBlockEntity(pos, IMMEDIATE)} en crée une à la première lecture ;</li>
 *   <li>une entité de bloc encore empaquetée est promue à la première lecture ;</li>
 *   <li>le ticker en retire une quand elle se déclare supprimée.</li>
 * </ul>
 *
 * <p>Or la liste des entités de bloc — et l'étiquette de chacune — est dans le paquet. Ces deux
 * méthodes sont froides : les couvrir ne coûte rien et ferme la question.
 *
 * <h2>5. {@code setLoaded} : rendre la mémoire</h2>
 *
 * <p>{@code ChunkMap} appelle {@code setLoaded(false)} quand il décharge. Le colis n'est plus
 * servable — la référence faible vers le chunk s'apprête à se vider — et il occupe de la place sous
 * le plafond. On le retire tout de suite plutôt que d'attendre qu'une éviction le trouve.
 *
 * <p>L'appel symétrique {@code setLoaded(true)} passe ici aussi : il n'y a rien en cache pour un
 * chunk qui vient d'arriver, la prise ne fait donc rien. Distinguer les deux coûterait une
 * condition pour ne rien gagner.
 */
@Mixin(LevelChunk.class)
public abstract class EmballageChunkMixin {

    /** Voir §1 : l'entonnoir de tout ce qui rend un chunk différent du disque. */
    @Inject(method = "markUnsaved()V", at = @At("HEAD"))
    private void lanterne$dropOnChange(CallbackInfo callback) {
        Emballage.oublie((LevelChunk) (Object) this);
    }

    /** Voir §2 : la sortie anticipée qui saute {@code markUnsaved}. */
    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;I)"
                    + "Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"))
    private void lanterne$dropOnBlockState(
            BlockPos position, BlockState etat, int drapeaux, CallbackInfoReturnable<BlockState> callback) {
        Emballage.oublie((LevelChunk) (Object) this);
    }

    /** Voir §3 : entité de bloc créée ou promue hors de {@code setBlockState}. */
    @Inject(method = "setBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;)V", at = @At("HEAD"))
    private void lanterne$dropOnBlockEntityAdded(BlockEntity entite, CallbackInfo callback) {
        Emballage.oublie((LevelChunk) (Object) this);
    }

    /** Voir §4 : entité de bloc retirée par son ticker, sans passer par {@code setBlockState}. */
    @Inject(method = "removeBlockEntity(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"))
    private void lanterne$dropOnBlockEntityRemoved(BlockPos position, CallbackInfo callback) {
        Emballage.oublie((LevelChunk) (Object) this);
    }

    /** Voir §5 : le chunk est déchargé, la place est rendue. */
    @Inject(method = "setLoaded(Z)V", at = @At("HEAD"))
    private void lanterne$dropOnUnload(boolean charge, CallbackInfo callback) {
        Emballage.oublie((LevelChunk) (Object) this);
    }
}
