package fr.clubcitrouille.lanterne.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.client.portals.PortailPrototype;
import fr.clubcitrouille.lanterne.client.portals.PortailsConfig;

/**
 * Le second rendu de niveau du prototype de portail — un appel de plus à
 * {@code LevelRenderer.render}, avec une caméra partagée déplacée puis restaurée.
 *
 * <h2>Pourquoi {@code renderLevel}, à sa QUEUE, et pas {@code render} comme UpscaleGameRendererMixin</h2>
 *
 * <p>{@code UpscaleGameRendererMixin} (autre chantier, en cours de modification ce soir — voir
 * {@code client/upscale/*}) accroche déjà {@code GameRenderer.render} à deux points précis, autour
 * de son propre appel à {@code renderLevel()}, pour échanger {@code mainRenderTarget} le temps du
 * monde. Accrocher ce mixin-ci au MÊME point exposerait les deux mixins à un ordre d'exécution non
 * garanti sur la même variable partagée — le genre de collision que ce dépôt demande d'éviter entre
 * forks actifs le même soir (voir les instructions de cette mission).
 *
 * <p>{@code renderLevel()} est une méthode À PART de {@code GameRenderer} (vérifié par
 * {@code javap} et par lecture de la source décompilée) : s'y accrocher à sa QUEUE — après que le
 * VRAI rendu (celui de l'image que le joueur voit) est entièrement terminé, entités et tout —
 * élimine toute compétition avec les points d'accroche de {@code UpscaleGameRendererMixin}, qui
 * sont tous dans {@code render()}, une méthode différente.
 *
 * <h2>Pourquoi le VRAI rendu doit passer EN PREMIER, jamais en second</h2>
 *
 * <p>Lu dans {@code LevelRenderer.render} (source décompilée du vrai jar patché) :
 * {@code submitFeatures} VIDE {@code levelRenderState.entityRenderStates} et consorts
 * immédiatement après les avoir soumis. Ces listes ne sont remplies qu'UNE fois par image, par
 * l'extraction qui a déjà eu lieu pour la caméra RÉELLE avant ce point. Si l'appel de ce mixin
 * passait AVANT le vrai rendu, c'est le vrai rendu — celui que le joueur voit — qui se retrouverait
 * sans la moindre entité, à CHAQUE image. En le plaçant après, c'est uniquement la vue de
 * destination du portail qui hérite de cette limite — voir la Javadoc de {@link PortailPrototype}
 * pour le détail complet. Le jeu réel n'est jamais concerné.
 *
 * <h2>Ce qui est sauvegardé, muté, puis restauré — et pourquoi c'est nécessaire</h2>
 *
 * <p>{@code LevelRenderer.render} lit sa matrice de terrain (celle qui décide QUELLES sections sont
 * candidates au dessin) sur {@code this.levelRenderState.cameraRenderState} — un champ propre à
 * {@code LevelRenderer}, PAS sur le paramètre {@code cameraState} qu'on lui passe, alors même que
 * dans l'usage normal les deux sont le MÊME objet (voir {@code GameRenderer.renderLevel}, qui passe
 * exactement {@code this.gameRenderState.levelRenderState.cameraRenderState} comme paramètre). La
 * seule façon de rendre les deux lectures cohérentes pour un second appel est donc de MUTER cet
 * objet partagé en place, pas de lui en substituer un autre — d'où la sauvegarde/mutation/
 * restauration ci-dessous, plutôt qu'une {@code CameraRenderState} neuve.
 */
@Mixin(GameRenderer.class)
public abstract class PortailRenderMixin {
    @Shadow
    @Final
    @Mutable
    private RenderTarget mainRenderTarget;

    @Shadow
    private Minecraft minecraft;

    @Shadow
    private FogRenderer fogRenderer;

    @Shadow
    public abstract GameRenderState gameRenderState();

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void lanterne$portail(CallbackInfo callback) {
        if (!PortailsConfig.TEST_PORTAL.get() || PortailPrototype.sessionDisabled()) {
            return;
        }
        if (this.minecraft.level == null || this.minecraft.player == null) {
            return;
        }
        try {
            lanterne$renderDestinationAndComposite();
        } catch (Throwable problem) {
            // Voir la Javadoc de classe : un incident ici n'est PAS traité comme un incident
            // d'image ordinaire (voir Gbuffer.run, qui lui continue d'essayer chaque image). Ce
            // mécanisme est trop neuf, et trop profond dans un rendu que ce mixin ne possède qu'à
            // moitié, pour risquer une corruption silencieuse répétée : une image cassée, puis
            // plus rien pour le reste de la session.
            PortailPrototype.disableSession();
            Lanterne.LOG.warn("[PORTAIL] Échec du second rendu de niveau — le prototype se coupe "
                    + "pour le reste de la session (voir notes/immersive-portals-faisabilite.md).",
                    problem);
        }
    }

    private void lanterne$renderDestinationAndComposite() {
        RenderTarget real = this.mainRenderTarget;
        if (real == null) {
            return;
        }
        RenderTarget destination = PortailPrototype.destinationTarget(real);
        if (destination == null) {
            return;
        }

        CameraRenderState camera = this.gameRenderState().levelRenderState.cameraRenderState;
        if (camera == null || camera.pos == null || camera.viewRotationMatrix == null) {
            return;
        }

        Vec3 realPos = camera.pos;
        double destX = realPos.x + PortailsConfig.TEST_OFFSET_X.get();
        double destY = realPos.y + PortailsConfig.TEST_OFFSET_Y.get();
        double destZ = realPos.z + PortailsConfig.TEST_OFFSET_Z.get();
        Vec3 destPos = new Vec3(destX, destY, destZ);

        camera.pos = destPos;
        if (camera.cullFrustum != null) {
            // Repositionne le MÊME frustum plutôt que d'en reconstruire un : sa forme ne change
            // pas (même orientation, même projection — voir la Javadoc de PortailPrototype), seul
            // son origine bouge. Frustum.prepare(x,y,z) est exactement la méthode publique prévue
            // pour ça (vérifiée par javap), à la différence d'une reconstruction depuis deux
            // matrices dont la convention exacte n'a pas été vérifiée cette passe.
            camera.cullFrustum.prepare(destX, destY, destZ);
        }

        RenderTarget savedMain = this.mainRenderTarget;
        this.mainRenderTarget = destination;
        try {
            this.minecraft.levelRenderer.render(GraphicsResourceAllocator.UNPOOLED, false, camera,
                    this.fogRenderer.getBuffer(FogRenderer.FogMode.WORLD), camera.fogData.color,
                    false, false);
        } finally {
            this.mainRenderTarget = savedMain;
            camera.pos = realPos;
            if (camera.cullFrustum != null) {
                camera.cullFrustum.prepare(realPos.x, realPos.y, realPos.z);
            }
        }

        PortailPrototype.compositeQuad(real, camera);
    }
}
