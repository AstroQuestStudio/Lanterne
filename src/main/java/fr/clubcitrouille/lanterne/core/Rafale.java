package fr.clubcitrouille.lanterne.core;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.network.AutominerPresence;
import fr.clubcitrouille.lanterne.core.network.RafaleRequest;

/**
 * Le pont AutoMiner pour la construction en masse : même forme que {@link Increvable}, une capacité
 * bien plus lourde.
 *
 * <h2>Pourquoi ce n'est PAS réservé aux opérateurs</h2>
 *
 * <p>Le premier réflexe — exiger aussi la permission d'opérateur, comme {@code /lanterne vol} — a été
 * écarté <b>à la demande explicite du patron</b> : ce pont doit fonctionner pour n'importe quel joueur
 * survie qui a réellement AutoMiner chargé, pas seulement pour les opérateurs. La garde reste donc
 * exactement celle d'{@link Increvable} : le réglage serveur ({@link Config#RAFALE}, ÉTEINT par
 * défaut — voir plus bas pourquoi ce défaut diffère de celui d'Increvable) ET le canal d'identité
 * {@link AutominerPresence}, jamais autre chose. C'est un pont entre deux mods que la même personne
 * possède, pas un privilège qu'un opérateur distribue un par un.
 *
 * <h2>Pourquoi le réglage serveur est ÉTEINT par défaut, contrairement à celui d'Increvable</h2>
 *
 * <p>Épargner une durabilité est une commodité ; remplir une région en un geste est une capacité de
 * construction, à l'échelle d'un {@code /fill} vanilla — un administrateur qui installe ce mod ne
 * s'attend pas à ce que tout joueur survie équipé d'AutoMiner puisse soudain bâtir aussi vite qu'un
 * opérateur. Ouvrir ce pont reste un choix conscient du serveur, jamais un effet de bord de
 * l'installation du mod.
 *
 * <h2>« Casser, pas supprimer »</h2>
 *
 * <p>La démolition en masse ne vide pas les cases : elle les <b>casse</b>, une par une, par
 * {@code ServerPlayerGameMode.destroyBlock} — le même chemin que vanilla emprunte pour un bris de
 * bloc normal, donc les mêmes drops, le même calcul d'outil, la même usure. En créatif ce chemin
 * détruit déjà instantanément sans drop, sans rien de spécial à écrire ; en survie il se comporte
 * en vraie casse. Rien ici ne fait disparaître un bloc gratuitement.
 *
 * <p>La construction, côté survie, consomme réellement l'objet correspondant dans l'inventaire du
 * joueur ({@link #consommer}) — une case dont le joueur n'a plus la matière est sautée, jamais posée
 * gratuitement. En créatif ({@code player.hasInfiniteMaterials()}), aucune consommation.
 *
 * <h2>Ce que ce pont ne fait pas</h2>
 *
 * <p>Il ne simule pas une vraie interaction de pose : pas d'orientation selon le regard du joueur,
 * pas de données de bloc-entité recopiées, pas de logique de voisinage (torche qui a besoin d'un
 * support, porte à deux moitiés posée en une fois). {@code wanted} — l'état exact voulu, tel
 * qu'AutoMiner l'a déjà résolu depuis son plan — est posé tel quel. C'est une extension du principe
 * déjà validé dans {@code BuildSite.clear()}/{@code BUILD_CLEAR_CREATIVE_BATCH} : plusieurs gestes
 * par tick plutôt qu'une vraie simulation d'interaction.
 *
 * <h2>Pourquoi une requête n'est plus traitée d'un seul coup</h2>
 *
 * <p>Un crash réel et reproductible (20/09/2026, deux occurrences) fait perdre la connexion en jeu :
 * {@code DecoderException: Failed to decode packet 'clientbound/minecraft:recipe_book_add'}, cause
 * {@code Optional.orElseThrow} dans un codec basé registre de {@code RecipeDisplayEntry}. Les deux
 * occurrences partagent un motif commun — un très grand nombre d'objets de types différents devient
 * disponible pour le joueur dans la même poignée de ticks (une fois un {@code /give} massif, une fois
 * une démolition en masse par ce pont) — ce qu'un joueur humain ne produit jamais en jeu normal, et
 * qui multiplie d'un coup les récompenses de recettes accordées par {@code AdvancementRewards}
 * (javap confirmé : c'est elle, et non un déclencheur d'inventaire direct, qui appelle
 * {@code ServerPlayer.awardRecipesByKey}, empaqueté ensuite en {@code ClientboundRecipeBookAddPacket}).
 * L'endroit exact du bug dans ce codec — probablement lié à {@code RecipeDisplayEntry.category}
 * (registre) ou {@code craftingRequirements} (liste d'ingrédients, potentiellement basés sur une
 * étiquette) — n'a pas pu être isolé avec certitude par lecture de bytecode seule, faute d'un
 * débogueur en direct ; c'est une bêta de moteur ({@code neo_version=26.3.0.7-beta}), pas un fichier
 * que ce dépôt possède.
 *
 * <p>Sans pouvoir corriger le vrai codec, on peut réduire le risque de le déclencher : traiter au
 * plus {@link #BATCH_PAR_TICK} cases par tick au lieu des 4096 d'une requête d'un coup ramène le
 * débit d'objets nouvellement disponibles à un ordre de grandeur bien plus proche de ce qu'un joueur
 * humain produit en minant normalement — le rythme qui n'a, lui, jamais fait planter personne. Les
 * vrais drops restent inchangés (voir plus haut, « Casser, pas supprimer ») : ce n'est pas leur
 * existence qui est en cause ici, seulement leur concentration dans le temps.
 */
