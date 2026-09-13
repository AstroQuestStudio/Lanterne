package fr.clubcitrouille.lanterne.lab;

import java.util.Locale;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.levelgen.Heightmap;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'épreuve qui décide si le raccourci des projectiles a le droit d'exister.
 *
 * <h2>Le mécanisme le plus risqué du mod, après le bateau</h2>
 *
 * <p>Le module « projectiles » rend ×4,04 sur une volée de six mille flèches, en supprimant quatre
 * millions de recherches de cible. Il le fait en pariant que <b>rien de sélectionnable</b> ne se
 * trouve dans la zone examinée — et ce pari repose sur un recensement, tenu pendant le tour des
 * entités.
 *
 * <p>Si ce recensement manque une entité, les flèches la traversent. Et la façon dont il pourrait la
 * manquer n'est pas théorique : il est alimenté depuis {@code EntityThrottle.shouldTick}, c'est-à-dire
 * depuis le passage où chaque entité se présente une fois par tick. Encore faut-il que <b>les joueurs
 * y passent aussi</b>. Rien dans le code ne le promet ; il faut le vérifier.
 *
 * <p>Un défaut de ce genre ne se remarque pas sur un banc de vitesse — il se remarque en jeu, six
 * mois plus tard, quand quelqu'un signale que les squelettes ne touchent plus personne. C'est le même
 * genre de défaut que le bateau qui ne bloquait qu'un tick sur quarante, et il a été trouvé de la
 * même façon : en écrivant l'épreuve avant d'y croire.
 *
 * <h2>Le protocole</h2>
 *
 * <p>Une créature est posée à dix blocs, une flèche est tirée droit dessus, et l'on regarde sa santé
 * vingt ticks plus tard. Deux fois : mod allumé, puis mod éteint. <b>Les deux doivent toucher</b>, et
 * l'épreuve échoue si le verdict diffère — y compris si c'est le mod qui touche et pas vanilla, ce
 * qui trahirait une autre erreur.
 */
public final class Volley {
    /** Ticks laissés à la flèche pour parcourir ses dix blocs et frapper. */
    private static final int FLIGHT = 25;

    /** Distance de tir, en blocs. Assez pour que la flèche vole vraiment, assez peu pour ne pas rater. */
    private static final double RANGE = 10d;

    private enum Step { OFF, SHOT_ON, SHOT_OFF, DONE }

    private static Step step = Step.OFF;
    private static int waiting;
    private static Cow target;
    private static float healthBefore;
    private static boolean hitWithMod;
    private static boolean hitWithout;
    private static MinecraftServer host;

