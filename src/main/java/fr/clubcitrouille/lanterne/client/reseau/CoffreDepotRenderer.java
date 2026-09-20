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

import fr.clubcitrouille.lanterne.content.reseau.CoffreDepotBlockEntity;
import fr.clubcitrouille.lanterne.content.reseau.Reseaux;

/**
 * Le rendu du Coffre de dépôt : la lueur du Réseau (voir {@link Lueur}), allumée tant que ce bloc
 * est un nœud vivant du Réseau.
 *
 * <h2>Un signal constant, et c'est assumé — comme le portail</h2>
 *
 * <p>{@link CoffreDepotBlockEntity} s'annonce inconditionnellement à {@code onLoad} dès que {@code
 * Config#RESEAU_ACTIF} le permet — voir sa Javadoc de classe : « ce bloc EST déjà un nœud ». Il n'y
 * a donc rien de plus fin à afficher que « le Réseau tourne, et ce coffre en fait partie » : c'est
 * exactement le même genre de vérité qu'un {@code minecraft:nether_portal} allumé donne en tournant
 * sans discontinuer — pas d'événement précis à guetter, juste un état qui dure tant qu'il dure.
 *
 * <h2>Ce qui a été écarté, et pourquoi</h2>
 *
 * <p>Un dépôt reçu (« un objet vient d'arriver ») aurait été un signal plus vivant, mais il exigerait
 * de synchroniser un instant précis au client — un nouveau paquet, envoyé potentiellement à chaque
 * dépose d'un bot minier qui peut vider des centaines de piles à la minute (voir {@code
 * bot.GrandTrieurSelfCheck} du dépôt AutoMiner pour l'échelle réelle visée). Un coffre de dépôt très
 * sollicité enverrait alors plus de paquets qu'un Guichet n'en reçoit de clics — le genre de coût
 * qu'un simple habillage visuel n'a pas le droit d'imposer.
 */
public class CoffreDepotRenderer
        implements BlockEntityRenderer<CoffreDepotBlockEntity, CoffreDepotRenderer.State> {

    public CoffreDepotRenderer(BlockEntityRendererProvider.Context context) {}

    public static class State extends BlockEntityRenderState {
        boolean actif;
        float animTime;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(CoffreDepotBlockEntity coffre, State state, float partialTick,
            Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(coffre, state, partialTick, cameraPosition,
                breakProgress);
        state.actif = Reseaux.actif();
        Level level = coffre.getLevel();
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
