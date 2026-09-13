package fr.clubcitrouille.lanterne.content;

import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le seul contenu que ce mod ajoute au jeu.
 *
 * <h2>Pourquoi presque rien</h2>
 *
 * <p>Un mod d'optimisation qui ajoute des blocs éveille à juste titre la méfiance : chaque objet
 * nouveau est une incompatibilité possible, une recette à équilibrer, une traduction à tenir. Ce
 * paquet n'en contient donc qu'un seul, et il répond à un besoin que l'optimisation elle-même fait
 * naître.
 *
 * <p>Lanterne dégrade ce qui est loin. C'est ce qui le rend rapide — et cela laisse une question
 * ouverte : que faire de la ferme qu'on veut voir tourner en son absence ? La réponse habituelle est
 * un mod de chargement de chunks, et ces mods chargent large. L'ancre charge <b>un</b> chunk : le
 * sien. Voir {@link Anchor}.
 */
public final class Contents {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Lanterne.ID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(Lanterne.ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Lanterne.ID);

    /**
     * L'ancre de chunk.
     *
     * <p>Dure comme l'obsidienne — on ne la casse pas par mégarde — et incassable par une explosion,
     * parce qu'une ferme protégée par une ancre ne doit pas se retrouver déchargée parce qu'un creeper
     * est passé. Sa lumière faible sert de repère : une ancre posée se voit de loin dans le noir, et
     * l'on sait ce qu'on paie.
     */
    public static final net.neoforged.neoforge.registries.DeferredBlock<Anchor> ANCHOR =
            BLOCKS.registerBlock("anchor", Anchor::new, () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(50.0F, 1200.0F)
                    .sound(SoundType.NETHERITE_BLOCK)
                    .lightLevel(state -> 7)
                    .requiresCorrectToolForDrops());

    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem>
            ANCHOR_ITEM = ITEMS.registerSimpleBlockItem("anchor", ANCHOR);

    /**
     * La Lanterne de Veille.
     *
     * <p>Éclaire au maximum — quinze, comme une pierre lumineuse — parce qu'un bloc qui empêche les
     * monstres d'apparaître doit <b>avoir l'air</b> de le faire. Fragile en comparaison de l'ancre :
     * on la pose par dizaines dans une base, et exiger de la netherite pour chacune la rendrait
     * inutilisable. Voir {@link Vigil} pour ce qu'elle épargne.
     */
    public static final net.neoforged.neoforge.registries.DeferredBlock<Vigil> VIGIL =
            BLOCKS.registerBlock("veilleuse", Vigil::new, () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.LANTERN)
                    .lightLevel(state -> 15)
                    .requiresCorrectToolForDrops());

    public static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem>
            VIGIL_ITEM = ITEMS.registerSimpleBlockItem("veilleuse", VIGIL);

    /**
     * Le Carnet de repères.
     *
     * <p>Sans lui, la touche du carnet ne fait rien : les repères cessent d'être une fonctionnalité
     * d'interface pour devenir un <b>objet du monde</b>. On le fabrique, on le perd à la mort, on peut
     * le donner. Voir {@code Settings#waypoints} pour l'interrupteur qui le rend facultatif.
     */
    public static final net.neoforged.neoforge.registries.DeferredItem<Item> CARNET =
            ITEMS.registerItem("carnet", properties -> new Item(properties.stacksTo(1)));

    /** La Boussole d'Ancre. Voir {@link AnchorCompass} : elle lit le chargement forcé de vanilla. */
    public static final net.neoforged.neoforge.registries.DeferredItem<AnchorCompass> ANCHOR_COMPASS =
            ITEMS.registerItem("boussole_ancre",
                    properties -> new AnchorCompass(properties.stacksTo(1)));

    public static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>>
            BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Lanterne.ID);

    /**
     * Le bloc-entité de la Lanterne — sans ticker, donc gratuit par tick.
     *
     * <p>Il n'existe que pour être sérialisé avec son chunk et pour signaler son arrivée et son
     * départ. Voir {@link VigilBlockEntity} pour l'alternative sans bloc-entité qui a été écartée, et
     * la raison qui l'a fait écarter.
     */
    public static final Supplier<net.minecraft.world.level.block.entity.BlockEntityType<VigilBlockEntity>>
            VIGIL_ENTITY = BLOCK_ENTITIES.register("veilleuse",
                    () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
                            VigilBlockEntity::new, VIGIL.get()));

    /** Un onglet à part : on doit pouvoir trouver l'ancre sans savoir où Mojang l'aurait rangée. */
    public static final Supplier<CreativeModeTab> TAB = TABS.register("lanterne",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.lanterne"))
                    .icon(() -> new net.minecraft.world.item.ItemStack(ANCHOR_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(ANCHOR_ITEM.get());
                        output.accept(VIGIL_ITEM.get());
                        output.accept(CARNET.get());
                        output.accept(ANCHOR_COMPASS.get());
                    })
                    .build());

    private Contents() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
    }
}
