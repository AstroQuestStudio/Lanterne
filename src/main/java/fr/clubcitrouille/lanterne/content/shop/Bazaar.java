package fr.clubcitrouille.lanterne.content.shop;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Le bazar : ce qui relie la caisse, l'étal, le grand livre, les commandes et le réseau.
 *
 * <h2>La règle qui gouverne ce fichier</h2>
 *
 * <p>Un serveur ne croit <b>rien</b> de ce qu'un client lui dit. L'identité du joueur ne vient jamais
 * du paquet : elle est lue dans la connexion. Le prix n'est jamais dans le paquet : il est calculé
 * ici. La quantité est bornée ici. C'est la même discipline que {@code content.waypoint.Waypoints},
 * et elle compte davantage encore : un repère volé se repose, un solde volé ne se reprend pas.
 *
 * <h2>Pourquoi aucun {@code AbstractContainerMenu}</h2>
 *
 * <p>Une boutique ressemble à un conteneur, et l'on est tenté d'en écrire un : des emplacements, des
 * piles, un {@code MenuType} à enregistrer. C'est un piège. Un conteneur synchronise des <em>piles
 * d'objets</em> entre client et serveur, avec un protocole de verrouillage et de réconciliation conçu
 * pour des coffres ; nos cases ne sont pas des piles, ce sont des <b>lignes de catalogue</b> qui ne
 * changent jamais de main. On paierait tout le coût du mécanisme sans en employer la propriété utile.
 *
 * <p>Deux paquets suffisent : le catalogue coté descend, la demande monte. L'écran est un
 * {@code Screen} ordinaire, comme le carnet de repères, et l'inventaire du joueur reste le seul
 * conteneur du jeu.
 *
 * <h2>Le chargement des classes du client</h2>
 *
 * <p>Les deux gestionnaires de paquets entrants côté client référencent {@code client.shop.Slate}.
 * Le corps d'une lambda n'est chargé qu'à son premier appel : un serveur dédié enregistre donc ces
 * canaux sans jamais charger la moindre classe cliente. Le même détail est expliqué dans
 * {@code content.painting.Easel}, où il avait coûté un plantage au démarrage.
 */
public final class Bazaar {
    /** Version du protocole. Un client d'une autre version se verra refuser le canal. */
    private static final String PROTOCOL = "1";

    /** Ticks écoulés depuis la dernière détente des volumes. */
    private static int sinceRelax;

    private Bazaar() {}

    // --- Enregistrement ----------------------------------------------------

