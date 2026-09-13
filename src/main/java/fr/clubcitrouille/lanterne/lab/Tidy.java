package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.report.Herd;

/**
 * L'épreuve des objets au sol : le sommeil laisse-t-il le monde se ranger tout seul ?
 *
 * <h2>Pourquoi cette épreuve décide de tout</h2>
 *
 * <p>Le sommeil des objets vient de rendre le chiffre le plus élevé de ce projet : quatre cent douze
 * millisecondes par tick ramenées à huit, soit un facteur cinquante, sur un sol jonché de huit mille
 * objets.
 *
 * <p>Ce chiffre ne vaut <b>rien</b> sans celle-ci. Un objet endormi qui ne disparaîtrait plus
 * transformerait le gain en fuite de mémoire : le sol s'accumulerait indéfiniment, et le serveur
 * finirait par s'étouffer — plus lentement qu'avant, mais plus sûrement. Un objet endormi qui ne serait
 * plus ramassable serait pire encore : le joueur verrait son minerai par terre, marcherait dessus, et ne
 * le récupérerait pas.
 *
 * <p>Le raisonnement qui autorise ce sommeil repose sur trois affirmations, lues dans le code du jeu.
 * Cette épreuve les vérifie une par une, parce qu'un raisonnement juste mal implémenté donne exactement
 * le même résultat qu'un raisonnement faux.
 *
 * <h2>Les trois vérifications</h2>
 *
 * <p><b>Un — la disparition.</b> C'est la seule chose qu'un objet fait de sa propre initiative : compter
 * son âge, et s'effacer au bout de cinq minutes. Le sommeil continue de compter à la main ; on mesure
 * donc le tick exact de la disparition, avec et sans le mod. Tout écart est un défaut.
 *
 * <p>La durée de vie est imposée courte plutôt qu'attendue. Cinq minutes par phase feraient vingt
 * minutes d'épreuve pour vérifier un compteur qui se lit aussi bien sur six secondes — et
 * {@code lifespan} est un champ public du jeu, prévu pour être réglé.
 *
 * <p><b>Deux — la trémie.</b> Le raisonnement dit que c'est la trémie qui cherche les objets au-dessus
 * d'elle, jamais l'objet qui se propose. Si c'est vrai, un objet endormi est aspiré comme les autres. On
 * pose donc une trémie et un objet dessus, et l'on regarde.
 *
 * <p><b>Trois — le ramassage.</b> {@code Player.java:503} fait {@code entity.playerTouch(this)} : c'est
 * le joueur qui touche l'objet. Un objet posé sous les pieds d'une doublure doit donc finir dans son
 * inventaire, endormi ou non.
 *
 * <h2>Loin du joueur, comme toujours</h2>
 *
 * <p>La disparition et la trémie sont éprouvées à cent vingt blocs. Sous les yeux du joueur, le mod
 * dégrade moins, et l'épreuve passerait sans rien prouver. Le ramassage, lui, exige évidemment d'être
 * à portée — c'est le seul des trois qui se vérifie sur place.
 */
public final class Tidy {
    /** Distance d'observation : assez loin pour que le mod agisse pleinement. */
    private static final int DISTANCE = 120;
    private static final int CROWD = 40;

    /**
     * Durée de vie imposée, en ticks.
     *
     * <p>Six secondes au lieu de cinq minutes. Le compteur se vérifie aussi bien, et l'épreuve entière
     * tient en moins d'une minute au lieu de vingt.
     */
    private static final int LIFE = 120;

    /** Ticks au-delà desquels on cesse d'attendre une disparition. */
    private static final int PATIENCE = 400;

    private enum Step { OFF, WITH, WITHOUT, DONE }

    private static Step step = Step.OFF;
    private static int elapsed;
    private static MinecraftServer host;

    private static final List<ItemEntity> DOOMED = new ArrayList<>();
    /** Tick de disparition de chaque objet, ou zéro s'il est toujours là. */
    private static final int[][] GONE = new int[2][CROWD];

    private static BlockPos hopperAt;
    private static ItemEntity hopperBait;
    /** Objets parvenus dans la trémie : indice 0 avec le mod, 1 sans. */
    private static final int[] SUCKED = new int[2];

