package fr.clubcitrouille.lanterne.content.waypoint;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Les portails de repère : ce qui les allume, ce qui les relie, et ce qui les efface.
 *
 * <h2>Le lien est unilatéral, et la réciprocité est un cadeau</h2>
 *
 * <p>Deux règles étaient possibles, et aucune des deux n'allait seule.
 *
 * <p>Un lien <b>forcément réciproque</b> — relier A vers B relie B vers A — interdit les moyeux : cinq
 * portails de ferme qui reviennent tous à la base sont impossibles, puisque la base ne peut mener qu'à
 * un seul d'entre eux. Pire, il permet de <b>voler</b> la destination du portail d'autrui en reliant le
 * sien dessus.
 *
 * <p>Un lien <b>strictement unilatéral</b> laisse, lui, des culs-de-sac : on franchit un portail public,
 * on arrive chez quelqu'un qui n'a rien configuré, et il faut rentrer à pied — ce qui est exactement ce
 * que le système prétendait épargner.
 *
 * <p>La règle retenue est donc : <b>le lien va dans un sens ; et si la destination ne menait nulle part,
 * elle reçoit le retour</b>. Personne ne se fait prendre son lien, puisqu'on ne touche qu'à un portail
 * qui n'en avait pas ; les moyeux restent possibles, puisqu'un portail déjà relié n'est jamais modifié ;
 * et le cas ordinaire — deux portails neufs qu'on relie — donne une porte à deux sens, sans que personne
 * ait eu à y penser. C'est la boucle propre que le joueur demandait.
 *
 * <h2>Public par défaut, privé sur décision</h2>
 *
 * <p>Les repères naissent privés : un repère est une note personnelle, et en publier cinq par joueur
 * noierait la liste commune. Un portail naît <b>public</b>, parce qu'un portail est une infrastructure :
 * il a coûté dix blocs d'obsidienne et un cœur, il occupe un lieu, et l'intérêt d'un réseau croît avec
 * le nombre de nœuds qu'on peut atteindre. Celui qui veut une porte dérobée la rend privée d'un clic ;
 * l'inverse — un réseau que personne ne pense à ouvrir — ne se répare pas tout seul.
 *
 * <p>Un portail privé n'est ni listé, ni relié, ni traversé par un autre que son propriétaire.
 *
 * <h2>Allumer demande un joueur, et c'est pour cela qu'un feu ne suffit pas</h2>
 *
 * <p>Un portail sans propriétaire ne se renomme pas, ne se relie pas, ne se retire pas : c'est un
 * meuble. Un feu allumé par de la lave, un distributeur ou un éclair n'a personne derrière lui — on
 * refuse donc simplement que le cadre devienne un portail du Nether ({@link #onPortalSpawn}) et l'on
 * n'allume rien. Le geste du joueur, briquet en main, est intercepté plus tôt
 * ({@link #onRightClick}) et c'est lui qui crée la fiche.
 */
@EventBusSubscriber(modid = Lanterne.ID)
public final class Gates {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Lanterne.ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Lanterne.ID);

    /**
     * Le Cœur de repère.
     *
     * <p>Aussi dur que l'obsidienne — on ne l'ôte pas d'un cadre par mégarde — et indestructible par une
     * explosion : un portail qui disparaîtrait parce qu'un creeper est passé emporterait avec lui toutes
     * les destinations que d'autres joueurs ont inscrites chez eux. Sa lueur faible dit de loin qu'un
     * cadre n'est pas ordinaire.
     */
    public static final DeferredBlock<Heart> HEART = BLOCKS.registerBlock("coeur_de_repere",
            Heart::new, () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .strength(50.0F, 1200.0F)
                    .sound(SoundType.AMETHYST)
                    .lightLevel(state -> 7)
                    .requiresCorrectToolForDrops());

    public static final DeferredItem<net.minecraft.world.item.BlockItem> HEART_ITEM =
            ITEMS.registerSimpleBlockItem("coeur_de_repere", HEART);

    /**
     * La nappe du portail.
     *
     * <p>Celles du portail du Nether, à deux différences près.
     *
     * <p>Douze de lumière au lieu de onze : assez pour qu'une salle de portails se passe de torches,
     * pas assez pour que le portail serve d'éclairage.
     *
     * <p>Et <b>pas de tick aléatoire</b>. Celui du Nether s'en sert pour faire apparaître des piglins
     * zombifiés ; celui-ci n'a rien à y faire, et dans un mod dont le propos est de retirer du travail
     * que personne ne réclame, laisser une propriété qui fait ticker des centaines de blocs pour
     * exécuter une méthode vide serait une contradiction.
     *
     * <p>Incassable et sans butin : elle n'existe qu'allumée, et {@link GateBlock#getCloneItemStack}
     * refuse même de la donner en créatif.
     */
    public static final DeferredBlock<GateBlock> GATE = BLOCKS.registerBlock("portail_repere",
            GateBlock::new, () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .noCollision()
                    .noLootTable()
                    .strength(-1.0F)
                    .sound(SoundType.GLASS)
                    .lightLevel(state -> 12)
                    .pushReaction(PushReaction.IMMOVEABLE));

    /**
     * L'ouverture de l'écran, posée par le client au démarrage.
     *
     * <p>Nulle part ailleurs qu'ici : sur un serveur dédié ce champ reste ce qu'il est — une lambda qui
     * ne fait rien — et la classe de l'écran n'est jamais chargée. C'est la façon la plus sûre qu'un
     * bloc commun ouvre une fenêtre sans qu'un serveur n'ait à connaître le moindre morceau de client.
     */
    public static Consumer<BlockPos> OPENER = pos -> {};

    private Gates() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(Gates::onBuildTab);
        // S'enregistre toujours, même si aucune carte (BlueMap ou autre) n'est présente — voir
        // compat.carte.CarteMarkers, qui ne référence lui-même aucun type de carte particulier.
        fr.clubcitrouille.lanterne.compat.carte.CarteMarkers.register(GatesMarkerSource.INSTANCE);
    }

    /** Le cœur rejoint l'onglet du mod, à côté du carnet dont il est la suite. */
    private static void onBuildTab(net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) {
        if (event.getTab() == fr.clubcitrouille.lanterne.content.Contents.TAB.get()) {
            event.accept(HEART_ITEM.get());
        }
    }

    // ------------------------------------------------------------------------------------------
    // L'allumage
    // ------------------------------------------------------------------------------------------

    /**
     * Le briquet dans un cadre : on allume nous-mêmes plutôt que de laisser le feu le faire.
     *
     * <p>L'évènement passe <b>avant</b> que le feu ne soit posé, et il porte le joueur — les deux
     * raisons pour lesquelles c'est ici qu'on s'accroche et non sur la création du portail. Poser le feu
     * d'abord aurait marché aussi, mais il aurait fallu deviner à qui il appartenait, et il n'y a pas de
     * bonne façon de deviner cela.
     *
     * <p>Le refus est prononcé des <b>deux côtés</b>. L'évènement est tiré sur le client comme sur le
     * serveur ; ne l'annuler que sur le serveur laisserait le client poser sa flamme par anticipation,
     * puis la voir disparaître au premier accusé de réception. Le client connaît le cadre aussi bien que
     * le serveur — il a les mêmes blocs — donc il peut refuser tout seul. Ce qu'il ne fait pas, c'est
     * créer la fiche : cela reste le privilège du serveur.
     */
    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getFace() == null) {
            return;
        }
        var stack = event.getItemStack();
        if (!stack.is(Items.FLINT_AND_STEEL) && !stack.is(Items.FIRE_CHARGE)) {
            return;
        }
        Level where = event.getLevel();
        BlockPos spark = event.getPos().relative(event.getFace());
        if (!where.getBlockState(spark).isAir()) {
            return;
        }
        // Le même axe préféré que BaseFireBlock : celui du cadre qu'on a en face de soi.
        Direction.Axis preferred = event.getEntity().getDirection().getCounterClockWise().getAxis();
        Frame.Reading reading = Frame.fromInside(where, spark, preferred);
        if (!reading.ok()) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        if (!(where instanceof ServerLevel level)
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (kindle(level, reading.frame(), player)) {
            level.playSound(null, spark, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS,
                    1.0F, level.getRandom().nextFloat() * 0.4F + 0.8F);
            if (!player.hasInfiniteMaterials()) {
                if (stack.is(Items.FIRE_CHARGE)) {
                    stack.shrink(1);
                } else {
                    stack.hurtAndBreak(1, player, event.getHand().asEquipmentSlot());
                }
            }
        }
    }

    /**
     * Un cadre qui porte un cœur ne devient jamais un portail du Nether.
     *
     * <p>Sans cela, un feu venu d'ailleurs — lave, distributeur, éclair — transformerait le cadre en
     * portail du Nether avec un cœur coincé dedans : le joueur aurait payé un cœur pour rien, et il ne
     * verrait pas pourquoi. On refuse, et le cadre reste ce qu'il est, prêt à être allumé à la main.
     */
    @SubscribeEvent
    public static void onPortalSpawn(BlockEvent.PortalSpawnEvent event) {
        if (!(event.getLevel() instanceof Level level)) {
            return;
        }
        Frame.Reading reading = Frame.fromInside(level, event.getPos(), Direction.Axis.X);
        if (reading.ok() || reading.refusal() == Frame.Refusal.TWO_HEARTS) {
            event.setCanceled(true);
        }
    }

    /** Allume depuis un cœur qu'on vient de cliquer : il faut d'abord retrouver son cadre. */
    public static boolean kindleAt(ServerLevel level, BlockPos heart, ServerPlayer who) {
        Frame.Reading reading = Frame.aroundHeart(level, heart);
        if (!reading.ok()) {
            Waypoints.tell(who, reading.refusal().said);
            return false;
        }
        return kindle(level, reading.frame(), who);
    }

    /**
     * Remplit un cadre, en lui rendant son identité s'il en avait déjà une.
     *
     * @return vrai si le portail brûle à la fin, et donc si le briquet doit s'user
     */
    public static boolean kindle(ServerLevel level, Frame frame, ServerPlayer who) {
        if (!Settings.waypoints()) {
            Waypoints.tell(who, "Les repères sont désactivés sur ce serveur.");
            return false;
        }
        MinecraftServer server = level.getServer();
        Atlas atlas = Atlas.of(server);
        Identifier dimension = level.dimension().identifier();
        Gate known = atlas.gateAtHeart(dimension, frame.heart());

        Gate gate;
        if (known == null) {
            gate = new Gate(UUID.randomUUID(), who.getUUID(), who.getGameProfile().name(),
                    autoName(frame), dimension, frame.heart(), frame.axis(),
                    frame.min(), frame.max(), autoColour(who.getUUID()), true, true,
                    Optional.empty());
            String refusal = atlas.addGate(gate);
            if (refusal != null) {
                Waypoints.tell(who, refusal);
                return false;
            }
            Waypoints.tell(who, "Portail « " + gate.name() + " » allumé. Clic droit sur le cœur "
                    + "pour le nommer et choisir où il mène.");
        } else {
            if (known.lit() && level.getBlockState(known.min()).is(GATE.get())) {
                // Dire pourquoi rien ne se passe : sans cela, un joueur qui garde son briquet en main
                // clique sur le cœur, ne voit ni flamme ni écran, et conclut à une panne.
                Waypoints.tell(who, "« " + known.name() + " » brûle déjà. Clic droit à main nue "
                        + "sur le cœur pour le régler.");
                return false;
            }
            // Le cadre a pu changer de taille entre deux allumages. Balayer l'ANCIENNE forme avant
            // d'inscrire la nouvelle évite d'abandonner une nappe orpheline que plus aucune fiche ne
            // revendiquerait — et qu'on ne pourrait donc plus éteindre.
            sweep(level, known);
            gate = known.withShape(frame.axis(), frame.min(), frame.max());
            atlas.replaceGate(gate);
            Waypoints.tell(who, "Portail « " + gate.name() + " » rallumé.");
        }

        BlockState nappe = GATE.get().defaultBlockState()
                .setValue(GateBlock.AXIS, frame.axis());
        // Le drapeau 18 est celui de vanilla : prévenir les clients (2) sans déclencher de mise à jour
        // de voisinage (16). Sans le 16, le premier bloc posé demanderait au cadre s'il est complet
        // alors qu'il ne l'est pas encore, et se retirerait aussitôt.
        for (BlockPos cell : frame.inside()) {
            level.setBlock(cell, nappe, 18);
        }
        atlas.syncAll(server);
        return true;
    }

    /** Un nom qu'on peut lire avant d'en avoir choisi un : l'endroit, puisque c'est tout ce qu'on sait. */
    private static String autoName(Frame frame) {
        return Waypoints.clean(String.format(Locale.ROOT, "Portail %d %d",
                frame.heart().getX(), frame.heart().getZ()));
    }

    /**
     * Une teinte tirée de l'identifiant du joueur.
     *
     * <p>Deux joueurs différents obtiennent presque toujours deux couleurs différentes, et tous les
     * portails d'un même joueur la même : sans rien régler, une salle de portails communs se lit déjà
     * comme un plan du serveur. Celui qui veut autre chose la change d'un clic.
     */
    private static int autoColour(UUID who) {
        return Waypoint.PALETTE[Math.floorMod(who.hashCode(), Waypoint.PALETTE.length)];
    }

    // ------------------------------------------------------------------------------------------
    // L'extinction et l'effacement
    // ------------------------------------------------------------------------------------------

    /** Un bloc de nappe vient de partir : on retire les autres et la fiche passe à « éteint ». */
    public static void douse(ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        Atlas atlas = Atlas.of(server);
        Gate gate = atlas.gateAtBlock(level.dimension().identifier(), pos);
        if (gate == null) {
            return;
        }
        atlas.replaceGate(gate.withLit(false));
        sweep(level, gate);
        atlas.syncAll(server);
    }

    /** Le cœur vient d'être cassé : la fiche disparaît, et les liens qui la visaient avec elle. */
    public static void forget(ServerLevel level, BlockPos heart) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }
        Atlas atlas = Atlas.of(server);
        Gate gate = atlas.gateAtHeart(level.dimension().identifier(), heart);
        if (gate == null) {
            return;
        }
        // L'ordre compte : on éteint la fiche AVANT de retirer les blocs, faute de quoi chaque bloc
        // retiré rappellerait « douse » qui la retrouverait encore allumée.
        atlas.replaceGate(gate.withLit(false));
        sweep(level, gate);
        atlas.removeGate(gate.id());
        atlas.syncAll(server);
    }

    private static void sweep(ServerLevel level, Gate gate) {
        for (BlockPos cell : BlockPos.betweenClosed(gate.min(), gate.max())) {
            if (level.getBlockState(cell).is(GATE.get())) {
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), 18);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // La traversée
    // ------------------------------------------------------------------------------------------

    /**
     * Où mène ce bloc de portail, pour cette entité.
     *
     * <p>Chaque refus est expliqué à voix haute. Un portail qui ne fait rien et ne dit rien est
     * indiscernable d'un mod cassé, et c'est le genre de doute qui fait désinstaller.
     *
     * <p><b>Distance et chunks déchargés.</b> Rien ici ne dépend de la distance : la fiche du portail
     * de destination vit dans l'{@link Atlas} (données persistées, toujours en mémoire), et la seule
     * lecture du monde — {@code beyond.getBlockState(there.min())} — passe par
     * {@code Level.getBlockState} puis {@code getChunk(x, z)}, qui CHARGE le chunk s'il ne l'est pas
     * (lu dans le source patché de 26.3), y compris dans une autre dimension. La transition pose
     * ensuite un ticket de portail ({@code PLACE_PORTAL_TICKET}) comme un portail vanilla. Un portail
     * lointain ou dans un chunk déchargé se traverse donc normalement.
     *
     * <p>La dernière vérification se fait dans le monde, pas dans la fiche : si le cadre de destination
     * a été détruit pendant que son chunk dormait, la fiche passe à « éteint » et le voyageur est
     * prévenu, plutôt que déposé dans la pierre.
     */
    static TeleportTransition destination(ServerLevel level, Entity entity, BlockPos entry) {
        MinecraftServer server = level.getServer();
        if (server == null || !Settings.waypoints()) {
            return null;
        }
        Atlas atlas = Atlas.of(server);
        Gate here = atlas.gateAtBlock(level.dimension().identifier(), entry);
        if (here == null) {
            return null;
        }
        if (here.target().isEmpty()) {
            refuse(entity, here, "« " + here.name() + " » ne mène nulle part. Clic droit sur son "
                    + "cœur pour choisir une destination.");
            return null;
        }
        Gate there = atlas.gate(here.target().get());
        if (there == null) {
            refuse(entity, here, "La destination de « " + here.name() + " » n'existe plus. Clic "
                    + "droit sur son cœur pour en choisir une autre.");
            return null;
        }
        ServerLevel beyond = server.getLevel(
                ResourceKey.create(Registries.DIMENSION, there.dimension()));
        if (beyond == null) {
            refuse(entity, here, "Le monde de « " + there.name() + " » n'est pas chargé sur ce serveur.");
            return null;
        }
        if (!there.lit() || !beyond.getBlockState(there.min()).is(GATE.get())) {
            if (there.lit()) {
                atlas.replaceGate(there.withLit(false));
                atlas.syncAll(server);
                Lanterne.LOG.info("[PORTAIL] « {} » trouvé détruit à la traversée : marqué éteint.",
                        there.name());
            }
            refuse(entity, here, "« " + there.name() + " » est éteint. Rallume-le avant de le rejoindre.");
            return null;
        }
        Vec3 spot = PortalShape.findCollisionFreePosition(there.landing(), beyond, entity,
                entity.getDimensions(entity.getPose()));
        // Quart de tour si les deux cadres ne sont pas dans le même plan, pour qu'on sorte face au
        // portail d'arrivée plutôt que de biais.
        int turn = here.axis() == there.axis() ? 0 : 90;
        Lanterne.LOG.info("[PORTAIL] traversée : {} de « {} » ({}) vers « {} » ({} {} {} {}).",
                entity.getName().getString(), here.name(), here.dimension(), there.name(),
                there.dimension(), there.heart().getX(), there.heart().getY(), there.heart().getZ());
        return new TeleportTransition(beyond, spot, Vec3.ZERO, turn, 0.0F,
                Relative.union(Relative.DELTA, Relative.ROTATION),
                TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET));
    }

    /** Dernier refus journalisé, par portail : une entité immobile dans la nappe redemande sans cesse. */
    private static final java.util.Map<UUID, Long> LAST_REFUSAL =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void refuse(Entity entity, Gate here, String why) {
        say(entity, why);
        long now = System.currentTimeMillis();
        Long last = LAST_REFUSAL.get(here.id());
        if (last == null || now - last > 10_000L) {
            LAST_REFUSAL.put(here.id(), now);
            Lanterne.LOG.info("[PORTAIL] traversée refusée à « {} » : {}", here.name(), why);
        }
    }

    /**
     * Qui voyage, quand ce n'est pas un joueur qui entre.
     *
     * <p>Un bateau, un chariot ou un cheval franchit le portail à la place de son passager :
     * {@code canUsePortal} refuse de téléporter un passager tout seul, et c'est la monture qui porte la
     * demande. Sans cette fonction, un joueur sur sa monture serait traité comme un inconnu devant son
     * propre portail privé.
     */
    private static UUID travellerOf(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            return player.getUUID();
        }
        for (Entity passenger : entity.getPassengers()) {
            UUID found = travellerOf(passenger);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void say(Entity entity, String what) {
        if (entity instanceof ServerPlayer player) {
            player.sendOverlayMessage(Component.literal(what).withStyle(ChatFormatting.GRAY));
            return;
        }
        for (Entity passenger : entity.getPassengers()) {
            say(passenger, what);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Ce que l'écran demande
    // ------------------------------------------------------------------------------------------

    /**
     * Traite une demande de l'écran, sur le fil du serveur.
     *
     * <p>Le demandeur n'est jamais celui que le paquet annonce : il est lu dans la connexion. Un client
     * modifié peut demander n'importe quoi, il n'obtiendra que ce que {@link #mayManage} lui accorde.
     */
    public static void onAsk(GateAsk ask, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        // enqueueWork n'est pas une précaution de style : le gestionnaire est appelé sur le fil réseau,
        // et toucher aux données de monde depuis là serait une course pure et simple.
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                apply(ask, player, true);
            }
        });
    }

    /**
     * Qui peut régler un portail : celui qui l'a posé, et les opérateurs (niveau « maître du jeu »,
     * même permission que la peinture — voir {@code Canvas}). Tout le monde peut le traverser et le
     * choisir comme destination : les portails sont publics.
     */
    public static boolean mayManage(ServerPlayer player, Gate gate) {
        return gate.ownedBy(player.getUUID()) || player.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    /**
     * Répond au joueur. Depuis l'écran, la réponse s'affiche DANS l'écran ({@link GateReply}) : le chat
     * est invisible tant qu'un écran est ouvert, et c'est exactement ce qui rendait un lien refusé
     * indiscernable d'un clic perdu. Depuis une commande, elle part dans le chat, où l'on regarde.
     */
    private static void answer(ServerPlayer player, boolean fromScreen, boolean ok, String text) {
        Lanterne.LOG.info("[PORTAIL] réponse à {} ({}) : {}", player.getGameProfile().name(),
                ok ? "ok" : "refus", text);
        if (fromScreen) {
            try {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new GateReply(ok, text.length() > GateReply.TEXT_LIMIT
                                ? text.substring(0, GateReply.TEXT_LIMIT) : text));
                return;
            } catch (RuntimeException clientWithoutChannel) {
                // Client d'avant ce paquet (canal facultatif) : on retombe sur le chat.
            }
        }
        Waypoints.tell(player, text);
    }

    /**
     * Le traitement proprement dit, sur le fil du serveur.
     *
     * <p>L'écran et les commandes entrent par ici, et par ici seulement : toute règle ajoutée vaut
     * aussitôt pour les deux. Chaque issue — réussite comme refus — produit une réponse et une ligne
     * {@code [PORTAIL]} dans le journal : plus jamais un clic qui ne dit rien.
     */
    private static void apply(GateAsk ask, ServerPlayer player, boolean fromScreen) {
        Lanterne.LOG.info("[PORTAIL] demande reçue de {} : {} sur {}{}", player.getGameProfile().name(),
                ask.verb(), ask.gate(), ask.target().map(id -> " vers " + id).orElse(""));
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        if (!Settings.waypoints()) {
            answer(player, fromScreen, false, "Les portails sont désactivés sur ce serveur.");
            return;
        }
        Atlas atlas = Atlas.of(server);
        Gate mine = atlas.gate(ask.gate());
        if (mine == null) {
            answer(player, fromScreen, false, "Ce portail n'existe plus.");
            atlas.syncAll(server);
            return;
        }
        if (!mayManage(player, mine)) {
            answer(player, fromScreen, false, "Ce portail appartient à " + mine.ownerName()
                    + " : tu peux le traverser, pas le régler.");
            return;
        }
        switch (ask.verb()) {
            case LINK -> {
                String refusal = link(atlas, player, fromScreen, mine, ask.target().orElse(null));
                if (refusal != null) {
                    answer(player, fromScreen, false, refusal);
                    atlas.syncAll(server);
                    return;
                }
            }
            case UNLINK -> {
                if (mine.target().isEmpty()) {
                    answer(player, fromScreen, false, "« " + mine.name() + " » ne menait déjà nulle part.");
                    atlas.syncAll(server);
                    return;
                }
                atlas.replaceGate(mine.withTarget(Optional.empty()));
                answer(player, fromScreen, true, "« " + mine.name() + " » ne mène plus nulle part.");
            }
            // Conservé dans le format du paquet pour les clients plus anciens : les portails sont
            // désormais toujours publics, il n'y a plus rien à basculer.
            case SHARE -> {
                answer(player, fromScreen, true, "Les portails sont toujours publics.");
                atlas.syncAll(server);
                return;
            }
            case RENAME -> {
                String name = Waypoints.clean(ask.name());
                if (name.isEmpty()) {
                    answer(player, fromScreen, false, "Un portail a besoin d'un nom.");
                    return;
                }
                atlas.replaceGate(mine.withName(name));
                answer(player, fromScreen, true, "Portail renommé « " + name + " ».");
            }
            case TINT -> {
                atlas.replaceGate(mine.withColour(ask.colour() & 0xFFFFFF));
                answer(player, fromScreen, true, "Teinte changée.");
            }
            case FORGET -> {
                ServerLevel where = server.getLevel(
                        ResourceKey.create(Registries.DIMENSION, mine.dimension()));
                if (where != null) {
                    atlas.replaceGate(mine.withLit(false));
                    sweep(where, mine);
                }
                atlas.removeGate(mine.id());
                answer(player, fromScreen, true, "Portail « " + mine.name() + " » oublié.");
            }
        }
        atlas.syncAll(server);
    }

    /**
     * Pose le lien, et offre le retour si la destination n'en avait pas. Voir l'en-tête du fichier.
     *
     * <p>Ne lit QUE l'{@link Atlas} : aucune lecture du monde, donc ni la distance, ni un chunk
     * déchargé, ni une autre dimension ne peuvent empêcher un lien. Le monde n'est consulté qu'à la
     * traversée (voir {@link #destination}).
     */
    private static String link(Atlas atlas, ServerPlayer player, boolean fromScreen, Gate mine,
                               UUID targetId) {
        if (targetId == null) {
            return "Aucune destination choisie.";
        }
        if (targetId.equals(mine.id())) {
            return "Un portail ne mène pas à lui-même.";
        }
        Gate there = atlas.gate(targetId);
        if (there == null) {
            return "Cette destination n'existe plus.";
        }
        if (mine.target().isPresent() && mine.target().get().equals(there.id())) {
            return "« " + mine.name() + " » mène déjà à « " + there.name() + " ».";
        }
        atlas.replaceGate(mine.withTarget(Optional.of(there.id())));
        String said = "« " + mine.name() + " » mène maintenant à « " + there.name() + " ».";
        if (there.target().isEmpty()) {
            atlas.replaceGate(there.withTarget(Optional.of(mine.id())));
            said += " Retour installé : « " + there.name() + " » ramène ici.";
        } else if (!there.target().get().equals(mine.id())) {
            said += " (« " + there.name() + " » mène ailleurs : pas de retour automatique.)";
        }
        if (!there.lit()) {
            said += " Attention : « " + there.name() + " » est éteint, il faudra le rallumer.";
        }
        answer(player, fromScreen, true, said);
        return null;
    }

    /**
     * {@code /lanterne portail …}, greffé sur la racine existante.
     *
     * <p>Brigadier fusionne les enfants d'un littéral déjà enregistré : déclarer une seconde fois
     * {@code lanterne} ajoute la branche sans toucher aux autres. C'est ce qui permet à ce chantier de
     * vivre entièrement dans son paquet, sans modifier le fichier de commandes du mod.
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lanterne")
                .then(Commands.literal("portail")
                        .executes(context -> list(context.getSource()))
                        .then(Commands.literal("delier")
                                .then(Commands.argument("portail", StringArgumentType.greedyString())
                                        .suggests(Gates::mineSuggestions)
                                        .executes(context -> unlink(context.getSource(),
                                                StringArgumentType.getString(context, "portail")))))
                        .then(Commands.literal("oublier")
                                .then(Commands.argument("portail", StringArgumentType.greedyString())
                                        .suggests(Gates::mineSuggestions)
                                        .executes(context -> forget(context.getSource(),
                                                StringArgumentType.getString(context, "portail")))))
                        .then(Commands.literal("lier")
                                .then(Commands.argument("portail", StringArgumentType.string())
                                        .suggests(Gates::mineSuggestions)
                                        .then(Commands.argument("destination",
                                                        StringArgumentType.greedyString())
                                                .suggests(Gates::visibleSuggestions)
                                                .executes(context -> tie(context.getSource(),
                                                        StringArgumentType.getString(context, "portail"),
                                                        StringArgumentType.getString(context,
                                                                "destination"))))))));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            mineSuggestions(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player != null) {
            for (Gate gate : Atlas.of(context.getSource().getServer())
                    .gatesOwnedBy(player.getUUID())) {
                builder.suggest(gate.name());
            }
        }
        return builder.buildFuture();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
            visibleSuggestions(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                               com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player != null) {
            for (Gate gate : Atlas.of(context.getSource().getServer())
                    .gatesVisibleTo(player.getUUID())) {
                builder.suggest(gate.name());
            }
        }
        return builder.buildFuture();
    }

    private static int list(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Atlas atlas = Atlas.of(source.getServer());
        var seen = atlas.gatesVisibleTo(player.getUUID());
        if (seen.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "Aucun portail. Bâtis un cadre d'obsidienne, remplace un de ses blocs par un "
                            + "Cœur de repère, et allume-le.").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        for (Gate gate : seen) {
            String target = gate.target()
                    .map(id -> {
                        Gate other = atlas.gate(id);
                        return other == null ? " → (disparu)" : " → " + other.name();
                    })
                    .orElse(" → (nulle part)");
            String line = String.format(Locale.ROOT, "  %s  %d %d %d  (%s)%s%s%s",
                    gate.name(), gate.heart().getX(), gate.heart().getY(), gate.heart().getZ(),
                    gate.dimension().getPath(), target,
                    gate.lit() ? "" : "  · éteint",
                    gate.ownedBy(player.getUUID()) ? "  · à toi" : "  · de " + gate.ownerName());
            source.sendSuccess(() -> Component.literal(line)
                    .withStyle(style -> style.withColor(gate.colour())), false);
        }
        return seen.size();
    }

    private static int unlink(CommandSourceStack source, String name)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return act(source, name, GateAsk.Verb.UNLINK, null);
    }

    private static int forget(CommandSourceStack source, String name)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return act(source, name, GateAsk.Verb.FORGET, null);
    }

    private static int tie(CommandSourceStack source, String name, String target)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Gate destination = byName(Atlas.of(source.getServer())
                .gatesVisibleTo(player.getUUID()), target);
        if (destination == null) {
            Waypoints.tell(player, "Aucun portail nommé « " + target + " ».");
            return 0;
        }
        return act(source, name, GateAsk.Verb.LINK, destination.id());
    }

    private static int act(CommandSourceStack source, String name, GateAsk.Verb verb, UUID target)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Atlas atlas = Atlas.of(source.getServer());
        Gate gate = byName(atlas.gatesOwnedBy(player.getUUID()), name);
        if (gate == null) {
            // Un opérateur peut régler n'importe quel portail : on cherche alors parmi tous.
            Gate any = byName(atlas.gatesVisibleTo(player.getUUID()), name);
            if (any != null && mayManage(player, any)) {
                gate = any;
            }
        }
        if (gate == null) {
            Waypoints.tell(player, "Tu n'as pas de portail nommé « " + name + " ».");
            return 0;
        }
        apply(new GateAsk(verb, gate.id(), Optional.ofNullable(target), gate.name(), gate.colour()),
                player, false);
        return 1;
    }

    private static Gate byName(java.util.List<Gate> among, String name) {
        for (Gate gate : among) {
            if (gate.name().equalsIgnoreCase(name.trim())) {
                return gate;
            }
        }
        return null;
    }
}
