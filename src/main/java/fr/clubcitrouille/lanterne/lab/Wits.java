package fr.clubcitrouille.lanterne.lab;

import java.util.Locale;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'épreuve qui décide si le cerveau accéléré pense encore.
 *
 * <h2>Le défaut que le banc de vitesse ne peut pas voir</h2>
 *
 * <p>{@code BrainMixin} ne recalcule plus, à chaque tick, quels comportements d'une créature sont en
 * cours : il tient la liste à jour au fil des démarrages et des arrêts. Cela rend ×1,21 sur six
 * cents villageois.
 *
 * <p>Mais si un seul chemin de démarrage lui échappait, le comportement concerné ne serait plus
 * jamais tické. La créature se figerait — et un banc de vitesse applaudirait, puisqu'une créature qui
 * ne fait rien est la plus rapide de toutes. <b>C'est le seul module du mod dont la panne améliore
 * le chiffre.</b> Il lui faut donc une épreuve qui regarde ailleurs que l'horloge.
 *
 * <h2>Pourquoi on ne demande pas au module s'il va bien</h2>
 *
 * <p>La tentation serait de compter les comportements en cours avec {@code getRunningBehaviors()}.
 * Mais c'est précisément la méthode que le module remplace : une liste fausse mentirait à son propre
 * examinateur, et l'épreuve validerait le défaut qu'elle cherche.
 *
 * <p>On mesure donc deux choses que le module ne touche pas :
 *
 * <ul>
 *   <li><b>La distance réellement parcourue</b>, cumulée tick par tick. Un villageois dont le cerveau
 *       est figé ne marche pas. Aucun raccourci ne peut simuler ce chiffre.</li>
 *   <li><b>Les mémoires de déplacement et de regard</b>, que seuls les comportements en cours
 *       écrivent. Elles sont lues dans la table des mémoires, à laquelle le module ne touche pas.</li>
 * </ul>
 *
 * <h2>Trois phases, et la troisième est la vraie</h2>
 *
 * <p>Allumé, éteint, <b>rallumé</b>. Les deux premières disent si le module pense aussi bien que
 * vanilla. La troisième vise un piège particulier : pendant la phase éteinte, vanilla fait démarrer
 * et s'arrêter des comportements sans que le module en sache rien. Sa liste devient fausse. S'il la
 * reprenait telle quelle en se rallumant, il ticherait des comportements arrêtés et en oublierait
 * d'autres.
 *
 * <p>C'est {@link Settings#epoch()} qui l'en empêche, et c'est cette troisième phase qui le vérifie.
 * Le cas n'a rien de théorique : {@code /lanterne off} puis {@code /lanterne on} le produit en jeu.
 *
 * <h2>Le verdict</h2>
 *
 * <p>La distance parcourue avec le mod doit valoir au moins {@value #FLOOR_PERCENT} % de celle sans.
 * Le seuil est large à dessein : l'IA d'un villageois est bruyante, et deux fenêtres de dix secondes
 * ne rendent jamais le même chiffre. Ce qu'on cherche n'est pas une égalité, c'est un effondrement —
 * un cerveau figé rend zéro, pas quatre-vingts pour cent.
 */
public final class Wits {
    /** Durée de chaque phase, en ticks. Dix secondes : assez pour marcher, assez peu pour trois fois. */
    private static final int WINDOW = 200;

    /** Part minimale de l'activité de vanilla que le mod doit conserver. */
    private static final int FLOOR_PERCENT = 60;

    private enum Step { OFF, WARM, ON_FIRST, VANILLA, ON_AGAIN, DONE }

    private static Step step = Step.OFF;
    private static int waiting;
    private static MinecraftServer host;

    /** Dernière position connue de chaque villageois, pour cumuler le déplacement. */
    private static final Int2ObjectOpenHashMap<double[]> LAST = new Int2ObjectOpenHashMap<>();

    private static double walkedOn;
    private static double walkedOff;
    private static double walkedAgain;
    private static long targetsOn;
    private static long targetsOff;
    private static long targetsAgain;
    private static int sampledTicks;

    private Wits() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        Settings.setEnabled(true);
        LAST.clear();
        // Les distances doivent rester fixes : voir Bench, meme raison.
        Settings.setTide(false);
        freezeNoon(server);
        step = Step.WARM;
        // Un tick d'amorce avant la première phase : sans position de départ, le premier relevé
        // compterait le déplacement depuis l'origine du monde.
        waiting = 1;
        Lanterne.LOG.info("[ESPRIT] Épreuve armée — trois fenêtres de {} ticks : allumé, éteint, "
                + "rallumé.", WINDOW);
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();

        if (step == Step.WARM) {
            snapshot(level);
            if (--waiting > 0) {
                return;
            }
            step = Step.ON_FIRST;
            waiting = WINDOW;
            Lanterne.LOG.info("[ESPRIT] Première fenêtre, mod allumé.");
            return;
        }

        gather(level);
        if (--waiting > 0) {
            return;
        }

        switch (step) {
            case ON_FIRST -> {
                Lanterne.LOG.info(String.format(Locale.ROOT,
                        "[ESPRIT] mod allumé — %.1f blocs parcourus, %d cible(s) de déplacement "
                        + "relevée(s).", walkedOn, targetsOn));
                Settings.setEnabled(false);
                step = Step.VANILLA;
                waiting = WINDOW;
                Lanterne.LOG.info("[ESPRIT] Deuxième fenêtre, mod éteint — c'est ici que la liste du "
                        + "module devient fausse sans qu'il le sache.");
            }
            case VANILLA -> {
                Lanterne.LOG.info(String.format(Locale.ROOT,
                        "[ESPRIT] mod éteint — %.1f blocs parcourus, %d cible(s) de déplacement "
                        + "relevée(s).", walkedOff, targetsOff));
                Settings.setEnabled(true);
                step = Step.ON_AGAIN;
                waiting = WINDOW;
                Lanterne.LOG.info("[ESPRIT] Troisième fenêtre, mod rallumé — la liste périmée doit "
                        + "avoir été jetée.");
            }
            case ON_AGAIN -> {
                Lanterne.LOG.info(String.format(Locale.ROOT,
                        "[ESPRIT] mod rallumé — %.1f blocs parcourus, %d cible(s) de déplacement "
                        + "relevée(s).", walkedAgain, targetsAgain));
                step = Step.DONE;
                report();
                if (host != null) {
                    host.halt(false);
                }
            }
            default -> { }
        }
    }

    /**
     * Arrête l'horloge du monde.
     *
     * <h2>Pourquoi cette épreuve ne valait rien sans cela</h2>
     *
     * <p>Un villageois ne fait pas la même chose selon l'heure : il travaille, se promène, puis
     * cherche un lit. Son déplacement suit donc l'horloge du monde autant que son cerveau.
     *
     * <p>Deux exécutions successives de cette épreuve ont rendu 10 078 blocs puis 2 203 — un facteur
     * cinq, pour le même code et la même charge. Et à l'intérieur d'une exécution, les trois fenêtres
     * décroissaient régulièrement : 2 203, puis 1 812, puis 1 656. La troisième fenêtre était donc
     * pénalisée <em>par l'heure qu'il était</em>, et c'est précisément elle qui porte le verdict sur
     * le rallumage.
     *
     * <p>Le défaut jouait contre le mod, ce qui rendait l'épreuve trop sévère plutôt que complaisante.
     * Mais une épreuve dont le résultat dépend de l'heure ne prouve rien dans un sens comme dans
     * l'autre, et la prochaine dérive pourrait aller dans le bon.
     *
     * <p>L'horloge arrêtée, les trois fenêtres voient le même monde. Peu importe l'heure
     * qu'il est : ce qui compte est qu'elle soit la même pour les trois.
     */
    private static void freezeNoon(MinecraftServer server) {
        ServerLevel level = server.overworld();
        // En 26.1, « doDaylightCycle » s'appelle « advance_time » : le renommage figure dans
        // GameRuleRegistryFix, et les règles sont devenues un registre.
        level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME,
                false, server);
        Lanterne.LOG.info("[ESPRIT] Horloge arrêtée (tick de jeu {}) — sans quoi les trois fenêtres "
                + "ne compareraient que l'heure qu'il est.", level.getGameTime());
    }

    /** Note les positions sans rien cumuler : sert d'origine à la première fenêtre. */
    private static void snapshot(ServerLevel level) {
        for (Entity entity : level.getAllEntities()) {
            if (isBrained(entity)) {
                LAST.put(entity.getId(), new double[] { entity.getX(), entity.getZ() });
            }
        }
    }

    /**
     * Cumule le déplacement et compte les cibles, pour la fenêtre en cours.
     *
     * <p>Les deux mémoires choisies sont écrites par des comportements distincts — marcher et
     * regarder — ce qui évite qu'une seule panne les fasse disparaître ensemble par coïncidence.
     */
    private static void gather(ServerLevel level) {
        double walked = 0d;
        long targets = 0L;

        for (Entity entity : level.getAllEntities()) {
            if (!isBrained(entity)) {
                continue;
            }
            LivingEntity body = (LivingEntity) entity;
            double[] before = LAST.get(entity.getId());
            if (before != null) {
                double dx = body.getX() - before[0];
                double dz = body.getZ() - before[1];
                walked += Math.sqrt(dx * dx + dz * dz);
                before[0] = body.getX();
                before[1] = body.getZ();
            } else {
                LAST.put(entity.getId(), new double[] { body.getX(), body.getZ() });
            }

            var brain = body.getBrain();
            if (brain.hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
                targets++;
            }
            if (brain.hasMemoryValue(MemoryModuleType.LOOK_TARGET)) {
                targets++;
            }
        }

        switch (step) {
            case ON_FIRST -> {
                walkedOn += walked;
                targetsOn += targets;
                sampledTicks++;
            }
            case VANILLA -> {
                walkedOff += walked;
                targetsOff += targets;
            }
            case ON_AGAIN -> {
                walkedAgain += walked;
                targetsAgain += targets;
            }
            default -> { }
        }
    }

    /** Une créature à cerveau, et non un joueur — qui en a un, vide, et fausserait le compte. */
    private static boolean isBrained(Entity entity) {
        return entity instanceof net.minecraft.world.entity.npc.villager.Villager;
    }

    private static void report() {
        Lanterne.LOG.info("[ESPRIT] ── Verdict ── ({} ticks relevés par fenêtre)", sampledTicks);
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[ESPRIT] Blocs parcourus · allumé %.1f · éteint %.1f · rallumé %.1f",
                walkedOn, walkedOff, walkedAgain));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[ESPRIT] Cibles relevées · allumé %d · éteint %d · rallumé %d",
                targetsOn, targetsOff, targetsAgain));

        if (walkedOff <= 0.001d) {
            Lanterne.LOG.error("[ESPRIT] ÉPREUVE INVALIDE : les villageois ne bougent pas même sans "
                    + "le mod. Le protocole est en cause, et son silence ne prouverait rien.");
            return;
        }

        int firstPercent = (int) Math.round(100d * walkedOn / walkedOff);
        int againPercent = (int) Math.round(100d * walkedAgain / walkedOff);

        if (firstPercent < FLOOR_PERCENT) {
            Lanterne.LOG.error("[ESPRIT] NON CONFORME : le mod ne conserve que {} % du déplacement de "
                    + "vanilla. Des comportements ne sont plus tickés. Le module doit être retiré tant "
                    + "que ce n'est pas expliqué.", firstPercent);
            return;
        }
        if (againPercent < FLOOR_PERCENT) {
            Lanterne.LOG.error("[ESPRIT] NON CONFORME APRÈS RALLUMAGE : {} % du déplacement de vanilla, "
                    + "contre {} % au premier allumage. La liste des comportements en cours n'a pas été "
                    + "jetée quand le mod s'est rallumé — voir Settings.epoch().",
                    againPercent, firstPercent);
            return;
        }
        Lanterne.LOG.info("[ESPRIT] CONFORME : {} % du déplacement de vanilla au premier allumage, "
                + "{} % après extinction et rallumage. Les cerveaux pensent, et la liste survit au "
                + "basculement.", firstPercent, againPercent);
    }
}
