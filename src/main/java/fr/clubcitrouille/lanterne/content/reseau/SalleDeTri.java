package fr.clubcitrouille.lanterne.content.reseau;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;

import fr.clubcitrouille.lanterne.content.trieur.TrieurBlockEntity;

/**
 * La salle de tri vue par un Guichet (26.3.142) : les coffres que les Trieurs voisins remplissent,
 * retrouvés en suivant le chemin RÉEL des objets — sans Aiguillage, sans liaison à la main.
 *
 * <h2>Pourquoi suivre le flux, et pas « tous les coffres de la zone »</h2>
 *
 * <p>Une salle réelle (78 colonnes, un Trieur au-dessus d'une cascade de huit coffres doubles) compte
 * plus de mille deux cents moitiés de coffre : un Aiguillage par moitié est hors de question. Prendre
 * tous les coffres d'un rayon attraperait aussi le coffre de collecte du Guichet, ceux de l'antichambre,
 * ceux du voisin. Le flux, lui, ne ment pas : un coffre fait partie de la salle si un objet trié PEUT y
 * arriver. Les arêtes suivies sont exactement celles que le jeu emprunte :
 *
 * <ul>
 * <li>un entonnoir (Trieur compris) pousse vers sa face {@code facing} ;</li>
 * <li>un Trieur non orienté vers le bas cède d'abord au Trieur de dessous (la priorité au tri de
 * {@code TrieurTransfert}) ;</li>
 * <li>un conteneur est vidé par l'entonnoir posé sous lui ; un coffre double compte ses deux moitiés ;</li>
 * <li>le stockage posé AU-DESSUS d'un entonnoir (le coffre d'entrée) en fait partie aussi.</li>
 * </ul>
 *
 * <p>Graines : chaque {@link TrieurBlockEntity} de la zone, trouvé dans la table des blocs-entités des
 * chunks DÉJÀ chargés ({@code getChunkNow} : jamais de chargement forcé) — quelques dizaines de chunks,
 * quelques milliers de blocs-entités au pire, pas un balayage de blocs. Ne sont retenus comme stockage
 * que coffres, tonneaux et boîtes de Shulker : un four ou un entonnoir traversé n'est qu'un passage.
 *
 * <h2>Le coût, parce que le serveur est mono-cœur</h2>
 *
 * <p>Un relevé coûte un parcours des chunks de la zone puis un parcours en largeur borné ({@link
 * #NOEUDS_MAX}) — une poignée de milliers de lectures de bloc sur la salle réelle. Il n'est JAMAIS
 * refait en tâche de fond : {@code GuichetBlockEntity} le garde {@code GuichetBlockEntity#FRAICHEUR_TICKS}
 * et ne le recalcule qu'à l'ouverture, à une demande, ou pendant qu'un joueur a l'écran ouvert.
 */
public final class SalleDeTri {
    /** Borne dure du parcours : de quoi couvrir quatre salles comme celle du serveur. */
    static final int NOEUDS_MAX = 8192;

    private SalleDeTri() {}

    /** Le résultat d'un relevé : les stockages trouvés (positions de bloc, une par moitié), et le compte de Trieurs. */
    public record Releve(List<BlockPos> stockages, int trieurs) {
        public static final Releve VIDE = new Releve(List.of(), 0);
    }

    /** Un bloc-entité qui GARDE des objets, par opposition à un passage (entonnoir, four...). */
    static boolean stockage(BlockEntity be) {
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity;
    }

    /**
     * Relève la salle autour de {@code centre} : Trieurs dans un pavé de {@code portee} blocs à
     * l'horizontale, {@code portee / 2} à la verticale ; le parcours peut déborder de seize blocs pour
     * suivre une cascade qui descend sous la zone.
     */
    public static Releve relever(ServerLevel level, BlockPos centre, int portee) {
        if (portee <= 0) {
            return Releve.VIDE;
        }
        int vertical = Math.max(8, portee / 2);
        ArrayDeque<BlockPos> file = new ArrayDeque<>();
        Set<BlockPos> vus = new HashSet<>();
        int trieurs = 0;
        int cx0 = SectionPos.blockToSectionCoord(centre.getX() - portee);
        int cx1 = SectionPos.blockToSectionCoord(centre.getX() + portee);
        int cz0 = SectionPos.blockToSectionCoord(centre.getZ() - portee);
        int cz1 = SectionPos.blockToSectionCoord(centre.getZ() + portee);
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos p = be.getBlockPos();
                    if (be instanceof TrieurBlockEntity && !be.isRemoved()
                            && Math.abs(p.getX() - centre.getX()) <= portee
                            && Math.abs(p.getZ() - centre.getZ()) <= portee
                            && Math.abs(p.getY() - centre.getY()) <= vertical
                            && vus.add(p.immutable())) {
                        file.add(p.immutable());
                        trieurs++;
                    }
                }
            }
        }
        if (file.isEmpty()) {
            return Releve.VIDE;
        }
        int bord = portee + 16;
        List<BlockPos> stockages = new ArrayList<>();
        while (!file.isEmpty() && vus.size() < NOEUDS_MAX) {
            BlockPos pos = file.poll();
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof HopperBlockEntity) {
                BlockState etat = be.getBlockState();
                if (!etat.hasProperty(HopperBlock.FACING)) {
                    continue;
                }
                Direction facing = etat.getValue(HopperBlock.FACING);
                suivre(level, centre, bord, vus, file, pos.relative(facing), false);
                if (be instanceof TrieurBlockEntity && facing != Direction.DOWN) {
                    suivre(level, centre, bord, vus, file, pos.below(), false);
                }
                // Le coffre d'entrée : un stockage au-dessus d'un entonnoir le nourrit.
                suivre(level, centre, bord, vus, file, pos.above(), true);
            } else if (be != null && stockage(be)) {
                stockages.add(pos);
                BlockState etat = be.getBlockState();
                if (etat.getBlock() instanceof ChestBlock && etat.hasProperty(ChestBlock.TYPE)
                        && etat.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    suivre(level, centre, bord, vus, file, ChestBlock.getConnectedBlockPos(pos, etat), false);
                }
                suivre(level, centre, bord, vus, file, pos.below(), false);
            }
        }
        return new Releve(List.copyOf(stockages), trieurs);
    }

    /**
     * Met {@code pos} en file si c'est un entonnoir ou un stockage chargé, pas déjà vu, dans les bornes.
     * {@code stockageSeul} : n'accepte qu'un stockage (le coffre d'entrée), jamais un autre entonnoir —
     * remonter le long des entonnoirs ferait déborder la salle sur tout ce qui l'alimente.
     */
    private static void suivre(ServerLevel level, BlockPos centre, int bord, Set<BlockPos> vus,
            ArrayDeque<BlockPos> file, BlockPos pos, boolean stockageSeul) {
        if (Math.abs(pos.getX() - centre.getX()) > bord || Math.abs(pos.getZ() - centre.getZ()) > bord
                || Math.abs(pos.getY() - centre.getY()) > bord || vus.contains(pos) || !level.isLoaded(pos)) {
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || be.isRemoved()) {
            return;
        }
        boolean retenu = stockageSeul ? stockage(be) : (be instanceof HopperBlockEntity || stockage(be));
        if (retenu) {
            BlockPos fige = pos.immutable();
            vus.add(fige);
            file.add(fige);
        }
    }
}
