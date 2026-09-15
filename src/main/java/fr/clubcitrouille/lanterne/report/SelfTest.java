package fr.clubcitrouille.lanterne.report;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.lab.Conformance;
import fr.clubcitrouille.lanterne.lab.Kitchen;
import fr.clubcitrouille.lanterne.lab.Understudy;

/**
 * L'auto-test : le mod se mesure lui-même, sans personne aux commandes.
 *
 * <h2>Pourquoi il existe</h2>
 *
 * <p>Un banc qui demande un joueur connecté ne peut tourner ni en intégration continue, ni sur un
 * serveur nu, ni pendant qu'on développe. Il ne serait lancé qu'à la main, c'est-à-dire rarement,
 * c'est-à-dire jamais — et les chiffres publiés vieilliraient en silence jusqu'à devenir faux.
 *
 * <p>La conséquence qui compte : <b>aucun chiffre publié sur ce mod n'aura été saisi à la main.</b>
 * Ils sortent tous de cette procédure, reproductible par quiconque a le dépôt.
 *
 * <h2>Trois temps, et le second a été appris à la dure</h2>
 *
 * <p>La première version faisait tout dans le même tick : poser l'observateur, créer le troupeau,
 * lancer le banc. Le rapport a répondu « entités dans le monde : 0 » — deux fois.
 *
 * <p>La cause : {@code addTicketWithRadius} <b>inscrit</b> une demande de chargement, il ne charge
 * pas. Les chunks n'arrivent qu'au bout de quelques ticks, une fois le gestionnaire de distance
 * passé. Les cinq mille vaches étaient donc créées dans des chunks qui n'existaient pas encore, et
 * disparaissaient aussitôt.
 *
 * <p>D'où trois temps nettement séparés : on pose, on <b>attend</b>, puis on peuple. C'est le genre
 * de détail qu'aucune lecture du code ne révèle, et qu'une mesure révèle immédiatement.
 */
public final class SelfTest {
    /** Ticks d'attente avant de poser l'observateur : le temps que le monde finisse de s'ouvrir. */
    private static final int SETTLE = 60;
    /**
     * Ticks d'attente après la pose : le temps que les chunks demandés arrivent vraiment.
     *
     * <h2>Une constante qui supposait un seul joueur</h2>
     *
     * <p>Cent vingt ticks suffisent largement pour un observateur. À dix, le contrôle préalable a
     * refusé de mesurer : <b>18 entités vivantes sur 10 500 demandées</b>, parce que dix doublures
     * espacées de cinq cents blocs réclament dix fois plus de chunks, et que le gestionnaire de
     * distance ne les livre pas en six secondes.
     *
     * <p>Le refus est le bon comportement — sans lui, le banc aurait mesuré deux fois un monde presque
     * vide et annoncé « aucun effet » avec aplomb. Mais un outil qui refuse toujours ne sert pas non
     * plus : le délai suit désormais le nombre d'observateurs.
     */
    private static final int CHUNK_LOAD = 120;

    /** Ticks d'attente supplémentaires par observateur au-delà du premier. */
    private static final int CHUNK_LOAD_PER_EXTRA = 60;

    /** Le délai réellement accordé, qui dépend du nombre de mondes à charger. */
    private static int chunkLoadDelay() {
        return CHUNK_LOAD + Math.max(0, observers - 1) * CHUNK_LOAD_PER_EXTRA;
    }
    /** Ticks d'attente après le peuplement : le temps que les entités s'installent. */
    private static final int POPULATE = 40;

    private enum Step { OFF, SETTLING, LOADING, POPULATING, LAUNCHED }

    private static int countWanted;
    private static int radiusWanted;
    private static int observers = 1;
    /** Ce que la charge a réellement produit, retenu d.une étape à la suivante. */
    private static int born;

    /** La charge bâtie, retenue pour que le contrôle sache si elle fusionne. */
    private static fr.clubcitrouille.lanterne.lab.Scene.Kind builtScene;
    private static Step step = Step.OFF;
    private static int waiting;

    private SelfTest() {}

    /**
     * Lit la consigne d'environnement.
     *
     * <p>Format : {@code entités:rayon:observateurs}. Les deux derniers sont facultatifs.
     */
    /** Vrai si l'on éprouve la conformité plutôt que la vitesse. */
    private static boolean conformance;
    /** Vrai si l'on éprouve la cuisson. */
    private static boolean kitchen;