    public static void register(IEventBus modBus) {
        modBus.addListener(Bazaar::onRegisterPayloads);
        NeoForge.EVENT_BUS.register(Bazaar.class);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL);
        registrar.playToClient(StallSync.TYPE, StallSync.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> fr.clubcitrouille.lanterne.client.shop.Slate.accept(payload)));
        registrar.playToClient(TradeEcho.TYPE, TradeEcho.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> fr.clubcitrouille.lanterne.client.shop.Slate.absorb(payload)));
        registrar.playToServer(TradeAsk.TYPE, TradeAsk.STREAM_CODEC, Bazaar::onAsk);
    }

    // --- Évènements serveur ------------------------------------------------

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // Après « ServerStarted », les paquets de données sont chargés et le gestionnaire de
        // recettes est complet — c'est la première occasion où le catalogue peut être engendré.
        Stall.awaken(event.getServer());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        // Le compte est ouvert même si la boutique est éteinte : un administrateur qui l'allume six
        // mois plus tard ne veut pas que ses joueurs partent de zéro parce qu'ils étaient là avant.
        Ledger.of(server).seat(player);
        if (Tariff.active()) {
            sync(player, false);
        }
    }

    /**
     * La détente des volumes, au rythme du serveur.
     *
     * <p>Le coût quand rien n'est à détendre est celui d'une comparaison d'entiers. C'est délibéré :
     * la méthode est appelée vingt fois par seconde, et un module de confort n'a pas le droit de
     * coûter quoi que ce soit quand il ne sert pas. C'est la règle du mod entier.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // La garde couvre DEUX détentes, à deux rythmes : celle des volumes, qui ramène les prix
        // vers leur ancre, et celle des compteurs de puits, qui reconstitue les franchises. La
        // première version ne testait que la dérive ; sur un serveur qui l'aurait coupée en gardant
        // les puits, aucun compteur ne serait jamais redescendu et la première grosse vente aurait
        // valu une retenue perpétuelle. Le défaut était silencieux, et c'est ce qui le rendait
        // grave.
        if (!Tariff.active()) {
            return;
        }
        boolean volumes = Tariff.drift() && Tariff.relaxPermille() > 0;
        boolean counters = Tariff.tollOn() && Tariff.tollRelaxPermille() > 0;
        if (!volumes && !counters) {
            return;
        }
        if (++sinceRelax < Tariff.relaxTicks()) {
            return;
        }
        sinceRelax = 0;
        Ledger.of(event.getServer()).relax();
    }

    // --- Réseau ------------------------------------------------------------

    /**
     * Envoie à ce joueur le catalogue coté et son solde.
     *
     * <p><b>Le catalogue est coté pour lui, pas pour le serveur.</b> Depuis les puits, deux joueurs
     * devant le même article ne touchent plus la même somme, et ce paquet est déjà construit par
     * destinataire — la personnalisation ne coûte donc rien de plus qu'un appel par article. Cet
     * appel, {@link Toll#keep}, ressort immédiatement pour un joueur dont les compteurs sont vides,
     * ce qui est le cas courant : mille trois cents consultations de table de hachage, et pas une
     * tangente hyperbolique.
     */
    public static void sync(ServerPlayer player, boolean open) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        Ledger ledger = Ledger.of(server);
        List<Quote> quotes = new ArrayList<>(Math.min(Stall.size(), StallSync.CEILING));
        for (Offer offer : Stall.all()) {
            if (quotes.size() >= StallSync.CEILING) {
                // Tronquer et le dire vaut mieux que l'exception qu'un codec de liste lève à
                // l'écriture : celle-ci ferait échouer la connexion de chaque joueur, en silence.
                Lanterne.LOG.warn("[BOUTIQUE] catalogue de {} articles : seuls les {} premiers sont"
                        + " envoyés au client. Allège-le, ou la boutique sera incomplète.",
                        Stall.size(), StallSync.CEILING);
                break;
            }
            quotes.add(Quote.of(offer, ledger.volume(offer.item()),
                    Toll.keep(ledger, player.getUUID(), offer.item())));
        }
        PacketDistributor.sendToPlayer(player, new StallSync(open, Tariff.symbol(),
                ledger.balance(player.getUUID()), Tariff.batch(), quotes));
    }

    /** Renvoie le catalogue à tous : après un rechargement, un prix périmé serait un mensonge. */
    public static void syncAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sync(player, false);
        }
    }

    /**
     * Traite une demande du client, sur le fil du serveur.
     *
     * <p>{@code enqueueWork} n'est pas une précaution de style : le gestionnaire est appelé sur le fil
     * réseau, et toucher au grand livre ou à l'inventaire depuis là serait une course de données
     * pure et simple — celle qui produit, une fois sur dix mille, un solde faux que personne ne sait
     * reproduire.
     */
    private static void onAsk(TradeAsk ask, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!Tariff.active()) {
                answer(player, Till.Receipt.no(Till.Outcome.OFF, 0L), ask.item());
                return;
            }
            if (ask.verb() == TradeAsk.Verb.OPEN) {
                sync(player, true);
                return;
            }
            Till.Receipt receipt = ask.verb() == TradeAsk.Verb.BUY
                    ? Till.buy(player, ask.item(), ask.count())
                    : Till.sell(player, ask.item(), ask.count());
            answer(player, receipt, ask.item());
        });
    }

    /** Renvoie le ticket de caisse, et l'article tel qu'il est après l'opération. */
    private static void answer(ServerPlayer player, Till.Receipt receipt, Identifier id) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        Ledger ledger = Ledger.of(server);
        Offer offer = Stall.find(id);
        long volume = ledger.volume(id);
        long buy = offer == null ? 0L : Drift.buy(offer, volume);
        long sell = offer == null ? 0L : Drift.sell(offer, volume);
        // La part est relue APRÈS la transaction : le joueur veut savoir ce qu'il touchera à la
        // vente suivante, pas ce qu'il vient de toucher — ce dernier chiffre est déjà sur son
        // ticket.
        int keep = offer == null ? Toll.FULL : Toll.keep(ledger, player.getUUID(), id);
        PacketDistributor.sendToPlayer(player, new TradeEcho(receipt.outcome(), receipt.detail(),
                receipt.count(), receipt.total(), ledger.balance(player.getUUID()), id, buy, sell,
                Drift.trend(volume), keep));
    }

    // --- Commandes ---------------------------------------------------------

    /**
     * Les deux arbres de commandes, {@code /banque} et {@code /boutique}.
     *
     * <p>Deux arbres séparés, et non greffés sur {@code /lanterne} : le mod doit pouvoir perdre sa
     * boutique sans qu'une ligne de {@code report.LanterneCommand} n'ait à bouger. C'est la même
     * séparation que {@code /tableau} et {@code /disque}.
     *
     * <p>Les montants sont pris comme des <b>chaînes</b> et non comme des décimaux. Brigadier lit très
     * bien un {@code double}, mais un {@code double} ne représente pas 0,10 — et l'analyseur de
     * {@link Coin} n'emploie, lui, que des entiers. Le point est le séparateur décimal sans
     * guillemets ; la virgule marche aussi, entre guillemets : {@code /banque payer Bob "12,50"}.
     */
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("banque")
                .executes(context -> mine(context.getSource()))
                .then(Commands.literal("classement").executes(context -> board(context.getSource())))
                .then(Commands.literal("payer")
                        .then(Commands.argument("joueur", EntityArgument.player())
                                .then(Commands.argument("montant", StringArgumentType.string())
                                        .executes(context -> pay(context.getSource(),
                                                EntityArgument.getPlayer(context, "joueur"),
                                                StringArgumentType.getString(context, "montant"))))))
                .then(Commands.literal("voir")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("joueur", EntityArgument.player())
                                .executes(context -> peek(context.getSource(),
                                        EntityArgument.getPlayer(context, "joueur")))))
                .then(Commands.literal("donner")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("joueur", EntityArgument.player())
                                .then(Commands.argument("montant", StringArgumentType.string())
                                        .executes(context -> move(context.getSource(),
                                                EntityArgument.getPlayer(context, "joueur"),
                                                StringArgumentType.getString(context, "montant"),
                                                Move.GIVE)))))
                .then(Commands.literal("retirer")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("joueur", EntityArgument.player())
                                .then(Commands.argument("montant", StringArgumentType.string())
                                        .executes(context -> move(context.getSource(),
                                                EntityArgument.getPlayer(context, "joueur"),
                                                StringArgumentType.getString(context, "montant"),
                                                Move.TAKE)))))
                .then(Commands.literal("fixer")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("joueur", EntityArgument.player())
                                .then(Commands.argument("montant", StringArgumentType.string())
                                        .executes(context -> move(context.getSource(),
                                                EntityArgument.getPlayer(context, "joueur"),
                                                StringArgumentType.getString(context, "montant"),
                                                Move.SET)))))
                .then(Commands.literal("masse")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> mass(context.getSource()))));

        dispatcher.register(Commands.literal("boutique")
                .executes(context -> openShop(context.getSource()))
                .then(Commands.literal("cours")
                        .executes(context -> quotes(context.getSource(), null))
                        .then(Commands.argument("objet", ItemArgument.item(event.getBuildContext()))
                                .executes(context -> quotes(context.getSource(),
                                        idOf(context, "objet")))))
                .then(Commands.literal("poser")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("objet", ItemArgument.item(event.getBuildContext()))
                                .then(Commands.argument("achat", StringArgumentType.string())
                                        .then(Commands.argument("rachat", StringArgumentType.string())
                                                .executes(context -> shelve(context.getSource(),
                                                        idOf(context, "objet"),
                                                        StringArgumentType.getString(context, "achat"),
                                                        StringArgumentType.getString(context, "rachat"),
                                                        null))
                                                .then(Commands.argument("rayon",
                                                                StringArgumentType.string())
                                                        .executes(context -> shelve(context.getSource(),
                                                                idOf(context, "objet"),
                                                                StringArgumentType.getString(context, "achat"),
                                                                StringArgumentType.getString(context, "rachat"),
                                                                StringArgumentType.getString(context, "rayon"))))))))
                .then(Commands.literal("retirer")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("objet", ItemArgument.item(event.getBuildContext()))
                                .executes(context -> unshelve(context.getSource(),
                                        idOf(context, "objet")))))
                .then(Commands.literal("recharger")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> reload(context.getSource())))
                .then(Commands.literal("ecrire")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> inscribe(context.getSource())))
                .then(Commands.literal("generer")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> generate(context.getSource())))
                .then(Commands.literal("verifier")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> verify(context.getSource())))
                .then(Commands.literal("remettre")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> reset(context.getSource(), null))
                        .then(Commands.argument("objet", ItemArgument.item(event.getBuildContext()))
                                .executes(context -> reset(context.getSource(),
                                        idOf(context, "objet")))))
                .then(Commands.literal("quota")
                        .executes(context -> quota(context.getSource(),
                                context.getSource().getPlayerOrException()))
                        .then(Commands.argument("joueur", EntityArgument.player())
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(context -> quota(context.getSource(),
                                        EntityArgument.getPlayer(context, "joueur"))))
                        .then(Commands.literal("remettre")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.argument("joueur", EntityArgument.player())
                                        .executes(context -> pardon(context.getSource(),
                                                EntityArgument.getPlayer(context, "joueur")))))));
    }

    /**
     * Où en est un joueur de ses franchises.
     *
     * <p>Ouverte à tous pour soi-même, réservée aux administrateurs pour autrui. C'est le pendant en
     * texte de ce que l'écran montre : l'écran dit « ta part est de 34 % » sur l'article regardé,
     * cette commande dit <em>pourquoi</em>, et sur quels articles. Un joueur qui ne comprend pas
     * pourquoi son fer se vend moins cher qu'hier doit pouvoir obtenir la réponse sans demander à un
     * administrateur.
     */
    private static int quota(CommandSourceStack source, ServerPlayer who) {
        if (offline(source)) {
            return 0;
        }
        if (!Tariff.tollOn()) {
            tell(source, "Aucune limite de vente sur ce serveur : la boutique paie le cours plein,"
                    + " quoi que tu vendes et quelle qu'en soit la quantité.");
            return 1;
        }
        Ledger ledger = Ledger.of(source.getServer());
        String name = who.getGameProfile().name();
        long total = ledger.takings(who.getUUID());
        if (total <= 0L) {
            tell(source, name + " : aucune vente récente. La boutique paie le cours plein sur tout"
                    + " le catalogue.");
            return 1;
        }
        if (Tariff.debitOn()) {
            int held = Toll.withhold(total, Tariff.debitAllowance(), Tariff.debitMost());
            tell(source, name + " — ventes récentes, tous articles : " + Coin.say(total)
                    + " sur une franchise de " + Coin.say(Tariff.debitAllowance()) + ". "
                    + (held == 0 ? "Rien n'est retenu." : "Retenue de " + held / 10 + " %."));
        }
        if (Tariff.quotaOn()) {
            var worst = ledger.biggestTakings(who.getUUID(), 5);
            if (worst.isEmpty()) {
                tell(source, "Aucun article au-dessus de sa franchise.");
            } else {
                tell(source, "Par article (franchise " + Coin.say(Tariff.quotaAllowance()) + ") :");
                for (var entry : worst) {
                    int held = Toll.withhold(entry.getValue(), Tariff.quotaAllowance(),
                            Tariff.quotaMost());
                    tell(source, "  " + entry.getKey() + "   " + Coin.say(entry.getValue())
                            + (held == 0 ? "   (sous la franchise)" : "   retenue " + held / 10
                            + " %"));
                }
            }
        }
        tell(source, "Ces compteurs redescendent tout seuls : environ la moitié par jour de serveur"
                + " allumé. Vendre autre chose, en attendant, rapporte le plein tarif.");
        return 1;
    }

    private static int pardon(CommandSourceStack source, ServerPlayer who) {
        if (offline(source)) {
            return 0;
        }
        String name = who.getGameProfile().name();
        if (!Ledger.of(source.getServer()).pardon(who.getUUID())) {
            tell(source, name + " n'avait aucun compteur à remettre.");
            return 0;
        }
        tell(source, name + " : compteurs de vente remis à neuf. Il touche de nouveau le cours"
                + " plein partout.");
        sync(who, false);
        return 1;
    }

    private enum Move { GIVE, TAKE, SET }

    private static Identifier idOf(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
            String name) throws CommandSyntaxException {
        Item item = ItemArgument.getItem(context, name).item().value();
        return BuiltInRegistries.ITEM.getKey(item);
    }

    // --- /banque -----------------------------------------------------------

    private static int mine(CommandSourceStack source) throws CommandSyntaxException {
        if (offline(source)) {
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        Ledger ledger = Ledger.of(source.getServer());
        tell(source, "Ton solde : " + Coin.say(ledger.balance(player.getUUID())));
        return 1;
    }

    private static int peek(CommandSourceStack source, ServerPlayer target) {
        if (offline(source)) {
            return 0;
        }
        Ledger ledger = Ledger.of(source.getServer());
        tell(source, target.getGameProfile().name() + " : "
                + Coin.say(ledger.balance(target.getUUID())));
        return 1;
    }

    private static int board(CommandSourceStack source) {
        if (offline(source)) {
            return 0;
        }
        Ledger ledger = Ledger.of(source.getServer());
        List<Ledger.Account> best = ledger.richest(Tariff.boardSize());
        if (best.isEmpty()) {
            tell(source, "Aucun compte ouvert.");
            return 0;
        }
        tell(source, "Les plus fortunés :");
        for (int rank = 0; rank < best.size(); rank++) {
            Ledger.Account account = best.get(rank);
            tell(source, "  " + (rank + 1) + ". " + account.name() + "   "
                    + Coin.say(account.amount()));
        }
        return best.size();
    }

    /**
     * L'état de la masse monétaire, et de ce qui la freine.
     *
     * <p>Les deux nombres se lisent ensemble : la masse dit où en est le serveur, la retenue dit ce
     * que les puits ont empêché depuis le premier jour. Un serveur où la retenue reste à zéro après
     * un mois n'a pas de fermes — ou a des franchises trop larges.
     */
    private static int mass(CommandSourceStack source) {
        if (offline(source)) {
            return 0;
        }
        Ledger ledger = Ledger.of(source.getServer());
        tell(source, ledger.accountCount() + " compte(s), " + Coin.say(ledger.mass())
                + " en circulation, " + ledger.driftedCount() + " article(s) hors de leur ancre.");
        if (Tariff.tollOn() || ledger.withheld() > 0L) {
            tell(source, "Puits : " + Coin.say(ledger.withheld()) + " jamais versés depuis le"
                    + " premier jour. Quota " + (Tariff.quotaOn()
                    ? Coin.amount(Tariff.quotaAllowance()) + " par article" : "éteint")
                    + ", débit " + (Tariff.debitOn()
                    ? Coin.amount(Tariff.debitAllowance()) + " en tout" : "éteint") + ".");
        }
        return 1;
    }

    private static int pay(CommandSourceStack source, ServerPlayer target, String raw)
            throws CommandSyntaxException {
        if (offline(source)) {
            return 0;
        }
        if (!Tariff.pay()) {
            tell(source, "Les virements entre joueurs sont désactivés sur ce serveur.");
            return 0;
        }
        ServerPlayer from = source.getPlayerOrException();
        long amount = Coin.parse(raw);
        if (amount == Coin.BAD || amount <= 0L) {
            tell(source, "« " + raw + " » n'est pas un montant. Exemple : 12.50");
            return 0;
        }
        Ledger ledger = Ledger.of(source.getServer());
        long fee = Toll.fee(amount);
        String refusal = ledger.transfer(from.getUUID(), from.getGameProfile().name(),
                target.getUUID(), target.getGameProfile().name(), amount, fee);
        if (refusal != null) {
            tell(source, refusal);
            return 0;
        }
        String cost = fee > 0L ? " (" + Coin.say(fee) + " de frais)" : "";
        tell(source, "Viré " + Coin.say(amount - fee) + " à " + target.getGameProfile().name()
                + cost + ". Il te reste " + Coin.say(ledger.balance(from.getUUID())) + ".");
        target.sendSystemMessage(Component.literal(from.getGameProfile().name() + " t'a viré "
                + Coin.say(amount - fee) + ".").withStyle(ChatFormatting.GREEN));
        sync(from, false);
        sync(target, false);
        return 1;
    }

    private static int move(CommandSourceStack source, ServerPlayer target, String raw, Move how) {
        if (offline(source)) {
            return 0;
        }
        long amount = Coin.parse(raw);
        if (amount == Coin.BAD || amount < 0L) {
            tell(source, "« " + raw + " » n'est pas un montant. Exemple : 12.50");
            return 0;
        }
        Ledger ledger = Ledger.of(source.getServer());
        String name = target.getGameProfile().name();
        switch (how) {
            case GIVE -> ledger.credit(target.getUUID(), name, amount);
            case TAKE -> ledger.set(target.getUUID(), name,
                    Math.max(0L, ledger.balance(target.getUUID()) - amount));
            case SET -> ledger.set(target.getUUID(), name, amount);
        }
        tell(source, name + " : " + Coin.say(ledger.balance(target.getUUID())) + ".");
        sync(target, false);
        return 1;
    }

    // --- /boutique ---------------------------------------------------------

    private static int openShop(CommandSourceStack source) throws CommandSyntaxException {
        if (offline(source)) {
            return 0;
        }
        sync(source.getPlayerOrException(), true);
        return 1;
    }

    private static int quotes(CommandSourceStack source, Identifier only) {
        if (offline(source)) {
            return 0;
        }
        Ledger ledger = Ledger.of(source.getServer());
        if (only != null) {
            Offer offer = Stall.find(only);
            if (offer == null) {
                tell(source, only + " n'est pas au catalogue.");
                return 0;
            }
            say(source, offer, ledger.volume(only));
            return 1;
        }
        int shown = 0;
        for (Offer offer : Stall.all()) {
            long volume = ledger.volume(offer.item());
            if (volume == 0L) {
                continue;
            }
            say(source, offer, volume);
            shown++;
        }
        if (shown == 0) {
            tell(source, Stall.size() + " article(s) au catalogue, tous à leur prix d'ancrage.");
        }
        return shown;
    }

    private static void say(CommandSourceStack source, Offer offer, long volume) {
        int trend = Drift.trend(volume);
        String arrow = trend > 0 ? "▲" : trend < 0 ? "▼" : "=";
        tell(source, "  " + offer.item() + "   achat " + Coin.say(Drift.buy(offer, volume))
                + "   rachat " + (offer.bought() ? Coin.say(Drift.sell(offer, volume)) : "aucun")
                + "   " + arrow + " " + (trend / 10.0) + " %   (ancre " + Coin.amount(offer.buy())
                + " / " + Coin.amount(offer.sell()) + ", volume net " + volume + ")");
    }

    private static int shelve(CommandSourceStack source, Identifier id, String rawBuy,
            String rawSell, String aisle) {
        long buy = Coin.parse(rawBuy);
        long sell = Coin.parse(rawSell);
        if (buy == Coin.BAD) {
            tell(source, "« " + rawBuy + " » n'est pas un montant.");
            return 0;
        }
        if (sell == Coin.BAD) {
            tell(source, "« " + rawSell + " » n'est pas un montant.");
            return 0;
        }
        Offer previous = Stall.find(id);
        String kept = aisle != null ? aisle : previous != null ? previous.category() : Offer.LOOSE;
        String refusal = Stall.put(id, buy, sell, kept);
        if (refusal != null) {
            tell(source, refusal);
            return 0;
        }
        tell(source, id + " : achat " + Coin.say(buy) + ", rachat "
                + (sell > 0 ? Coin.say(sell) : "aucun") + ", rayon « " + Offer.cleanCategory(kept)
                + " ». Pense à « /boutique ecrire » pour que cela survive au redémarrage.");
        pushAll(source);
        return 1;
    }

    private static int unshelve(CommandSourceStack source, Identifier id) {
        if (!Stall.drop(id)) {
            tell(source, id + " n'était pas au catalogue.");
            return 0;
        }
        tell(source, id + " retiré du catalogue.");
        pushAll(source);
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        int count = Stall.reload();
        if (count < 0) {
            tell(source, "Fichier illisible. Le catalogue en place est conservé ; la raison est dans"
                    + " le journal du serveur.");
            return 0;
        }
        tell(source, count + " article(s) rechargés depuis " + Stall.file() + ".");
        pushAll(source);
        return count;
    }

    private static int inscribe(CommandSourceStack source) {
        if (!Stall.inscribe()) {
            tell(source, "Écriture impossible ; la raison est dans le journal du serveur.");
            return 0;
        }
        tell(source, Stall.size() + " article(s) écrits dans " + Stall.file() + ".");
        return 1;
    }

    /**
     * Refait le catalogue entier depuis les recettes du jeu.
     *
     * <p>Le message dit ce qui vient de l'écrit et ce qui vient du calcul, et nomme les objets qui
     * n'ont pas pu être tarifés. Sur un serveur moddé, cette dernière ligne est la seule chose que
     * l'administrateur ait à lire : c'est sa liste de courses.
     */
    private static int generate(CommandSourceStack source) {
        if (offline(source)) {
            return 0;
        }
        tell(source, "Génération en cours — le fichier va être écrasé…");
        Assay.Tally tally = Stall.generate(source.getServer());
        tell(source, tally.total() + " article(s) : " + tally.fromSeam()
                + " de la table de base, " + tally.fromRecipes() + " déduits de "
                + tally.steps() + " recette(s) en " + tally.passes() + " passe(s).");
        if (tally.refused() > 0) {
            tell(source, tally.refused() + " article(s) écartés — la raison est dans le journal.");
        }
        if (!tally.orphans().isEmpty()) {
            tell(source, tally.orphans().size() + " objet(s) sans prix : aucune recette ne les"
                    + " relie à une matière première connue. Le journal les nomme ; ajoute-les à la"
                    + " main dans " + Stall.file() + " si tu les veux.");
        }
        tell(source, "Écrit dans " + Stall.file() + ". Pense à « /boutique verifier ».");
        pushAll(source);
        return tally.total();
    }

    /**
     * Cherche les boucles d'arbitrage, et les nomme.
     *
     * <p>À lancer après toute retouche du fichier. Le test n'est pas fait sur les prix d'ancrage mais
     * sur les <b>extrêmes de la dérive</b> : c'est le seul cas qui compte, parce que deux articles
     * peuvent dériver en sens contraire. Voir {@link Assay#audit}.
     */
    private static int verify(CommandSourceStack source) {
        if (offline(source)) {
            return 0;
        }
        double safety = Tariff.safety();
        tell(source, String.format(java.util.Locale.ROOT,
                "Condition de sûreté : rachat × facteur × dérive = %.3f (doit rester sous 1,000).",
                safety));
        List<Assay.Flaw> flaws = Assay.audit(source.getServer());
        if (flaws.isEmpty()) {
            tell(source, "Aucune boucle. " + Stall.size() + " article(s) au catalogue, "
                    + Assay.recipeCount(source.getServer()) + " recette(s) examinées aux deux"
                    + " extrêmes de la dérive.");
            return 1;
        }
        tell(source, flaws.size() + " BOUCLE(S) D'ARBITRAGE — un joueur peut fabriquer de l'argent :");
        int shown = 0;
        for (Assay.Flaw flaw : flaws) {
            if (shown++ >= 15) {
                tell(source, "  … et " + (flaws.size() - 15) + " autre(s).");
                break;
            }
            tell(source, "  " + flaw.recipe() + " — " + (flaw.forward()
                    ? "acheter les entrées (" + Coin.say(flaw.cost()) + ") puis revendre "
                    + flaw.item() + " (" + Coin.say(flaw.gain()) + ")"
                    : "acheter " + flaw.item() + " (" + Coin.say(flaw.cost())
                    + ") puis revendre les entrées (" + Coin.say(flaw.gain()) + ")"));
        }
        tell(source, "Baisse generation.rachat_pourcent ou derive.amplitude_pourcent, ou corrige"
                + " ces articles à la main.");
        return -flaws.size();
    }

    private static int reset(CommandSourceStack source, Identifier only) {
        if (offline(source)) {
            return 0;
        }
        Ledger ledger = Ledger.of(source.getServer());
        if (only != null) {
            ledger.forget(only);
            tell(source, only + " est revenu à son prix d'ancrage.");
            pushAll(source);
            return 1;
        }
        int count = ledger.forgetAll();
        tell(source, count + " article(s) revenus à leur prix d'ancrage.");
        pushAll(source);
        return count;
    }

    // --- Menue monnaie -----------------------------------------------------

    private static void pushAll(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server != null) {
            syncAll(server);
        }
    }

    /** Vrai — et le dit — si la boutique est éteinte ou si le serveur n'est pas là. */
    private static boolean offline(CommandSourceStack source) {
        if (source.getServer() == null) {
            return true;
        }
        if (!Tariff.active()) {
            tell(source, "L'économie est désactivée sur ce serveur"
                    + " (config/lanterne-boutique.toml, economie.active).");
            return true;
        }
        return false;
    }

    private static void tell(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
    }
}
