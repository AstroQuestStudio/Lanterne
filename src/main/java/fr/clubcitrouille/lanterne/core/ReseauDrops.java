package fr.clubcitrouille.lanterne.core;

import java.util.List;

import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.reseau.Reseaux;

/**
 * Casser un Coffre de dépôt ou de réception rendait un coffre vierge : sa table de butin
 * ({@code loot_table/blocks/coffre_{depot,reception}.json}) n'avait qu'une entrée d'item plate,
 * aucune copie du bloc-entité — les objets stockés (27 cases, comme un {@code ChestBlockEntity}
 * vanilla) et, pour le Coffre de réception, son filtre de réapprovisionnement configuré au clic
 * droit ({@code CoffreReceptionBlockEntity#filtre}) disparaissaient sans laisser de trace. Même
 * défaut, même remède que {@link fr.clubcitrouille.lanterne.core.SpawnerSilk} et {@code BeamerDrops}
 * — copier le NBT du bloc-entité dans la chute avant qu'elle ne parte, en suivant le même chemin
 * que {@code ServerGamePacketListenerImpl#addBlockDataToItem} (pick-block créatif).
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class ReseauDrops {
    private ReseauDrops() {}

    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        BlockState state = event.getState();
        if (!state.is(Reseaux.COFFRE_DEPOT.get()) && !state.is(Reseaux.COFFRE_RECEPTION.get())) {
            return;
        }
        BlockEntity blockEntity = event.getBlockEntity();
        if (blockEntity == null) {
            return;
        }
        List<ItemEntity> drops = event.getDrops();
        if (drops.isEmpty()) {
            return;
        }
        ItemStack drop = drops.get(0).getItem();
        if (!(drop.getItem() instanceof BlockItem)) {
            return;
        }
        try (ProblemReporter.ScopedCollector reporter =
                new ProblemReporter.ScopedCollector(blockEntity.problemPath(), Lanterne.LOG)) {
            TagValueOutput output = TagValueOutput.createWithContext(reporter, event.getLevel().registryAccess());
            blockEntity.saveCustomOnly(output);
            blockEntity.removeComponentsFromTag(output);
            BlockItem.setBlockEntityData(drop, blockEntity.getType(), output);
            drop.applyComponents(blockEntity.collectComponents());
        }
    }
}
