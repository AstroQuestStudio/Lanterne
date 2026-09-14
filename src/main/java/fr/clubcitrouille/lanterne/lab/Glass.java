package fr.clubcitrouille.lanterne.lab;

import java.util.Arrays;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * La vitre à travers laquelle on regarde le rendu : le banc d'images, reproductible et sans main
 * humaine.
 *
 * <h2>Avertissement de compatibilité — à lire avant de câbler cette classe</h2>
 *
 * <p><b>Cette classe référence {@code net.minecraft.client.Minecraft} et
 * {@code net.minecraft.client.player.LocalPlayer} — des classes qui n'existent tout simplement pas
 * sur un serveur dédié.</b> Lanterne tourne désormais des deux côtés ; charger {@code Glass} sur un
 * serveur dédié provoquerait un {@code NoClassDefFoundError} au premier appel qui touche réellement
 * ces types (le chargement d'une classe est paresseux en JVM, mais un appel suffit à le déclencher).
 *
 * <p><b>Cette classe ne s'enregistre auprès d'aucun bus d'évènement elle-même</b> — volontairement :
 * ce choix appartient à celui qui la câble, avec la visibilité complète sur le cycle de vie du mod.
 * Deux méthodes seulement ont des contrats de sécurité différents :
 *
 * <ul>
 *   <li>{@link #armed()} ne touche à aucune classe client : elle lit une variable d'environnement
 *       et rien d'autre. Elle peut être appelée <b>sans condition</b>, y compris depuis un contexte
 *       serveur dédié — c'est fait pour.</li>
 *   <li>{@link #begin()} et {@link #frame()} touchent {@code Minecraft} et {@code LocalPlayer}
 *       directement. Elles ne doivent <b>jamais</b> être atteintes ailleurs que sur la distribution
 *       client.</li>
 * </ul>
 *
 * <p>La façon sûre de les relier, non appliquée ici à dessein : un {@code @EventBusSubscriber} du
 * bus de jeu, restreint à la distribution client (ce qui empêche NeoForge de charger la classe
 * porteuse — et donc {@code Glass} — sur un serveur dédié), placé dans une classe séparée d'un
 * paquet {@code .client}, qui appelle {@code Glass.frame()} sur l'évènement de rendu. Voir le résumé
 * de livraison pour le nom exact de cet évènement et le niveau de certitude de chaque affirmation
 * NeoForge (le dépôt {@code .mcsrc} ne contient que du vanilla patché — les classes
 * {@code net.neoforged.*} elles-mêmes n'y sont pas décompilées).
 *
 * <h2>Pourquoi le temps par image, et pas les images par seconde</h2>
 *
 * <p>{@code Minecraft.getFps()} est une moyenne recalculée une fois par seconde
 * ({@code Minecraft.java}, boucle {@code while (Util.getMillis() >= this.lastTime + 1000L)}) : un
 * entier grossier, et une moyenne — deux défauts pour un banc qui se méfie justement des moyennes.
 *
 * <p>{@code Minecraft.getFrameTimeNs()} est calculé image par image,
 * {@code this.frameTimeNs = Util.getNanos() - renderStartTimer;}, <b>avant</b> le plafonneur
 * d'images ({@code FramerateLimiter.limitDisplayFPS}, exécuté seulement si le plafond est en dessous
 * de 260). C'est donc le coût réel de rendu d'une image, non pollué par une synchronisation
 * verticale ou un plafond actif — le seul des deux signaux qui mesure ce qu'on veut mesurer.
 *
 * <h2>Pourquoi la caméra est reposée à chaque image, et non une seule fois</h2>
 *
 * <p>Le rapport de recherche suggérait de placer le joueur « en vol » une fois, puis de le laisser.
 * En vérifiant {@code Entity.java}, un choix plus strict s'est imposé : {@code snapTo(x, y, z, yRot,
 * xRot)} ne fait pas que déplacer l'entité, il fixe aussi sa position et sa rotation
 * <em>précédentes</em> ({@code setOldPosAndRot()}) au même point. Rappeler cette méthode à chaque
 * image, plutôt qu'une fois au début, élimine non seulement la chute (le vol y suffisait déjà) mais
 * aussi tout residuum — tangage de la marche, recul d'un souffle d'explosion lointain, dérive de
 * l'entrée clavier — qu'aucun réglage de vol ne couvre. Deux exécutions voient alors des coordonnées
 * <em>identiques au bit près</em>, image après image, ce qui est une garantie plus forte que « ne
 * tombe pas ».
 *
 * <h2>Pourquoi deux phases, avec et sans le mod</h2>
 *
 * <p>Lanterne n'a aucun crochet dans le moteur de rendu : rien, dans ce mod, ne dessine un chunk ou
 * une entité différemment selon {@link Settings#enabled()}. On pourrait donc conclure d'avance que
 * la bascule ne change rien au temps d'image, et se contenter d'une phase.
 *
 * <p>Mais en solo, le rendu et la simulation tournent dans le <b>même processus</b> : le serveur
 * intégré et le client se disputent le même processeur. Si Lanterne réduit le travail par tick du
 * serveur (c'est tout son projet), il libère potentiellement du temps processeur que le thread de
 * rendu peut récupérer — un effet indirect, médié par la contention, jamais mesuré, et que ce projet
 * refuse de supposer plutôt que de chiffrer. On mesure donc les deux phases, exactement comme
 * {@code Bench}. Si l'écart est nul, ce banc le dira aussi clairement que l'inverse — et ce sera une
 * réponse, pas un silence.
 *
 * <h2>Pourquoi la médiane, et le centile, jamais la moyenne</h2>
 *
 * <p>Le raisonnement est celui de {@code Bench} : une image ralentie par une compilation de shader
 * en retard ou un ramassage de mémoire ajoute des dizaines de millisecondes à une seule image, et la
 * moyenne en est ruinée. La médiane n'en sait rien.
 *
 * <p>Mais la médiane seule cache exactement ce que le ressenti d'un joueur remarque le plus : les
 * à-coups. On calcule donc aussi le <b>1 % le plus lent</b> — la moyenne des images les plus longues
 * de l'échantillon — parce qu'un serveur qui tient une médiane honorable mais dont une image sur cent
 * dépasse cent millisecondes est perçu comme saccadé, quoi que dise sa moyenne.
 *
 * <h2>Ce que ce protocole refuse de mesurer</h2>
 *
 * <p>Si la fenêtre est réduite, {@code Minecraft.java} saute purement et simplement la présentation
 * à l'écran ({@code if (!windowRenderState.isMinimized) { this.mainRenderTarget.blitToScreen(); }})
 * avant de calculer {@code frameTimeNs}. Une mesure prise fenêtre réduite ne mesure plus le travail
 * de rendu réel : elle mesure l'absence d'un travail qu'on croit encore présent. Ce protocole vérifie
 * cette condition à chaque image et <b>refuse</b> — au sens de {@code Preflight} : un chiffre
 * silencieux vaut mieux qu'un chiffre qui ment — dès qu'elle se présente.
 *
 * <h2>Limites honnêtes</h2>
 *
 * <ul>
 *   <li>Le rendu dépend du pilote graphique, des autres processus, de l'état thermique de la
 *       machine — bien plus bruyant qu'un banc processeur pur. Une comparaison n'a de sens que sur
 *       <b>la même machine</b>, jamais entre deux postes, et un seul passage ne prouve rien : c'est
 *       la discipline de répétition de {@code Bench}/{@code Herd}, pas une propriété de ce fichier.
 *   <li>{@code getFrameTimeNs()} mesure le rendu, pas la fluidité perçue. Un signal réseau qui
 *       change le pas d'interpolation d'une entité lointaine ne changerait rien à ce chiffre tout en
 *       changeant beaucoup le ressenti — ce protocole est aveugle à cette classe d'effets par
 *       construction, pas par oubli.
 *   <li>Les coordonnées de pose supposent un monde de banc centré sur l'origine, comme le sont les
 *       charges de {@link Scene}. Rien ici ne construit ni ne peuple ce monde : {@code Glass} suppose
 *       qu'il a été préparé et sauvegardé à l'avance, conformément à l'étape 1 du protocole retenu
 *       dans {@code recherche-client.md}. Un monde de banc différent exige d'ajuster les constantes
 *       de pose.
 * </ul>
 */
public final class Glass {
    /**
     * Images jetées après chaque bascule, avant de commencer à relever.
     *
     * <h2>Deux cents images ne chauffaient rien du tout</h2>
     *
     * <p>Cette valeur a été reprise de {@code Bench.WARMUP}, qui vaut deux cents <em>ticks</em>, soit
     * dix secondes. Deux cents <em>images</em>, à quatre cents images par seconde, font une demi-seconde.
     *
     * <p>La première exécution l'a montré sans ambiguïté. Le mod n'ayant aucun crochet de rendu, les
     * deux phases auraient dû être indiscernables ; le verdict a rendu <b>« perte ×0,87 »</b>, avec un
     * centile le plus lent à <b>24,71 ms contre 4,17</b>. Ce ne sont pas des images lentes, ce sont des
     * chunks qui se construisent encore, des textures qui montent sur la carte graphique, un
     * compilateur qui n'a pas fini.
     *
     * <p>La phase suivante trouvait tout ce travail déjà fait. Le protocole avantageait donc
     * systématiquement la seconde moitié — <b>exactement le défaut que {@code Bench} avait eu</b>, pour
     * la même raison, et qu'on avait cru corriger en portant sa chauffe à deux cents ticks.
     *
     * <p>On chauffe désormais sur une <b>durée</b> et non sur un compte d'images : trente secondes,
     * quel que soit le nombre d'images qu'elles contiennent. C'est la seule formulation qui ne dépende
     * pas de la puissance de la machine — sur un ordinateur deux fois plus rapide, deux mille images ne
     * chaufferaient pas davantage, mais trente secondes restent trente secondes.
     */
    private static final long WARMUP_NANOS = 30_000_000_000L;

    /**
     * Images visées par phase de mesure.
     *
     * <p>Trois mille six cents : même à un régime dégradé de quarante images par seconde — une
     * charge lourde, pas un cas nominal — cela représente encore quatre-vingt-dix secondes, l'ordre
     * de grandeur retenu par le rapport de recherche pour une fenêtre de mesure. À un régime plus
     * confortable, la phase se termine simplement plus vite.
     */
    private static final int MEASURE_TARGET_FRAMES = 16384;

    /**
     * Plafond de temps réel par phase de mesure, en nanosecondes.
     *
     * <h2>Le même problème que {@code Bench}, côté image</h2>
     *
     * <p>Si la charge affiche un régime catastrophique, viser trois mille six cents images pourrait
     * ne jamais aboutir. Une phase s'arrête donc aussi à l'échéance — soixante secondes — sitôt un
     * minimum d'échantillons atteint. En dessous de ce minimum, on préfère déborder : voir
     * {@link #MEASURE_LEAST_FRAMES}.
     */
    private static final long MEASURE_BUDGET_NANOS = 60_000_000_000L;

    /**
     * Relevés minimaux avant d'accepter qu'une échéance de temps réel tranche la phase.
     *
     * <p>Plus haut que l'équivalent de {@code Bench} (quinze) : le centile à un pour cent dégénère en
     * « la pire image toute seule » avec moins d'une centaine d'échantillons. Cent est le plancher en
     * dessous duquel ce second chiffre ne veut plus rien dire.
     */
    private static final int MEASURE_LEAST_FRAMES = 100;

    /**
     * Plafond d'images posé pendant la mesure.
     *
     * <p>{@code Minecraft.java} ligne 1403 n'applique son limiteur que {@code if (framerateLimit <
     * 260)}. Porter le réglage à cette valeur revient donc à retirer le plafond, sans avoir à
     * toucher au limiteur lui-même.
     */
    private static final int UNCAPPED = 260;

    /**
     * Débit au-delà duquel on soupçonne une butée plutôt qu'une mesure.
     *
     * <p>Un relevé qui frôle une fréquence d'écran courante — 60, 75, 120, 144, 165, 240 — ne dit
     * plus rien du mod. On refuse donc au lieu de publier, et le seuil est volontairement bas :
     * mieux vaut refuser une mesure honnête que publier une butée.
     */
    private static final double SUSPECT_RATE = 0.95d;

    /**
     * Part des intervalles collés à la médiane au-delà de laquelle on parle de cadence imposée.
     *
     * <p>Un affichage synchronisé rend des intervalles quasi identiques ; un affichage libre, non.
     * La moitié de l'échantillon groupée à cinq pour cent près est déjà une régularité que le rendu
     * d'un monde vivant ne produit pas de lui-même.
     */
    private static final double STEADY_SHARE = 0.50d;

    /**
     * Coordonnées et angles fixes de la pose de caméra.
     *
     * <h2>Pourquoi ces valeurs précisément</h2>
     *
     * <p>Toutes les charges de {@link Scene} (l'anneau, l'enclos, le sol jonché) sont bâties autour
     * de l'origine du monde — c'est la convention déjà en place, pas une invention de cette classe.
     * On se poste donc à quarante blocs en {@code Z}, assez haut ({@code Y = 150}, bien au-dessus de
     * la plupart des reliefs générés) pour ne jamais se retrouver à l'intérieur d'un bloc — ce qui
     * biaiserait la mesure en dissimulant la scène derrière de la géométrie opaque — et on regarde
     * vers l'origine ({@code yRot = 180}, qui fait face au nord depuis un {@code Z} positif ;
     * {@code xRot = 30}, une inclinaison vers le bas suffisante pour cadrer le sol).
     *
     * <p>Ce ne sont que des valeurs par défaut raisonnables : aucun monde de banc réel n'a servi à
     * les vérifier. Quiconque prépare le monde sauvegardé de l'étape 1 du protocole doit les ajuster
     * à son relief.
     */
    private static final double POSE_X = 0.5d;

    private static final double POSE_Z = 40.0d;

    private static final float POSE_YAW = 180.0f;

    /**
     * Hauteur des yeux au-dessus du sol de la scène.
     *
     * <h2>La caméra regardait soixante blocs au-dessus du troupeau</h2>
     *
     * <p>Cette pose valait auparavant {@code Y = 150} et {@code pitch = 30°}, avec en commentaire
     * « une inclinaison vers le bas suffisante pour cadrer le sol ». Le calcul dit le contraire :
     * depuis cent cinquante blocs d'altitude, à quarante blocs de distance horizontale, une
     * inclinaison de trente degrés vise {@code 150 − 40 × tan(30°) ≈ 127}. Le sol du banc est vers
     * soixante-quatre. Il aurait fallu <b>soixante-cinq</b> degrés.
     *
     * <p>Le banc posait donc mille vaches devant une caméra qui regardait le ciel. Il ne mesurait
     * pas un troupeau : il mesurait du vide, avec un troupeau chargé en mémoire juste en dessous du
     * champ de vision. C'est le deuxième défaut de ce fichier à avoir la même forme que le premier —
     * mesurer autre chose que ce qu'on croit mesurer — et le troisième du projet.
     *
     * <p>On se place désormais à hauteur d'yeux au-dessus du sol <b>réel</b>, celui que
     * {@link Scene#groundLevel} demande au générateur de terrain, et on regarde à l'horizontale. La
     * scène est bâtie autour de l'origine, à cette même altitude : elle est donc dans l'axe, quel
     * que soit le relief de la graine.
     */
    private static final double EYE_HEIGHT = 2.0d;

    /** Hauteur de pose, calculée à la construction de la scène. Voir {@link #EYE_HEIGHT}. */
    private static volatile double poseY = 64.0d + EYE_HEIGHT;

    private enum Phase { IDLE, WAITING, WARM_ON, MEASURE_ON, WARM_OFF, MEASURE_OFF, DONE, REFUSED }

    private static Phase phase = Phase.IDLE;
    private static int countdown;
    private static long phaseOpened;
    private static int filled;
    /** Les modules actifs, relevés PENDANT la phase active — voir le commentaire du verdict. */
    private static String modulesDuringOn = "";

    private static int keptOn;
    private static int keptOff;

    /**
     * Intervalle réel entre deux images — la mesure sur laquelle porte le verdict.
     *
     * <h2>Le banc mesurait un tiers du problème, et se contredisait tout seul</h2>
     *
     * <p>Une exécution a rendu ceci : médiane <b>11,82 ms</b> avec le mod contre <b>8,77</b> sans —
     * donc une perte — mais <b>2019 images</b> avec contre <b>1797</b> sans, sur la même fenêtre de
     * soixante secondes — donc un gain. Les deux chiffres du même relevé disaient l'inverse l'un de
     * l'autre, et le verdict publié en a choisi un sans voir l'autre.
     *
     * <p>Le calcul tranche : 2019 images en 60 s font <b>29,7 ms</b> par image, pas 11,82. Le second
     * chiffre ne pouvait donc pas être le temps d'une image. Et de fait, il ne l'est pas.
     * {@code Minecraft.getFrameTimeNs()} est calculé {@code Util.getNanos() - renderStartTimer}
     * autour du seul appel {@code gameRenderer.render(...)} : il mesure le <em>rendu</em>. En solo, le
     * même fil enchaîne aussi le tick du serveur intégré, celui du client, et la présentation à
     * l'écran. Tout cela vit entre deux images et n'entre dans aucun de ces deux points.
     *
     * <p>Les deux mesures étaient donc justes toutes les deux, et portaient sur deux choses
     * différentes : le mod <b>gagne</b> environ sept millisecondes sur ce qui n'est pas du rendu —
     * c'est son métier, il allège le tick serveur — et en <b>perd</b> trois sur le rendu lui-même,
     * parce qu'en ralentissant le troupeau il le garde groupé dans le champ de la caméra plus
     * longtemps. Le solde est positif, et c'est le compte d'images qui le disait.
     *
     * <p>On relève désormais l'écart entre deux passages consécutifs de ce point d'accroche. Il
     * contient tout ce qui sépare deux images affichées, il est homogène au compte d'images, et il
     * est ce qu'un joueur ressent. {@link #RENDER_ON} garde l'ancien signal à côté, non plus comme
     * verdict mais comme diagnostic : savoir <em>où</em> va le temps reste utile.
     */
    private static final long[] SAMPLES_ON = new long[MEASURE_TARGET_FRAMES];

    private static final long[] SAMPLES_OFF = new long[MEASURE_TARGET_FRAMES];

    /** Coût du rendu seul, conservé comme diagnostic — voir {@link #SAMPLES_ON}. */
    private static final long[] RENDER_ON = new long[MEASURE_TARGET_FRAMES];

    private static final long[] RENDER_OFF = new long[MEASURE_TARGET_FRAMES];

    /**
     * Horodatage de l'image précédente, ou zéro au début d'une phase.
     *
     * <p>Remis à zéro à chaque ouverture de phase : le premier intervalle d'une phase enjamberait la
     * bascule et la reconstruction de la scène, et vaudrait des centaines de millisecondes qui
     * n'appartiennent à aucune des deux mesures.
     */
    private static long lastFrameAt;

    /** Durée réelle de chaque phase de mesure — sert au contrôle de cohérence du verdict. */
    private static long spanOn;

    private static long spanOff;

    /**
     * Le tirage de l'échantillonnage par réservoir — voir {@link #reserve}.
     *
     * <p>Graine fixe : deux exécutions du banc doivent retenir les mêmes rangs d'images, sans quoi
     * la médiane bougerait d'un passage à l'autre pour une raison qui n'aurait rien à voir avec le
     * mod mesuré.
     */
    private static final java.util.Random SHUFFLE = new java.util.Random(20260914L);

    private Glass() {}

    /**
     * La mesure est-elle demandée ?
     *
     * <p>Ne touche à aucune classe client — voir l'avertissement de compatibilité en tête de
     * fichier. Appelable sans condition, y compris depuis un contexte serveur dédié.
     */
    public static boolean armed() {
        return "1".equals(System.getenv("LANTERNE_GLASS"));
    }

    /** Le banc tourne-t-il encore ? */
    public static boolean running() {
        return phase != Phase.IDLE && phase != Phase.DONE && phase != Phase.REFUSED;
    }

    /**
     * Arme le protocole : attente du monde, puis chauffe, mesure, verdict.
     *
     * <p>N'appeler qu'une fois, depuis un contexte déjà garanti client. Sans effet si
     * {@link #armed()} est faux ou si le banc tourne déjà.
     */
    public static void begin() {
        if (!armed() || phase != Phase.IDLE) {
            return;
        }
        phase = Phase.WAITING;
        Lanterne.LOG.info("[VITRE] Banc d'images armé — attente du monde et du joueur.");
    }

    /**
     * Le point d'accroche par image.
     *
     * <p>À appeler depuis un gestionnaire d'évènement de rendu client, une fois par image — voir
     * l'avertissement de compatibilité en tête de fichier pour la façon de le relier sans risque.
     */
    public static void frame() {
        if (!running()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }

        if (phase == Phase.WAITING) {
            waitForWorld(minecraft);
            return;
        }

        // À partir d'ici la caméra est posée à chaque image : la fenêtre doit rester visible.
        if (minecraft.gameRenderer.getGameRenderState().windowRenderState.isMinimized) {
            refuse(minecraft, "fenêtre réduite pendant la mesure — l'affichage est sauté, "
                    + "le temps d'image ne veut plus rien dire ici");
            return;
        }
        if (!pose(minecraft)) {
            return;
        }

        switch (phase) {
            case WARM_ON -> {
                if (System.nanoTime() - phaseOpened > WARMUP_NANOS) {
                    beginMeasure(true);
                }
            }
            case MEASURE_ON -> collect(true);
            case WARM_OFF -> {
                if (System.nanoTime() - phaseOpened > WARMUP_NANOS) {
                    beginMeasure(false);
                }
            }
            case MEASURE_OFF -> collect(false);
            default -> { }
        }
    }

    /**
     * Peuple le monde avant de mesurer.
     *
     * <h2>Un banc d.images qui mesurait un monde vide</h2>
     *
     * <p>La première exécution de ce banc a rendu une <b>perte</b> : 2,57 ms par image avec le mod
     * contre 2,21 sans, soit x0,86. Le chiffre était juste et la conclusion qu.on en aurait tirée,
     * fausse.
     *
     * <p>Le monde d.essai ne contient rien. Le mod n.y a donc <em>aucun travail à éviter</em>, et seul
     * son propre coût apparaît — celui de ses recensements, de ses interrupteurs, de ses compteurs.
     * Mesurer un mod d.optimisation sur une scène vide revient à peser l.outil sans peser ce qu.il
     * soulève.
     *
     * <p>C.est le même défaut que celui du banc de dynamite, à l.envers : là-bas les deux phases ne
     * subissaient pas la même charge, ici elles n.en subissent aucune.
     *
     * <p>On pose donc devant la caméra ce que le mod est fait pour traiter : un troupeau dense, tel
     * qu.un joueur en rencontre dans une ferme. Le serveur intégré est atteint depuis le client parce
     * qu.en solo ils partagent le processus — et cette classe ne vit que sur la distribution client,
     * donc la référence est sans danger pour un serveur dédié.
     */
    private static void populate(Minecraft minecraft) {
        var server = minecraft.getSingleplayerServer();
        if (server == null) {
            Lanterne.LOG.warn("[VITRE] pas de serveur intégré — la scène restera vide, et le banc ne "
                    + "mesurera que le coût du mod, jamais son gain.");
            return;
        }
        var level = server.overworld();
        server.execute(() -> {
            Scene.Kind kind = kindWanted();
            int born = Scene.build(level, kind, herdWanted(), 0);
            // La pose dépend du relief de la graine : on ne peut la connaître qu'ici, une fois le
            // monde ouvert. Voir EYE_HEIGHT pour ce que cette ligne corrige.
            poseY = Scene.groundLevel(level) + EYE_HEIGHT;
            Lanterne.LOG.info("[VITRE] scène « {} » posée devant la caméra : {} créature(s), "
                    + "œil à Y={}. Sans elle, le banc pèse l.outil sans peser ce qu.il soulève.",
                    kind, born, String.format(Locale.ROOT, "%.1f", poseY));
        });
    }

    /**
     * La charge à poser devant la caméra.
     *
     * <p>Par défaut la <b>grange</b> et non l'enclos : à ciel ouvert, aucun bloc ne cache rien, et
     * un banc de rendu qui n'occulte rien ne peut mesurer que le coût des modules qui occultent,
     * jamais leur gain. {@code LANTERNE_GLASS_SCENE=enclos} rétablit l'ancienne charge pour
     * comparer les deux.
     */
    private static Scene.Kind kindWanted() {
        String raw = System.getenv("LANTERNE_GLASS_SCENE");
        if (raw == null || raw.isBlank()) {
            return Scene.Kind.BARN;
        }
        return Scene.parse(raw.trim());
    }

    /** Rebâtit la scène à l.identique entre les deux phases. */
    private static void repopulate() {
        populate(Minecraft.getInstance());
    }

    /**
     * Force la reconstruction de toute la géométrie de chunk après une bascule.
     *
     * <h2>Un module que ce banc aurait mesuré à zéro</h2>
     *
     * <p>Le maillage d'un chunk est calculé une fois puis conservé tant que rien n'y change. Un
     * module qui agit sur la <em>manière de mailler</em> — les coffres rendus comme des blocs
     * ordinaires, par exemple — n'a donc aucun effet visible sur une géométrie déjà construite.
     *
     * <p>Sans cet appel, la phase « sans » aurait continué d'afficher le maillage produit par la
     * phase « avec », et le banc aurait conclu « aucun effet mesurable » avec une parfaite
     * assurance. C'est la cinquième fois dans ce projet qu'un état conservé d'une phase à l'autre
     * menace de transformer un gain réel en zéro, ou l'inverse.
     *
     * <p>La chauffe de trente secondes qui suit absorbe le coût de la reconstruction elle-même.
     */
    /**
     * Retire la laisse : ni synchronisation verticale, ni plafond d'images.
     *
     * <h2>Le banc mesurait l'écran, pas le mod</h2>
     *
     * <p>Le premier relevé publié du Voile donnait <b>95,4 images par seconde</b> avec le module,
     * contre 29,9 sans — un gain de ×3,19. Sur une machine dont la synchronisation verticale
     * plafonne à cent vingt, 95,4 n'est pas une mesure : c'est une <b>butée</b>. La phase rapide
     * passait son temps à attendre l'écran, la phase lente non, et l'écart entre les deux mesurait
     * autant la fréquence du moniteur que le travail économisé.
     *
     * <p>Le signe était dans le relevé, et il a été mal lu. Le banc avait bien remarqué que le débit
     * (×3,19) et la médiane (×3,99) ne s'accordaient pas sur l'ampleur, et il l'avait mis sur le
     * compte d'un « gain inégalement réparti entre les images ». La vraie raison est ailleurs :
     * {@code getFrameTimeNs()} est calculé <b>avant</b> le limiteur d'images
     * ({@code Minecraft.java}, ligne 1403 : {@code if (framerateLimit < 260)}), donc la médiane
     * ignorait le plafond pendant que le débit s'y écrasait. Les deux chiffres ne divergeaient pas
     * par hasard : l'un était bridé et l'autre pas.
     *
     * <p>Deux réglages suffisent à les remettre d'accord. Le plafond est comparé à deux cent
     * soixante dans le code du jeu : l'y porter revient à le supprimer. La synchronisation, elle,
     * demande en plus d'être poussée jusqu'à la fenêtre — {@code Minecraft.java} ligne 677 ne lit
     * l'option qu'au démarrage.
     *
     * <p>Ces réglages ne touchent que le monde de développement ({@code run/options.txt}), jamais
     * l'installation du joueur.
     */
    private static void unchain(Minecraft minecraft) {
        minecraft.options.enableVsync().set(false);
        minecraft.options.framerateLimit().set(UNCAPPED);
        minecraft.getWindow().updateVsync(false);
        Lanterne.LOG.info("[VITRE] Laisse retirée : synchronisation verticale coupée, plafond porté "
                + "à {}. Sans cela, le banc mesure la fréquence de l'écran.", UNCAPPED);
    }

    private static void invalidateTerrain() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
        }
    }

    /** Taille du troupeau posé devant la caméra. */
    private static int herdWanted() {
        String raw = System.getenv("LANTERNE_GLASS_HERD");
        if (raw == null || raw.isBlank()) {
            return 1000;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException malformed) {
            return 1000;
        }
    }

    /** Attend que le monde soit chargé et le joueur présent, sans intervention humaine. */
    private static void waitForWorld(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        populate(minecraft);
        Lanterne.LOG.info("[VITRE] Monde chargé, joueur présent — pose de la caméra, début de la "
                + "chauffe (mod actif).");
        unchain(minecraft);
        Settings.setEnabled(true);
        // Le monde vient d'être maillé pendant le chargement, alors que les interrupteurs n'étaient
        // pas encore posés. Même raisonnement qu'à la bascule : voir invalidateTerrain.
        invalidateTerrain();
        // La chauffe se mesure en temps, pas en images : voir WARMUP_NANOS.
        phase = Phase.WARM_ON;
        phaseOpened = System.nanoTime();
    }

    /**
     * Repose la caméra sur les coordonnées fixes de l'épreuve.
     *
     * @return faux si le joueur a disparu en cours de route — le banc se refuse alors lui-même.
     */
    private static boolean pose(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            refuse(minecraft, "le joueur a disparu en cours de mesure");
            return false;
        }
        // Voir le Javadoc de classe : reposer à CHAQUE image, et non une seule fois, élimine toute
        // dérive résiduelle en plus de la chute — une garantie plus stricte que « en vol ».
        // Regard horizontal : la scène est bâtie au niveau du sol, à la même altitude que l'œil.
        player.snapTo(POSE_X, poseY, POSE_Z, POSE_YAW, 0.0f);
        return true;
    }

    private static void beginMeasure(boolean on) {
        filled = 0;
        lastFrameAt = 0L;
        phaseOpened = System.nanoTime();
        phase = on ? Phase.MEASURE_ON : Phase.MEASURE_OFF;
        Lanterne.LOG.info("[VITRE] Chauffe terminée ({} s) — mesure {} en cours.",
                WARMUP_NANOS / 1_000_000_000L, on ? "AVEC Lanterne" : "SANS Lanterne");
    }

    /**
     * Relève une image.
     *
     * <h2>Un décalage d'une image, vérifié inoffensif</h2>
     *
     * <p>Le point d'accroche recommandé (voir le résumé de livraison) se déclenche après
     * {@code this.gameRenderer.render(...)} mais <b>avant</b> {@code this.frameTimeNs =
     * Util.getNanos() - renderStartTimer;}, qui n'a lieu que quelques lignes plus loin dans
     * {@code Minecraft.java}. Le nombre lu à un appel donné n'est donc pas celui de l'image qui vient
     * de se terminer, mais celui de l'image précédente — le champ n'a pas encore été réécrit pour
     * celle-ci. Sur des centaines d'appels consécutifs, chaque image réelle est tout de même relevée
     * exactement une fois, seulement décalée d'un rang ; le tout premier relevé de la phase, lui, est
     * sans objet et se trouve absorbé par la chauffe qui le précède. Aucun hameçon de ce mod ne se
     * déclenche après ce calcul dans le fichier vanilla lu : c'est la meilleure précision disponible
     * sans mixin.
     */
    private static void collect(boolean on) {
        long now = System.nanoTime();
        // Le premier passage d'une phase n'a pas d'image précédente à laquelle se comparer : il
        // amorce l'horloge et ne compte pas. Voir le champ lastFrameAt.
        if (lastFrameAt == 0L) {
            lastFrameAt = now;
            return;
        }
        long gap = now - lastFrameAt;
        lastFrameAt = now;

        long[] target = on ? SAMPLES_ON : SAMPLES_OFF;
        long[] render = on ? RENDER_ON : RENDER_OFF;
        // Compter et stocker sont deux choses différentes, et les confondre a coûté un relevé.
        // Le débit a besoin du COMPTE sur toute la fenêtre ; la médiane n'a besoin que d'un
        // échantillon. Arrêter la phase quand le tableau est plein revenait à écourter la fenêtre
        // de la phase rapide — 18,7 s contre 60 — et donc à rétablir l'inégalité de fenêtres que la
        // correction précédente venait justement de supprimer.
        int slot = reserve(target.length);
        if (slot >= 0) {
            target[slot] = gap;
            render[slot] = Minecraft.getInstance().getFrameTimeNs();
        }
        filled++;
        if (overdue()) {
            int kept = Math.min(filled, MEASURE_TARGET_FRAMES);
            if (on) {
                keptOn = kept;
                spanOn = now - phaseOpened;
                modulesDuringOn = Settings.describe();
                Settings.setEnabled(false);
                invalidateTerrain();
                // La scène est rebâtie entre les phases, et c'est indispensable ici : mille vaches
                // lâchées ensemble se dispersent en une minute. La première phase voyait donc un
                // troupeau serré devant la caméra, la seconde un troupeau étalé — c'est-à-dire deux
                // quantités de rendu différentes, attribuées au mod.
                //
                // Pire : le mod RALENTIT les bêtes, donc il les garde groupées plus longtemps. Il se
                // faisait facturer le rendu des créatures que son propre ralentissement empêchait de
                // partir. C'est le quatrième banc de ce projet à souffrir du même défaut.
                repopulate();
                // La chauffe se mesure en temps : voir WARMUP_NANOS.
                phaseOpened = System.nanoTime();
                phase = Phase.WARM_OFF;
                Lanterne.LOG.info("[VITRE] Phase AVEC terminée ({} image(s) retenue(s)). Bascule — "
                        + "mod désactivé, nouvelle chauffe.", kept);
            } else {
                keptOff = kept;
                spanOff = now - phaseOpened;
                conclude();
            }
        }
    }

    /**
     * La phase est-elle finie ?
     *
     * <h2>Une fenêtre de temps, et non un compte d'images</h2>
     *
     * <p>Ce banc s'arrêtait au premier des deux critères atteints : trois mille six cents images
     * <b>ou</b> soixante secondes. Le premier relevé du voile en a montré la conséquence — la phase
     * avec le module a duré <b>31,4 s</b> et celle sans, <b>60 s</b>. Le verdict portait donc sur
     * deux fenêtres différentes.
     *
     * <p>Un débit est un taux, donc en principe insensible à la durée. Mais deux fenêtres inégales
     * ne voient pas le même monde : les créatures se déplacent, le ramasse-miettes passe, la machine
     * chauffe. Comparer une demi-minute à une minute entière, c'est réintroduire par la porte le
     * biais que la chauffe avait chassé par la fenêtre — et ce projet a déjà retiré quatre bancs
     * pour cette famille de défaut.
     *
     * <p>La fenêtre est désormais la <b>même durée</b> des deux côtés. Le compte d'images est ce
     * qu'on mesure ; il n'a plus le droit d'être aussi ce qui arrête la mesure. Le plafond de seize
     * mille relevés ne subsiste que comme garde-fou de mémoire : à deux cent soixante-dix images par
     * seconde soutenues pendant une minute, il ne serait pas atteint.
     */
    private static boolean overdue() {
        return filled >= MEASURE_LEAST_FRAMES
                && System.nanoTime() - phaseOpened > MEASURE_BUDGET_NANOS;
    }

    /**
     * Où ranger le relevé de cette image, ou {@code -1} pour ne pas le ranger.
     *
     * <h2>L'échantillonnage par réservoir, et pourquoi il faut celui-là</h2>
     *
     * <p>Tant que le tableau n'est pas plein, on range à la suite. Une fois plein, garder les
     * premiers relevés donnerait une médiane du début de la phase, et écraser en rond donnerait une
     * médiane de la fin : deux façons de décrire une portion de fenêtre en croyant décrire la
     * fenêtre entière.
     *
     * <p>L'algorithme de Vitter donne à chaque image de la phase la <b>même probabilité</b> de
     * figurer dans l'échantillon final, quelle que soit sa position. La médiane porte alors sur
     * toute la fenêtre, avec un tableau de taille fixe — ce qui compte quand une phase rapide peut
     * rendre cinquante mille images là où la lente en rend deux mille.
     */
    private static int reserve(int capacity) {
        if (filled < capacity) {
            return filled;
        }
        int candidate = SHUFFLE.nextInt(filled + 1);
        return candidate < capacity ? candidate : -1;
    }

    /**
     * Refuse de mesurer et arrête le client — dans l'esprit de {@link Preflight} : un chiffre
     * silencieux vaut mieux qu'un chiffre qui ment. Un banc automatisé qui ne peut pas conclure ne
     * doit pas non plus rester suspendu indéfiniment ; il s'arrête, lui aussi, proprement.
     */
    private static void refuse(Minecraft minecraft, String reason) {
        Lanterne.LOG.error("[VITRE] MESURE REFUSÉE : {}", reason);
        Lanterne.LOG.error("[VITRE] Un chiffre faux ne se corrige pas — il se propage. "
                + "Corriger les conditions, puis relancer.");
        phase = Phase.REFUSED;
        Settings.setEnabled(true);
        minecraft.stop();
    }

    private static void conclude() {
        double medianOn = median(SAMPLES_ON, keptOn);
        double medianOff = median(SAMPLES_OFF, keptOff);
        double slowOn = slowestPercent(SAMPLES_ON, keptOn, 0.01d);
        double slowOff = slowestPercent(SAMPLES_OFF, keptOff, 0.01d);
        double renderOn = median(RENDER_ON, keptOn);
        double renderOff = median(RENDER_OFF, keptOff);

        Lanterne.LOG.info("[VITRE] ── Résultat (intervalle réel entre deux images) ──");
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[VITRE] Avec Lanterne : médiane %.2f ms · 1%% le plus lent %.2f ms "
                        + "(%d image(s) en %.1f s)",
                medianOn / 1e6, slowOn / 1e6, keptOn, spanOn / 1e9d));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[VITRE] Sans Lanterne : médiane %.2f ms · 1%% le plus lent %.2f ms "
                        + "(%d image(s) en %.1f s)",
                medianOff / 1e6, slowOff / 1e6, keptOff, spanOff / 1e9d));

        double rateOn = keptOn / (spanOn / 1e9d);
        double rateOff = keptOff / (spanOff / 1e9d);
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[VITRE] Débit : %.1f image(s)/s avec, %.1f sans.", rateOn, rateOff));
        // La moyenne à côté de la médiane : leur écart mesure la queue de distribution, c'est-à-dire
        // les à-coups. Une médiane qui s'améliore pendant que la moyenne se dégrade décrit un module
        // qui accélère le cas courant et paie ailleurs — un renseignement que ni l'une ni l'autre ne
        // donne seule.
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[VITRE] Moyenne : %.2f ms avec (médiane %.2f), %.2f ms sans (médiane %.2f).",
                spanOn / 1e6d / Math.max(1, keptOn), medianOn / 1e6,
                spanOff / 1e6d / Math.max(1, keptOff), medianOff / 1e6));

        if (!unchained(rateOn, rateOff)) {
            Settings.setEnabled(true);
            phase = Phase.REFUSED;
            Minecraft.getInstance().stop();
            return;
        }

        if (!coherent(rateOn, rateOff, medianOn, medianOff)) {
            Settings.setEnabled(true);
            phase = Phase.REFUSED;
            Minecraft.getInstance().stop();
            return;
        }

        if (keptOn < MEASURE_TARGET_FRAMES || keptOff < MEASURE_TARGET_FRAMES) {
            Lanterne.LOG.info("[VITRE] Une phase au moins s'est arrêtée à l'échéance de {} s : la "
                    + "cadence d'image était trop basse pour atteindre {} relevés. Le chiffre reste "
                    + "valable, il porte sur moins d'images.",
                    MEASURE_BUDGET_NANOS / 1_000_000_000L, MEASURE_TARGET_FRAMES);
        }

        // Relevé pendant la phase active, jamais ici : le verdict s'écrit après la bascule, et
        // « Settings.describe() » y décrirait le mod éteint. C'est le même défaut que Bench avait sur
        // sa répartition de cadences — il annonçait « tout éteint » pour la phase où tout était allumé.
        Lanterne.LOG.info("[VITRE] Modules actifs pendant la phase AVEC : {}", modulesDuringOn);

        // Le second signal, en diagnostic et jamais en verdict : où va le temps gagné ou perdu.
        // Voir le commentaire de SAMPLES_ON pour ce que cette distinction a coûté à comprendre.
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[VITRE] Détail — rendu seul : %.2f ms avec, %.2f ms sans. Hors rendu (tick serveur, "
                        + "tick client, présentation) : %.2f ms avec, %.2f ms sans.",
                renderOn / 1e6, renderOff / 1e6,
                (medianOn - renderOn) / 1e6, (medianOff - renderOff) / 1e6));

        // Le verdict porte sur le DÉBIT, pas sur la médiane. Le débit est exact — un compte divisé
        // par une durée — et c'est lui que le joueur perçoit. La médiane, elle, décrit la
        // régularité : deux relevés de même débit dont l'un a une médiane bien plus haute ont la
        // même fluidité moyenne et pas du tout la même sensation.
        if (rateOn > 0d && rateOff > 0d) {
            double ratio = rateOn / rateOff;
            if (ratio > 1.05d) {
                Lanterne.LOG.info(String.format(Locale.ROOT,
                        "[VITRE] Gain : ×%.2f sur le débit d'images.", ratio));
            } else if (ratio < 0.95d) {
                Lanterne.LOG.info(String.format(Locale.ROOT,
                        "[VITRE] PERTE : ×%.2f — le mod coûte plus qu'il ne rapporte ici.", ratio));
            } else {
                Lanterne.LOG.info("[VITRE] Aucun effet mesurable (écart sous le bruit de fond).");
            }
        }

        Settings.setEnabled(true);
        Lanterne.LOG.info("[VITRE] Mesure terminée — arrêt du client.");
        phase = Phase.DONE;
        Minecraft.getInstance().stop();
    }

    /**
     * Les deux signaux du relevé pointent-ils dans le même sens ?
     *
     * <h2>Le contrôle qu'il fallait, après un premier qui se trompait de critère</h2>
     *
     * <p>Le relevé qui a motivé ce garde-fou donnait une médiane en hausse — donc une perte — et un
     * compte d'images en hausse lui aussi — donc un gain. Un mod ne peut pas à la fois rendre chaque
     * image plus lente et en afficher davantage dans la même minute : l'un des deux chiffres ne
     * mesurait pas ce qu'on croyait, et c'était la médiane, calculée sur le seul temps de rendu.
     *
     * <h2>Le premier critère écrit ici était faux, et il faut le dire</h2>
     *
     * <p>Il vérifiait {@code images × médiane ≈ durée}. C'est une identité pour une distribution
     * symétrique et pour elle seule. Le premier relevé de la grange l'a mis en défaut sans qu'il y
     * ait rien d'incohérent : 2677 images, médiane 32,46 ms, fenêtre de 60 s — soit « 145 %
     * expliqué », et un refus. Le calcul juste est {@code images × moyenne = durée}, une identité
     * exacte pour des intervalles consécutifs — donc un contrôle qui ne peut rien détecter, puisque
     * toujours vrai par construction.
     *
     * <p>La médiane valait ici bien plus que la moyenne, ce qui signale une distribution à traîne
     * gauche : beaucoup d'images très rapides et un socle d'images lentes. C'est un renseignement
     * sur la régularité, pas une incohérence.
     *
     * <p>Le vrai critère est celui-ci : <b>le débit et la médiane doivent aller dans le même
     * sens</b>. Leurs amplitudes peuvent différer — le débit intègre tout, la médiane décrit le cas
     * typique. Leurs signes, non.
     */
    /**
     * Le relevé a-t-il buté sur un plafond au lieu de mesurer ?
     *
     * <p>{@link #unchain} coupe la synchronisation et retire le plafond d'images, mais rien ne
     * garantit que le pilote graphique obéisse : certaines configurations imposent la
     * synchronisation au niveau du pilote, et le jeu n'a alors pas voix au chapitre. Ce contrôle
     * s'assure donc du résultat plutôt que de l'intention.
     *
     * <p>Le critère est le voisinage d'une fréquence d'écran courante. Un débit de 119 images par
     * seconde sur un écran à 120 ne mesure pas un mod ; il mesure le moniteur, et l'écart avec la
     * phase lente sous-estime le gain d'autant.
     */
    private static boolean unchained(double rateOn, double rateOff) {
        return free(rateOn, SAMPLES_ON, keptOn, "AVEC")
                && free(rateOff, SAMPLES_OFF, keptOff, "SANS");
    }

    /**
     * Une phase est-elle libre, ou cadencée par l'écran ?
     *
     * <h2>Pourquoi le voisinage d'une fréquence ne suffit pas</h2>
     *
     * <p>Le premier critère écrit ici refusait tout débit proche d'une fréquence d'écran courante.
     * Il a aussitôt refusé un relevé parfaitement valable : 72,5 images par seconde, à trois pour
     * cent des 75 Hz — une coïncidence, pas une butée. Un banc qui refuse les mesures honnêtes ne
     * vaut pas mieux qu'un banc qui publie les fausses ; il rend seulement l'erreur invisible.
     *
     * <p>Ce qui distingue vraiment une cadence imposée, c'est la <b>régularité</b>. Un affichage
     * synchronisé rend des intervalles quasi identiques — l'écran dicte le rythme, et le jeu attend.
     * Un affichage libre produit une distribution étalée, parce que chaque image coûte ce qu'elle
     * coûte. On exige donc les deux signes à la fois : un débit au voisinage d'une fréquence
     * <b>et</b> une concentration anormale des intervalles autour de la médiane.
     *
     * <p>Le relevé qui a motivé ce garde-fou avait une médiane de 5,03 ms et un centile le plus lent
     * à 48,22 : tout sauf régulier. Il passe désormais, et c'est justice.
     */
    private static boolean free(double rate, long[] samples, int count, String label) {
        boolean nearRefresh = false;
        int matched = 0;
        for (int refresh : new int[] {60, 75, 90, 100, 120, 144, 165, 240}) {
            if (rate > refresh * SUSPECT_RATE && rate < refresh * 1.02d) {
                nearRefresh = true;
                matched = refresh;
                break;
            }
        }
        if (!nearRefresh) {
            return true;
        }

        double median = median(samples, count);
        if (median <= 0d) {
            return true;
        }
        int tight = 0;
        for (int i = 0; i < count; i++) {
            if (Math.abs(samples[i] - median) < median * 0.05d) {
                tight++;
            }
        }
        double concentration = tight / (double) count;
        if (concentration < STEADY_SHARE) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[VITRE] Phase %s : %.1f image(s)/s tombe près de %d Hz, mais les intervalles "
                            + "sont étalés (%.0f %% groupés). Coïncidence, pas une butée.",
                    label, rate, matched, concentration * 100d));
            return true;
        }
        Lanterne.LOG.error(String.format(Locale.ROOT,
                "[VITRE] MESURE REFUSÉE : phase %s cadencée par l'écran — %.1f image(s)/s à %d Hz, "
                        + "et %.0f %% des intervalles collés à la médiane. Le banc a mesuré le "
                        + "moniteur. Couper la synchronisation au niveau du PILOTE et relancer.",
                label, rate, matched, concentration * 100d));
        return false;
    }

    private static boolean coherent(double rateOn, double rateOff, double medianOn,
            double medianOff) {
        if (rateOn <= 0d || rateOff <= 0d || medianOn <= 0d || medianOff <= 0d) {
            return true;
        }
        double byRate = rateOn / rateOff;
        double byMedian = medianOff / medianOn;
        boolean rateSaysGain = byRate > 1.05d;
        boolean rateSaysLoss = byRate < 0.95d;
        boolean medianSaysGain = byMedian > 1.05d;
        boolean medianSaysLoss = byMedian < 0.95d;

        if ((rateSaysGain && medianSaysLoss) || (rateSaysLoss && medianSaysGain)) {
            Lanterne.LOG.error(String.format(Locale.ROOT,
                    "[VITRE] MESURE REFUSÉE : les deux signaux se contredisent. Le débit dit ×%.2f, "
                            + "la médiane dit ×%.2f. Un mod ne peut pas rendre chaque image plus "
                            + "lente ET en afficher davantage. Aucun chiffre n'est publié.",
                    byRate, byMedian));
            return false;
        }
        if (Math.abs(byRate - byMedian) > 0.25d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[VITRE] Les deux signaux s'accordent sur le sens mais pas sur l'ampleur "
                            + "(débit ×%.2f, médiane ×%.2f) : le gain est inégalement réparti entre "
                            + "les images. Le verdict porte sur le débit.",
                    byRate, byMedian));
        }
        return true;
    }

    /**
     * La médiane d'un échantillon de temps d'image.
     *
     * <p>Même raisonnement que {@code Bench.median} : ne trier que ce qui a été rempli, puisqu'une
     * phase arrêtée à l'échéance laisse des zéros en fin de tableau qui fausseraient tout.
     */
    private static double median(long[] values, int count) {
        if (count <= 0) {
            return 0d;
        }
        long[] copy = Arrays.copyOf(values, count);
        Arrays.sort(copy);
        int middle = count / 2;
        return count % 2 == 0 ? (copy[middle - 1] + copy[middle]) / 2d : copy[middle];
    }

    /**
     * La moyenne des images les plus lentes de l'échantillon, sur la fraction demandée.
     *
     * <p>C'est le « 1 % low » habituel des bancs d'images, exprimé ici en temps plutôt qu'en images
     * par seconde : la moyenne des durées les plus longues, celles qui font l'à-coup que la médiane
     * ne voit jamais.
     */
    private static double slowestPercent(long[] values, int count, double fraction) {
        if (count <= 0) {
            return 0d;
        }
        long[] copy = Arrays.copyOf(values, count);
        Arrays.sort(copy);
        int take = Math.max(1, (int) Math.ceil(count * fraction));
        long sum = 0L;
        for (int i = count - take; i < count; i++) {
            sum += copy[i];
        }
        return sum / (double) take;
    }
}
