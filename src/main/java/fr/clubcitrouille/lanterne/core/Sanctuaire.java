package fr.clubcitrouille.lanterne.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

import fr.clubcitrouille.lanterne.Lanterne;

/**
 * Les zones sans monstres (26.3.143) : des boîtes où aucun monstre hostile n'apparaît de lui-même.
 *
 * <h2>Ce qui est bloqué, et où</h2>
 *
 * <p>Deux points d'accroche, parce que les apparitions naturelles ne passent pas toutes au même endroit :
 * <ul>
 * <li>{@link MobSpawnEvent.SpawnPlacementCheck} — le cycle naturel ({@code NaturalSpawner}) et la
 * génération de chunk, AVANT que l'entité ne soit construite : un refus ici ne coûte qu'un test de boîte,
 * et le cycle cherche une autre case dans le même tick (la zone ne « mange » pas le plafond de monstres
 * par des apparitions fantômes).</li>
 * <li>{@link FinalizeSpawnEvent} — ce qui contourne les règles de placement : phantoms (raison
 * {@code NATURAL} depuis {@code PhantomSpawner}), patrouilles d'illageois ({@code PATROL}), siège de
 * village ({@code EVENT}), renforts de zombie ({@code REINFORCEMENT}).</li>
 * </ul>
 *
 * <h2>Ce qui ne l'est pas</h2>
 *
 * <p>Spawners et trial spawners, œufs, distributeurs, {@code /summon}, reproduction, conversions, golems
 * (catégorie MISC), et toute créature hors catégorie MONSTER. Les RAIDS non plus, volontairement : un
 * {@code Raider} est inscrit dans la vague AVANT {@code finalizeSpawn} ({@code Raid.joinRaid}) ; annulé,
 * il n'entre jamais dans le monde, ne tique jamais, et {@code Raid.updateRaiders} ne l'efface qu'après 600
 * ticks d'existence — le raid resterait bloqué pour toujours sur des pillards fantômes. Un raid naît d'un
 * joueur porteur du Mauvais présage, qu'il ne peut plus attraper dans la zone faute de patrouille.
 *
 * <h2>Coût</h2>
 *
 * <p>Catégorie d'abord (un champ), puis un tableau figé de boîtes en entiers ; liste vide = retour
 * immédiat. Rien côté client : les événements ne sont tirés que par le serveur logique.
 */
public final class Sanctuaire {
    /** Une boîte : bornes INCLUSES, déjà ordonnées. */
    private record Zone(ResourceKey<Level> dimension, int x1, int y1, int z1, int x2, int y2, int z2) {
        boolean contient(ResourceKey<Level> dim, int x, int y, int z) {
            return x >= x1 && x <= x2 && z >= z1 && z <= z2 && y >= y1 && y <= y2 && dim.equals(dimension);
        }

        String texte() {
            return dimension.identifier() + ";" + x1 + ";" + y1 + ";" + z1 + ";" + x2 + ";" + y2 + ";" + z2;
        }
    }

    private static final Zone[] AUCUNE = new Zone[0];
    /** Relu au chargement du fichier et à chaque commande ; lu par le fil serveur à chaque tentative. */
    private static volatile Zone[] zones = AUCUNE;

    static ModConfigSpec.ConfigValue<List<? extends String>> ZONES;

    private Sanctuaire() {}

