package fr.clubcitrouille.lanterne.client.portals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;

import org.joml.Matrix4f;
import org.joml.Vector3d;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.mixin.RenderTargetAccessor;

/**
 * Le prototype de rendu de portail — un seul rectangle statique, non récursif, qui montre une
 * vraie vue à travers plutôt qu'un mur.
 *
 * <h2>Ce que ce module fait, exactement</h2>
 *
 * <p>Deux temps, chaque image, uniquement si {@link PortailsConfig#TEST_PORTAL} est vrai :
 *
 * <ol>
 *   <li>{@link #destinationTarget} prépare une cible séparée ; {@code PortailRenderMixin} y fait
 *       rejouer <b>tout le rendu de niveau réel</b> — {@code LevelRenderer.render}, le même appel
 *       que {@code GameRenderer.renderLevel} fait pour l'image normale — mais avec la caméra
 *       partagée temporairement déplacée à {@code position du joueur + décalage configuré}, puis
 *       remise à sa vraie valeur. Voir la Javadoc de {@code PortailRenderMixin} pour le détail
 *       exact de ce qui est sauvegardé et restauré, et pourquoi c'est nécessaire.</li>
 *   <li>{@link #compositeQuad} peint un rectangle texturé de cette destination, à sa position
 *       fixe dans le monde, testé (mais pas écrit) contre la profondeur déjà présente dans la
 *       cible réelle — donc correctement caché par un mur qui passerait devant.</li>
 * </ol>
 *
 * <h2>Deux limites structurelles, pas des oublis</h2>
 *
 * <p><b>Aucune entité, bloc-entité ni particule n'apparaît dans la vue de destination.</b> Lu dans
 * {@code LevelRenderer.render} (source décompilée du vrai jar patché, pas supposé) :
 * {@code submitFeatures} vide {@code levelRenderState.entityRenderStates}/
 * {@code blockEntityRenderStates}/etc. immédiatement après les avoir soumis — une seule fois par
 * image, alimentés par l'extraction (({@code LevelExtractor}) qui a déjà eu lieu pour la caméra
 * RÉELLE avant que ce mixin n'agisse. Le second appel à {@code render} — forcément après le vrai,
 * jamais avant, voir {@code PortailRenderMixin} — trouve donc ces listes déjà vides. Rejouer
 * l'extraction pour une seconde caméra est un chantier séparé, hors budget de ce soir — voir
 * {@code notes/immersive-portals-faisabilite.md}.
 *
 * <p><b>Le ciel n'est pas rendu dans la vue de destination</b> — {@code shouldRenderSky} vaut
 * délibérément faux dans l'appel. {@code SkyRenderer} capture l'objet {@code RenderTarget} de la
 * cible principale dans un champ FINAL à sa construction (voir la Javadoc de classe de
 * {@code client.upscale.Scene}, qui documente exactement ce piège pour la toile réduite) : le
 * laisser se reconstruire pendant que {@code mainRenderTarget} pointe vers la texture de
 * destination le capturerait, et la prochaine image réelle dessinerait le ciel dans une texture
 * qui n'est plus la sienne. Éviter la question plutôt que la résoudre à l'aveugle.
 *
 * <h2>Pourquoi une caméra de destination qui ne fait que TRANSLATER</h2>
 *
 * <p>Même orientation, même projection, même {@code Frustum} (juste re-préparé à la nouvelle
 * position via {@code Frustum.prepare(x,y,z)} — une méthode publique dédiée à ce déplacement,
 * vérifiée par {@code javap}, PAS une reconstruction depuis deux matrices dont la convention
 * exacte n'a pas été vérifiée cette passe). Ce choix évite tout le champ de mines des données
 * dérivées d'une orientation différente ({@code Frustum} reconstruit depuis zéro, brouillard,
 * FOV) au prix d'un vrai portail : celui-ci ne fait encore que déplacer le point de vue, pas le
 * réorienter selon le plan du cadre de destination. Voir la note pour ce que ça impliquerait de
 * lever cette limite.
 */
public final class PortailPrototype {
    /** Résolution fixe de la texture de destination — pas liée à la fenêtre, pour un coût borné. */
    private static final int SIZE = 512;

    private static RenderTarget destination;

    private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
            .withUniform("CameraMatrices", UniformType.UNIFORM_BUFFER)
            .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();

