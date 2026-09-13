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

    /** Vrai si l'on éprouve le devenir des objets au sol. */
    private static boolean tidy;

    /** Vrai si l'on éprouve la sauvegarde des chunks sur disque. */
    private static boolean vault;

    /** Vrai si l'on éprouve le rendement d'une ferme. */
    private static boolean yield;

    public static void arm() {
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
                    && !quarry && !tidy && !vault && !swarm && !volley)) {
            return;
        }
        if (!Conformance.running() && !Kitchen.running()
                && !fr.clubcitrouille.lanterne.lab.Boom.running()
                && !fr.clubcitrouille.lanterne.lab.Yield.running()
                && !fr.clubcitrouille.lanterne.lab.Flow.running()
                && !fr.clubcitrouille.lanterne.lab.Quarry.running()
                && !fr.clubcitrouille.lanterne.lab.Tidy.running()
                && !fr.clubcitrouille.lanterne.lab.Vault.running() && waiting-- > 0) {
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
                Understudy.enter(server, level, observers, 128);
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
                Bench.startHeadless(server);
                step = Step.LAUNCHED;
            }
            default -> { }
        }
    }
}
