package fr.clubcitrouille.lanterne.content;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Ferme;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'Ancre de ferme : l'Ancre de chunk, plus les monstres.
 *
 * <h2>Un bloc à part, et non un mode de l'Ancre</h2>
 *
 * <p>Un mode se règle au clic et s'oublie ; il ne se voit pas. Or ce bloc engage bien plus que
 * l'Ancre : il fait naître des monstres loin de tout joueur, sur le plafond global de tout le
 * serveur. Un joueur survie doit pouvoir reconnaître d'un coup d'œil ce qui est posé dans sa ferme,
 * et un administrateur doit pouvoir le retrouver dans une liste de blocs — d'où un bloc distinct, une
 * texture distincte, une recette qui part de l'Ancre elle-même.
 *
 * <h2>Ce qu'il fait</h2>
 *
 * <p>Il pose sur son chunk le ticket {@code lanterne:ferme} (voir {@link Ferme#TICKET}) : persistant,
 * il charge et simule le chunk comme {@code /forceload}, et y autorise l'apparition naturelle des
 * monstres tant qu'un joueur est connecté — dans la dimension ou, avec {@code ferme_toute_dimension}
 * (par défaut), dans n'importe quelle autre. Tout le reste — pourquoi
 * vanilla s'y oppose en cinq endroits, et comment chacun est levé — est dans {@link Ferme}.
 *
 * <h2>Plusieurs ancres de ferme dans un même chunk</h2>
 *
 * <p>Même règle que l'Ancre : le ticket n'est pas un compteur. On vérifie qu'il ne reste aucune autre
 * ancre de ferme dans le chunk avant de le relâcher. Une Ancre de chunk voisine n'est pas concernée :
 * les deux tickets sont indépendants, chacun tient ce qu'il a promis.
 */
public class AncreDeFerme extends Block {
    public AncreDeFerme(Properties properties) {
        super(properties);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
            boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel server && !oldState.is(this) && Settings.ferme()) {
            ChunkPos chunk = ChunkPos.containing(pos);
            Ferme.anchor(server, chunk);
            Lanterne.LOG.info("[ANCRE] ferme : chunk ({}, {}) gardé chargé, monstres autorisés loin des"
                            + " joueurs (plafond {} à {} blocs) — ancre de ferme posée en {}.",
                    chunk.x(), chunk.z(), Settings.fermePlafond(), Ferme.RAYON_PLAFOND,
                    pos.toShortString());
            Ferme.alimentation(server, pos, server.hasNeighborSignal(pos));
        }
    }

    /** Un signal redstone sur l'ancre arrête les fermes voisines (voir {@code Ferme.alimentation}). */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
            net.minecraft.world.level.block.Block neighbor,
            net.minecraft.world.level.redstone.@org.jspecify.annotations.Nullable Orientation orientation,
            boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighbor, orientation, movedByPiston);
        if (level instanceof ServerLevel server) {
            Ferme.alimentation(server, pos, server.hasNeighborSignal(pos));
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
            boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        Ferme.alimentation(level, pos, false);
        ChunkPos chunk = ChunkPos.containing(pos);
        if (anotherIn(level, chunk, pos)) {
            Lanterne.LOG.info("[ANCRE] ferme : chunk ({}, {}) reste une ferme — une autre ancre de"
                    + " ferme s'y trouve.", chunk.x(), chunk.z());
            return;
        }
        Ferme.release(level, chunk);
        Lanterne.LOG.info("[ANCRE] ferme : chunk ({}, {}) relâché — plus aucune ancre de ferme.",
                chunk.x(), chunk.z());
    }

    /**
     * Reste-t-il une ancre de ferme dans ce chunk ? Balayage de la colonne entière, comme
     * {@code Anchor.anotherAnchorIn} : sans conséquence, on ne casse pas ce bloc vingt fois par
     * seconde.
     */
    private static boolean anotherIn(ServerLevel level, ChunkPos chunk, BlockPos removed) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                for (int y = level.getMinY(); y < level.getMaxY(); y++) {
                    cursor.set(x, y, z);
                    if (!cursor.equals(removed)
                            && level.getBlockState(cursor).getBlock() instanceof AncreDeFerme) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
