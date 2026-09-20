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

import fr.clubcitrouille.lanterne.content.reseau.CoffreReceptionBlockEntity;
import fr.clubcitrouille.lanterne.content.reseau.Reseaux;

/**
 * Le rendu du Coffre de réception : la lueur du Réseau (voir {@link Lueur}), allumée tant que ce
 * bloc est un nœud vivant du Réseau — même condition et même raison que {@code CoffreDepotRenderer}.
 *
 * <h2>Le réapprovisionnement lui-même reste invisible, et c'est un choix, pas un oubli</h2>
 *
 * <p>{@link CoffreReceptionBlockEntity#reapprovisionner} serait le geste le plus « vivant » à
 * montrer — un sursaut au moment précis où le coffre se sert dans le Réseau. Mais ni {@code filtre}
 * ni l'instant d'un réapprovisionnement ne sont aujourd'hui synchronisés au client — voir {@code
 * saveAdditional}/{@code loadAdditional} de cette classe, qui ne parlent qu'au disque. Faire
 * autrement demanderait un nouveau champ transitoire et sa propre paire {@code
 * getUpdateTag}/{@code getUpdatePacket} (le patron déjà posé par {@code content.screen.PanelEntity}),
 * un ajout réel mais qui ne peut pas se vérifier depuis cet environnement — aucun client complet n'y
 * tourne. Plutôt qu'écrire un paquet neuf sans pouvoir le voir arriver en jeu, cette version s'en
 * tient au signal déjà honnête et déjà vérifiable : le bloc est-il, MAINTENANT, un nœud du Réseau.
 */
public class CoffreReceptionRenderer
        implements BlockEntityRenderer<CoffreReceptionBlockEntity, CoffreReceptionRenderer.State> {

    public CoffreReceptionRenderer(BlockEntityRendererProvider.Context context) {}

    public static class State extends BlockEntityRenderState {
        boolean actif;
        float animTime;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(CoffreReceptionBlockEntity coffre, State state, float partialTick,
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
