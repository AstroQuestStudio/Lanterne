package fr.clubcitrouille.lanterne.client.upscale;

import java.lang.reflect.Method;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.report.Compagnons;

/**
 * L'autre moteur : un mod de shaders a-t-il pris la main sur le rendu du monde ?
 *
 * <h2>Pourquoi la question se pose, et pourquoi elle est sérieuse</h2>
 *
 * <p>Notre mise à l'échelle repose sur une hypothèse précise, lue dans les sources de 26.2 :
 * {@code LevelRenderer.render} prend ses dimensions dans
 * {@code gameRenderer.mainRenderTarget().width}, si bien qu'il suffit de <b>substituer la cible
 * principale</b> le temps du monde pour que tout le rendu de niveau suive. Voir {@link Scene}.
 *
 * <p>Un mod de shaders ne se contente pas de dessiner autrement : il <b>détourne le rendu dans son
 * propre pipeline</b> — passe d'ombre, tampons de géométrie, cibles multiples, passe finale. S'il
 * prend ses dimensions au même endroit que nous et au bon moment, tout compose, et son pipeline
 * entier tourne à l'échelle réduite : c'est exactement le gain qu'on cherche, et c'est même le
 * meilleur des cas, puisque les shaders sont ce qui coûte le plus cher. Mais s'il les prend
 * <b>ailleurs</b> — la fenêtre — ou <b>plus tôt</b> que notre substitution, on obtient une image
 * décalée, étirée, ou noire.
 *
 * <h2>La règle, et elle n'est pas symétrique</h2>
 *
 * <p>Tant que la composition n'a pas été <b>observée sur une image</b>, cette classe fait rendre la
 * main. Ce choix est délibérément conservateur, et il découle d'une asymétrie réelle : un joueur qui
 * n'a pas la mise à l'échelle perd quelques images par seconde et ne s'en aperçoit pas ; un joueur
 * dont l'écran est déformé ou noir croit que le mod est cassé, et il a raison de le croire.
 *
 * <p>C'est donc ici, et dans {@link #stands()}, que se change la politique le jour où quelqu'un
 * aura <b>regardé</b> le résultat. Pas ailleurs, et pas avant.
 *
 * <h2>Pourquoi la réflexion, et pourquoi elle est sans danger ici</h2>
 *
 * <p>Ce mod ne compile pas contre Iris et ne doit pas en dépendre : il doit fonctionner à
 * l'identique quand il est absent, ce qui est le cas de la grande majorité des installations. On
 * interroge donc son interface publique par réflexion, une seule résolution mise en cache, et
 * <b>tout échec vaut « oui, un pack est actif »</b> — c'est-à-dire le sens qui nous fait reculer.
 * Se tromper par excès de prudence coûte des images ; se tromper dans l'autre sens coûte l'écran.
 */
public final class Rival {
    /** Iris — le moteur de shaders de référence, et le seul qui expose une interface stable. */
    public static final String IRIS = "iris";

    /** Oculus — le portage Forge historique d'Iris. Même interface publique. */
    public static final String OCULUS = "oculus";

    /** Vitrail — les packs au format OptiFine sur le backend Vulkan. */
    public static final String VITRAIL = "vitrail";

    /** L'interface publique et versionnée d'Iris. Le {@code v0} fait partie du nom, et il est stable. */
    private static final String API = "net.irisshaders.iris.api.v0.IrisApi";

    /** Résolu une seule fois : nul tant qu'on n'a pas cherché, présent ou définitivement absent après. */
    private static Method inUse;
    private static Object instance;
    private static boolean resolved;
    private static boolean announced;

    /** Vrai dès qu'un mod de shaders a été vu. Voir {@link #present()} pour pourquoi il se fige. */
    private static boolean installed;

    private Rival() {}

    /**
     * Un mod de shaders est-il installé ? Ne dit rien de ce qu'il fait.
     *
     * <p>Mis en cache dès la première réponse utile, parce qu'à la différence de {@link #engaged()}
     * cette réponse-là <b>ne peut pas</b> changer en cours de partie : la liste des mods est figée
     * au démarrage. Ce chemin est interrogé à chaque image, et trois recherches dans la table des
     * mods par image pour une valeur constante seraient trois de trop.
     */
    public static boolean present() {
        if (!installed) {
            // Faux tant que la liste des mods n'est pas peuplée. On ne fige donc que les « oui » :
            // un « non » prononcé trop tôt se reposera à l'image suivante.
            boolean any = Compagnons.present(IRIS) || Compagnons.present(OCULUS)
                    || Compagnons.present(VITRAIL);
            if (!any) {
                return false;
            }
            installed = true;
        }
        return true;
    }

