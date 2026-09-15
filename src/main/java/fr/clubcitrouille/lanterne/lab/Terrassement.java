package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Remblai;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * L'épreuve du terrassement : un {@code /fill} réel, chronométré, et la preuve qu'il rend le même
 * monde.
 *
 * <h2>Ce que ce banc refuse de faire</h2>
 *
 * <p>Il ne chronomètre pas une méthode interne choisie parce qu'elle arrange le résultat. Il exécute
 * la <b>commande</b>, par le répartiteur du serveur, avec une pile de source ordinaire :
 *
 * <pre>
 * serveur.getCommands().performPrefixedCommand(source, "fill x1 y1 z1 x2 y2 z2 minecraft:stone");
 * </pre>
 *
 * <p>Tout ce qu'un joueur paierait est donc dans la mesure : l'analyse de la commande, la boucle de
 * {@code FillCommand}, {@code BlockInput.place}, {@code LevelChunk.setBlockState}, les cartes de
 * hauteur, la mise en file de la lumière, le marquage pour le client, et la boucle finale de
 * {@code updateNeighboursOnBlockSet}.
 *
 * <h2>Deux temps, et le premier est un veto</h2>
 *
 * <p>Un remplissage dix fois plus rapide qui laisse un monde différent est un échec, pas une
 * optimisation. Ce banc commence donc par le <b>témoin</b>, et ne chronomètre rien tant que le témoin
 * n'a pas conclu à l'identité.
 *
 * <p>Le témoin est une boîte de seize par dix par seize, <b>entièrement close par une coque de
 * pierre</b>. La coque n'est pas un ornement : sans elle, un objet lâché, une chute de sable ou un
 * écoulement d'eau sortirait du champ de comparaison, et l'on croirait à l'identité parce qu'on
 * n'aurait pas regardé là où la différence est partie. Rien ne peut quitter la boîte.
 *
 * <p>L'intérieur est peuplé d'un mélange déterministe de blocs choisis pour <b>réagir</b> : barrières,
 * barreaux, vitres, murets et rails, dont l'état dépend de leurs voisins ; coffres, dont le type
 * dépend du coffre d'à côté ; poudre, torches de redstone, répéteurs, comparateurs, observateurs,
 * leviers, échelles, lianes et échafaudages, qui tombent ou changent quand leur support disparaît ;
 * sable et gravier, qui programment un tick ; eau, qui en programme un autre. Ce sont exactement les
 * blocs que l'oracle de {@link Remblai} déclare sensibles, et donc les seuls sur lesquels une erreur
 * de ce module pourrait se voir.
 *
 * <p>Sur cette boîte, deux remplissages : l'un met trois couches à l'air — on casse — l'autre pose
 * une couche de pierre en plein milieu — on remblaie. Puis vingt ticks de repos, pour que les ticks
 * programmés s'exécutent, que le sable tombe et que l'eau coule.
 *
 * <p>La comparaison porte sur <b>tous</b> les blocs de la boîte élargie d'un bloc dans chaque
 * direction — trois mille huit cent quatre-vingt-huit états, pas un échantillon — et, en plus, sur
 * l'inventaire des entités qu'elle contient : un objet lâché d'un côté et pas de l'autre est une
 * différence, même si les blocs, eux, concordent. Au premier écart, le banc <b>refuse de conclure</b>
 * et publie la position et les deux états.
 *
 * <h2>Pourquoi la construction du témoin se fait module éteint, des deux côtés</h2>
 *
 * <p>Si la scène était bâtie une fois module actif et une fois module éteint, un écart pourrait venir
 * de la <em>construction</em> et non du remplissage mesuré. Elle est donc bâtie deux fois dans les
 * mêmes conditions — {@link Remblai#suspendre(boolean)} à vrai — avec {@code Block.UPDATE_NONE |
 * Block.UPDATE_KNOWN_SHAPE} (276), le drapeau que {@code LevelChunk.postProcessGeneration} s'envoie à
 * lui-même. L'état de départ est alors identique par construction, et la seule variable restante est
 * celle qu'on veut isoler.
 *
 * <h2>L'interrupteur, et pourquoi ce n'est pas celui du mod</h2>
 *
 * <p>{@code Settings.setEnabled(false)} n'éteindrait <b>pas</b> ce module : {@code Settings.remblai()}
 * n'est pas filtré par l'interrupteur général. Et l'éteindre éteindrait tout le reste, ce qui
 * mesurerait Lanterne au complet, pas le remblai. On se sert donc de
 * {@link Remblai#suspendre(boolean)}, qui ne touche que lui.
 *
 * <h2>Relevés alternés, pas deux moitiés</h2>
 *
 * <p>{@code lab/Boom} fait quarante tirs d'un côté puis quarante de l'autre. Ici les relevés
 * <b>alternent</b> un par un, et c'est délibéré : un remplissage de seize mille blocs alloue
 * abondamment, le tas grossit au fil de l'épreuve, et un ramassage de mémoire tombe plus volontiers
 * dans la seconde moitié que dans la première. Deux blocs consécutifs donneraient cette dérive pour
 * un effet du mod. Alterner la répartit sur les deux bras. Le prix est nul : chaque relevé repart
 * d'une boîte d'air, laissée telle quelle par le relevé précédent.
 *
 * <p>Le revers doit être dit : en alternant, le compilateur à la volée voit les deux chemins et n'en
 * spécialise aucun, alors qu'en service réel il n'en verrait qu'un. Les deux bras y perdent
 * <b>également</b>, donc le rapport publié reste juste — mais il est plutôt pessimiste pour le bras
 * actif, dont le test d'inertie serait mieux prédit sur un serveur qui ne bascule jamais.
 *
 * <h2>Ce que la mesure ne contient pas, et il faut le dire avant de lire le chiffre</h2>
 *
 * <p>{@code /fill} ne propage pas la lumière : {@code LevelChunk.setBlockState} appelle
 * {@code lightEngine.checkBlock(pos)}, qui sur un serveur <b>met en file</b> et rend la main
 * ({@code ThreadedLevelLightEngine.runLightUpdates} lève même une exception si on tente de la forcer
 * — « Ran automatically on a different thread! »). La propagation a lieu plus loin dans le tick,
 * hors du chronomètre.
 *
 * <p>Conséquence, et elle va contre nous : le rapport qui suit mesure la <b>part synchrone</b> du
 * remplissage. Le gain relatif sur le tick entier, lumière comprise, est nécessairement
 * <em>inférieur</em> à celui qu'on publie ici. Un banc qui tairait cela vendrait un chiffre flatteur.
 */
public final class Terrassement {
    /**
     * Le nom de la variable d'environnement qui arme cette épreuve.
     *
     * <p>Déclarée ici plutôt que recopiée dans {@code report/SelfTest} : la chaîne et le banc ne
     * peuvent pas diverger si elle n'existe qu'à un seul endroit.
     */
    public static final String LANTERNE_TERRASSEMENT = "LANTERNE_TERRASSEMENT";

    /** Coin du chantier, loin du point d'apparition et des autres bancs du dépôt. */
    private static final int X0 = 2048;
    private static final int Z0 = 2048;

    /** Hauteur de dégagement au-dessus du terrain réel : on travaille dans l'air, jamais dans le sol. */
    private static final int DEGAGEMENT = 24;

    /** Côté horizontal de la boîte chronométrée. */
    private static final int MESURE_COTE = 32;
    /** Hauteur de la boîte chronométrée. 32 × 16 × 32 = 16 384 blocs, sous la règle de jeu
     * {@code max_block_modifications} qui vaut 32 768 par défaut. */
    private static final int MESURE_HAUTEUR = 16;

    /** Côté horizontal de la boîte témoin, coque comprise. */
    private static final int TEMOIN_COTE = 16;
    /** Hauteur de la boîte témoin, coque comprise. */
    private static final int TEMOIN_HAUTEUR = 10;
    /** Élargissement du champ de comparaison au-delà de la coque. */
    private static final int MARGE = 1;

    /** Ticks d'attente pour que les chunks demandés arrivent vraiment. Voir {@code report/SelfTest}. */
    private static final int CHARGEMENT = 120;

    /** Ticks de repos avant chaque relevé du témoin : ticks programmés, chutes, écoulements. */
    private static final int REPOS = 20;

    /** Relevés utiles par bras. */
    private static final int UTILES = 24;
    /** Relevés de chauffe jetés en tête de chaque bras. */
    private static final int CHAUFFE = 6;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState PIERRE = Blocks.STONE.defaultBlockState();

    /**
     * Le mélange du témoin.
     *
     * <p>Aucun de ces blocs n'est là pour faire nombre. Chacun redéfinit {@code updateShape},
     * {@code neighborChanged}, ou les deux — c'est-à-dire que chacun est un bloc que
     * {@link Remblai} <b>refuse</b> de court-circuiter. Si l'oracle se trompait, c'est ici que cela
     * se verrait : une barrière qui ne se déconnecte pas, un coffre qui reste double, une torche qui
     * ne tombe pas, un rail qui garde sa courbe.
     *
     * <p>La pierre, la terre et l'air y figurent aussi, et pour la raison inverse : ce sont les blocs
     * inertes, ceux dont le module supprime effectivement le travail. Une scène qui n'en contiendrait
     * pas ne testerait jamais le chemin court.
     *
     * <h2>Ce qui en a été retiré, et il fallait y penser</h2>
     *
     * <p>Aucun bloc à <b>tick aléatoire</b>. Cactus, lianes et stalactites en faisaient partie, et
     * auraient ruiné l'épreuve : entre les deux passages il s'écoule une centaine de ticks, pendant
     * lesquels {@code ServerLevel.tickChunk} tire trois positions au hasard par section et par tick.
     * Un cactus qui pousse d'un côté et pas de l'autre est une différence <b>réelle</b> dans le monde
     * — et le banc refuserait de conclure, en accusant un module qui n'y est pour rien.
     *
     * <p>Aucun bloc sensible au ciel non plus : la coque de pierre est fermée par le haut, donc rien
     * n'y gèle, n'y fond ni n'y prend feu. La seule chose qui doit varier entre les deux passages est
     * l'interrupteur du module.
     */
    private static final BlockState[] PALETTE = {
        Blocks.STONE.defaultBlockState(),
        Blocks.AIR.defaultBlockState(),
        Blocks.DIRT.defaultBlockState(),
        Blocks.OAK_FENCE.defaultBlockState(),
        Blocks.IRON_BARS.defaultBlockState(),
        Blocks.GLASS_PANE.defaultBlockState(),
        Blocks.COBBLESTONE_WALL.defaultBlockState(),
        Blocks.CHEST.defaultBlockState(),
        Blocks.RAIL.defaultBlockState(),
        Blocks.REDSTONE_WIRE.defaultBlockState(),
        Blocks.REDSTONE_TORCH.defaultBlockState(),
        Blocks.TORCH.defaultBlockState(),
        Blocks.LEVER.defaultBlockState(),
        Blocks.REPEATER.defaultBlockState(),
        Blocks.COMPARATOR.defaultBlockState(),
        Blocks.OBSERVER.defaultBlockState(),
        Blocks.STICKY_PISTON.defaultBlockState(),
        Blocks.NOTE_BLOCK.defaultBlockState(),
        Blocks.HOPPER.defaultBlockState(),
        Blocks.LADDER.defaultBlockState(),
        Blocks.SCAFFOLDING.defaultBlockState(),
        Blocks.OAK_STAIRS.defaultBlockState(),
        Blocks.OAK_SLAB.defaultBlockState(),
        Blocks.OAK_LOG.defaultBlockState(),
        Blocks.GLASS.defaultBlockState(),
        Blocks.SAND.defaultBlockState(),
        Blocks.GRAVEL.defaultBlockState(),
        Blocks.WATER.defaultBlockState(),
        Blocks.LANTERN.defaultBlockState(),
        Blocks.TRIPWIRE.defaultBlockState(),
        Blocks.TRIPWIRE_HOOK.defaultBlockState(),
        Blocks.STONE_PRESSURE_PLATE.defaultBlockState(),
        Blocks.OAK_TRAPDOOR.defaultBlockState(),
        Blocks.IRON_DOOR.defaultBlockState(),
        Blocks.OAK_DOOR.defaultBlockState(),
    };

    private enum Etape {
        ARRET,
        CHARGEMENT,
        TEMOIN_A_POSE, TEMOIN_A_REMPLIT, TEMOIN_A_RELEVE,
        TEMOIN_B_POSE, TEMOIN_B_REMPLIT, TEMOIN_B_RELEVE,
        MESURE_PREPARE, MESURE,
        FINI
    }

    private static Etape etape = Etape.ARRET;
    private static int pause;
    private static MinecraftServer hote;

    /** Altitude du plancher de la boîte chronométrée, fixée une fois dans {@link #begin}. */
    private static int baseY;

    /** Relevé courant, tous bras confondus : les rangs pairs sont « avec », les impairs « sans ». */
    private static int releve;

    private static final long[] AVEC = new long[UTILES];
    private static final long[] SANS = new long[UTILES];

    private static BlockState[] etatsA;
    private static BlockState[] etatsB;
    private static String entitesA = "";
    private static String entitesB = "";

    /** La pile de source des commandes, construite une fois : elle n'a rien à faire dans le chrono. */
    private static CommandSourceStack source;

    private Terrassement() {}

    public static boolean running() {
        return etape != Etape.ARRET && etape != Etape.FINI;
    }

    /**
     * Ouvre l'épreuve : pose les tickets de chunk et laisse le temps aux chunks d'arriver.
     *
     * <p>Les deux tickets d'un joueur, et pas seulement celui du chargement : {@code sendBlockUpdated}
     * ne marque un bloc pour le client que si le chunk est au moins en {@code BLOCK_TICKING}
     * ({@code chunk.getFullStatus().isOrAfter(...)} dans {@code Level.markAndNotifyBlock}), et
     * {@code ChunkHolder.blockChanged} rend la main immédiatement si {@code getTickingChunk()} est
     * nul. Sans simulation, on mesurerait un {@code /fill} amputé de tout son travail de diffusion.
     */
    public static void begin(MinecraftServer serveur) {
        hote = serveur;
        ServerLevel monde = serveur.overworld();

        if (!Settings.remblai()) {
            Lanterne.LOG.warn("[TERRASSEMENT] Le module « remblai » est éteint par réglage : les deux "
                    + "bras seraient identiques. Épreuve annulée.");
            etape = Etape.FINI;
            serveur.halt(false);
            return;
        }

        int sol = monde.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, X0, Z0);
        baseY = Math.min(sol + DEGAGEMENT, monde.getMaxY() - (MESURE_HAUTEUR + 40));

        ChunkPos centre = new ChunkPos(
                SectionPos.blockToSectionCoord(X0 + MESURE_COTE / 2),
                SectionPos.blockToSectionCoord(Z0 + MESURE_COTE / 2));
        monde.getChunkSource().addTicketWithRadius(TicketType.PLAYER_LOADING, centre, 4);
        monde.getChunkSource().addTicketWithRadius(TicketType.PLAYER_SIMULATION, centre, 4);

        etape = Etape.CHARGEMENT;
        pause = CHARGEMENT;
        releve = 0;
        Remblai.oublier();
        Lanterne.LOG.info(
                "[TERRASSEMENT] Épreuve armée : boîte de {}×{}×{} = {} blocs à x={} y={} z={}, "
                        + "{} relevés utiles par bras dont {} de chauffe jetés.",
                MESURE_COTE, MESURE_HAUTEUR, MESURE_COTE,
                MESURE_COTE * MESURE_HAUTEUR * MESURE_COTE, X0, baseY, Z0, UTILES, CHAUFFE);
    }

    /** Fait avancer l'épreuve d'un pas. Appelé à chaque tick du serveur. */
    public static void tick(MinecraftServer serveur) {
        if (!running()) {
            return;
        }
        if (pause > 0) {
            pause--;
            return;
        }
        ServerLevel monde = serveur.overworld();
        switch (etape) {
            case CHARGEMENT -> {
                // Sortie supprimée, et pas de rappel de résultat : « performPrefixedCommand » passe
                // CommandResultCallback.EMPTY à l'exécution et ignore celui de la source. On contrôle
                // donc le travail sur le monde, pas sur un accusé qui ne viendrait jamais.
                source = serveur.createCommandSourceStack()
                        .withLevel(monde)
                        .withSuppressedOutput();
                etape = Etape.TEMOIN_A_POSE;
            }
            case TEMOIN_A_POSE -> {
                poserTemoin(monde);
                etape = Etape.TEMOIN_A_REMPLIT;
                pause = REPOS;
            }
            case TEMOIN_A_REMPLIT -> {
                Remblai.suspendre(false);
                remplirTemoin();
                etape = Etape.TEMOIN_A_RELEVE;
                pause = REPOS;
            }
            case TEMOIN_A_RELEVE -> {
                etatsA = releverEtats(monde);
                entitesA = releverEntites(monde);
                etape = Etape.TEMOIN_B_POSE;
            }
            case TEMOIN_B_POSE -> {
                poserTemoin(monde);
                etape = Etape.TEMOIN_B_REMPLIT;
                pause = REPOS;
            }
            case TEMOIN_B_REMPLIT -> {
                Remblai.suspendre(true);
                remplirTemoin();
                etape = Etape.TEMOIN_B_RELEVE;
                pause = REPOS;
            }
            case TEMOIN_B_RELEVE -> {
                etatsB = releverEtats(monde);
                entitesB = releverEntites(monde);
                Remblai.suspendre(false);
                if (!juger(monde)) {
                    terminer(serveur);
                    return;
                }
                etape = Etape.MESURE_PREPARE;
            }
            case MESURE_PREPARE -> {
                Remblai.suspendre(true);
                nettoyerTemoin(monde);
                remplir(monde, X0, baseY, Z0,
                        X0 + MESURE_COTE - 1, baseY + MESURE_HAUTEUR - 1, Z0 + MESURE_COTE - 1, AIR);
                Remblai.suspendre(false);
                if (!blancDeMesure(monde)) {
                    terminer(serveur);
                    return;
                }
                Remblai.oublier();
                etape = Etape.MESURE;
            }
            case MESURE -> mesurer(serveur, monde);
            default -> { }
        }
    }

    // ────────────────────────────── le témoin ──────────────────────────────

    /**
     * Bâtit la scène témoin, à l'identique, module suspendu.
     *
     * <p>La coque de pierre d'abord, l'intérieur ensuite. Tout est posé avec
     * {@code Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE} : aucun voisin n'est prévenu, aucune forme
     * n'est recalculée, aucun paquet ne part. Une torche sans support reste donc en place, et c'est
     * voulu — c'est le remplissage <b>mesuré</b> qui devra la faire tomber, des deux côtés
     * pareillement.
     */
    private static void poserTemoin(ServerLevel monde) {
        Remblai.suspendre(true);
        nettoyerTemoin(monde);

        int x1 = X0 + 4;
        int y1 = baseY + 24;
        int z1 = Z0 + 4;
        int x2 = x1 + TEMOIN_COTE - 1;
        int y2 = y1 + TEMOIN_HAUTEUR - 1;
        int z2 = z1 + TEMOIN_COTE - 1;

        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    boolean coque = x == x1 || x == x2 || y == y1 || y == y2 || z == z1 || z == z2;
                    poser(monde, x, y, z, coque ? PIERRE : choisir(x, y, z));
                }
            }
        }
    }

    /**
     * Vide la boîte témoin et sa marge, blocs et entités.
     *
     * <p>Les entités comptent autant que les blocs : un objet resté du premier passage fausserait
     * l'inventaire du second, et le banc refuserait de conclure pour une raison qui ne serait pas la
     * bonne.
     */
    private static void nettoyerTemoin(ServerLevel monde) {
        int x1 = X0 + 4 - MARGE;
        int y1 = baseY + 24 - MARGE;
        int z1 = Z0 + 4 - MARGE;
        int x2 = X0 + 4 + TEMOIN_COTE - 1 + MARGE;
        int y2 = baseY + 24 + TEMOIN_HAUTEUR - 1 + MARGE;
        int z2 = Z0 + 4 + TEMOIN_COTE - 1 + MARGE;
        remplir(monde, x1, y1, z1, x2, y2, z2, AIR);
        for (Entity entite : monde.getEntitiesOfClass(Entity.class, boite(x1, y1, z1, x2, y2, z2))) {
            if (!(entite instanceof Player)) {
                entite.discard();
            }
        }
    }

    /**
     * Les deux remplissages du témoin : on casse, puis on remblaie.
     *
     * <p>Ils ne touchent que l'intérieur de la coque. Le premier met trois couches à l'air — tout ce
     * qui reposait dessus perd son support. Le second pose une couche de pierre en plein milieu de ce
     * qui reste — tout ce qui l'entoure doit se reconnecter.
     */
    private static void remplirTemoin() {
        int x1 = X0 + 5;
        int z1 = Z0 + 5;
        int x2 = X0 + 4 + TEMOIN_COTE - 2;
        int z2 = Z0 + 4 + TEMOIN_COTE - 2;
        int y1 = baseY + 25;
        executer(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
                x1, y1, z1, x2, y1 + 2, z2));
        executer(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone",
                x1, y1 + 4, z1, x2, y1 + 4, z2));
    }

    /** Tous les états de la boîte témoin élargie, dans un ordre fixe. */
    private static BlockState[] releverEtats(ServerLevel monde) {
        int x1 = X0 + 4 - MARGE;
        int y1 = baseY + 24 - MARGE;
        int z1 = Z0 + 4 - MARGE;
        int cote = TEMOIN_COTE + 2 * MARGE;
        int hauteur = TEMOIN_HAUTEUR + 2 * MARGE;
        BlockState[] etats = new BlockState[cote * hauteur * cote];
        BlockPos.MutableBlockPos curseur = new BlockPos.MutableBlockPos();
        int index = 0;
        for (int dx = 0; dx < cote; dx++) {
            for (int dy = 0; dy < hauteur; dy++) {
                for (int dz = 0; dz < cote; dz++) {
                    curseur.set(x1 + dx, y1 + dy, z1 + dz);
                    etats[index++] = monde.getBlockState(curseur);
                }
            }
        }
        return etats;
    }

    /** L'inventaire des entités de la boîte témoin, sous une forme comparable. */
    private static String releverEntites(ServerLevel monde) {
        int x1 = X0 + 4 - MARGE;
        int y1 = baseY + 24 - MARGE;
        int z1 = Z0 + 4 - MARGE;
        int x2 = X0 + 4 + TEMOIN_COTE - 1 + MARGE;
        int y2 = baseY + 24 + TEMOIN_HAUTEUR - 1 + MARGE;
        int z2 = Z0 + 4 + TEMOIN_COTE - 1 + MARGE;
        List<String> lignes = new ArrayList<>();
        for (Entity entite : monde.getEntitiesOfClass(Entity.class, boite(x1, y1, z1, x2, y2, z2))) {
            if (entite instanceof Player) {
                continue;
            }
            if (entite instanceof ItemEntity objet) {
                lignes.add("objet " + objet.getItem().getCount() + "×" + objet.getItem().getItem());
            } else {
                lignes.add(entite.getClass().getSimpleName());
            }
        }
        // Trié : l'ordre de parcours des entités n'est pas un fait de jeu, seule leur composition
        // l'est. Comparer l'ordre ferait échouer le banc sur une différence qui n'en est pas une.
        Collections.sort(lignes);
        return String.join(", ", lignes);
    }

    /**
     * Le veto.
     *
     * <p>Rend vrai si, et seulement si, les deux passages ont laissé exactement le même monde. Au
     * premier écart, la position et les deux états sont publiés : un banc qui dirait « différent »
     * sans dire où ne servirait à rien.
     */
    private static boolean juger(ServerLevel monde) {
        int x1 = X0 + 4 - MARGE;
        int y1 = baseY + 24 - MARGE;
        int z1 = Z0 + 4 - MARGE;
        int cote = TEMOIN_COTE + 2 * MARGE;
        int hauteur = TEMOIN_HAUTEUR + 2 * MARGE;

        int ecarts = 0;
        int premierIndex = -1;
        for (int i = 0; i < etatsA.length; i++) {
            if (etatsA[i] != etatsB[i]) {
                ecarts++;
                if (premierIndex < 0) {
                    premierIndex = i;
                }
            }
        }

        if (ecarts > 0) {
            int dz = premierIndex % cote;
            int dy = (premierIndex / cote) % hauteur;
            int dx = premierIndex / (cote * hauteur);
            Lanterne.LOG.error(String.format(Locale.ROOT,
                    "[TERRASSEMENT] REFUS DE CONCLURE : %d bloc(s) sur %d diffèrent entre le "
                            + "remplissage module actif et module éteint.",
                    ecarts, etatsA.length));
            Lanterne.LOG.error("[TERRASSEMENT] Premier écart à x={} y={} z={} — avec : {} · sans : {}",
                    x1 + dx, y1 + dy, z1 + dz, etatsA[premierIndex], etatsB[premierIndex]);
            Lanterne.LOG.error("[TERRASSEMENT] Aucun chiffre de vitesse ne sera publié : un "
                    + "remplissage qui ne rend pas le même monde n'est pas une optimisation.");
            return false;
        }

        if (!entitesA.equals(entitesB)) {
            Lanterne.LOG.error("[TERRASSEMENT] REFUS DE CONCLURE : les blocs concordent mais pas les "
                    + "entités laissées sur place.");
            Lanterne.LOG.error("[TERRASSEMENT] avec : [{}]", entitesA);
            Lanterne.LOG.error("[TERRASSEMENT] sans : [{}]", entitesB);
            return false;
        }

        Lanterne.LOG.info(
                "[TERRASSEMENT] Témoin concluant : {} blocs identiques, entités identiques ([{}]). "
                        + "La mesure de vitesse peut commencer.",
                etatsA.length, entitesA.isEmpty() ? "aucune" : entitesA);
        // La boîte témoin a fini son office ; on la vide pour ne rien laisser tomber sur le chantier
        // chronométré, qui se trouve juste en dessous.
        nettoyerTemoin(monde);
        return true;
    }

    // ────────────────────────────── la mesure ──────────────────────────────

    /**
     * Le blanc : la commande fait-elle vraiment ce qu'elle annonce, sur toute la boîte ?
     *
     * <p>Une fois, avant le premier chrono, et sur <b>tous</b> les blocs — pas sur un échantillon.
     * {@code performPrefixedCommand} avale ses propres échecs : une erreur d'analyse, une zone hors
     * limites ou un chunk absent rendraient la main sans rien dire, et l'épreuve publierait des temps
     * magnifiques pour une commande qui ne fait rien. Le contrôle coûte deux parcours de seize mille
     * blocs, une seule fois.
     */
    private static boolean blancDeMesure(ServerLevel monde) {
        int x2 = X0 + MESURE_COTE - 1;
        int y2 = baseY + MESURE_HAUTEUR - 1;
        int z2 = Z0 + MESURE_COTE - 1;
        executer(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone",
                X0, baseY, Z0, x2, y2, z2));
        int pierres = compter(monde, PIERRE);
        executer(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
                X0, baseY, Z0, x2, y2, z2));
        int airs = compter(monde, AIR);

        int attendu = MESURE_COTE * MESURE_HAUTEUR * MESURE_COTE;
        if (pierres != attendu || airs != attendu) {
            Lanterne.LOG.error("[TERRASSEMENT] REFUS DE CONCLURE : le /fill de contrôle n'a pas "
                    + "rempli la boîte — {} pierre(s) puis {} air(s) sur {} attendus. La commande "
                    + "n'atteint pas le terrain (chunks absents, limite de règle de jeu, zone hors "
                    + "monde ?).", pierres, airs, attendu);
            return false;
        }
        return true;
    }

    /** Combien de blocs de la boîte chronométrée portent cet état ? */
    private static int compter(ServerLevel monde, BlockState attendu) {
        int compte = 0;
        for (BlockPos pos : BlockPos.betweenClosed(X0, baseY, Z0,
                X0 + MESURE_COTE - 1, baseY + MESURE_HAUTEUR - 1, Z0 + MESURE_COTE - 1)) {
            if (monde.getBlockState(pos) == attendu) {
                compte++;
            }
        }
        return compte;
    }

    /**
     * Un relevé par tick.
     *
     * <p>Un relevé est un aller-retour complet : {@code /fill} en pierre, puis {@code /fill} en air.
     * Les deux sens comptent — poser et casser sont le même module, et la casse est le geste du
     * mineur. La boîte finit donc chaque relevé exactement comme elle l'a commencé, en air, ce qui
     * rend le relevé suivant comparable sans remise en état.
     *
     * <p>Un seul relevé par tick, pour la raison que {@code lab/Boom} explique : empiler plusieurs
     * charges dans un même tick fabriquerait le pic que {@code Rationing} est conçu pour absorber, et
     * l'on mesurerait alors le mod en train de réagir à l'épreuve.
     */
    private static void mesurer(MinecraftServer serveur, ServerLevel monde) {
        int rang = releve / 2;
        boolean avec = (releve % 2) == 0;

        int x2 = X0 + MESURE_COTE - 1;
        int y2 = baseY + MESURE_HAUTEUR - 1;
        int z2 = Z0 + MESURE_COTE - 1;
        String pose = String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone",
                X0, baseY, Z0, x2, y2, z2);
        String casse = String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air",
                X0, baseY, Z0, x2, y2, z2);

        Remblai.suspendre(!avec);
        long debut = System.nanoTime();
        executer(pose);
        long milieu = System.nanoTime();
        executer(casse);
        long fin = System.nanoTime();
        Remblai.suspendre(false);

        // Contrôle de réalité : une commande qui échoue en silence rendrait des chiffres superbes.
        BlockPos temoin = new BlockPos(X0 + MESURE_COTE / 2, baseY + MESURE_HAUTEUR / 2,
                Z0 + MESURE_COTE / 2);
        if (!monde.getBlockState(temoin).isAir()) {
            Lanterne.LOG.error("[TERRASSEMENT] REFUS DE CONCLURE : après le remplissage en air, "
                    + "x={} y={} z={} contient {}. La commande n'a pas fait ce qu'elle dit.",
                    temoin.getX(), temoin.getY(), temoin.getZ(), monde.getBlockState(temoin));
            terminer(serveur);
            return;
        }

        if (rang >= CHAUFFE) {
            (avec ? AVEC : SANS)[rang - CHAUFFE] = (fin - debut);
            if (rang == CHAUFFE && avec) {
                Lanterne.LOG.info(String.format(Locale.ROOT,
                        "[TERRASSEMENT] Chauffe finie. Premier relevé utile : pose %.1f ms, "
                                + "casse %.1f ms.",
                        (milieu - debut) / 1e6, (fin - milieu) / 1e6));
            }
        }

        releve++;
        if (releve >= 2 * (CHAUFFE + UTILES)) {
            rapporter();
            terminer(serveur);
        }
    }

    /** Le verdict. */
    private static void rapporter() {
        double avec = mediane(AVEC);
        double sans = mediane(SANS);
        double rapport = avec <= 0d ? 0d : sans / avec;
        int blocs = MESURE_COTE * MESURE_HAUTEUR * MESURE_COTE;

        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[TERRASSEMENT] ── Aller-retour /fill sur %d blocs (pose + casse), médiane sur %d "
                        + "relevés par bras ──", blocs, UTILES));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[TERRASSEMENT] Sans Lanterne : %.2f ms (%.0f ns/bloc) · Avec Lanterne : %.2f ms "
                        + "(%.0f ns/bloc)",
                sans / 1e6, sans / (2d * blocs), avec / 1e6, avec / (2d * blocs)));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[TERRASSEMENT] Évités pendant l'épreuve : %d recalculs de forme, %d notifications "
                        + "de voisinage, %d relectures de chemin.",
                Remblai.formesEvitees(), Remblai.voisinagesEvites(), Remblai.navigationsEvitees()));

        if (rapport > 1.05d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[TERRASSEMENT] VERDICT : gain ×%.2f, soit %.0f %% de temps en moins sur la part "
                            + "synchrone du remplissage. La propagation de la lumière n'est pas dans "
                            + "ce chiffre : le gain sur le tick entier est plus faible.",
                    rapport, (1d - avec / sans) * 100d));
        } else if (rapport < 0.95d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[TERRASSEMENT] VERDICT : PERTE ×%.2f — le module coûte plus cher qu'il ne "
                            + "rapporte sur cette charge. À éteindre, ou à refaire.", rapport));
        } else {
            Lanterne.LOG.info("[TERRASSEMENT] VERDICT : aucun effet mesurable — l'écart est sous le "
                    + "bruit de fond. Le module ne mérite pas d'être allumé sur cette charge.");
        }
    }

    // ────────────────────────────── utilitaires ──────────────────────────────

    /** Exécute une commande par le répartiteur du serveur, sortie supprimée. */
    private static void executer(String commande) {
        hote.getCommands().performPrefixedCommand(source, commande);
    }

    /**
     * Pose un bloc sans le moindre effet de bord.
     *
     * <p>{@code Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE} = 276, exactement le drapeau que
     * {@code LevelChunk.postProcessGeneration} s'envoie à lui-même : ni notification de voisinage, ni
     * recalcul de forme, ni paquet client, ni effet de bloc-entité. C'est ce qui rend la construction
     * du témoin reproductible au bloc près.
     */
    private static void poser(ServerLevel monde, int x, int y, int z, BlockState etat) {
        monde.setBlock(new BlockPos(x, y, z), etat, Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE);
    }

    /** Remplit une boîte, sans effet de bord. Voir {@link #poser}. */
    private static void remplir(ServerLevel monde, int x1, int y1, int z1,
                                int x2, int y2, int z2, BlockState etat) {
        int drapeaux = Block.UPDATE_NONE | Block.UPDATE_KNOWN_SHAPE;
        for (BlockPos pos : BlockPos.betweenClosed(x1, y1, z1, x2, y2, z2)) {
            monde.setBlock(pos, etat, drapeaux);
        }
    }

    /** Le bloc de la scène témoin en (x, y, z) — déterministe, donc reproductible. */
    private static BlockState choisir(int x, int y, int z) {
        int melange = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
        return PALETTE[Math.floorMod(melange, PALETTE.length)];
    }

    /** La boîte englobante d'un pavé de blocs, bornes comprises. */
    private static AABB boite(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new AABB(x1, y1, z1, x2 + 1d, y2 + 1d, z2 + 1d);
    }

    /** La médiane, jamais la moyenne — même raison que {@code report/Bench}. */
    private static double mediane(long[] valeurs) {
        long[] copie = valeurs.clone();
        java.util.Arrays.sort(copie);
        int milieu = copie.length / 2;
        return copie.length % 2 == 0
                ? (copie[milieu - 1] + copie[milieu]) / 2d
                : copie[milieu];
    }

    private static void terminer(MinecraftServer serveur) {
        Remblai.suspendre(false);
        etape = Etape.FINI;
        serveur.halt(false);
    }
}
