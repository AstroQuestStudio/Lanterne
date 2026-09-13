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
     * <p>Deux cents : la fourchette haute que le rapport de recherche indique pour laisser le
     * premier maillage de chunks se terminer et le compilateur à la volée se fixer — le même ordre
     * de grandeur que {@code Bench.WARMUP}, choisi pour la même raison, appliqué ici à des images et
     * non à des ticks.
     */
    private static final int WARMUP_FRAMES = 200;

    /**
     * Images visées par phase de mesure.
     *
     * <p>Trois mille six cents : même à un régime dégradé de quarante images par seconde — une
     * charge lourde, pas un cas nominal — cela représente encore quatre-vingt-dix secondes, l'ordre
     * de grandeur retenu par le rapport de recherche pour une fenêtre de mesure. À un régime plus
     * confortable, la phase se termine simplement plus vite.
     */
    private static final int MEASURE_TARGET_FRAMES = 3600;

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
    private static final double POSE_Y = 150.0d;
    private static final double POSE_Z = 40.0d;
    private static final float POSE_YAW = 180.0f;
    private static final float POSE_PITCH = 30.0f;

    private enum Phase { IDLE, WAITING, WARM_ON, MEASURE_ON, WARM_OFF, MEASURE_OFF, DONE, REFUSED }

    private static Phase phase = Phase.IDLE;
    private static int countdown;
    private static long phaseOpened;
    private static int filled;
    private static int keptOn;
    private static int keptOff;

    private static final long[] SAMPLES_ON = new long[MEASURE_TARGET_FRAMES];
    private static final long[] SAMPLES_OFF = new long[MEASURE_TARGET_FRAMES];

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
                if (--countdown <= 0) {
                    beginMeasure(true);
                }
            }
            case MEASURE_ON -> collect(true);
            case WARM_OFF -> {
                if (--countdown <= 0) {
                    beginMeasure(false);
                }
            }
            case MEASURE_OFF -> collect(false);
            default -> { }
        }
    }

    /** Attend que le monde soit chargé et le joueur présent, sans intervention humaine. */
    private static void waitForWorld(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Lanterne.LOG.info("[VITRE] Monde chargé, joueur présent — pose de la caméra, début de la "
                + "chauffe (mod actif).");
        Settings.setEnabled(true);
        countdown = WARMUP_FRAMES;
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
        player.snapTo(POSE_X, POSE_Y, POSE_Z, POSE_YAW, POSE_PITCH);
        return true;
    }

    private static void beginMeasure(boolean on) {
        filled = 0;
        phaseOpened = System.nanoTime();
        phase = on ? Phase.MEASURE_ON : Phase.MEASURE_OFF;
        Lanterne.LOG.info("[VITRE] Chauffe terminée ({} images jetées) — mesure {} en cours.",
                WARMUP_FRAMES, on ? "AVEC Lanterne" : "SANS Lanterne");
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
        long[] target = on ? SAMPLES_ON : SAMPLES_OFF;
        if (filled < target.length) {
            target[filled] = Minecraft.getInstance().getFrameTimeNs();
        }
        filled++;
        if (filled >= MEASURE_TARGET_FRAMES || overdue()) {
            int kept = Math.min(filled, MEASURE_TARGET_FRAMES);
            if (on) {
                keptOn = kept;
                Settings.setEnabled(false);
                countdown = WARMUP_FRAMES;
                phaseOpened = System.nanoTime();
                phase = Phase.WARM_OFF;
                Lanterne.LOG.info("[VITRE] Phase AVEC terminée ({} image(s) retenue(s)). Bascule — "
                        + "mod désactivé, nouvelle chauffe.", kept);
            } else {
                keptOff = kept;
                conclude();
            }
        }
    }

    private static boolean overdue() {
        return filled >= MEASURE_LEAST_FRAMES && System.nanoTime() - phaseOpened > MEASURE_BUDGET_NANOS;
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

        Lanterne.LOG.info("[VITRE] ── Résultat (temps par image) ──");
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[VITRE] Avec Lanterne : médiane %.2f ms · 1%% le plus lent %.2f ms (%d image(s))",
                medianOn / 1e6, slowOn / 1e6, keptOn));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[VITRE] Sans Lanterne : médiane %.2f ms · 1%% le plus lent %.2f ms (%d image(s))",
                medianOff / 1e6, slowOff / 1e6, keptOff));

        if (keptOn < MEASURE_TARGET_FRAMES || keptOff < MEASURE_TARGET_FRAMES) {
            Lanterne.LOG.info("[VITRE] Une phase au moins s'est arrêtée à l'échéance de {} s : la "
                    + "cadence d'image était trop basse pour atteindre {} relevés. Le chiffre reste "
                    + "valable, il porte sur moins d'images.",
                    MEASURE_BUDGET_NANOS / 1_000_000_000L, MEASURE_TARGET_FRAMES);
        }

        Lanterne.LOG.info("[VITRE] Modules actifs pendant la phase AVEC : {}", Settings.describe());

        if (medianOn > 0d && medianOff > 0d) {
            double ratio = medianOff / medianOn;
            if (ratio > 1.05d) {
                Lanterne.LOG.info(String.format(Locale.ROOT,
                        "[VITRE] Gain : ×%.2f sur la médiane de temps d'image.", ratio));
            } else if (ratio < 0.95d) {
                Lanterne.LOG.info(String.format(Locale.ROOT,
                        "[VITRE] PERTE : ×%.2f — le mod coûte plus qu'il ne rapporte au rendu ici.",
                        ratio));
            } else {
                Lanterne.LOG.info("[VITRE] Aucun effet mesurable sur le rendu (écart sous le bruit "
                        + "de fond) — attendu, ce mod n'agit pas directement sur le moteur de rendu.");
            }
        }

        Settings.setEnabled(true);
        Lanterne.LOG.info("[VITRE] Mesure terminée — arrêt du client.");
        phase = Phase.DONE;
        Minecraft.getInstance().stop();
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
