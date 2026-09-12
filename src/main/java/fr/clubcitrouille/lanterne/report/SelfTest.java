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
    /** Ticks d'attente après la pose : le temps que les chunks demandés arrivent vraiment. */
    private static final int CHUNK_LOAD = 120;
    /** Ticks d'attente après le peuplement : le temps que les entités s'installent. */
    private static final int POPULATE = 40;

    private enum Step { OFF, SETTLING, LOADING, POPULATING, LAUNCHED }

    private static int countWanted;
    private static int radiusWanted;
    private static int observers = 1;
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

    public static void arm() {
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
        if (step == Step.OFF || (step == Step.LAUNCHED && !conformance && !kitchen)) {
            return;
        }
        if (!Conformance.running() && !Kitchen.running() && waiting-- > 0) {
            return;
        }

        if (kitchen) {
            if (Kitchen.running()) {
                Kitchen.tick(server);
            } else if (step == Step.SETTLING) {
                Understudy.enter(server, server.overworld(), 1, 512);
                step = Step.LOADING;
                waiting = CHUNK_LOAD;
            } else if (step == Step.LOADING) {
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
                waiting = CHUNK_LOAD;
            } else if (step == Step.LOADING) {
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
                Understudy.enter(server, level, observers, 512);
                step = Step.LOADING;
                waiting = CHUNK_LOAD;
            }
            case LOADING -> {
                String ovens = System.getenv("LANTERNE_OVENS");
                if (ovens != null && !ovens.isBlank()) {
                    int made = Herd.ovens(level, Integer.parseInt(ovens.trim()), 80);
                    Lanterne.LOG.info("Auto-test : {} four(s) allumé(s).", made);
                }
                int born = Herd.populate(level, 0d, 0d, countWanted, radiusWanted);
                Lanterne.LOG.info("Auto-test : {} entités créées sur un anneau de {} blocs.",
                        born, radiusWanted);
                step = Step.POPULATING;
                waiting = POPULATE;
            }
            case POPULATING -> {
                Lanterne.LOG.info("Auto-test : {} entité(s) vivante(s), {} joueur(s) en ligne.",
                        Bench.livingCount(level), server.getPlayerList().getPlayerCount());
                Bench.startHeadless(server);
                step = Step.LAUNCHED;
            }
            default -> { }
        }
    }
}
