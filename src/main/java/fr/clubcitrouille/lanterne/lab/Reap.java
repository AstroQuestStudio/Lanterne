package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.DyeColor;

/**
 * L'épreuve de la moisson : les villageois travaillent-ils encore ?
 *
 * <h2>Ce que l'épreuve des cerveaux ne dit pas</h2>
 *
 * <p>{@link Wits} vérifie que les villageois <b>marchent</b> autant avec le mod que sans, et que
 * leurs mémoires de déplacement se remplissent. C'est un bon signal de vie, mais ce n'est qu'un
 * signal de vie : un villageois qui se promène sans jamais moissonner marcherait tout autant.
 *
 * <p>Or ce qu'on attend d'un villageois sur un serveur, c'est qu'il <b>produise</b>. Récolter, puis
 * replanter, est le comportement le plus exigeant de son répertoire : il demande un métier, un champ
 * à portée, la bonne heure, et un enchaînement de plusieurs comportements qui se passent la main.
 * Si l'accélération du cerveau devait casser quelque chose, c'est ici que cela se verrait d'abord.
 *
 * <h2>Ce qu'on compte, et pourquoi c'est incontestable</h2>
 *
 * <p>On compte les <b>blocs de blé dont l'âge a reculé</b>. Un champ est semé entièrement mûr
 * ({@code AGE = 7}) ; un villageois qui moissonne casse l'épi et en replante un jeune. Le bloc passe
 * donc de sept à zéro, et ce changement ne peut venir de rien d'autre :
 *
 * <ul>
 *   <li>le blé ne <em>rajeunit</em> jamais tout seul — la croissance ne va que dans un sens ;</li>
 *   <li>aucune créature n'est présente hormis les villageois ;</li>
 *   <li>le champ est irrigué, donc la terre ne se dessèche pas et les épis ne tombent pas.</li>
 * </ul>
 *
 * <p>Le compte est relevé dans le monde, bloc par bloc. Il ne passe par aucune structure que le mod
 * touche — contrairement à un compteur interne, qui pourrait être juste alors que rien ne sort.
 *
 * <h2>L'heure, qui décide de tout</h2>
 *
 * <p>Un villageois ne moissonne que pendant son activité de travail, et son emploi du temps suit
 * l'horloge du monde. Lancer l'épreuve à une heure quelconque reviendrait à mesurer l'heure plutôt
 * que le mod — {@link Wits} l'a appris en rendant un facteur cinq entre deux exécutions du même
 * code.
 *
 * <p>D'où l'attente préalable : on laisse le temps courir jusqu'à ce qu'un villageois passe
 * réellement en activité {@code WORK}, <b>puis</b> on arrête l'horloge. Les trois fenêtres voient
 * alors la même heure, et cette heure est une heure de travail.
 *
 * <h2>Les trois fenêtres</h2>
 *
 * <p>Allumé, éteint, rallumé — comme {@link Wits}, et pour la même raison : la troisième fenêtre
 * vérifie qu'une extinction en cours de partie ne laisse pas derrière elle une liste de
 * comportements périmée.
 */
public final class Reap {
    /** Durée de chaque fenêtre, en ticks. Assez pour plusieurs moissons par villageois. */
    private static final int WINDOW = 400;

    /** Duree des fenetres de rassemblement. Plus longue : une naissance demande environ 300 ticks. */
    private static final int MEET_WINDOW = 700;

    /** Ticks accordés pour atteindre une heure de travail avant de figer l'horloge. */
    private static final int WAIT_FOR_WORK = 200;

    /** Côté du champ, en blocs. */
    private static final int FIELD = 24;

    /** Combien de villageois fermiers on pose dessus. */
    private static final int FARMERS = 24;

    /** Part minimale de la moisson de vanilla que le mod doit conserver. */
    private static final int FLOOR_PERCENT = 60;

    private enum Step { OFF, WAITING_FOR_WORK, ON_FIRST, VANILLA, ON_AGAIN, MEET_ON, MEET_OFF, DONE }

    private static Step step = Step.OFF;
    private static int waiting;
    private static MinecraftServer host;
    private static int fieldY;
    private static int fieldX0;
    private static int fieldZ0;

