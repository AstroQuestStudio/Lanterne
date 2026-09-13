package fr.clubcitrouille.lanterne.mixin;

import java.util.List;
import java.util.Optional;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import fr.clubcitrouille.lanterne.core.Rubble;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'explosion, refaite là où elle coûte — et nulle part ailleurs.
 *
 * <p>Deux méthodes sont reprises. Elles ne changent ni les blocs détruits, ni le butin, ni la
 * poussée : ce sont les mêmes calculs, débarrassés du travail qu'ils refaisaient.
 *
 * <p>Le reste de {@code ServerExplosion} est laissé intact, y compris le ramassage du butin, dont on
 * sait pourtant qu'il est quadratique. Voir {@link Rubble} pour la raison.
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Vec3 center;
    @Shadow @Final private float radius;
    @Shadow @Final private ExplosionDamageCalculator damageCalculator;

    /**
     * Le tracé des rayons, sans les vingt mille objets qu'il créait.
     *
     * <h2>Ce que vanilla alloue pour trouver deux cent soixante-dix blocs</h2>
     *
     * <p>Le tracé part de la surface d'un cube de seize : mille trois cent cinquante-deux rayons, qui
     * avancent par pas de trois dixièmes de bloc jusqu'à épuisement de leur énergie. Pour une portée
     * de quatre, cela fait une quinzaine de pas par rayon, soit environ <b>vingt mille pas</b>. Et à
     * chaque pas, vanilla fait ceci :
     *
     * <pre>
     * BlockPos pos = BlockPos.containing(xp, yp, zp);          // un objet, jeté au pas suivant
     * BlockState block = this.level.getBlockState(pos);        // une descente jusqu'à la palette
     * FluidState fluid = this.level.getFluidState(pos);        // une seconde descente, au même endroit
     * </pre>
     *
     * <p>Vingt mille objets créés puis abandonnés, et quarante mille descentes dans la palette — pour
     * aboutir à un ensemble de deux à trois cents positions. Multiplié par vingt mille charges, c'est
     * l'essentiel des dix gigaoctets que le banc mesure sur une charge de dynamite.
     *
     * <h2>Trois corrections, aucune concession</h2>
     *
     * <ol>
     *   <li><b>Le fluide vient de l'état.</b> {@code LevelChunk.getFluidState} descend dans la section
     *       et rend {@code states.get(…).getFluidState()} — c'est-à-dire, exactement, le fluide de
     *       l'état de bloc qu'on vient de lire. La seconde descente donnait une valeur déjà en
     *       main.</li>
     *   <li><b>Un pas sur trois change de bloc.</b> Le pas vaut trois dixièmes ; il faut donc en
     *       moyenne trois pas et demi pour franchir une frontière. En mémorisant la dernière case
     *       entière visitée, on ne relit la palette que lorsqu'on en sort. L'énergie, elle, continue
     *       de décroître à chaque pas — c'est le calcul de vanilla, inchangé.</li>
     *   <li><b>Les positions sont des entiers.</b> L'ensemble des blocs touchés devient un ensemble de
     *       longs empaquetés. Il n'y a plus d'objet {@code BlockPos} que pour les positions
     *       réellement retenues, une fois chacune, à la toute fin.</li>
     * </ol>
     *
     * <p>L'ensemble passe de vingt mille allocations à deux cent soixante-dix, et de quarante mille
     * descentes dans la palette à six mille.
     *
     * <h2>L'ordre, et pourquoi il n'a pas d'importance</h2>
     *
     * <p>Un {@code HashSet} d'objets et un ensemble de longs ne s'énumèrent pas dans le même ordre.
     * C'est sans conséquence : {@code interactWithBlocks} commence par mélanger la liste. Le mélange
     * consomme le même nombre de tirages quelle que soit l'entrée, et rend une permutation uniforme
     * dans les deux cas. Une explosion n'était de toute façon pas reproductible — son énergie de
     * départ est tirée au sort rayon par rayon.
     */
    @WrapMethod(method = "calculateExplodedPositions")
    private List<BlockPos> lanterne$traceWithoutGarbage(Operation<List<BlockPos>> original) {
        if (!Settings.explosions()) {
            List<BlockPos> vanilla = original.call();
            Rubble.noteExplosion(vanilla.size()); // témoin d'équité : voir Rubble.noteExplosion
            return vanilla;
        }

        LongOpenHashSet touched = new LongOpenHashSet(512);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int xx = 0; xx < 16; xx++) {
            for (int yy = 0; yy < 16; yy++) {
                for (int zz = 0; zz < 16; zz++) {
                    if (xx != 0 && xx != 15 && yy != 0 && yy != 15 && zz != 0 && zz != 15) {
                        continue; // seule la surface du cube émet des rayons
                    }

                    double xd = xx / 15.0F * 2.0F - 1.0F;
                    double yd = yy / 15.0F * 2.0F - 1.0F;
                    double zd = zz / 15.0F * 2.0F - 1.0F;
                    double length = Math.sqrt(xd * xd + yd * yd + zd * zd);
                    xd /= length;
                    yd /= length;
                    zd /= length;

                    float remainingPower = this.radius * (0.7F + this.level.getRandom().nextFloat() * 0.6F);
                    double xp = this.center.x;
                    double yp = this.center.y;
                    double zp = this.center.z;

                    // La case entière du pas précédent, et ce qu'on y avait lu. Un pas de trois
                    // dixièmes ne change de case qu'une fois sur trois et demie ; le reste du temps,
                    // ces trois entiers évitent une descente complète dans la palette.
                    int lastX = Integer.MIN_VALUE;
                    int lastY = Integer.MIN_VALUE;
                    int lastZ = Integer.MIN_VALUE;
                    BlockState block = null;
                    boolean inBounds = true;

                    for (; remainingPower > 0.0F; remainingPower -= 0.22500001F) {
                        int bx = net.minecraft.util.Mth.floor(xp);
                        int by = net.minecraft.util.Mth.floor(yp);
                        int bz = net.minecraft.util.Mth.floor(zp);

                        if (bx != lastX || by != lastY || bz != lastZ) {
                            lastX = bx;
                            lastY = by;
                            lastZ = bz;
                            cursor.set(bx, by, bz);
                            inBounds = this.level.isInWorldBounds(cursor);
                            block = inBounds ? this.level.getBlockState(cursor) : null;
                        } else {
                            Rubble.noteLookupSkipped();
                        }

                        if (!inBounds) {
                            break;
                        }

                        // Le fluide de l'état est celui que level.getFluidState aurait rendu : la
                        // section le dérive de ce même état. Voir le commentaire de classe.
                        Optional<Float> resistance = this.damageCalculator.getBlockExplosionResistance(
                                (ServerExplosion) (Object) this, this.level, cursor, block, block.getFluidState());
                        if (resistance.isPresent()) {
                            remainingPower -= (resistance.get() + 0.3F) * 0.3F;
                        }

                        if (remainingPower > 0.0F
                                && this.damageCalculator.shouldBlockExplode(
                                        (ServerExplosion) (Object) this, this.level, cursor, block, remainingPower)) {
                            touched.add(BlockPos.asLong(bx, by, bz));
                        }

                        xp += xd * 0.3F;
                        yp += yd * 0.3F;
                        zp += zd * 0.3F;
                    }
                }
            }
        }

        ObjectArrayList<BlockPos> result = new ObjectArrayList<>(touched.size());
        for (LongIterator packed = touched.iterator(); packed.hasNext(); ) {
            result.add(BlockPos.of(packed.nextLong()));
        }
        Rubble.noteExplosion(result.size()); // témoin d'équité : voir Rubble.noteExplosion
        return result;
    }
}
