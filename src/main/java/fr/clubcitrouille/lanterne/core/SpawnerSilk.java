package fr.clubcitrouille.lanterne.core;

import java.util.List;

import net.minecraft.core.registries.Registries;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueOutput;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Un spawner miné avec un outil Silk Touch rend le bloc lui-même, comme n'importe quel autre bloc
 * silk-touchable — vanilla ne le permet jamais, aucune exception dans son code de butin.
 *
 * <h2>Pourquoi {@code BlockDropsEvent} plutôt qu'une table de butin</h2>
 *
 * <p>Vanilla ne fournit aucune table de butin pour {@code minecraft:spawner} (son champ
 * {@code lootTable} vaut {@code EMPTY}) : rien à faire réagir dans ce sens-là. {@code
 * BlockDropsEvent} se déclenche après la casse, avec l'outil réel et une liste de chutes déjà
 * calculée (vide ici) — remplacer son contenu est le point d'entrée le plus direct, sans avoir à
 * fabriquer une table de butin qui n'existe pas.
 *
 * <h2>Pourquoi copier le NBT du bloc-entité</h2>
 *
 * <p>Un spawner configuré (type de mob choisi via œuf d'invocation, commande, ou structure)
 * porte cet état dans son bloc-entité, pas dans le bloc. Sans copie, la chute est un spawner
 * "vierge" et la configuration est perdue à jamais — {@link BlockDropsEvent#getBlockEntity()}
 * expose encore le bloc-entité intact à ce point de l'événement. Reprend exactement le chemin
 * vanilla de {@code ServerGamePacketListenerImpl#addBlockDataToItem} (pick-block créatif),
 * jamais utilisé pour une chute de casse mais identique dans son mécanisme.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class SpawnerSilk {
    private SpawnerSilk() {}

    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        BlockState state = event.getState();
        if (!state.is(Blocks.SPAWNER)) {
            return;
        }
        ItemStack tool = event.getTool();
        var silkTouch = event.getLevel().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SILK_TOUCH);
        if (tool.getEnchantments().getLevel(silkTouch) <= 0) {
            return;
        }
        ItemStack drop = new ItemStack(Items.SPAWNER);
        BlockEntity blockEntity = event.getBlockEntity();
        if (blockEntity != null) {
            try (ProblemReporter.ScopedCollector reporter =
                    new ProblemReporter.ScopedCollector(blockEntity.problemPath(), Lanterne.LOG)) {
                TagValueOutput output = TagValueOutput.createWithContext(reporter, event.getLevel().registryAccess());
                blockEntity.saveCustomOnly(output);
                blockEntity.removeComponentsFromTag(output);
                BlockItem.setBlockEntityData(drop, blockEntity.getType(), output);
                drop.applyComponents(blockEntity.collectComponents());
            }
        }
        List<ItemEntity> drops = event.getDrops();
        drops.clear();
        drops.add(new ItemEntity(event.getLevel(),
                event.getPos().getX() + 0.5, event.getPos().getY() + 0.5, event.getPos().getZ() + 0.5,
                drop));
    }
}