    private static int reapedOn;
    private static int reapedOff;
    private static int reapedAgain;
    private static int babiesOn;
    private static int babiesOff;
    private static int babiesAgain;
    private static int courtingOn;
    private static int courtingOff;
    private static int babiesMeetOn;
    private static int babiesMeetOff;

    private Reap() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        ServerLevel level = server.overworld();
        Settings.setEnabled(true);
        // Les distances doivent rester fixes : voir Bench, meme raison.
        Settings.setTide(false);

        // Sans cette règle, HarvestFarmland refuse de démarrer : sa toute première condition est
        // « cette créature a-t-elle le droit de modifier le monde ? ».
        level.getGameRules().set(GameRules.MOB_GRIEFING, true, server);
        // Aucune créature parasite : le compte des bébés doit être celui des villageois, et rien ne
        // doit piétiner le champ.
        level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);

        forceChunks(level);
        clearVillagers(level);
        sowRipeField(level);
        int sown = countRipe(level);
        int expected = FIELD * (FIELD - FIELD / 8);
        if (sown < expected * 9 / 10) {
            Lanterne.LOG.error("[MOISSON] ÉPREUVE INVALIDE : {} épi(s) mûr(s) sur pied, il en "
                    + "fallait environ {}. Le champ n'a pas pris — inutile de compter ce qui en "
                    + "disparaît.", sown, expected);
            step = Step.DONE;
            if (host != null) {
                host.halt(false);
            }
            return;
        }
        int born = placeFarmers(level);

        step = Step.WAITING_FOR_WORK;
        waiting = WAIT_FOR_WORK;
        Lanterne.LOG.info("[MOISSON] Champ de {}×{} semé : {} épi(s) mûr(s) sur pied, {} fermier(s). "
                + "On attend l'heure du travail.", FIELD, FIELD, sown, born);
    }

    /**
     * Force les chunks du champ.
     *
     * <p>Cette épreuve tourne sans joueur. Sans ticket de chargement, les chunks autour de l'origine
     * n'existent pas en mémoire : {@code setBlock} n'y écrit rien, le champ reste vide, et l'épreuve
     * compte zéro épi mûr.
     *
     * <p>C'est exactement le défaut que l'épreuve de tir avait rencontré — elle avait rendu « vache
     * épargnée » des deux côtés — et que celle des fluides avait rencontré avant elle. Trois fois le
     * même oubli : il est assez fréquent pour mériter d'être nommé.
     *
     * <p>Cinq chunks de côté couvrent largement un champ de vingt-quatre blocs centré sur l'origine,
     * les fermiers compris.
     */
    private static void forceChunks(ServerLevel level) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setChunkForced(dx, dz, true);
            }
        }
    }

    /**
     * Sème un champ entièrement mûr, irrigué.
     *
     * <p>Les canaux d'eau tous les huit blocs ne sont pas un décor : sans eux la terre se dessèche
     * pendant la mesure, les épis tombent d'eux-mêmes, et le compte des blocs rajeunis deviendrait
     * faux au profit de personne.
     */
    private static void sowRipeField(ServerLevel level) {
        fieldY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0) - 1;
        fieldX0 = -FIELD / 2;
        fieldZ0 = -FIELD / 2;

        BlockState farmland = Blocks.FARMLAND.defaultBlockState()
                .setValue(FarmlandBlock.MOISTURE, 7);
        BlockState ripe = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, CropBlock.MAX_AGE);
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState soil = Blocks.DIRT.defaultBlockState();
        BlockState nothing = Blocks.AIR.defaultBlockState();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < FIELD; x++) {
            for (int z = 0; z < FIELD; z++) {
                // La hauteur du sol est relevee en (0,0) une seule fois, mais le terrain d'un monde
                // ne l'est pas : sur vingt-quatre blocs de cote, une pente suffit a enterrer la
                // moitie du champ et a laisser l'autre moitie en l'air, ou le ble tombe au premier
                // rafraichissement. La premiere version de cette epreuve a ainsi trouve zero epi mur
                // — et compte les cinq cent quatre absents comme autant de moissons.
                //
                // On aplanit donc : une assise pleine dessous, et le vide degage dessus.
                cursor.set(fieldX0 + x, fieldY - 1, fieldZ0 + z);
                level.setBlock(cursor, soil, 2);
                for (int above = 1; above <= 4; above++) {
                    cursor.set(fieldX0 + x, fieldY + above, fieldZ0 + z);
                    level.setBlock(cursor, nothing, 2);
                }

                cursor.set(fieldX0 + x, fieldY, fieldZ0 + z);
                if (z % 8 == 4) {
                    level.setBlock(cursor, water, 2);
                    continue;
                }
                level.setBlock(cursor, farmland, 2);
                cursor.set(fieldX0 + x, fieldY + 1, fieldZ0 + z);
                level.setBlock(cursor, ripe, 2);
            }
        }
    }

    /**
     * Compte les epis reellement mûrs sur pied.
     *
     * <p>Ce controle est la lecon de la premiere execution. {@link #countReaped} compte tout ce qui
     * n'est <em>pas</em> un epi mûr — ce qui est juste pour un epi moissonne, mais l'est tout autant
     * pour un epi qui n'a jamais ete seme. L'epreuve a donc annonce « cent pour cent, conforme »
     * sur un champ vide, trois fois de suite, avec le meme nombre des trois cotes.
     *
     * <p>Un verdict faux prononce avec assurance est pire qu'une absence de verdict. On verifie
     * desormais que le champ est bien plein avant de commencer a compter ce qui en disparait.
     */
    private static int countRipe(ServerLevel level) {
        int ripe = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < FIELD; x++) {
            for (int z = 0; z < FIELD; z++) {
                if (z % 8 == 4) {
                    continue;
                }
                cursor.set(fieldX0 + x, fieldY + 1, fieldZ0 + z);
                BlockState state = level.getBlockState(cursor);
                if (state.is(Blocks.WHEAT) && state.getValue(CropBlock.AGE) == CropBlock.MAX_AGE) {
                    ripe++;
                }
            }
        }
        return ripe;
    }

    /**
     * Pose les fermiers sur le champ.
     *
     * <p>Chacun reçoit du pain : un villageois qui a de quoi manger est disposé à se reproduire, ce
     * qui rend le second compteur de cette épreuve capable de rendre autre chose que zéro.
     */
    private static int placeFarmers(ServerLevel level) {
        int born = 0;
        for (int i = 0; i < FARMERS; i++) {
            Villager farmer = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
            if (farmer == null) {
                continue;
            }
            double x = fieldX0 + 1 + (i * 7) % (FIELD - 2);
            double z = fieldZ0 + 1 + (i * 5) % (FIELD - 2);
            farmer.snapTo(x + 0.5d, fieldY + 1d, z + 0.5d, 0f, 0f);
            farmer.setVillagerData(farmer.getVillagerData()
                    .withProfession(level.registryAccess(), VillagerProfession.FARMER));
            farmer.getInventory().addItem(new ItemStack(Items.BREAD, 64));
            farmer.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 64));
            if (!level.addFreshEntity(farmer)) {
                continue;
            }
            born++;

            // Le poste de travail, sans lequel rien de tout cela ne sert.
            //
            // L'activite WORK d'un villageois est declaree avec une condition d'entree explicite :
            // ImmutableSet.of(Pair.of(MemoryModuleType.JOB_SITE, VALUE_PRESENT)). Sans cette
            // memoire, l'activite ne peut PAS s'activer — et HarvestFarmland, qui vit dedans, n'est
            // jamais propose. La premiere version de cette epreuve a attendu deux mille quatre cents
            // ticks pour rien, et a eu raison de refuser de conclure.
            //
            // On pose donc un vrai composteur — le poste du fermier — et on le lui attribue, ce que
            // le jeu ferait de lui-meme apres plusieurs minutes de recherche.
            BlockPos site = new BlockPos((int) Math.floor(x), fieldY + 1, (int) Math.floor(z) + 1);
            level.setBlock(site, Blocks.COMPOSTER.defaultBlockState(), 3);
            farmer.getBrain().setMemory(MemoryModuleType.JOB_SITE,
                    GlobalPos.of(level.dimension(), site));
        }
        return born;
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        ServerLevel level = server.overworld();

        keepBusy(level, step == Step.MEET_ON || step == Step.MEET_OFF
                ? Activity.MEET : Activity.WORK);
        if (step == Step.MEET_ON) {
            courtingOn = Math.max(courtingOn, countCourting(level));
        } else if (step == Step.MEET_OFF) {
            courtingOff = Math.max(courtingOff, countCourting(level));
        }

        if (step == Step.WAITING_FOR_WORK) {
            if (anyoneWorking(level)) {
                level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                step = Step.ON_FIRST;
                waiting = WINDOW;
                Lanterne.LOG.info("[MOISSON] Un fermier est passé au travail — horloge arrêtée. "
                        + "Première fenêtre, mod allumé.");
                return;
            }
            if (--waiting > 0) {
                return;
            }
            Lanterne.LOG.error("[MOISSON] ÉPREUVE INVALIDE : aucun fermier n'est passé au travail en "
                    + "{} ticks. Le protocole est en cause, et son silence ne prouverait rien.",
                    WAIT_FOR_WORK);
            step = Step.DONE;
            if (host != null) {
                host.halt(false);
            }
            return;
        }

        if (--waiting > 0) {
            return;
        }

        int reaped = countReaped(level);
        int babies = countBabies(level);

        switch (step) {
            case ON_FIRST -> {
                reapedOn = reaped;
                babiesOn = babies;
                Lanterne.LOG.info("[MOISSON] mod allumé — {} épi(s) moissonné(s), {} bébé(s).",
                        reapedOn, babiesOn);
                resow(level);
                Settings.setEnabled(false);
                step = Step.VANILLA;
                waiting = WINDOW;
                Lanterne.LOG.info("[MOISSON] Deuxième fenêtre, mod éteint.");
            }
            case VANILLA -> {
                reapedOff = reaped;
                babiesOff = babies;
                Lanterne.LOG.info("[MOISSON] mod éteint — {} épi(s) moissonné(s), {} bébé(s).",
                        reapedOff, babiesOff);
                resow(level);
                Settings.setEnabled(true);
                step = Step.ON_AGAIN;
                waiting = WINDOW;
                Lanterne.LOG.info("[MOISSON] Troisième fenêtre, mod rallumé.");
            }
            case ON_AGAIN -> {
                reapedAgain = reaped;
                babiesAgain = babies;
                Lanterne.LOG.info("[MOISSON] mod rallume : {} epi(s) moissonne(s), {} bebe(s).",
                        reapedAgain, babiesAgain);
                buildVillage(level);
                Settings.setEnabled(true);
                step = Step.MEET_ON;
                waiting = MEET_WINDOW;
                Lanterne.LOG.info("[MOISSON] Quatrieme fenetre : on passe au rassemblement, mod "
                        + "allume. Ce n'est plus la moisson qu'on regarde mais la reproduction.");
            }
            case MEET_ON -> {
                babiesMeetOn = babies;
                Lanterne.LOG.info("[MOISSON] rassemblement, mod allume : {} courtise(s) au plus "
                        + "fort, {} bebe(s).", courtingOn, babiesMeetOn);
                resetBreeding(level);
                buildVillage(level);
                Settings.setEnabled(false);
                step = Step.MEET_OFF;
                waiting = MEET_WINDOW;
                Lanterne.LOG.info("[MOISSON] Cinquieme fenetre, mod eteint.");
            }
            case MEET_OFF -> {
                babiesMeetOff = babies;
                Lanterne.LOG.info("[MOISSON] rassemblement, mod eteint : {} courtise(s) au plus "
                        + "fort, {} bebe(s).", courtingOff, babiesMeetOff);
                Settings.setEnabled(true);
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
     * Maintient les fermiers en activité de travail.
     *
     * <h2>Pourquoi forcer, et pourquoi cela ne fausse rien</h2>
     *
     * <p>L'emploi du temps d'un villageois le fait travailler entre deux heures précises de la
     * journée, et l'horloge du monde de ce dépôt est là où les épreuves précédentes l'ont laissée.
     * Attendre l'heure de travail demanderait, au pire, un jour de jeu complet — vingt minutes pour
     * une épreuve qui en dure trois.
     *
     * <p>On impose donc l'activité à chaque tick. {@code updateActivityFromSchedule} la reprendra
     * peut-être vingt ticks plus tard ; on la réimpose aussitôt.
     *
     * <p>Ce forçage est appliqué <b>à l'identique dans les trois fenêtres</b>. Il ne peut donc pas
     * favoriser l'une d'elles : il déplace le point de comparaison, il ne le penche pas. Et il ne
     * touche qu'à l'activité — les comportements qu'elle contient démarrent, tournent et s'arrêtent
     * exactement comme d'habitude, ce qui est précisément ce que l'on veut observer.
     */
    private static void keepBusy(ServerLevel level, Activity activity) {
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Villager farmer && !farmer.isBaby()) {
                farmer.getBrain().setActiveActivityIfPossible(activity);
            }
        }
    }

    /**
     * Fait table rase des villageois.
     *
     * <p>Le monde de ce depot sert a toutes les mesures et garde ce que les precedentes y ont
     * laisse. La premiere execution de cette epreuve a ainsi dresse son village pour <b>648</b>
     * villageois au lieu de vingt-quatre : les six cents de la charge du banc etaient encore la.
     *
     * <p>Avec vingt-quatre lits pour six cent quarante-huit habitants, la limite de population etait
     * atteinte des le premier instant. L'epreuve ne mesurait donc plus la reproduction mais le
     * plafond du village, ce qui n'a rien a voir.
     */
    private static void clearVillagers(ServerLevel level) {
        List<Entity> leftovers = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Villager) {
                leftovers.add(entity);
            }
        }
        for (Entity leftover : leftovers) {
            leftover.discard();
        }
        if (!leftovers.isEmpty()) {
            Lanterne.LOG.info("[MOISSON] {} villageois d'une epreuve precedente retire(s).",
                    leftovers.size());
        }
    }

    /**
     * Remet les conditions de reproduction a neuf entre deux fenetres.
     *
     * <h2>Le biais que ceci corrige</h2>
     *
     * <p>Premiere execution avec les deux fenetres : <b>68 courtises avec le mod, 0 sans</b>. Un
     * ecart pareil en faveur du mod n'est pas credible, et il ne l'etait pas.
     *
     * <p>La cause est que la reproduction <b>se consomme</b>. Pendant la premiere fenetre, les
     * couples se forment, vingt-quatre enfants naissent, et les vingt-quatre lits sont des lors
     * occupes. La seconde fenetre trouve un village plein et des parents qui ont mange leur
     * nourriture : elle ne peut rien rendre d'autre que zero, quel que soit le code qui tourne.
     *
     * <p>C'est le meme defaut que la scene du banc corrige avec sa remise a neuf entre les deux
     * phases, et pour la meme raison : deux phases ne se comparent que si elles partent du meme
     * etat. On retire donc les enfants, on rend la nourriture, et on relibere les lits.
     */
    private static void resetBreeding(ServerLevel level) {
        List<Entity> young = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Villager villager && villager.isBaby()) {
                young.add(entity);
            }
        }
        for (Entity child : young) {
            child.discard();
        }

        int refed = 0;
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Villager villager) || villager.isBaby()) {
                continue;
            }
            villager.getInventory().addItem(new ItemStack(Items.BREAD, 64));
            villager.getBrain().eraseMemory(MemoryModuleType.BREED_TARGET);
            refed++;
        }
        Lanterne.LOG.info("[MOISSON] Remise a neuf : {} enfant(s) retire(s), {} parent(s) renourri(s) "
                + "- sans quoi la seconde fenetre trouverait un village plein.",
                young.size(), refed);
    }

    /**
     * Dresse le village : une cloche, des lits, et les memoires qui vont avec.
     *
     * <h2>Pourquoi la moisson ne pouvait pas mesurer la reproduction</h2>
     *
     * <p>Les deux comportements ne vivent pas dans la meme activite. Moissonner est dans
     * {@code WORK} ; se reproduire est dans {@code MEET}. Or cette epreuve impose {@code WORK} a
     * chaque tick pour tenir les fermiers au travail : elle rendait donc <b>zero bebe par
     * construction</b>, et le premier verdict l'a affiche trois fois sans que cela signifie quoi
     * que ce soit.
     *
     * <p>D'ou deux fenetres de plus, sous {@code MEET}. Et {@code MEET} a sa propre condition
     * d'entree, exactement comme {@code WORK} avait la sienne : {@code MEETING_POINT} doit etre
     * present, c'est-a-dire qu'il faut une cloche.
     *
     * <h2>Les lits</h2>
     *
     * <p>Un couple de villageois ne fait naitre un enfant que s'il reste un lit libre : c'est ainsi
     * que le jeu borne la population d'un village. Sans lits, l'epreuve mesurerait cette limite et
     * non le module.
     */
    private static void buildVillage(ServerLevel level) {
        BlockPos bell = new BlockPos(fieldX0 - 3, fieldY + 1, fieldZ0 - 3);
        level.setBlock(bell.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(bell, Blocks.BELL.defaultBlockState(), 3);

        int laid = 0;
        for (int i = 0; i < FARMERS; i++) {
            BlockPos foot = new BlockPos(fieldX0 - 6, fieldY + 1, fieldZ0 + i * 2);
            BlockPos head = foot.relative(Direction.EAST);
            level.setBlock(foot.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(head.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(foot, Blocks.BED.pick(DyeColor.WHITE).defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST)
                    .setValue(BedBlock.PART, BedPart.FOOT), 3);
            level.setBlock(head, Blocks.BED.pick(DyeColor.WHITE).defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, Direction.EAST)
                    .setValue(BedBlock.PART, BedPart.HEAD), 3);
            laid++;
        }

        int given = 0;
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Villager villager) || villager.isBaby()) {
                continue;
            }
            villager.getBrain().setMemory(MemoryModuleType.MEETING_POINT,
                    GlobalPos.of(level.dimension(), bell));
            villager.getBrain().setMemory(MemoryModuleType.HOME, GlobalPos.of(level.dimension(),
                    new BlockPos(fieldX0 - 6, fieldY + 1, fieldZ0 + (given % FARMERS) * 2)));
            given++;
        }
        Lanterne.LOG.info("[MOISSON] Village dresse : une cloche, {} lit(s), {} villageois pourvus "
                + "d'un point de rassemblement et d'un foyer.", laid, given);
    }

    /**
     * Les villageois en train de se courtiser.
     *
     * <p>On compte cette memoire plutot que les naissances, parce qu'une naissance demande de deux
     * cent soixante-quinze a trois cent vingt-cinq ticks <em>apres</em> que le couple se soit
     * trouve : une fenetre entiere rien que pour le dernier pas. {@code BREED_TARGET} se pose des
     * que l'enchainement demarre, ce qui est precisement ce qu'on veut verifier : que le cerveau
     * accelere propose encore ce comportement.
     *
     * <p>La memoire est lue dans la table du cerveau, a laquelle le module ne touche pas.
     */
    private static int countCourting(ServerLevel level) {
        int courting = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Villager villager
                    && villager.getBrain().hasMemoryValue(MemoryModuleType.BREED_TARGET)) {
                courting++;
            }
        }
        return courting;
    }

    /** Vrai dès qu'un fermier est réellement passé en activité de travail. */
    private static boolean anyoneWorking(ServerLevel level) {
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Villager farmer && farmer.getBrain().isActive(Activity.WORK)) {
                return true;
            }
        }
        return false;
    }

    /** Les épis qui ne sont plus mûrs : le champ a été semé entièrement à maturité. */
    private static int countReaped(ServerLevel level) {
        int reaped = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < FIELD; x++) {
            for (int z = 0; z < FIELD; z++) {
                if (z % 8 == 4) {
                    continue;
                }
                cursor.set(fieldX0 + x, fieldY + 1, fieldZ0 + z);
                BlockState state = level.getBlockState(cursor);
                if (!state.is(Blocks.WHEAT) || state.getValue(CropBlock.AGE) < CropBlock.MAX_AGE) {
                    reaped++;
                }
            }
        }
        return reaped;
    }

    private static int countBabies(ServerLevel level) {
        int babies = 0;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Villager young && young.isBaby()) {
                babies++;
            }
        }
        return babies;
    }

    /** Remet le champ à maturité entre deux fenêtres, pour qu'elles partent du même état. */
    private static void resow(ServerLevel level) {
        BlockState ripe = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, CropBlock.MAX_AGE);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 0; x < FIELD; x++) {
            for (int z = 0; z < FIELD; z++) {
                if (z % 8 == 4) {
                    continue;
                }
                cursor.set(fieldX0 + x, fieldY + 1, fieldZ0 + z);
                level.setBlock(cursor, ripe, 2);
            }
        }
    }

    private static void report() {
        Lanterne.LOG.info("[MOISSON] ── Verdict ── ({} ticks par fenêtre)", WINDOW);
        Lanterne.LOG.info("[MOISSON] Épis moissonnés · allumé {} · éteint {} · rallumé {}",
                reapedOn, reapedOff, reapedAgain);
        Lanterne.LOG.info("[MOISSON] Bébés villageois · allumé {} · éteint {} · rallumé {}",
                babiesOn, babiesOff, babiesAgain);

        if (reapedOff <= 0) {
            Lanterne.LOG.error("[MOISSON] ÉPREUVE INVALIDE : rien n'est moissonné même sans le mod. "
                    + "Le protocole est en cause, pas le module.");
            return;
        }

        // Le verdict porte sur la troisieme fenetre rapportee a la deuxieme, et sur elles seules.
        //
        // La premiere fenetre est systematiquement avantagee, et lourdement : 80 epis contre 27 et
        // 25, 16 courtises contre 1. La cause n'est pas le mod mais la position de depart : les
        // fermiers viennent d'etre poses au milieu d'un champ entierement mur, et moissonnent sans
        // avoir a chercher. Les fenetres suivantes trouvent un champ deja entame et des fermiers
        // disperses.
        //
        // C'est le meme defaut de derive que le banc de vitesse a corrige en entrelacant ses phases.
        // Ici, deux fenetres consecutives suffisent : la deuxieme et la troisieme se suivent, voient
        // le meme champ et les memes fermiers au meme endroit. Leur rapport est interpretable ; celui
        // de la premiere ne l'est pas, et il est donc affiche a titre indicatif sans porter le
        // verdict.
        int firstPercent = (int) Math.round(100d * reapedOn / reapedOff);
        int againPercent = (int) Math.round(100d * reapedAgain / reapedOff);

        Lanterne.LOG.info("[MOISSON] Premiere fenetre a {} % - indicatif seulement : elle part d'un "
                + "champ intact et de fermiers deja sur place.", firstPercent);
        if (againPercent < FLOOR_PERCENT) {
            Lanterne.LOG.error("[MOISSON] NON CONFORME APRÈS RALLUMAGE : {} % contre {} % au premier "
                    + "allumage. Voir Settings.epoch().", againPercent, firstPercent);
            return;
        }
        Lanterne.LOG.info("[MOISSON] Courtises - mod allume {} - mod eteint {}",
                courtingOn, courtingOff);
        Lanterne.LOG.info("[MOISSON] Bebes au rassemblement - allume {} - eteint {}",
                babiesMeetOn, babiesMeetOff);

        if (courtingOff > 0 && courtingOn == 0) {
            Lanterne.LOG.error("[MOISSON] NON CONFORME : les villageois se courtisent sans le mod "
                    + "et plus du tout avec. Le comportement de reproduction n'est plus propose.");
            return;
        }
        if (courtingOff == 0 && courtingOn == 0) {
            Lanterne.LOG.warn("[MOISSON] Reproduction NON PROUVEE : aucune courtise des deux cotes. "
                    + "Le protocole n'a pas reuni les conditions ; cela ne dit rien du module, ni "
                    + "en bien ni en mal.");
        }
        Lanterne.LOG.info("[MOISSON] CONFORME : {} % de la moisson de vanilla au premier allumage, "
                + "{} % après extinction et rallumage. Les villageois travaillent.",
                firstPercent, againPercent);
    }
}
