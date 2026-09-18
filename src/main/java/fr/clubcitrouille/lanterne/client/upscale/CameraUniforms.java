package fr.clubcitrouille.lanterne.client.upscale;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.joml.Matrix4f;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * Le mécanisme d'uniforme caméra <b>vivant</b> — la brique que {@link Gbuffer} consomme pour
 * reconstruire des normales, et que tout futur passage à la main de ce module peut réutiliser.
 *
 * <h2>Le problème que cette classe résout</h2>
 *
 * <p>{@code PostPass} (vanilla, piloté par le JSON d'un {@code PostChain}) fige ses uniformes une
 * bonne fois pour toutes à la construction — {@code UniformValue} ne sait lire qu'une constante du
 * JSON, jamais une valeur qui change d'une image à l'autre. Voir le Javadoc de classe d'
 * {@link Accumulate}, qui a rencontré exactement cette limite pour sa matrice de reprojection et
 * l'a contournée de la même façon : un pipeline bâti à la main, un tampon d'uniformes transitoire
 * réécrit chaque image via {@code TransientMemory.uploadGpu}. Cette classe fait la même chose pour
 * les matrices de caméra — vue et projection — plutôt que de dupliquer ce calcul dans chaque
 * passage à la main qui en aurait besoin.
 *
 * <h2>D'où viennent les matrices, et pourquoi aucun mixin n'est nécessaire pour les lire</h2>
 *
 * <p>{@code GameRenderer.gameRenderState()} est <b>public</b>, tout comme
 * {@code GameRenderState.levelRenderState}, {@code LevelRenderState.cameraRenderState} et les
 * champs {@code projectionMatrix}/{@code viewRotationMatrix} de {@code CameraRenderState} — vérifié
 * par {@code javap} sur le vrai jar patché, pas supposé. {@link UpscaleGameRendererMixin} lit déjà
 * ces mêmes champs pour {@link Reproject#advance}, depuis l'intérieur de {@code renderLevel} ; cette
 * classe lit les <b>mêmes</b> champs, mais depuis l'extérieur, à un moment différent (voir
 * ci-dessous) — {@code Minecraft.getInstance().gameRenderer} est lui aussi un champ public, donc
 * aucune accroche de mixin supplémentaire n'est nécessaire pour y arriver.
 *
 * <h2>Quel instant de l'image cette classe lit, et pourquoi ça suffit</h2>
 *
 * <p>{@link Gbuffer} — le seul consommateur actuel — tourne après {@code renderLevel} et avant la
 * remontée d'échelle, c'est-à-dire une fois le monde déjà dessiné. La matrice lue ici n'est donc
 * <b>pas</b> celle que {@link Jitter} a décalée pour l'accumulation temporelle (ce décalage reste
 * local à la variable de {@code renderLevel} que le mixin modifie, jamais réécrit dans
 * {@code cameraRenderState}) : c'est la matrice de projection <b>non décalée</b>, la même que celle
 * que {@link Reproject} reçoit. L'écart entre les deux est une fraction de pixel — voir
 * {@link Jitter} — largement sous le seuil auquel une reconstruction de position depuis la
 * profondeur, destinée à une normale par fragment, peut se tromper de voisin.
 *
 * <h2>Pourquoi une texture pour les nuanciers tiers, et un tampon d'uniformes ici</h2>
 *
 * <p>Cette classe elle-même ne sert que du code Java du mod : {@link Gbuffer} lie
 * {@link #upload(CommandEncoder)} comme un {@code UNIFORM_BUFFER} nommé {@code CameraMatrices},
 * exactement comme {@link Accumulate} lie {@code Reprojection}. Un nuancier tiers, lui, ne compile
 * jamais son propre {@code RenderPipeline} — son GLSL passe par {@code PostChain}, qui n'offre
 * aucun point d'ancrage pour un {@code BindGroupLayout} choisi par le mod. La seule façon vérifiée
 * de lui faire voir une valeur qui change chaque image est de l'écrire dans une <b>texture</b>
 * qu'il échantillonne comme n'importe quelle autre cible du {@code Resolve.ALLOWED} — c'est le
 * chemin que {@link Gbuffer} emprunte pour les normales (le canal alpha porte la profondeur), et
 * c'est le chemin qu'une future passe pourrait réutiliser pour publier les matrices elles-mêmes
 * sous une cible dédiée si un nuancier tiers en a un jour explicitement besoin — voir le Javadoc de
 * {@link Nuancier} pour ce qui est livré aujourd'hui contre ce qui resterait à faire.
 */
final class CameraUniforms {
    private CameraUniforms() {}

    /**
     * Empaquette la vue et la projection courantes — et l'inverse de la seconde, déjà calculée
     * pour épargner ce travail à chaque consommateur — dans un tampon d'uniformes transitoire.
     *
     * <p>Disposition {@code std140} : trois {@code mat4} consécutifs, chacun aligné sur 16 octets
     * par colonne donc déjà aligné pour se suivre sans bourrage — {@code ViewRotation} à l'octet 0,
     * {@code Projection} à 64, {@code InverseProjection} à 128, 192 octets au total. Toute passe qui
     * lie ce tampon doit déclarer exactement ce bloc, dans cet ordre, sous le nom
     * {@code CameraMatrices} — voir {@code gbuffer_normal.fsh} pour l'unique exemple à ce jour.
     *
     * <p>256, comme dans {@link Accumulate#uploadReprojection} : l'alignement d'uniforme le plus
     * contraignant qu'un pilote GPU courant réclame, pas une propriété du contenu.
     *
     * @return la tranche à lier au nom {@code CameraMatrices} de la passe appelante
     */
    static GpuBufferSlice upload(CommandEncoder encoder) {
        CameraRenderState camera = Minecraft.getInstance().gameRenderer.gameRenderState()
                .levelRenderState.cameraRenderState;

        Matrix4f viewRotation = new Matrix4f(camera.viewRotationMatrix);
        Matrix4f projection = new Matrix4f(camera.projectionMatrix);
        // L'inverse d'une matrice de projection en perspective existe toujours (déterminant non
        // nul) : aucun repli nécessaire ici, à la différence d'un inverse générique.
        Matrix4f inverseProjection = new Matrix4f(projection).invert();

        ByteBuffer bytes = ByteBuffer.allocateDirect(192).order(ByteOrder.nativeOrder());
        // get(index, buffer) écrit à un index absolu SANS avancer la position du tampon — à la
        // différence de get(buffer), qui écrirait les trois matrices au même endroit si on
        // l'appelait trois fois de suite sans le faire avancer soi-même. Vérifié sur le JOML
        // réellement embarqué (1.10.9) : la surcharge existe.
        viewRotation.get(0, bytes);
        projection.get(64, bytes);
        inverseProjection.get(128, bytes);

        return encoder.transientMemory().uploadGpu(bytes, 256L, GpuBuffer.USAGE_UNIFORM);
    }
}
