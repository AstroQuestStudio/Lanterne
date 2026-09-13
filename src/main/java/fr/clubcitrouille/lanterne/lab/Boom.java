package fr.clubcitrouille.lanterne.lab;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'épreuve de la détonation : combien coûte le calcul d'une explosion, et le mod y change-t-il
 * quoi que ce soit ?
 *
 * <h2>Ce que personne, dans ce mod, ne prétend toucher</h2>
 *
 * <p>{@code EntityThrottle} le dit sans détour à propos de la poudre amorcée : espacer son tick ne
 * ferait que déplacer l'instant de la détonation, pas en réduire le coût, et le fuseau n'y est donc
 * pas soumis. C'est une décision juste — mais elle ne dit rien du <em>calcul</em> de l'explosion
 * elle-même, celui qui casse les blocs et cherche les entités à souffler. Aucun module de ce mod ne
 * touche {@code Explosion} ni {@code ServerExplosion} : la prévision est donc qu'un relevé pris ici
 * ne bougera pas d'un cheveu, mod actif ou non. C'est exactement le genre de prévision que ce projet
 * a appris à ne jamais publier sans l'avoir mesurée — trois chiffres faux, racontés dans
 * {@code lab/Preflight.java}, sont partis d'un raisonnement tout aussi solide en apparence.
 *
 * <h2>Pourquoi une charge reconstituée, et non le protocole habituel</h2>
 *
 * <p>Le banc de {@link fr.clubcitrouille.lanterne.report.Bench} compare deux phases de charge égale
 * sur le <b>même</b> monde, parce que rien, dans une ferme ou un troupeau, ne change le terrain sous
 * les pieds pendant la mesure. Une explosion, elle, démolit ce qu'elle touche : la relever deux fois
 * de suite au même endroit reviendrait à tirer un second coup sur un cratère, contre un premier coup
 * qui avait un plein cube de pierre à percer. L'écart mesuré ne dirait alors rien du mod — il dirait
 * la différence entre percer de la pierre et percer du vide.
 *
 * <p>La seule mesure honnête reconstruit donc, avant <b>chaque</b> tir, un cube de pierre identique
 * — mêmes dimensions, même matériau, même position — puis chronomètre la détonation qui suit.
 * Quatre-vingts tirs, quatre-vingts cubes.
 *
 * <h2>Poser vite, sans polluer la mesure</h2>
 *
 * <p>Poser quatre mille neuf cent treize blocs avec {@code setBlockAndUpdate} (drapeaux
 * {@code UPDATE_ALL = 3}) déclencherait, pour chacun, une notification de voisinage
 * ({@code updateNeighborsAt}, conditionnée à {@code updateFlags & 1} dans
 * {@code Level.markAndNotifyBlock}) et, faute du drapeau {@code UPDATE_KNOWN_SHAPE = 16}, un recalcul
 * de forme par rapport aux voisins ({@code updateFlags & 16}, même méthode). Pour de la pierre pleine
 * empilée contre de la pierre pleine, ce travail ne change jamais le résultat — il ne fait que le
 * payer, quatre mille neuf cent treize fois par cube.
 *
 * <p>Chaque bloc est donc posé avec {@code Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE} (260 | 16 =
 * 276) : ni notification de voisinage, ni recalcul de forme, ni paquet client, ni effet de
 * bloc-entité. Ce n'est pas une invention pour l'occasion — c'est très exactement le drapeau que la
 * génération de monde s'envoie à elle-même lors du repeuplement différé
 * ({@code LevelChunk.postProcessGeneration}, qui appelle {@code level.setBlock(blockPos,
 * blockStateNew, 276)}), pour la même raison : poser en masse sans faire payer à chaque bloc une mise
 * à jour dont le résultat est déjà connu d'avance.
 *
 * <h2>Le choix de la détonation</h2>
 *
 * <p>{@code Level} expose cinq surcharges d'{@code explode}. On retient la plus courte — celle à six
 * arguments, {@code explode(Entity, double, double, double, float, ExplosionInteraction)} — parce
 * qu'elle délègue, sans rien y changer, vers les mêmes valeurs par défaut que la TNT réelle : pas de
 * feu, pas de calculateur de dégâts particulier, mêmes particules et même son. On le vérifie à la
 * source : {@code PrimedTnt.explode()} appelle la surcharge à neuf arguments avec ces mêmes
 * {@code false} et {@code null}, sauf lorsqu'un portail a été traversé — un cas qui ne se présente
 * jamais ici.
 *
 * <p>Le rayon est 4.0, celui d'une TNT vanilla ({@code Block.explosionPower}, valeur par défaut du
 * champ dans {@code Block.java}). L'interaction est {@code Level.ExplosionInteraction.TNT}, qui fait
 * détruire les blocs selon la règle de jeu {@code TNT_EXPLOSION_DROP_DECAY} — le comportement d'une
 * vraie charge, et non celui, inerte, de {@code NONE}, ni celui, conditionné à {@code mobGriefing},
 * de {@code MOB}. La source est {@code null} : aucune entité ne déclenche ce tir, et l'interaction
 * {@code TNT} ne consulte de toute façon jamais {@code mobGriefing} — seule l'interaction {@code MOB}
 * le fait.
 *
 * <h2>Un relevé par tick, jamais un paquet de quatre-vingts</h2>
 *
 * <p>L'explosion est un calcul synchrone : contrairement à la cuisson observée par {@code Kitchen},
 * qui s'étale sur deux cents ticks avant de livrer un résultat, une détonation commence et finit dans
 * le même appel. Rien n'empêcherait donc, techniquement, de tirer les quatre-vingts coups dans un
 * seul tick. Rien, sauf que ce mod surveille lui-même la durée de ses ticks ({@code TickBudget},
 * {@code Rationing}) et durcit sa sévérité quand l'un d'eux déborde. Empiler quatre-vingts cubes et
 * quatre-vingts détonations dans un seul tick fabriquerait exactement le pic isolé que
 * {@code Rationing} est conçu pour absorber — et l'on mesurerait alors un mod en train de réagir à
 * l'épreuve, plutôt qu'un mod inerte face à elle. Un relevé par tick garde chaque tick proche d'un
 * tick ordinaire, et évite à l'épreuve de fausser ses propres conditions.
 *
 * <h2>Chauffe, médiane, verdict</h2>
 *
 * <p>Sur les quarante relevés de chaque moitié, les dix premiers sont jetés. {@code Bench} en jette
 * deux cents parce que son tick de référence est quasiment vide et qu'il faut des centaines d'appels
 * avant que le compilateur à la volée ne s'y attarde ; ici, chaque relevé exécute déjà un corps de
 * code autrement plus lourd — quatre mille neuf cent treize écritures de bloc, puis un calcul
 * d'explosion à part entière — de quoi atteindre le seuil de compilation en une poignée d'appels
 * plutôt qu'en centaines. Dix relevés de marge restent une estimation prudente, pas une preuve : si
 * le verdict tremble d'une exécution à l'autre, c'est ce chiffre qu'il faut revoir en premier.
 *
 * <p>Comme partout ailleurs dans ce mod, le verdict retient la <b>médiane</b>, jamais la moyenne —
 * {@link fr.clubcitrouille.lanterne.report.Bench} explique pourquoi : un relevé perturbé par un
 * ramassage de mémoire ou une sauvegarde automatique ne doit pas peser sur le nombre qu'on publie. Et
 * un écart sous cinq pour cent n'est pas annoncé comme un gain : c'est du bruit, et le dire est la
 * seule façon de rester crédible le jour où le chiffre est bon.
 *
 * <h2>Ce qu'il reste sur le terrain</h2>
 *
 * <p>Le dernier tir laisse un cratère, comme tous les précédents — sauf qu'aucun tir suivant ne
 * viendra le reboucher. On referme donc le chantier avec un dernier cube plein plutôt que de remettre
 * de l'air ou de laisser un trou béant : un bloc de pierre entier ne surprend personne, ne fait rien
 * tomber au-dessus de lui, et se repère d'un coup d'œil si quelqu'un doit un jour remettre les pieds
 * là-bas.
 */
