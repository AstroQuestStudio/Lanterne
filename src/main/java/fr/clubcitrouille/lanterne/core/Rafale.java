package fr.clubcitrouille.lanterne.core;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

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

    // Voir Increvable.EPARGNES pour le pourquoi de cette paire de compteurs (ecrits depuis le fil du
    // serveur, lus depuis Radiographie.report() sur un autre fil en fin de session).
    private static final AtomicLong POSES = new AtomicLong();
    private static final AtomicLong CASSES = new AtomicLong();

    private Rafale() {}

    /** Ce joueur a-t-il le droit d'emprunter ce pont ? Même question qu'{@link Increvable#epargne}. */
    public static boolean autorise(ServerPlayer player) {
        return Settings.rafale() && player.connection.hasChannel(AutominerPresence.TYPE);
    }

    /**
     * Traite une requête déjà validée par {@code core.network.RafaleNet} (canal, réglage, taille).
     * Ne lève jamais : une case refusée est sautée, jamais une raison d'abandonner les autres.
     */
    public static void traiter(ServerPlayer player, RafaleRequest request) {
        Level level = player.level();
        if (!player.mayBuild()) {
            return; // aventure, spectateur : vanilla le refuserait aussi a une pose normale.
        }
        if (request.demolish()) {
            demolir(player, level, request.positions());
        } else {
            batir(player, level, request.positions(), request.states());
        }
    }

    private static void demolir(ServerPlayer player, Level level, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            if (level.getBlockState(pos).isAir()) {
                continue;
            }
            if (player.gameMode.destroyBlock(pos)) {
                CASSES.incrementAndGet();
            }
        }
    }

    private static void batir(ServerPlayer player, Level level, List<BlockPos> positions,
            List<BlockState> states) {
        if (positions.size() != states.size()) {
            return; // paquet incoherent : rien de sur a en tirer, on refuse tout plutot que devinner.
        }
        boolean creatif = player.hasInfiniteMaterials();
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            BlockState wanted = states.get(i);
            if (level.getBlockState(pos).is(wanted.getBlock()) && level.getBlockState(pos).equals(wanted)) {
                continue; // deja en place : ni consommer ni reposer.
            }
            if (!creatif && !consommer(player, wanted)) {
                continue; // matiere manquante : sautee, jamais posee gratuitement.
            }
            if (level.setBlock(pos, wanted, Block.UPDATE_ALL, 512)) {
                POSES.incrementAndGet();
            }
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
