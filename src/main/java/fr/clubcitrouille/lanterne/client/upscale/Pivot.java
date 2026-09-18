package fr.clubcitrouille.lanterne.client.upscale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.PreferredGraphicsApi;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le basculement OpenGL ↔ Vulkan, en un clic, depuis l'écran du mod.
 *
 * <h2>Pourquoi ce fichier existe alors que vanilla a déjà l'option</h2>
 *
 * <p>Parce qu'un gain que personne ne sait activer est un gain qui n'existe pas. L'option de Mojang
 * est réelle, publique, et enfouie dans les réglages vidéo sous une étiquette « expérimental » que
 * la quasi-totalité des joueurs ne trouvera jamais et n'oserait pas cocher si elle la trouvait.
 * L'écran de ce mod, lui, a une raison précise d'en parler : c'est lui qui vient d'annoncer au
 * joueur que sa carte sait faire DLSS et que le backend l'en empêche. Annoncer un verrou sans
 * tendre la clé serait de la décoration.
 *
 * <h2>Ce que le clic fait, et pourquoi il écrit le fichier</h2>
 *
 * <p>{@code OptionInstance.set} ne touche qu'un champ en mémoire. Sans {@code Options.save()} qui
 * suit, le choix se perd au redémarrage — et comme ce réglage-ci <b>ne prend effet qu'au
 * redémarrage</b>, il se perdrait exactement au moment où il devait servir. Le mod s'est déjà fait
 * prendre à cette faute sur son propre fichier de configuration ; voir {@code Dials.commit()}.
 *
 * <h2>Un clic, deux fichiers — et le second n'est pas optionnel</h2>
 *
 * <p>Poser {@code VULKAN} dans {@code options.txt} ne suffit <b>pas</b> sur NeoForge : son écran de
 * chargement cède à Minecraft une fenêtre déjà créée en OpenGL, et GLFW refuse alors d'y ouvrir une
 * surface Vulkan. Le jeu ne démarre pas. {@link Dawn} porte la démonstration et le remède — une
 * ligne de {@code config/fml.toml}.
 *
 * <p>Les deux écritures sont donc faites <b>ensemble</b>, par le même clic. Les séparer en deux
 * réglages aurait été plus honnête à écrire et pire à utiliser : un joueur sur deux aurait fait le
 * premier geste, conclu que Vulkan ne marche pas, et eu raison de le conclure.
 *
 * <h2>Le filet de sécurité est réel, et il mérite d'être dit</h2>
 *
 * <p>{@code Minecraft} note à chaque lancement si le précédent s'est terminé proprement. Si non, et
 * que le backend demandé était {@code VULKAN}, il le remet lui-même sur {@code DEFAULT} et
 * <b>enregistre</b> — c'est écrit dans {@code Minecraft.<init>}, juste avant la création de la
 * fenêtre. Mieux : {@code PreferredGraphicsApi.getBackendsToTry()} rend {@code [vulkan, gl]} pour
 * {@code VULKAN}, donc un pilote qui refuse Vulkan fait retomber le jeu sur OpenGL <b>dans le même
 * lancement</b>, sans plantage ni intervention.
 *
 * <p>On ne peut donc pas se coincer, et c'est précisément l'information qui décide un joueur à
 * essayer. Un joueur qui l'ignore n'essaie pas.
 *
 * <h2>Ce que la classe ne prétend pas savoir</h2>
 *
 * <p>Elle ne promet aucun gain de performance. Le backend Vulkan de 26.2 est marqué expérimental par
 * Mojang, qui l'a rétrogradé de défaut à expérimental entre deux instantanés ; il est plus rapide
 * sur certaines machines et plus lent sur d'autres. Le seul argument <b>vérifiable</b> que ce mod
 * ait à avancer est celui-ci : DLSS exige un contexte Vulkan, et il n'y a pas d'autre chemin.
 */
public final class Pivot {
    private Pivot() {}

    /**
     * L'état du backend, tel qu'un écran de réglages doit le présenter.
     *
     * <p>Six cas et non deux, parce que le réglage et la réalité peuvent diverger de trois manières
     * différentes, et que les confondre ferait mentir la ligne.
     */
    public enum State {
        /** Le relevé n'a pas pu se faire — périphérique graphique absent ou options non chargées. */
        INCONNU("non relevé"),
        /** Vulkan demandé, Vulkan obtenu. */
        VULKAN("actif"),
        /** Vulkan demandé, pas encore obtenu : il faut relancer le jeu. */
        VULKAN_AU_RELANCEMENT("au prochain lancement"),
        /**
         * Vulkan demandé, mais l'écran de chargement de NeoForge est encore actif — et il cédera à
         * Minecraft une fenêtre OpenGL sur laquelle aucune surface Vulkan ne peut s'ouvrir.
         *
         * <p>Cet état existe pour le joueur qui a basculé par les options vidéo de vanilla plutôt
         * que par ce cadran : il aurait obtenu un refus au lancement sans savoir pourquoi. Voir
         * {@link Dawn}.
         */
        VULKAN_ENTRAVE("écran de chargement à couper"),
        /**
         * Vulkan demandé, aucun redémarrage en attente, et pourtant on tourne sur OpenGL. Le
         * backend a donc été tenté puis refusé par le pilote au démarrage.
         */
        VULKAN_REFUSE("refusé par le pilote"),
        /** OpenGL, et c'est bien ce qui est demandé. */
        OPENGL("arrêté"),
        /** OpenGL demandé alors que Vulkan tourne : le retour prendra effet au relancement. */
        OPENGL_AU_RELANCEMENT("coupé au relancement");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    /** Le dernier état annoncé au journal, pour ne pas répéter la même ligne à chaque clic. */
    private static State announced;

