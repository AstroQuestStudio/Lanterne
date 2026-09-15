package fr.clubcitrouille.lanterne.client.upscale;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La toile : la cible de rendu réduite dans laquelle le monde est dessiné.
 *
 * <h2>Ce que la lecture des sources de 26.2 a changé au plan</h2>
 *
 * <p>Le plan de départ disait « trouver où la cible principale est dimensionnée et la rendre plus
 * petite ». Le code dit autre chose, et c'est mieux :
 *
 * <ul>
 *   <li>{@code GameRenderer.render} <b>remet</b> la cible principale à la taille de la fenêtre au
 *       début de chaque image, en comparant {@code windowRenderState} à {@code mainRenderTarget}.
 *       La rétrécir durablement est donc impossible : le jeu la regonflerait à l'image suivante.</li>
 *   <li>Surtout, {@code LevelRenderer.render} lit ses dimensions d'écran <b>dans la cible principale
 *       elle-même</b> et non dans la fenêtre :
 *       {@code int screenWidth = this.gameRenderer.mainRenderTarget().width;}. Toutes ses cibles
 *       intermédiaires — transparence, entités-objets, particules, météo, nuages — en héritent.</li>
 * </ul>
 *
 * <p>Il suffit donc de <b>substituer la cible principale le temps du monde</b>, et tout le rendu de
 * niveau suit sans qu'on ait à toucher à rien d'autre. L'interface, elle, est peinte après la
 * remontée, sur la vraie cible, à la résolution de la fenêtre. C'est le mixin qui opère l'échange ;
 * cette classe ne s'occupe que de la toile.
 *
 * <h2>Pourquoi la toile copie les réglages de la cible principale</h2>
 *
 * <p>Même format de couleur, même profondeur, <b>même pochoir</b>. Le pochoir n'est pas un détail :
 * NeoForge l'active à la demande d'un mod tiers, et une cible sans pochoir prise pour une cible avec
 * ferait échouer le premier rendu qui s'en sert. On recopie donc ce que la cible principale annonce
 * plutôt que de supposer.
 */
public final class Scene {
    private static RenderTarget target;

    private Scene() {}

    /**
     * Prépare la toile de cette image, ou refuse.
     *
     * <p>Rend {@code null} chaque fois que le monde doit être rendu en résolution native : module
     * éteint, facteur à un, chaîne de nuanceurs indisponible, allocation refusée. Le mixin
     * n'échange alors rien, et l'image est exactement celle de vanilla.
     *
     * @param screen la vraie cible principale, à la taille de la fenêtre
     */
    public static RenderTarget borrow(RenderTarget screen) {
        if (screen == null || !Upscale.active()) {
            release();
            return null;
        }

        double divisor = Upscale.divisor();
        int width = Math.max(1, (int) Math.round(screen.width / divisor));
        int height = Math.max(1, (int) Math.round(screen.height / divisor));
        // Une fenêtre minuscule, ou un facteur qui n'enlève rien : le détour coûterait deux passes
        // de post-traitement pour zéro pixel épargné.
        if (width >= screen.width || height >= screen.height) {
            release();
            return null;
        }

        // L'échelon du haut est relevé ici, une seule fois, pour que le journal dise pourquoi DLSS
        // ne sert pas — et non pour qu'il serve : voir Deep, qui explique ce qui manque.
        Deep.probe();

        // Vérifié AVANT l'échange, jamais après : si la chaîne manque, on ne doit pas s'être engagé
        // à dessiner le monde ailleurs que là où il sera présenté. C'est la différence entre « pas
        // de mise à l'échelle » et « écran noir ».
        if (!Deep.available() && Resolve.chain() == null) {
            release();
            return null;
        }

        if (target == null || target.width != width || target.height != height) {
            release();
            try {
                target = new TextureTarget("Lanterne / toile", width, height, true,
                        screen.useStencil, GpuFormat.RGBA8_UNORM);
            } catch (Throwable problem) {
                target = null;
                Lanterne.LOG.warn("[ÉCHELLE] Impossible d'allouer une toile de {}×{}.",
                        width, height, problem);
                Upscale.fail("la toile réduite n'a pas pu être allouée");
                return null;
            }
            Lanterne.LOG.info("[ÉCHELLE] Toile de {}×{} pour un écran de {}×{} — {} % des pixels.",
                    width, height, screen.width, screen.height, Upscale.pixelPercent());
        }
        return target;
    }

    /**
     * Étale la toile sur la cible principale.
     *
     * <p>N'a le droit d'échouer qu'en éteignant le module : à ce stade le monde <em>est</em> dans la
     * toile, et ne rien faire laisserait la cible principale telle que le jeu l'a nettoyée, donc
     * unie. Une image perdue, puis le rendu natif pour le reste de la session.
     */
    public static void give(RenderTarget screen, RenderTarget scene) {
        // Les trois échelons, dans l'ordre. Deep rend faux en toutes circonstances aujourd'hui, et
        // c'est écrit ainsi plutôt que supprimé : le jour où le pont natif existe, la bascule est
        // ici et nulle part ailleurs.
        if (Deep.available() && Deep.resolve(scene, screen)) {
            return;
        }
        Resolve.run(scene, screen);
    }

    /**
     * Rend la mémoire de la toile.
     *
     * <p>Appelée dès que le module cesse d'agir, et non gardée « au cas où » : une cible plein écran
     * en couleur et en profondeur, c'est une dizaine de mégaoctets de mémoire graphique qu'un joueur
     * qui a coupé le réglage n'a aucune raison de payer.
     */
    public static void release() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }

    /** La toile courante, ou {@code null}. Pour la jauge, qui aime dire des tailles vraies. */
    public static RenderTarget current() {
        return target;
    }
}
