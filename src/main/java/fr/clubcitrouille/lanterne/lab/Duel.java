package fr.clubcitrouille.lanterne.lab;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Cadence;
import fr.clubcitrouille.lanterne.core.Census;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'épreuve du duel : la créature qu'on frappe se comporte-t-elle encore comme une créature ?
 *
 * <h2>Ce qu'aucun banc de ce dépôt ne savait voir</h2>
 *
 * <p>Toutes les épreuves écrites jusqu'ici mesurent des <b>millisecondes par tick</b>. Aucune ne
 * mesure si une créature se <em>comporte</em> correctement. Le défaut qui a motivé celle-ci est
 * arrivé par un joueur, en jeu, et non par un banc :
 *
 * <blockquote>« Je tape un squelette, il prend dix ans, et surtout dix ans avant de retaper, et il
 * lévite au sol quand il prend des dégâts. »</blockquote>
 *
 * <p>Trois symptômes, une seule cause — le tick de la créature était annulé — et un banc de vitesse
 * aurait <b>applaudi</b>, puisqu'une créature qui ne fait rien est la plus rapide de toutes. C'est
 * le même piège que {@link Wits} a nommé pour le cerveau des villageois, et il fallait le nommer une
 * seconde fois pour le combat.
 *
 * <h2>Les deux chiffres, et pourquoi ils ne peuvent pas mentir</h2>
 *
 * <p>Le jeu écrit ces deux lignes, et elles ne s'exécutent <b>que</b> dans le tick de la créature :
 *
 * <pre>
 * LivingEntity.baseTick : if (this.hurtTime &gt; 0) this.hurtTime--;
 *                         if (this.invulnerableTime &gt; 0) this.invulnerableTime--;
 * </pre>
 *
 * <p><b>{@code hurtTime}.</b> Un coup le pose à dix. Il ne redescend que d'un par tick réel, et par
 * rien d'autre. Relever la suite {@code 10, 9, 8, 7…} prouve que la créature a tické dix fois ;
 * relever {@code 10, 10, 10, 10…} prouve qu'elle n'a pas tické du tout. Aucun raccourci ne peut
 * simuler ce chiffre, et surtout : <b>le module ne le touche pas</b>, ce qui est la condition pour
 * qu'une épreuve ne valide pas le défaut qu'elle cherche.
 *
 * <p><b>La position verticale.</b> La gravité ne s'applique que dans {@code travel()}, donc dans le
 * tick. Une créature dont le tick est annulé ne tombe pas : elle reste exactement où le recul l'a
 * laissée. C'est la lévitation, au sens littéral.
 *
 * <h2>La boucle qui rendait la lévitation permanente</h2>
 *
 * <p>{@code EntityThrottle.mustNeverSkip} exempte déjà ce qui tombe, mais à une condition :
 *
 * <pre>
 * return !entity.onGround() &amp;&amp; entity.getDeltaMovement().y &lt; 0d &amp;&amp; Crowd.neighbours(entity) &lt; 6;
 * </pre>
 *
 * <p>Or le recul d'un coup envoie la créature <b>vers le haut</b> : sa vitesse verticale est
 * positive, l'exemption ne joue pas, le tick est annulé — et la gravité, qui seule aurait pu rendre
 * cette vitesse négative, ne s'applique jamais. La créature ne remplira donc <em>jamais</em> la
 * condition qui l'aurait sauvée. Elle reste en l'air jusqu'à son prochain réveil, trois secondes et
 * demie plus tard.
 *
 * <p>C'est pour rompre cette boucle que l'exemption de combat existe, et c'est elle que cette
 * épreuve vérifie. On reproduit donc le geste exactement : un coup, <b>et le recul qui va avec</b>.
 *
 * <h2>Les deux mondes, et pourquoi le second n'est pas une politesse</h2>
 *
 * <p>L'épreuve tourne dans l'Overworld <b>et</b> dans le Nether. Ce n'est pas de la rigueur
 * décorative : le défaut d'origine ne se produisait <em>que</em> hors de l'Overworld, parce que le
 * recensement des distances était unique pour tous les mondes et que le premier monde à ticker
 * fermait la porte aux autres. Une épreuve qui n'aurait tourné que dans l'Overworld aurait rendu
 * « conforme » pendant des mois — ce qu'elle a effectivement fait, sous d'autres noms.
 *
 * <p>La leçon dépasse ce cas : <b>une garantie de distance ne vaut que ce que vaut la distance qui
 * l'alimente.</b> La promesse « rien n'est dégradé à moins de vingt-quatre blocs » était exacte, et
 * elle a produit des squelettes flottants parce que personne n'avait éprouvé son entrée ailleurs
 * que là où elle marchait.
 *
 * <h2>Ce que l'épreuve refuse de faire</h2>
 *
 * <p>Elle s'arrête plutôt que de conclure si le squelette n'est pas là, si son chunk n'est pas
 * simulé, si le coup n'a pas porté, ou — le contrôle qui compte le plus — <b>si la créature se
 * trouve dans la zone franche</b>. Dans ce dernier cas elle serait tickée à plein régime de toute
 * façon, l'exemption de combat ne servirait à rien, et l'épreuve annoncerait « conforme » sans avoir
 * rien éprouvé. Trois épreuves de ce dépôt sont déjà tombées dans ce trou-là.
 */
