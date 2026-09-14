package fr.clubcitrouille.lanterne.content.painting;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
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
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le chevalet : tout ce qui, côté serveur, fait qu'une image devient un tableau.
 *
 * <h2>La règle qui gouverne ce fichier</h2>
 *
 * <p><b>Une image ne traverse le réseau qu'une fois par client, jamais deux.</b> Tout le reste — le
 * catalogue, les empreintes, le cache disque, le découpage en segments — n'existe que pour tenir
 * cette promesse. Elle est tenue par construction, et non par un réglage : le serveur n'envoie
 * <em>jamais</em> d'image de sa propre initiative. Il annonce ce qu'il possède ; le client compare
 * avec ce qu'il a sur son disque et demande la différence. Un client qui a déjà tout ne reçoit rien,
 * quel que soit le nombre de tableaux posés sur les murs.
 *
 * <h2>Le chemin d'une image, du dossier au mur</h2>
 *
 * <ol>
 *   <li><b>Au démarrage</b>, un fil de fond parcourt {@code config/lanterne/tableaux/}. Chaque
 *       fichier passe au {@link Mill} : réduction, encodage, empreinte. Le résultat est écrit dans
 *       {@code .cache/<empreinte>.png} et indexé.</li>
 *   <li><b>Aux démarrages suivants</b>, l'index évite tout le travail : un fichier dont le nom, la
 *       taille et la date n'ont pas bougé est déjà connu, et son empreinte est lue plutôt que
 *       recalculée. Quatre-vingts images reviennent en quelques millisecondes au lieu de quelques
 *       secondes.</li>
 *   <li><b>À la connexion d'un joueur</b>, le catalogue lui est annoncé — des noms et des
 *       dimensions, pas un pixel.</li>
 *   <li><b>Sur demande</b>, et seulement sur demande, les octets partent en segments réglés au
 *       tick.</li>
 * </ol>
 *
 * <h2>Pourquoi le fil de fond, et ce qu'il ne fait pas</h2>
 *
 * <p>Décoder et réduire quatre-vingts images prend plusieurs secondes de calcul pur. Les faire sur
 * le fil du serveur, c'est un démarrage figé et, sur un serveur déjà ouvert, des joueurs déconnectés
 * pour dépassement de délai. Le fil de fond n'a pas ce problème.
 *
 * <p>Il ne touche en revanche à <b>rien du monde</b> : il lit des fichiers, calcule, et écrit dans
 * deux tables concurrentes. Aucune entité, aucun chunk, aucun joueur. C'est ce qui rend l'absence de
 * verrou correcte plutôt que dangereuse — et c'est la même discipline que celle qui a fait refuser
 * le lancer de rayon parallèle dans {@code core.Shroud}.
 */