    /** Vrai si l'on éprouve les explosions. */
    private static boolean boom;

    /** Vrai si l'on éprouve les fluides. */
    private static boolean flow;

    /** Vrai si l'on éprouve la génération et le chargement des chunks. */
    private static boolean quarry;

    /** Vrai si l.on éprouve le débit de génération selon le parallélisme. */
    private static boolean swarm;

    /** Vrai si l.on éprouve qu.une flèche touche encore. */
    private static boolean volley;

    /**
     * Vrai si l'on éprouve que les cerveaux accélérés pensent encore.
     *
     * <p>Seule épreuve qui réutilise la charge du banc au lieu de bâtir la sienne : il lui faut six
     * cents villageois, et le chemin qui les pose est déjà écrit et éprouvé. Elle se substitue donc
     * au banc de vitesse au moment où celui-ci démarrerait.
     */
    private static boolean wits;

    /** Vrai si l'on éprouve que les villageois moissonnent encore. */
    private static boolean reap;

    /**
     * Vrai si l'on éprouve qu'une créature frappée se comporte encore comme une créature.
     *
     * <p>La seule épreuve du dépôt qui ne mesure pas des millisecondes. Elle vient d'un défaut
     * rapporté en jeu qu'aucun banc de vitesse ne pouvait voir — et pour cause, une créature qui ne
     * tick pas est la plus rapide de toutes. Voir {@code lab/Duel}.
     */
    private static boolean duel;

    /** Vrai si l'on eprouve que la maree descend puis remonte. */
    private static boolean surge;

    /** Vrai si l'on éprouve le devenir des objets au sol. */
    private static boolean tidy;

    /** Vrai si l'on éprouve la sauvegarde des chunks sur disque. */
    private static boolean vault;

    /** Vrai si l'on éprouve le rendement d'une ferme. */
    private static boolean yield;

    /** Vrai si l'on éprouve la compression des paquets de chunk. */
    private static boolean zip;

    /** Vrai si l'on relève la largeur des palettes de terrain. */
    private static boolean palette;

    /** Vrai si l'on éprouve la pose d'un gabarit de structure à cheval sur un chunk. */
    private static boolean mason;

    /** Vrai si l'on éprouve le temps que met une couronne de feuillage à tomber. */
    private static boolean grove;

    /**
     * À quelle distance du troupeau on plante l'observateur.
     *
     * <p>{@code LANTERNE_OBSERVER=8} met le joueur <b>dans</b> l'enclos, ce qui interdit au niveau de
     * détail de dégrader quoi que ce soit et force le mod à gagner autrement — ou à ne rien gagner,
     * ce qui est une réponse tout aussi utile.
     *
     * <p>Cent vingt-huit reste la valeur par défaut pour que les mesures déjà publiées restent
     * comparables : changer silencieusement la distance rendrait tous les anciens chiffres
     * incomparables aux nouveaux sans que rien ne le signale.
     */
    private static int observerRange() {
        String raw = System.getenv("LANTERNE_OBSERVER");
        if (raw == null || raw.isBlank()) {
            return 128;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException malformed) {
            Lanterne.LOG.warn("LANTERNE_OBSERVER illisible : {} — on garde 128.", raw);
            return 128;
        }
    }

