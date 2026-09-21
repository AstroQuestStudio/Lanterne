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
import fr.clubcitrouille.lanterne.content.screen.Screens;

/**
 * Casser un projecteur configuré (flux réglé, propriétaire) rend un projecteur vierge : sa table de
 * butin ({@code loot_table/blocks/projecteur.json}) n'a qu'une entrée d'item plate, aucune copie du
 * bloc-entité. Même défaut, même remède que {@link SpawnerSilk} — copier le NBT du bloc-entité dans
 * la chute avant qu'elle ne parte, en suivant le même chemin que {@code
 * ServerGamePacketListenerImpl#addBlockDataToItem} (pick-block créatif).
 *
 * <p>Le mur d'écran ({@code Panel}/{@code Wall}) n'a pas ce problème : sa recomposition efface et
 * reconstruit délibérément l'assemblage à chaque changement de forme, donc le flux n'y est jamais un
 * état qu'un seul bloc porte seul — rien à préserver côté {@code Panel}.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class BeamerDrops {
    private BeamerDrops() {}

    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        BlockState state = event.getState();
        if (!state.is(Screens.BEAMER.get())) {
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
