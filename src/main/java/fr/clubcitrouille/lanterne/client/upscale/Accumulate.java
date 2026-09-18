package fr.clubcitrouille.lanterne.client.upscale;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;

import org.joml.Matrix4fc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * L'accumulation temporelle : mélange l'image courante avec l'historique reprojeté, avant EASU.
 *
 * <h2>Pourquoi cette passe ne passe pas par {@code PostChain}</h2>
 *
 * <p>{@link Resolve} s'appuie sur {@code PostChain} parce que ses uniformes sont figés une bonne
 * fois pour toutes à la construction, depuis le JSON — exactement ce qu'il faut pour EASU et RCAS,
 * dont les seuls réglages (netteté, tailles source/destination) ne changent qu'à la marge, jamais
 * <b>à chaque image</b>. La matrice de reprojection, elle, change à chaque image par construction :
 * c'est une matrice différente entre deux positions de caméra différentes. Aucune passe
 * {@code PostChain} de 26.2 ne sait recevoir un uniforme différent d'une image à l'autre — le seul
 * mécanisme comparable, {@code SamplerInfo}, est un cas spécial câblé en dur dans {@code PostPass}
 * pour un seul usage, pas un point d'extension générique. Voir {@code notes/fsr2-plan.md}, section
 * « piste 1 », pour la lecture de bytecode qui ferme cette porte.
 *
 * <p>D'où cette passe bâtie à la main : un pipeline plein écran, deux échantillonneurs, un tampon
 * d'uniformes transitoire réécrit à chaque image via {@code TransientMemory.uploadGpu}. Trois fois
 * plus de code que confier la tâche à {@code PostChain} — et la seule façon d'obtenir un uniforme
 * vraiment dynamique.
 *
 * <h2>Pourquoi le pipeline n'est pas dans {@code RenderPipelines}</h2>
 *
 * <p>Le registre vanilla précompile tous ses pipelines pendant le rechargement des ressources, et
 * {@code RenderSystem.getCompiledPipeline} lève si on lui présente un pipeline hors de cette liste
 * fermée — lu en bytecode, pas supposé. Aucun point d'extension n'y ajoute une entrée de mod. Cette
 * classe compile donc son unique pipeline elle-même, une fois, au premier appel, directement via
 * {@code GpuDevice.compilePipeline} — et fournit sa propre petite implémentation de
 * {@code ShaderSource} pour les deux nuanceurs qu'il lui faut, lus tels quels depuis les ressources.
 *
 * <h2>Tout échec de compilation est définitif, et n'éteint que cette passe</h2>
 *
 * <p>Contrairement à {@link Upscale#fail}, qui coupe toute la mise à l'échelle, un pipeline qui
 * refuse de compiler ici ne doit couper <b>que l'accumulation</b> : EASU et RCAS n'ont besoin de
 * rien de ce que cette classe construit. {@link Scene#give} retombe alors sur
 * {@code Resolve.run(scene, screen)}, exactement le chemin qu'empruntait le module avant que cette
 * classe existe.
 */
final class Accumulate {
    private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
            .withUniform("SceneSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("HistorySampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("Reprojection", UniformType.UNIFORM_BUFFER)
            .build();

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(Lanterne.ID, "accumulation_temporelle"))
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(Lanterne.ID, "post/temporal_accumulate"))
            .withBindGroupLayout(LAYOUT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(
                    new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .build();

    /**
     * Lit les deux nuanceurs de cette passe tels quels depuis les ressources du jeu.
     *
     * <p>Ni l'un ni l'autre n'emploie {@code #moj_import} : {@link #getInclude} ne sert donc à rien
     * ici, et le dit plutôt que de faire semblant de le supporter.
     */
    private static final ShaderSource SHADER_SOURCE = new ShaderSource() {
        @Override
        public String getShader(Identifier id, ShaderType type) {
            Identifier file = type.idConverter().idToFile(id);
            try {
                return Minecraft.getInstance().getResourceManager().getResourceOrThrow(file).readAllAsString();
            } catch (Exception problem) {
                throw new RuntimeException("Nuanceur introuvable : " + file, problem);
            }
        }

        @Override
        public ShaderSource.CachedIncludeSource getInclude(Identifier id) {
            return ShaderSource.CachedIncludeSource.createError("Aucun #moj_import attendu ici : " + id);
        }

        @Override
        public void close() {}
    };

    /** Vrai une fois que la compilation a été tentée, réussie ou non. Pour ne l'essayer qu'une fois. */
    private static boolean triedInit;

    /** Vrai si la compilation a échoué. Définitif : voir la classe. */
    private static boolean broken;

    private static CompiledRenderPipeline compiled;
    private static GpuSampler sceneSampler;
    private static GpuSampler historySampler;

    private Accumulate() {}

    /** Cette passe est-elle encore susceptible de fonctionner cette session ? */
    static boolean available() {
        return !broken;
    }

    /**
     * Mélange {@code scene} et {@code history} dans {@code accum}, via la matrice de reprojection.
     *
     * @return vrai si {@code accum} contient bien le résultat du mélange
     */
    static boolean run(RenderTarget scene, RenderTarget history, RenderTarget accum, Matrix4fc reprojection) {
        if (!ensureReady()) {
            return false;
        }
        try {
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();
            try (RenderPass pass = encoder.createRenderPass(() -> "Lanterne / accumulation temporelle",
                    accum.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(compiled);
                pass.setUniform("SceneSampler", scene.getColorTextureView(), sceneSampler);
                pass.setUniform("HistorySampler", history.getColorTextureView(), historySampler);
                pass.setUniform("Reprojection", uploadReprojection(encoder, reprojection));
                pass.draw(3, 1, 0, 0);
            }
            encoder.submit();
            return true;
        } catch (Throwable problem) {
            // Un incident d'une image n'éteint pas la passe : voir Upscale.note, dont c'est
            // exactement l'usage. Seul un échec de COMPILATION, dans ensureReady, est définitif.
            Upscale.note("l'accumulation temporelle a échoué en cours d'image", problem);
            return false;
        }
    }

    private static GpuBufferSlice uploadReprojection(CommandEncoder encoder, Matrix4fc reprojection) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        reprojection.get(bytes);
        // 256 : alignement d'uniforme le plus contraignant qu'un pilote GPU courant réclame. Le
        // tampon transitoire s'en sert pour placer cette tranche, pas pour en changer le contenu.
        return encoder.transientMemory().uploadGpu(bytes, 256L, GpuBuffer.USAGE_UNIFORM);
    }

    /** Compile le pipeline et crée les échantillonneurs au tout premier appel, et une seule fois. */
    private static boolean ensureReady() {
        if (compiled != null) {
            return true;
        }
        if (triedInit) {
            return false;
        }
        triedInit = true;
        try {
            GpuDevice device = RenderSystem.getDevice();
            sceneSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
            historySampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());
            compiled = device.compilePipeline(PIPELINE, SHADER_SOURCE, Runnable::run)
                    .get()
                    .finishCompile();
            Lanterne.LOG.info("[ÉCHELLE] Pipeline d'accumulation temporelle compilé.");
            return true;
        } catch (Throwable problem) {
            broken = true;
            Lanterne.LOG.warn("[ÉCHELLE] Compilation de l'accumulation temporelle refusée : la "
                    + "remontée continue sans elle (EASU/RCAS seuls).", problem);
            return false;
        }
    }
}
