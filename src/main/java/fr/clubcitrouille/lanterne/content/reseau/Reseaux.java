package fr.clubcitrouille.lanterne.content.reseau;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Config;
import fr.clubcitrouille.lanterne.core.Reseau;

/**
 * Le Réseau : enregistrement, et tout ce que le serveur accepte d'un Guichet.
 *
 * <h2>Ce fichier ne contient AUCUNE logique de registre</h2>
 *
 * <p>Elle vit entièrement dans {@code core.Reseau}, volontairement séparée — voir sa Javadoc de classe.
 * Ce fichier-ci ne fait que le brancher au jeu : blocs, bloc-entités, menu, réseau, onglet créatif.
 * Même découpage que {@code content.screen.Screens} vis-à-vis de {@code client.screen.Clock}/{@code Feed}.
 *
 * <h2>Pourquoi les blocs s'enregistrent toujours, même {@code reseau_actif} à faux</h2>
 *
 * <p>Même règle que partout ailleurs dans ce mod, écrite en toutes lettres dans {@code content.Contents}
 * et dans {@code content.screen.Screens} : un bloc absent du registre transforme en air tout ce qui a
 * été bâti. {@code Config#RESEAU_ACTIF} éteint l'EFFET (plus d'enregistrement dans {@code core.Reseau},
 * plus de réapprovisionnement, un Guichet qui le dit poliment) — jamais le bloc lui-même.
 */