    private static RenderPipeline pipeline;
    private static boolean triedInit;
    private static boolean broken;
    private static CompiledRenderPipeline compiled;
    private static GpuSampler sampler;

    /**
     * Coupé pour le reste de la session par {@code PortailRenderMixin} au premier incident du
     * second rendu de niveau — voir sa Javadoc de classe pour pourquoi ce mécanisme est plus
     * prudent que le reste du mod (qui, lui, retente chaque image après un incident isolé).
     */
    private static boolean sessionDisabled;

    /** Vrai si un incident a déjà coupé le prototype pour cette session. */
    public static boolean sessionDisabled() {
        return sessionDisabled;
    }

    /** Coupe le prototype pour le reste de la session. Appelé une seule fois, au premier incident. */
    public static void disableSession() {
        sessionDisabled = true;
        release();
    }

    /** Lit {@code portail_quad.vsh}/{@code .fsh} depuis les ressources. Même patron que Gbuffer. */
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
            return ShaderSource.CachedIncludeSource.createError("Aucune inclusion attendue ici : " + id);
        }

        @Override
        public void close() {}
    };

    private PortailPrototype() {}

    /**
     * La cible de destination, créée au premier appel. Ne rend jamais {@code null} une fois créée
     * avec succès — seule une allocation refusée éteint durablement le prototype.
     */
    public static RenderTarget destinationTarget(RenderTarget real) {
        if (broken) {
            return null;
        }
        if (destination != null) {
            return destination;
        }
        try {
            GpuFormat depthFormat = ((RenderTargetAccessor) real).lanterne$depthFormat();
            destination = new TextureTarget("Lanterne / portail (destination)", SIZE, SIZE,
                    GpuFormat.RGBA8_UNORM, depthFormat);
            Lanterne.LOG.info("[PORTAIL] Cible de destination {}x{} allouée.", SIZE, SIZE);
            return destination;
        } catch (Throwable problem) {
            broken = true;
            Lanterne.LOG.warn("[PORTAIL] Impossible d'allouer la cible de destination — "
                    + "le prototype reste éteint pour le reste de la session.", problem);
            return null;
        }
    }

    /** Libère la cible de destination. Appelé quand le réglage repasse à faux. */
    public static void release() {
        if (destination != null) {
            destination.destroyBuffers();
            destination = null;
        }
    }

    /**
     * Peint le rectangle du portail dans {@code real}, testé contre sa profondeur déjà présente,
     * texturé depuis {@link #destination}.
     *
     * @param camera la caméra RÉELLE (déjà restaurée par l'appelant) — {@code ViewRotation}/
     *               {@code Projection} en sont tirées pour que le rectangle se projette
     *               correctement à l'écran, exactement comme n'importe quel autre objet du monde.
     */
    public static void compositeQuad(RenderTarget real, CameraRenderState camera) {
        if (!ensureReady() || camera == null || camera.pos == null) {
            return;
        }
        try {
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();
            try (RenderPass pass = encoder.createRenderPass(() -> "Lanterne / portail (composite)",
                    real.getColorTextureView(), Optional.empty(),
                    real.getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(compiled);
                pass.setUniform("CameraMatrices", uploadCameraMatrices(encoder, camera));
                pass.setUniform("Sampler0", destination.getColorTextureView(), sampler);
                pass.setVertexBuffer(0, uploadQuad(encoder, camera.pos));
                pass.draw(4, 1, 0, 0);
            }
            encoder.submit();
        } catch (Throwable problem) {
            broken = true;
            Lanterne.LOG.warn("[PORTAIL] Composition du rectangle refusée — "
                    + "le prototype reste éteint pour le reste de la session.", problem);
        }
    }

    /** Même disposition std140 que {@code client.upscale.CameraUniforms.upload} — dupliquée ici
     * parce que ce champ est package-private là-bas, dans un fichier qu'un autre chantier modifie
     * ce soir. Voir la Javadoc de classe. */
    private static GpuBufferSlice uploadCameraMatrices(CommandEncoder encoder, CameraRenderState camera) {
        Matrix4f viewRotation = new Matrix4f(camera.viewRotationMatrix);
        Matrix4f projection = new Matrix4f(camera.projectionMatrix);
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        ByteBuffer bytes = ByteBuffer.allocateDirect(192).order(ByteOrder.nativeOrder());
        viewRotation.get(0, bytes);
        projection.get(64, bytes);
        inverseProjection.get(128, bytes);
        return encoder.transientMemory().uploadGpu(bytes, 256L, GpuBuffer.USAGE_UNIFORM);
    }

    /**
     * Les quatre coins du rectangle configuré, en positions RELATIVES À LA CAMÉRA RÉELLE (ce
     * moteur rend à origine flottante — voir la Javadoc de {@code portail_quad.vsh}), rechargés à
     * chaque image parce que la position de la caméra change à chaque image même si le rectangle,
     * lui, est fixe dans le monde.
     *
     * <h2>Vérification de ce soir : position ANCRÉE SUR LE JOUEUR, pas fixe dans le monde</h2>
     *
     * <p>{@code TEST_X}/{@code TEST_Y}/{@code TEST_Z} sont ici ajoutés à la position COURANTE de la
     * caméra réelle plutôt qu'utilisés comme coordonnées absolues — un monde fraîchement engendré
     * (chaque {@code -Pbanc=} obtient son propre dossier, donc son propre monde, voir
     * {@code build.gradle}) n'a pas de coordonnées de spawn connues à l'avance. Ancrer le rectangle
     * sur le joueur garantit qu'il reste en vue quel que soit le point d'apparition, pour cette
     * vérification précise. Le mécanisme lui-même ne change pas : une position absolue
     * fonctionnerait identiquement (voir {@code notes/immersive-portals-faisabilite.md} pour ce que
     * ça voudrait dire de la rattacher à un vrai bloc placé, plutôt qu'à un réglage).
     */
    private static GpuBufferSlice uploadQuad(CommandEncoder encoder, net.minecraft.world.phys.Vec3 cameraPos) {
        double cx = cameraPos.x + PortailsConfig.TEST_X.get();
        double cy = cameraPos.y + PortailsConfig.TEST_Y.get();
        double cz = cameraPos.z + PortailsConfig.TEST_Z.get();
        double halfW = PortailsConfig.TEST_WIDTH.get() / 2.0;
        double halfH = PortailsConfig.TEST_HEIGHT.get() / 2.0;

        // Face SUD (normale +Z) : le rectangle varie en X et en Y, Z fixe. Une seule orientation
        // pour ce prototype — voir la Javadoc de classe.
        Vector3d[] corners = {
                new Vector3d(cx - halfW, cy - halfH, cz),
                new Vector3d(cx + halfW, cy - halfH, cz),
                new Vector3d(cx + halfW, cy + halfH, cz),
                new Vector3d(cx - halfW, cy + halfH, cz),
        };
        float[][] uv = {{0f, 1f}, {1f, 1f}, {1f, 0f}, {0f, 0f}};

        // POSITION (3 floats) + UV0 (2 floats) = 20 octets par sommet, ordre DefaultVertexFormat
        // .POSITION_TEX — vérifié par javap sur le vrai jar patché (position puis uv, aucun
        // rembourrage : deux éléments flottants consécutifs).
        ByteBuffer bytes = ByteBuffer.allocateDirect(4 * 20).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4; i++) {
            bytes.putFloat((float) (corners[i].x - cameraPos.x));
            bytes.putFloat((float) (corners[i].y - cameraPos.y));
            bytes.putFloat((float) (corners[i].z - cameraPos.z));
            bytes.putFloat(uv[i][0]);
            bytes.putFloat(uv[i][1]);
        }
        bytes.flip();
        return encoder.transientMemory().uploadGpu(bytes, 80L, GpuBuffer.USAGE_VERTEX);
    }

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
            sampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, 1, java.util.OptionalDouble.empty());
            pipeline = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath(Lanterne.ID, "portail_quad"))
                    .withVertexShader(Identifier.fromNamespaceAndPath(Lanterne.ID, "core/portail_quad"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath(Lanterne.ID, "core/portail_quad"))
                    .withBindGroupLayout(LAYOUT)
                    .withVertexBinding(0, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLE_FAN)
                    .withCull(false)
                    .withDepthStencilState(Optional.of(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false)))
                    .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                    .build();
            compiled = device.compilePipeline(pipeline, SHADER_SOURCE, Runnable::run).get().finishCompile();
            Lanterne.LOG.info("[PORTAIL] Pipeline du rectangle de portail compilé.");
            return true;
        } catch (Throwable problem) {
            broken = true;
            Lanterne.LOG.warn("[PORTAIL] Compilation du pipeline de portail refusée : "
                    + "le prototype reste éteint pour cette session.", problem);
            return false;
        }
    }
}
