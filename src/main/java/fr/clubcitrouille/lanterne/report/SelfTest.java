package fr.clubcitrouille.lanterne.report;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.lab.Phantom;

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
    public static void arm() {
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
        if (step == Step.OFF || step == Step.LAUNCHED) {
            return;
        }
        if (waiting-- > 0) {
            return;
        }

        ServerLevel level = server.overworld();
        switch (step) {
            case SETTLING -> {
                // Les observateurs d'abord, seuls. Sans leurs tickets, les chunks se déchargent et
                // tout ce qu'on y poserait partirait avec eux.
                Phantom.summon(level, observers, 512, 12);
                step = Step.LOADING;
                waiting = CHUNK_LOAD;
            }
            case LOADING -> {
                int born = Herd.populate(level, 0d, 0d, countWanted, radiusWanted);
                Lanterne.LOG.info("Auto-test : {} entités créées sur un anneau de {} blocs.",
                        born, radiusWanted);
                step = Step.POPULATING;
                waiting = POPULATE;
            }
            case POPULATING -> {
                Lanterne.LOG.info("Auto-test : {} entité(s) vivante(s) avant mesure.",
                        Bench.livingCount(level));
                Bench.startHeadless(server);
                step = Step.LAUNCHED;
            }
            default -> { }
        }
    }
}