public final class Reseaux {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Lanterne.ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Lanterne.ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Lanterne.ID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Lanterne.ID);

    // --- Les blocs -----------------------------------------------------------

    public static final DeferredBlock<Aiguillage> AIGUILLAGE = BLOCKS.registerBlock("aiguillage",
            Aiguillage::new, () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(3.0F, 4.8F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops());
    public static final DeferredItem<net.minecraft.world.item.BlockItem> AIGUILLAGE_ITEM =
            ITEMS.registerSimpleBlockItem("aiguillage", AIGUILLAGE);

    public static final DeferredBlock<Guichet> GUICHET = BLOCKS.registerBlock("guichet",
            Guichet::new, () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .strength(3.5F, 6.0F)
                    .sound(SoundType.DEEPSLATE)
                    .requiresCorrectToolForDrops());
    public static final DeferredItem<net.minecraft.world.item.BlockItem> GUICHET_ITEM =
            ITEMS.registerSimpleBlockItem("guichet", GUICHET);

    public static final DeferredBlock<CoffreDepot> COFFRE_DEPOT = BLOCKS.registerBlock("coffre_depot",
            CoffreDepot::new, () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.5F, 3.0F)
                    .sound(SoundType.WOOD)
                    .requiresCorrectToolForDrops());
    public static final DeferredItem<net.minecraft.world.item.BlockItem> COFFRE_DEPOT_ITEM =
            ITEMS.registerSimpleBlockItem("coffre_depot", COFFRE_DEPOT);

    public static final DeferredBlock<CoffreReception> COFFRE_RECEPTION = BLOCKS.registerBlock(
            "coffre_reception", CoffreReception::new, () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BROWN)
                    .strength(2.5F, 3.0F)
                    .sound(SoundType.WOOD)
                    .requiresCorrectToolForDrops());
    public static final DeferredItem<net.minecraft.world.item.BlockItem> COFFRE_RECEPTION_ITEM =
            ITEMS.registerSimpleBlockItem("coffre_reception", COFFRE_RECEPTION);

    // --- Les bloc-entités ------------------------------------------------------

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AiguillageBlockEntity>>
            AIGUILLAGE_ENTITY = BLOCK_ENTITIES.register("aiguillage",
                    () -> new BlockEntityType<>(AiguillageBlockEntity::new, AIGUILLAGE.get()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GuichetBlockEntity>>
            GUICHET_ENTITY = BLOCK_ENTITIES.register("guichet",
                    () -> new BlockEntityType<>(GuichetBlockEntity::new, GUICHET.get()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CoffreDepotBlockEntity>>
            COFFRE_DEPOT_ENTITY = BLOCK_ENTITIES.register("coffre_depot",
                    () -> new BlockEntityType<>(CoffreDepotBlockEntity::new, COFFRE_DEPOT.get()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CoffreReceptionBlockEntity>>
            COFFRE_RECEPTION_ENTITY = BLOCK_ENTITIES.register("coffre_reception",
                    () -> new BlockEntityType<>(CoffreReceptionBlockEntity::new, COFFRE_RECEPTION.get()));

    // --- Le menu du Guichet ------------------------------------------------------

    public static final DeferredHolder<MenuType<?>, MenuType<GuichetMenu>> GUICHET_MENU =
            MENUS.register("guichet", () -> IMenuTypeExtension.create(GuichetMenu::new));

    /** Version du protocole du Réseau. */
    private static final String PROTOCOL = "1";

    /** Portée du bras, au carré — même valeur et même raison que {@code content.screen.Screens#REACH_SQ}. */
    private static final double REACH_SQ = 64.0d;

    /** Une demande ne peut jamais dépasser cent stacks : borne large, mais une borne quand même. */
    private static final int DEMANDE_MAX = 6400;

    private Reseaux() {}

    /**
     * Le Réseau est-il allumé — lecture sûre pour un appelant CLIENT.
     *
     * <p>Les points d'entrée serveur existants ({@link Aiguillage#onLoad}, {@link
     * CoffreDepotBlockEntity#onLoad}, {@code Guichet#tick}...) lisent {@code Config.RESEAU_ACTIF.get()}
     * sans détour : côté serveur, le fichier est chargé bien avant qu'un monde existe. Un renderer
     * client n'a pas cette garantie — {@code Dials#serverSpoke()} documente le cas précis où {@code
     * get()} lève avant la synchronisation du config serveur — d'où la garde {@code isLoaded()} ici,
     * réservée aux appelants qui ne peuvent pas la tenir pour acquise.
     */
    public static boolean actif() {
        return Config.SPEC.isLoaded() && Config.RESEAU_ACTIF.get();
    }

    // --- La part cliente, tenue à distance ------------------------------------

    /**
     * Ce que le client sait faire d'un instantané de stock. Même mécanique et même raison que
     * {@code content.screen.Screens.Projectionist} : cette classe est chargée par un serveur dédié, où
     * {@code GuichetScreen} et {@code GuichetMenu} côté client n'existent pas au sens du rendu.
     */
    public interface ReseauClient {
        void stock(Bordereau.Stock payload);
    }

    private static final ReseauClient DEAF = payload -> {};
    private static ReseauClient client = DEAF;

    /** Le client s'annonce. Appelé derrière une garde de distribution, et de nulle part ailleurs. */
    public static void clientSide(ReseauClient work) {
        client = work;
    }

    // --- Enregistrement --------------------------------------------------------

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
        modBus.addListener(Reseaux::onBuildCreativeTab);
        modBus.addListener(Reseaux::onRegisterPayloads);
        NeoForge.EVENT_BUS.register(Reseaux.class);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        registrar.playToClient(Bordereau.Stock.TYPE, Bordereau.Stock.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> client.stock(payload)));
        registrar.playToServer(Bordereau.Demande.TYPE, Bordereau.Demande.STREAM_CODEC, Reseaux::onDemande);
    }

    private static void onBuildCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == null
                || !Lanterne.ID.equals(event.getTabKey().identifier().getNamespace())) {
            return;
        }
        event.accept(new ItemStack(AIGUILLAGE_ITEM.get()));
        event.accept(new ItemStack(GUICHET_ITEM.get()));
        event.accept(new ItemStack(COFFRE_DEPOT_ITEM.get()));
        event.accept(new ItemStack(COFFRE_RECEPTION_ITEM.get()));
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        Reseau.clear();
    }

    // --- Ce que le serveur accepte d'un Guichet ---------------------------------

    /**
     * Une demande de retrait, déclenchée par un clic sur une ligne du Guichet.
     *
     * <h2>Rien n'est cru sur parole</h2>
     *
     * <p>Le Guichet visé n'est JAMAIS celui que le client prétend — voir la Javadoc de classe de
     * {@code Bordereau} — mais celui que {@code player.containerMenu} rapporte réellement ouvert. La
     * portée est revérifiée ici en plus, exactement pour la raison que {@code content.screen.Screens}
     * documente pour son propre {@code reach} : un client modifié ne doit pas pouvoir agir sur un
     * Guichet situé à l'autre bout du monde simplement parce qu'un menu y reste ouvert par accident de
     * synchronisation.
     */
    private static void onDemande(Bordereau.Demande payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !Config.RESEAU_ACTIF.get()) {
                return;
            }
            if (!(player.containerMenu instanceof GuichetMenu menu) || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            BlockPos pos = menu.pos();
            if (!level.isLoaded(pos) || player.distanceToSqr(
                    pos.getX() + 0.5d, pos.getY() + 0.5d, pos.getZ() + 0.5d) > REACH_SQ) {
                return;
            }
            if (!(level.getBlockEntity(pos) instanceof GuichetBlockEntity guichet)) {
                return;
            }
            Container collecte = guichet.coffreAdjacent();
            if (collecte == null) {
                tell(player, "Aucun coffre de collecte à côté du Guichet.");
                return;
            }
            int quantite = Math.clamp(payload.quantite(), 1, DEMANDE_MAX);
            int obtenu = Reseau.withdraw(level, payload.item(), quantite, collecte);
            if (obtenu > 0) {
                // Le joueur voit l'effet de son clic tout de suite, sans attendre le prochain passage
                // du ticker — voir Guichet#RAFRAICHISSEMENT_TICKS.
                guichet.republierSiChange(level);
            }
        });
    }

    public static void tell(Player player, String text) {
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }
}
