package fr.clubcitrouille.lanterne.core;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;

/**
 * L'enclos : cesser de décider d'aller quelque part quand on a prouvé qu'on n'y va pas.
 *
 * <h2>Le chaînon qui bloquait toute la chaîne</h2>
 *
 * <p>Trois modules de ce mod attendaient que les bêtes s'immobilisent, et aucun n'y parvenait.
 *
 * <ul>
 *   <li>{@link Jam} cesse de résoudre la bousculade d'un amas figé — mais il exige
 *       <b>vingt ticks d'immobilité consécutifs</b>, et n'en trouvait que cent cinquante-deux sur
 *       mille.</li>
 *   <li>Un module de repos posé, écrit puis retiré, court-circuitait la collision d'une bête
 *       immobile — et ne se déclenchait que <b>quatre fois sur cent</b>.</li>
 * </ul>
 *
 * <p>La cause était commune, et en amont des deux : <b>les bêtes décident d'aller se promener</b>.
 * Le compteur d'immobilité se remet à zéro, l'amas ne se déclare jamais figé, la bousculade continue,
 * et plus rien ne peut se poser.
 *
 * <h2>Ce qu'une décision d'errance produit vraiment dans un tas</h2>
 *
 * <p>Une vache au milieu d'un enclos plein décide de marcher vers un point tiré au hasard. Le jeu
 * calcule un chemin, la navigation le suit, et les voisines la repoussent. Elle finit là où elle
 * était. Le résultat observable est <b>exactement le même</b> que si elle n'avait rien décidé — seul
 * le calcul diffère.
 *
 * <h2>Le critère, et pourquoi ce n'est pas la densité</h2>
 *
 * <p>La première idée était de compter les voisines. Elle est fausse : douze bêtes réparties sur un
 * chunk de seize blocs de côté font un compte élevé et un enclos parfaitement libre. On aurait figé
 * un troupeau qui se promenait très bien.
 *
 * <p>Le critère retenu ne suppose rien sur la forme du lieu : <b>cette bête est-elle allée quelque
 * part ?</b> On note sa position, on revient cent ticks plus tard, et l'on regarde. Moins de deux
 * blocs parcourus en cinq secondes, c'est une bête qui a essayé et qui n'y arrive pas — quelle qu'en
 * soit la raison : un enclos, un tas, un couloir, un mod qui l'entrave.
 *
 * <p>Et l'on recommence à chaque fenêtre. Dès que la barrière s'ouvre, la bête parcourt ses deux
 * blocs, le verdict s'inverse, et elle recommence à décider. Aucune surveillance de barrière n'est
 * nécessaire : le fait de bouger est sa propre preuve.
 *
 * <h2>Pourquoi ce point d'accroche-ci peut payer, là où le précédent ne pouvait pas</h2>
 *
 * <p>Le module de repos avait échoué pour une raison qui n'était pas son code : {@code Entity.collide}
 * est appelée des milliers de fois par tick et inlinée par le compilateur, et y poser un mixin lui
 * retire cette inlinisation — sur tous les appels, y compris les quatre-vingt-seize pour cent où le
 * raccourci ne servait pas.
 *
 * <p>{@code RandomStrollGoal.canUse} est d'un tout autre ordre : une fois par bête et par tick au
 * plus, et une fois tous les neuf à vingt-quatre ticks une fois la cadence appliquée. Cinquante à
 * cent appels par tick au lieu de plusieurs milliers. Le droit d'entrée reste négligeable.
 *
 * <h2>Ce qu'on ne fige jamais</h2>
 *
 * <p>Ce qui porte un nom, ce qui est apprivoisé, monté, ou tenu en laisse. Et rien d'autre que les
 * animaux : un monstre qui cesserait d'avancer serait une ferme cassée, pas une ferme optimisée.
 */
public final class Pasture {
    /** Ticks entre deux verdicts. Cent : cinq secondes, largement de quoi traverser un enclos. */
    private static final int WINDOW = 100;