public final class Easel {
    // --- Registres ---------------------------------------------------------

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Lanterne.ID);

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Lanterne.ID);

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, Lanterne.ID);

    /** La charge d'un pinceau. Voir {@link Brush.Ink}. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Brush.Ink>> INK =
            COMPONENTS.register("encre", () -> DataComponentType.<Brush.Ink>builder()
                    .persistent(Brush.Ink.CODEC)
                    .networkSynchronized(Brush.Ink.STREAM_CODEC)
                    .build());

    public static final DeferredItem<Brush> BRUSH =
            ITEMS.registerItem("pinceau", properties -> new Brush(properties.stacksTo(16)));

    /**
     * Le type d'entité de la toile.
     *
     * <p>{@code sized(0.5F, 0.5F)} n'a aucun effet visible : {@code BlockAttachedEntity} redéfinit
     * sa boîte à chaque recalcul et ignore les dimensions du type. La valeur n'est là que parce que
     * le constructeur en exige une, et la choisir petite évite qu'un code générique du jeu ne
     * raisonne sur une taille fausse avant le premier recalcul.
     *
     * <p>{@code clientTrackingRange(10)} — dix chunks — est volontairement généreux : un tableau est
     * un repère visuel, et le voir apparaître à quelques blocs du nez gâcherait tout le travail sur
     * le rendu. Le coût est nul, une toile ne bougeant jamais : le suivi n'envoie rien après le
     * paquet d'apparition. {@code updateInterval} est poussé au maximum pour la même raison.
     */
    public static final DeferredHolder<EntityType<?>, EntityType<Canvas>> CANVAS =
            ENTITIES.register("tableau", id -> EntityType.Builder
                    .<Canvas>of(Canvas::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(10)
                    .updateInterval(Integer.MAX_VALUE)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, id)));

    /** Version du protocole des tableaux. */
    private static final String PROTOCOL = "1";

    // --- État serveur ------------------------------------------------------

    /** Le catalogue : empreinte vers métadonnées. Lu par le fil serveur, écrit par l'import. */
    private static final Map<String, Plate> CATALOGUE = new ConcurrentHashMap<>();

    /** Les convois en cours, un par joueur. */
    private static final Map<UUID, Carrier> CONVOYS = new ConcurrentHashMap<>();

    /** Vrai tant que l'import de fond tourne. */
    private static volatile boolean grinding;

    /**
     * L'écho du catalogue chez le client.
     *
     * <h2>Pourquoi deux tables et non une</h2>
     *
     * <p>Sur un serveur dédié, {@link #CATALOGUE} est plein et cet écho est vide ; sur un client en
     * multijoueur, c'est l'inverse ; en partie solo, les deux existent dans la même machine
     * virtuelle et disent la même chose. Les confondre en une seule table marcherait en solo et
     * échouerait exactement là où l'on ne peut pas tester facilement.
     *
     * <p>L'écho ne sert qu'à une chose : écrire le nom d'une image dans l'infobulle d'un pinceau.
     * Il ne porte aucune autorité — un client ne décide pas de ce qui existe.
     */
    private static final Map<String, Plate> ECHO = new ConcurrentHashMap<>();

    private Easel() {}

    /** Le client vient de recevoir le rôle d'appel : il retient les noms, et rien de plus. */
    public static void echo(List<Plate> plates) {
        ECHO.clear();
        for (Plate plate : plates) {
            ECHO.put(plate.hash(), plate);
        }
    }

    // --- Enregistrement ----------------------------------------------------

    public static void register(IEventBus modBus) {
        // L'alias AVANT « register » : NeoForge interdit d'en ajouter une fois l'évènement
        // d'enregistrement passé, et l'exception serait levée au chargement du mod.
        // Il ne sert que si Immersive Paintings est absent — voir Relic.
        ENTITIES.addAlias(Identifier.fromNamespaceAndPath(Relic.FOREIGN, "painting"),
                Identifier.fromNamespaceAndPath(Lanterne.ID, "tableau"));
        ENTITIES.addAlias(Identifier.fromNamespaceAndPath(Relic.FOREIGN, "glow_painting"),
                Identifier.fromNamespaceAndPath(Lanterne.ID, "tableau"));

        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener(Easel::onRegisterPayloads);
        modBus.addListener(Easel::onBuildCreativeTab);
        NeoForge.EVENT_BUS.register(Easel.class);
    }

    /**
     * Ce que le client sait faire des paquets qu'il reçoit.
     *
     * <h2>Pourquoi cette interface existe — une leçon payée par un serveur qui ne démarrait plus</h2>
     *
     * <p>La version précédente traitait les paquets descendants directement ici, dans des lambdas
     * qui appelaient {@code Hoard} et construisaient un écran. Le raisonnement était : « le corps
     * d'une lambda n'est chargé qu'à son premier appel, donc un serveur dédié ne chargera jamais ces
     * classes ». <b>Ce raisonnement est faux</b>, et il a mis un serveur dédié par terre au
     * chargement du mod.
     *
     * <p>La raison est le <b>vérificateur de code</b>. Résoudre un appel comme
     * {@code setScreen(new Frame(...))} oblige la machine virtuelle à vérifier que {@code Frame} est
     * bien un {@code Screen} — c'est-à-dire à charger les deux classes — et cette vérification a lieu
     * à la préparation de la méthode, pas à son exécution. Une lambda n'y échappe pas : elle est
     * compilée en une méthode synthétique de cette classe même.
     *
     * <p>La règle qui en découle, et qu'il faut tenir sans exception : <b>une classe chargée des deux
     * côtés ne nomme jamais un type client</b>, ni dans une signature, ni dans un corps de méthode,
     * ni dans une lambda. Le pont se fait par une interface commune, que seul le client implémente.
     *
     * <p>{@code @OnlyIn} ne protège de rien : NeoForge 26.1 avertit lui-même que le retrait des
     * membres annotés n'a plus lieu à l'exécution. L'annotation documente une intention ; seule la
     * séparation en classes distinctes la fait respecter.
     */
    public interface Brushwork {
        void roll(PaintingRoll payload);

        void shard(PaintingShard payload);

        void want(PaintingWant payload);

        void open(PaintingOpen payload);
    }

    /**
     * Ce que fait un serveur dédié de ces paquets : rien.
     *
     * <p>Il ne les recevra jamais — ce sont des paquets descendants. L'implémentation muette n'est
     * pas une précaution contre un cas possible, c'est ce qui permet à cette classe de compiler et de
     * se charger sans jamais nommer une classe cliente.
     */
    private static final Brushwork DEAF = new Brushwork() {
        @Override
        public void roll(PaintingRoll payload) {}

        @Override
        public void shard(PaintingShard payload) {}

        @Override
        public void want(PaintingWant payload) {}

        @Override
        public void open(PaintingOpen payload) {}
    };

    private static Brushwork brushwork = DEAF;

    /** Le client s'annonce. Appelé derrière une garde de distribution, et de nulle part ailleurs. */
    public static void clientSide(Brushwork work) {
        brushwork = work;
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        // Aucun de ces gestionnaires ne nomme une classe cliente : ils passent tous par l'interface
        // ci-dessus, dont l'implémentation muette suffit à un serveur dédié. Voir Brushwork.
        registrar.playToClient(PaintingRoll.TYPE, PaintingRoll.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> brushwork.roll(payload)));
        registrar.playToClient(PaintingShard.TYPE, PaintingShard.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> brushwork.shard(payload)));
        registrar.playToClient(PaintingWant.TYPE, PaintingWant.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> brushwork.want(payload)));
        registrar.playToClient(PaintingOpen.TYPE, PaintingOpen.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> brushwork.open(payload)));
        registrar.playToServer(PaintingWish.TYPE, PaintingWish.STREAM_CODEC, Easel::onWish);
        registrar.playToServer(PaintingSet.TYPE, PaintingSet.STREAM_CODEC, Easel::onSet);
        registrar.playToServer(PaintingGift.TYPE, PaintingGift.STREAM_CODEC, Easel::onGift);
    }

    /**
     * Le pinceau dans l'onglet de Lanterne, et un pinceau chargé par image connue.
     *
     * <p>Ajouter une entrée par image plutôt qu'un seul pinceau vierge : c'est le seul endroit où un
     * joueur en mode créatif peut <em>voir</em> ce que le serveur possède sans taper de commande, et
     * la recherche de l'inventaire créatif y donne accès par le nom.
     */
    private static void onBuildCreativeTab(
            net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == null
                || !Lanterne.ID.equals(event.getTabKey().identifier().getNamespace())) {
            return;
        }
        event.accept(new ItemStack(BRUSH.get()));
        for (Plate plate : sorted()) {
            int[] fit = plate.fittingBlocks(Studio.paintingMaxBlocks());
            event.accept(Brush.charged(plate.hash(), fit[0], fit[1], Trim.BOIS));
        }
    }

    // --- Dossiers ----------------------------------------------------------

    /**
     * Le dossier du jeu.
     *
     * <p>Le répertoire courant du processus : c'est celui où le lanceur place {@code config/},
     * {@code mods/} et les sauvegardes, aussi bien pour le client que pour un serveur dédié. Passer
     * par lui plutôt que par une interface du chargeur de mods évite une dépendance de plus, et
     * c'est aussi ce qu'Immersive Paintings fait — ce qui garantit qu'on cherche son dossier de
     * cache au bon endroit.
     */
    public static Path gameDir() {
        return Path.of("").toAbsolutePath();
    }

    /** Le dossier où le joueur dépose ses images. */
    public static Path folder() {
        return gameDir().resolve("config").resolve("lanterne").resolve("tableaux");
    }

    /** Le dossier des images déjà préparées, indexées par empreinte. */
    public static Path cacheFolder() {
        return folder().resolve(".cache");
    }

    private static Path indexFile() {
        return cacheFolder().resolve("index.properties");
    }

    private static Path heritageFile() {
        return cacheFolder().resolve("heritage.properties");
    }

    /** Le fichier d'une image préparée. */
    public static Path cachedFile(String hash) {
        return cacheFolder().resolve(hash + ".png");
    }

    // --- Catalogue ---------------------------------------------------------

    public static Plate known(String hash) {
        Plate plate = CATALOGUE.get(hash);
        return plate != null ? plate : ECHO.get(hash);
    }

    public static List<Plate> sorted() {
        List<Plate> plates = new ArrayList<>(CATALOGUE.values());
        plates.sort(Comparator.comparing(Plate::name, String.CASE_INSENSITIVE_ORDER));
        return plates;
    }

    /** Retrouve une image par son nom, à la casse près, ou par un début d'empreinte. */
    public static Plate find(String query) {
        for (Plate plate : CATALOGUE.values()) {
            if (plate.name().equalsIgnoreCase(query)) {
                return plate;
            }
        }
        String lower = query.toLowerCase(Locale.ROOT);
        for (Plate plate : CATALOGUE.values()) {
            if (plate.hash().startsWith(lower)) {
                return plate;
            }
        }
        return null;
    }

    // --- Import ------------------------------------------------------------

    /**
     * Lance l'import, sur un fil à lui.
     *
     * <p>Un seul fil, et non un pool : le travail est majoritairement du calcul entier sur de gros
     * tableaux de pixels, et le paralléliser sur toutes les cœurs disponibles ferait exactement ce
     * qu'on cherche à éviter — voler au serveur le temps processeur dont il a besoin pour son
     * premier tick. Un fil de moins que le nombre de cœurs reste libre par construction, et
     * l'import est fini avant que le premier joueur n'ait fini de charger son terrain.
     *
     * <p>{@code setDaemon(true)} : si le serveur s'arrête pendant l'import, la machine virtuelle ne
     * doit pas rester ouverte à cause de nous.
     */
    public static void awaken(MinecraftServer server) {
        if (grinding) {
            return;
        }
        grinding = true;
        Thread worker = new Thread(() -> {
            try {
                grind(server);
            } catch (Throwable trouble) {
                Lanterne.LOG.error("[ATELIER] l'import des tableaux a échoué", trouble);
            } finally {
                grinding = false;
            }
        }, "Lanterne-atelier");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static void grind(MinecraftServer server) {
        long started = System.nanoTime();
        try {
            Files.createDirectories(folder());
            Files.createDirectories(cacheFolder());
        } catch (IOException problem) {
            Lanterne.LOG.error("[ATELIER] dossier des tableaux impossible à créer : {}",
                    problem.getMessage());
            return;
        }
        Relic.recall(heritageFile());
        Properties index = readIndex();
        Properties updated = new Properties();

        int fresh = 0;
        int reused = 0;
        int refused = 0;

        // 1. Les images du joueur.
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.list(folder())) {
            walk.filter(Files::isRegularFile).filter(Mill::readable).forEach(files::add);
        } catch (IOException problem) {
            Lanterne.LOG.warn("[ATELIER] dossier des tableaux illisible : {}", problem.getMessage());
        }
        for (Path file : files) {
            if (CATALOGUE.size() >= Studio.paintingQuota()) {
                Lanterne.LOG.warn("[ATELIER] quota de {} images atteint ; « {} » et la suite sont ignorés.",
                        Studio.paintingQuota(), file.getFileName());
                break;
            }
            String stamp = stamp(file);
            String memory = index.getProperty(stamp);
            if (memory != null && adopt(memory, file.getFileName().toString())) {
                updated.setProperty(stamp, memory);
                reused++;
                continue;
            }
            Plate plate = press(file, null);
            if (plate == null) {
                refused++;
                continue;
            }
            updated.setProperty(stamp, plate.hash() + ":" + plate.pixelWidth() + ":" + plate.pixelHeight());
            fresh++;
        }

        // 2. L'héritage d'Immersive Paintings.
        int inherited = 0;
        for (Relic.Bequest bequest : Relic.inventory(gameDir(), server)) {
            if (CATALOGUE.size() >= Studio.paintingQuota()) {
                break;
            }
            // Déjà repris lors d'un démarrage précédent : le pont le sait et le fichier préparé est
            // sur le disque. Il ne reste qu'à en relire les dimensions — vingt-quatre octets — au
            // lieu de repasser l'image au moulin pour la millième fois.
            Relic.Heir already = Relic.lookup(bequest.motive());
            if (already != null) {
                int[] size = Mill.measure(cachedFile(already.hash()));
                if (size != null) {
                    CATALOGUE.putIfAbsent(already.hash(),
                            new Plate(already.hash(), trim(bequest.name()), size[0], size[1]));
                    continue;
                }
            }
            Plate plate = press(bequest.file(), bequest.name());
            if (plate == null) {
                continue;
            }
            int[] fit = plate.fittingBlocks(Studio.paintingMaxBlocks());
            Relic.bridge(bequest.motive(), plate.hash(),
                    bequest.width() > 0 ? bequest.width() : fit[0],
                    bequest.height() > 0 ? bequest.height() : fit[1]);
            inherited++;
        }

        writeIndex(updated);
        Relic.inscribe(heritageFile());
        long millis = (System.nanoTime() - started) / 1_000_000L;
        Lanterne.LOG.info(
                "[ATELIER] {} tableau(x) au catalogue en {} ms — {} préparé(s), {} déjà connu(s), "
                        + "{} hérité(s) d'Immersive Paintings, {} refusé(s).",
                CATALOGUE.size(), millis, fresh, reused, inherited, refused);

        // Le catalogue a changé : on le réannonce à ceux qui étaient déjà connectés. L'appel revient
        // sur le fil du serveur, parce que parcourir la liste des joueurs depuis un fil de fond
        // serait exactement la course de données que ce fil s'interdit.
        server.execute(Easel::proclaim);
    }

    /** Reprend une entrée d'index sans repasser par le moulin. */
    private static boolean adopt(String memory, String name) {
        String[] parts = memory.split(":");
        if (parts.length != 3 || !Mill.plausible(parts[0])) {
            return false;
        }
        if (!Files.isRegularFile(cachedFile(parts[0]))) {
            return false;
        }
        int width;
        int height;
        try {
            width = Integer.parseInt(parts[1]);
            height = Integer.parseInt(parts[2]);
        } catch (NumberFormatException broken) {
            return false;
        }
        if (width <= 0 || height <= 0) {
            return false;
        }
        CATALOGUE.put(parts[0], new Plate(parts[0], trim(name), width, height));
        return true;
    }

    /** Passe un fichier au moulin et l'ajoute au catalogue. Rend {@code null} en cas de refus. */
    private static Plate press(Path file, String forcedName) {
        try {
            Mill.Ground ground = Mill.press(file, Studio.paintingMaxSide(),
                    Studio.paintingMaxSourceBytes());
            Plate plate = ground.plate();
            if (forcedName != null && !forcedName.isEmpty()) {
                plate = new Plate(plate.hash(), trim(forcedName), plate.pixelWidth(), plate.pixelHeight());
            }
            Path target = cachedFile(plate.hash());
            if (!Files.isRegularFile(target)) {
                Files.write(target, ground.png());
            }
            CATALOGUE.put(plate.hash(), plate);
            return plate;
        } catch (IOException refusal) {
            Lanterne.LOG.warn("[ATELIER] « {} » refusé : {}", file.getFileName(), refusal.getMessage());
            return null;
        }
    }

    private static String trim(String name) {
        if (name == null || name.isBlank()) {
            return "sans titre";
        }
        return name.length() > Plate.NAME_LIMIT ? name.substring(0, Plate.NAME_LIMIT) : name;
    }

    /**
     * L'empreinte d'un fichier <em>source</em> : nom, taille, date.
     *
     * <p>Ce n'est volontairement pas un condensé du contenu. Lire quatre-vingts fichiers pour les
     * condenser coûterait presque aussi cher que de les décoder, et ferait perdre tout l'intérêt de
     * l'index. Nom, taille et date au millième de seconde changent dès qu'un fichier est modifié par
     * un moyen normal ; le cas pathologique — remplacer une image par une autre du même poids à la
     * même milliseconde — n'arrive pas, et se corrige en supprimant le dossier {@code .cache}.
     */
    private static String stamp(Path file) {
        long size = 0L;
        long time = 0L;
        try {
            size = Files.size(file);
            time = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ignored) {
            // Un fichier illisible échouera de toute façon au moulin, avec un meilleur message.
        }
        return file.getFileName().toString() + "|" + size + "|" + time;
    }

    private static Properties readIndex() {
        Properties properties = new Properties();
        Path file = indexFile();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException ignored) {
                // Un index illisible n'est pas une panne : on repasse tout au moulin, une fois.
            }
        }
        return properties;
    }

    private static void writeIndex(Properties properties) {
        try (OutputStream out = Files.newOutputStream(indexFile())) {
            properties.store(out, "Lanterne - index des tableaux deja prepares."
                    + " Supprime ce fichier pour tout refaire.");
        } catch (IOException problem) {
            Lanterne.LOG.warn("[ATELIER] index non écrit : {}", problem.getMessage());
        }
    }

    // --- Évènements serveur ------------------------------------------------

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        awaken(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        CATALOGUE.clear();
        CONVOYS.clear();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            announce(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        CONVOYS.remove(event.getEntity().getUUID());
        LIFTS.remove(event.getEntity().getUUID());
    }

    private static void announce(ServerPlayer player) {
        player.connection.send(new PaintingRoll(sorted(), law()));
    }

    /**
     * La loi du serveur, telle qu'elle est annoncée au client.
     *
     * <p>La liste de domaines est tronquée à ce que le codec réseau accepte. Un administrateur qui en
     * écrirait deux cents ferait sinon échouer l'encodage du paquet <em>à la connexion de chaque
     * joueur</em> — une panne dont la cause serait à peu près introuvable dans un journal.
     */
    public static PaintingRoll.Rules law() {
        List<String> allowed = Studio.domains();
        if (allowed.size() > 64) {
            allowed = allowed.subList(0, 64);
        }
        return new PaintingRoll.Rules(
                Studio.paintingMaxSide(),
                Studio.paintingMaxBlocks(),
                (int) (Studio.paintingMaxSourceBytes() / 1024L),
                Studio.links(),
                allowed);
    }

    /**
     * Ouvre l'écran d'édition chez ce joueur, s'il en a le droit.
     *
     * <p>Appelée depuis {@code Canvas.interact}, côté serveur. Le refus est dit dans la barre d'action
     * plutôt que dans le chat : c'est un retour sur un geste, pas une nouvelle.
     */
    public static void openFor(ServerPlayer player, Canvas canvas) {
        if (!canvas.mayEdit(player)) {
            player.sendSystemMessage(Component.literal(
                    "Cette toile appartient à quelqu'un d'autre.").withStyle(ChatFormatting.GRAY));
            return;
        }
        player.connection.send(new PaintingOpen(canvas.getId(), canvas.hash(),
                canvas.blockWidth(), canvas.blockHeight(), canvas.trim().id()));
    }

    private static void proclaim() {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            announce(player);
        }
    }

    // --- Transfert ---------------------------------------------------------

    /**
     * Le convoi d'un joueur : ce qu'il a demandé, et où en est l'envoi.
     *
     * <p>Une file par joueur, et une seule image en vol à la fois. On pourrait en envoyer plusieurs
     * de front ; ce serait plus rapide sur le papier et plus lent en pratique, parce que le débit
     * total est plafonné de toute façon et que le joueur préfère voir <em>un</em> tableau apparaître
     * tout de suite plutôt que dix à moitié.
     */
    private static final class Carrier {
        private final Deque<String> wishes = new ArrayDeque<>();
        private String hash;
        private byte[] data;
        private int offset;
    }

    /**
     * Une demande de client.
     *
     * <h2>Ce qu'on ne croit pas</h2>
     *
     * <p>Rien. L'empreinte est vérifiée comme bien formée avant tout usage — sans quoi elle
     * servirait à construire un nom de fichier, et un client malveillant écrirait
     * {@code ../../serveur.properties}. Elle doit ensuite désigner une image réellement présente au
     * catalogue. Et la file est bornée : un client qui demande mille images n'en obtient que les
     * premières, au lieu de faire grossir un tampon serveur jusqu'à la panne de mémoire.
     */
    private static void onWish(PaintingWish wish, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            String hash = wish.hash();
            if (!Mill.plausible(hash) || !CATALOGUE.containsKey(hash)) {
                return;
            }
            Carrier carrier = CONVOYS.computeIfAbsent(player.getUUID(), key -> new Carrier());
            if (carrier.wishes.size() >= 128 || carrier.wishes.contains(hash)
                    || hash.equals(carrier.hash)) {
                return;
            }
            carrier.wishes.add(hash);
        });
    }

    // --- Ce que le joueur apporte -----------------------------------------

    /**
     * Une image qui monte, et ce qu'elle doit devenir une fois arrivée.
     *
     * <p>La consigne de pose est retenue <b>ici</b> et non chez le client. Sans cela, il faudrait
     * que le client renvoie « et maintenant applique-la » après l'envoi, ce qui ouvre une fenêtre où
     * un autre joueur peut avoir cassé la toile entre-temps, et une deuxième où le client peut
     * viser une toile différente de celle qu'il avait annoncée.
     */
    private static final class Lift {
        private String hash;
        private byte[] buffer;
        private int received;
        private int entityId;
        private int width;
        private int height;
        private int trim;
        private long nextAllowed;
    }

    /** Les envois en cours, un par joueur. */
    private static final Map<UUID, Lift> LIFTS = new ConcurrentHashMap<>();

    /**
     * Délai minimal entre deux images apportées par un même joueur, en millisecondes.
     *
     * <p>Trois secondes n'empêchent personne de décorer sa base : accrocher un tableau demande de
     * choisir une image, ce qui prend plus longtemps. Ce que ce délai empêche, c'est qu'un client
     * automatisé remplisse le disque du serveur et le catalogue de tous les joueurs à la vitesse du
     * réseau.
     */
    private static final long LIFT_COOLDOWN = 3000L;

    /**
     * Le joueur demande à poser une image sur une toile.
     *
     * <h2>L'ordre des vérifications</h2>
     *
     * <p>Rien n'est cru sur parole, et chaque test précède l'opération qu'il rend inutile : la toile
     * existe-t-elle, est-elle à portée, ce joueur a-t-il le droit d'y toucher, la taille demandée
     * tient-elle dans la loi du serveur. Seulement ensuite vient la question de l'image.
     *
     * <p>Le test de <b>portée</b> mérite d'être nommé : sans lui, un client modifié pourrait changer
     * n'importe quelle toile du monde chargé depuis son coin de carte, sans jamais s'en approcher.
     * C'est la faille la plus banale de tout paquet qui porte un identifiant d'entité, et la plus
     * facile à oublier.
     */
    private static void onSet(PaintingSet set, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.level().getEntity(set.entityId()) instanceof Canvas canvas)) {
                return;
            }
            if (player.distanceToSqr(canvas) > 64.0d * 64.0d || !canvas.mayEdit(player)) {
                return;
            }
            int width = Math.clamp(set.width(), 1, Studio.paintingMaxBlocks());
            int height = Math.clamp(set.height(), 1, Studio.paintingMaxBlocks());
            Trim trim = Trim.byId(set.trim());
            String hash = set.hash();

            if (hash.isEmpty()) {
                canvas.dress("", width, height, trim);
                return;
            }
            if (!Mill.plausible(hash)) {
                return;
            }
            if (CATALOGUE.containsKey(hash)) {
                // Le cas le plus fréquent, et le seul qui compte : l'image est déjà là, rien ne
                // traverse le réseau, le tableau change dans le tick.
                canvas.dress(hash, width, height, trim);
                return;
            }
            if (CATALOGUE.size() >= Studio.paintingQuota()) {
                tell(player, "Le serveur a atteint son nombre d'images (" + Studio.paintingQuota()
                        + "). Un opérateur peut le relever dans lanterne-atelier.toml.");
                return;
            }
            Lift lift = LIFTS.computeIfAbsent(player.getUUID(), key -> new Lift());
            long now = System.currentTimeMillis();
            if (now < lift.nextAllowed) {
                tell(player, "Doucement — attends " + ((lift.nextAllowed - now) / 1000L + 1)
                        + " s avant d'apporter une autre image.");
                return;
            }
            lift.hash = hash;
            lift.buffer = null;
            lift.received = 0;
            lift.entityId = set.entityId();
            lift.width = width;
            lift.height = height;
            lift.trim = trim.id();
            lift.nextAllowed = now + LIFT_COOLDOWN;
            player.connection.send(new PaintingWant(hash));
        });
    }

    /**
     * Une tranche d'image envoyée par un joueur.
     *
     * <h2>Ce qui est vérifié, et ce qui arriverait sans</h2>
     *
     * <ul>
     *   <li><b>L'empreinte annoncée est celle qu'on attend de ce joueur.</b> Sinon un client pourrait
     *       remonter une image sans jamais avoir demandé à en poser une.</li>
     *   <li><b>La taille tient sous le plafond</b>, et ne change pas d'une tranche à l'autre.</li>
     *   <li><b>L'empreinte est recalculée à la fin.</b> C'est le contrôle qui compte : sans lui, un
     *       client pourrait faire passer n'importe quel contenu sous l'empreinte d'une image connue,
     *       et ce contenu irait ensuite dans le cache <em>de tous les joueurs du serveur</em>, où il
     *       resterait — un cache indexé par empreinte tient pour bon ce qu'il contient déjà.</li>
     *   <li><b>Les pixels sont relus</b> avant d'entrer au catalogue. Un PNG qui déclare
     *       trente mille pixels de côté ferait sinon exploser la mémoire du premier client qui le
     *       recevrait.</li>
     * </ul>
     */
    private static void onGift(PaintingGift gift, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Lift lift = LIFTS.get(player.getUUID());
            if (lift == null || lift.hash == null || !lift.hash.equals(gift.hash())) {
                return;
            }
            long ceiling = Studio.paintingMaxSourceBytes();
            if (gift.size() < 1 || gift.size() > ceiling || gift.offset() < 0) {
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

            byte[] png = lift.buffer;
            lift.buffer = null;
            lift.hash = null;
            if (!Mill.fingerprint(png).equals(gift.hash())) {
                tell(player, "Image abîmée en route — réessaie.");
                return;
            }
            int[] size = Mill.measure(png);
            if (size == null || size[0] > Studio.paintingMaxSide() || size[1] > Studio.paintingMaxSide()) {
                tell(player, "Image refusée : au-delà de " + Studio.paintingMaxSide() + " px de côté.");
                return;
            }
            try {
                Path target = cachedFile(gift.hash());
                Files.createDirectories(target.getParent());
                Files.write(target, png);
            } catch (IOException problem) {
                Lanterne.LOG.warn("[ATELIER] image apportée non écrite : {}", problem.getMessage());
                tell(player, "Le serveur n'a pas pu enregistrer l'image.");
                return;
            }
            String name = player.getGameProfile().name() + " — " + gift.hash().substring(0, 6);
            CATALOGUE.put(gift.hash(), new Plate(gift.hash(), trim(name), size[0], size[1]));
            Lanterne.LOG.info("[ATELIER] « {} » a apporté une image ({}×{} px).",
                    player.getGameProfile().name(), size[0], size[1]);

            if (player.level().getEntity(lift.entityId) instanceof Canvas canvas
                    && canvas.mayEdit(player)) {
                canvas.dress(gift.hash(), lift.width, lift.height, Trim.byId(lift.trim));
            }
            // Le catalogue a grandi : tout le monde doit le savoir, sans quoi les autres joueurs
            // verraient une toile sombre et n'auraient aucun moyen de demander l'image.
            proclaim();
        });
    }

    private static void tell(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    /**
     * Vide les convois, au rythme décidé par les réglages.
     *
     * <p>Le coût de cette méthode quand personne ne demande rien est celui d'un test sur une table
     * vide. C'est délibéré : elle est appelée à chaque tick du serveur, et un module de confort n'a
     * pas le droit de coûter quoi que ce soit quand il ne sert pas.
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
                player.connection.send(new PaintingShard(
                        carrier.hash, carrier.offset, carrier.data.length, slice));
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

    /**
     * Charge la prochaine image demandée depuis le cache disque.
     *
     * <p>Lire un fichier depuis le fil du serveur mérite justification. Le fichier est local, déjà
     * dans le cache du système de fichiers neuf fois sur dix, et pèse un mébioctet : la lecture se
     * compte en centaines de microsecondes. Surtout, elle n'a lieu qu'<b>une fois par image et par
     * joueur</b>, c'est-à-dire quelques fois dans la vie d'un serveur. Garder toutes les images en
     * mémoire pour éviter cela coûterait soixante mébioctets en permanence ; les lire à la demande
     * coûte une pause invisible, rarement.
     */
    private static boolean load(Carrier carrier) {
        while (!carrier.wishes.isEmpty()) {
            String hash = carrier.wishes.poll();
            if (hash == null || !CATALOGUE.containsKey(hash)) {
                continue;
            }
            try {
                carrier.data = Files.readAllBytes(cachedFile(hash));
                carrier.hash = hash;
                carrier.offset = 0;
                return true;
            } catch (IOException problem) {
                Lanterne.LOG.warn("[ATELIER] image « {} » illisible dans le cache : {}",
                        hash, problem.getMessage());
            }
        }
        return false;
    }

    // --- Commandes ---------------------------------------------------------

    /** Propose les noms du catalogue, entre guillemets si besoin. */
    private static final SuggestionProvider<CommandSourceStack> NAMES = (context, builder) -> {
        for (Plate plate : sorted()) {
            builder.suggest(com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(plate.name()));
        }
        return builder.buildFuture();
    };

    /**
     * L'arbre de commandes {@code /tableau}.
     *
     * <p>Enregistré par un écouteur ajouté depuis {@code Lanterne}, et non greffé sur la commande
     * existante du mod : les deux arbres sont indépendants, et cette fonctionnalité peut donc être
     * ajoutée ou retirée sans toucher une ligne du fichier de l'autre.
     */
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("tableau")
                .then(Commands.literal("liste").executes(context -> {
                    List<Plate> plates = sorted();
                    if (plates.isEmpty()) {
                        reply(context.getSource(), grinding
                                ? "Import en cours…"
                                : "Aucune image. Dépose des PNG dans config/lanterne/tableaux/ puis"
                                        + " « /tableau recharger ».");
                        return 0;
                    }
                    reply(context.getSource(), plates.size() + " image(s) :");
                    for (Plate plate : plates) {
                        int[] fit = plate.fittingBlocks(Studio.paintingMaxBlocks());
                        reply(context.getSource(), "  " + plate.name() + "  ("
                                + plate.pixelWidth() + "×" + plate.pixelHeight() + " px, propose "
                                + fit[0] + "×" + fit[1] + " blocs)");
                    }
                    return plates.size();
                }))
                .then(Commands.literal("prendre")
                        .then(Commands.argument("image", StringArgumentType.string())
                                .suggests(NAMES)
                                .executes(context -> give(context.getSource(),
                                        StringArgumentType.getString(context, "image"), 0, 0))
                                .then(Commands.argument("largeur", IntegerArgumentType.integer(1, 16))
                                        .then(Commands.argument("hauteur", IntegerArgumentType.integer(1, 16))
                                                .executes(context -> give(context.getSource(),
                                                        StringArgumentType.getString(context, "image"),
                                                        IntegerArgumentType.getInteger(context, "largeur"),
                                                        IntegerArgumentType.getInteger(context, "hauteur")))))))
                .then(Commands.literal("recharger")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> {
                            if (grinding) {
                                reply(context.getSource(), "Un import est déjà en cours.");
                                return 0;
                            }
                            MinecraftServer server = context.getSource().getServer();
                            awaken(server);
                            reply(context.getSource(), "Import relancé ; le catalogue sera réannoncé.");
                            return 1;
                        }))
                .then(Commands.literal("heritage")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> {
                            reply(context.getSource(), Relic.bridged()
                                    + " motif(s) d'Immersive Paintings reconnus. Les tableaux déjà"
                                    + " posés reprennent leur image tout seuls, au chargement de leur"
                                    + " chunk.");
                            return Relic.bridged();
                        })));
    }

    private static int give(CommandSourceStack source, String query, int width, int height) {
        Plate plate = find(query);
        if (plate == null) {
            reply(source, "Aucune image de ce nom. « /tableau liste » les montre toutes.");
            return 0;
        }
        if (width <= 0 || height <= 0) {
            int[] fit = plate.fittingBlocks(Studio.paintingMaxBlocks());
            width = fit[0];
            height = fit[1];
        }
        int limit = Studio.paintingMaxBlocks();
        if (width > limit || height > limit) {
            reply(source, "Trop grand : la limite du serveur est " + limit + " blocs de côté.");
            return 0;
        }
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException notAPlayer) {
            reply(source, "Cette commande doit être lancée par un joueur.");
            return 0;
        }
        ItemStack brush = Brush.charged(plate.hash(), width, height, Trim.BOIS);
        if (!player.getInventory().add(brush)) {
            player.drop(brush, false);
        }
        reply(source, "Pinceau « " + plate.name() + " » en " + width + "×" + height + " blocs.");
        return 1;
    }

    private static void reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
    }
}
