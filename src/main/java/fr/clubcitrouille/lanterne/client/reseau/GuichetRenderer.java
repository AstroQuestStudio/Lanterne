package fr.clubcitrouille.lanterne.client.reseau;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import fr.clubcitrouille.lanterne.content.reseau.GuichetBlockEntity;
import fr.clubcitrouille.lanterne.content.reseau.Reseaux;

/**
 * Le rendu du Guichet : la lueur du Réseau (voir {@link Lueur}), allumée seulement quand ce bloc
 * peut réellement livrer quelque chose.
 *
 * <h2>Le même test que le serveur, pas un autre</h2>
 *
 * <p>{@link GuichetBlockEntity#coffreAdjacent} sert déjà de garde côté serveur — voir {@code
 * Reseaux#onDemande} : « Aucun coffre de collecte à côté du Guichet » est exactement le message
 * qu'un joueur reçoit quand cette méthode rend {@code null}. Réutiliser la même méthode ici, plutôt
 * qu'inventer un second calcul, garantit que la lueur ne promet jamais un retrait que le clic
 * refuserait.
 *
 * <p>{@code coffreAdjacent} ne lit que {@code this.level} et les positions voisines — jamais un
 * champ propre au serveur — donc l'appeler depuis le niveau CLIENT, dans un renderer, rend
 * exactement ce qu'il rendrait côté serveur au même instant.
 */
public class GuichetRenderer implements BlockEntityRenderer<GuichetBlockEntity, GuichetRenderer.State> {

    public GuichetRenderer(BlockEntityRendererProvider.Context context) {}

    public static class State extends BlockEntityRenderState {
        boolean actif;
        float animTime;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(GuichetBlockEntity guichet, State state, float partialTick,
            Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(guichet, state, partialTick, cameraPosition,
                breakProgress);
        state.actif = Reseaux.actif() && guichet.coffreAdjacent() != null;
        Level level = guichet.getLevel();
        state.animTime = level == null ? 0f : level.getGameTime() + partialTick;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector,
            CameraRenderState camera) {
        if (state.actif) {
            Lueur.pulse(poseStack, collector, state.blockPos, state.animTime);
        }
    }
}
