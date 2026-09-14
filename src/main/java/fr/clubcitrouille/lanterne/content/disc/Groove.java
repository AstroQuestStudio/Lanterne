package fr.clubcitrouille.lanterne.content.disc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.jspecify.annotations.Nullable;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.content.painting.Mill;
import fr.clubcitrouille.lanterne.content.painting.Studio;

/**
 * Le sillon : les disques que le joueur apporte lui-même.
 *
 * <h2>Deux chemins vers un disque, et le second est le principal</h2>
 *
 * <ol>
 *   <li><b>Le graveur.</b> On fabrique un {@link #BLANK disque vierge} et un {@link #BURNER graveur},
 *       on met l'un dans l'autre, on clique, on colle un lien ou on choisit un fichier, et la machine
 *       tourne six secondes. C'est le geste que le commanditaire a demandé, et c'est le seul qui se
 *       découvre sans lire de documentation.</li>
 *   <li><b>Le dossier</b> {@code config/lanterne/disques/}, lu au démarrage. Commode pour un serveur
 *       qui veut préparer une discothèque ; inutilisable pour un joueur en pleine partie.</li>
 * </ol>
 *
 * <h2>Pourquoi les deux chemins n'aboutissent pas au même endroit</h2>
 *
 * <p>Un morceau du <b>dossier</b> obtient une entrée de registre à lui, avec sa durée exacte : le
 * registre est bâti au chargement du monde, et le fichier est déjà là.
 *
 * <p>Un morceau <b>gravé en jeu</b> ne le peut pas — le registre est scellé, et forcer un
 * rechargement périmerait tous les disques déjà en inventaire. Il occupe donc un <b>sillon</b>
 * réservé d'avance. Voir {@link Slots}, qui explique le mécanisme et son unique défaut.
 *
 * <h2>La discipline de chargement</h2>
 *
 * <p>Cette classe est chargée <b>des deux côtés</b>. Elle ne nomme donc aucun type client, jamais,
 * pas même au fond d'une lambda : le vérificateur de la machine virtuelle résout ces types à la
 * préparation de la classe, et un serveur dédié refuserait de la charger. Le pont passe par
 * {@link Turntable}, que seul {@link Wheel} implémente. Cette règle a été apprise en cassant un
 * serveur ; elle n'admet pas d'exception.
 */
