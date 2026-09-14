package fr.clubcitrouille.lanterne.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import fr.clubcitrouille.lanterne.core.Stencil;

/**
 * Le pochoir posé sur la pose des gabarits de structure.
 *
 * <h2>Un seul point d'accroche, et il est le bon</h2>
 *
 * <p>{@code placeInWorld} lit la liste des blocs du gabarit en <b>un seul endroit</b> — la ligne 264
 * de {@code StructureTemplate} en 26.1.2 — et la remet aussitôt à {@code processBlockInfos}. Détourner
 * cette lecture suffit donc à tout : la préparation, les processeurs, la copie des balises NBT et la
 * boucle de pose travaillent tous sur ce que nous rendons.
 *
 * <p>Le détournement capture les arguments de la méthode englobante, ce qui évite d'avoir à
 * recalculer l'ancre et les réglages de pose. Voir {@link Stencil} pour ce que le tri garantit et
 * pour les trois cas où il renonce.
 *
 * <p>Repris de <b>StructureLayoutOptimizer</b> (TelepathicGrunt, MIT), qui vise précisément 26.1 —
 * avec une différence assumée : ce tri-ci ne porte que sur X et Z, parce que
 * {@code GravityProcessor} déplace les blocs en hauteur.
 */
@Mixin(StructureTemplate.class)
public abstract class TemplateStencilMixin {
    @Redirect(
            method = "placeInWorld(Lnet/minecraft/world/level/ServerLevelAccessor;"
                    + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;"
                    + "Lnet/minecraft/util/RandomSource;I)Z",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/"
                            + "StructureTemplate$Palette;blocks()Ljava/util/List;"))
    private List<StructureTemplate.StructureBlockInfo> lanterne$onlyWhatFalls(
            StructureTemplate.Palette palette,
            ServerLevelAccessor level, BlockPos position, BlockPos referencePos,
            StructurePlaceSettings settings, RandomSource random, int updateMode) {
        return Stencil.inWindow(palette, position, settings);
    }
}
