package fr.clubcitrouille.lanterne.client.reseau;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import fr.clubcitrouille.lanterne.content.reseau.AiguillageBlockEntity;
import fr.clubcitrouille.lanterne.content.reseau.Reseaux;

/**
 * Le rendu de l'Aiguillage : la lueur du Réseau (voir {@link Lueur}), allumée seulement quand ce
 * bloc a réellement un voisin à annoncer.
 *
 * <h2>Un balayage refait à chaque image, plutôt que {@code annonces}</h2>
 *
 * <p>{@link AiguillageBlockEntity} tient bien une liste de ce qu'il a annoncé — {@code annonces} —
 * mais sa propre Javadoc dit pourquoi elle ne convient pas ici : capturée UNE FOIS à {@code onLoad}
 * et jamais recalculée, y compris si un coffre voisin est cassé ensuite. Un client qui s'y fierait
 * afficherait une lueur périmée après un remaniement du voisinage — le genre de mensonge que ce
 * dépôt refuse.
 *
 * <p>Le vrai balayage des six faces coûte moins cher à refaire qu'à faire suivre par un paquet : six
 * lectures de {@code BlockEntity} déjà en mémoire (le client connaît TOUJOURS le type et la position
 * des bloc-entités d'un chunk chargé, même sans leur contenu — c'est ce que {@link
 * fr.clubcitrouille.lanterne.content.reseau.GuichetBlockEntity#coffreAdjacent} utilise déjà, tel
 * quel, côté serveur), et il est à jour à l'image près.
 */
public class AiguillageRenderer
        implements BlockEntityRenderer<AiguillageBlockEntity, AiguillageRenderer.State> {

    public AiguillageRenderer(BlockEntityRendererProvider.Context context) {}

    public static class State extends BlockEntityRenderState {
        boolean actif;
        float animTime;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(AiguillageBlockEntity aiguillage, State state, float partialTick,
            Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(aiguillage, state, partialTick, cameraPosition,
                breakProgress);
        Level level = aiguillage.getLevel();
        state.actif = level != null && Reseaux.actif()
                && aUnVoisinRelie(level, aiguillage.getBlockPos());
        state.animTime = level == null ? 0f : level.getGameTime() + partialTick;
    }

    /** Voir la Javadoc de classe : le même test que {@code AiguillageBlockEntity#onLoad}, à jour. */
    private static boolean aUnVoisinRelie(Level level, BlockPos pos) {
        for (Direction face : Direction.values()) {
            if (level.getBlockEntity(pos.relative(face)) instanceof Container) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState camera) {
        if (state.actif) {
            Lueur.pulse(poseStack, collector, state.blockPos, state.animTime);
        }
    }
}