    /**
     * Un pack de shaders est-il <b>réellement en service</b> à cette image ?
     *
     * <p>La distinction compte plus qu'il n'y paraît : Iris installé sans pack sélectionné laisse le
     * rendu de vanilla intact, et notre mise à l'échelle y fonctionne exactement comme sans lui.
     * Reculer dans ce cas-là serait une perte sèche pour un joueur qui n'a rien activé.
     *
     * <p>La valeur n'est <b>pas</b> mise en cache : un joueur change de pack en cours de partie, et
     * un cadran qui afficherait l'état d'il y a dix minutes mentirait. Seule la résolution
     * réflexive l'est, et elle ne se fait qu'une fois.
     */
    public static boolean engaged() {
        if (!present()) {
            return false;
        }
        if (!resolved) {
            resolve();
        }
        if (inUse == null || instance == null) {
            // Un mod de shaders est là et l'on ne sait pas l'interroger. On suppose qu'il travaille.
            return true;
        }
        try {
            return Boolean.TRUE.equals(inUse.invoke(instance));
        } catch (Throwable problem) {
            return true;
        }
    }

    /**
     * Faut-il rendre la main ?
     *
     * <h2>Iris : non. Et ce n'est pas un pari</h2>
     *
     * <p>La lecture des sources d'Iris (branche 26.2) répond trois fois oui à la composition :
     *
     * <ul>
     *   <li>Iris lit ses dimensions <b>exclusivement</b> dans
     *       {@code gameRenderer.mainRenderTarget()} — zéro occurrence de {@code Window.getWidth()}
     *       dans son chemin de rendu.</li>
     *   <li>Il le fait <b>au bon moment</b> : {@code beginLevelRendering()} est appelé depuis un
     *       {@code @Inject(method = "render", at = @At("HEAD"))} sur {@code LevelRenderer}, donc
     *       <b>à l'intérieur</b> de {@code renderLevel}, donc <b>après</b> notre échange.</li>
     *   <li>Il <b>refuse délibérément</b> de mettre la cible en cache, et son code le dit :
     *       <i>« It is not safe to cache the render target due to mods like Resolution Control
     *       modifying the render target field »</i> ({@code ViewportUniforms.java}). Notre technique
     *       y est nommée et soutenue.</li>
     * </ul>
     *
     * <p>Et c'est le cas où la composition <b>rapporte le plus</b> : un pack de shaders est ce qui
     * coûte le plus cher d'une image, et il tourne alors entièrement à l'échelle réduite.
     *
     * <h2>Vitrail : oui, et pour une raison opposée</h2>
     *
     * <p>Vitrail ne risque pas de casser l'image : il fait <b>déjà</b> la même chose que nous, avec
     * son propre curseur de résolution et sa propre passe FSR 1.0. Deux réductions superposées
     * donneraient un monde rendu à 45 % de 45 %, et le joueur n'aurait aucun moyen de comprendre
     * d'où vient la bouillie. On lui laisse la main : c'est lui qui tient le pipeline.
     */
    public static boolean stands() {
        boolean standing = Compagnons.present(VITRAIL);
        if (standing && !announced) {
            announced = true;
            Lanterne.LOG.info("[ÉCHELLE] Vitrail est installé : la mise à l'échelle se met en "
                    + "retrait. Il a déjà son propre curseur de résolution et sa propre passe "
                    + "FSR — deux réductions superposées rendraient le monde à 45 % de 45 %.");
        } else if (!standing && engaged() && !announced) {
            announced = true;
            Lanterne.LOG.info("[ÉCHELLE] Un pack de shaders est en service, et la mise à l'échelle "
                    + "reste active : Iris relit la cible principale à chaque image et se "
                    + "dimensionne dessus. Tout son pipeline tourne donc à l'échelle réduite, ce "
                    + "qui est précisément là où le gain compte le plus. Si l'image paraît fausse, "
                    + "couper l'upscaling dans l'écran Lanterne le dira tout de suite.");
        }
        return standing;
    }

    /** Une ligne pour l'écran de réglages, qui doit dire ce qui se passe et non « indisponible ». */
    public static String describe() {
        if (!present()) {
            return "";
        }
        if (stands()) {
            return "en retrait (Vitrail)";
        }
        return engaged() ? "avec shaders" : "shaders inactifs";
    }

    private static void resolve() {
        resolved = true;
        try {
            Class<?> api = Class.forName(API);
            instance = api.getMethod("getInstance").invoke(null);
            inUse = api.getMethod("isShaderPackInUse");
        } catch (Throwable absent) {
            // Ni une erreur ni une surprise : Oculus et Vitrail n'exposent pas forcément cette
            // interface, et Iris peut la renommer. On le note une fois, en débogage, et on retombe
            // sur l'hypothèse prudente.
            inUse = null;
            instance = null;
            Lanterne.LOG.debug("[ÉCHELLE] Interface d'Iris introuvable : on suppose un pack actif.",
                    absent);
        }
    }
}
