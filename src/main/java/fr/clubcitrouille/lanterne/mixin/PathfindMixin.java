package fr.clubcitrouille.lanterne.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;

import fr.clubcitrouille.lanterne.core.Impasse;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le point d'accroche de {@link Impasse} : la seule recherche A* réelle du jeu.
 *
 * <h2>Un seul entonnoir, quel que soit le mob</h2>
 *
 * <p>{@code PathNavigation.createPath(Set, int, boolean, int, float)} est le point où
 * <b>toutes</b> les surcharges publiques convergent — {@code GroundPathNavigation},
 * {@code FlyingPathNavigation}, {@code WaterBoundPathNavigation} et
 * {@code AmphibiousPathNavigation} n'en redéfinissent aucune, vérifié par lecture du jar merged
 * 26.3 : un seul mixin, sur la classe abstraite, couvre donc tout ce qui marche, nage ou vole.
 *
 * <p>La cible du redirect est l'appel {@code this.pathFinder.findPath(region, mob, targets, f,
 * i, f2)}, celui qui alloue une {@code PathNavigationRegion} et explore les nœuds — le seul coût
 * réel de la méthode. Tout ce qui précède dans {@code createPath} (vide, hors monde, chemin en
 * cours déjà valide) reste exécuté sans y toucher : on ne raccourcit que l'appel qui coûte.
 *
 * <h2>Où vit le souvenir</h2>
 *
 * <p>Directement sur l'instance de {@code PathNavigation}, qui est déjà un-à-un avec son mob :
 * aucune table partagée, donc aucune fuite à purger et aucun risque de servir à un mob le
 * résultat d'un autre. Le souvenir disparaît avec le mob lui-même.
 */
@Mixin(PathNavigation.class)
public abstract class PathfindMixin {
    @Unique
    private long lanterne$lastFailStart = Long.MIN_VALUE;
    @Unique
    private long lanterne$lastFailTargets;
    @Unique
    private long lanterne$lastFailTick = Long.MIN_VALUE;

    /**
     * Remplace l'appel de recherche par le souvenir du dernier échec, quand il tient encore.
     *
     * <p>Le paramètre {@code finder} est le récepteur de l'appel redirigé ({@code this.pathFinder}),
     * et {@code mob} celui que {@code createPath} passait déjà à {@code findPath} — on n'a donc
     * besoin d'aucun {@code @Shadow} pour l'atteindre.
     */
    @Redirect(
            method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/pathfinder/PathFinder;"
                            + "findPath(Lnet/minecraft/world/level/PathNavigationRegion;"
                            + "Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)"
                            + "Lnet/minecraft/world/level/pathfinder/Path;"))
    private Path lanterne$cachedFindPath(PathFinder finder, PathNavigationRegion region, Mob mob,
            Set<BlockPos> targets, float distance, int range, float multiplier) {
        if (!Settings.impasse()) {
            return finder.findPath(region, mob, targets, distance, range, multiplier);
        }

        long now = mob.level().getGameTime();
        long startKey = mob.blockPosition().asLong();
        long targetKey = Impasse.hashTargets(targets);

        if (this.lanterne$lastFailTick != Long.MIN_VALUE
                && now - this.lanterne$lastFailTick < Impasse.TTL_TICKS
                && this.lanterne$lastFailStart == startKey
                && this.lanterne$lastFailTargets == targetKey) {
            Impasse.noteSkipped();
            return null;
        }

        Path result = finder.findPath(region, mob, targets, distance, range, multiplier);
        if (result == null) {
            this.lanterne$lastFailStart = startKey;
            this.lanterne$lastFailTargets = targetKey;
            this.lanterne$lastFailTick = now;
            Impasse.noteRecorded();
        }
        return result;
    }
}
