package fr.clubcitrouille.lanterne.content.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * La projection : enregistrement, et tout ce que le serveur accepte.
 *
 * <h2>La règle, la même qu'ailleurs, et elle décide de la structure du fichier</h2>
 *
 * <p>Un serveur ne croit rien de ce qu'un client lui dit. Ni qu'il a le droit de changer cette
 * source, ni que cette source est acceptable, ni qu'il est assez près du bloc pour le toucher. Les
 * trois sont vérifiées ici, dans cet ordre, pour chaque pli qui monte, et il n'existe aucun autre
 * chemin par lequel une séance puisse changer.
 *
 * <p>La vérification de <b>distance</b> mérite un mot, parce qu'elle est celle qu'on oublie. Sans
 * elle, un client modifié change la source d'un écran situé à l'autre bout du monde, sans l'avoir vu,
 * sans y avoir accès, et sans que personne sur place ne comprenne ce qui s'est passé. Le verrou
 * protège du mauvais joueur ; la distance protège du joueur absent.
 *
 * <h2>Pourquoi les blocs s'enregistrent toujours, même coupés</h2>
 *
 * <p>{@link Booth#active()} éteint la projection ; il ne retire aucun bloc du registre. Un bloc absent
 * du registre transforme en air tout ce qui a été bâti — un mur de cinquante blocs disparaît parce
 * qu'un administrateur a basculé un interrupteur. C'est la même leçon que l'ancre, et elle est écrite
 * en toutes lettres dans {@code Lanterne} : un interrupteur ne doit jamais pouvoir détruire une
 * construction.
 */
public final class Screens {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Lanterne.ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Lanterne.ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Lanterne.ID);

    /**
     * Le bloc d'écran.
     *
     * <p>Solide comme une machine, et pas comme un coffre-fort : on en pose deux cent cinquante-six,
     * et exiger de la netherite pour chacun rendrait le mur impossible à bâtir. Il se casse à la
     * pioche et se récupère entier.
     *
     * <p>Il n'émet <b>aucune</b> lumière, même en jouant. Un écran qui éclairerait la pièce à quinze
     * empêcherait les monstres d'apparaître sur toute une base — ce serait une lampe déguisée en
     * écran, et l'on découvrirait la mécanique par accident. La lumière de l'image est peinte, pas
     * émise ; voir {@code Feed.brightness}.
     */
    public static final DeferredBlock<Panel> PANEL =
            BLOCKS.registerBlock("ecran", Panel::new, () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.COPPER_BULB)
                    .requiresCorrectToolForDrops());

    public static final DeferredItem<net.minecraft.world.item.BlockItem> PANEL_ITEM =
            ITEMS.registerSimpleBlockItem("ecran", PANEL);

    /**
     * Le projecteur.
     *
     * <p>Lui éclaire un peu quand il tourne — sept, comme l'ancre. Ce n'est pas une décoration : un
     * bloc allumé au fond d'une pièce sombre dit qu'il travaille, et c'est la seule information qu'on
     * ait sur lui quand on est derrière.
     */
    public static final DeferredBlock<Beamer> BEAMER =
            BLOCKS.registerBlock("projecteur", Beamer::new, () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(3.5F, 6.0F)
                    .sound(SoundType.COPPER)
                    .lightLevel(state -> state.getValue(Beamer.LIT) ? 7 : 0)
                    .requiresCorrectToolForDrops());

    public static final DeferredItem<net.minecraft.world.item.BlockItem> BEAMER_ITEM =
            ITEMS.registerSimpleBlockItem("projecteur", BEAMER);

    public static final DeferredRegister<net.minecraft.sounds.SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, Lanterne.ID);

    /**
     * L'évènement sonore d'un écran.
     *
     * <h2>Un seul, pour tous les écrans du monde</h2>
     *
     * <p>La définition qu'il désigne pointe vers une seconde de silence, et ce silence n'est
     * <b>jamais joué</b> : {@code client.screen.Blare} redéfinit {@code getStream} et fournit le flux
     * vivant à sa place. Le fichier n'existe que pour que le chargeur de sons ne rejette pas
     * l'évènement au démarrage — un évènement sans ressource est écarté avec un avertissement, et
     * l'on ne pourrait plus le jouer du tout.
     *
     * <p>Un seul suffit parce que le routage se fait <b>par instance</b> et non par chemin de
     * fichier. C'est ce qui évite les seize définitions et le mixin qu'aurait demandés un routage par
     * identifiant — voir {@code client.screen.Blare} pour l'explication complète.
     */
    public static final DeferredHolder<net.minecraft.sounds.SoundEvent, net.minecraft.sounds.SoundEvent>
            SCREEN_SOUND = SOUNDS.register("ecran",
                    () -> net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
                            net.minecraft.resources.Identifier.fromNamespaceAndPath(
                                    Lanterne.ID, "ecran")));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PanelEntity>> PANEL_ENTITY =
            BLOCK_ENTITIES.register("ecran",
                    () -> new BlockEntityType<>(PanelEntity::new, PANEL.get()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BeamerEntity>> BEAMER_ENTITY =
            BLOCK_ENTITIES.register("projecteur",
                    () -> new BlockEntityType<>(BeamerEntity::new, BEAMER.get()));

    /** Version du protocole de projection. Un client d'une autre version n'entre pas sur le canal. */
    private static final String PROTOCOL = "1";

    /**
     * Portée du bras, au carré, pour qu'un pli touche un bloc qu'on aurait pu toucher.
     *
     * <p>Huit blocs : la portée maximale en créatif est de cinq, et la marge couvre le déplacement
     * entre l'envoi du pli et son traitement. Un joueur qui recule d'un pas pendant que son clic
     * voyage ne doit pas voir son geste refusé.
     */
    private static final double REACH_SQ = 64d;

    /** Durée maximale qu'un client peut annoncer : douze heures. Au-delà, c'est un mensonge. */
    private static final long DURATION_CAP = 12L * 3600L * 1000L;

    private Screens() {}

    // --- La part cliente, tenue à distance ------------------------------------

    /**
     * Ce que le client sait faire des plis qu'il reçoit.
     *
     * <p>Même mécanique et même raison que {@code content.disc.Groove.Turntable} : cette classe est
     * chargée par un serveur dédié, où l'écran de réglages et le moteur de décodage n'existent pas.
     * Le code commun ne nomme donc qu'une interface, implémentée du seul côté client.
     */
    public interface Projectionist {
        void open(Mail.Open payload);

        void rules(Mail.Rules payload);
    }

    /** Ce qu'un serveur dédié fait de ces plis : rien. Il ne les recevra jamais. */
    private static final Projectionist DEAF = new Projectionist() {
        @Override
        public void open(Mail.Open payload) {}

        @Override
        public void rules(Mail.Rules payload) {}
    };

    private static Projectionist projectionist = DEAF;

    /** Le client s'annonce. Appelé derrière une garde de distribution, et de nulle part ailleurs. */
    public static void clientSide(Projectionist work) {
        projectionist = work;
    }

    // --- Enregistrement --------------------------------------------------------

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        SOUNDS.register(modBus);
        modBus.addListener(Screens::onBuildCreativeTab);
        modBus.addListener(Screens::onRegisterPayloads);
        NeoForge.EVENT_BUS.register(Screens.class);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        registrar.playToClient(Mail.Open.TYPE, Mail.Open.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> projectionist.open(payload)));
        registrar.playToClient(Mail.Rules.TYPE, Mail.Rules.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> projectionist.rules(payload)));
        registrar.playToServer(Mail.Set.TYPE, Mail.Set.STREAM_CODEC, Screens::onSet);
        registrar.playToServer(Mail.Command.TYPE, Mail.Command.STREAM_CODEC, Screens::onCommand);
        registrar.playToServer(Mail.Measure.TYPE, Mail.Measure.STREAM_CODEC, Screens::onMeasure);
        registrar.playToServer(Mail.Aim.TYPE, Mail.Aim.STREAM_CODEC, Screens::onAim);
    }

    private static void onBuildCreativeTab(
            net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == null
                || !Lanterne.ID.equals(event.getTabKey().identifier().getNamespace())) {
            return;
        }
        event.accept(new ItemStack(PANEL_ITEM.get()));
        event.accept(new ItemStack(BEAMER_ITEM.get()));
    }

    /**
     * Les règles du serveur, à l'arrivée du joueur.
     *
     * <p>Avant qu'il n'ait pu cliquer sur quoi que ce soit : l'écran de réglages les suppose connues
     * pour dire ce qui passerait, et le client les consulte avant toute connexion sortante. Les
     * envoyer à la demande aurait créé un instant — le premier clic — où le client ne sait pas encore
     * ce qu'il a le droit de charger.
     */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            player.connection.send(rules());
        }
    }

    private static Mail.Rules rules() {
        return new Mail.Rules(Booth.domains(), Booth.filtering(), Booth.active(),
                Booth.rangeCap(), Booth.throwCap());
    }

    // --- Ce que le serveur accepte ---------------------------------------------

    /** Ouvre l'écran de réglages, après vérification du verrou. Appelée par les deux blocs. */
    public static void openConsole(ServerPlayer player, Stage stage) {
        if (!Booth.active()) {
            tell(player, "La projection est désactivée sur ce serveur.");
            return;
        }
        if (!stage.mayCommand(player)) {
            tell(player, "Cet écran est réservé à " + (stage.ownerName().isBlank()
                    ? "son poseur." : "« " + stage.ownerName() + " »."));
            return;
        }
        player.connection.send(new Mail.Open(stage.stagePos()));
    }

    /**
     * Retrouve la scène que ce pli désigne, ou {@code null} si quoi que ce soit cloche.
     *
     * <p>Une seule porte pour les quatre plis montants. Toute vérification écrite ici est faite
     * quatre fois ; toute vérification écrite dans un gestionnaire ne l'est qu'une, et sera oubliée
     * dans le cinquième qu'on ajoutera un jour.
     *
     * <p>Pour un mur, elle rend le <b>maître</b> et non le bloc cliqué : c'est lui qui porte la
     * séance, et un pli visant un esclave doit donc être redirigé plutôt que refusé — le client n'a
     * pas à connaître notre convention d'assemblage.
     */
    private static Stage reach(IPayloadContext context, BlockPos pos) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return null;
        }
        if (!Booth.active()) {
            tell(player, "La projection est désactivée sur ce serveur.");
            return null;
        }
        Level level = player.level();
        // Le chunk doit être chargé. Sans ce test, un pli forgé fait charger n'importe quel chunk du
        // monde, ce qui est un levier de déni de service gratuit et silencieux.
        if (!level.isLoaded(pos) || player.distanceToSqr(
                pos.getX() + 0.5d, pos.getY() + 0.5d, pos.getZ() + 0.5d) > REACH_SQ) {
            return null;
        }
        Stage stage = switch (level.getBlockEntity(pos)) {
            case PanelEntity panel -> Wall.master(level, panel);
            case BeamerEntity beamer -> beamer;
            case null, default -> null;
        };
        if (stage == null || !stage.mayCommand(player)) {
            return null;
        }
        return stage;
    }

    private static void onSet(Mail.Set payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Stage stage = reach(context, payload.pos());
            if (stage == null || !(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // La portée sonore est bornée par le serveur EN PLUS de l'être par Feed.sane : le
            // plafond de l'administrateur n'est pas connu de l'enregistrement, qui ne connaît que
            // le plafond absolu.
            Feed wanted = payload.feed().sane()
                    .withRange(Math.min(payload.feed().range(), Booth.rangeCap()));

            boolean sourceChanged = !wanted.source().equals(stage.feed().source());
            if (sourceChanged && !wanted.idle()) {
                Sieve.Verdict verdict = Booth.admits(wanted.source());
                if (!verdict.ok()) {
                    tell(player, "Source refusée : " + verdict.label() + ".");
                    // Une liste vide ne filtre plus rien (voir Booth.filtering), donc ce verdict
                    // implique desormais une liste REELLEMENT garnie. La branche « aucun —
                    // l'administrateur doit en inscrire » etait le message qu'un joueur voyait
                    // alors qu'il ETAIT l'administrateur et n'avait rien demande de tel.
                    if (verdict == Sieve.Verdict.HORS_LISTE) {
                        tell(player, "Domaines autorisés : " + String.join(", ", Booth.domains()));
                    }
                    return;
                }
            }

            long now = stage.stagePos() == null ? 0L : gameTime(player);
            // Une source qui change remet l'horloge à zéro, en pause. Garder la position aurait
            // ouvert le nouveau film à la minute où l'on en était du précédent — et la durée de
            // l'ancien, qui ne veut plus rien dire, aurait replié la boucle n'importe où.
            Clock beat = sourceChanged ? stage.clock().rewind(now) : stage.clock();
            stage.setShow(wanted, beat);
            if (sourceChanged && wanted.autoplay() && !wanted.idle()) {
                stage.setShow(wanted, beat.play(now));
            }
            relight(player.level(), stage);
        });
    }

    private static void onCommand(Mail.Command payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Stage stage = reach(context, payload.pos());
            if (stage == null || !(context.player() instanceof ServerPlayer player)) {
                return;
            }
            long now = gameTime(player);
            Clock beat = stage.clock();
            Feed feed = stage.feed();
            switch (payload.verb()) {
                case LIRE -> {
                    if (feed.idle()) {
                        return; // rien à lire : un bouton qui ne fait rien vaut mieux qu'un mensonge
                    }
                    beat = beat.play(now);
                }
                case PAUSE -> beat = beat.pause(now);
                case CURSEUR -> beat = beat.seek(now, Math.max(0L, payload.millis()));
                case ARRET -> beat = beat.rewind(now).withDuration(beat.durationMillis());
            }
            stage.setShow(feed, beat);
            relight(player.level(), stage);
        });
    }

    /**
     * La durée, rapportée par un client qui a ouvert le flux.
     *
     * <h2>Trois conditions, et chacune arrête une bêtise différente</h2>
     *
     * <p><b>Seulement si elle est inconnue.</b> Sans cela, deux clients dont les moteurs lisent des
     * durées légèrement différentes se la corrigeraient mutuellement, et la boucle sauterait à chaque
     * échange. Le premier qui sait fait foi ; les suivants se taisent.
     *
     * <p><b>Seulement entre des bornes.</b> Une durée d'une seconde ferait boucler l'image vingt fois
     * par seconde chez tout le monde. C'est la seule façon qu'un client a de nuire ici, et elle coûte
     * une comparaison.
     *
     * <p><b>Seulement à portée.</b> Comme tous les autres plis — voir {@link #reach}.
     */
    private static void onMeasure(Mail.Measure payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Stage stage = reach(context, payload.pos());
            if (stage == null || stage.feed().idle()) {
                return;
            }
            if (stage.clock().durationMillis() > 0L) {
                return;
            }
            long millis = payload.millis();
            if (millis < 500L || millis > DURATION_CAP) {
                return;
            }
            stage.setShow(stage.feed(), stage.clock().withDuration(millis));
        });
    }

    private static void onAim(Mail.Aim payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (reach(context, payload.pos()) instanceof BeamerEntity beamer) {
                beamer.aim(payload.reach(), payload.span());
            }
        });
    }

    /**
     * Le projecteur s'allume quand il joue.
     *
     * <p>Un changement d'état de bloc est cher — il touche le chunk, la lumière et le réseau — et
     * c'est pourquoi il n'a lieu que si la valeur change réellement. Sans ce test, chaque réglage de
     * volume recalculerait l'éclairage d'une section entière.
     */
    private static void relight(Level level, Stage stage) {
        if (!(stage instanceof BeamerEntity beamer)) {
            return;
        }
        boolean lit = !stage.feed().idle() && !stage.clock().paused();
        var state = beamer.getBlockState();
        if (state.getValue(Beamer.LIT) != lit) {
            level.setBlock(beamer.stagePos(), state.setValue(Beamer.LIT, lit), 3);
        }
    }

    private static long gameTime(ServerPlayer player) {
        return player.level().getGameTime();
    }

    public static void tell(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    // --- Commandes -------------------------------------------------------------

    /**
     * La liste blanche, réglable sans toucher au fichier.
     *
     * <p>Le même argument que {@code core.Config} fait pour ses interrupteurs : demander à un
     * administrateur d'un hébergement partagé d'éditer un fichier TOML, de le téléverser et de
     * redémarrer, c'est lui demander de renoncer. Une commande fait la même chose en une ligne,
     * depuis la console, sur un serveur qui tourne.
     */
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("projection")
                .then(Commands.literal("domaines").executes(context -> {
                    CommandSourceStack source = context.getSource();
                    reply(source, Booth.filtering()
                            ? "Liste blanche ACTIVE."
                            : "Liste blanche DÉSACTIVÉE — tout lien public passe.");
                    List<String> known = Booth.domains();
                    if (known.isEmpty()) {
                        reply(source, "  aucun domaine inscrit : aucune source ne sera acceptée.");
                    }
                    known.forEach(domain -> reply(source, "  " + domain));
                    return known.size();
                }))
                .then(Commands.literal("domaine")
                        .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.literal("ajouter")
                                .then(Commands.argument("domaine", StringArgumentType.string())
                                        .executes(context -> edit(context.getSource(),
                                                StringArgumentType.getString(context, "domaine"),
                                                true))))
                        .then(Commands.literal("retirer")
                                .then(Commands.argument("domaine", StringArgumentType.string())
                                        .suggests((context, builder) -> {
                                            Booth.domains().forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> edit(context.getSource(),
                                                StringArgumentType.getString(context, "domaine"),
                                                false))))));
    }

    private static int edit(CommandSourceStack source, String raw, boolean adding) {
        String domain = raw.trim().toLowerCase(Locale.ROOT);
        if (domain.isEmpty() || domain.contains("/") || domain.contains(" ")) {
            reply(source, "Un domaine, pas une adresse : « exemple.com » ou « *.exemple.com ».");
            return 0;
        }
        List<String> known = new ArrayList<>(Booth.domains());
        boolean changed = adding ? (!known.contains(domain) && known.add(domain))
                : known.remove(domain);
        if (!changed) {
            reply(source, adding ? "Déjà inscrit." : "Pas dans la liste.");
            return 0;
        }
        // « save() » écrit le fichier : sans lui, la liste serait juste pour cette session et
        // l'administrateur découvrirait au redémarrage suivant que son travail a disparu.
        Booth.DOMAINS.set(known);
        Booth.SPEC.save();
        reply(source, (adding ? "Ajouté : " : "Retiré : ") + domain + " — " + known.size()
                + " domaine(s).");
        // Les clients doivent savoir : ils appliquent le même tamis avant d'aller chercher quoi que
        // ce soit. Une liste élargie chez le serveur et pas chez eux refuserait une source que le
        // serveur vient d'accepter, ce qui est le pire des deux mondes.
        var server = source.getServer();
        server.getPlayerList().getPlayers().forEach(player -> player.connection.send(rules()));
        return known.size();
    }

    private static void reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
    }
}