public final class Boom {
    /** Coordonnées horizontales du chantier : loin du spawn, pour ne rien risquer d'habité. */
    private static final int CENTER_X = 512;
    private static final int CENTER_Z = 512;
    /**
     * Hauteur de dégagement au-dessus du terrain réel, mesurée une fois dans {@link #begin}.
     *
     * <p>Une hauteur fixe aurait pu, selon la génération, retomber en pleine montagne : on préfère
     * interroger le terrain sous (x, z) — comme le font déjà {@code Kitchen} et
     * {@code LanterneCommand} — et flotter largement au-dessus. Rien, sous le cube, n'est alors
     * jamais touché par l'explosion.
     */
    private static final int CLEARANCE = 24;

    /** Demi-côté du cube reconstruit à chaque tir : huit blocs de part et d'autre du centre, soit
     * un cube de dix-sept blocs de côté — large marge autour d'une explosion de rayon 4. */
    private static final int HALF_SIDE = 8;

    /** Rayon de l'explosion : celui d'une TNT vanilla (voir le Javadoc de classe). */
    private static final float BLAST_RADIUS = 4.0F;

    /** Tirs par moitié, chauffe comprise. */
    private static final int READINGS = 40;
    /** Parmi les {@link #READINGS} tirs, ceux qu'on jette en tête (voir le Javadoc de classe). */
    private static final int WARMUP = 10;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    private enum Step { OFF, RUN_ON, RUN_OFF, DONE }

