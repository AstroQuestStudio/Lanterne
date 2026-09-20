package fr.clubcitrouille.lanterne.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Census;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.TickBudget;
import net.minecraft.world.entity.EntityTypes;

/**
 * Le rapport, l'interrupteur, le banc et la charge — tout ce qui permet de juger le mod.
 *
 * <h2>La règle de la maison</h2>
 *
 * <p>Tout mod d'optimisation affirme accélérer le jeu, et presque aucun ne montre de quoi. Le joueur
 * en installe vingt, constate que « ça a l'air mieux », et serait bien en peine de dire lequel y est
 * pour quelque chose — ni si l'un d'eux, en réalité, coûte plus qu'il ne rapporte.
 *
 * <p>Celui-ci rend ses comptes, et il fournit de quoi le contredire. C'est délibéré : un mod qu'on
 * ne peut pas réfuter n'est pas un mod rapide, c'est une affirmation.
 */
public final class LanterneCommand {
    /** Au-delà, on refuse : le but est de charger le serveur, pas de le tuer. */
    private static final int MAX_HERD = 20_000;

    /** Le monde de test : une plaine plate, vierge, sans rapport avec la vraie base. */
    private static final ResourceKey<Level> MONDE_TEST =
            ResourceKey.create(Registries.DIMENSION,
                    Identifier.fromNamespaceAndPath(Lanterne.ID, "monde_test"));

    /**
     * Où revenir, par joueur — perdu au redémarrage, et c'est très bien ainsi.
     *
     * <p>Un aller-retour de confort n'a pas à survivre à un arrêt du serveur : au pire, un joueur
     * revenu par un autre moyen retape la commande et repart d'où il se trouve alors, ce qui reste
     * un résultat raisonnable pour un outil de test.
     */
    private static final java.util.Map<java.util.UUID, GlobalPos> RETURN_POINTS =
            new java.util.HashMap<>();

    private record GlobalPos(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch) {}

    private LanterneCommand() {}

    /**
     * Le relevé des recettes du mod réellement chargées par le serveur.
     *
     * <h2>La question qu'il fallait pouvoir poser</h2>
     *
     * <blockquote>« Sur JEI ça me montre pas de craft pour tes items et blocs ! »</blockquote>
     *
     * <p>Toutes les vérifications sur fichiers avaient répondu « tout est en ordre » : les recettes
     * sont dans le bon dossier, leurs ingrédients existent, leurs résultats sont enregistrés, et le
     * journal ne signale aucune erreur. Un défaut qui survit à cela ne se cherche plus dans les
     * fichiers : il se cherche <b>à l'exécution</b>.
     *
     * <p>Or entre « le serveur n'a pas chargé nos recettes » et « il les a chargées, et c'est
     * l'afficheur qui ne les voit pas », il y a deux corrections entièrement différentes — et rien,
     * dans le jeu, ne permettait de trancher. D'où cette commande : elle compte, elle nomme, et elle
     * ne raisonne pas.
     *
     * <p>Elle est ouverte à tous, sans droit particulier : un joueur qui se demande pourquoi un
     * objet n'a pas de recette doit pouvoir répondre lui-même, et le relevé ne révèle rien qu'un
     * fichier de mod ne dise déjà.
     */
    private static int recipes(CommandSourceStack source) {
        var manager = source.getServer().getRecipeManager();
        int total = 0;
        List<String> ours = new ArrayList<>();
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            total++;
            Identifier id = holder.id().identifier();
            if (Lanterne.ID.equals(id.getNamespace())) {
                ours.add(id.getPath());
            }
        }
        Collections.sort(ours);

