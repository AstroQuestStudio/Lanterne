package fr.clubcitrouille.lanterne.lab;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'épreuve de l'écoulement : combien coûte une nappe d'eau qui cherche son niveau ?
 *
 * <h2>Pourquoi le banc habituel ne dit rien d'un fluide</h2>
 *
 * <p>{@link fr.clubcitrouille.lanterne.report.Bench} compare deux phases de charge égale sur le
 * <b>même</b> monde, parce que rien, dans une ferme ou un troupeau, ne change sous les pieds entre
 * les deux. L'eau, elle, change sous ses propres pieds : posée en source, elle passe quelques
 * dizaines de ticks à occuper le terrain, puis <b>s'arrête</b> — plus aucun voisin à conquérir, plus
 * aucun tick programmé, plus rien à payer. Un banc qui poserait une source une fois et regarderait
 * tourner le monde pendant vingt-cinq secondes mesurerait, pour l'essentiel, une nappe immobile : la
 * seconde phase, comme la première, verrait un lac figé, et l'écart mesuré ne dirait rien du coût
 * réel de l'écoulement.
 *
 * <p>La seule mesure honnête reconstruit donc, avant <b>chaque</b> relevé, un bassin identique et vide
 * — même geste que {@link Boom}, qui reconstruit un cube de pierre avant chaque tir pour la même
 * raison : ne jamais mesurer une seconde fois le résultat du premier coup.
 *
 * <h2>Le protocole</h2>
 *
 * <p>Un bassin carré de vingt-et-un blocs de côté ({@link #HALF_SIDE}, un demi-côté de dix), encadré de
 * murs de pierre de deux blocs de haut ({@link #WALL_HEIGHT}), posé en l'air à l'écart de tout
 * ({@code x=-512, z=-512}), à une hauteur flottant largement au-dessus du terrain réel — exactement
 * la précaution prise par {@code Boom} et {@code Kitchen} pour ne jamais laisser la génération du monde
 * s'inviter dans la mesure. Le fond et les murs sont posés avec les drapeaux
 * {@code Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE} (260 | 16 = 276), pour la raison que
 * {@code Boom} explique en détail : de la pierre pleine contre de la pierre pleine (ou de l'air contre
 * de l'air) n'a jamais besoin d'une notification de voisinage ni d'un recalcul de forme, et le payer
 * quand même ne ferait que polluer la mesure sans changer le résultat.
 *
 * <p>Ce même drapeau sert aussi à poser la source d'eau — et c'est vérifié, pas supposé : dans
 * {@code LevelChunk.setBlockState}, l'appel à {@code state.onPlace(...)} n'est conditionné qu'à
 * l'absence du drapeau {@code UPDATE_SKIP_ON_PLACE} (512, absent de 276), jamais à
 * {@code UPDATE_NEIGHBORS} ni à {@code UPDATE_KNOWN_SHAPE}. Autrement dit, {@code LiquidBlock.onPlace}
 * — qui programme le premier tick de propagation via {@code level.scheduleTick(pos,
 * fluidState.getType(), fluid.getTickDelay(level))} — se déclenche bel et bien avec 276. Le reste de la
 * propagation ne nous appartient de toute façon plus : {@code FlowingFluid.spreadTo} pose lui-même ses
 * blocs suivants avec le drapeau {@code 3} codé en dur, quel que soit celui qu'on a choisi pour la
 * source initiale.
 *
 * <h2>Le point délicat : que mesure-t-on, au juste ?</h2>
 *
 * <p>Une explosion, {@code Boom} peut la chronométrer directement : {@code level.explode(...)} est un
 * appel synchrone, qui commence et finit dans le même tick, sous les yeux du code qui le déclenche.
 * L'écoulement d'un fluide ne l'est pas. Il vit dans une file de ticks programmés que le monde traite
 * lui-même, avant que quoi que ce soit dans ce mod n'en soit informé :
 * {@code ServerLevel} tient deux files séparées, {@code blockTicks} et {@code fluidTicks} (toutes deux
 * des {@code LevelTicks<>}), et la seconde est vidée à chaque tick par
 * {@code this.fluidTicks.tick(tick, 65536, this::tickFluid)}, qui appelle {@code ServerLevel.tickFluid}
 * puis {@code FluidState.tick}, qui délègue enfin à {@code FlowingFluid.tick(ServerLevel, BlockPos,
 * BlockState, FluidState)} — la méthode qui appelle {@code spread(...)} et, par elle,
 * {@code spreadToSides(...)}. Rien de tout cela ne passe par un point d'entrée que ce fichier pourrait
 * chronométrer de l'intérieur : le calcul a déjà eu lieu quand {@link #tick(MinecraftServer)} est
 * appelé.
 *
 * <p>Deux façons honnêtes de contourner ce problème étaient possibles.
 *
 * <ol>
 *   <li><b>(a) Chronométrer le tick du monde entier</b> pendant que le fluide coule : simple, mais le
 *       nombre obtenu contiendrait aussi les entités, la lumière, la météo — tout le reste du tick, pas
 *       seulement le fluide.</li>
 *   <li><b>(b) Chronométrer deux fenêtres de N ticks identiques</b> — un bassin avec de l'eau et un
 *       bassin sans — et retenir la <em>différence</em>.</li>
 * </ol>
 *
 * <p>Ce fichier retient (b). La raison : ce mod n'a aucune prise sur le tick du monde pris dans son
 * ensemble ({@code Rationing}, {@code TickBudget}, {@code Census} y ajoutent chacun leur propre travail,
 * indépendant de l'eau), et {@link #tick(MinecraftServer)} est appelé une seule fois par tick réel, au
 * même point du cycle que {@code Boom.tick} et {@code Kitchen.tick} — c'est-à-dire <b>après</b> que le
 * monde a fini son tick (convention de ce mod, câblée ailleurs, hors de ce fichier). La seule fenêtre
 * chronométrable depuis ici est donc « le temps écoulé entre N appels consécutifs de
 * {@code tick(MinecraftServer)} », qui approxime « le temps cumulé de N ticks réels du monde ». Prise
 * une fois, cette fenêtre vaut ce que vaut l'option (a) — tout le tick, pas seulement le fluide. Prise
 * deux fois, sur le même bassin, à la même position, pour le même nombre de ticks, une fois avec de
 * l'eau et une fois sans, la <b>différence</b> retranche tout ce qui ne dépend pas de l'eau : les
 * entités du monde, le travail de {@code Rationing}, le bruit du système, sont censés peser
 * identiquement sur les deux fenêtres, et ne survivre à la soustraction que si quelque chose les a fait
 * varier entre les deux — ce qui serait en soi une découverte, pas un artefact.
 *
 * <p>La limite de la méthode (b), et il faut la dire franchement : elle soustrait deux grands nombres
 * bruités pour retrouver un petit nombre. Si le coût réel de l'écoulement est de l'ordre de quelques
 * dizaines de microsecondes cumulées sur cent ticks, et que la gigue d'un tick ordinaire (ramasse-
 * miettes, ordonnancement du système d'exploitation) vaut plusieurs centaines de microsecondes à elle
 * seule, la différence d'un seul relevé peut très bien être négative sans que rien ne soit faux dans le
 * protocole — seulement que le signal cherché est plus petit que le bruit qui le porte. C'est
 * exactement la situation où la médiane sur plusieurs relevés, et le seuil de cinq pour cent avant de
 * parler de gain, protègent contre un chiffre publié à tort. {@link #report()} le dit sans détour
 * quand c'est le cas plutôt que de forcer un ratio qui ne voudrait rien dire.
 *
 * <h2>Cent ticks, et pourquoi la moitié ne sert déjà plus à rien</h2>
 *
 * <p>Une source isolée sur un fond plat perd un niveau à chaque pas ({@code WaterFluid.getDropOff}
 * renvoie 1) et ne programme un nouveau tick que tous les cinq ticks ({@code WaterFluid.getTickDelay}
 * renvoie 5) : huit niveaux, cinq ticks chacun, l'essentiel de la nappe est donc formé en une
 * quarantaine de ticks, marge comprise pour les diagonales. Choisir cent ticks — comme le suggère
 * l'énoncé de cette épreuve — laisse délibérément plus de deux fois cette marge : la seconde moitié de
 * la fenêtre « avec eau » ne mesure presque plus rien de neuf, elle mesure un bassin déjà stable, tout
 * comme la fenêtre « sans eau ». Cela ne fausse pas la différence — les deux fenêtres se stabilisent
 * l'une vers l'autre — mais cela dit franchement que l'essentiel du signal est concentré dans le
 * premier tiers de la mesure, et que les deux tiers restants ne font qu'ajouter du bruit sans ajouter
 * de coût.
 *
 * <h2>La conformité : compter l'eau, pas seulement le temps</h2>
 *
 * <p>Un bassin où l'eau finit par recouvrir un peu moins de terrain n'est pas une optimisation, même si
 * le tick est plus rapide — c'est un jeu qui se comporte différemment. {@code Blocks.WATER} est un
 * unique bloc — {@code LiquidBlock} — qui représente à la fois la source et chaque palier d'écoulement
 * via sa propriété {@code LEVEL} ; il suffit donc de compter, à la fin de chaque relevé « avec eau »,
 * les positions du bassin où {@code getBlockState(pos).is(Blocks.WATER)} est vrai. Ce nombre doit être
 * rigoureusement identique, mod actif ou non : {@link #report()} le vérifie en premier, avant même de
 * regarder le temps, parce qu'un gain de vitesse payé par une nappe incomplète est un échec de
 * conformité, pas un résultat à publier.
 *
 * <h2>Chauffe, médiane, verdict</h2>
 *
 * <p>Comme partout dans ce mod, on jette les premiers relevés de chaque moitié ({@link #WARMUP} sur
 * {@link #READINGS}) et on retient la <b>médiane</b>, jamais la moyenne — {@code Bench} explique
 * pourquoi : un relevé perturbé par un ramassage de mémoire ne doit pas peser sur le chiffre publié. Le
 * nombre de relevés est volontairement plus modeste que celui de {@code Boom} : chaque relevé y coûte
 * un tir d'explosion, quasi instantané ; chaque relevé ici coûte deux fenêtres de cent ticks, soit une
 * dizaine de secondes de monde réel — en reprendre quarante par moitié, comme {@code Boom}, ferait
 * approcher le quart d'heure à elle seule (quatre-vingts relevés × dix secondes). Seize relevés par
 * moitié, dont quatre de chauffe, restent une
 * estimation prudente et non une preuve : si le verdict tremble d'une exécution à l'autre, c'est ce
 * chiffre qu'il faut revoir en premier.
 *
 * <h2>Ce qu'il reste sur le terrain</h2>
 *
 * <p>Le dernier relevé laisse un bassin, avec ou sans eau selon l'endroit où il s'est arrêté. On
 * referme le chantier en remplissant tout le volume — fond, murs et intérieur — de pierre pleine,
 * exactement comme {@code Boom} rebouche son cratère : un bloc entier ne surprend personne et ne
 * laisse aucune eau traîner derrière l'épreuve.
 */
public final class Flow {
    /** Coordonnées horizontales du chantier : à l'écart de tout, comme {@code Boom} et {@code Kitchen}
     * le sont chacun de leur côté. */
    private static final int CENTER_X = -512;
    private static final int CENTER_Z = -512;

    /** Dégagement au-dessus du terrain réel, mesuré une fois dans {@link #begin}. Voir {@code Boom}
     * pour le même raisonnement : interroger le terrain plutôt que fixer une hauteur qui pourrait, sur
     * un autre monde, retomber en pleine montagne. */
    private static final int CLEARANCE = 24;

    /** Demi-côté du bassin : un fond de vingt-et-un blocs de côté (2 × 10 + 1), la taille suggérée par
     * l'énoncé de cette épreuve — large marge autour d'une source qui, seule sur un fond plat, ne
     * s'étend de toute façon jamais à plus de sept blocs (voir le Javadoc de classe). */
    private static final int HALF_SIDE = 10;

    /** Hauteur des murs, en blocs au-dessus du fond : deux, comme le suggère l'énoncé — large
     * couverture pour une nappe qui ne dépasse jamais un bloc de haut. */
    private static final int WALL_HEIGHT = 2;

    /** Ticks de propagation laissés à chaque fenêtre (avec ou sans eau) avant de fermer le
     * chronomètre. Voir le Javadoc de classe, section « Cent ticks… », sur la marge que ce chiffre
     * laisse par rapport aux quarante ticks nécessaires à la stabilisation. */
    private static final int FLOW_TICKS = 100;

    /** Relevés par moitié, chauffe comprise. Volontairement plus bas que {@code Boom} : voir le
     * Javadoc de classe, section « Chauffe, médiane, verdict ». */
    private static final int READINGS = 16;
    /** Parmi les {@link #READINGS} relevés, ceux qu'on jette en tête. */
    private static final int WARMUP = 4;

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Niveau 0 de {@code LiquidBlock.LEVEL} : la source, pas un palier d'écoulement — voir
     * {@code LiquidBlock.registerDefaultState} et le Javadoc de classe. */
    private static final BlockState WATER_SOURCE = Blocks.WATER.defaultBlockState();

    /** Drapeaux de pose pour le fond, les murs et la source : voir le Javadoc de classe pour la preuve,
     * lue dans {@code LevelChunk.setBlockState}, que {@code onPlace} se déclenche malgré eux. */
    private static final int BUILD_FLAGS = Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE;

    private enum Step { OFF, RUN_ON, RUN_OFF, DONE }

    /** Étape courante à l'intérieur d'un relevé : reconstruire à sec, mesurer à sec, poser la source,
     * mesurer avec l'eau. Les deux étapes de construction ne durent qu'un appel et ne comptent pas dans
     * la fenêtre chronométrée — exactement comme {@code Boom} exclut la reconstruction du cube de sa
     * mesure de l'explosion. */
    private enum Stage { BUILD_DRY, MEASURE_DRY, PLACE_WATER, MEASURE_WET }

    private static Step step = Step.OFF;
    private static Stage stage = Stage.BUILD_DRY;
    /** Relevé courant dans la moitié en cours, de 0 à {@link #READINGS} exclu. */
    private static int reading;
    /** Ticks écoulés dans la fenêtre {@code MEASURE_DRY} ou {@code MEASURE_WET} en cours. */
    private static int ticksInWindow;
    private static long windowStart;
    /** Durée de la fenêtre à sec du relevé en cours, retenue le temps de calculer la différence. */
    private static long dryElapsed;

    private static int poolFloorY;
    private static MinecraftServer host;

    /** Différence (avec eau moins à sec), en nanosecondes : le coût imputable au fluide pour ce
     * relevé. Peut être négative sur un relevé isolé — voir le Javadoc de classe. */
    private static final long[] WITH = new long[READINGS - WARMUP];
    private static final long[] WITHOUT = new long[READINGS - WARMUP];
    /** Blocs d'eau comptés en fin de fenêtre « avec eau » — la mesure de conformité. */
    private static final int[] WITH_WATER = new int[READINGS - WARMUP];
    private static final int[] WITHOUT_WATER = new int[READINGS - WARMUP];

    private Flow() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    /**
     * Ouvre l'épreuve.
     *
     * <p>Comme {@code Boom}, aucun contrôle préalable n'est demandé : le coût d'un écoulement ne dépend
     * d'aucun joueur en ligne, seulement du bassin qu'on lui donne, et ce bassin est reconstruit avant
     * chaque relevé.
     */
    public static void begin(MinecraftServer server) {
        host = server;
        ServerLevel level = server.overworld();
        poolFloorY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                CENTER_X, CENTER_Z) + CLEARANCE;
        // <h2>Un bassin où l'eau ne coulait pas</h2>
        //
        // La première exécution a rendu « blocs d'eau en fin de propagation : 1,0 » — la source, et rien
        // d'autre. Au bout de cent ticks, dans un bassin vide de vingt-et-un blocs de côté.
        //
        // La cause n'est pas dans la construction du bassin, qui était correcte, mais dans un fait qu'on
        // avait oublié de poser : <b>un chunk sans joueur à proximité n'est pas simulé</b>.
        // {@code ServerLevel.tick} ne traite les ticks de fluide en attente que pour les chunks du rayon
        // de simulation ; le bassin, à cinq cents blocs de tout, n'en faisait pas partie. La source
        // était bien posée, son tick d'écoulement bien programmé, et personne ne l'exécutait jamais.
        //
        // L'épreuve des explosions n'a pas ce défaut parce qu'une explosion est <em>impérative</em> :
        // on l'appelle, elle se produit. Un fluide, lui, attend son tour — et il faut donc s'assurer
        // qu'il y a un tour.
        //
        // On force donc le chargement des chunks du chantier, exactement comme le fait la commande
        // « /forceload » du jeu. C'est aussi ce que l'épreuve de sauvegarde avait dû faire, pour la
        // même raison.
        int chunkX = CENTER_X >> 4;
        int chunkZ = CENTER_Z >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced(chunkX + dx, chunkZ + dz, true);
            }
        }
        Lanterne.LOG.info("[FLOW] Neuf chunks forcés autour de ({}, {}) : sans cela, aucun tick de "
                + "fluide n'y est exécuté et l'eau reste immobile.", chunkX, chunkZ);

        step = Step.RUN_ON;
        stage = Stage.BUILD_DRY;
        reading = 0;
        Settings.setEnabled(true);
        Lanterne.LOG.info(
                "[FLOW] Épreuve d'écoulement lancée, mod actif — {} relevés par moitié, dont {} de "
                        + "chauffe jetés, {} ticks par fenêtre, à x={} y={} z={}.",
                READINGS, WARMUP, FLOW_TICKS, CENTER_X, poolFloorY, CENTER_Z);
    }

    /**
     * Fait avancer l'épreuve d'un pas. Appelé à chaque tick du serveur.
     *
     * <p>Un seul pas par appel, comme {@code Boom} : construire, ou avancer d'un tick dans une des deux
     * fenêtres chronométrées, jamais les deux à la fois dans le même appel.
     */
    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();

        switch (stage) {
            case BUILD_DRY -> {
                // Reconstruction hors mesure : voir le Javadoc de classe, section « Le protocole ».
                rebuildEmptyPool(level);
                ticksInWindow = 0;
                windowStart = System.nanoTime();
                stage = Stage.MEASURE_DRY;
            }
            case MEASURE_DRY -> {
                ticksInWindow++;
                if (ticksInWindow >= FLOW_TICKS) {
                    dryElapsed = System.nanoTime() - windowStart;
                    stage = Stage.PLACE_WATER;
                }
            }
            case PLACE_WATER -> {
                // Pose hors mesure, comme la reconstruction : voir le Javadoc de classe sur le
                // drapeau retenu et la preuve que « onPlace » se déclenche malgré lui.
                level.setBlock(waterPos(), WATER_SOURCE, BUILD_FLAGS);
                ticksInWindow = 0;
                windowStart = System.nanoTime();
                stage = Stage.MEASURE_WET;
            }
            case MEASURE_WET -> {
                ticksInWindow++;
                if (ticksInWindow >= FLOW_TICKS) {
                    long wetElapsed = System.nanoTime() - windowStart;
                    finishReading(level, wetElapsed - dryElapsed);
                }
            }
        }
    }

    /** Clôt un relevé : range l'échantillon (sauf pendant la chauffe), compte l'eau restante, et
     * enchaîne sur le relevé suivant ou sur la moitié suivante. */
    private static void finishReading(ServerLevel level, long fluidCost) {
        int waterCount = countWater(level);
        if (reading >= WARMUP) {
            int index = reading - WARMUP;
            if (step == Step.RUN_ON) {
                WITH[index] = fluidCost;
                WITH_WATER[index] = waterCount;
            } else {
                WITHOUT[index] = fluidCost;
                WITHOUT_WATER[index] = waterCount;
            }
        }

        reading++;
        if (reading >= READINGS) {
            advanceHalf(level);
        } else {
            stage = Stage.BUILD_DRY;
        }
    }

    private static void advanceHalf(ServerLevel level) {
        if (step == Step.RUN_ON) {
            step = Step.RUN_OFF;
            reading = 0;
            stage = Stage.BUILD_DRY;
            Settings.setEnabled(false);
            Lanterne.LOG.info("[FLOW] Seconde moitié, mod éteint.");
            return;
        }

        step = Step.DONE;
        Settings.setEnabled(true);
        // On referme le chantier avec un volume plein plutôt qu'un bassin ouvert : voir le Javadoc de
        // classe, section « Ce qu'il reste sur le terrain ».
        sealSite(level);
        report();
        if (host != null) {
            host.halt(false);
        }
    }

    private static BlockPos waterPos() {
        return new BlockPos(CENTER_X, poolFloorY + 1, CENTER_Z);
    }

    /**
     * Vide le bassin — intérieur à l'air, fond et murs de pierre — pour garantir qu'il ne reste
     * aucune eau d'un relevé précédent avant d'en commencer un nouveau.
     *
     * <p>Ordre important : l'air d'abord, sur tout le volume intérieur (murs compris), puis la pierre
     * du fond et des murs par-dessus. Poser la pierre en premier laisserait, le temps d'un relevé, un
     * intérieur qui contiendrait encore l'eau du relevé précédent tant que l'air n'aurait pas été posé
     * après coup.
     */
    private static void rebuildEmptyPool(ServerLevel level) {
        BlockPos airMin = new BlockPos(CENTER_X - HALF_SIDE, poolFloorY + 1, CENTER_Z - HALF_SIDE);
        BlockPos airMax = new BlockPos(
                CENTER_X + HALF_SIDE, poolFloorY + WALL_HEIGHT, CENTER_Z + HALF_SIDE);
        for (BlockPos pos : BlockPos.betweenClosed(airMin, airMax)) {
            level.setBlock(pos, AIR, BUILD_FLAGS);
        }

        BlockPos floorMin = new BlockPos(CENTER_X - HALF_SIDE, poolFloorY, CENTER_Z - HALF_SIDE);
        BlockPos floorMax = new BlockPos(CENTER_X + HALF_SIDE, poolFloorY, CENTER_Z + HALF_SIDE);
        for (BlockPos pos : BlockPos.betweenClosed(floorMin, floorMax)) {
            level.setBlock(pos, STONE, BUILD_FLAGS);
        }

        for (int y = poolFloorY + 1; y <= poolFloorY + WALL_HEIGHT; y++) {
            for (int x = CENTER_X - HALF_SIDE; x <= CENTER_X + HALF_SIDE; x++) {
                level.setBlock(new BlockPos(x, y, CENTER_Z - HALF_SIDE), STONE, BUILD_FLAGS);
                level.setBlock(new BlockPos(x, y, CENTER_Z + HALF_SIDE), STONE, BUILD_FLAGS);
            }
            for (int z = CENTER_Z - HALF_SIDE; z <= CENTER_Z + HALF_SIDE; z++) {
                level.setBlock(new BlockPos(CENTER_X - HALF_SIDE, y, z), STONE, BUILD_FLAGS);
                level.setBlock(new BlockPos(CENTER_X + HALF_SIDE, y, z), STONE, BUILD_FLAGS);
            }
        }
    }

    /** Remplit tout le volume du bassin de pierre pleine — le geste de clôture, voir le Javadoc de
     * classe. */
    private static void sealSite(ServerLevel level) {
        BlockPos min = new BlockPos(CENTER_X - HALF_SIDE, poolFloorY, CENTER_Z - HALF_SIDE);
        BlockPos max = new BlockPos(
                CENTER_X + HALF_SIDE, poolFloorY + WALL_HEIGHT, CENTER_Z + HALF_SIDE);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            level.setBlock(pos, STONE, BUILD_FLAGS);
        }
    }

    /**
     * Compte les blocs d'eau restants dans l'intérieur du bassin (murs exclus) — la mesure de
     * conformité. Voir le Javadoc de classe : {@code Blocks.WATER} couvre à la fois la source et
     * chaque palier d'écoulement, {@code LEVEL} n'étant qu'une propriété du même bloc.
     *
     * <p>Les deux niveaux intérieurs sont parcourus par prudence, bien qu'un fond plat sans creux ne
     * permette à l'eau de tomber nulle part : rien ne garantit ici qu'une variante du mod ne change pas
     * ce raisonnement, et le vérifier coûte sept cent vingt-deux lectures de bloc, négligeables une
     * fois par relevé.
     */
    private static int countWater(ServerLevel level) {
        BlockPos min = new BlockPos(
                CENTER_X - HALF_SIDE + 1, poolFloorY + 1, CENTER_Z - HALF_SIDE + 1);
        BlockPos max = new BlockPos(
                CENTER_X + HALF_SIDE - 1, poolFloorY + WALL_HEIGHT, CENTER_Z + HALF_SIDE - 1);
        int total = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (level.getBlockState(pos).is(Blocks.WATER)) {
                total++;
            }
        }
        return total;
    }

    /**
     * Le verdict — la conformité d'abord, la vitesse ensuite.
     *
     * <p>Voir le Javadoc de classe : une nappe incomplète payée par un tick plus rapide est un échec de
     * conformité, pas une optimisation, et ce verdict le dit avant même de regarder le temps.
     */
    private static void report() {
        double waterWith = medianInt(WITH_WATER);
        double waterWithout = medianInt(WITHOUT_WATER);

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[FLOW] ── Écoulement d'une source d'eau, médiane sur %d relevé(s) utile(s) par "
                        + "moitié, %d ticks par fenêtre ──",
                WITH.length, FLOW_TICKS));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[FLOW] Blocs d'eau en fin de propagation — sans : %.1f · avec : %.1f",
                waterWithout, waterWith));

        boolean conform = Math.abs(waterWith - waterWithout) < 0.5d;
        if (!conform) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[FLOW] VERDICT DE CONFORMITÉ : ÉCHEC — la nappe ne couvre pas la même surface "
                            + "mod actif ou non (écart médian de %.1f bloc(s)). Un gain de vitesse ne "
                            + "compenserait pas ceci : l'eau coule différemment, ce n'est pas une "
                            + "optimisation.",
                    waterWith - waterWithout));
        } else {
            Lanterne.LOG.info(
                    "[FLOW] Conformité : la nappe finale couvre la même surface, mod actif ou non.");
        }

        double with = median(WITH);
        double without = median(WITHOUT);
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[FLOW] Coût imputable au fluide (différence avec-eau moins à-sec) — sans : %.1f µs "
                        + "· avec : %.1f µs",
                without / 1e3, with / 1e3));

        if (with <= 0d || without <= 0d) {
            // Voir le Javadoc de classe, section sur la limite de la méthode par différence : un
            // médian nul ou négatif ne prouve pas l'absence de coût, il dit que ce coût, s'il existe,
            // est resté sous le bruit de la mesure. Publier un ratio ici mentirait par excès de
            // précision.
            Lanterne.LOG.info(
                    "[FLOW] VERDICT DE VITESSE : aucun effet mesurable — au moins une moitié rend "
                            + "un coût médian nul ou négatif, ce qui signifie que le signal cherché "
                            + "est resté sous la gigue ordinaire du tick, pas qu'il n'existe pas.");
        } else {
            double ratio = without / with;
            if (ratio > 1.05d) {
                // <h2>Un gain qu'il faut refuser de croire</h2>
                //
                // Aucun module de ce mod ne touche aux fluides. Un gain sur l'écoulement ne peut donc
                // pas venir du mod : il vient de la méthode, qui soustrait deux grandes durées bruitées
                // pour en tirer un petit signal.
                //
                // Deux exécutions consécutives du protocole corrigé l'ont confirmé : <b>×1,58 puis
                // ×3,33</b>. Le même protocole, le même monde, un facteur deux d'écart. Pendant ce
                // temps, le compte de blocs d'eau — la mesure de conformité — donnait 113 les deux fois.
                //
                // On publie donc ce que le banc sait mesurer, et l'on refuse ce qu'il ne sait pas.
                Lanterne.LOG.warn(String.format(Locale.ROOT,
                        "[FLOW] VERDICT DE VITESSE : REJETÉ (rapport ×%.2f). Aucun module de ce mod ne "
                                + "touche aux fluides : un gain ne peut venir que du protocole, qui "
                                + "soustrait deux fenêtres bruitées. Deux exécutions ont donné ×1,58 "
                                + "puis ×3,33 — chiffre à ne pas citer. Seule la conformité ci-dessus "
                                + "est fiable, et elle est stable.",
                        ratio));
            } else if (ratio < 0.95d) {
                // <h2>Le rejet doit valoir dans les deux sens, et il ne le valait pas</h2>
                //
                // Ce banc refusait les gains — puisqu'aucun module ne touche aux fluides, un gain ne
                // peut venir que du protocole — mais publiait les pertes. L'asymétrie n'avait aucune
                // justification : le même argument vaut à l'identique dans l'autre sens.
                //
                // Un tir de contrôle l'a chiffrée. Mod DÉSACTIVÉ DES DEUX CÔTÉS, c'est-à-dire les deux
                // moitiés mesurant rigoureusement la même chose :
                //
                //     sans : 371,2 µs   ·   avec : 3619,9 µs   →   « PERTE ×0,10 »
                //
                // Dix fois, sur deux moitiés identiques. Le banc précédent avait annoncé ×0,47 pour le
                // mod : ce chiffre ne disait rien de lui, il mesurait la gigue de sa propre méthode.
                //
                // La cause est dans la méthode elle-même, et le Javadoc de classe la nommait déjà : on
                // soustrait deux fenêtres de plusieurs millisecondes pour en tirer quelques centaines
                // de microsecondes. Le signal cherché est cent fois plus petit que les grandeurs dont
                // on le tire.
                Lanterne.LOG.warn(String.format(Locale.ROOT,
                        "[FLOW] VERDICT DE VITESSE : REJETÉ (rapport ×%.2f). Le rejet vaut dans les "
                                + "deux sens : aucun module ne touche aux fluides, donc ni gain ni "
                                + "perte ne peut venir du mod. Un tir de contrôle, mod éteint des deux "
                                + "côtés, a rendu ×0,10 sur deux moitiés identiques — cette méthode "
                                + "soustrait deux fenêtres de millisecondes pour en tirer des "
                                + "microsecondes. Seule la conformité ci-dessus est fiable.",
                        ratio));
            } else {
                Lanterne.LOG.info(
                        "[FLOW] VERDICT DE VITESSE : aucun effet mesurable — l'écart est sous le "
                                + "bruit de fond.");
            }
        }
    }

    /** La médiane d'un échantillon de durées — même raisonnement que {@code Bench.median} et
     * {@code Boom.median} : voir le Javadoc de classe sur le choix de la médiane plutôt que la
     * moyenne. */
    private static double median(long[] values) {
        long[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0
                ? (copy[middle - 1] + copy[middle]) / 2d
                : copy[middle];
    }

    /** La médiane d'un échantillon de comptages — même méthode que {@link #median(long[])}, sur des
     * entiers plutôt que des durées. */
    private static double medianInt(int[] values) {
        int[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0
                ? (copy[middle - 1] + copy[middle]) / 2d
                : copy[middle];
    }
}
