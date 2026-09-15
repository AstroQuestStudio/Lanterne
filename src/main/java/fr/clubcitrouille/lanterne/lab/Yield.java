package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.report.Herd;
import net.minecraft.world.entity.EntityTypes;

/**
 * L'épreuve du rendement : une ferme produit-elle encore autant ?
 *
 * <h2>La question que ce projet ne s'était jamais posée</h2>
 *
 * <p>Ce mod possédait trois épreuves. La conformité vérifie qu'une créature <b>tombe</b> à la même
 * vitesse, la cuisson qu'un four cuit au tick près, le banc que le serveur va plus vite. Aucune ne
 * posait la question qui décide de tout : <b>la ferme produit-elle toujours autant ?</b>
 *
 * <p>L'absence n'était pas un oubli de détail, et la lecture du code du jeu l'a montré. Les compteurs
 * qui font le rendement d'un élevage ne sont pas rangés à part : ils vivent au milieu de
 * l'intelligence, dans les {@code aiStep()} des sous-classes.
 *
 * <pre>
 * Chicken.aiStep    : if (--this.eggTime &lt;= 0) { ... pond un œuf ... }
 * AgeableMob.aiStep : if (this.canAgeUp()) this.setAge(++age);
 * Animal.aiStep     : if (this.inLove &gt; 0) this.inLove--;
 * </pre>
 *
 * <p>Tant que ralentir une créature voulait dire <b>annuler son tick</b>, ces trois horloges
 * s'arrêtaient avec elle. Une poule tickée une fois sur huit pondait huit fois moins ; un veau
 * grandissait huit fois plus lentement. Le mod annonçait un gain de temps réel, mesuré, honnête — et
 * prélevait la différence sur la production, sans que rien ne l'indique.
 *
 * <p>C'est la forme de dégât la plus grave qu'un mod d'optimisation puisse causer : <b>invisible sur le
 * moment</b>. La ferme tourne, les animaux s'agitent, les chiffres du serveur sont bons. Elle rend
 * simplement moins, et on l'attribue à la malchance.
 *
 * <h2>Le protocole</h2>
 *
 * <p>On mesure la production, pas le compteur — un compteur peut être juste alors que rien ne sort.
 *
 * <p><b>Les poules</b> reçoivent une échéance de ponte courte, puis l'on compte les œufs réellement
 * tombés au sol. C'est une épreuve franche : à cadence normale, toutes les poules pondent dans le
 * délai imparti ; ralenties, aucune n'y arrive. Il n'y a pas de demi-résultat à interpréter.
 *
 * <p><b>Les veaux</b> partent tous du même âge négatif, et l'on additionne leur progression. Là, la
 * mesure est continue : elle dit non seulement <em>si</em> la croissance a lieu, mais <b>à quelle
 * fraction</b> de sa vitesse normale — ce qui est exactement le chiffre qu'un éleveur remarquerait.
 *
 * <h2>Loin du joueur, et c'est le point</h2>
 *
 * <p>Le troupeau est posé à cent vingt blocs. Sous les yeux du joueur, la garantie de proximité
 * s'applique et rien n'est ralenti : l'épreuve passerait, et elle ne prouverait rien. C'est là-bas,
 * hors de vue, que le mod agit — et donc là-bas qu'il faut aller vérifier.
 */
public final class Yield {
    /** Distance au joueur : assez loin pour être ralenti, assez près pour rester simulé. */
    private static final int DISTANCE = 120;
    private static final int HENS = 40;
    private static final int CALVES = 40;

    /**
     * Ticks avant la première ponte.
     *
     * <p>Le jeu tire entre six mille et douze mille — cinq à dix minutes. Attendre cela doublerait la
     * durée de l'épreuve pour rien : ce qu'on mesure est le <em>rapport</em> entre deux cadences, et il
     * se lit aussi bien sur une échéance courte. On l'impose donc, et {@code eggTime} est public dans
     * le jeu, ce qui évite d'y accéder par la force.
     */
    private static final int EGG_FUSE = 100;

    /** Âge de départ des veaux. Négatif : c'est ainsi que le jeu marque un petit. */
    private static final int CALF_AGE = -1200;

    /**
     * Durée d'observation.
     *
     * <p>Deux fois et demie l'échéance de ponte : de quoi laisser pondre toute poule dont la cadence
     * n'est pas dégradée d'un facteur supérieur à deux, et voir clairement celles qui n'y arrivent pas.
     */
    private static final int WATCH = 260;

