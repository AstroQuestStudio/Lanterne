package fr.clubcitrouille.lanterne.content;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * L'Ancre : un bloc qui garde son chunk chargé, et rien d'autre.
 *
 * <h2>Pourquoi un mod d'optimisation ajoute un bloc</h2>
 *
 * <p>Cela peut sembler contradictoire — ce mod passe son temps à <em>retirer</em> du travail, et
 * voici un objet dont la seule fonction est d'en <em>ajouter</em>. La contradiction n'est
 * qu'apparente, et elle tient en une phrase : <b>une ferme qui tourne quand personne ne la regarde
 * est utile ; cinq cents chunks qui tickent pour rien ne le sont pas.</b>
 *
 * <p>Les mods de chargement de chunks existants souffrent du défaut inverse de ce projet : ils
 * chargent large. Un rayon de trois, de cinq, parfois « tout le tronçon » — c'est-à-dire des dizaines
 * de chunks pour une machine qui en occupe un. Le joueur ne demandait qu'une chose : que sa ferme
 * continue de tourner.
 *
 * <p>Celle-ci charge <b>son propre chunk</b>. Un seul, celui où elle est posée. Ce qui déborde n'est
 * pas gardé, et c'est le point : on sait exactement ce qu'on paie, et l'on paie exactement ce qu'on
 * a demandé.
 *
 * <h2>Ce qu'elle coûte, et pourquoi elle doit coûter</h2>
 *
 * <p>Un chunk chargé en permanence, c'est de la mémoire retenue et du temps de tick, indéfiniment.
 * Une ancre qu'on poserait à la légère serait une dette qu'on oublie. Sa recette est donc chère —
 * elle demande de la netherite — et c'est délibéré : le prix doit rappeler qu'on prend un
 * engagement.
 *
 * <h2>Comment elle tient sa promesse</h2>
 *
 * <p>Elle s'appuie sur le chargement forcé du jeu, celui de la commande {@code /forceload}. Ce
 * mécanisme est <b>persistant</b> : il est écrit dans les données du monde, survit au redémarrage du
 * serveur, et ne dépend d'aucun code de ce mod pour être rétabli. Une ancre posée reste efficace même
 * si l'on désinstalle Lanterne — le chunk reste chargé, et {@code /forceload query} le dira.
 *
 * <p>Cette propriété n'est pas un détail : un mod de chargement de chunks qui perdrait ses tickets à
 * la moindre panne laisserait des fermes à l'arrêt sans que personne comprenne pourquoi.
 *
 * <h2>Plusieurs ancres dans un même chunk</h2>
 *
 * <p>Le chargement forcé n'est pas un compteur, mais un état : le chunk est forcé ou il ne l'est pas.
 * Retirer une ancre alors qu'une autre reste dans le même chunk déchargerait donc ce que la seconde
 * était censée tenir. On vérifie avant de relâcher.
 */
public class Anchor extends Block {
    public Anchor(Properties properties) {
        super(properties);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
            boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel server && !oldState.is(this)
                && fr.clubcitrouille.lanterne.core.Settings.anchor()) {
            ChunkPos chunk = ChunkPos.containing(pos);
            server.setChunkForced(chunk.x(), chunk.z(), true);
            Lanterne.LOG.info("[ANCRE] chunk ({}, {}) gardé chargé — une ancre y a été posée.",
                    chunk.x(), chunk.z());
        }
    }

    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
            boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        ChunkPos chunk = ChunkPos.containing(pos);
        if (anotherAnchorIn(level, chunk, pos)) {
            Lanterne.LOG.info("[ANCRE] chunk ({}, {}) reste chargé — une autre ancre s'y trouve.",
                    chunk.x(), chunk.z());
            return;
        }
        level.setChunkForced(chunk.x(), chunk.z(), false);
        Lanterne.LOG.info("[ANCRE] chunk ({}, {}) relâché — plus aucune ancre.", chunk.x(), chunk.z());
    }

    /**
     * Reste-t-il une ancre dans ce chunk, en dehors de celle qu'on retire ?
     *
     * <p>On balaie la colonne entière du chunk. C'est seize par seize par la hauteur du monde, soit
     * une centaine de milliers de positions — ce qui serait inacceptable dans une boucle de tick, et
     * qui est sans conséquence ici : on ne casse pas une ancre vingt fois par seconde.
     */
    private static boolean anotherAnchorIn(ServerLevel level, ChunkPos chunk, BlockPos removed) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
            for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                for (int y = level.getMinY(); y < level.getMaxY(); y++) {
                    cursor.set(x, y, z);
                    if (!cursor.equals(removed)
                            && level.getBlockState(cursor).getBlock() instanceof Anchor) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
