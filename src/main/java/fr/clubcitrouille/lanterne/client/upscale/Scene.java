package fr.clubcitrouille.lanterne.client.upscale;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;

import net.minecraft.client.Minecraft;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.mixin.RenderTargetAccessor;

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
 *
 * <h2>L'identité de la toile est un contrat — et il a été rompu une fois</h2>
 *
 * <p>Toute la conception du module repose sur l'idée que ses lecteurs relisent
 * {@code gameRenderer.mainRenderTarget()} à chaque usage. C'est vrai <b>presque</b> partout. Une
 * classe de 26.2 fait exception, et elle a été trouvée en suivant la piste d'un mod concurrent :
 *
 * <pre>SkyRenderer.java:64   private final RenderTarget renderTarget;
 * LevelRenderer.java:333  this.skyRenderer = new SkyRenderer(…, this.gameRenderer.mainRenderTarget());</pre>
 *
 * <p>{@code SkyRenderer} <b>capture l'objet</b> à sa construction et s'en sert à treize endroits.
 * Comme il est bâti pendant {@code LevelRenderer.render}, c'est-à-dire pendant notre échange, il
 * capture <b>la toile</b>. Et il n'est rebâti que si {@code shouldResetSkyRenderer} est levé — ce
 * que vanilla ne fait qu'au rechargement des ressources.
 *
 * <p>Deux conséquences, toutes deux atteignables sans aucun mod tiers :
 *
 * <ul>
 *   <li>La toile est détruite — le joueur coupe le réglage, ou la houle change de palier — et le
 *       ciel continue d'écrire dans <b>des textures libérées</b>.</li>
 *   <li>La toile naît alors que le ciel tenait la vraie cible, et le ciel se peint à pleine taille
 *       pendant que le reste du monde se peint en réduit.</li>
 * </ul>
 *
 * <p>D'où les deux règles que cette classe applique désormais, et qu'il ne faut pas défaire :
 *
 * <ol>
 *   <li><b>La toile est redimensionnée sur place</b>, jamais remplacée. Un changement de taille ne
 *       change donc pas son identité, et le champ du ciel reste juste — il relit ses vues de
 *       texture à chaque usage, seule la <em>référence</em> était en cause.</li>
 *   <li>Quand l'identité change malgré tout — création, destruction — {@link #consumeSkyStale()}
 *       le signale, et le mixin lève le drapeau de vanilla pour que le ciel soit rebâti.</li>
 * </ol>
 *
 * <p>Le remède complet serait d'échanger les <em>champs de texture</em> à l'intérieur de l'unique
 * cible plutôt que l'objet, comme le fait {@code vitrail-shaders} pour cette raison exacte. C'est
 * plus sûr et plus invasif : il y faut un accesseur de mixin sur {@code RenderTarget}, dont les
 * quatre champs sont protégés. À faire le jour où quelqu'un pourra regarder une image.
 */
public final class Scene {
    private static RenderTarget target;

    /**
     * La cible intermédiaire de l'anticrénelage, à la <b>taille réduite</b>.
     *
     * <h2>Pourquoi elle est allouée ici et non déclarée dans le JSON</h2>
     *
     * <p>Une cible interne de {@code PostChain} prend par défaut la taille de l'écran, et le JSON
     * ne peut lui en donner qu'une <b>fixe</b>. Or celle-ci doit suivre la toile : l'anticrénelage
     * travaille <em>avant</em> la remontée, donc à la résolution réduite — et cette résolution
     * change avec le préréglage, avec la houle et avec la fenêtre.
     *
     * <p>On la fournit donc comme cible externe, exactement comme la toile elle-même. Sans
     * profondeur : une passe plein écran n'en a pas l'usage, et c'est autant de mémoire graphique
     * qu'on ne prend pas.
     */
    private static RenderTarget aa;

    /** Voir {@link #consumeSkyStale()}. */
    private static boolean skyStale;

    /**
     * Instant du premier appel de {@link #borrow} avec un monde présent, ou zéro tant qu'aucun monde
     * n'a été vu. Voir {@link #withinJoinGrace()}.
     */
    private static long worldSeenAtNanos;

    /**
     * Délai avant la toute première tentative de compilation de la chaîne, après l'apparition d'un
     * monde.
     *
     * <h2>Le vrai coupable du "premier join échoue, le second marche"</h2>
     *
     * <p>Reproduit en jeu réel : la toute première tentative de {@link Resolve#chain()} après un
     * chargement de monde échoue de façon déterministe — {@code PipelineBuilder} (thread
     * {@code Worker-Main}, pas le thread de rendu) journalise {@code Couldn't find source for
     * FRAGMENT shader}, et {@link Upscale#fail} coupe le module pour le reste de la session.
     *
     * <p>{@code ShaderManager.getOrLoadPostChain} met l'échec en cache de façon <b>permanente</b> —
     * lu en bytecode, pas supposé : une entrée absente déclenche {@code loadPostChain}, mais une
     * entrée déjà présente (même un échec, encodé {@code Optional.empty()}) est retournée telle
     * quelle, sans jamais retenter. Retenter sur le même identifiant après un échec ne changerait donc
     * rien : c'est la case vide du cache qu'on relirait, pas une vraie nouvelle tentative.
     *
     * <p>Plutôt que de vider ce cache privé par réflexion — fragile, et une fausse bonne idée pour un
     * problème de <em>moment</em> — on évite simplement la première tentative pendant que le
     * chargement du monde bat son plein : compilation de maillages, tourbillon de blocs-entités,
     * rafale de paquets. Cinq secondes de rendu natif, invisibles pour le joueur, contre un module
     * éteint pour toute la session.
     */
    private static final long JOIN_GRACE_NANOS = 5_000_000_000L;

