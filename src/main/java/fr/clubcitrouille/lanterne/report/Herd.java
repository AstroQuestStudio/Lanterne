package fr.clubcitrouille.lanterne.report;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * La charge d'essai : un troupeau posé de façon reproductible.
 *
 * <h2>Un anneau, et non un tas</h2>
 *
 * <p>Les entités sont réparties en spirale sur un disque, et non entassées sur un point. C'est ce
 * qui permet de mesurer le <em>gradient</em> : les plus proches restent en pleine simulation, les
 * plus lointaines dorment, et le résultat porte sur le mélange qu'on rencontre réellement en jeu.
 *
 * <p>Un tas au même endroit ne mesurerait qu'un seul niveau de détail, et donnerait une réponse qui
 * ne vaudrait que pour lui — flatteuse ou catastrophique selon la distance choisie, et fausse dans
 * les deux cas.
 *
 * <p>L'angle d'or répartit les points sans jamais les aligner, et la racine carrée du rang donne une
 * densité constante sur le disque. Deux détails sans importance pour le code, mais qui font la
 * différence entre une charge homogène et une charge qui se concentre au centre.
 */
public final class Herd {
    /**
     * Vide le monde de ses créatures, sans toucher au terrain.
     *
     * <h2>Pourquoi ne plus effacer le monde entre deux épreuves</h2>
     *
     * <p>Le banc supprimait le monde à chaque exécution, pour repartir d'un état propre. Le
     * profileur d'allocations a montré ce que cela coûtait : la génération de terrain — fonctions de
     * densité, splines, structures — dominait les allocations mesurées, jusqu'à trente pour cent du
     * total.
     *
     * <p>On ne mesurait donc pas le régime établi d'un serveur, mais celui d'une exploration. Tant
     * que c'était le cas, aucune optimisation de la lumière ou des palettes n'aurait pu se voir :
     * elle se serait perdue dans le bruit de la génération.
     *
     * <p>Le terrain est désormais conservé d'une épreuve à l'autre, et seules les créatures sont
     * retirées. La première exécution paie la génération ; les suivantes mesurent ce qu'on cherche.
     */
    public static int sweepEntities(ServerLevel level) {
        // On recense d'abord, on retire ensuite. Retirer pendant le parcours vide la collection
        // sous l'itérateur, qui rend alors des éléments nuls — le serveur s'est arrêté dessus au
        // premier essai, sur un « Cannot invoke Entity.discard() because entity is null ».
        java.util.List<net.minecraft.world.entity.Entity> doomed = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (entity != null && !(entity instanceof net.minecraft.world.entity.player.Player)) {
                doomed.add(entity);
            }
        }
        for (net.minecraft.world.entity.Entity entity : doomed) {
            entity.discard();
        }
        return doomed.size();
    }

    /**
     * Pose un champ de fours allumés, pour éprouver le sommeil à échéance.
     *
     * <p>Les fours sont posés en damier autour du joueur, chacun garni de combustible et de minerai.
     * C'est la charge type d'une base construite : quelques centaines de blocs-entités qui
     * travaillent en permanence sans que personne ne les regarde.
     */
    public static int ovens(ServerLevel level, int count, int spread) {
        int born = 0;
        int side = (int) Math.ceil(Math.sqrt(count));
        for (int i = 0; i < count; i++) {
            int x = (i % side) * 2 - spread / 2;
            int z = (i / side) * 2 - spread / 2;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
            level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.FURNACE
                    .defaultBlockState());
            if (level.getBlockEntity(pos)
                    instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity furnace) {
                furnace.setItem(0, new net.minecraft.world.item.ItemStack(
                        net.minecraft.world.item.Items.RAW_IRON, 64));
                furnace.setItem(1, new net.minecraft.world.item.ItemStack(
                        net.minecraft.world.item.Items.COAL, 64));
                furnace.setChanged();
                born++;
            }
        }
        return born;
    }

    /** L'angle d'or, en radians : la répartition la plus régulière qui soit sur un disque. */
    private static final double GOLDEN_ANGLE = 2.399963d;

    private Herd() {}

    public static int populate(ServerLevel level, double centreX, double centreZ,
                               int count, int radius) {
        int born = 0;
        for (int i = 0; i < count; i++) {
            double angle = i * GOLDEN_ANGLE;
            double spread = radius == 0 ? 0d : radius * Math.sqrt((i + 0.5d) / count);
            double x = centreX + Math.cos(angle) * spread;
            double z = centreZ + Math.sin(angle) * spread;

            Cow cow = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
            if (cow == null) {
                continue;
            }
            double y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
            cow.snapTo(x, y, z, 0f, 0f);
            // Sans cela, la charge s'évapore au premier passage de disparition, et la seconde moitié
            // du banc ne mesurerait pas le même monde que la première.
            cow.setPersistenceRequired();
            if (level.addFreshEntity(cow)) {
                born++;
            }
        }
        // On vérifie ce qui vit, et non ce qu'on a demandé. C'est cette ligne qui a révélé que les
        // deux mille vaches créées disparaissaient dans le tick, faute de chunk chargé pour les
        // accueillir — trois bancs durant, le mod semblait inutile alors qu'il n'avait rien à faire.
        int alive = 0;
        for (var ignored : level.getAllEntities()) {
            alive++;
        }
        if (alive < born) {
            fr.clubcitrouille.lanterne.Lanterne.LOG.warn(
                    "{} entités demandées, {} vivantes : des chunks manquaient à l'appel.",
                    born, alive);
        }
        return born;
    }
}