    /**
     * Ticks de grâce avant d'ouvrir le chronomètre.
     *
     * <h2>Cinq pour cent de trop, et pourquoi il fallait les expliquer</h2>
     *
     * <p>Le premier relevé a rendu « croissance 105 % » : les veaux du mod grandissaient plus vite que
     * ceux du témoin. Un verdict flatteur, donc suspect — un mod qui <em>améliore</em> le rendement
     * n'améliore rien, il compte mal.
     *
     * <p>L'explication tenait au protocole, pas au mod. Une créature qu'on vient de créer n'est pas
     * tickée dans l'instant : elle entre d'abord dans la file du monde. Les deux moitiés de l'épreuve
     * ne perdaient pas le même nombre de ticks à l'installation, et l'écart — treize ticks sur deux
     * cent soixante — se retrouvait tel quel dans le résultat.
     *
     * <p>On laisse donc le troupeau s'installer avant de compter. Le chronomètre ne mesure plus la
     * pose des animaux, seulement ce qu'ils produisent.
     */
    private static final int SETTLE = 20;

    private enum Step { OFF, WITH, WITHOUT, DONE }

    private static Step step = Step.OFF;
    private static int elapsed;
    private static MinecraftServer host;

    private static final List<Chicken> HENHOUSE = new ArrayList<>();
    private static final List<Cow> PASTURE = new ArrayList<>();

    /** Œufs pondus, puis croissance cumulée : indice 0 avec le mod, indice 1 sans. */
    private static final int[] EGGS = new int[2];
    private static final int[] GROWTH = new int[2];

    /** Âge de chaque veau à l'ouverture du chronomètre, et non à sa création. */
    private static final int[] START_AGE = new int[CALVES];
    private static int EGGS_AT_START;

