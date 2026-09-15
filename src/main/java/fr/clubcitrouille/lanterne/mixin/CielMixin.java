package fr.clubcitrouille.lanterne.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;

import fr.clubcitrouille.lanterne.core.Ciel;

/**
 * Le ciel branché sur la boucle de remplissage.
 *
 * <h2>Cinq prises, et toutes sur une signature complète</h2>
 *
 * <p>Les cinq appels que {@code doFill} fait au {@code NoiseChunk} dans sa boucle sont uniques dans
 * la méthode, et ce sont les cinq seuls qu'on touche :
 *
 * <pre>
 * noiseChunk.selectCellYZ(cellYIndex, cellZIndex);   ← on demande le verdict juste après
 * noiseChunk.updateForY(posY, factorY);              ← 8 fois par cellule
 * noiseChunk.updateForX(posX, factorX);              ← 32 fois
 * noiseChunk.updateForZ(posZ, factorZ);              ← 128 fois
 * BlockState state = noiseChunk.getInterpolatedState();  ← 128 fois
 * </pre>
 *
 * <p>Les trois {@code updateFor*} sont ce que coûte l'interpolation, et il faut compter les
 * interpolateurs pour voir ce que c'est. Le routeur du surmonde pose <b>huit</b> marqueurs
 * {@code Interpolated} : la densité finale, les quatre fonctions de nouilles et les trois de
 * filons. Chaque {@code updateForY} fait quatre {@code lerp} par interpolateur, chaque
 * {@code updateForX} deux, chaque {@code updateForZ} un — soit
 * {@code 8×8×4 + 32×8×2 + 128×8 ≈ 1800 lerp} par cellule, tous jetés si la cellule est du ciel.
 * {@code getInterpolatedState} est l'autre moitié : descente des règles de matériaux, lecture du
 * cache de cellule, appel à l'aquifère.
 *
 * <p>Aucune de ces cibles n'est un ordinal, un nom de variable locale ni un numéro de lambda :
 * {@code tools/verifie_mixins.py} peut les confronter toutes les cinq au bytecode réel.
 *
 * <h2>Ce qu'on ne fait pas, et ce que ça coûte</h2>
 *
 * <p>La boucle de vanilla tourne toujours : on en vide le corps, on ne la saute pas. Il reste donc
 * par cellule de ciel 128 tours d'arithmétique entière et huit {@code getSectionIndex} — ce qui est
 * l'ordre de grandeur du bruit devant ce qu'on retire. Sauter la boucle demanderait de viser le
 * compteur {@code yInCell} par son rang dans la table des variables locales, et ce dépôt s'interdit
 * ce genre de cible ; {@code JigsawPlacerMixin} dit pourquoi, et il l'a appris à ses dépens.
 *
 * <p>La contrepartie est heureuse : {@code debugPreliminarySurfaceLevel} continue d'être appelé, sur
 * exactement l'état que vanilla lui aurait donné. Le mode {@code DEBUG_AQUIFERS} n'a donc pas besoin
 * d'éteindre le module — ses blocs de miel et de slime sont posés comme avant.
 *
 * <p>Voir {@code core.Ciel} pour la démonstration que la cellule est bien de l'air, et
 * {@code CielCellMixin} pour l'endroit où elle se fait.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class CielMixin {
    /**
     * Le verdict se prend juste après la sélection, et pas avant.
     *
     * <p>C'est {@code selectCellYZ} qui remplit le cache des 128 densités ; avant lui, il n'y a rien
     * à lire. On le laisse donc faire son travail entier, puis on regarde ce qu'il a écrit.
     */
    @Redirect(
            method = "doFill",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;selectCellYZ(II)V"))
    private void lanterne$examine(NoiseChunk noiseChunk, int cellYIndex, int cellZIndex) {
        noiseChunk.selectCellYZ(cellYIndex, cellZIndex);
        ((Ciel.Cell)noiseChunk).lanterne$examineCell();
    }

    @Redirect(
            method = "doFill",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;updateForY(ID)V"))
    private void lanterne$updateY(NoiseChunk noiseChunk, int posY, double factorY) {
        if (!((Ciel.Cell)noiseChunk).lanterne$skyCell()) {
            noiseChunk.updateForY(posY, factorY);
        }
    }

    @Redirect(
            method = "doFill",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;updateForX(ID)V"))
    private void lanterne$updateX(NoiseChunk noiseChunk, int posX, double factorX) {
        if (!((Ciel.Cell)noiseChunk).lanterne$skyCell()) {
            noiseChunk.updateForX(posX, factorX);
        }
    }

    /**
     * Le seul des trois dont le saut demande une justification.
     *
     * <p>{@code updateForZ} incrémente {@code interpolationCounter}, que {@code CacheOnce} lit pour
     * savoir si la position a changé. Sauter des incréments est sans effet : le compteur ne fait que
     * croître, et tout calcul est précédé d'un {@code updateForZ} — deux positions distinctes ne
     * peuvent donc jamais porter le même jeton. Dans une cellule vidée, aucun calcul n'a lieu, donc
     * personne ne lit le compteur.
     */
    @Redirect(
            method = "doFill",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;updateForZ(ID)V"))
    private void lanterne$updateZ(NoiseChunk noiseChunk, int posZ, double factorZ) {
        if (!((Ciel.Cell)noiseChunk).lanterne$skyCell()) {
            noiseChunk.updateForZ(posZ, factorZ);
        }
    }

    /**
     * L'air rendu sans passer par l'aquifère.
     *
     * <p>{@code getInterpolatedState} est {@code protected} : un {@code @Redirect} ne pourrait pas
     * la rappeler depuis notre paquet. {@code @WrapOperation} règle la question sans accesseur — il
     * rappelle l'appel d'origine sans avoir à le nommer.
     *
     * <p>Rendre {@link Ciel#AIR} plutôt que {@code null} n'est pas indifférent : {@code null} ferait
     * poser le bloc par défaut. C'est bien l'air, et c'est bien le <b>même objet</b> que celui que
     * la ligne suivante compare par référence.
     */
    @WrapOperation(
            method = "doFill",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;getInterpolatedState()"
                            + "Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState lanterne$state(NoiseChunk noiseChunk, Operation<BlockState> original) {
        if (((Ciel.Cell)noiseChunk).lanterne$skyCell()) {
            return Ciel.AIR;
        }
        return original.call(noiseChunk);
    }
}
