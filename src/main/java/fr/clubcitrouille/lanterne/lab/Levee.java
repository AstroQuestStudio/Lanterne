package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Digue;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'épreuve de la digue : combien coûte un chargement synchrone, et le refuser casse-t-il quelque
 * chose ?
 *
 * <h2>Pourquoi cette épreuve ne mesure pas une moyenne de temps de tick</h2>
 *
 * <p>Tous les bancs de ce dépôt comparent des <b>médianes</b> de temps de tick, et c'est la bonne
 * grandeur pour un module qui allège un travail répété vingt fois par seconde. Ce module-ci n'en est
 * pas un. Il supprime un <b>événement rare et énorme</b> : un chargement de chunk au milieu du tick.
 * Noyé dans cinq cents ticks, un gel de deux cents millisecondes déplace la moyenne de quatre
 * dixièmes — le bruit du laboratoire est plus grand que lui, et l'on conclurait « aucun effet » sur
 * un module qui supprime précisément ce que le joueur voit.
 *
 * <p>La grandeur juste ici est le <b>pire tick</b>, et le nombre de ticks au-dessus de cinquante
 * millisecondes. C'est la définition opérationnelle d'un à-coup.
 *
 * <h2>Les trois nombres, et le premier est mesuré et non supposé</h2>
 *
 * <p>Ce dépôt a une règle : la valeur d'un raccourci est le produit de son <b>taux de
 * déclenchement</b>, de ce qu'il <b>épargne</b> à chaque fois, et du <b>nombre d'appels</b>. Trois
 * modules sont tombés pour avoir cru au deuxième seul.
 *
 * <p>Le premier tableau de cette épreuve mesure l'épargne : le temps réel, sur cette machine, d'un
 * {@code level.getChunk(x, z)} qui doit générer, contre celui d'un {@code getChunkNow} qui refuse.
 * Personne n'a besoin de croire le « deux cents millisecondes » de la littérature : le chiffre est
 * relevé ici, sur le générateur de ce monde.
 *
 * <p>Le second tableau mesure le taux, dans une scène où le chargement a réellement lieu.
 *
 * <h2>Ce que l'épreuve refuse de faire</h2>
 *
 * <p>Elle renonce à conclure si le compteur de refus vaut zéro : cela voudrait dire que la scène
 * n'a produit aucun chargement synchrone, et que les deux bras ont mesuré le même code. Quatre
 * épreuves de ce dépôt ont rendu « conforme » sur une scène vide.
 *
 * <h2>L'interrupteur qui doit la faire échouer</h2>
 *
 * <p>{@code LANTERNE_BREAK_DIGUE=1} retire la garde de mémoire de {@code BeeHiveMixin} et rétablit
 * le comportement de {@code ServerCore}. Le troisième tableau — la conformité — <b>doit</b> alors
 * annoncer une abeille qui a oublié sa ruche. Une épreuve qui ne peut pas échouer ne prouve rien.
 */
public final class Levee {
    /** Chunks vierges générés pour chronométrer un chargement synchrone. */
    private static final int PRICED = 16;

    /** Où générer : loin du spawn et loin des arènes, pour que rien ne soit déjà là. */
    private static final int PRICE_ORIGIN = 400_000;

    /**
     * Abeilles par arène, réparties sur quatre côtés.
     *
     * <h2>La géométrie n'est pas décorative : la première version a mesuré zéro</h2>
     *
     * <p>Un seul chunk est forcé, et ce n'est pas de l'économie. Un ticket {@code FORCED} vaut le
     * niveau <b>31</b> ({@code ChunkMap.FORCED_TICKET_LEVEL}), et le niveau monte d'un par chunk
     * d'écart ; or {@code FULL} est le niveau <b>33</b>. Un chunk forcé rend donc ses voisins
     * <em>chargés</em> jusqu'à <b>deux chunks</b> de distance.
     *
     * <p>La première version de cette épreuve forçait un carré de trois sur trois et posait les
     * ruches à quarante blocs, soit deux chunks et demi du centre — c'est-à-dire <b>à l'intérieur de
     * l'anneau que le forçage venait de charger</b>. Elle a relevé zéro refus des deux côtés, et a
     * eu raison de refuser de conclure.
     *
     * <p>Un seul chunk forcé, des ruches à quarante-six blocs droit sur un axe : la ruche tombe au
     * troisième chunk, hors de portée du forçage, et sous les quarante-huit blocs de
     * {@code isTooFarAway}. C'est l'unique fenêtre où la scène existe, et elle est étroite.
     */
    private static final int BEES = 48;