    private static ItemEntity underfoot;
    /** L'objet posé sous la doublure a-t-il été ramassé ? */
    private static final boolean[] PICKED = new boolean[2];

    private Tidy() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        step = Step.WITH;
        Settings.setEnabled(true);
        scatter(server.overworld());
        Lanterne.LOG.info("[OBJETS] Épreuve lancée, mod actif — {} objet(s) à {} blocs, "
                + "durée de vie imposée {} ticks.", DOOMED.size(), DISTANCE, LIFE);
    }

    /** Pose les trois dispositifs. */
    private static void scatter(ServerLevel level) {
        DOOMED.clear();
        elapsed = 0;

        // Un — les objets dont on mesure la disparition, sur un arc à distance constante.
        for (int i = 0; i < CROWD; i++) {
            double angle = Math.PI / 2d * i / CROWD;
            double x = Math.cos(angle) * DISTANCE;
            double z = Math.sin(angle) * DISTANCE;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int) Math.floor(x), (int) Math.floor(z));

            ItemEntity item = new ItemEntity(level, x, y + 0.2d, z,
                    new ItemStack(Items.COBBLESTONE, 64));
            item.setDeltaMovement(0d, 0d, 0d);
            // On impose la durée de vie APRÈS la construction : le constructeur la tire de la pile
            // elle-même, et l'écraserait.
            item.lifespan = LIFE;
            if (level.addFreshEntity(item)) {
                DOOMED.add(item);
            }
        }

        // Deux — la trémie et son appât, à la même distance.
        int hopperY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, DISTANCE, 0);
        hopperAt = new BlockPos(DISTANCE, hopperY, 0);
        level.setBlockAndUpdate(hopperAt, Blocks.HOPPER.defaultBlockState()
                .setValue(HopperBlock.FACING, Direction.DOWN));
        hopperBait = new ItemEntity(level, DISTANCE + 0.5d, hopperY + 1.1d, 0.5d,
                new ItemStack(Items.DIAMOND, 1));
        hopperBait.setDeltaMovement(0d, 0d, 0d);
        // L'appât ne doit pas expirer avant d'avoir été aspiré, sinon on confondrait les deux issues.
        hopperBait.setUnlimitedLifetime();
        level.addFreshEntity(hopperBait);

        // Trois — l'objet sous les pieds de la doublure, qui se tient à l'origine.
        int here = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        underfoot = new ItemEntity(level, 0.5d, here + 0.2d, 0.5d,
                new ItemStack(Items.EMERALD, 1));
        underfoot.setDeltaMovement(0d, 0d, 0d);
        underfoot.setUnlimitedLifetime();
        level.addFreshEntity(underfoot);
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();
        elapsed++;

        int slot = step == Step.WITH ? 0 : 1;

        boolean allGone = true;
        for (int i = 0; i < DOOMED.size(); i++) {
            if (GONE[slot][i] > 0) {
                continue;
            }
            if (DOOMED.get(i).isRemoved()) {
                GONE[slot][i] = elapsed;
            } else {
                allGone = false;
            }
        }

        if (hopperBait != null && hopperBait.isRemoved() && SUCKED[slot] == 0) {
            SUCKED[slot] = countInHopper(level);
        }
        if (underfoot != null && underfoot.isRemoved()) {
            PICKED[slot] = true;
        }

        if ((allGone && SUCKED[slot] > 0) || elapsed >= PATIENCE) {
            if (SUCKED[slot] == 0) {
                SUCKED[slot] = countInHopper(level);
            }
            advance(level);
        }
    }

    private static int countInHopper(ServerLevel level) {
        if (hopperAt == null
                || !(level.getBlockEntity(hopperAt) instanceof HopperBlockEntity hopper)) {
            return -1;
        }
        int total = 0;
        for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
            total += hopper.getItem(slot).getCount();
        }
        return total;
    }

    private static void advance(ServerLevel level) {
        if (step == Step.WITH) {
            step = Step.WITHOUT;
            Settings.setEnabled(false);
            level.setBlockAndUpdate(hopperAt, Blocks.AIR.defaultBlockState());
            Herd.sweepEntities(level);
            scatter(level);
            Lanterne.LOG.info("[OBJETS] Seconde moitié, mod éteint.");
            return;
        }
        step = Step.DONE;
        Settings.setEnabled(true);
        level.setBlockAndUpdate(hopperAt, Blocks.AIR.defaultBlockState());
        assertBoatBlocks(level);
        report();
        if (host != null) {
            host.halt(false);
        }
    }

    /**
     * Le court-circuit de collision se désactive-t-il quand quelque chose peut vraiment bloquer ?
     *
     * <h2>Le mécanisme le plus risqué du mod, et le seul que rien ne vérifiait</h2>
     *
     * <p>{@code Solid} supprime les recherches de collision d'entités en s'appuyant sur un fait vérifié :
     * {@code Entity.canBeCollidedWith()} rend faux par défaut, et trois classes seulement le
     * redéfinissent dans tout Minecraft — le bateau, le shulker et le ghast apprivoisé.
     *
     * <p>Le raisonnement est juste. Mais s'il est <b>mal implémenté</b>, le symptôme n'est pas un
     * ralentissement : c'est une créature qui traverse un bateau. Et aucune des épreuves de ce projet
     * n'aurait pu le voir — la conformité mesure des chutes, le rendement des œufs, le banc du temps.
     *
     * <p>On interroge donc le mécanisme directement, sur le seul cas qui compte : un bateau posé dans le
     * monde doit <b>interdire</b> le raccourci sur sa propre boîte, et le laisser possible ailleurs. Les
     * deux réponses importent autant l'une que l'autre : un mécanisme qui refuse toujours serait sûr et
     * inutile.
     */
    private static void assertBoatBlocks(ServerLevel level) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 40, 40);
        var boat = net.minecraft.world.entity.EntityType.OAK_BOAT.create(
                level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (boat == null || !level.addFreshEntity(boat)) {
            Lanterne.LOG.warn("[OBJETS] Bateau — NON MESURÉ : le bateau d'essai n'a pas pu être créé.");
            return;
        }
        boat.snapTo(40.5d, y + 1d, 40.5d, 0f, 0f);

        // Le recensement des bloquantes se clôt au tick suivant : on le force ici pour ne pas dépendre
        // du moment où cette vérification tombe dans le tick.
        fr.clubcitrouille.lanterne.core.Solid.note(boat);
        fr.clubcitrouille.lanterne.core.Solid.rotate(level);

        boolean freeOnBoat = fr.clubcitrouille.lanterne.core.Solid.noneIn(boat.getBoundingBox());
        boolean freeFarAway = fr.clubcitrouille.lanterne.core.Solid.noneIn(
                new net.minecraft.world.phys.AABB(400d, y, 400d, 402d, y + 2d, 402d));
        boat.discard();

        if (!freeOnBoat && freeFarAway) {
            Lanterne.LOG.info("[OBJETS] Bateau — le raccourci de collision est bien refusé sur la boîte "
                    + "du bateau, et autorisé ailleurs. Le mécanisme distingue les deux.");
            return;
        }
        if (freeOnBoat) {
            Lanterne.LOG.warn("[OBJETS] VERDICT : LE RACCOURCI DE COLLISION IGNORE UN BATEAU — les "
                    + "créatures le traverseront. C'est le défaut le plus grave que ce module puisse "
                    + "avoir, et il ne se verrait qu'en jeu.");
        }
        if (!freeFarAway) {
            Lanterne.LOG.warn("[OBJETS] Le raccourci est refusé même loin de tout bloquant : sûr, mais "
                    + "inutile — le module ne rapporte alors rien.");
        }
    }

    private static void report() {
        Lanterne.LOG.info("[OBJETS] ── Ce que devient un objet posé par terre ──");

        int mismatches = 0;
        int worst = 0;
        for (int i = 0; i < CROWD; i++) {
            if (GONE[0][i] == 0 && GONE[1][i] == 0) {
                continue;
            }
            int gap = GONE[0][i] - GONE[1][i];
            if (gap != 0) {
                mismatches++;
                worst = Math.max(worst, Math.abs(gap));
            }
        }

        // Un exemple concret vaut mieux qu'un pourcentage : on montre le premier objet, et l'on résume
        // les autres. Un rapport de quarante lignes ne se lit pas.
        if (!DOOMED.isEmpty()) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[OBJETS] Disparition — durée de vie demandée %d ticks · "
                    + "premier objet : sans %d, avec %d", LIFE, GONE[1][0], GONE[0][0]));
        }
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[OBJETS] Disparition — %d objet(s) décalé(s) sur %d · écart maximal %d tick(s)",
                mismatches, CROWD, worst));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[OBJETS] Trémie — aspiré sans : %d · avec : %d", SUCKED[1], SUCKED[0]));
        // <h2>Un volet qui ne mesure rien, et qui le dit</h2>
        //
        // Le ramassage a lieu dans {@code Player.aiStep} : le joueur cherche les entités de sa boîte
        // élargie et les touche. Or une doublure n'a pas de client réel, et son {@code aiStep} dépend
        // de la réception de paquets de mouvement qu'aucun client n'envoie.
        //
        // L'épreuve a donc rendu « non » des deux côtés. Ce n'est pas un défaut du mod : c'est une
        // limite de l'outillage. Afficher « non · non » laisserait croire que le ramassage est cassé
        // alors qu'il n'a simplement pas été éprouvé — et ce projet a déjà annoncé « exact au tick
        // près » en comparant deux échecs identiques.
        if (!PICKED[0] && !PICKED[1]) {
            Lanterne.LOG.info("[OBJETS] Ramassage par le joueur — NON MESURÉ : la doublure n'exécute "
                    + "pas son aiStep faute de client réel, donc elle ne ramasse rien, avec ou sans "
                    + "le mod. Ce volet ne prouve rien et ne compte pas dans le verdict.");
        } else {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[OBJETS] Ramassage par le joueur — sans : %s · avec : %s",
                    PICKED[1] ? "oui" : "non", PICKED[0] ? "oui" : "non"));
        }

        // Le refus de conclure quand le témoin lui-même n'a rien fait. L'épreuve de cuisson a déjà
        // annoncé « exact au tick près » en comparant deux fours qui n'avaient cuit ni l'un ni l'autre.
        if (GONE[1][0] == 0) {
            Lanterne.LOG.warn("[OBJETS] VERDICT IMPOSSIBLE : sans le mod, les objets n'ont pas "
                    + "disparu non plus. L'épreuve ne mesure rien — durée de vie mal imposée, ou "
                    + "objets hors du rayon de simulation.");
            return;
        }

        boolean lifeFine = mismatches == 0;
        boolean hopperFine = SUCKED[0] == SUCKED[1];
        // Un volet non mesuré ne peut ni valider ni condamner. Le compter comme réussi serait aussi
        // malhonnête que le compter comme échoué.
        boolean pickFine = (!PICKED[0] && !PICKED[1]) || PICKED[0] == PICKED[1];

        if (lifeFine && hopperFine && pickFine) {
            // Le verdict n'énumère que ce qui a été vérifié. La version précédente ajoutait « et les
            // joueurs les ramassent », alors que ce volet n'était pas mesuré — une phrase vraie par
            // raisonnement, fausse comme constat, et c'est exactement la nuance que ce projet paie
            // cher à chaque fois qu'il la laisse passer.
            Lanterne.LOG.info("[OBJETS] VERDICT : le sommeil ne change rien de ce qui a été mesuré. "
                    + "Les objets disparaissent au tick exact et les trémies les aspirent à "
                    + "l'identique. Le ramassage par un joueur reste à éprouver avec un vrai client.");
            return;
        }
        if (!lifeFine) {
            Lanterne.LOG.warn("[OBJETS] VERDICT : LA DISPARITION EST DÉCALÉE — le sol s'accumulera. "
                    + "C'est une fuite de mémoire déguisée en optimisation.");
        }
        if (!hopperFine) {
            Lanterne.LOG.warn("[OBJETS] VERDICT : LA TRÉMIE N'ASPIRE PLUS PAREIL — toutes les fermes "
                    + "automatiques en dépendent.");
        }
        if (!pickFine) {
            Lanterne.LOG.warn("[OBJETS] VERDICT : LE RAMASSAGE A CHANGÉ — c'est le défaut le plus "
                    + "visible qu'un joueur puisse rencontrer.");
        }
    }
}
