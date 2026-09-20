package fr.clubcitrouille.lanterne.client.trieur;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import fr.clubcitrouille.lanterne.client.reseau.Lueur;
import fr.clubcitrouille.lanterne.content.trieur.TrieurBlockEntity;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le rendu du Trieur : la lueur du Réseau (voir {@link Lueur}), allumée seulement quand ce bloc
 * filtre réellement quelque chose.
 *
 * <h2>Le signal retenu, et celui qui a été écarté</h2>
 *
 * <p>Deux informations pourraient justifier la lueur : {@code HopperBlock.ENABLED} — vérifié au
 * {@code javap}, un vrai champ de blocstate vanilla, synchronisé au client comme n'importe quel
 * changement d'état — et le contenu du filtre (gabarits posés, liste blanche ou noire). Seule la
 * première est retenue.
 *
 * <p>La raison tient à ce que {@link TrieurBlockEntity} synchronise réellement : ni {@code
 * saveAdditional} ni aucune surcharge de {@code getUpdateTag} n'envoient le filtre au client — exactement
 * comme un {@code HopperBlockEntity} vanilla ne synchronise jamais son inventaire. Un client ne peut
 * donc pas honnêtement savoir si le filtre d'un Trieur qu'il n'a pas ouvert est vide ou rempli, et
 * inventer un signal sur une donnée qu'on n'a pas serait le genre de faux que ce dépôt refuse. Le
 * blocstate, lui, est une vérité que CHAQUE client connaît déjà, sans le moindre paquet de plus.
 *
 * <p>{@link Settings#trieur()} coupe le filtrage lui-même sans toucher au bloc — voir la Javadoc de
 * {@link TrieurBlockEntity#canPlaceItem}. Un Trieur qui se comporte comme un entonnoir ordinaire
 * n'a rien de particulier à signaler, d'où la même condition ici.
 */
public class TrieurRenderer implements BlockEntityRenderer<TrieurBlockEntity, TrieurRenderer.State> {

    public TrieurRenderer(BlockEntityRendererProvider.Context context) {}

    public static class State extends BlockEntityRenderState {
        boolean actif;
        float animTime;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(TrieurBlockEntity trieur, State state, float partialTick,
            Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(trieur, state, partialTick, cameraPosition,
                breakProgress);
        state.actif = Settings.trieur() && trieur.getBlockState().getValue(HopperBlock.ENABLED);
        Level level = trieur.getLevel();
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