public final class Duel {
    /** Ticks laissés au monde pour charger ses chunks et au squelette pour se poser. */
    private static final int SETTLE = 60;

    /**
     * Ticks relevés après le coup.
     *
     * <p>Neuf : {@code hurtTime} part de dix et l'on veut le voir descendre sans jamais atteindre
     * zéro, où il cesserait de décroître et ne prouverait plus rien.
     */
    private static final int WINDOW = 9;

    /** Valeur que le jeu pose dans {@code hurtTime} à chaque coup encaissé. */
    private static final int HURT_DURATION = 10;

    /** Poussée verticale du recul, en blocs par tick. Celle d'un coup d'épée ordinaire. */
    private static final double KNOCKBACK = 0.42d;

    /** Écart en deçà duquel deux hauteurs sont tenues pour identiques. */
    private static final double STILL = 1.0e-6d;

    private enum Step { OFF, BUILDING, SETTLING, WATCHING, DONE }

    /**
     * Ticks accordés pour que l'arène devienne réellement simulée.
     *
     * <h2>Pourquoi un délai fixe ne suffisait pas</h2>
     *
     * <p>La première version attendait {@link #SETTLE} ticks, puis frappait. Dans l'Overworld cela
     * marchait ; dans le Nether l'épreuve s'est arrêtée sur « le chunk n'est pas simulé ».
     *
     * <p>La cause n'est pas le nombre de ticks mais le fait de les compter. Poser les chunks du
     * Nether demande de les <b>générer</b>, ce qui bloque le fil du serveur pendant des secondes ;
     * le serveur rattrape ensuite son retard en enchaînant les ticks aussi vite qu'il peut. Les
     * soixante ticks s'écoulaient donc en une fraction de seconde, bien avant que la promotion du
     * ticket n'ait eu lieu.
     *
     * <p>Un banc ne doit pas attendre <em>un temps</em>, il doit attendre <b>sa condition</b>. On
     * guette donc l'état voulu, et ce compteur ne sert plus qu'à renoncer si l'état n'arrive jamais.
     */
    private static final int PATIENCE = 1200;

    private static Step step = Step.OFF;
    private static int waiting;
    private static int patience;
    private static MinecraftServer host;

    /** Les mondes à éprouver, dans l'ordre. Le Nether n'est pas une politesse : voir la note. */
    private static final ResourceKey<Level>[] ARENAS = arenas();

    private static int arena;

    private static Skeleton victim;
    private static double groundY;
    private static int sample;

    /** Suite relevée de {@code hurtTime}, un relevé par tick. */
    private static final int[] HURT = new int[WINDOW];
    /** Suite relevée des hauteurs, un relevé par tick. */
    private static final double[] HEIGHT = new double[WINDOW];

    /** Verdicts par monde, pour le rapport final. */
    private static final StringBuilder VERDICTS = new StringBuilder();
    private static boolean anyFailure;

    @SuppressWarnings("unchecked")
    private static ResourceKey<Level>[] arenas() {
        return new ResourceKey[] { Level.OVERWORLD, Level.NETHER };
    }

    private Duel() {}

    public static boolean running() {
        return step != Step.OFF && step != Step.DONE;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        Settings.setEnabled(true);
        // Les distances doivent rester fixes pendant la mesure : meme raison que partout ailleurs.
        Settings.setTide(false);
        arena = 0;
        anyFailure = false;
        VERDICTS.setLength(0);
        step = Step.BUILDING;
        waiting = 0;
        Lanterne.LOG.info("[DUEL] Épreuve armée — un squelette frappé dans chacun des {} monde(s). "
                + "On relève hurtTime et la hauteur, tick par tick.", ARENAS.length);
    }

