package fr.clubcitrouille.lanterne.client.upscale;

import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
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
 * Le premier vrai G-buffer de ce module : une normale par fragment, reconstruite depuis la
 * profondeur de la toile plutôt qu'écrite par le passage de rendu du terrain.
 *
 * <h2>Ce que cette classe ajoute, exactement, à ce que {@link Nuancier} offrait avant elle</h2>
 *
 * <p>L'audit qui précède ce chantier est sans ambiguïté : {@link Nuancier} enregistre des JSON
 * {@code PostChain} vanilla, qui n'accrochent que l'étage <b>final</b> de composition — après que
 * le monde est déjà aplati en couleur et en profondeur. Ni les normales, ni la position monde, ni
 * un albédo séparé n'existaient nulle part qu'un nuancier tiers puisse lire. Cette classe comble la
 * première de ces trois cases — la seule qui soit le « strict minimum pour un AO/reflets
 * écran-espace crédibles » — sans toucher au passage de rendu du terrain, dont la lecture (voir
 * {@code TerrainVertexFormat.putQuad}) a confirmé qu'il ne porte aujourd'hui aucune normale de
 * sommet à exploiter, et dont un autre chantier peut encore modifier le format.
 *
 * <h2>Pourquoi une passe à la main, comme {@link Accumulate}, et pas un JSON de plus</h2>
 *
 * <p>Reconstruire une normale par dérivées d'écran depuis la profondeur exige d'inverser la
 * matrice de projection <b>réelle</b> de l'image courante — voir {@link CameraUniforms}, dont
 * c'est l'unique consommateur à ce jour. Aucun JSON {@code PostChain} ne peut porter cette valeur :
 * {@code UniformValue} ne connaît que des constantes figées à la compilation du JSON. Cette classe
 * suit donc exactement le patron d'{@link Accumulate} — un {@code RenderPipeline} compilé une fois
 * à la main via {@code GpuDevice.compilePipeline}, hors du registre {@code RenderPipelines} fermé
 * de vanilla — plutôt que de réessayer, encore une fois, de faire entrer une valeur vivante dans un
 * système conçu pour des constantes.
 *
 * <h2>Ce que la cible produite porte, et pourquoi</h2>
 *
 * <p>{@code lanterne:normal} (voir {@link Resolve#NORMAL}) est une texture RGBA16_FLOAT : RGB
 * porte la normale vue en composantes signées, A porte la profondeur brute déjà lue ici même. Voir
 * le Javadoc de {@code gbuffer_normal.fsh} pour pourquoi le canal alpha existe — en bref, pour
 * qu'un nuancier tiers n'ait jamais à deviner si {@code lanterne:scene} a ou non une profondeur
 * exploitable cette image-là (ce n'est pas toujours le cas : voir {@link Scene#give}).
 *
 * <h2>Tout échec de compilation est définitif, et n'éteint que cette passe</h2>
 *
 * <p>Même contrat qu'{@link Accumulate} : un pilote qui refuse ce pipeline ne doit couper ni la
 * remontée d'échelle ni le reste du nuancier actif, seulement la disponibilité de
 * {@code lanterne:normal}. Un nuancier qui la référence verrait alors une texture absente du
 * panier — un cas que {@link Resolve.Bundle#get} traite déjà en rendant {@code null}, exactement
 * comme pour {@link Resolve#AA} quand l'anticrénelage est coupé.
 */
final class Gbuffer {
    private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
            .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("CameraMatrices", UniformType.UNIFORM_BUFFER)
            .build();

    private static final RenderPipeline PIPELINE = RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath(Lanterne.ID, "gbuffer_normal"))
            .withVertexShader(Identifier.fromNamespaceAndPath("minecraft", "core/screenquad"))
            .withFragmentShader(Identifier.fromNamespaceAndPath(Lanterne.ID, "post/gbuffer_normal"))
            .withBindGroupLayout(LAYOUT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(
                    new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .build();

    /** Lit {@code gbuffer_normal.fsh} tel quel depuis les ressources. Voir {@link Accumulate}, même patron. */
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
    private static GpuSampler depthSampler;

    private Gbuffer() {}

    /** Cette passe est-elle encore susceptible de fonctionner cette session ? */
    static boolean available() {
        return !broken;
    }

    /**
     * Reconstruit les normales de {@code scene} dans {@code normal}.
     *
     * @param scene  la toile de cette image — doit porter une profondeur ({@link RenderTarget#hasDepth()})
     * @param normal la cible {@code lanterne:normal}, de même taille que {@code scene}
     * @return vrai si {@code normal} contient bien le résultat de cette image
     */
    static boolean run(RenderTarget scene, RenderTarget normal) {
        if (!scene.hasDepth() || !ensureReady()) {
            return false;
        }
        try {
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();
            try (RenderPass pass = encoder.createRenderPass(() -> "Lanterne / normales de G-buffer",
                    normal.getColorTextureView(), Optional.empty())) {
                pass.setPipeline(compiled);
                pass.setUniform("DepthSampler", scene.getDepthTextureView(), depthSampler);
                pass.setUniform("CameraMatrices", CameraUniforms.upload(encoder));
                pass.draw(3, 1, 0, 0);
            }
            encoder.submit();
            return true;
        } catch (Throwable problem) {
            // Un incident d'une image n'éteint pas la passe : voir Upscale.note, dont c'est
            // exactement l'usage. Seul un échec de COMPILATION, dans ensureReady, est définitif.
            Upscale.note("la reconstruction des normales de G-buffer a échoué en cours d'image", problem);
            return false;
        }
    }

    /** Compile le pipeline et crée l'échantillonneur au tout premier appel, et une seule fois. */
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
            // NEAREST : la profondeur ne s'interpole pas sans fausser la reconstruction sur un
            // bord — un filtrage bilinéaire mélangerait le proche et le lointain d'un contour en
            // une valeur qui ne correspond à aucun des deux.
            depthSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
            compiled = device.compilePipeline(PIPELINE, SHADER_SOURCE, Runnable::run)
                    .get()
                    .finishCompile();
            Lanterne.LOG.info("[ÉCHELLE] Pipeline de reconstruction des normales compilé.");
            return true;
        } catch (Throwable problem) {
            broken = true;
            Lanterne.LOG.warn("[ÉCHELLE] Compilation des normales de G-buffer refusée : "
                    + "lanterne:normal restera absente cette session.", problem);
            return false;
        }
    }
}