    /**
     * Vrai tant que le monde vient d'apparaître depuis moins de {@link #JOIN_GRACE_NANOS}.
     *
     * <p>Le chronomètre repart de zéro à chaque disparition du monde (retour au menu, déconnexion) :
     * un rejoin est une nouvelle charge de démarrage, pas une continuation.
     */
    private static boolean withinJoinGrace() {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            worldSeenAtNanos = 0L;
            return false;
        }
        if (worldSeenAtNanos == 0L) {
            worldSeenAtNanos = System.nanoTime();
        }
        return System.nanoTime() - worldSeenAtNanos < JOIN_GRACE_NANOS;
    }

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

        // Un moteur de shaders détourne le rendu dans son propre pipeline, et notre échange de
        // cible principale n'a pas été observé composer avec lui. On rend la main plutôt que de
        // risquer une image fausse : voir Rival, qui porte le raisonnement et la façon de changer
        // d'avis le jour où quelqu'un aura regardé le résultat.
        if (Rival.stands()) {
            release();
            return null;
        }

        // Voir withinJoinGrace() : la toute première tentative de compilation, prise pendant la
        // tempête de chargement d'un monde qui vient d'apparaître, échoue de façon déterministe et
        // coupe le module pour de bon. On attend que ça se tasse avant d'essayer.
        if (withinJoinGrace()) {
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

        // Redimensionner SUR PLACE plutôt que jeter et réallouer. Ce n'est pas une économie :
        // c'est une correction. Voir le chapitre « L'identité de la toile est un contrat » en tête
        // de cette classe — SkyRenderer garde l'OBJET dans un champ final, et lui en substituer un
        // autre le laisserait dessiner dans une cible détruite.
        if (target != null && ((RenderTargetAccessor) target).lanterne$depthFormat()
                        == ((RenderTargetAccessor) screen).lanterne$depthFormat()
                && (target.width != width || target.height != height)) {
            try {
                target.resize(width, height);
                Lanterne.LOG.debug("[ÉCHELLE] Toile redimensionnée en {}×{}.", width, height);
            } catch (Throwable problem) {
                Lanterne.LOG.warn("[ÉCHELLE] Redimensionnement de la toile refusé.", problem);
                release();
            }
        }

        if (target == null || target.width != width || target.height != height) {
            release();
            try {
                target = new TextureTarget("Lanterne / toile", width, height, GpuFormat.RGBA8_UNORM,
                        ((RenderTargetAccessor) screen).lanterne$depthFormat());
                // Une toile neuve : l'identité change, donc le ciel doit être rebâti.
                skyStale = true;
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

        if (Upscale.antialias()) {
            if (aa == null || aa.width != width || aa.height != height) {
                releaseAa();
                try {
                    aa = new TextureTarget("Lanterne / anticrénelage", width, height,
                            GpuFormat.RGBA8_UNORM, null);
                } catch (Throwable problem) {
                    // On ne casse pas la mise à l'échelle pour autant : sans cette cible, la
                    // chaîne « aa_ » échouerait à s'exécuter, alors on redescend sur la variante
                    // sans anticrénelage plutôt que de rendre en natif. Une image moins propre
                    // vaut mieux qu'une image en moins.
                    aa = null;
                    Lanterne.LOG.warn("[ÉCHELLE] Cible d'anticrénelage de {}×{} refusée : la "
                            + "remontée se fera sans elle.", width, height, problem);
                }
            }
        } else {
            releaseAa();
        }
        return target;
    }

    /** La cible d'anticrénelage, ou {@code null} si elle n'est ni voulue ni allouable. */
    static RenderTarget antialiasTarget() {
        return aa;
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
            // La toile disparaît : si le ciel l'avait capturée, il dessine désormais dans une
            // cible détruite. Il doit être rebâti avant la prochaine passe de ciel.
            skyStale = true;
        }
        releaseAa();
    }

    /**
     * Le ciel doit-il être rebâti parce que la toile a changé d'identité ?
     *
     * <p>Consommé par le mixin, qui seul a le bon moment pour poser le drapeau de vanilla : il faut
     * le faire <b>après</b> l'extraction — qui l'efface à chaque image — et <b>avant</b> la passe de
     * ciel. Il n'y a qu'un instant qui satisfasse les deux, et c'est l'échange lui-même.
     */
    public static boolean consumeSkyStale() {
        boolean was = skyStale;
        skyStale = false;
        return was;
    }

    private static void releaseAa() {
        if (aa != null) {
            aa.destroyBuffers();
            aa = null;
        }
    }

    /** La toile courante, ou {@code null}. Pour la jauge, qui aime dire des tailles vraies. */
    public static RenderTarget current() {
        return target;
    }
}