    public static void tick(MinecraftServer server) {
        if (!running()) {
            return;
        }
        switch (step) {
            case BUILDING -> build(server);
            case SETTLING -> {
                waiting--;
                patience--;
                // Le délai minimal est toujours respecté : il laisse au squelette le temps de se
                // poser sur la dalle. Au-delà, on n'attend plus un temps mais un état.
                if (waiting > 0) {
                    return;
                }
                if (settled(server) || patience <= 0) {
                    strike(server);
                }
            }
            case WATCHING -> watch(server);
            default -> { }
        }
    }

    // ------------------------------------------------------------------ la mise en place

    private static void build(MinecraftServer server) {
        if (arena >= ARENAS.length) {
            finish();
            return;
        }
        ServerLevel level = server.getLevel(ARENAS[arena]);
        if (level == null) {
            note(ARENAS[arena], "monde absent du serveur — rien à éprouver ici.");
            arena++;
            return;
        }

        // Le champ de bataille doit tourner sans joueur. Sans ticket, les chunks n'existent pas en
        // mémoire : le squelette n'est jamais simulé et l'épreuve relèverait « il ne tick pas » pour
        // une raison qui n'a rien à voir avec le mod. Trois épreuves de ce dépôt s'y sont déjà
        // laissé prendre — voir Reap.forceChunks, qui raconte les deux premières.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced(dx, dz, true);
            }
        }
        // <h2>Mais ce forçage ne doit pas compter comme une présence</h2>
        //
        // En jeu, forcer un chunk est un geste délibéré du joueur — « continue de simuler ceci » —
        // et le recensement le traite désormais comme une présence, ce qui protège les fermes
        // laissées derrière soi. Ici, le forçage n'est qu'un échafaudage : il n'exprime aucune
        // intention, il permet seulement à la scène de s'animer sans joueur.
        //
        // Sans cette ligne, l'épreuve se saborde toute seule : sa victime passe « à zéro bloc d'un
        // joueur », donc dans la zone franche, donc sans rien à démontrer — et l'épreuve refuse de
        // conclure, très correctement, sur une mise en scène qu'elle a elle-même faussée.
        Census.countForcedChunks(false);

        // Aucune créature parasite : une apparition naturelle dans l'arène pourrait frapper le
        // squelette, et un second coup remettrait hurtTime à dix au milieu du relevé.
        level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);

        groundY = floorOf(level);
        pave(level);

        Skeleton skeleton = EntityTypes.SKELETON.create(level, EntitySpawnReason.COMMAND);
        if (skeleton == null) {
            note(ARENAS[arena], "le jeu a refusé de créer un squelette.");
            arena++;
            return;
        }
        skeleton.snapTo(0.5d, groundY + 1d, 0.5d, 0f, 0f);
        // Ni casque ni soleil : l'arène est couverte. Un squelette qui brûle encaisse des dégâts de
        // feu pendant le relevé, et chaque dégât remet hurtTime à dix — la suite relevée deviendrait
        // 10, 9, 10, 9 et l'épreuve conclurait à un défaut qu'elle aurait elle-même provoqué.
        if (!level.addFreshEntity(skeleton)) {
            note(ARENAS[arena], "le monde a refusé d'accueillir le squelette.");
            arena++;
            return;
        }
        victim = skeleton;

        step = Step.SETTLING;
        waiting = SETTLE;
        patience = PATIENCE;
        Lanterne.LOG.info("[DUEL] {} · arène dressée à y={}, squelette posé. On attend qu'il touche "
                + "la dalle et que le chunk simule (au moins {} ticks, au plus {}).",
                ARENAS[arena].identifier(), (int) groundY, SETTLE, PATIENCE);
    }

    /**
     * Le sol de l'arène.
     *
     * <p>Dans l'Overworld, la carte des hauteurs suffit. Dans le Nether elle désigne le plafond de
     * bedrock, qui ne convient pas : on y descend donc à une altitude franche, au-dessus de la mer
     * de lave et sous le plafond.
     */
    private static double floorOf(ServerLevel level) {
        if (level.dimension() == Level.NETHER) {
            return 48d;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
    }

    /**
     * Dresse un sol plein et un toit.
     *
     * <p>Le toit n'est pas un décor : il supprime d'un coup la combustion au soleil, qui fausserait
     * la suite relevée, et il rend l'arène identique dans les deux mondes. Le squelette monte de
     * moins d'un bloc sous l'effet du recul ; trois blocs de dégagement ne le gênent jamais.
     */
    private static void pave(ServerLevel level) {
        BlockState floor = Blocks.STONE.defaultBlockState();
        BlockState roof = Blocks.STONE.defaultBlockState();
        BlockState nothing = Blocks.AIR.defaultBlockState();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                cursor.set(x, (int) groundY, z);
                level.setBlock(cursor, floor, 2);
                for (int above = 1; above <= 3; above++) {
                    cursor.set(x, (int) groundY + above, z);
                    level.setBlock(cursor, nothing, 2);
                }
                cursor.set(x, (int) groundY + 4, z);
                level.setBlock(cursor, roof, 2);
            }
        }
    }

    /**
     * L'arène est-elle prête à recevoir un coup ?
     *
     * <p>Deux conditions, et aucune n'est un délai : le chunk doit réellement simuler ses entités,
     * et le squelette doit avoir touché la dalle. Frapper une créature encore en train de tomber
     * ferait relever une hauteur qui descend pour une raison qui n'a rien à voir avec le recul.
     */
    private static boolean settled(MinecraftServer server) {
        ServerLevel level = server.getLevel(ARENAS[arena]);
        return level != null
                && victim != null
                && victim.isAlive()
                && level.isPositionEntityTicking(victim.blockPosition())
                && victim.onGround();
    }

    // ------------------------------------------------------------------ le coup

    private static void strike(MinecraftServer server) {
        ServerLevel level = server.getLevel(ARENAS[arena]);
        if (level == null || victim == null || !victim.isAlive()) {
            note(ARENAS[arena], "le squelette n'a pas survécu à la mise en place.");
            nextArena();
            return;
        }

        // <h2>Le contrôle qui décide si l'épreuve a le droit de conclure</h2>
        //
        // Si la créature est dans la zone franche, elle est tickée à plein régime quoi qu'il arrive,
        // l'exemption de combat ne sert à rien, et un verdict « conforme » ne prouverait rien du
        // tout. C'est très exactement la faute que l'épreuve de tir a commise — « vache épargnée »
        // des deux côtés — et celle de cuisson avant elle.
        double distance = Census.distanceOf(victim);
        if (Cadence.untouched(distance, 0d)) {
            note(ARENAS[arena], String.format(Locale.ROOT,
                    "ÉPREUVE INVALIDE : le squelette est à %.0f bloc(s) d'un observateur, donc dans "
                    + "la zone franche (%.0f blocs). Il serait tické à plein régime de toute façon : "
                    + "l'exemption de combat n'a rien à démontrer ici.",
                    distance, Cadence.untouchedRadius()));
            anyFailure = true;
            nextArena();
            return;
        }
        if (!level.isPositionEntityTicking(victim.blockPosition())) {
            note(ARENAS[arena], "ÉPREUVE INVALIDE : le chunk du squelette n'est pas simulé. Rien n'y "
                    + "tick, et son silence ne prouverait rien.");
            anyFailure = true;
            nextArena();
            return;
        }

        // Le coup, et le recul qui va avec. Le recul est le cœur du sujet : c'est lui qui donne une
        // vitesse verticale POSITIVE, et c'est cette vitesse positive qui mettait l'exemption de
        // chute en échec.
        victim.hurtServer(level, level.damageSources().generic(), 1.0f);
        Vec3 motion = victim.getDeltaMovement();
        victim.setDeltaMovement(motion.x, KNOCKBACK, motion.z);
        // Renommé syncVelocity en 26.3 — même champ public, même effet : forcer la resynchro de
        // la vitesse vers les clients après l'avoir écrasée manuellement ci-dessus.
        victim.syncVelocity = true;

        if (victim.hurtTime != HURT_DURATION) {
            note(ARENAS[arena], String.format(Locale.ROOT,
                    "ÉPREUVE INVALIDE : le coup n'a pas porté — hurtTime vaut %d et non %d juste "
                    + "après. Inutile de compter ce qui devrait en descendre.",
                    victim.hurtTime, HURT_DURATION));
            anyFailure = true;
            nextArena();
            return;
        }

        sample = 0;
        step = Step.WATCHING;
        Lanterne.LOG.info("[DUEL] {} · coup porté (hurtTime={}, recul +{} bloc/tick). {} relevés.",
                ARENAS[arena].identifier(), victim.hurtTime, KNOCKBACK, WINDOW);
    }

    // ------------------------------------------------------------------ le relevé

    private static void watch(MinecraftServer server) {
        if (victim == null || !victim.isAlive()) {
            note(ARENAS[arena], "ÉPREUVE INVALIDE : le squelette est mort pendant le relevé.");
            anyFailure = true;
            nextArena();
            return;
        }
        HURT[sample] = victim.hurtTime;
        HEIGHT[sample] = victim.getY();
        sample++;
        if (sample < WINDOW) {
            return;
        }
        judge();
        nextArena();
    }

    /**
     * Le verdict d'un monde.
     *
     * <p>Deux questions indépendantes, posées sur deux grandeurs que le module ne touche ni l'une ni
     * l'autre. Elles peuvent échouer séparément, et le message le dit.
     */
    private static void judge() {
        StringBuilder hurtLine = new StringBuilder();
        StringBuilder heightLine = new StringBuilder();
        for (int i = 0; i < WINDOW; i++) {
            hurtLine.append(i == 0 ? "" : ", ").append(HURT[i]);
            heightLine.append(i == 0 ? "" : ", ")
                    .append(String.format(Locale.ROOT, "%.3f", HEIGHT[i]));
        }
        Lanterne.LOG.info("[DUEL] {} · hurtTime : {}", ARENAS[arena].identifier(), hurtLine);
        Lanterne.LOG.info("[DUEL] {} · hauteur  : {}", ARENAS[arena].identifier(), heightLine);

        // hurtTime doit valoir 9, 8, 7… : un tick réel, un cran. Le premier relevé a lieu un tick
        // après le coup, donc la valeur attendue au relevé i est 10 - (i + 1).
        int ticked = 0;
        for (int i = 0; i < WINDOW; i++) {
            if (HURT[i] == HURT_DURATION - (i + 1)) {
                ticked++;
            }
        }

        // La hauteur doit bouger à chaque tick : le squelette monte sous l'effet du recul, puis
        // retombe. Une hauteur rigoureusement constante est la signature d'un tick annulé.
        int moved = 0;
        for (int i = 1; i < WINDOW; i++) {
            if (Math.abs(HEIGHT[i] - HEIGHT[i - 1]) > STILL) {
                moved++;
            }
        }

        boolean hurtOk = ticked == WINDOW;
        boolean heightOk = moved >= WINDOW - 2;

        if (hurtOk && heightOk) {
            note(ARENAS[arena], String.format(Locale.ROOT,
                    "CONFORME : %d/%d ticks réels comptés sur hurtTime, %d/%d relevés de hauteur en "
                    + "mouvement. La créature frappée vit à plein régime.",
                    ticked, WINDOW, moved, WINDOW - 1));
            return;
        }
        anyFailure = true;
        if (!hurtOk) {
            note(ARENAS[arena], String.format(Locale.ROOT,
                    "NON CONFORME : seulement %d tick(s) réel(s) sur %d attendus. hurtTime ne descend "
                    + "pas d'un par tick, donc baseTick() ne s'exécute pas : le tick de la créature "
                    + "est annulé alors qu'on est en train de la frapper. C'est le défaut rapporté en "
                    + "jeu — les coups suivants seront refusés tant qu'invulnerableTime reste au-dessus "
                    + "de dix.", ticked, WINDOW));
        }
        if (!heightOk) {
            note(ARENAS[arena], String.format(Locale.ROOT,
                    "NON CONFORME : la hauteur n'a bougé que %d fois sur %d. La gravité ne s'applique "
                    + "que dans travel(), donc dans le tick : une hauteur figée après un recul est une "
                    + "créature qui lévite. C'est le mot employé par le joueur.",
                    moved, WINDOW - 1));
        }
    }

    // ------------------------------------------------------------------ l'enchaînement

    private static void nextArena() {
        if (victim != null) {
            victim.discard();
            victim = null;
        }
        arena++;
        step = Step.BUILDING;
    }

    private static void note(ResourceKey<Level> where, String what) {
        String line = where.identifier() + " · " + what;
        VERDICTS.append(line).append('\n');
        if (what.startsWith("CONFORME")) {
            Lanterne.LOG.info("[DUEL] {}", line);
        } else {
            Lanterne.LOG.error("[DUEL] {}", line);
        }
    }

    private static void finish() {
        step = Step.DONE;
        Lanterne.LOG.info("[DUEL] ── Verdict ──");
        for (String line : VERDICTS.toString().split("\n")) {
            if (!line.isBlank()) {
                Lanterne.LOG.info("[DUEL] {}", line);
            }
        }
        if (anyFailure) {
            Lanterne.LOG.error("[DUEL] Au moins un monde a échoué. Une créature qu'on frappe doit "
                    + "tourner à plein régime PARTOUT, sans quoi le combat est cassé là où personne "
                    + "ne pense à regarder.");
        } else {
            Lanterne.LOG.info("[DUEL] Tous les mondes sont conformes. Rappel : "
                    + "LANTERNE_BREAK_MELEE=1 retire l'exemption de combat — cette épreuve DOIT "
                    + "alors échouer, sans quoi c'est elle qu'il faut réparer.");
        }
        if (host != null) {
            host.halt(false);
        }
    }
}