    /** Appelé depuis le constructeur partagé de {@link Config}, section {@code [sanctuaire]}. */
    static void defineInto(ModConfigSpec.Builder builder) {
        ZONES = builder.comment(
                "Boites ou AUCUN monstre hostile n'apparait de lui-meme (cycle naturel, phantoms,",
                "patrouilles, siege de village, renforts de zombie). Spawners, oeufs, /summon, raids,",
                "reproduction et mobs passifs ne sont PAS touches. Une boite par ligne :",
                "\"dimension;x1;y1;z1;x2;y2;z2\", bornes incluses, dans n'importe quel ordre.",
                "Exemple : \"minecraft:overworld;-2100;0;-720;-1940;200;-420\".",
                "Aussi : /lanterne zonesansmonstres ajouter|retirer|liste (op).")
                .defineListAllowEmpty("zones_sans_monstres", List.of(), () -> "",
                        entry -> entry instanceof String s && lire(s) != null);
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, Sanctuaire::onPlacement);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, Sanctuaire::onFinalize);
        NeoForge.EVENT_BUS.addListener(Sanctuaire::onRegisterCommands);
    }

    /** Bus du mod : le fichier est (re)lu. */
    public static void onConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() != Config.SPEC) {
            return;
        }
        if (event instanceof ModConfigEvent.Loading || event instanceof ModConfigEvent.Reloading) {
            recharger();
        }
    }

    private static void recharger() {
        List<Zone> lues = new ArrayList<>();
        for (String s : ZONES.get()) {
            Zone z = lire(s);
            if (z != null) {
                lues.add(z);
            }
        }
        // Relu jusqu'à trois fois par commande (la nôtre, le Reloading tiré par save(), l'observateur de
        // fichier) : on ne l'annonce que si le compte change.
        int avant = zones.length;
        zones = lues.isEmpty() ? AUCUNE : lues.toArray(AUCUNE);
        if (lues.size() != avant) {
            Lanterne.LOG.info("[SANCTUAIRE] {} zone(s) sans monstres", lues.size());
        }
    }

    private static Zone lire(String texte) {
        String[] p = texte.strip().split(";");
        if (p.length != 7) {
            return null;
        }
        Identifier id = Identifier.tryParse(p[0].strip());
        if (id == null) {
            return null;
        }
        try {
            int[] v = new int[6];
            for (int i = 0; i < 6; i++) {
                v[i] = Integer.parseInt(p[i + 1].strip());
            }
            return new Zone(ResourceKey.create(Registries.DIMENSION, id),
                    Math.min(v[0], v[3]), Math.min(v[1], v[4]), Math.min(v[2], v[5]),
                    Math.max(v[0], v[3]), Math.max(v[1], v[4]), Math.max(v[2], v[5]));
        } catch (NumberFormatException invalide) {
            return null;
        }
    }

    private static boolean dansUneZone(ServerLevelAccessor level, int x, int y, int z) {
        Zone[] boites = zones;
        if (boites.length == 0) {
            return false;
        }
        ResourceKey<Level> dim = level.getLevel().dimension();
        for (Zone b : boites) {
            if (b.contient(dim, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private static void onPlacement(MobSpawnEvent.SpawnPlacementCheck event) {
        EntitySpawnReason raison = event.getSpawnType();
        if (zones.length == 0 || event.getEntityType().getCategory() != MobCategory.MONSTER
                || (raison != EntitySpawnReason.NATURAL && raison != EntitySpawnReason.CHUNK_GENERATION)) {
            return;
        }
        BlockPos pos = event.getPos();
        if (dansUneZone(event.getLevel(), pos.getX(), pos.getY(), pos.getZ())) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    private static void onFinalize(FinalizeSpawnEvent event) {
        if (zones.length == 0) {
            return;
        }
        EntityType<?> type = event.getEntity().getType();
        if (type.getCategory() != MobCategory.MONSTER) {
            return;
        }
        EntitySpawnReason raison = event.getSpawnType();
        boolean naturelle = switch (raison) {
            case NATURAL, CHUNK_GENERATION, PATROL, REINFORCEMENT -> true;
            // EVENT = siège de village… et raids : voir la Javadoc de classe pour pourquoi on les laisse.
            case EVENT -> !(event.getEntity() instanceof Raider);
            default -> false;
        };
        if (naturelle && dansUneZone(event.getLevel(),
                (int) Math.floor(event.getX()), (int) Math.floor(event.getY()), (int) Math.floor(event.getZ()))) {
            event.setSpawnCancelled(true);
        }
    }

    // ---- /lanterne zonesansmonstres ---------------------------------------------------------------

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("lanterne")
                .then(Commands.literal("zonesansmonstres")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("liste").executes(c -> liste(c.getSource())))
                        .then(Commands.literal("ajouter")
                                .then(Commands.argument("de", BlockPosArgument.blockPos())
                                        .then(Commands.argument("a", BlockPosArgument.blockPos())
                                                .executes(c -> ajouter(c, c.getSource().getLevel().dimension()))
                                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                        .executes(c -> ajouter(c,
                                                                DimensionArgument.getDimension(c, "dimension").dimension()))))))
                        .then(Commands.literal("retirer")
                                .then(Commands.argument("numero", IntegerArgumentType.integer(1))
                                        .executes(c -> retirer(c.getSource(), IntegerArgumentType.getInteger(c, "numero")))))));
    }

    private static int liste(CommandSourceStack source) {
        Zone[] boites = zones;
        if (boites.length == 0) {
            source.sendSuccess(() -> Component.literal("Aucune zone sans monstres."), false);
            return 0;
        }
        StringBuilder sb = new StringBuilder(boites.length + " zone(s) sans monstres :");
        for (int i = 0; i < boites.length; i++) {
            Zone b = boites[i];
            sb.append(String.format(Locale.ROOT, "%n  %d. %s  (%d x %d x %d)", i + 1, b.texte(),
                    b.x2 - b.x1 + 1, b.y2 - b.y1 + 1, b.z2 - b.z1 + 1));
        }
        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return boites.length;
    }

    private static int ajouter(CommandContext<CommandSourceStack> c, ResourceKey<Level> dim) throws CommandSyntaxException {
        BlockPos a = BlockPosArgument.getBlockPos(c, "de");
        BlockPos b = BlockPosArgument.getBlockPos(c, "a");
        Zone z = lire(dim.identifier() + ";" + a.getX() + ";" + a.getY() + ";" + a.getZ() + ";"
                + b.getX() + ";" + b.getY() + ";" + b.getZ());
        List<String> l = new ArrayList<>(ZONES.get());
        l.add(z.texte());
        enregistrer(l);
        c.getSource().sendSuccess(() -> Component.literal("Zone sans monstres n°" + l.size() + " ajoutée : " + z.texte()), true);
        return l.size();
    }

    private static int retirer(CommandSourceStack source, int numero) {
        List<String> l = new ArrayList<>(ZONES.get());
        if (numero > l.size()) {
            source.sendFailure(Component.literal("Pas de zone n°" + numero + " (" + l.size() + " en tout)."));
            return 0;
        }
        String retiree = l.remove(numero - 1);
        enregistrer(l);
        source.sendSuccess(() -> Component.literal("Zone sans monstres retirée : " + retiree), true);
        return 1;
    }

    /** Écrit le fichier ET met le cache à jour tout de suite, sans attendre l'observateur de fichier. */
    private static void enregistrer(List<String> l) {
        ZONES.set(List.copyOf(l));
        Config.SPEC.save();
        recharger();
    }
}
