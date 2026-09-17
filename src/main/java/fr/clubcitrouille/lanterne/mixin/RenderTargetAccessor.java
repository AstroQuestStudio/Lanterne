package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;

/**
 * Le format de profondeur d'une cible de rendu, que le jeu garde protégé.
 *
 * <h2>Pourquoi il faut le lire</h2>
 *
 * <p>{@link fr.clubcitrouille.lanterne.client.upscale.Scene} recopie délibérément le format de
 * profondeur de la cible principale sur sa propre toile réduite — « même profondeur, même pochoir »,
 * voir sa javadoc de classe pour pourquoi ce n'est pas un détail : NeoForge active le pochoir à la
 * demande d'un mod tiers, et une toile sans pochoir prise pour une toile avec ferait échouer le
 * premier rendu qui s'en sert. Depuis la 26.3, ce format n'est plus un simple booléen
 * {@code useStencil} mais un {@link GpuFormat} complet ({@code D32_FLOAT}, {@code D32_FLOAT_S8_UINT}...)
 * porté par un champ {@code protected} de {@code RenderTarget} — invisible hors du paquet vanilla.
 */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {
    @Accessor("depthFormat")
    GpuFormat lanterne$depthFormat();
}