    /** Distance abeille-ruche, en blocs. Sous les quarante-huit de {@code isTooFarAway}. */
    private static final int HIVE_REACH = 46;

    /** Ticks relevés par bras. Six secondes : trois passages du test des vingt ticks. */
    private static final int WINDOW = 120;

    /** Ticks laissés à l'arène avant de commencer à relever. */
    private static final int SETTLE = 40;

    /** Ticks accordés pour qu'un chunk déforcé disparaisse réellement de la mémoire. */
    private static final int PATIENCE = 600;

    /** Où poser la scène de conformité : loin du spawn, dont les chunks ne partent jamais. */
    private static final int WITNESS_ORIGIN = 60_000;

    /** Ticks que le témoin doit avoir réellement vécus : trois passages du test des vingt ticks. */
    private static final int WITNESS_TICKS = 60;

    /**
     * Les arènes, et pourquoi elles alternent.
     *
     * <p>Chaque bras doit trouver du terrain <b>vierge</b> : une fois un chunk chargé, il le reste,
     * et le bras suivant ne mesurerait plus rien. Les arènes sont donc distinctes et éloignées.
     *
     * <p>Mais deux endroits d'un monde n'ont ni le même relief ni le même biome, et générer une
     * jungle coûte plus cher qu'un océan. L'ordre est donc <b>alterné</b> — avec, sans, sans, avec —
     * de sorte qu'un biais de terrain qui favoriserait le premier bras défavorise le dernier. On
     * publie les deux médianes.
     */
    private static final int[][] ARENAS = {
        {60_000, 100_000},
        {100_000, 100_000}, {140_000, 100_000}, {180_000, 100_000}, {220_000, 100_000},
    };

    private static final boolean[] GUARDED = {true, true, false, false, true};

    /**
     * La première arène est jetée, et il a fallu une mesure pour le savoir.
     *
     * <p>Au premier passage complet, les médianes relevées étaient 1,66 · 0,87 · 0,66 · 0,54 ms
     * dans l'ordre des arènes. La décroissance ne suit pas les bras — elle suit l'<b>ordre</b> :
     * c'est le compilateur à la volée qui finit de chauffer. Publier la première arène revenait à
     * porter le prix du démarrage au débit du module qui se trouvait y être allumé.
     *
     * <p>Une arène d'échauffement est donc tournée puis jetée. C'est le même échauffement que
     * {@code Mason.measure}, qui l'avait appris avant.
     */
    private static final int WARMUP_ARENAS = 1;

    private enum Step { OFF, PRICE, CONFORM_BUILD, CONFORM_UNLOAD, CONFORM_WATCH,
        ARENA_BUILD, ARENA_SETTLE, ARENA_WATCH, DONE }

    private static Step step = Step.OFF;
    private static MinecraftServer host;
    private static int waiting;
    private static int patience;

    // ---- la conformité
    private static Bee witness;
    private static BlockPos witnessHive;
    private static BlockPos witnessSeat;
    private static int witnessChunkX;
    private static int witnessChunkZ;
    private static boolean conformOk;
    private static String conformSays = "non éprouvée";

    // ---- les arènes
    private static boolean alone;
    private static int arena;
    private static int sample;
    private static final long[] TICKS = new long[WINDOW];
    private static long tickStarted;
    private static final List<Double> MEDIANS = new ArrayList<>();
    private static final List<Double> WORSTS = new ArrayList<>();
    private static final List<Integer> OVERRUNS = new ArrayList<>();
    private static final List<Long> REFUSALS = new ArrayList<>();
    private static final List<Boolean> KEPT = new ArrayList<>();
    private static final List<Bee> FLOCK = new ArrayList<>();

