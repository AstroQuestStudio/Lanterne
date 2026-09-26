package fr.clubcitrouille.lanterne.content.reseau;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import org.jspecify.annotations.Nullable;

/**
 * Le Coffre de dépôt : un coffre ordinaire à l'usage, un nœud du Réseau sans qu'on ait rien à faire.
 *
 * <h2>Pourquoi un bloc à soi, et pas simplement « posez un Aiguillage sous un coffre »</h2>
 *
 * <p>Le patron du projet a demandé deux positions FIXES et connues d'avance, pour qu'un bot minier
 * externe ({@code ClubCitrouilleAutoMiner}, un autre dépôt) puisse y déverser son butin sans avoir à
 * découvrir ou mémoriser où sont les deux cents coffres de la base. Un coffre vanilla relié par un
 * Aiguillage aurait tout aussi bien fonctionné pour un JOUEUR — mais son emplacement dépendrait alors
 * de la construction du joueur, pas d'un contrat stable que l'extérieur peut coder en dur une fois pour
 * toutes.
 *
 * <h2>Pourquoi pas {@code ChestBlock} lui-même, seulement {@code ChestBlockEntity}</h2>
 *
 * <p>{@code ChestBlock} vanilla porte toute la mécanique de coffre DOUBLE — {@code ChestType},
 * {@code chestCanConnectTo}, le combineur de deux blocs-entités en un seul conteneur de 54 cases, le
 * chat qui empêche l'ouverture. Rien de tout cela n'est nécessaire à un point de dépôt fixe, et
 * reproduire son modèle 3D à deux battants animés serait du travail pur pour un bloc que le patron n'a
 * jamais demandé de voir s'ouvrir en jeu de cette façon précise. Ce bloc est donc un cube simple,
 * rendu par un modèle de bloc-état normal ({@link #getRenderShape}) — le conteneur qu'il ouvre au clic
 * est, lui, le VRAI {@code ChestMenu} vanilla à 27 cases, hérité tel quel par {@link CoffreDepotBlockEntity}.
 */
public class CoffreDepot extends BaseEntityBlock {
    public CoffreDepot(Properties properties) {
        super(properties);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CoffreDepotBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * Ouvre le coffre. Vanilla laisse déjà tomber au chemin « sans objet » quand {@code useItemOn} rend
     * {@code PASS} — comme {@code ChestBlock} lui-même, aucune redéfinition de {@code useItemOn} n'est
     * nécessaire pour qu'un clic avec un objet en main ouvre quand même le coffre.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof CoffreDepotBlockEntity coffre)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            Liaison.commencer(player, level, pos, coffre);
            return InteractionResult.SUCCESS_SERVER;
        }
        player.openMenu(coffre);
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * L'envoi vers la salle de tri (26.3.142) : une passe par seconde, décalée par position pour que
     * vingt coffres ne tombent pas tous sur le même tick. Rien sans destination — voir
     * {@link CoffreDepotBlockEntity#envoyer}.
     */
    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        return level.isClientSide() ? null
                : createTickerHelper(type, Reseaux.COFFRE_DEPOT_ENTITY.get(), CoffreDepot::tick);
    }

    private static void tick(Level level, BlockPos pos, BlockState state, CoffreDepotBlockEntity coffre) {
        if (coffre.envoi() == null || !(level instanceof ServerLevel server)
                || (server.getGameTime() + (pos.hashCode() & 0xFF)) % CoffreDepotBlockEntity.CADENCE_ENVOI != 0L) {
            return;
        }
        coffre.envoyer(server);
    }

    /**
     * Relier un Coffre de dépôt à sa destination, en jeu, sans commande : accroupi main vide sur le
     * coffre (« liaison en cours », trente secondes), puis accroupi main vide sur la destination. Le
     * même geste sur le coffre lui-même coupe l'envoi. Le second clic est pris AVANT le bloc visé
     * ({@code RightClickBlock}, annulé) : la destination — souvent un coffre — ne s'ouvre pas.
     *
     * <p>L'attente vit en mémoire, par joueur, jamais sauvegardée : un redémarrage l'oublie, et c'est
     * très bien pour un geste de trente secondes.
     */
    public static final class Liaison {
        private static final long DUREE_TICKS = 600L;

        private record Attente(ResourceKey<Level> monde, BlockPos coffre, long debut) {}

        private static final Map<UUID, Attente> ATTENTES = new ConcurrentHashMap<>();

        private Liaison() {}

        static void commencer(Player player, Level level, BlockPos pos, CoffreDepotBlockEntity coffre) {
            ATTENTES.put(player.getUUID(), new Attente(level.dimension(), pos.immutable(), level.getGameTime()));
            BlockPos actuel = coffre.envoi();
            Reseaux.tell(player, (actuel == null ? "Aucun envoi." : "Envoi actuel : "
                    + actuel.getX() + " " + actuel.getY() + " " + actuel.getZ() + ".")
                    + " Liaison : accroupi, main vide, clic droit sur le conteneur de destination"
                    + " (ce coffre-ci pour couper l'envoi) — 30 s.");
        }

        @SubscribeEvent
        public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
            Player player = event.getEntity();
            if (event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND
                    || !player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()) {
                return;
            }
            Attente attente = ATTENTES.get(player.getUUID());
            if (attente == null) {
                return;
            }
            Level level = event.getLevel();
            BlockPos cible = event.getPos();
            long maintenant = level.getGameTime();
            if (!level.dimension().equals(attente.monde()) || maintenant - attente.debut() > DUREE_TICKS) {
                ATTENTES.remove(player.getUUID());
                return;
            }
            // Cet évènement passe AVANT useWithoutItem : le clic qui lance une liaison n'arrive jamais
            // ici avec sa propre attente. Une attente trouvée est donc toujours celle d'un geste précédent.
            ATTENTES.remove(player.getUUID());
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS_SERVER);
            if (!(level.getBlockEntity(attente.coffre()) instanceof CoffreDepotBlockEntity coffre)) {
                Reseaux.tell(player, "Le Coffre de dépôt a disparu : liaison annulée.");
                return;
            }
            if (cible.equals(attente.coffre())) {
                coffre.setEnvoi(null);
                Reseaux.tell(player, "Envoi coupé : coffre de dépôt ordinaire.");
                return;
            }
            if (!(level.getBlockEntity(cible) instanceof Container)) {
                Reseaux.tell(player, "Ce bloc n'est pas un conteneur : liaison annulée.");
                return;
            }
            if (!coffre.setEnvoi(cible)) {
                Reseaux.tell(player, "Trop loin (" + CoffreDepotBlockEntity.PORTEE_ENVOI
                        + " blocs au plus) : liaison annulée.");
                return;
            }
            Reseaux.tell(player, "Envoi réglé : ce coffre se videra chaque seconde vers "
                    + cible.getX() + " " + cible.getY() + " " + cible.getZ() + ".");
        }
    }
}