    private static Step step = Step.OFF;
    /** Tir courant dans la moitié en cours, de 0 à {@link #READINGS} exclu. */
    private static int shot;
    private static int centerY;
    private static MinecraftServer host;

    private static final long[] WITH = new long[READINGS - WARMUP];
    private static final long[] WITHOUT = new long[READINGS - WARMUP];

    private Boom() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    /**
     * Ouvre l'épreuve.
     *
     * <p>Contrairement à {@code Conformance} et {@code Kitchen}, aucun contrôle préalable n'est
     * demandé ici : le coût d'une explosion ne dépend d'aucun joueur en ligne ni d'aucune entité
     * recensée, seulement du cube qu'on lui donne à percer, et ce cube est reconstruit avant chaque
     * tir. Il n'y a donc rien, dans les conditions de mesure, qu'un contrôle puisse invalider.
     */
    public static void begin(MinecraftServer server) {
        host = server;
        ServerLevel level = server.overworld();
        centerY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                CENTER_X, CENTER_Z) + CLEARANCE;
        step = Step.RUN_ON;
        shot = 0;
        Settings.setEnabled(true);
        Lanterne.LOG.info(
                "[BOOM] Épreuve d'explosion lancée, mod actif — {} tirs par moitié, dont {} de "
                        + "chauffe jetés, à x={} y={} z={}.",
                READINGS, WARMUP, CENTER_X, centerY, CENTER_Z);
    }

    /**
     * Fait avancer l'épreuve d'un tir. Appelé à chaque tick du serveur.
     *
     * <p>Un seul tir par appel — voir le Javadoc de classe sur le risque de fabriquer, en en
     * empilant plusieurs dans le même tick, exactement le pic de charge que {@code Rationing} est
     * censé absorber.
     */
    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();
        BlockPos center = new BlockPos(CENTER_X, centerY, CENTER_Z);

        // Reconstruction de la charge : hors mesure, voir le Javadoc de classe.
        rebuildCube(level, center);

        long start = System.nanoTime();
        level.explode(null, CENTER_X + 0.5, centerY + 0.5, CENTER_Z + 0.5, BLAST_RADIUS,
                Level.ExplosionInteraction.TNT);
        long elapsed = System.nanoTime() - start;

        if (shot >= WARMUP) {
            long[] into = step == Step.RUN_ON ? WITH : WITHOUT;
            into[shot - WARMUP] = elapsed;
        }
        shot++;

        if (shot >= READINGS) {
            advance(level, center);
        }
    }

    private static void advance(ServerLevel level, BlockPos center) {
        if (step == Step.RUN_ON) {
            step = Step.RUN_OFF;
            shot = 0;
            Settings.setEnabled(false);
            Lanterne.LOG.info("[BOOM] Seconde moitié, mod éteint.");
            return;
        }

        step = Step.DONE;
        Settings.setEnabled(true);
        // On referme le chantier avec un cube plein plutôt qu'un cratère : voir le Javadoc de
        // classe, section « Ce qu'il reste sur le terrain ».
        rebuildCube(level, center);
        report();
        if (host != null) {
            host.halt(false);
        }
    }

    /**
     * Remplit le cube de pierre autour de {@code center}.
     *
     * <p>Drapeaux {@code Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE} : ni notification de
     * voisinage, ni recalcul de forme, ni paquet client — voir le Javadoc de classe pour la source
     * vanilla de ce choix ({@code LevelChunk.postProcessGeneration} pose ses blocs différés avec
     * exactement ce même drapeau, 276, et pour la même raison).
     */
    private static void rebuildCube(ServerLevel level, BlockPos center) {
        BlockPos min = center.offset(-HALF_SIDE, -HALF_SIDE, -HALF_SIDE);
        BlockPos max = center.offset(HALF_SIDE, HALF_SIDE, HALF_SIDE);
        int flags = Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            level.setBlock(pos, STONE, flags);
        }
    }

    /**
     * Le verdict.
     *
     * <p>Écrit sans complaisance : la prévision de ce banc est « aucun effet », et c'est ce qui sera
     * dit si l'écart tient sous cinq pour cent — pas maquillé en « stable », pas gonflé en « gain
     * marginal ». Voir {@link fr.clubcitrouille.lanterne.report.Bench} sur cette règle.
     */
    private static void report() {
        double with = median(WITH);
        double without = median(WITHOUT);
        double ratio = with <= 0d ? 0d : without / with;

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[BOOM] ── Coût d'une explosion (rayon %.1f), médiane sur %d tirs utiles par "
                        + "moitié ──",
                BLAST_RADIUS, WITH.length));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[BOOM] Sans Lanterne : %.1f µs · Avec Lanterne : %.1f µs",
                without / 1e3, with / 1e3));

        // <h2>Deux modules d'explosion écrits, deux modules retirés</h2>
        //
        // Le premier supprimait un doublon réel — l'état du bloc puis l'état du fluide, demandés
        // séparément dix-sept mille fois par tir. Gain nul : 972 µs contre 1022, le cache du dernier
        // chunk rendait déjà la seconde demande gratuite.
        //
        // Le second réutilisait une position mutable au lieu d'en allouer une par pas de rayon. La
        // redondance était réelle aussi — 37,7 % des positions ne servaient qu'à poser une question,
        // soit vingt-quatre millions d'objets évités pour vingt mille charges. Et le résultat a été
        // bien pire que nul : sur la charge dynamite, 89,28 ms contre 43,88, et deux fois plus
        // d'allocations. Un détournement de mixin posé sur un chemin parcouru dix-sept mille fois par
        // explosion coûte davantage que l'allocation qu'il évite.
        //
        // La leçon vaut au-delà des explosions : sur un chemin très chaud, l'interception EST le coût.
        if (ratio > 1.05d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[BOOM] VERDICT : gain ×%.2f (%.0f %% de temps en moins) — inattendu, puisque "
                            + "rien dans ce mod ne cible l'explosion ; à revérifier avant de le "
                            + "publier.",
                    ratio, (1d - with / without) * 100d));
        } else if (ratio < 0.95d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[BOOM] VERDICT : PERTE ×%.2f — le mod coûterait plus cher ici, ce que rien "
                            + "dans sa conception n'explique ; à revérifier avant de le publier.",
                    ratio));
        } else {
            Lanterne.LOG.info(
                    "[BOOM] VERDICT : aucun effet mesurable — l'écart est sous le bruit de fond, "
                            + "ce qui est la prévision : aucun module de ce mod ne touche au calcul "
                            + "d'une explosion.");
        }
    }

    /** La médiane d'un échantillon — même raisonnement que {@code Bench.median} : voir le Javadoc
     * de classe sur le choix de la médiane plutôt que la moyenne. */
    private static double median(long[] values) {
        long[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0
                ? (copy[middle - 1] + copy[middle]) / 2d
                : copy[middle];
    }
}