public final class Groove {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Lanterne.ID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Lanterne.ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Lanterne.ID);

    /**
     * Le disque gravé.
     *
     * <p>Un seul objet pour tous les morceaux : ce sont ses composants qui portent le morceau et le
     * titre. Enregistrer un objet par disque aurait plafonné leur nombre au registre des objets et
     * rendu impossible d'en ajouter en cours de partie.
     */
    public static final DeferredItem<Item> DISC =
            ITEMS.registerItem("disque", properties -> new Item(properties
                    .stacksTo(1)
                    .rarity(Rarity.RARE)));

    /**
     * Le disque vierge.
     *
     * <p>Empilable par seize : on en fabrique une pile et on grave au fur et à mesure. C'est le seul
     * consommable de tout ce paquet, et c'est lui qui donne son poids au geste — un disque gratuit
     * aurait fait du graveur un presse-bouton.
     */
    public static final DeferredItem<Item> BLANK =
            ITEMS.registerItem("disque_vierge", properties -> new Item(properties.stacksTo(16)));

    /**
     * Le graveur de vinyle.
     *
     * <p>Solide sans être précieux — on le casse à la pioche et on le récupère. Il ne s'agit pas de
     * protéger une machine, mais de la poser quelque part.
     */
    public static final DeferredBlock<Burner> BURNER =
            BLOCKS.registerBlock("graveur", Burner::new, () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(3.5F, 6.0F)
                    .sound(SoundType.COPPER)
                    .lightLevel(state -> state.getValue(Burner.WORKING) ? 10 : 0)
                    .requiresCorrectToolForDrops());

    public static final DeferredItem<net.minecraft.world.item.BlockItem> BURNER_ITEM =
            ITEMS.registerSimpleBlockItem("graveur", BURNER);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BurnerEntity>> BURNER_ENTITY =
            BLOCK_ENTITIES.register("graveur",
                    () -> new BlockEntityType<>(BurnerEntity::new, BURNER.get()));

    /** Version du protocole des disques. */
    private static final String PROTOCOL = "2";

    /** Le catalogue du dossier, construit une fois puis relu. */
    private static volatile Map<String, Wax> catalogue;

    /** Les envois en cours vers les clients, un par joueur. */
    private static final Map<UUID, Carrier> CONVOYS = new ConcurrentHashMap<>();

    /** Les remontées en cours, une par joueur. */
    private static final Map<UUID, Lift> LIFTS = new ConcurrentHashMap<>();

    private Groove() {}

    // --- La part cliente, tenue à distance --------------------------------

    /**
     * Ce que le client sait faire des plis qu'il reçoit.
     *
     * <p>Même mécanique et même raison que {@code content.painting.Easel.Brushwork} : cette classe
     * est chargée sur un serveur dédié, où {@link Lathe} et {@link Wheel} n'existent pas.
     */
    public interface Turntable {
        void open(Post.Open payload);

        void want(Post.Want payload);

        void roll(Post.Roll payload);

        void shard(Post.Shard payload);

        void refresh();
    }

    /** Ce qu'un serveur dédié fait de ces plis : rien. Il ne les recevra jamais. */
    private static final Turntable DEAF = new Turntable() {
        @Override
        public void open(Post.Open payload) {}

        @Override
        public void want(Post.Want payload) {}

        @Override
        public void roll(Post.Roll payload) {}

        @Override
        public void shard(Post.Shard payload) {}

        @Override
        public void refresh() {}
    };

    private static Turntable turntable = DEAF;

    /** Le client s'annonce. Appelé derrière une garde de distribution, et de nulle part ailleurs. */
    public static void clientSide(Turntable work) {
        turntable = work;
    }

    // --- Enregistrement ----------------------------------------------------

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        modBus.addListener(Groove::onAddPackFinders);
        modBus.addListener(Groove::onBuildCreativeTab);
        modBus.addListener(Groove::onRegisterPayloads);
        NeoForge.EVENT_BUS.register(Groove.class);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        registrar.playToClient(Post.Open.TYPE, Post.Open.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> turntable.open(payload)));
        registrar.playToClient(Post.Want.TYPE, Post.Want.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> turntable.want(payload)));
        registrar.playToClient(Post.Roll.TYPE, Post.Roll.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> turntable.roll(payload)));
        registrar.playToClient(Post.Shard.TYPE, Post.Shard.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> turntable.shard(payload)));
        registrar.playToClient(DiscRefresh.TYPE, DiscRefresh.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> turntable.refresh()));
        registrar.playToServer(Post.Burn.TYPE, Post.Burn.STREAM_CODEC, Groove::onBurn);
        registrar.playToServer(Post.Gift.TYPE, Post.Gift.STREAM_CODEC, Groove::onGift);
        registrar.playToServer(Post.Wish.TYPE, Post.Wish.STREAM_CODEC, Groove::onWish);
    }

    private static void onAddPackFinders(AddPackFindersEvent event) {
        PackType type = event.getPackType();
        // Toujours : les sillons existent même quand le dossier est vide, et ce sont eux qui rendent
        // la gravure possible. Un paquet conditionné à la présence de fichiers aurait fait dépendre
        // le graveur d'un dossier que personne n'est censé remplir à la main.
        event.addRepositorySource(consumer -> consumer.accept(Reel.pack(type)));
    }

    // --- Dossiers ----------------------------------------------------------

    public static Path folder() {
        return Path.of("").toAbsolutePath()
                .resolve("config").resolve("lanterne").resolve("disques");
    }

    /** Les morceaux gravés, rangés par empreinte — des deux côtés du fil. */
    public static Path cacheFolder() {
        return folder().resolve(".cache");
    }

    public static Path cachedFile(String hash) {
        return cacheFolder().resolve(hash + ".ogg");
    }

    // --- Le catalogue du dossier ------------------------------------------

    public static Collection<Wax> catalogue() {
        return scan().values();
    }

    public static @Nullable Wax disc(String slug) {
        return scan().get(slug);
    }

    public static void forget() {
        catalogue = null;
    }

    private static Map<String, Wax> scan() {
        Map<String, Wax> known = catalogue;
        if (known != null) {
            return known;
        }
        synchronized (Groove.class) {
            if (catalogue != null) {
                return catalogue;
            }
            catalogue = Collections.unmodifiableMap(sweep());
            return catalogue;
        }
    }

    private static Map<String, Wax> sweep() {
        Map<String, Wax> found = new LinkedHashMap<>();
        Path folder = folder();
        try {
            Files.createDirectories(folder);
            Files.createDirectories(cacheFolder());
        } catch (IOException problem) {
            Lanterne.LOG.warn("[ATELIER] dossier des disques impossible à créer : {}",
                    problem.getMessage());
            return found;
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> listing = Files.list(folder)) {
            listing.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException problem) {
            Lanterne.LOG.warn("[ATELIER] dossier des disques illisible : {}", problem.getMessage());
            return found;
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString(),
                String.CASE_INSENSITIVE_ORDER));

        int refused = 0;
        for (Path file : files) {
            if (found.size() >= Studio.discQuota()) {
                Lanterne.LOG.warn("[ATELIER] quota de {} disques atteint.", Studio.discQuota());
                break;
            }
            if (!Press.isOgg(file)) {
                // Le dossier ne sert plus qu'à préparer une discothèque, et un administrateur qui le
                // remplit sait convertir. La conversion en jeu passe désormais par le graveur, où
                // elle a une barre de progression pour l'accompagner.
                if (Press.isConvertible(file)) {
                    Lanterne.LOG.warn("[ATELIER] « {} » ignoré : convertis-le en OGG, ou grave-le"
                            + " au graveur qui le fera pour toi.", file.getFileName());
                    refused++;
                }
                continue;
            }
            try {
                long weight = Files.size(file);
                if (weight > Studio.discMaxBytes()) {
                    throw new IOException("trop lourd : " + (weight / 1048576L) + " Mio");
                }
                Press.Reading reading = Press.read(file);
                if (reading.seconds() > Studio.discMaxSeconds()) {
                    throw new IOException("trop long : " + Math.round(reading.seconds()) + " s");
                }
                String name = file.getFileName().toString();
                String slug = unique(found, Wax.slugify(name));
                found.put(slug, new Wax(slug, Wax.prettify(name), reading.seconds(), file));
            } catch (IOException refusal) {
                Lanterne.LOG.warn("[ATELIER] « {} » refusé : {}", file.getFileName(),
                        refusal.getMessage());
                refused++;
            }
        }
        Lanterne.LOG.info("[ATELIER] {} disque(s) au dossier{}, {} sillon(s) gravé(s).",
                found.size(), refused > 0 ? ", " + refused + " refusé(s)" : "", Slots.used());
        return found;
    }

    private static String unique(Map<String, Wax> taken, String wanted) {
        if (!taken.containsKey(wanted)) {
            return wanted;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = wanted + "_" + suffix;
            if (!taken.containsKey(candidate)) {
                return candidate;
            }
        }
        return wanted + "_" + System.nanoTime();
    }

    // --- Fabriquer un disque ----------------------------------------------

    public static ResourceKey<JukeboxSong> songKey(String name) {
        return ResourceKey.create(Registries.JUKEBOX_SONG,
                Identifier.fromNamespaceAndPath(Lanterne.ID, name));
    }

    /**
     * Fabrique un disque à partir d'un morceau du dossier.
     *
     * <p>Le porteur vient du registre et jamais d'une valeur inventée : le composant
     * {@code JUKEBOX_PLAYABLE} est sauvegardé par un codec qui n'accepte que des entrées
     * enregistrées. Un morceau écrit en toutes lettres passerait le réseau puis disparaîtrait à la
     * première sauvegarde — un objet qui se vide en sortant du jeu est la pire sorte de bogue, parce
     * qu'il ne se voit qu'après.
     */
    public static ItemStack pressed(HolderLookup.Provider registries, Wax wax) {
        return build(registries, wax.slug(), wax.name());
    }

    /** Fabrique un disque gravé, sur un sillon. */
    public static ItemStack pressed(HolderLookup.Provider registries, int slot, String title) {
        return build(registries, Slots.name(slot), title);
    }

    private static ItemStack build(HolderLookup.Provider registries, String song, String title) {
        Holder.Reference<JukeboxSong> holder = registries
                .lookupOrThrow(Registries.JUKEBOX_SONG)
                .get(songKey(song))
                .orElse(null);
        if (holder == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(DISC.get());
        stack.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(holder));
        stack.set(DataComponents.ITEM_NAME, Component.literal(title));
        return stack;
    }

    /**
     * Occupe un sillon. Rend le numéro, ou moins un s'il n'y en a plus d'assez long.
     *
     * <p>Appelé par {@link BurnerEntity} à la fin de la gravure, jamais au début : un sillon réservé
     * pendant six secondes serait perdu si la gravure échouait, et deux joueurs qui gravent en même
     * temps se disputeraient des réservations plutôt que des sillons libres.
     */
    public static int engrave(Slots.Cut cut) {
        int slot = Slots.vacant(cut.seconds());
        if (slot < 0) {
            Lanterne.LOG.warn("[ATELIER] plus de sillon libre assez long pour {} s.", cut.seconds());
            return -1;
        }
        Slots.occupy(slot, cut);
        Slots.inscribe();
        Lanterne.LOG.info("[ATELIER] « {} » gravé au sillon {} ({} s) par {}.",
                cut.title(), slot, Math.round(cut.seconds()), cut.author());
        return slot;
    }

    // --- Évènements serveur ------------------------------------------------

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        Slots.recall();
        forget();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        Slots.inscribe();
        CONVOYS.clear();
        LIFTS.clear();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            player.connection.send(roll());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CONVOYS.remove(event.getEntity().getUUID());
        LIFTS.remove(event.getEntity().getUUID());
    }

    private static Post.Roll roll() {
        List<Post.Vinyl> cuts = new ArrayList<>();
        Slots.all().forEach((slot, cut) ->
                cuts.add(new Post.Vinyl(slot, cut.hash(), cut.title(), cut.seconds())));
        return new Post.Roll(cuts);
    }

    /** Annonce les sillons à tout le monde — après une gravure. */
    public static void proclaim(Level level) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        Post.Roll payload = roll();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(payload);
        }
    }

    // --- Le transfert ------------------------------------------------------

    private static final class Carrier {
        private final Deque<String> wishes = new ArrayDeque<>();
        private String hash;
        private byte[] data;
        private int offset;
    }

    private static final class Lift {
        private String hash;
        private byte[] buffer;
        private int received;
        private net.minecraft.core.BlockPos pos;
        private String title;
        private float seconds;
        private long nextAllowed;
    }

    /**
     * Le joueur demande une gravure.
     *
     * <p>Rien n'est cru : le bloc doit exister, être à portée, contenir un vierge et ne pas déjà
     * travailler. La durée annoncée sert seulement à savoir tout de suite s'il reste un sillon assez
     * long — elle sera relue sur les octets reçus avant d'occuper quoi que ce soit.
     */
    private static void onBurn(Post.Burn burn, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Level level = player.level();
            if (!(level.getBlockEntity(burn.pos()) instanceof BurnerEntity burner)) {
                return;
            }
            if (player.distanceToSqr(burn.pos().getX() + 0.5d, burn.pos().getY() + 0.5d,
                    burn.pos().getZ() + 0.5d) > 64.0d * 64.0d) {
                return;
            }
            if (burner.busy() || burner.empty() || burner.holdsFinished()) {
                tell(player, "Le graveur n'est pas prêt.");
                return;
            }
            if (!Mill.plausible(burn.hash()) || burn.size() < 1
                    || burn.size() > Studio.discMaxBytes()) {
                return;
            }
            if (burn.seconds() > Studio.discMaxSeconds()) {
                tell(player, "Trop long : la limite est " + Studio.discMaxSeconds() + " s.");
                return;
            }
            if (Slots.vacant(burn.seconds()) < 0) {
                tell(player, "Plus aucun sillon libre assez long. Un opérateur peut en libérer un"
                        + " avec « /disque liberer <n> », ou en ajouter dans lanterne-atelier.toml.");
                return;
            }

            String title = clean(burn.title());
            if (Files.isRegularFile(cachedFile(burn.hash()))) {
                // Le serveur a déjà ce morceau : quelqu'un l'a gravé avant, ou c'est le même joueur
                // qui recommence. Rien ne traverse le réseau.
                burner.begin(burn.hash(), title, player.getGameProfile().name(), burn.seconds());
                return;
            }

            Lift lift = LIFTS.computeIfAbsent(player.getUUID(), key -> new Lift());
            long now = System.currentTimeMillis();
            if (now < lift.nextAllowed) {
                tell(player, "Doucement — attends " + ((lift.nextAllowed - now) / 1000L + 1) + " s.");
                return;
            }
            lift.hash = burn.hash();
            lift.buffer = null;
            lift.received = 0;
            lift.pos = burn.pos();
            lift.title = title;
            lift.seconds = burn.seconds();
            lift.nextAllowed = now + 5000L;
            player.connection.send(new Post.Want(burn.hash()));
        });
    }

    /**
     * Une tranche de morceau envoyée par un joueur.
     *
     * <p>L'empreinte est recalculée à la fin, et la durée <b>relue</b> sur les octets reçus plutôt
     * que reprise du paquet : sans cela, un client pourrait annoncer trente secondes pour obtenir le
     * plus petit sillon, puis envoyer un morceau d'une heure qui serait coupé net à la lecture.
     */
    private static void onGift(Post.Gift gift, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Lift lift = LIFTS.get(player.getUUID());
            if (lift == null || lift.hash == null || !lift.hash.equals(gift.hash())) {
                return;
            }
            if (gift.size() < 1 || gift.size() > Studio.discMaxBytes() || gift.offset() < 0) {
                LIFTS.remove(player.getUUID());
                return;
            }
            if (lift.buffer == null) {
                lift.buffer = new byte[gift.size()];
            }
            if (lift.buffer.length != gift.size()
                    || gift.offset() + gift.data().length > lift.buffer.length) {
                LIFTS.remove(player.getUUID());
                return;
            }
            System.arraycopy(gift.data(), 0, lift.buffer, gift.offset(), gift.data().length);
            lift.received += gift.data().length;
            if (lift.received < lift.buffer.length) {
                return;
            }

            byte[] ogg = lift.buffer;
            lift.buffer = null;
            lift.hash = null;
            if (!Mill.fingerprint(ogg).equals(gift.hash())) {
                tell(player, "Morceau abîmé en route — réessaie.");
                return;
            }
            float seconds;
            try {
                Path target = cachedFile(gift.hash());
                Files.createDirectories(target.getParent());
                Files.write(target, ogg);
                seconds = Press.read(target).seconds();
            } catch (IOException problem) {
                Lanterne.LOG.warn("[ATELIER] morceau apporté refusé : {}", problem.getMessage());
                tell(player, "Le serveur n'a pas su lire ce morceau.");
                return;
            }
            if (seconds > Studio.discMaxSeconds()) {
                tell(player, "Trop long : " + Math.round(seconds) + " s.");
                return;
            }

            if (player.level().getBlockEntity(lift.pos) instanceof BurnerEntity burner
                    && !burner.busy() && !burner.empty()) {
                burner.begin(gift.hash(), lift.title, player.getGameProfile().name(), seconds);
            }
        });
    }

    /** Un client réclame un morceau qu'il n'a pas. */
    private static void onWish(Post.Wish wish, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!Mill.plausible(wish.hash()) || !Files.isRegularFile(cachedFile(wish.hash()))) {
                return;
            }
            Carrier carrier = CONVOYS.computeIfAbsent(player.getUUID(), key -> new Carrier());
            if (carrier.wishes.size() >= 64 || carrier.wishes.contains(wish.hash())
                    || wish.hash().equals(carrier.hash)) {
                return;
            }
            carrier.wishes.add(wish.hash());
        });
    }

    /**
     * Pousse les morceaux réclamés, au rythme des réglages.
     *
     * <p>Quand personne n'attend rien — c'est-à-dire presque toujours — cette méthode coûte un test
     * sur une table vide. Un module de confort n'a pas le droit de coûter quoi que ce soit quand il
     * ne sert pas.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (CONVOYS.isEmpty()) {
            return;
        }
        int allowance = Studio.shardsPerTick();
        int size = Studio.shardBytes();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            Carrier carrier = CONVOYS.get(player.getUUID());
            if (carrier == null) {
                continue;
            }
            for (int sent = 0; sent < allowance; sent++) {
                if (carrier.data == null && !load(carrier)) {
                    break;
                }
                int length = Math.min(size, carrier.data.length - carrier.offset);
                byte[] slice = new byte[length];
                System.arraycopy(carrier.data, carrier.offset, slice, 0, length);
                player.connection.send(new Post.Shard(carrier.hash, carrier.offset,
                        carrier.data.length, slice));
                carrier.offset += length;
                if (carrier.offset >= carrier.data.length) {
                    carrier.data = null;
                    carrier.hash = null;
                    carrier.offset = 0;
                }
            }
            if (carrier.data == null && carrier.wishes.isEmpty()) {
                CONVOYS.remove(player.getUUID());
            }
        }
    }

    private static boolean load(Carrier carrier) {
        while (!carrier.wishes.isEmpty()) {
            String hash = carrier.wishes.poll();
            if (hash == null) {
                continue;
            }
            try {
                carrier.data = Files.readAllBytes(cachedFile(hash));
                carrier.hash = hash;
                carrier.offset = 0;
                return true;
            } catch (IOException problem) {
                Lanterne.LOG.warn("[ATELIER] morceau « {} » illisible : {}", hash,
                        problem.getMessage());
            }
        }
        return false;
    }

    // --- Onglet créatif ----------------------------------------------------

    private static void onBuildCreativeTab(
            net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == null
                || !Lanterne.ID.equals(event.getTabKey().identifier().getNamespace())) {
            return;
        }
        // Le vierge et la machine d'abord : ce sont eux le geste, les disques déjà faits ne sont
        // qu'un raccourci de commodité.
        event.accept(new ItemStack(BLANK.get()));
        event.accept(new ItemStack(BURNER_ITEM.get()));
        HolderLookup.Provider registries = event.getParameters().holders();
        for (Wax wax : catalogue()) {
            ItemStack stack = pressed(registries, wax);
            if (!stack.isEmpty()) {
                event.accept(stack);
            }
        }
        Slots.all().forEach((slot, cut) -> {
            ItemStack stack = pressed(registries, slot, cut.title());
            if (!stack.isEmpty()) {
                event.accept(stack);
            }
        });
    }

    // --- Commandes ---------------------------------------------------------

    private static final SuggestionProvider<CommandSourceStack> NAMES = (context, builder) -> {
        for (Wax wax : catalogue()) {
            builder.suggest(StringArgumentType.escapeIfRequired(wax.name()));
        }
        Slots.all().values().forEach(cut ->
                builder.suggest(StringArgumentType.escapeIfRequired(cut.title())));
        return builder.buildFuture();
    };

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("disque")
                .then(Commands.literal("liste").executes(context -> {
                    Collection<Wax> discs = catalogue();
                    reply(context.getSource(), discs.size() + " au dossier, " + Slots.used()
                            + " gravé(s) sur " + Studio.discSlots() + " sillons.");
                    for (Wax wax : discs) {
                        reply(context.getSource(), "  " + wax.name() + "  ("
                                + Math.round(wax.seconds()) + " s)");
                    }
                    Slots.all().forEach((slot, cut) -> reply(context.getSource(),
                            "  [" + slot + "] " + cut.title() + "  (" + Math.round(cut.seconds())
                                    + " s, par " + cut.author() + ")"));
                    return discs.size() + Slots.used();
                }))
                .then(Commands.literal("prendre")
                        .then(Commands.argument("titre", StringArgumentType.string())
                                .suggests(NAMES)
                                .executes(context -> give(context.getSource(),
                                        StringArgumentType.getString(context, "titre")))))
                .then(Commands.literal("liberer")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("sillon", IntegerArgumentType.integer(0, 255))
                                .executes(context -> {
                                    int slot = IntegerArgumentType.getInteger(context, "sillon");
                                    Slots.Cut cut = Slots.at(slot);
                                    if (cut == null) {
                                        reply(context.getSource(), "Ce sillon est déjà libre.");
                                        return 0;
                                    }
                                    Slots.release(slot);
                                    Slots.inscribe();
                                    reply(context.getSource(), "Sillon " + slot + " libéré — « "
                                            + cut.title() + " » devient muet.");
                                    return 1;
                                })))
                .then(Commands.literal("recharger")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> {
                            forget();
                            Slots.recall();
                            int count = catalogue().size();
                            for (ServerPlayer player : context.getSource().getServer()
                                    .getPlayerList().getPlayers()) {
                                player.connection.send(new DiscRefresh());
                                player.connection.send(roll());
                            }
                            reply(context.getSource(), count + " disque(s) relu(s) au dossier.");
                            reply(context.getSource(), "Les disques du DOSSIER demandent en plus un"
                                    + " « /reload ». Les disques GRAVÉS, non : ils ne passent pas"
                                    + " par le registre.");
                            return count;
                        }))
                .then(Commands.literal("etat")
                        .executes(context -> {
                            reply(context.getSource(), "ffmpeg : "
                                    + (Press.ffmpegAvailable() ? "présent" : "absent"));
                            reply(context.getSource(), "Sillons : " + Slots.used() + " / "
                                    + Studio.discSlots());
                            reply(context.getSource(), "Dossier : " + folder());
                            return 1;
                        })));
    }

    private static int give(CommandSourceStack source, String query) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException notAPlayer) {
            reply(source, "Cette commande doit être lancée par un joueur.");
            return 0;
        }
        for (Wax wax : catalogue()) {
            if (wax.name().equalsIgnoreCase(query) || wax.slug().equalsIgnoreCase(query)) {
                return hand(source, player, pressed(player.level().registryAccess(), wax), wax.name());
            }
        }
        for (Map.Entry<Integer, Slots.Cut> entry : Slots.all().entrySet()) {
            if (entry.getValue().title().equalsIgnoreCase(query)) {
                return hand(source, player,
                        pressed(player.level().registryAccess(), entry.getKey(),
                                entry.getValue().title()),
                        entry.getValue().title());
            }
        }
        reply(source, "Aucun disque de ce nom. « /disque liste » les montre tous.");
        return 0;
    }

    private static int hand(CommandSourceStack source, ServerPlayer player, ItemStack stack,
            String name) {
        if (stack.isEmpty()) {
            reply(source, "Ce disque n'est pas chargé dans ce monde.");
            return 0;
        }
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        reply(source, "Disque « " + name + " ».");
        return 1;
    }

    private static String clean(String raw) {
        String trimmed = ChatFormatting.stripFormatting(raw);
        if (trimmed == null || trimmed.isBlank()) {
            return "Disque";
        }
        trimmed = trimmed.trim();
        return trimmed.length() > Wax.NAME_LIMIT ? trimmed.substring(0, Wax.NAME_LIMIT) : trimmed;
    }

    /** Un mot au joueur, en gris, sans cérémonie. */
    public static void tell(Player player, String text) {
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    private static void reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
    }
}