    public static void arm() {
        if ("1".equals(System.getenv("LANTERNE_GROVE"))) {
            grove = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve du bosquet armée.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_MASON"))) {
            mason = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve du maçon armée.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_PALETTE"))) {
            palette = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Relevé des palettes armé.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_ZIP"))) {
            zip = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve de compression armée.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_YIELD"))) {
            yield = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve de rendement armée.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_VAULT"))) {
            vault = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve de sauvegarde armée.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_TIDY"))) {
            tidy = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve des objets au sol armée.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_VOLLEY"))) {
            volley = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve de tir armée.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_SWARM"))) {
            swarm = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve de débit de génération armée.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_QUARRY"))) {
            quarry = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve des chunks armée.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_FLOW"))) {
            flow = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve des fluides armée.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_BOOM"))) {
            boom = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve d'explosion armée.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_KITCHEN"))) {
            kitchen = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve de cuisson armée.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_CONFORMANCE"))) {
            conformance = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve de conformité armée.");
            return;
        }
        // Lue sans interrompre la suite : cette épreuve a besoin de la charge que LANTERNE_SELFTEST
        // va poser, et ne fait que remplacer ce qui se passe une fois la charge en place.
        wits = "1".equals(System.getenv("LANTERNE_WITS"));
        if ("1".equals(System.getenv("LANTERNE_SURGE"))) {
            surge = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Epreuve de maree armee.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_REAP"))) {
            reap = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve de moisson armée.");
            return;
        }
        if ("1".equals(System.getenv("LANTERNE_DUEL"))) {
            duel = true;
            step = Step.SETTLING;
            waiting = SETTLE;
            Lanterne.LOG.info("Épreuve de duel armée.");
            return;
        }

        String raw = System.getenv("LANTERNE_SELFTEST");
        if (raw == null || raw.isBlank()) {
            return;
        }
        String[] parts = raw.split(":");
        try {
            countWanted = Integer.parseInt(parts[0].trim());
            radiusWanted = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 160;
            observers = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 1;
        } catch (NumberFormatException malformed) {
            Lanterne.LOG.warn("LANTERNE_SELFTEST illisible : {}", raw);
            return;
        }
        step = Step.SETTLING;
        waiting = SETTLE;
        Lanterne.LOG.info("Auto-test armé : {} entités, anneau de {} blocs, {} observateur(s).",
                countWanted, radiusWanted, observers);
    }