    /**
     * Distance en deçà de laquelle on considère que la bête n'est allée nulle part, en blocs.
     *
     * <p>Deux : plus que le bruit d'une bousculade, moins qu'une promenade. Une vache qui broute en
     * liberté parcourt bien davantage en cinq secondes.
     */
    private static final int ROAM = 2;

    /** Position en coordonnées de bloc, empaquetée. */
    private static final Int2LongOpenHashMap MARK = new Int2LongOpenHashMap();
    /** Tick auquel le verdict de chaque bête doit être refait. */
    private static final Int2LongOpenHashMap DUE = new Int2LongOpenHashMap();
    /** Bêtes dont le dernier verdict est « elle n'ira nulle part ». */
    private static final IntOpenHashSet PENNED = new IntOpenHashSet();

    private static final long NEVER = Long.MIN_VALUE;

    private static long refused;
    private static long lastSweep;

    static {
        MARK.defaultReturnValue(NEVER);
        DUE.defaultReturnValue(NEVER);
    }

    private Pasture() {}

    /**
     * Cette bête a-t-elle prouvé qu'elle n'allait nulle part ?
     *
     * <p>Deux consultations de table dans le cas courant. Le verdict n'est refait qu'une fois par
     * fenêtre de cent ticks, décalée par identifiant pour que mille bêtes ne se jugent pas toutes au
     * même tick — sans quoi la charge ne baisserait pas, elle se concentrerait.
     */
    public static boolean penned(PathfinderMob mob) {
        if (!(mob instanceof Animal) || mob.hasCustomName() || mob.isLeashed()
                || mob.isVehicle() || mob.isPassenger()) {
            return false;
        }
        if (mob instanceof TamableAnimal tamed && tamed.isTame()) {
            return false;
        }

        int id = mob.getId();
        long now = mob.level().getGameTime();
        long due = DUE.get(id);
        if (due != NEVER && now < due) {
            return PENNED.contains(id);
        }

        long here = pack(mob);
        long before = MARK.get(id);
        MARK.put(id, here);
        // Le décalage par identifiant étale les verdicts sur toute la fenêtre.
        DUE.put(id, now + WINDOW + (id % WINDOW));

        if (before == NEVER) {
            // Première observation : on ne juge pas sans point de comparaison. C'est la faute que
            // ferait un critère « position actuelle » — il déclarerait figée toute bête qu'on vient
            // de voir pour la première fois.
            PENNED.remove(id);
            return false;
        }

        boolean stuck = travelled(before, here) < ROAM;
        if (stuck) {
            PENNED.add(id);
        } else {
            PENNED.remove(id);
        }
        return stuck;
    }

    /** Une décision d'errance épargnée, et le chemin qu'elle aurait fait calculer. */
    public static void noteRefusal() {
        refused++;
    }

    private static long pack(PathfinderMob mob) {
        return ((long) mob.getBlockX() << 32) | (mob.getBlockZ() & 0xFFFFFFFFL);
    }

    /** Distance de Tchebychev entre deux positions empaquetées : le plus grand des deux écarts. */
    private static int travelled(long from, long to) {
        int dx = Math.abs((int) (from >> 32) - (int) (to >> 32));
        int dz = Math.abs((int) from - (int) to);
        return Math.max(dx, dz);
    }

    /**
     * Purge les bêtes disparues.
     *
     * <p>Sans elle, les tables enfleraient de tout ce qui meurt ou se décharge — et un mod qui
     * reproche au jeu ses allocations serait mal placé pour en fuir.
     */
    public static void sweep(long gameTime) {
        if (gameTime - lastSweep < 1200L) {
            return;
        }
        lastSweep = gameTime;
        if (MARK.size() > 20_000) {
            MARK.clear();
            DUE.clear();
            PENNED.clear();
        }
    }

    public static long refused() {
        return refused;
    }

    public static int penned() {
        return PENNED.size();
    }

    public static void reset() {
        MARK.clear();
        DUE.clear();
        PENNED.clear();
        refused = 0L;
    }
}