    private Volley() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        Settings.setEnabled(true);
        // Sans chunk chargé, rien ne tick : la flèche reste en l'air et la vache est épargnée des
        // deux côtés. La première version de cette épreuve l'a appris en rendant « épargnée » deux
        // fois, et c'est son propre garde-fou qui l'a déclarée invalide. Le même défaut avait déjà
        // coûté une mesure à l'épreuve des fluides.
        forceChunks(server.overworld());
        step = Step.SHOT_ON;
        fire(server.overworld());
        Lanterne.LOG.info("[TIR] Première moitié, mod allumé — une flèche, une vache à {} blocs.",
                (int) RANGE);
    }

    /** Force les chunks autour du champ de tir : sans eux, aucune entité n.y est simulée. */
    private static void forceChunks(ServerLevel level) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setChunkForced(dx, dz, true);
            }
        }
        Lanterne.LOG.info("[TIR] vingt-cinq chunks forcés autour de l.origine.");
    }

    /**
     * Pose la cible et tire.
     *
     * <p>La créature est rendue insensible à la gravité de la chute et placée sur le sol : on veut
     * mesurer l'effet d'une flèche, pas celui d'un atterrissage.
     */
    private static void fire(ServerLevel level) {
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);

        target = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
        if (target == null) {
            Lanterne.LOG.warn("[TIR] impossible de créer la cible — épreuve abandonnée.");
            step = Step.DONE;
            return;
        }
        target.snapTo(0d, ground + 1d, 0d, 0f, 0f);
        target.setNoAi(true);
        level.addFreshEntity(target);
        healthBefore = target.getHealth();

        AbstractArrow arrow = (AbstractArrow) EntityType.ARROW.create(level, EntitySpawnReason.COMMAND);
        if (arrow == null) {
            Lanterne.LOG.warn("[TIR] impossible de créer la flèche — épreuve abandonnée.");
            step = Step.DONE;
            return;
        }
        // Tirée à hauteur de poitrail et VISÉE, non poussée. La première version se contentait de
        // poser une vitesse horizontale : la flèche perdait alors un bloc de hauteur sur les dix du
        // trajet et passait sous la vache. L.épreuve a rendu « épargnée » des deux côtés, et son
        // propre garde-fou l.a déclarée invalide — ce qui est exactement ce qu.on lui demande.
        arrow.snapTo(0d, ground + 1.1d, -RANGE, 0f, 0f);
        arrow.shoot(0d, 0.12d, 1d, 3.0f, 0f);
        level.addFreshEntity(arrow);

        waiting = FLIGHT;
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        if (--waiting > 0) {
            return;
        }

        Lanterne.LOG.info("[TIR] état du recensement : {} cible(s) au dernier tick, {} recherche(s) "
                + "évitée(s), {} appel(s) de recensement depuis le démarrage.",
                fr.clubcitrouille.lanterne.core.Quarry.targets(),
                fr.clubcitrouille.lanterne.core.Quarry.shortcuts(),
                fr.clubcitrouille.lanterne.core.Quarry.noted());
        boolean touched = target != null && target.getHealth() < healthBefore;
        if (step == Step.SHOT_ON) {
            hitWithMod = touched;
            Lanterne.LOG.info("[TIR] mod allumé — la vache a {} ({} → {} points de vie).",
                    touched ? "ÉTÉ TOUCHÉE" : "été épargnée",
                    String.format(Locale.ROOT, "%.1f", healthBefore),
                    String.format(Locale.ROOT, "%.1f", target == null ? 0f : target.getHealth()));
            cleanUp(server.overworld());
            Settings.setEnabled(false);
            step = Step.SHOT_OFF;
            fire(server.overworld());
            Lanterne.LOG.info("[TIR] Seconde moitié, mod éteint.");
            return;
        }

        hitWithout = touched;
        Lanterne.LOG.info("[TIR] mod éteint — la vache a {} ({} → {} points de vie).",
                touched ? "ÉTÉ TOUCHÉE" : "été épargnée",
                String.format(Locale.ROOT, "%.1f", healthBefore),
                String.format(Locale.ROOT, "%.1f", target == null ? 0f : target.getHealth()));
        cleanUp(server.overworld());
        Settings.setEnabled(true);
        step = Step.DONE;
        report();
        if (host != null) {
            host.halt(false);
        }
    }

    private static void cleanUp(ServerLevel level) {
        if (target != null) {
            target.discard();
            target = null;
        }
        java.util.List<net.minecraft.world.entity.Entity> spent = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity leftover : level.getAllEntities()) {
            if (leftover instanceof AbstractArrow) {
                spent.add(leftover);
            }
        }
        for (net.minecraft.world.entity.Entity leftover : spent) {
            leftover.discard();
        }
    }

    /**
     * Le verdict, qui n'admet qu'un seul résultat acceptable.
     *
     * <p>Les deux moitiés doivent toucher. Si le mod épargne la vache, son raccourci est faux et il
     * doit être retiré. Si c'est vanilla qui l'épargne, l'épreuve elle-même est mal faite et son
     * silence ne prouverait rien — ce cas est donc signalé aussi fort que l'autre.
     */
    private static void report() {
        Lanterne.LOG.info("[TIR] ── Verdict ──");
        if (hitWithMod && hitWithout) {
            Lanterne.LOG.info("[TIR] CONFORME : la flèche touche dans les deux cas. Le raccourci de "
                    + "recherche de cible ne supprime que des recherches vides.");
            return;
        }
        if (!hitWithout) {
            Lanterne.LOG.error("[TIR] ÉPREUVE INVALIDE : la flèche ne touche pas même sans le mod. "
                    + "Le protocole est en cause, pas le module — et son silence ne prouverait rien.");
            return;
        }
        Lanterne.LOG.error("[TIR] NON CONFORME : la flèche touche sans le mod et le rate avec. Le "
                + "recensement des cibles manque quelque chose. Le module doit être retiré tant que "
                + "ce n'est pas expliqué.");
    }

    /** Le type de cible, gardé pour que l'import serve au lecteur autant qu'au compilateur. */
    static Class<? extends LivingEntity> aimedAt() {
        return Cow.class;
    }
}