    /** Fait avancer la procédure. Appelé à chaque tick du serveur. */
    public static void tick(MinecraftServer server) {
        if (step == Step.OFF
                || (step == Step.LAUNCHED && !conformance && !kitchen && !boom && !yield && !flow
                    && !quarry && !tidy && !vault && !swarm && !volley && !zip && !palette
                    && !wits && !reap && !surge && !mason && !grove && !duel)) {
            return;
        }
        if (!Conformance.running() && !Kitchen.running()
                && !fr.clubcitrouille.lanterne.lab.Boom.running()
                && !fr.clubcitrouille.lanterne.lab.Yield.running()
                && !fr.clubcitrouille.lanterne.lab.Flow.running()
                && !fr.clubcitrouille.lanterne.lab.Quarry.running()
                && !fr.clubcitrouille.lanterne.lab.Tidy.running()
                && !fr.clubcitrouille.lanterne.lab.Vault.running()
                && !fr.clubcitrouille.lanterne.lab.Wits.running()
                && !fr.clubcitrouille.lanterne.lab.Reap.running()
                && !fr.clubcitrouille.lanterne.lab.Mason.running()
                && !fr.clubcitrouille.lanterne.lab.Grove.running()
                && !fr.clubcitrouille.lanterne.lab.Duel.running()
                && !fr.clubcitrouille.lanterne.lab.Surge.running() && waiting-- > 0) {
            return;
        }

        if (duel) {
            if (fr.clubcitrouille.lanterne.lab.Duel.running()) {
                fr.clubcitrouille.lanterne.lab.Duel.tick(server);
            } else if (step == Step.SETTLING) {
                fr.clubcitrouille.lanterne.lab.Duel.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        if (grove) {
            // Une doublure, comme partout ailleurs : sans joueur, le chunk de l'origine n'est pas
            // simulé, et ni le tick programmé ni le tick aléatoire ne s'y exécutent. Les deux
            // fenêtres rendraient alors « rien n'est tombé », ce qui est le piège exact dans lequel
            // l'épreuve de cuisson est déjà tombée une fois.
            if (fr.clubcitrouille.lanterne.lab.Grove.running()) {
                fr.clubcitrouille.lanterne.lab.Grove.tick(server);
            } else if (step == Step.SETTLING) {
                Understudy.enter(server, server.overworld(), 1, 512);
                step = Step.LOADING;
                waiting = chunkLoadDelay();
            } else if (step == Step.LOADING) {
                Herd.sweepEntities(server.overworld());
                fr.clubcitrouille.lanterne.lab.Grove.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        if (mason) {
            // Une doublure, comme pour la moisson et les objets au sol : sans joueur, les chunks de
            // l'origine ne sont pas chargés et setBlock n'écrit rien. L'épreuve poserait alors son
            // gabarit dans le vide et mesurerait deux fois la même absence.
            if (fr.clubcitrouille.lanterne.lab.Mason.running()) {
                fr.clubcitrouille.lanterne.lab.Mason.tick(server);
            } else if (step == Step.SETTLING) {
                Understudy.enter(server, server.overworld(), 1, 512);
                step = Step.LOADING;
                waiting = chunkLoadDelay();
            } else if (step == Step.LOADING) {
                fr.clubcitrouille.lanterne.lab.Mason.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        if (palette) {
            if (step == Step.SETTLING) {
                Weave.survey(server.overworld(), 0, 0,
                        line -> Lanterne.LOG.info("[PALETTES] {}", line));
                step = Step.LAUNCHED;
                server.halt(false);
            }
            return;
        }

        if (zip) {
            // Aucune doublure : cette épreuve compresse des octets, elle n'a besoin d'aucun joueur.
            // Elle a seulement besoin de chunks chargés, et le monde en fournit dès le démarrage.
            if (step == Step.SETTLING) {
                fr.clubcitrouille.lanterne.lab.Zip.run(server);
                step = Step.LAUNCHED;
                server.halt(false);
            }
            return;
        }

        if (vault) {
            if (fr.clubcitrouille.lanterne.lab.Vault.running()) {
                fr.clubcitrouille.lanterne.lab.Vault.tick(server);
            } else if (step == Step.SETTLING) {
                fr.clubcitrouille.lanterne.lab.Vault.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        if (tidy) {
            // Une doublure est indispensable : sans joueur, ni les chunks ne sont simulés, ni le
            // ramassage ne peut être éprouvé. Le troisième volet de cette épreuve porte précisément
            // sur un objet posé sous ses pieds.
            if (fr.clubcitrouille.lanterne.lab.Tidy.running()) {
                fr.clubcitrouille.lanterne.lab.Tidy.tick(server);
            } else if (step == Step.SETTLING) {
                Understudy.enter(server, server.overworld(), 1, 512);
                step = Step.LOADING;
                waiting = chunkLoadDelay();
            } else if (step == Step.LOADING) {
                Herd.sweepEntities(server.overworld());
                fr.clubcitrouille.lanterne.lab.Tidy.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        if (surge) {
            // La maree ne se voit qu'avec un joueur : sans observateur, aucun chunk n'est charge,
            // rien n'est simule, et douze cents villageois laissent le serveur a douze
            // millisecondes. La premiere execution de cette epreuve l'a constate et a refuse de
            // conclure, ce qui etait le bon comportement mais ne prouvait rien.
            if (fr.clubcitrouille.lanterne.lab.Surge.running()) {
                fr.clubcitrouille.lanterne.lab.Surge.tick(server);
            } else if (step == Step.SETTLING) {
                Understudy.enter(server, server.overworld(), 1, 512);
                step = Step.LOADING;
                waiting = chunkLoadDelay();
            } else if (step == Step.LOADING) {
                fr.clubcitrouille.lanterne.lab.Surge.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        if (reap) {
            if (fr.clubcitrouille.lanterne.lab.Reap.running()) {
                fr.clubcitrouille.lanterne.lab.Reap.tick(server);
            } else if (step == Step.SETTLING) {
                fr.clubcitrouille.lanterne.lab.Reap.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        if (wits && fr.clubcitrouille.lanterne.lab.Wits.running()) {
            fr.clubcitrouille.lanterne.lab.Wits.tick(server);
            return;
        }

        if (volley) {
            if (fr.clubcitrouille.lanterne.lab.Volley.running()) {
                fr.clubcitrouille.lanterne.lab.Volley.tick(server);
            } else if (step == Step.SETTLING) {
                fr.clubcitrouille.lanterne.lab.Volley.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        if (swarm) {
            if (fr.clubcitrouille.lanterne.lab.Swarm.running()) {
                fr.clubcitrouille.lanterne.lab.Swarm.tick(server);
            } else if (step == Step.SETTLING) {
                fr.clubcitrouille.lanterne.lab.Swarm.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        if (quarry) {
            if (fr.clubcitrouille.lanterne.lab.Quarry.running()) {
                fr.clubcitrouille.lanterne.lab.Quarry.tick(server);
            } else if (step == Step.SETTLING) {
                fr.clubcitrouille.lanterne.lab.Quarry.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        if (flow) {
            // Comme l'épreuve des explosions : aucune doublure, aucun recensement. On reconstruit un
            // bassin avant chaque relevé, et le protocole ne dépend d'aucun joueur.
            if (fr.clubcitrouille.lanterne.lab.Flow.running()) {
                fr.clubcitrouille.lanterne.lab.Flow.tick(server);
            } else if (step == Step.SETTLING) {
                fr.clubcitrouille.lanterne.lab.Flow.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        if (yield) {
            if (fr.clubcitrouille.lanterne.lab.Yield.running()) {
                fr.clubcitrouille.lanterne.lab.Yield.tick(server);
            } else if (step == Step.SETTLING) {
                // Une doublure est nécessaire : sans joueur, aucun chunk n'est simulé et la ferme
                // témoin ne produirait rien non plus — deux échecs identiques dont on tirerait un
                // verdict rassurant, exactement le piège dans lequel l'épreuve de cuisson est tombée.
                Understudy.enter(server, server.overworld(), 1, 512);
                step = Step.LOADING;
                waiting = chunkLoadDelay();
            } else if (step == Step.LOADING) {
                Herd.sweepEntities(server.overworld());
                fr.clubcitrouille.lanterne.lab.Yield.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        if (boom) {
            // Aucune doublure, aucun recensement : cette épreuve mesure une explosion sur un cube de
            // pierre reconstruit avant chaque tir. Elle ne dépend d'aucun joueur, et lui en imposer un
            // n'ajouterait qu'une source de variation.
            if (fr.clubcitrouille.lanterne.lab.Boom.running()) {
                fr.clubcitrouille.lanterne.lab.Boom.tick(server);
            } else if (step == Step.SETTLING) {
                fr.clubcitrouille.lanterne.lab.Boom.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        if (kitchen) {
            if (Kitchen.running()) {
                Kitchen.tick(server);
            } else if (step == Step.SETTLING) {
                Understudy.enter(server, server.overworld(), 1, 512);
                step = Step.LOADING;
                waiting = chunkLoadDelay();
            } else if (step == Step.LOADING) {
                Herd.sweepEntities(server.overworld());
                Kitchen.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        if (conformance) {
            if (Conformance.running()) {
                Conformance.tick(server);
            } else if (step == Step.SETTLING) {
                Understudy.enter(server, server.overworld(), 1, 512);
                step = Step.LOADING;
                waiting = chunkLoadDelay();
            } else if (step == Step.LOADING) {
                // Le monde est conservé d'une épreuve à l'autre depuis que la génération de terrain
                // faussait les mesures — mais les créatures des épreuves précédentes y restaient.
                // L'épreuve de chute s'en trouvait faussée : ses vaches étaient noyées dans une
                // foule, et l'exemption de chute exige précisément d'être hors d'un amas. Trois
                // relevés à six, quatre et zéro pour cent, imputés au mod alors que le banc seul
                // était en cause.
                Herd.sweepEntities(server.overworld());
                Conformance.begin(server);
                step = Step.LAUNCHED;
            }
            return;
        }

        ServerLevel level = server.overworld();
        switch (step) {
            case SETTLING -> {
                // De vrais joueurs, et non de simples porteurs de tickets : un mod concurrent qui
                // interroge « level.players() » doit les voir, sans quoi la comparaison mesure deux
                // mondes différents et ne prouve rien.
                // <h2>Cinq cents blocs entre deux joueurs, c'était une hypothèse et elle était fausse</h2>
                //
                // Le contrôle préalable a refusé deux fois de mesurer à dix observateurs : 18 entités
                // vivantes sur 10 500, puis 962. Dix doublures espacées de cinq cents blocs réclament
                // six mille deux cent cinquante chunks, et le gestionnaire de distance ne les livre pas
                // dans le temps imparti — les vaches naissaient dans des chunks qui n'existaient pas.
                //
                // Mais surtout : dix joueurs à cinq cents blocs les uns des autres ne décrivent aucune
                // situation réelle. Sur un serveur entre amis, ils sont ensemble — sur une base, dans
                // une ferme, autour d'un projet commun. C'est ce cas-là qui charge un serveur, et
                // c'est celui-là qu'il faut mesurer.
                // <h2>Cent vingt-huit blocs mesuraient le cas facile</h2>
                //
                // Cette distance était écrite en dur, et elle décidait de tout ce que le banc pouvait
                // voir. Le relevé des cadences l'a dit sans détour sur un élevage de mille bêtes :
                //
                //     Très lointain : 1015 · Lointain : 36 · Proche : 16 · Pleine simulation : 21
                //
                // Mille quinze bêtes sur mille étaient classées « très lointain ». Le niveau de détail
                // les étranglait toutes, et le gain annoncé — x5,25 — était celui d'un troupeau que
                // PERSONNE NE REGARDE.
                //
                // Or le cas qui coûte à un serveur est l'inverse : le joueur debout dans sa ferme, où
                // le niveau de détail n'a pas le droit de dégrader quoi que ce soit. Ce cas-là n'avait
                // jamais été mesuré, faute de pouvoir approcher l'observateur.
                Understudy.enter(server, level, observers, observerRange());
                step = Step.LOADING;
                waiting = chunkLoadDelay();
            }
            case LOADING -> {
                int swept = Herd.sweepEntities(level);
                if (swept > 0) {
                    Lanterne.LOG.info("Auto-test : {} entité(s) de l'épreuve précédente retirée(s).",
                            swept);
                }
                String ovens = System.getenv("LANTERNE_OVENS");
                if (ovens != null && !ovens.isBlank()) {
                    int made = Herd.ovens(level, Integer.parseInt(ovens.trim()), 80);
                    Lanterne.LOG.info("Auto-test : {} four(s) allumé(s).", made);
                }
                builtScene = fr.clubcitrouille.lanterne.lab.Scene.parse(
                        System.getenv("LANTERNE_SCENE"));
                born = fr.clubcitrouille.lanterne.lab.Scene.build(
                        level, builtScene, countWanted, radiusWanted);
                Lanterne.LOG.info("Auto-test : charge « {} », {} entité(s) créée(s).", builtScene, born);
                step = Step.POPULATING;
                waiting = POPULATE;
            }
            case POPULATING -> {
                Lanterne.LOG.info("Auto-test : {} entité(s) vivante(s), {} joueur(s) en ligne.",
                        Bench.livingCount(level), server.getPlayerList().getPlayerCount());
                // La composition, et non le seul total : mille vaches et mille objets au sol ne
                // mesurent pas la même chose, et un rapport qui ne dit que « deux mille entités »
                // laisse croire que deux exécutions sont comparables quand elles ne le sont pas.
                Lanterne.LOG.info("Auto-test : composition — {}",
                        fr.clubcitrouille.lanterne.lab.Scene.describe(level));
                // Ce que la charge a réellement produit, et non ce qu'on lui avait demandé. Les deux
                // coïncident pour un troupeau ou un tas d'objets ; ils diffèrent pour une charge qui
                // ne crée aucune entité — la lumière bâtit une salle et fait battre des lampes. Lui
                // réclamer deux mille entités faisait refuser la mesure pour une raison inexistante.
                // Une charge qui FUSIONNE ne peut pas annoncer son compte d.avance. Les orbes
                // d.expérience se joignent entre elles — c.est même tout l.intérêt du module qui les
                // concerne — si bien que quatre mille orbes posées en sont mille neuf cents quarante
                // secondes plus tard. Le contrôle préalable, qui vérifie qu.on a bien créé ce qu.on
                // demandait, refusait alors la mesure en croyant à des chunks non chargés.
                //
                // Pour ces charges-là, l.attendu est ce qui vit après stabilisation. Le contrôle perd
                // sa capacité à détecter une évaporation — mais il ne l.avait de toute façon pas ici,
                // puisqu.il ne sait pas distinguer une orbe fusionnée d.une orbe perdue.
                // La récolte fusionne comme les orbes : lui réclamer le compte demandé ferait refuser
                // la mesure pour la raison exacte qui la rend intéressante.
                boolean merges = builtScene == fr.clubcitrouille.lanterne.lab.Scene.Kind.ORBS
                        || builtScene == fr.clubcitrouille.lanterne.lab.Scene.Kind.DROPS;
                Bench.expect(merges ? Bench.livingCount(level) : born);
                // L'épreuve des cerveaux prend la place du banc : même charge, autre question.
                if (wits) {
                    fr.clubcitrouille.lanterne.lab.Wits.begin(server);
                } else {
                    Bench.startHeadless(server);
                }
                step = Step.LAUNCHED;
            }
            default -> { }
        }
    }
}