        final int loaded = total;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Recettes chargées par le serveur : %d au total, dont %d à nous.",
                loaded, ours.size())).withStyle(ChatFormatting.GOLD), false);

        if (ours.isEmpty()) {
            // Le cas où la cause est chez nous, et où il ne sert à rien de chercher chez l'afficheur.
            source.sendSuccess(() -> Component.literal(
                    "Aucune. Le serveur n'a pas chargé nos recettes du tout : le défaut est chez "
                    + "nous, pas chez l'afficheur. Regarder le dossier data/lanterne/recipe et le "
                    + "journal du chargement des données.")
                    .withStyle(ChatFormatting.RED), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.join(", ", ours))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal(
                "Elles sont chargées. Si l'afficheur n'en montre aucune, c'est lui qui ne les voit "
                + "pas — pas elles qui manquent.").withStyle(ChatFormatting.GREEN), false);
        return ours.size();
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("lanterne")
                .executes(context -> {
                    report(context.getSource());
                    return 1;
                })
                // Le sommaire, en tete parce que c'est par la qu'on arrive quand on ne sait pas
                // par ou commencer. Voir Guide.
                .then(Commands.literal("help")
                        .executes(context -> Guide.show(context.getSource())))
                .then(Commands.literal("aide")
                        .executes(context -> Guide.show(context.getSource())))
                // Le relevé des recettes : la seule façon de trancher entre « elles ne sont pas
                // chargées » et « elles le sont, et c'est l'afficheur qui ne les voit pas ».
                .then(Commands.literal("recettes")
                        .executes(context -> recipes(context.getSource())))
                .then(Commands.literal("demo")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("charge",
                                com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    for (String choice : new String[] {"grange", "entrepot",
                                            "butin", "feuilles", "enclos"}) {
                                        builder.suggest(choice);
                                    }
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("combien",
                                        IntegerArgumentType.integer(1, 20000))
                                        .executes(context -> {
                                            String kind = com.mojang.brigadier.arguments
                                                    .StringArgumentType.getString(context, "charge");
                                            int count = IntegerArgumentType.getInteger(
                                                    context, "combien");
                                            return demo(context.getSource(), kind, count);
                                        }))
                                .executes(context -> {
                                    String kind = com.mojang.brigadier.arguments
                                            .StringArgumentType.getString(context, "charge");
                                    return demo(context.getSource(), kind, 800);
                                })))
                .then(Commands.literal("pregen")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("rayon", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 20000))
                                .executes(context -> {
                                    int radius = com.mojang.brigadier.arguments.IntegerArgumentType
                                            .getInteger(context, "rayon");
                                    fr.clubcitrouille.lanterne.lab.Pregen.begin(
                                            context.getSource().getLevel(), radius);
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Pré-génération lancée sur un rayon de " + radius
                                            + " chunks. Ce qui existe déjà est sauté ; l'interrompre "
                                            + "ne perd rien, la reprise est gratuite.")
                                            .withStyle(ChatFormatting.GOLD), true);
                                    // Distant Horizons apprend chaque chunk au moment où il est
                                    // ECRIT — son mixin sur « ChunkMap.save » se déclenche au retour
                                    // de « ChunkSerializer.write », et la pré-génération demande des
                                    // chunks en statut FULL, qui passent donc toutes ses
                                    // vérifications. La pré-génération le nourrit directement.
                                    //
                                    // Mais elle peut aussi le noyer : DH refuse déjà de générer en
                                    // même temps que Chunky, « since Chunky can generate chunks
                                    // faster than DH can process them » — et il détecte Chunky par
                                    // un « Class.forName » qui ne nous verra jamais. Le dire ici est
                                    // le seul moment où cela sert : après coup, les trous sont déjà
                                    // dans la base et il faut la refaire.
                                    if (Compagnons.present(Compagnons.HORIZONS)) {
                                        context.getSource().sendSuccess(() -> Component.literal(
                                                "Distant Horizons est là : il apprendra chaque chunk "
                                                + "au moment où il est écrit. S'il prend du retard, "
                                                + "l'horizon aura des trous — monte son nombre de "
                                                + "fils, ou pré-génère avant de jouer.")
                                                .withStyle(ChatFormatting.AQUA), false);
                                    }
                                    return 1;
                                })))
                .then(Commands.literal("nbt")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> {
                            Ledger.survey(context.getSource(), 15);
                            return 1;
                        })
                        .then(Commands.argument("lignes", IntegerArgumentType.integer(1, 200))
                                .executes(context -> {
                                    Ledger.survey(context.getSource(),
                                            IntegerArgumentType.getInteger(context, "lignes"));
                                    return 1;
                                })))
                .then(Commands.literal("wp")
                        .then(Commands.literal("add")
                                .then(Commands.argument("nom", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(context -> {
                                            var player = context.getSource().getPlayerOrException();
                                            var atlas = fr.clubcitrouille.lanterne.content.waypoint.Atlas
                                                    .of(context.getSource().getServer());
                                            String name = fr.clubcitrouille.lanterne.content.waypoint
                                                    .Waypoints.clean(com.mojang.brigadier.arguments
                                                    .StringArgumentType.getString(context, "nom"));
                                            var mark = new fr.clubcitrouille.lanterne.content.waypoint.Waypoint(
                                                    player.getUUID(), player.getGameProfile().name(), name,
                                                    player.level().dimension().identifier(),
                                                    player.blockPosition(),
                                                    fr.clubcitrouille.lanterne.content.waypoint.Waypoint.PALETTE[0],
                                                    false);
                                            String refusal = atlas.add(mark);
                                            fr.clubcitrouille.lanterne.content.waypoint.Waypoints.tell(
                                                    player, refusal != null ? refusal
                                                            : "Repère « " + name + " » posé.");
                                            atlas.syncAll(context.getSource().getServer());
                                            return 1;
                                        })))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("nom", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(context -> {
                                            var player = context.getSource().getPlayerOrException();
                                            var atlas = fr.clubcitrouille.lanterne.content.waypoint.Atlas
                                                    .of(context.getSource().getServer());
                                            String name = com.mojang.brigadier.arguments
                                                    .StringArgumentType.getString(context, "nom");
                                            boolean gone = atlas.remove(player.getUUID(), name);
                                            fr.clubcitrouille.lanterne.content.waypoint.Waypoints.tell(
                                                    player, gone ? "Repère « " + name + " » retiré."
                                                            : "Aucun repère de ce nom.");
                                            atlas.syncAll(context.getSource().getServer());
                                            return 1;
                                        })))
                        .then(Commands.literal("share")
                                .then(Commands.argument("nom", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(context -> {
                                            var player = context.getSource().getPlayerOrException();
                                            var atlas = fr.clubcitrouille.lanterne.content.waypoint.Atlas
                                                    .of(context.getSource().getServer());
                                            String name = com.mojang.brigadier.arguments
                                                    .StringArgumentType.getString(context, "nom");
                                            var mark = atlas.find(player.getUUID(), name);
                                            if (mark == null) {
                                                fr.clubcitrouille.lanterne.content.waypoint.Waypoints
                                                        .tell(player, "Aucun repère de ce nom.");
                                                return 0;
                                            }
                                            boolean wanted = !mark.shared();
                                            atlas.replace(player.getUUID(), name,
                                                    m -> m.withShared(wanted));
                                            fr.clubcitrouille.lanterne.content.waypoint.Waypoints.tell(
                                                    player, "Repère « " + name + (wanted
                                                            ? " » rendu public." : " » redevenu privé."));
                                            atlas.syncAll(context.getSource().getServer());
                                            return 1;
                                        })))
                        .executes(context -> {
                            var player = context.getSource().getPlayerOrException();
                            var atlas = fr.clubcitrouille.lanterne.content.waypoint.Atlas
                                    .of(context.getSource().getServer());
                            var seen = atlas.visibleTo(player.getUUID());
                            if (seen.isEmpty()) {
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "Aucun repère. « /lanterne wp add <nom> » pour en poser un.")
                                        .withStyle(ChatFormatting.GRAY), false);
                                return 0;
                            }
                            for (var mark : seen) {
                                context.getSource().sendSuccess(() -> Component.literal(String.format(
                                        Locale.ROOT, "  %s  %d %d %d  (%s)%s",
                                        mark.name(), mark.pos().getX(), mark.pos().getY(),
                                        mark.pos().getZ(), mark.dimension().getPath(),
                                        mark.shared() ? " · public de " + mark.ownerName() : ""))
                                        .withStyle(style -> style.withColor(mark.colour())), false);
                            }
                            return seen.size();
                        }))
                .then(Commands.literal("clear")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> {
                            var haul = fr.clubcitrouille.lanterne.core.Sweeper.run(
                                    context.getSource().getServer());
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Balai passé : " + haul.describe() + ".")
                                    .withStyle(ChatFormatting.GOLD), true);
                            return haul.total();
                        })
                        .then(Commands.literal("on")
                                .executes(context -> {
                                    Settings.setBroom(true);
                                    fr.clubcitrouille.lanterne.core.Sweeper.rearm();
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Nettoyage périodique allumé. Il ne survivra pas au "
                                            + "redémarrage — pour cela, le fichier de configuration.")
                                            .withStyle(ChatFormatting.GOLD), true);
                                    return 1;
                                }))
                        .then(Commands.literal("off")
                                .executes(context -> {
                                    Settings.setBroom(false);
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Nettoyage périodique éteint.")
                                            .withStyle(ChatFormatting.GOLD), true);
                                    return 1;
                                }))
                        .then(Commands.literal("status")
                                .executes(context -> {
                                    long left = fr.clubcitrouille.lanterne.core.Sweeper.remaining();
                                    context.getSource().sendSuccess(() -> Component.literal(left < 0L
                                            ? "Nettoyage périodique éteint."
                                            : String.format(Locale.ROOT,
                                                    "Prochain passage dans %d min %02d s. "
                                                    + "%d entité(s) effacée(s) depuis le démarrage.",
                                                    left / 1200L, (left % 1200L) / 20L,
                                                    fr.clubcitrouille.lanterne.core.Broom.sweptTotal()))
                                            .withStyle(ChatFormatting.GRAY), false);
                                    return 1;
                                })))
                // Le vol, à la demande — jamais par défaut. Le serveur vanilla expulse tout joueur
                // qu'il voit s'élever sans que son ancien.mayfly le permette ; un mod client de vol
                // ne change rien à cette autorisation, il ne fait que produire le mouvement que le
                // serveur refuse ensuite. « Flying is not enabled on this server » n'est donc pas un
                // bogue du mod de vol, c'est le serveur qui applique une règle que personne n'a
                // encore levée pour ce joueur. Cette commande la lève, sans passer par le créatif —
                // un joueur qui veut juste voler ne veut pas forcément un sac infini.
                .then(Commands.literal("vol")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> toggleFlight(context.getSource(),
                                context.getSource().getPlayerOrException()))
                        .then(Commands.argument("joueur", EntityArgument.player())
                                .executes(context -> toggleFlight(context.getSource(),
                                        EntityArgument.getPlayer(context, "joueur")))))
                // Le monde de test : une plaine plate et vide, pour essayer un chantier ou un bloc
                // neuf sans le moindre risque sur la vraie base. Bascule dans les deux sens.
                .then(Commands.literal("test")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> toggleTestWorld(context.getSource(),
                                context.getSource().getPlayerOrException())))
                // En lecture seule, et délibérément. Ce module élargit des seuils anti-triche :
                // pouvoir l'allumer depuis le tchat mettrait cette décision à portée d'un opérateur
                // pressé, alors qu'elle appartient au fichier de configuration — c'est-à-dire à
                // quelqu'un qui a lu pourquoi elle est éteinte par défaut.
                .then(Commands.literal("elastique")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Élastique — " + fr.clubcitrouille.lanterne.core.Elastique.describe())
                                    .withStyle(ChatFormatting.AQUA), false);
                            return 1;
                        }))
                // Le burin se lit, et ne se règle pas depuis le chat — même raison que l'élastique
                // juste au-dessus : ce qu'il ouvre est une marge de portée, donc une surface
                // d'attaque, et une surface d'attaque ne s'ouvre pas d'un mot lancé en jeu.
                .then(Commands.literal("burin")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Burin — " + fr.clubcitrouille.lanterne.core.Burin.describe())
                                    .withStyle(ChatFormatting.AQUA), false);
                            return 1;
                        }))
                // En lecture seule, meme raison que l'elastique et le burin juste au-dessus : le
                // reglage se change dans la configuration, pas d'un mot lance en jeu — mais savoir
                // s'il a deja fait quelque chose, ca, ca se demande.
                .then(Commands.literal("increvable")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(
                                    fr.clubcitrouille.lanterne.core.Increvable.report())
                                    .withStyle(ChatFormatting.AQUA), false);
                            return 1;
                        }))
                // Meme raison qu'increvable juste au-dessus, et meme surface d'attaque que
                // l'elastique/le burin : une capacite de construction ne s'ouvre pas d'un mot lance
                // en jeu, mais savoir si elle a deja pose ou casse quelque chose, ca se demande.
                .then(Commands.literal("rafale")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(
                                    fr.clubcitrouille.lanterne.core.Rafale.report())
                                    .withStyle(ChatFormatting.AQUA), false);
                            return 1;
                        }))
                .then(Commands.literal("maree")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Marée — " + fr.clubcitrouille.lanterne.core.Tide.describe(
                                            context.getSource().getServer()))
                                    .withStyle(ChatFormatting.AQUA), false);
                            // Le plan de coupe de Distant Horizons est calculé à chaque image à
                            // partir de « Options.getEffectiveRenderDistance() », qui vaut le
                            // minimum entre le réglage du joueur et la distance que CE serveur
                            // annonce. Une descente de la marée est donc comblée à l'image suivante,
                            // sans reconstruction — et en mode automatique le recouvrement augmente
                            // même quand la distance baisse (0,9 au-dessus de dix chunks, 0,3 à
                            // quatre), précisément pour cacher la bordure. Voir RenderUtil
                            // .getNearClipPlaneInBlocks dans les sources de DH.
                            if (Compagnons.present(Compagnons.HORIZONS)) {
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "Distant Horizons comble derrière elle, dès l'image "
                                        + "suivante — mais seulement là où tu es déjà passé.")
                                        .withStyle(ChatFormatting.DARK_AQUA), false);
                            }
                            return 1;
                        })
                        .then(Commands.literal("on")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(context -> {
                                    Settings.setTide(true);
                                    fr.clubcitrouille.lanterne.core.Tide.anchor(
                                            context.getSource().getServer());
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Marée allumée — les distances suivront la charge.")
                                            .withStyle(ChatFormatting.GREEN), true);
                                    return 1;
                                }))
                        .then(Commands.literal("off")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(context -> {
                                    Settings.setTide(false);
                                    context.getSource().sendSuccess(() -> Component.literal(
                                            "Marée éteinte — les distances restent où elles sont. "
                                            + "Elles ne remonteront pas d'elles-mêmes.")
                                            .withStyle(ChatFormatting.GOLD), true);
                                    return 1;
                                })))
                .then(Commands.literal("palette")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> {
                            Weave.survey(context.getSource());
                            return 1;
                        }))
                // L'inventaire déclenche un ramassage complet du tas — une pause bien réelle. Il
                // reste donc une commande d'administration, jamais un relevé périodique.
                .then(Commands.literal("memoire")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> {
                            CommandSourceStack source = context.getSource();
                            fr.clubcitrouille.lanterne.core.Inventaire.survey(source.getServer(),
                                    line -> source.sendSuccess(() -> Component.literal(line), false));
                            return 1;
                        }))
                .then(Commands.literal("pregen-stop")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> {
                            fr.clubcitrouille.lanterne.lab.Pregen.halt();
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Pré-génération interrompue.").withStyle(ChatFormatting.GOLD), true);
                            return 1;
                        }))
                .then(Commands.literal("on").executes(context -> {
                    Settings.setEnabled(true);
                    context.getSource().sendSuccess(() -> Component.literal("Lanterne allumée.")
                            .withStyle(ChatFormatting.GREEN), true);
                    return 1;
                }))
                .then(Commands.literal("off").executes(context -> {
                    Settings.setEnabled(false);
                    context.getSource().sendSuccess(() -> Component.literal(
                                    "Lanterne éteinte — plus aucune dégradation. Le rapport continue.")
                            .withStyle(ChatFormatting.YELLOW), true);
                    return 1;
                }))
                .then(Commands.literal("bench").executes(context -> {
                    if (Bench.running()) {
                        context.getSource().sendFailure(Component.literal("Un banc est déjà en cours."));
                        return 0;
                    }
                    Bench.start(context.getSource());
                    return 1;
                }))
                .then(Commands.literal("test")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("nombre", IntegerArgumentType.integer(1, MAX_HERD))
                                .then(Commands.argument("rayon", IntegerArgumentType.integer(0, 400))
                                        .executes(context -> {
                                            int n = IntegerArgumentType.getInteger(context, "nombre");
                                            int r = IntegerArgumentType.getInteger(context, "rayon");
                                            CommandSourceStack src = context.getSource();
                                            Vec3 here = src.getPosition();
                                            // La sonde d'abord : sans observateur, rien n'est
                                            // recense, et le banc mesurerait deux fois la meme chose.
                                            spawnHerd(src, n, r);
                                            Bench.start(src);
                                            return 1;
                                        }))))
                .then(Commands.literal("charge")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("nombre", IntegerArgumentType.integer(1, MAX_HERD))
                                .then(Commands.argument("rayon", IntegerArgumentType.integer(0, 400))
                                        .executes(context -> spawnHerd(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "nombre"),
                                                IntegerArgumentType.getInteger(context, "rayon")))))));
    }

    /**
     * Crée une charge reproductible.
     *
     * <h2>Pourquoi un anneau, et non un tas</h2>
     *
     * <p>Les entités sont réparties sur un anneau de rayon choisi, et non entassées sur un point.
     * C'est ce qui permet de tester le gradient : à quarante blocs elles sont en pleine simulation,
     * à deux cents elles dorment. Un tas au même endroit ne mesurerait qu'un seul niveau, et
     * donnerait une réponse qui ne vaudrait que pour lui.
     *
     * <p>Le rayon est donc le véritable paramètre de l'expérience : c'est en le faisant varier qu'on
     * voit le mod agir, ou ne pas agir.
     */
    private static int spawnHerd(CommandSourceStack source, int count, int radius) {
        ServerLevel level = source.getLevel();
        Vec3 centre = source.getPosition();

        int born = 0;
        for (int i = 0; i < count; i++) {
            double angle = i * 2.399963d; // l'angle d'or : répartit sans jamais aligner
            double spread = radius == 0 ? 0d : radius * Math.sqrt((i + 0.5d) / count);
            double x = centre.x + Math.cos(angle) * spread;
            double z = centre.z + Math.sin(angle) * spread;

            Cow cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
            if (cow == null) {
                continue;
            }
            cow.snapTo(x, (double) level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    (int) x, (int) z), z, 0f, 0f);
            cow.setPersistenceRequired(); // sinon la charge s'évapore avant la fin du banc
            if (level.addFreshEntity(cow)) {
                born++;
            }
        }

        int finalBorn = born;
        source.sendSuccess(() -> Component.literal(
                        finalBorn + " vache(s) posée(s) sur un anneau de " + radius + " blocs.")
                .withStyle(ChatFormatting.GREEN), true);
        return born;
    }

    private static void report(CommandSourceStack source) {
        double avoided = Census.workAvoided();
        double pressure = TickBudget.pressure();

        source.sendSuccess(() -> Component.literal("── Lanterne ──")
                .withStyle(ChatFormatting.GOLD), false);

        if (!Settings.enabled()) {
            source.sendSuccess(() -> Component.literal("ÉTEINTE — aucune dégradation appliquée.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }

        line(source, "Tick moyen", String.format(Locale.ROOT, "%.2f ms", TickBudget.averageMillis())
                + String.format(Locale.ROOT, "  (pire : %.1f ms)", TickBudget.worstMillis()));

        // La pression est la seule valeur qui explique toutes les autres : c'est elle qui décide de
        // la sévérité, et elle est mesurée, jamais configurée.
        line(source, "Pression", String.format(Locale.ROOT, "%.0f %%", pressure * 100d)
                + (pressure < 0.05d ? "  — serveur au repos, rien n'est dégradé" : ""));

        line(source, "Machine", fr.clubcitrouille.lanterne.core.Machine.cores() + " fil(s), gain parallèle ×"
                + String.format(Locale.ROOT, "%.2f", fr.clubcitrouille.lanterne.core.Machine.speedup())
                + " — " + fr.clubcitrouille.lanterne.core.Machine.verdict());
        line(source, "Blocs-entités endormis",
                String.format(Locale.ROOT, "%.0f %% des ticks épargnés",
                        fr.clubcitrouille.lanterne.core.Sleep.savedShare() * 100d));
        line(source, "Chunks recensés", String.valueOf(Census.chunksSeen()));
        line(source, "Entités classées", String.valueOf(Census.entitiesSeen()));

        // Ce que les trois modules ajoutés en dernier ont réellement fait, et non ce qu'ils sont censés
        // faire. Un module mesuré « sans effet » parce qu'il ne s'est jamais déclenché et un module sans
        // effet réel donnent le même chiffre au banc — ces compteurs séparent les deux cas, en jeu.
        line(source, "Entités capables de bloquer",
                fr.clubcitrouille.lanterne.core.Solid.blockers()
                        + " (bateaux, shulkers, ghasts apprivoisés — tout le reste ne bloque rien)");
        line(source, "Ticks de production rattrapés",
                fr.clubcitrouille.lanterne.core.Produce.compensated()
                        + " — ponte, croissance et reproduction restent à l'heure");

        for (var entry : Census.buckets().entrySet()) {
            line(source, "  " + entry.getKey(), entry.getValue()[0] + " entité(s)");
        }

        source.sendSuccess(() -> Component.literal(
                        String.format(Locale.ROOT, "Travail évité : %.1f %%", avoided * 100d))
                .withStyle(avoided > 0.5d ? ChatFormatting.GREEN
                        : avoided > 0.2d ? ChatFormatting.YELLOW : ChatFormatting.GRAY), false);

        source.sendSuccess(() -> Component.literal(
                        "« /lanterne bench » compare la même charge avec et sans.")
                .withStyle(ChatFormatting.DARK_GRAY), false);
    }

    /**
     * Bascule l'autorisation de voler, sans toucher au reste des capacités du joueur.
     *
     * <p>{@code flying} redescend à faux quand {@code mayfly} s'éteint : sans cela, un joueur qui
     * volait au moment où l'autorisation lui est retirée resterait en l'air jusqu'à son prochain
     * mouvement, ce qui ressemble à un bogue plutôt qu'à une commande qui vient d'agir.
     *
     * <p>Le basculement est aussi écrit dans {@link fr.clubcitrouille.lanterne.core.Envol} : c'est ce
     * qui rend ce geste manuel <em>persistant</em> d'une connexion à l'autre, sans qu'il faille le
     * retaper — voir le javadoc de cette classe pour pourquoi une détection automatique du mod de vol
     * client n'a pas été possible.
     */
    private static int toggleFlight(CommandSourceStack source, ServerPlayer target) {
        var abilities = target.getAbilities();
        boolean allowed = !abilities.mayfly;
        abilities.mayfly = allowed;
        if (!allowed) {
            abilities.flying = false;
        }
        target.onUpdateAbilities();
        fr.clubcitrouille.lanterne.core.Envol.setAllowed(target.getUUID(), allowed);
        source.sendSuccess(() -> Component.literal(target.getGameProfile().name()
                        + (allowed ? " peut désormais voler." : " ne peut plus voler.")
                        + (allowed ? " (survivra à la reconnexion)" : ""))
                .withStyle(allowed ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return 1;
    }

    /**
     * Bascule entre le monde de test et l'endroit d'où le joueur est parti.
     *
     * <p>Le point de retour se pose au moment de PARTIR, jamais au moment de revenir — sans quoi un
     * second aller dans le monde de test écraserait le souvenir de la vraie position par une
     * position à l'intérieur du monde de test lui-même, et le joueur ne rentrerait plus jamais chez
     * lui.
     */
    private static int toggleTestWorld(CommandSourceStack source, ServerPlayer player) {
        ServerLevel testLevel = source.getServer().getLevel(MONDE_TEST);
        if (testLevel == null) {
            source.sendFailure(Component.literal(
                    "Le monde de test n'existe pas sur ce serveur (redémarrage nécessaire après "
                    + "l'installation du mod)."));
            return 0;
        }

        if (player.level().dimension().equals(MONDE_TEST)) {
            GlobalPos back = RETURN_POINTS.remove(player.getUUID());
            ServerLevel target = back != null ? source.getServer().getLevel(back.dimension()) : null;
            if (back == null || target == null) {
                target = source.getServer().overworld();
                Vec3 spawn = Vec3.atBottomCenterOf(target.getRespawnData().pos());
                player.teleportTo(target, spawn.x, spawn.y, spawn.z,
                        java.util.Set.of(), player.getYRot(), player.getXRot(), true);
            } else {
                player.teleportTo(target, back.pos().x, back.pos().y, back.pos().z,
                        java.util.Set.of(), back.yaw(), back.pitch(), true);
            }
            source.sendSuccess(() -> Component.literal("Retour du monde de test.")
                    .withStyle(ChatFormatting.GOLD), true);
            return 1;
        }

        RETURN_POINTS.put(player.getUUID(), new GlobalPos(player.level().dimension(),
                player.position(), player.getYRot(), player.getXRot()));
        player.teleportTo(testLevel, 0.5d, 5d, 0.5d, java.util.Set.of(),
                player.getYRot(), player.getXRot(), true);
        source.sendSuccess(() -> Component.literal(
                "Monde de test — plaine plate et vide. « /lanterne test » à nouveau pour revenir.")
                .withStyle(ChatFormatting.GOLD), true);
        return 1;
    }

    private static void line(CommandSourceStack source, String name, String value) {
        source.sendSuccess(() -> Component.literal(name + " : ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE)), false);
    }

    /**
     * Bâtit devant le joueur une des charges du laboratoire.
     *
     * <h2>Pourquoi cette commande existe</h2>
     *
     * <p>Un joueur qui regarde une plaine à cent soixante-dix images par seconde ne peut rien juger
     * de ce mod, et c'est <b>normal</b> : dans une plaine, il n'y a ni mur pour voiler quoi que ce
     * soit, ni coffre, ni pile au sol. Les modules économisent du travail qui, là, n'existe pas.
     *
     * <p>Les chiffres du laboratoire viennent de charges précises — mille vaches sous un toit, douze
     * cents coffres, quinze cents piles pleines. Cette commande pose ces mêmes charges <b>devant le
     * joueur</b>, pour qu'il bascule les réglages et voie l'effet de ses propres yeux plutôt que de
     * croire un tableau.
     *
     * <p>C'est la seule façon honnête de répondre à « je ne vois pas la différence » : montrer la
     * situation où la différence existe.
     */
    private static int demo(CommandSourceStack source, String kind, int count) {
        ServerLevel level = source.getLevel();
        fr.clubcitrouille.lanterne.lab.Scene.Kind chosen =
                fr.clubcitrouille.lanterne.lab.Scene.parse(kind);
        int born = fr.clubcitrouille.lanterne.lab.Scene.build(level, chosen, count, 0);
        source.sendSuccess(() -> Component.literal(
                "Charge « " + chosen + " » posée à l'origine du monde : " + born + " élément(s).")
                .withStyle(ChatFormatting.GOLD), true);
        source.sendSuccess(() -> Component.literal(
                "Rends-toi à 0 / 0 et regarde-la. Ouvre ensuite Options > Graphismes > Lanterne, "
                + "active la jauge, et bascule les réglages : la différence se voit là, pas dans "
                + "une plaine vide.")
                .withStyle(ChatFormatting.GRAY), false);
        return born;
    }
}