    private Yield() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        step = Step.WITH;
        Settings.setEnabled(true);
        stock(server.overworld());
        Lanterne.LOG.info("[RENDEMENT] Épreuve lancée, mod actif — {} poule(s), {} veau(x) à {} blocs.",
                HENHOUSE.size(), PASTURE.size(), DISTANCE);
    }

    /**
     * Pose le troupeau.
     *
     * <p>Les animaux sont répartis sur un arc à distance constante du joueur, et non sur une ligne : à
     * distance égale, ils reçoivent la même cadence, et la mesure porte sur un seul régime au lieu d'un
     * mélange qu'il faudrait démêler ensuite.
     */
    private static void stock(ServerLevel level) {
        HENHOUSE.clear();
        PASTURE.clear();
        elapsed = 0;

        int total = HENS + CALVES;
        for (int i = 0; i < total; i++) {
            // Un arc et non un cercle complet : on reste dans un quadrant, donc dans des chunks dont
            // on sait qu'ils sont chargés par le ticket de la doublure.
            double angle = Math.PI / 2d * i / total;
            double x = Math.cos(angle) * DISTANCE;
            double z = Math.sin(angle) * DISTANCE;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int) Math.floor(x), (int) Math.floor(z));

            if (i < HENS) {
                Chicken hen = EntityTypes.CHICKEN.create(level, EntitySpawnReason.COMMAND);
                if (hen == null) {
                    continue;
                }
                hen.snapTo(x, y, z, 0f, 0f);
                hen.setPersistenceRequired();
                hen.eggTime = EGG_FUSE;
                if (level.addFreshEntity(hen)) {
                    HENHOUSE.add(hen);
                }
            } else {
                Cow calf = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
                if (calf == null) {
                    continue;
                }
                calf.snapTo(x, y, z, 0f, 0f);
                calf.setPersistenceRequired();
                calf.setAge(CALF_AGE);
                if (level.addFreshEntity(calf)) {
                    PASTURE.add(calf);
                }
            }
        }
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();
        elapsed++;
        if (elapsed == SETTLE) {
            // Le troupeau est en place : c'est maintenant que la mesure commence. On note l'âge de
            // départ réel plutôt que celui qu'on avait demandé — entre les deux, quelques ticks ont
            // passé, et ce sont eux qui donnaient cent cinq pour cent.
            for (int i = 0; i < PASTURE.size(); i++) {
                START_AGE[i] = PASTURE.get(i).getAge();
            }
            EGGS_AT_START = countEggs(level);
            return;
        }
        if (elapsed < SETTLE + WATCH) {
            return;
        }

        int slot = step == Step.WITH ? 0 : 1;
        EGGS[slot] = countEggs(level) - EGGS_AT_START;
        GROWTH[slot] = growth();

        if (step == Step.WITH) {
            Lanterne.LOG.info("[RENDEMENT] Première moitié close : {} œuf(s), croissance {}.",
                    EGGS[0], GROWTH[0]);
            step = Step.WITHOUT;
            Settings.setEnabled(false);
            Herd.sweepEntities(level);
            stock(level);
            Lanterne.LOG.info("[RENDEMENT] Seconde moitié, mod éteint.");
            return;
        }

        step = Step.DONE;
        Settings.setEnabled(true);
        report();
        if (host != null) {
            host.halt(false);
        }
    }

    /**
     * Œufs réellement tombés au sol.
     *
     * <p>On compte le produit, et non le compteur. Un {@code eggTime} qui décroît correctement pendant
     * qu'une table de butin échoue ne nourrit personne, et c'est le genre d'écart qu'une épreuve doit
     * attraper au lieu de le supposer absent.
     */
    private static int countEggs(ServerLevel level) {
        int total = 0;
        for (var entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity dropped && dropped.getItem().is(Items.EGG)) {
                total += dropped.getItem().getCount();
            }
        }
        return total;
    }

    /**
     * Progression d'âge <b>par veau vivant</b> depuis l'ouverture du chronomètre.
     *
     * <h2>Une somme qui mesurait le nombre de survivants</h2>
     *
     * <p>Cette méthode additionnait les progressions. Le rapport annonçait alors « croissance 105 % »,
     * un chiffre flatteur donc suspect — un mod qui <em>améliore</em> la croissance n'améliore rien, il
     * compte mal.
     *
     * <p>L'arithmétique a livré la réponse d'un coup : 10 400 pour quarante veaux font exactement 260
     * chacun, et 9 880 pour <b>trente-huit</b> veaux font exactement 260 chacun aussi. La croissance
     * par bête était rigoureusement identique ; deux veaux manquaient simplement à l'appel du côté
     * témoin.
     *
     * <p>Une somme sur une population variable mesure la population autant que le phénomène. On divise
     * donc par le nombre de sujets réellement comptés — ce qui est la seule façon de comparer deux
     * troupeaux qui n'ont pas le même effectif.
     */
    private static int growth() {
        int total = 0;
        int alive = 0;
        for (int i = 0; i < PASTURE.size(); i++) {
            Cow calf = PASTURE.get(i);
            if (calf.isAlive()) {
                total += calf.getAge() - START_AGE[i];
                alive++;
            }
        }
        return alive == 0 ? 0 : total / alive;
    }

    private static void report() {
        Lanterne.LOG.info("[RENDEMENT] ── Ce qu'une ferme a produit en {} ticks, à {} blocs ──",
                WATCH, DISTANCE);
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RENDEMENT] Œufs pondus       — sans %d · avec %d", EGGS[1], EGGS[0]));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RENDEMENT] Croissance par veau — sans %d · avec %d ticks", GROWTH[1], GROWTH[0]));

        double eggShare = EGGS[1] == 0 ? -1d : EGGS[0] * 100d / EGGS[1];
        double growthShare = GROWTH[1] == 0 ? -1d : GROWTH[0] * 100d / GROWTH[1];

        if (eggShare < 0d && growthShare < 0d) {
            // Le cas qui a piégé l'épreuve de cuisson : deux échecs identiques comparés l'un à
            // l'autre donnent un verdict rassurant et faux. On refuse de conclure.
            Lanterne.LOG.warn("[RENDEMENT] VERDICT IMPOSSIBLE : la ferme témoin n'a rien produit non "
                    + "plus. L'épreuve ne mesure rien — ni pontes, ni croissance, même sans le mod.");
            return;
        }

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[RENDEMENT] Rendement conservé — pontes %.0f %% · croissance %.0f %%",
                Math.max(0d, eggShare), Math.max(0d, growthShare)));

        // Le seuil est serré à dessein. Sur une dégradation de rendement, il n'y a pas de compromis
        // acceptable à défendre : l'éleveur a construit sa ferme en comptant sur un débit, et un mod
        // d'optimisation n'a pas à renégocier ce contrat dans son dos.
        boolean eggsFine = eggShare < 0d || eggShare >= 95d;
        boolean growthFine = growthShare < 0d || growthShare >= 95d;

        if (eggsFine && growthFine) {
            Lanterne.LOG.info("[RENDEMENT] VERDICT : rendement intact.");
        } else {
            Lanterne.LOG.warn("[RENDEMENT] VERDICT : LE MOD COÛTE DU RENDEMENT. "
                    + "Les compteurs de production sont dans les aiStep des sous-classes ; "
                    + "sauter le tick entier les arrête.");
        }
    }
}