public final class Rafale {
    /**
     * Cases par requête, au maximum — répété dans {@link RafaleRequest#LIMIT} pour que le codec
     * refuse un paquet hors-borne avant même de le passer ici. Comparable au {@code /fill} vanilla
     * (32 768 par défaut, relevable jusqu'à 2 097 152 par le gamerule) mais délibérément plus bas :
     * ce pont s'active plusieurs fois par seconde depuis un bot, pas une fois par commande tapée à
     * la main — voir aussi {@code LanterneCommand.MAX_HERD}, même philosophie : le but est de
     * charger le serveur, pas de le tuer.
     */
    public static final int LIMIT = RafaleRequest.LIMIT;

    /**
     * Cases traitées par joueur, par tick — voir la Javadoc de classe pour le crash que ce plafond
     * atténue. Trente-deux fois le rythme de {@code BuildSite.build()} en vol (32/tick) : largement
     * assez pour rester bien plus rapide que la pose case par case, largement en dessous des 4096
     * d'un coup qui ont accompagné les deux occurrences réelles du crash.
     */
    private static final int BATCH_PAR_TICK = 256;

    /**
     * File d'attente par joueur, au-delà de laquelle une nouvelle requête est refusée plutôt
     * qu'empilée indéfiniment. Trois fois {@link #LIMIT} : de quoi absorber une requête pleine
     * pendant que la précédente finit de s'égrener sans laisser la mémoire grossir sans fin si un
     * client mal élevé en envoie plus vite qu'elles ne se vident.
     */
    private static final int FILE_MAX = LIMIT * 3;

    // Voir Increvable.EPARGNES pour le pourquoi de cette paire de compteurs (ecrits depuis le fil du
    // serveur, lus depuis Radiographie.report() sur un autre fil en fin de session).
    private static final AtomicLong POSES = new AtomicLong();
    private static final AtomicLong CASSES = new AtomicLong();

    /** Une case en attente, position et etat voulu confondus — {@code wanted} vaut {@code null} en demolition. */
    private record Operation(BlockPos position, BlockState wanted, boolean demolish) {}

    /** Une file par joueur. {@code ConcurrentHashMap} : cree depuis le fil reseau, videe depuis le fil du serveur. */
    private static final Map<UUID, ArrayDeque<Operation>> FILES = new ConcurrentHashMap<>();

    private Rafale() {}

    public static void register() {
        NeoForge.EVENT_BUS.register(Rafale.class);
    }

    /** Ce joueur a-t-il le droit d'emprunter ce pont ? Même question qu'{@link Increvable#epargne}. */
    public static boolean autorise(ServerPlayer player) {
        return Settings.rafale() && player.connection.hasChannel(AutominerPresence.TYPE);
    }

