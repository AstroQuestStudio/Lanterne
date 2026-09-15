package fr.clubcitrouille.lanterne.report;

import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.phys.Vec3;

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

    private LanterneCommand() {}

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
                .then(Commands.literal("maree")
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Marée — " + fr.clubcitrouille.lanterne.core.Tide.describe(
                                            context.getSource().getServer()))
                                    .withStyle(ChatFormatting.AQUA), false);
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
