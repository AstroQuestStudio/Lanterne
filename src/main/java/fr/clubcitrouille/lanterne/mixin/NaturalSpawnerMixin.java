package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le premier allocateur du serveur, une fois les entités maîtrisées.
 *
 * <h2>Ce que le profileur a désigné</h2>
 *
 * <p>Une fois le tick des créatures ramené à sa juste mesure, le classement des allocations change
 * complètement de tête. Le poste dominant n'est plus la simulation :
 *
 * <pre>
 * 41,0 %  NaturalSpawner.getRandomPosWithin → BlockPos   (2,29 Go)
 * </pre>
 *
 * <p><b>Deux gigaoctets et demi sur deux gigaoctets sept</b> — l'apparition naturelle des créatures
 * à elle seule représentait la moitié de tout ce que le serveur allouait encore. Et pour quoi ? Pour
 * tirer une position au hasard dans un chunk :
 *
 * <pre>
 * private static BlockPos getRandomPosWithin(Level level, LevelChunk chunk) {
 *     ChunkPos pos = chunk.getPos();
 *     int x = pos.getMinBlockX() + level.random.nextInt(16);
 *     int z = pos.getMinBlockZ() + level.random.nextInt(16);
 *     int topEmptyY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
 *     int y = Mth.randomBetweenInclusive(level.random, level.getMinY(), topEmptyY);
 *     return new BlockPos(x, y, z);
 * }
 * </pre>
 *
 * <p>Un objet neuf pour trois entiers, à chaque tentative d'apparition, pour chaque catégorie de
 * créature et chaque chunk éligible, à chaque tick.
 *
 * <h2>Pourquoi la réutilisation est sûre ici</h2>
 *
 * <p>La position rendue est <b>consommée immédiatement</b>, et le code appelant le montre sans
 * ambiguïté : il lit ses coordonnées, teste le bloc, puis crée <em>sa propre</em>
 * {@code MutableBlockPos} pour la suite du travail.
 *
 * <pre>
 * int yStart = start.getY();
 * BlockState state = chunk.getBlockState(start);
 * if (!state.isRedstoneConductor(chunk, start)) {
 *     BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();   // la sienne
 *     int x = start.getX();
 *     int z = start.getZ();
 * </pre>
 *
 * <p>Rien ne conserve {@code start} au-delà de l'appel : c'est la condition qui rend la
 * réutilisation légitime, et elle est vérifiée, non supposée.
 *
 * <h2>Le tirage reste le même</h2>
 *
 * <p>On reprend le générateur du monde dans le même ordre — deux tirages d'entier sur seize, puis un
 * tirage entre le plancher et la hauteur de surface. Mêmes positions tirées, mêmes créatures aux
 * mêmes endroits : l'apparition naturelle n'est pas dégradée d'un iota, seule l'allocation disparaît.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    /**
     * La position réutilisée.
     *
     * <p>Statique parce que la méthode l'est, et sans danger parce que l'apparition naturelle
     * s'exécute sur le fil du serveur, comme le reste du tick des mondes.
     */
    @Unique
    private static final BlockPos.MutableBlockPos LANTERNE_SCRATCH = new BlockPos.MutableBlockPos();

    @Inject(method = "getRandomPosWithin", at = @At("HEAD"), cancellable = true)
    private static void lanterne$reusePosition(Level level, LevelChunk chunk,
                                               CallbackInfoReturnable<BlockPos> callback) {
        if (!Settings.scratchPos()) {
            return;
        }
        // {@code level.random} est protégé ; {@code getRandom()} rend la même instance. On emprunte
        // donc l'accesseur public plutôt qu'un transformateur d'accès, qui serait une dépendance de
        // plus pour rien.
        var random = level.getRandom();
        var pos = chunk.getPos();
        int x = pos.getMinBlockX() + random.nextInt(16);
        int z = pos.getMinBlockZ() + random.nextInt(16);
        int topEmptyY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
        int y = Mth.randomBetweenInclusive(random, level.getMinY(), topEmptyY);
        callback.setReturnValue(LANTERNE_SCRATCH.set(x, y, z));
    }
}