    /**
     * Met une requête déjà validée par {@code core.network.RafaleNet} (canal, réglage, taille) en
     * file d'attente — voir la Javadoc de classe pour pourquoi elle n'est plus traitée d'un coup.
     * Ne lève jamais : une requête incohérente ou une file pleine est ignorée, jamais une raison de
     * faire échouer autre chose.
     */
    public static void enqueue(ServerPlayer player, RafaleRequest request) {
        List<BlockPos> positions = request.positions();
        List<BlockState> states = request.states();
        if (!request.demolish() && positions.size() != states.size()) {
            return; // paquet incoherent : rien de sur a en tirer, on refuse tout plutot que devinner.
        }
        ArrayDeque<Operation> file = FILES.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>());
        if (file.size() >= FILE_MAX) {
            Lanterne.LOG.warn("[RAFALE] file pleine pour {} : requete ignoree ({} en attente)",
                    player.getGameProfile().name(), file.size());
            return;
        }
        for (int i = 0; i < positions.size(); i++) {
            file.addLast(new Operation(positions.get(i), request.demolish() ? null : states.get(i),
                    request.demolish()));
        }
    }

    /**
     * Vide jusqu'à {@link #BATCH_PAR_TICK} cases par joueur en attente. Un joueur qui s'est
     * déconnecté entre l'envoi et le tick voit sa file abandonnée sans traitement — {@code mayBuild}
     * et l'inventaire n'ont plus de sens pour un joueur qui n'est plus là.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (FILES.isEmpty()) {
            return;
        }
        for (UUID id : List.copyOf(FILES.keySet())) {
            ArrayDeque<Operation> file = FILES.get(id);
            if (file == null) {
                continue;
            }
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(id);
            if (player == null) {
                FILES.remove(id);
                continue;
            }
            drain(player, file);
            if (file.isEmpty()) {
                FILES.remove(id);
            }
        }
    }

    private static void drain(ServerPlayer player, ArrayDeque<Operation> file) {
        Level level = player.level();
        if (!player.mayBuild()) {
            file.clear(); // aventure, spectateur : vanilla refuserait aussi une pose normale.
            return;
        }
        boolean creatif = player.hasInfiniteMaterials();
        for (int traitees = 0; traitees < BATCH_PAR_TICK && !file.isEmpty(); traitees++) {
            Operation op = file.pollFirst();
            if (op.demolish()) {
                demolirUne(player, level, op.position());
            } else {
                batirUne(player, level, op.position(), op.wanted(), creatif);
            }
        }
    }

    private static void demolirUne(ServerPlayer player, Level level, BlockPos pos) {
        if (level.getBlockState(pos).isAir()) {
            return;
        }
        if (player.gameMode.destroyBlock(pos)) {
            CASSES.incrementAndGet();
        }
    }

    private static void batirUne(ServerPlayer player, Level level, BlockPos pos, BlockState wanted,
            boolean creatif) {
        if (level.getBlockState(pos).is(wanted.getBlock()) && level.getBlockState(pos).equals(wanted)) {
            return; // deja en place : ni consommer ni reposer.
        }
        if (!creatif && !consommer(player, wanted)) {
            return; // matiere manquante : sautee, jamais posee gratuitement.
        }
        if (level.setBlock(pos, wanted, Block.UPDATE_ALL, 512)) {
            POSES.incrementAndGet();
        }
    }

    /** Retire un exemplaire de l'objet qui pose ce bloc. Vrai si l'inventaire en avait un. */
    private static boolean consommer(ServerPlayer player, BlockState wanted) {
        Item item = wanted.getBlock().asItem();
        if (item == net.minecraft.world.item.Items.AIR) {
            return false;
        }
        var inventory = player.getInventory();
        int retires = inventory.clearOrCountMatchingItems(
                stack -> stack.is(item), false, 1, inventory);
        return retires > 0;
    }

    /** Un résumé lisible, même statut que {@link Increvable#report()}. */
    public static String report() {
        long poses = POSES.get();
        long casses = CASSES.get();
        if (poses == 0L && casses == 0L) {
            return "rafale : aucune pose/casse en masse (reglage \"rafale\" eteint, ou aucun client "
                    + "AutoMiner detecte depuis le demarrage)";
        }
        return String.format(Locale.ROOT,
                "rafale : %d bloc(s) pose(s), %d bloc(s) casse(s) en masse (pont AutoMiner)",
                poses, casses);
    }
}
