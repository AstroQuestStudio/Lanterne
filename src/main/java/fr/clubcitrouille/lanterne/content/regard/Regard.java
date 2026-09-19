package fr.clubcitrouille.lanterne.content.regard;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import fr.clubcitrouille.lanterne.mixin.FurnaceProgressAccessor;

/**
 * Le côté serveur du Regard : ce qu'il accepte de révéler, et ce qu'il refuse.
 *
 * <h2>La même méfiance qu'ailleurs dans ce mod</h2>
 *
 * <p>Un client annonce une position ; le serveur ne la croit qu'après l'avoir revérifiée —
 * exactement la règle que {@code content.waypoint.Waypoints} pose pour la propriété d'un repère.
 * Ici, trois vérifications avant qu'un seul octet ne parte :
 *
 * <ol>
 *   <li><b>La distance.</b> {@link #RANGE} borne ce qu'un client modifié pourrait interroger — en
 *       jeu normal, {@code Minecraft.hitResult} ne désigne jamais rien d'aussi loin, puisqu'il est
 *       déjà borné par la portée d'interaction du joueur.</li>
 *   <li><b>Le débit.</b> {@link #MIN_INTERVAL_MS} referme la porte à un client qui redemanderait en
 *       boucle serrée — un coût nul pour un joueur honnête (voir {@code client.regard.Sight}, qui ne
 *       redemande que sur changement de cible ou toutes les quelques images), une digue pour un
 *       client qui ne le serait pas.</li>
 *   <li><b>La nature du bloc.</b> Seul un {@link Container} réel est décrit ; tout le reste est
 *       silence, jamais une erreur qui romprait la connexion.</li>
 * </ol>
 *
 * <h2>Pourquoi ce paquet existe alors que ce mod en a déjà beaucoup</h2>
 *
 * <p>Le contenu d'un conteneur, et plus encore la progression d'un four, n'existent nulle part côté
 * client tant que son menu n'a pas été ouvert — voir la Javadoc de {@link RegardAsk}. Un module qui
 * prétendrait construire cet aperçu sans ce paquet devrait inventer des chiffres ; celui-ci ne le
 * fait jamais.
 */
public final class Regard {
    /** Version du protocole. */
    private static final String PROTOCOL = "1";

    /**
     * Articles dominants envoyés au plus, par réponse — voir {@link RegardTell#dominant}.
     *
     * <p>Trois : assez pour dire ce qui pèse vraiment dans un coffre, jamais assez pour reconstituer
     * un inventaire. La version précédente envoyait les douze premières piles trouvées en fouillant
     * les cases dans l'ordre — une grille illisible dès qu'un coffre de bazar rangeait trente-deux
     * objets différents. Celle-ci additionne les quantités par TYPE sur le conteneur entier et ne
     * garde que les plus gros tas ; {@link RegardTell#filled} continue de porter le compte RÉEL
     * d'emplacements occupés, indépendamment de ce plafond.
     */
    static final int DOMINANT_CAP = 3;

    /**
     * Portée au-delà de laquelle une demande est refusée, en blocs.
     *
     * <p>Généreuse par rapport à ce qu'un joueur peut réellement viser : {@code Minecraft.hitResult},
     * seule source de {@link RegardAsk#pos} côté client (voir {@code client.regard.Sight}), est déjà
     * borné par la portée d'interaction du joueur — quelques blocs en survie. Ce plafond-ci n'est
     * donc quasiment jamais atteint en jeu normal ; il existe pour qu'un client modifié ne puisse pas
     * interroger un conteneur à l'autre bout de la carte.
     */
    private static final double RANGE = 24.0d;
    private static final double RANGE_SQ = RANGE * RANGE;

    /** Un même joueur ne peut pas obtenir deux réponses plus vite que ça. */
    private static final long MIN_INTERVAL_MS = 100L;

    /** Dernier octroi par joueur, en millisecondes horloge murale — un compteur, rien de plus. */
    private static final Map<UUID, Long> lastAnswered = new HashMap<>();

    private Regard() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(Regard::onRegisterPayloads);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        registrar.playToServer(RegardAsk.TYPE, RegardAsk.STREAM_CODEC, Regard::onAsk);
        registrar.playToClient(RegardTell.TYPE, RegardTell.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> fr.clubcitrouille.lanterne.client.regard.Sight.accept(payload)));
    }

    /**
     * Traite une demande, sur le fil du serveur.
     *
     * <p>{@code enqueueWork} n'est pas une précaution de style : le gestionnaire arrive sur le fil
     * réseau, et lire un bloc-entité depuis là serait une course avec le tick qui le modifie —
     * même règle que {@code content.waypoint.Waypoints#onAsk}.
     */
    private static void onAsk(RegardAsk ask, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            long now = System.currentTimeMillis();
            Long last = lastAnswered.get(player.getUUID());
            if (last != null && now - last < MIN_INTERVAL_MS) {
                return;
            }

            BlockPos pos = ask.pos();
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > RANGE_SQ) {
                return;
            }
            Level level = player.level();
            if (!level.isLoaded(pos)) {
                return;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof Container container)) {
                return;
            }
            // Le compteur n'avance qu'une fois la cible confirmée valide : un flot de demandes sur
            // un bloc qui n'est de toute façon pas un conteneur ne coûte au serveur qu'une lecture
            // de bloc-entité, jamais une construction de réponse.
            lastAnswered.put(player.getUUID(), now);

            int total = container.getContainerSize();
            int filled = 0;
            // Additionné par TYPE d'objet, pas par case : un coffre qui range 768 pierres réparties
            // sur douze piles doit compter comme UN article de 768, pas douze lignes identiques.
            Map<Item, Integer> counts = new LinkedHashMap<>();
            for (int slot = 0; slot < total; slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                filled++;
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
            List<ItemStack> dominant = counts.entrySet().stream()
                    .sorted(Map.Entry.<Item, Integer>comparingByValue().reversed())
                    .limit(DOMINANT_CAP)
                    .map(entry -> new ItemStack(entry.getKey(), entry.getValue()))
                    .toList();

            int cookPercent = -1;
            int fuelPercent = -1;
            ItemStack furnaceInput = ItemStack.EMPTY;
            ItemStack furnaceFuel = ItemStack.EMPTY;
            if (blockEntity instanceof AbstractFurnaceBlockEntity furnace) {
                FurnaceProgressAccessor access = (FurnaceProgressAccessor) furnace;
                int cookTotal = access.lanterne$cookingTotalTime();
                if (cookTotal > 0) {
                    cookPercent = Math.min(100,
                            (int) (100L * access.lanterne$cookingTimer() / cookTotal));
                }
                int litTotal = access.lanterne$litTotalTime();
                if (litTotal > 0) {
                    fuelPercent = Math.min(100,
                            (int) (100L * access.lanterne$litTimeRemaining() / litTotal));
                }
                // Cases fixes d'un four (voir la Javadoc de RegardTell) : nommées, pas classées.
                furnaceInput = furnace.getItem(0).copy();
                furnaceFuel = furnace.getItem(1).copy();
            }

            PacketDistributor.sendToPlayer(player,
                    new RegardTell(pos, dominant, filled, total, cookPercent, fuelPercent,
                            furnaceInput, furnaceFuel));
        });
    }
}