    /**
     * Où en est-on ?
     *
     * <p>Le relevé compare <b>trois</b> choses et non deux : le réglage enregistré, le backend
     * réellement en service, et le verdict de vanilla sur un redémarrage en attente. Comparer le
     * réglage au backend seul ne permettrait pas de distinguer « tu n'as pas encore relancé » de
     * « le pilote a dit non » — deux situations qui appellent des phrases opposées.
     */
    public static State state() {
        try {
            Options options = Minecraft.getInstance().options;
            if (options == null) {
                return State.INCONNU;
            }
            boolean wanted = options.preferredGraphicsBackend().get() == PreferredGraphicsApi.VULKAN;
            boolean live = onVulkan();
            if (wanted == live) {
                return wanted ? State.VULKAN : State.OPENGL;
            }
            // Vulkan demandé sans avoir coupé l'écran de chargement : le prochain lancement
            // échouera, et il vaut mieux le dire maintenant qu'après le refus. Ce cas passe AVANT
            // celui du redémarrage en attente, parce qu'il le rend inutile.
            if (wanted && Dawn.state() == Dawn.State.ACTIF) {
                return State.VULKAN_ENTRAVE;
            }
            // Vanilla sait déjà dire qu'un redémarrage est en attente : sa méthode compare le
            // réglage à la valeur qu'il avait au démarrage, ce qu'on ne peut pas refaire ici — le
            // champ est privé et sans accesseur. Elle englobe aussi le plein écran exclusif, ce
            // qui ne nous gêne que dans un cas rare et sans conséquence : Vulkan refusé au
            // démarrage ET plein écran changé depuis, où l'on dira « relance » au lieu de
            // « refusé ». Relancer reste un bon conseil, et la ligne suivante redira la vérité.
            if (options.isRestartRequiredToApplyVideoSettings()) {
                return wanted ? State.VULKAN_AU_RELANCEMENT : State.OPENGL_AU_RELANCEMENT;
            }
            return wanted ? State.VULKAN_REFUSE : State.OPENGL;
        } catch (Throwable problem) {
            Lanterne.LOG.debug("[ÉCHELLE] Relevé du backend graphique impossible.", problem);
            return State.INCONNU;
        }
    }

    /**
     * Le jeu tourne-t-il sur Vulkan à cette seconde ?
     *
     * <p>Délègue à {@link fr.clubcitrouille.lanterne.client.Vigie#vulkanActif()}, l'unique source
     * pour cette question — voir sa Javadoc. Cette classe interrogeait auparavant
     * {@code RenderSystem.getDevice()} elle-même, en double de {@link Vigie} et de {@link Deep}.
     */
    public static boolean onVulkan() {
        return fr.clubcitrouille.lanterne.client.Vigie.vulkanActif();
    }

    /**
     * Y a-t-il quelque chose à proposer à ce joueur ?
     *
     * <p>Faux si le relevé a échoué. Vrai dans tous les autres cas, <b>y compris sans carte RTX</b> :
     * ce basculement ne sert pas qu'à DLSS, et décider à la place du joueur qu'il ne l'intéresse pas
     * serait exactement le travers que cette classe corrige.
     */
    public static boolean offerable() {
        return state() != State.INCONNU;
    }

    /**
     * Bascule le backend demandé et écrit le fichier.
     *
     * <p>Le sens du clic ne dit rien : il n'y a que deux valeurs utiles, et le troisième cran de
     * l'énumération de Mojang — {@code DEFAULT} — n'est pas un choix du joueur mais l'état vierge.
     * C'est vers lui qu'on revient plutôt que vers {@code OPENGL}, pour qu'un joueur qui fait
     * l'aller-retour retrouve son fichier tel qu'il l'a trouvé.
     *
     * @return l'état après bascule, que l'appelant peut afficher tel quel
     */
    public static State toggle() {
        try {
            Options options = Minecraft.getInstance().options;
            if (options == null) {
                return State.INCONNU;
            }
            boolean wanted = options.preferredGraphicsBackend().get() == PreferredGraphicsApi.VULKAN;
            PreferredGraphicsApi next = wanted ? PreferredGraphicsApi.DEFAULT
                    : PreferredGraphicsApi.VULKAN;
            options.preferredGraphicsBackend().set(next);
            // Sans cette ligne le choix ne survit pas au redémarrage, c'est-à-dire ne survit pas
            // jusqu'au seul moment où il aurait pu servir.
            options.save();
            // Le second geste, sans lequel le premier ne sert à rien : l'écran de chargement de
            // NeoForge cède à Minecraft une fenêtre OpenGL, et Vulkan ne peut pas y ouvrir de
            // surface. Un clic doit suffire — un joueur qui devrait trouver tout seul une clé de
            // config/fml.toml n'aurait pas Vulkan. Voir Dawn pour la démonstration.
            Dawn.set(next != PreferredGraphicsApi.VULKAN);
            State after = state();
            if (after != announced) {
                announced = after;
                Lanterne.LOG.info("[ÉCHELLE] Backend graphique demandé : {} — état : {}.",
                        next.getSerializedName(), after.label());
            }
            return after;
        } catch (Throwable problem) {
            Lanterne.LOG.warn("[ÉCHELLE] Le backend graphique n'a pas pu être changé.", problem);
            return State.INCONNU;
        }
    }
}