    private Levee() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        Settings.setEnabled(true);
        // Les distances doivent rester fixes : la marée les changerait entre deux arènes, et l'on
        // comparerait deux mondes de tailles différentes en croyant comparer deux versions du code.
        Settings.setTide(false);
        arena = 0;
        MEDIANS.clear();
        WORSTS.clear();
        OVERRUNS.clear();
        REFUSALS.clear();
        KEPT.clear();
        step = Step.PRICE;
        alone = "digue".equals(Settings.describe());
        Lanterne.LOG.info("[DIGUE] Épreuve armée. Trois tableaux : le prix d'un chargement "
                + "synchrone, le taux dans une scène qui en produit, et la mémoire de la ruche.");
        Lanterne.LOG.info("[DIGUE] Modules actifs dans le bras allumé : {}", Settings.describe());
        if (!alone) {
            // <h2>Pourquoi ce contrôle, et pourquoi il est plus important ici qu'ailleurs</h2>
            //
            // Le bras éteint coupe l'interrupteur maître, donc TOUS les modules. Si le niveau de
            // détail est allumé, le bras allumé annule le tick des abeilles — elles n'appellent
            // alors jamais getBeehiveBlockEntity, il n'y a aucun chargement à refuser, et l'écart
            // publié appartiendrait au niveau de détail, pas à la digue.
            //
            // C'est la faute que ce dépôt a commise trois fois, sous trois noms. Ici elle est
            // particulièrement sournoise parce qu'elle produit un GROS gain, et dans le bon sens.
            Lanterne.LOG.warn("[DIGUE] ATTRIBUTION IMPOSSIBLE : d'autres modules sont allumés. Le "
                    + "bras éteint les coupe tous, donc l'écart mesuré ne sera pas celui de la "
                    + "digue. Relance avec LANTERNE_MODULES=digue.");
        }
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        switch (step) {
            case PRICE -> price(server);
            case CONFORM_BUILD -> conformBuild(server);
            case CONFORM_UNLOAD -> conformUnload(server);
            case CONFORM_WATCH -> conformWatch(server);
            case ARENA_BUILD -> arenaBuild(server);
            case ARENA_SETTLE -> {
                if (--waiting <= 0) {
                    Digue.reset();
                    sample = 0;
                    step = Step.ARENA_WATCH;
                }
            }
            case ARENA_WATCH -> arenaWatch(server);
            default -> { }
        }
    }

    /**
     * Ouvre le chronomètre du tick.
     *
     * <p>Appelé depuis {@code ServerTickEvent.Pre}, et fermé depuis {@code Post} : c'est la durée
     * du tick <b>entier</b> qu'on veut, parce qu'un chargement synchrone peut survenir n'importe où
     * dedans — dans le tour des entités pour l'abeille, dans l'inventaire du joueur pour la carte.
     *
     * <p>On ne se sert pas de {@code getCurrentSmoothedTickTime} : il lisse et écarte les valeurs
     * extrêmes, ce qui est exactement ce qu'il faut pour piloter la marée et exactement ce qu'il ne
     * faut pas pour compter des à-coups.
     */
    public static void beginTick() {
        if (step == Step.ARENA_WATCH) {
            tickStarted = System.nanoTime();
        }
    }

    // ------------------------------------------------------------------ premier tableau : le prix

    /**
     * Le prix d'un chargement synchrone, relevé et non supposé.
     *
     * <p>On chronomètre {@code level.getChunk(x, z)} — la surcharge qui vaut
     * {@code getChunk(x, z, FULL, true)}, celle qu'emploie {@code MapItem} — sur des chunks que le
     * monde n'a jamais vus. C'est exactement l'appel que la digue remplace, dans les conditions où
     * elle le remplace.
     *
     * <p>Puis on chronomètre le refus lui-même, sur d'autres chunks vierges : {@code getChunkNow} ne
     * doit rien faire d'autre qu'une lecture de table.
     */
    private static void price(MinecraftServer server) {
        ServerLevel level = server.overworld();
        List<Long> loads = new ArrayList<>();
        List<Long> refusals = new ArrayList<>();

        for (int i = 0; i < PRICED; i++) {
            int chunkX = PRICE_ORIGIN / 16 + i * 8;
            int chunkZ = PRICE_ORIGIN / 16;
            long started = System.nanoTime();
            level.getChunk(chunkX, chunkZ);
            loads.add(System.nanoTime() - started);
        }
        for (int i = 0; i < PRICED; i++) {
            int chunkX = PRICE_ORIGIN / 16 + i * 8;
            int chunkZ = PRICE_ORIGIN / 16 + 64;
            long started = System.nanoTime();
            Digue.now(level, chunkX, chunkZ);
            refusals.add(System.nanoTime() - started);
        }

        double loadMedian = median(loads);
        double loadWorst = worst(loads);
        double refuseMedian = median(refusals);

        Lanterne.LOG.info("[DIGUE] ── Tableau 1 · le prix d'un chargement synchrone ──");
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[DIGUE] getChunk(x, z) sur %d chunks vierges : médiane %.1f ms, pire %.1f ms",
                PRICED, loadMedian / 1e6, loadWorst / 1e6));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[DIGUE] getChunkNow(x, z) refusé sur %d chunks vierges : médiane %.4f ms",
                PRICED, refuseMedian / 1e6));
        if (loadMedian <= 0d) {
            Lanterne.LOG.warn("[DIGUE] REFUS sur ce tableau : médiane nulle, le chronomètre n'a "
                    + "rien vu. Les chunks étaient probablement déjà là.");
        } else {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[DIGUE] Épargne par refus : %.1f ms, soit %.1f tick(s) de cinquante "
                            + "millisecondes. C'est le deuxième des trois nombres ; le taux suit.",
                    loadMedian / 1e6, loadMedian / 5e7));
        }

        step = Step.CONFORM_BUILD;
    }

    // ------------------------------------------------------------------ troisième tableau : la mémoire

    /**
     * La scène de conformité : une ruche <b>réelle</b>, dans un chunk qu'on fait disparaître.
     *
     * <p>Le point à ne pas rater : la ruche doit <em>exister</em>. Si le chunk ne contenait rien,
     * l'abeille aurait raison de l'oublier une fois le chunk revenu, et l'épreuve ne distinguerait
     * plus l'oubli fautif de l'oubli légitime.
     *
     * <p>On force donc le chunk le temps d'y poser la ruche, puis on le déforce et l'on <b>attend
     * qu'il disparaisse</b> — on n'attend pas un délai, on attend l'état. C'est la leçon de
     * l'épreuve du duel dans le Nether.
     */
    private static void conformBuild(MinecraftServer server) {
        ServerLevel level = server.overworld();
        level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);

        int homeChunkX = WITNESS_ORIGIN >> 4;
        int homeChunkZ = WITNESS_ORIGIN >> 4;
        level.setChunkForced(homeChunkX, homeChunkZ, true);

        // L'abeille au bord est du chunk forcé, la ruche quarante-six blocs plus à l'est : trois
        // chunks, donc hors de l'anneau chargé par le forçage. Voir BEES pour l'arithmétique.
        witnessSeat = new BlockPos((homeChunkX << 4) + 15, 0, (homeChunkZ << 4) + 8);
        BlockPos hiveColumn = witnessSeat.offset(HIVE_REACH, 0, 0);
        witnessChunkX = hiveColumn.getX() >> 4;
        witnessChunkZ = hiveColumn.getZ() >> 4;
        level.setChunkForced(witnessChunkX, witnessChunkZ, true);

        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                hiveColumn.getX(), hiveColumn.getZ());
        witnessHive = new BlockPos(hiveColumn.getX(), ground, hiveColumn.getZ());
        level.setBlock(witnessHive.below(), Blocks.STONE.defaultBlockState(), 2);
        level.setBlock(witnessHive, Blocks.BEEHIVE.defaultBlockState(), 2);
        BlockState laid = level.getBlockState(witnessHive);
        if (!laid.is(Blocks.BEEHIVE)) {
            Lanterne.LOG.error("[DIGUE] ÉPREUVE INVALIDE : la ruche n'a pas été posée en {} — "
                    + "on a trouvé {}. Sans ruche, l'oubli de l'abeille serait légitime et le "
                    + "tableau ne dirait rien.", witnessHive, laid);
            conformOk = false;
            conformSays = "ÉPREUVE INVALIDE : la ruche n'a pas pu être posée.";
            step = Step.ARENA_BUILD;
            return;
        }

        level.setChunkForced(witnessChunkX, witnessChunkZ, false);
        patience = PATIENCE;
        step = Step.CONFORM_UNLOAD;
        Lanterne.LOG.info("[DIGUE] ── Tableau 3 · la mémoire de la ruche ──");
        Lanterne.LOG.info("[DIGUE] Ruche posée en {} (chunk {},{}), chunk déforcé. On attend qu'il "
                + "quitte la mémoire.", witnessHive, witnessChunkX, witnessChunkZ);
    }

    private static void conformUnload(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (Digue.now(level, witnessChunkX, witnessChunkZ) == null) {
            Bee bee = EntityTypes.BEE.create(level, EntitySpawnReason.COMMAND);
            if (bee == null) {
                conformOk = false;
                conformSays = "ÉPREUVE INVALIDE : le jeu a refusé de créer une abeille.";
                step = Step.ARENA_BUILD;
                return;
            }
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    witnessSeat.getX(), witnessSeat.getZ());
            bee.snapTo(witnessSeat.getX() + 0.5d, ground + 2d, witnessSeat.getZ() + 0.5d, 0f, 0f);
            bee.setPersistenceRequired();
            // Sans intelligence : une abeille qui vole quitte le chunk forcé, cesse d'être simulée,
            // et l'épreuve relèverait « elle se souvient » parce qu'elle n'a simplement plus tické.
            bee.setNoAi(true);
            bee.setHivePos(witnessHive);
            if (!level.addFreshEntity(bee)) {
                conformOk = false;
                conformSays = "ÉPREUVE INVALIDE : le monde a refusé d'accueillir l'abeille.";
                step = Step.ARENA_BUILD;
                return;
            }
            witness = bee;
            Digue.reset();
            patience = PATIENCE;
            step = Step.CONFORM_WATCH;
            Lanterne.LOG.info("[DIGUE] Chunk {},{} absent de la mémoire. Abeille posée, ruche à {} "
                    + "blocs. On attend qu'elle ait tické {} fois — aiStep n'éprouve sa ruche "
                    + "qu'un tick sur vingt.",
                    witnessChunkX, witnessChunkZ,
                    (int) Math.sqrt(witness.distanceToSqr(witnessHive.getX() + 0.5d,
                            witnessHive.getY() + 0.5d, witnessHive.getZ() + 0.5d)), WITNESS_TICKS);
            return;
        }
        if (--patience <= 0) {
            conformOk = false;
            conformSays = String.format(Locale.ROOT,
                    "ÉPREUVE INVALIDE : le chunk %d,%d est resté en mémoire après %d ticks de "
                    + "déforçage. Rien ne peut être refusé sur un chunk présent, et un verdict "
                    + "« conforme » ici ne prouverait rien.",
                    witnessChunkX, witnessChunkZ, PATIENCE);
            Lanterne.LOG.error("[DIGUE] {}", conformSays);
            step = Step.ARENA_BUILD;
        }
    }

    private static void conformWatch(MinecraftServer server) {
        if (witness == null || !witness.isAlive()) {
            conformOk = false;
            conformSays = "ÉPREUVE INVALIDE : l'abeille est morte pendant le relevé.";
            Lanterne.LOG.error("[DIGUE] {}", conformSays);
            step = Step.ARENA_BUILD;
            return;
        }
        // <h2>On attend un ÉTAT, jamais un délai — et cette épreuve l'a appris à ses dépens</h2>
        //
        // La première version attendait soixante-dix ticks de serveur, puis concluait. Elle a rendu
        // « CONFORME » deux fois de suite, y compris avec LANTERNE_BREAK_DIGUE=1 posé — c'est-à-dire
        // qu'elle validait le défaut qu'elle était censée trouver.
        //
        // La cause, révélée par le compteur qu'on publie plus bas : l'abeille n'avait tické que
        // QUATORZE fois. Forcer un chunk pose un ticket ; la promotion jusqu'à ENTITY_TICKING prend
        // ensuite des dizaines de ticks, et rien ne tick dedans avant. aiStep n'éprouve la ruche
        // qu'un tick sur vingt : quatorze ticks ne suffisent pas à l'éprouver une seule fois.
        //
        // C'est le même piège, mot pour mot, que Duel.PATIENCE a nommé pour le Nether.
        if (witness.tickCount < WITNESS_TICKS && --patience > 0) {
            return;
        }

        ServerLevel level = server.overworld();
        boolean stillAbsent = Digue.now(level, witnessChunkX, witnessChunkZ) == null;
        boolean remembers = witness.hasHive();

        // Les trois chiffres sans lesquels un « CONFORME » ne vaut rien : l'abeille a-t-elle
        // réellement tické, son chunk la simule-t-il, et la digue a-t-elle réellement refusé
        // quelque chose pour elle ? Une épreuve qui conclut sans les publier conclut sur une scène
        // qu'elle n'a pas vérifiée.
        Lanterne.LOG.info("[DIGUE] Témoin : tickCount={} · chunk simulé={} · refus relevés={}",
                witness.tickCount, level.isPositionEntityTicking(witness.blockPosition()),
                Digue.bees());
        if (witness.tickCount < WITNESS_TICKS) {
            conformOk = false;
            conformSays = String.format(Locale.ROOT,
                    "ÉPREUVE INVALIDE : l'abeille n'a tické que %d fois en %d ticks de serveur. "
                    + "aiStep n'éprouve sa ruche qu'un tick sur vingt : elle n'a donc pas eu "
                    + "l'occasion de l'oublier, et « elle se souvient » ne dit rien.",
                    witness.tickCount, PATIENCE);
            Lanterne.LOG.error("[DIGUE] {}", conformSays);
            witness.discard();
            witness = null;
            level.setChunkForced(WITNESS_ORIGIN >> 4, WITNESS_ORIGIN >> 4, false);
            step = Step.ARENA_BUILD;
            return;
        }
        if (Digue.bees() == 0L) {
            conformOk = false;
            conformSays = "ÉPREUVE INVALIDE : zéro refus relevé pour le témoin. La digue n'a rien "
                    + "eu à refuser — la ruche était donc joignable, et son souvenir ne prouve "
                    + "rien.";
            Lanterne.LOG.error("[DIGUE] {}", conformSays);
            witness.discard();
            witness = null;
            level.setChunkForced(WITNESS_ORIGIN >> 4, WITNESS_ORIGIN >> 4, false);
            step = Step.ARENA_BUILD;
            return;
        }

        if (!stillAbsent) {
            conformOk = false;
            conformSays = String.format(Locale.ROOT,
                    "ÉPREUVE INVALIDE : le chunk %d,%d est revenu en mémoire pendant le relevé. "
                    + "La digue n'avait donc plus rien à refuser, et « elle se souvient » ne dit "
                    + "rien d'elle.", witnessChunkX, witnessChunkZ);
            Lanterne.LOG.error("[DIGUE] {}", conformSays);
        } else if (remembers) {
            conformOk = true;
            conformSays = "CONFORME : la ruche est hors du chargé, l'abeille ne l'a pas oubliée. "
                    + "Refuser un chargement dit « je ne sais pas », pas « elle n'existe pas ».";
            Lanterne.LOG.info("[DIGUE] {}", conformSays);
        } else {
            conformOk = false;
            conformSays = "NON CONFORME : l'abeille a perdu sa ruche alors que celle-ci existe "
                    + "toujours — seul son chunk était absent. C'est le défaut de ServerCore : "
                    + "aiStep efface hivePos dès que isHiveValid() rend faux, et un refus de "
                    + "chargement le fait rendre faux.";
            Lanterne.LOG.error("[DIGUE] {}", conformSays);
        }
        if (Digue.broken()) {
            Lanterne.LOG.info("[DIGUE] (LANTERNE_BREAK_DIGUE=1 était posé : ce tableau DEVAIT "
                    + "annoncer NON CONFORME.)");
        }

        witness.discard();
        witness = null;
        level.setChunkForced(WITNESS_ORIGIN >> 4, WITNESS_ORIGIN >> 4, false);
        step = Step.ARENA_BUILD;
    }

    // ------------------------------------------------------------------ deuxième tableau : le taux

    private static void arenaBuild(MinecraftServer server) {
        if (arena >= ARENAS.length) {
            finish();
            return;
        }
        ServerLevel level = server.overworld();
        int chunkX = ARENAS[arena][0] >> 4;
        int chunkZ = ARENAS[arena][1] >> 4;
        int originX = chunkX << 4;
        int originZ = chunkZ << 4;

        // Un SEUL chunk forcé. Sans forçage, aucun chunk n'est simulé loin d'un joueur et les
        // abeilles ne tickeraient pas du tout — piège dans lequel trois épreuves de ce dépôt sont
        // déjà tombées, voir Duel.build. Avec un carré de trois sur trois, l'anneau chargé
        // engloutit les ruches — piège dans lequel celle-ci est tombée à sa première exécution,
        // voir BEES.
        level.setChunkForced(chunkX, chunkZ, true);
        level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);

        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        FLOCK.clear();
        for (int i = 0; i < BEES; i++) {
            Bee bee = EntityTypes.BEE.create(level, EntitySpawnReason.COMMAND);
            if (bee == null) {
                continue;
            }
            int[] side = sides[i % sides.length];
            int along = i / sides.length;
            // L'abeille au bord du chunk forcé, du côté de sa ruche ; la ruche droit devant.
            int seatX = originX + (side[0] > 0 ? 15 : side[0] < 0 ? 0 : along);
            int seatZ = originZ + (side[1] > 0 ? 15 : side[1] < 0 ? 0 : along);
            if (side[0] != 0) {
                seatZ = originZ + along;
            }
            int hiveX = seatX + side[0] * HIVE_REACH;
            int hiveZ = seatZ + side[1] * HIVE_REACH;
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, seatX, seatZ);
            bee.snapTo(seatX + 0.5d, ground + 2d, seatZ + 0.5d, 0f, 0f);
            bee.setPersistenceRequired();
            bee.setNoAi(true);
            bee.setHivePos(new BlockPos(hiveX, ground, hiveZ));
            if (level.addFreshEntity(bee)) {
                FLOCK.add(bee);
            }
        }

        Settings.setEnabled(GUARDED[arena]);
        waiting = SETTLE;
        step = Step.ARENA_SETTLE;
        Lanterne.LOG.info("[DIGUE] Arène {} · chunk forcé {},{} · {} abeille(s), ruches à {} blocs "
                + "droit sur un axe, donc au troisième chunk, jamais généré · digue {}.",
                arena + 1, chunkX, chunkZ, FLOCK.size(), HIVE_REACH,
                GUARDED[arena] ? "ARMÉE" : "ÉTEINTE");
    }

    private static void arenaWatch(MinecraftServer server) {
        if (sample < WINDOW) {
            // beginTick a posé le repère ; on ferme ici, à la fin du tick du serveur.
            TICKS[sample] = System.nanoTime() - tickStarted;
            sample++;
        }
        if (sample < WINDOW) {
            return;
        }

        ServerLevel level = server.overworld();
        List<Long> taken = new ArrayList<>(WINDOW);
        int overruns = 0;
        for (int i = 0; i < WINDOW; i++) {
            taken.add(TICKS[i]);
            if (TICKS[i] > 50_000_000L) {
                overruns++;
            }
        }
        boolean counted = arena >= WARMUP_ARENAS;
        if (counted) {
            MEDIANS.add(median(taken));
            WORSTS.add(worst(taken));
            OVERRUNS.add(overruns);
            REFUSALS.add(Digue.refusals());
            KEPT.add(GUARDED[arena]);
        }

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[DIGUE] Arène %d (digue %s%s) : médiane %.2f ms · pire tick %.1f ms · %d tick(s) "
                        + "au-dessus de 50 ms · %d refus (%s)",
                arena + 1, GUARDED[arena] ? "armée" : "éteinte", counted ? "" : ", ÉCHAUFFEMENT JETÉ",
                median(taken) / 1e6, worst(taken) / 1e6,
                overruns, Digue.refusals(), Digue.describe()));

        for (Bee bee : FLOCK) {
            bee.discard();
        }
        FLOCK.clear();
        level.setChunkForced(ARENAS[arena][0] >> 4, ARENAS[arena][1] >> 4, false);
        Settings.setEnabled(true);
        arena++;
        step = Step.ARENA_BUILD;
    }

    // ------------------------------------------------------------------ le verdict

    private static void finish() {
        step = Step.DONE;
        Lanterne.LOG.info("[DIGUE] ── Tableau 2 · le taux, et ce qu'il fait au pire tick ──");

        double guardedWorst = 0d;
        double bareWorst = 0d;
        double guardedMedian = 0d;
        double bareMedian = 0d;
        int guardedOver = 0;
        int bareOver = 0;
        long refused = 0L;
        int guardedCount = 0;
        int bareCount = 0;
        for (int i = 0; i < MEDIANS.size(); i++) {
            if (KEPT.get(i)) {
                guardedWorst += WORSTS.get(i);
                guardedMedian += MEDIANS.get(i);
                guardedOver += OVERRUNS.get(i);
                refused += REFUSALS.get(i);
                guardedCount++;
            } else {
                bareWorst += WORSTS.get(i);
                bareMedian += MEDIANS.get(i);
                bareOver += OVERRUNS.get(i);
                bareCount++;
            }
        }
        if (guardedCount == 0 || bareCount == 0) {
            Lanterne.LOG.error("[DIGUE] REFUS : un des deux bras n'a pas tourné.");
            halt();
            return;
        }
        guardedWorst /= guardedCount;
        bareWorst /= bareCount;
        guardedMedian /= guardedCount;
        bareMedian /= bareCount;

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[DIGUE] Digue ÉTEINTE : médiane %.2f ms · pire tick %.1f ms · %d tick(s) > 50 ms",
                bareMedian / 1e6, bareWorst / 1e6, bareOver));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[DIGUE] Digue ARMÉE   : médiane %.2f ms · pire tick %.1f ms · %d tick(s) > 50 ms "
                        + "· %d refus",
                guardedMedian / 1e6, guardedWorst / 1e6, guardedOver, refused));

        if (!alone) {
            Lanterne.LOG.error("[DIGUE] REFUS DE CONCLURE : l'épreuve n'a pas tourné avec la digue "
                    + "seule ({} refus relevés). Les deux bras diffèrent par plus que ce module — "
                    + "l'écart ci-dessus ne lui appartient pas. Relance avec "
                    + "LANTERNE_MODULES=digue.", refused);
        } else if (refused == 0L) {
            Lanterne.LOG.error("[DIGUE] REFUS DE CONCLURE : zéro refus dans le bras armé. La scène "
                    + "n'a produit aucun chargement synchrone — les deux bras ont donc mesuré le "
                    + "même code, et l'écart ci-dessus est du bruit. Ne rien publier de ce "
                    + "tableau.");
        } else if (bareWorst <= 0d || guardedWorst <= 0d) {
            Lanterne.LOG.error("[DIGUE] REFUS DE CONCLURE : pire tick nul d'un côté.");
        } else {
            double ratio = bareWorst / guardedWorst;
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[DIGUE] Sur le PIRE TICK — la seule grandeur qu'un joueur perçoit — : ×%.2f. "
                            + "Sur la médiane : ×%.2f.",
                    ratio, guardedMedian <= 0d ? 0d : bareMedian / guardedMedian));
            if (ratio <= 1.08d) {
                Lanterne.LOG.warn("[DIGUE] Le pire tick ne baisse pas, alors que {} refus ont eu "
                        + "lieu. Deux lectures possibles, et il faut trancher avant de publier : "
                        + "ou bien le chargement refusé ne coûtait rien dans cette scène, ou bien "
                        + "le pire tick est dominé par autre chose que lui.", refused);
            }
        }

        Lanterne.LOG.info("[DIGUE] ── Verdict ──");
        Lanterne.LOG.info("[DIGUE] Mémoire de la ruche : {}", conformSays);
        if (!conformOk) {
            Lanterne.LOG.error("[DIGUE] La conformité a échoué. Rappel : "
                    + "LANTERNE_BREAK_DIGUE=1 doit la faire échouer — si l'interrupteur n'était "
                    + "PAS posé, c'est le module qu'il faut réparer, pas l'épreuve.");
        } else {
            Lanterne.LOG.info("[DIGUE] Rappel : LANTERNE_BREAK_DIGUE=1 retire la garde de mémoire. "
                    + "Cette épreuve DOIT alors échouer, sans quoi c'est elle qu'il faut réparer.");
        }
        halt();
    }

    private static void halt() {
        if (host != null) {
            host.halt(false);
        }
    }

    private static double median(List<Long> values) {
        if (values.isEmpty()) {
            return 0d;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2d
                : sorted.get(middle);
    }

    private static double worst(List<Long> values) {
        long top = 0L;
        for (long value : values) {
            top = Math.max(top, value);
        }
        return top;
    }
}
