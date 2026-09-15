package fr.clubcitrouille.lanterne.client.sodium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;

import fr.clubcitrouille.lanterne.client.Dials;

/**
 * Le greffon : la porte vers les cadrans, posée cette fois chez Sodium.
 *
 * <h2>Le défaut, dit par le joueur</h2>
 *
 * <p><em>« Quand on installe Sodium en plus dessus, faut impérativement avoir nos sections dans les
 * settings Sodium, car sinon Sodium prend le dessus et ça c'est hyper chiant ! »</em>
 *
 * <p>Il a raison, et le mécanisme est vérifiable au bytecode. Sodium greffe
 * {@code OptionsScreenMixin} sur {@code OptionsScreen.lambda$init$3} — la fabrique qui construit
 * l'écran vidéo de vanilla quand on clique sur « Graphismes » — en {@code HEAD}, {@code cancellable},
 * et lui substitue son propre écran. Le {@code VideoSettingsScreen} de vanilla n'est alors
 * <b>jamais construit</b>, donc son {@code addOptions} n'est jamais appelé, donc
 * {@code VideoOptionsMixin} ne pose jamais son bouton. Le joueur perd d'un coup l'accès à
 * soixante-cinq réglages, sans le moindre message.
 *
 * <h2>Pourquoi une page qui renvoie ailleurs, et non nos familles recopiées</h2>
 *
 * <p>L'API de Sodium sait faire les deux. Elle offre un modèle d'options complet — interrupteurs,
 * curseurs, énumérations, liaisons, drapeaux de rechargement — avec lequel on pourrait rebâtir les
 * quatre familles réglables à l'intérieur du cadre de Sodium. C'est la voie qui paraît la plus
 * intégrée, et c'est celle qu'on écarte, pour trois raisons qui se vérifient toutes dans
 * {@link Dials} :
 *
 * <ol>
 *   <li><b>Deux interfaces à tenir, donc deux occasions de diverger.</b> Les soixante-cinq lignes
 *       existent déjà, avec leur colonne de détail. Les redire ailleurs, c'est s'engager à les
 *       corriger deux fois — et l'oubli se voit chez le joueur, pas ici.</li>
 *   <li><b>Le modèle de Sodium ne sait pas dire ce qu'un réglage coûte.</b> Il porte un
 *       {@code setTooltip(Component)}, c'est-à-dire un bloc. Le panneau de détail des cadrans en dit
 *       trois — ce que le module fait, ce qu'il coûte, quand l'effet est visible — et cette
 *       séparation est précisément ce qui empêche d'activer huit modules sans se demander pourquoi
 *       le jeu a changé.</li>
 *   <li><b>La moitié de l'écran ne se règle pas.</b> Les quatre dernières familles sont la lecture
 *       d'un fichier serveur. L'API de Sodium n'a pas de réglage en lecture seule : tout ce qu'on y
 *       déclare est une valeur que le joueur peut changer. Ces quatre familles seraient donc soit
 *       perdues, soit mensongères.</li>
 * </ol>
 *
 * <p>Reste ce que Sodium appelle une <b>page externe</b>. Ce n'est pas un bouton égaré au bas d'une
 * liste : {@code createExternalPage} inscrit une entrée dans la colonne de gauche de Sodium, sous un
 * en-tête à notre nom et à notre version — que Sodium lit tout seul dans {@code neoforge.mods.toml}
 * via {@code registerOwnModOptions} — et qui ouvre notre écran au clic. Le joueur voit « Lanterne »
 * au même rang que « Qualité » ou « Avancé », et c'est exactement ce qu'il demandait.
 *
 * <p>C'est aussi ce que Sodium recommande. Son {@code USAGE.md}, mot pour mot : <em>« To simply
 * switch to a new Screen when an entry in the video settings screen's page list is clicked, use
 * ConfigBuilder.createExternalPage »</em>.
 *
 * <h2>Rien de tout cela ne charge quoi que ce soit sans Sodium</h2>
 *
 * <p>Aucune classe de Lanterne ne nomme celle-ci. On ne l'instancie nulle part, on ne l'enregistre
 * nulle part, elle n'écoute aucun évènement. Son nom n'existe qu'en <b>texte</b>, dans une ligne de
 * {@code neoforge.mods.toml} :
 *
 * <pre>
 * [modproperties.lanterne]
 * "sodium:config_api_user" = "fr.clubcitrouille.lanterne.client.sodium.Graft"
 * </pre>
 *
 * <p>C'est Sodium — et lui seul — qui lit cette chaîne, fait le {@code Class.forName} et appelle le
 * constructeur sans argument. Sans Sodium, la classe n'est jamais résolue ; les interfaces
 * {@code net.caffeinemc.*} qu'elle implémente ne sont donc jamais cherchées, et il ne peut pas y
 * avoir de {@code NoClassDefFoundError}. C'est le même raisonnement que
 * {@code client/ponder/Hint.java} tient pour JEI : la dépendance est réelle à la compilation et
 * inexistante à l'exécution.
 *
 * <h2>Ce qui n'est pas repris, et pourquoi c'est important</h2>
 *
 * <p>Sodium est sous <b>PolyForm Shield 1.0.0</b> — une licence de source ouverte à la lecture mais
 * <em>non libre</em>, incompatible avec la GPL-3.0-only de Lanterne. Pas une ligne de Sodium n'entre
 * ici : l'artefact {@code sodium-neoforge-api} est déclaré {@code compileOnly} dans
 * {@code build.gradle}, il n'est ni embarqué, ni ombré, ni redistribué. L'archive publiée de
 * Lanterne ne contient aucun octet de Sodium. Voir {@code NOTICE.md}.
 */
public final class Graft implements ConfigEntryPoint {
    /**
     * Public et sans argument, et ce n'est pas un hasard : {@code ConfigManager} appelle
     * {@code getDeclaredConstructor().newInstance()} sur le nom lu dans le fichier. Le rendre privé,
     * ou lui donner un paramètre, ne casserait rien à la compilation et laisserait seulement une
     * ligne d'avertissement dans le journal du joueur.
     */
    public Graft() {}

    /**
     * La phase tardive, et non {@code registerConfigEarly}.
     *
     * <p>La phase précoce sert à ceux qui veulent que d'autres mods puissent ensuite <em>remplacer</em>
     * ce qu'ils ont déclaré. Nous n'offrons rien à remplacer, et la documentation de Sodium est
     * nette : seules les options enregistrées tardivement apparaissent dans l'écran.
     */
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
                .addPage(builder.createExternalPage()
                        .setName(Dials.DOOR)
                        .setScreenConsumer(Graft::open));
    }

    /**
     * Ouvre les cadrans depuis l'écran de Sodium.
     *
     * <p>Sodium transmet <b>son</b> écran et n'en change pas lui-même : le widget appelle le
     * consommateur puis joue le clic, rien de plus. C'est donc à nous de poser l'écran, et de garder
     * le sien comme parent — sans quoi Échap ramènerait le joueur au menu et non là d'où il vient.
     */
    private static void open(Screen current) {
        Minecraft.getInstance().gui.setScreen(new Dials(current));
    }
}
