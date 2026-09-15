package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Cadastre;
import fr.clubcitrouille.lanterne.core.Settings;

/**
 * Le bourg : combien coûte la <b>disposition</b> des pièces d'un village.
 *
 * <h2>Pourquoi aucune épreuve existante ne pouvait trancher</h2>
 *
 * <p>Le cadastre n'agit ni pendant le tick ni pendant l'image : il agit pendant l'étape
 * {@code generateStructureStarts} de la génération d'un chunk. Le banc de vitesse ne le verra donc
 * jamais.
 *
 * <p>La carrière, elle, génère soixante-quatre chunks de chaque côté. C'est trop peu : un village
 * apparaît environ une fois par trente-quatre chunks d'espacement, et s'il tombe dans la grille, son
 * coût se dilue dans deux secondes et demie de bruit de terrain. Un verdict « sous le bruit » ne
 * dirait alors rien du module ; il dirait que l'instrument est trop gros pour l'objet.
 *
 * <h2>Le geste, et pourquoi il est le vrai</h2>
 *
 * <p>On ne fabrique pas de structure de laboratoire. On prend la <b>vraie structure du jeu</b>
 * ({@code minecraft:village_plains}, puis {@code minecraft:ancient_city}), on lui construit le même
 * {@code Structure.GenerationContext} que le générateur de chunks lui construit, et l'on appelle
 * {@code findValidGenerationPoint} puis {@code getPiecesBuilder()} — qui est exactement l'appel où
 * {@code JigsawPlacement$Placer} fait son travail.
 *
 * <p><b>Aucun bloc n'est écrit.</b> La disposition se calcule sans rien poser : c'est ce qui permet
 * de la rejouer des dizaines de fois sur la même graine et d'obtenir chaque fois le même résultat.
 * C'est aussi pourquoi cette épreuve n'a besoin d'aucune doublure, contrairement au maçon qu'elle
 * remplace.
 *
 * <h2>La conformité passe AVANT la vitesse, et elle est le vrai sujet</h2>
 *
 * <p>Le symptôme d'un registre mal tenu ne serait pas une lenteur : ce serait <b>un village bâti
 * autrement</b> — deux maisons l'une dans l'autre, ou une rue qui s'arrête. Aucun banc de ce dépôt
 * n'aurait pu le voir.
 *
 * <p>On compare donc, position de chunk par position de chunk, la liste de pièces obtenue sans le
 * module et celle obtenue avec : même nombre, même élément, même rotation, même boîte, dans le même
 * ordre. Toute différence est une erreur, et il n'y a rien à interpréter.
 *
 * <p>{@code LANTERNE_BREAK_CADASTRE=1} rend le registre amnésique. Cette épreuve <b>doit</b> alors
 * annoncer une divergence — une épreuve qui ne peut pas échouer ne prouve rien.
 *
 * <h2>Ce que cette mesure n'est pas</h2>
 *
 * <p>Elle n'est pas une mesure de TPS. Le placement s'exécute sur le pool de travail ; ce qu'on gagne
 * est de la <b>latence d'exploration</b> — le temps qu'un joueur qui avance attend son chunk. Voir
 * {@code notes/serveur-un-coeur.md}.
 */
public final class Bourg {
    /**
     * Les structures essayées, et pourquoi ces deux-là.
     *
     * <p>Le village des plaines est le cas <b>courant</b> : c'est la structure qu'un joueur croise en
     * explorant, et celle dont le gel se remarque. La cité ancienne est le cas <b>extrême</b> : elle
     * a le plus grand nombre de pièces de vanilla, et c'est donc elle qui porte l'exposant quatre au
     * plus haut. Si un module de ce genre ne se voyait sur aucune des deux, il ne se verrait nulle
     * part.
     */
    private static final String[] STRUCTURES = {
        "minecraft:village_plains",
        "minecraft:ancient_city",
    };

    /**
     * Positions de chunk essayées pour chaque structure.
     *
     * <p>Quatre, et éloignées : le relief change la hauteur de départ, donc les pièces retenues, donc
     * le nombre de pièces. Une seule position mesurerait un village particulier ; quatre disent si le
     * résultat tient.
     */
    private static final int[][] SPOTS = {
        {40, 40}, {-72, 120}, {200, -160}, {-320, -288},
    };

    /** Dispositions de chauffe, jetées. Voir le maçon : une chauffe à vue a déjà faussé un banc. */
    private static final int WARMUP = 6;

    /** Passes mesurées, alternées entre les deux états. */
    private static final int PASSES = 24;

    /**
     * En deçà de ce nombre de pièces, on refuse de conclure.
     *
     * <p>Le défaut visé croît comme la puissance quatrième du nombre de pièces. Une structure de dix
     * pièces ne peut donc rien montrer, et publier un rapport de ×1,00 sur elle reviendrait à dire
     * « ce module ne sert à rien » en ayant mesuré une charge où il ne pouvait rien servir. Cinq
     * épreuves de ce dépôt ont déjà rendu « conforme » sur une scène vide.
     */
    private static final int ENOUGH_PIECES = 20;

    private static boolean running;
    private static int waited;
    private static MinecraftServer host;

    private Bourg() {}

    public static boolean running() {
        return running;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        running = true;
        waited = 0;
    }

    public static void tick(MinecraftServer server) {
        if (!running) {
            return;
        }
        if (waited++ < 40) {
            return;
        }
        running = false;
        run(server == null ? host : server);
    }

    private static void run(MinecraftServer server) {
        ServerLevel level = server.overworld();
        RegistryAccess access = level.registryAccess();

        if (Cadastre.broken()) {
            Lanterne.LOG.info("[BOURG] (LANTERNE_BREAK_CADASTRE=1 était posé : la conformité DOIT "
                    + "annoncer une divergence. Si elle annonce « aucun écart », c'est l'épreuve "
                    + "qui est cassée, pas le module qui est bon.)");
        }

        int measured = 0;
        for (String id : STRUCTURES) {
            Optional<Holder.Reference<Structure>> found = access
                    .lookupOrThrow(Registries.STRUCTURE)
                    .get(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(id)));
            if (found.isEmpty()) {
                Lanterne.LOG.warn("[BOURG] structure « {} » absente du registre — passée.", id);
                continue;
            }
            if (measure(server, level, found.get().value(), id)) {
                measured++;
            }
        }

        if (measured == 0) {
            Lanterne.LOG.error("[BOURG] REFUS DE CONCLURE : aucune structure n'a pu être disposée. "
                    + "Ce rapport ne dit rien du module.");
        }
        Lanterne.LOG.info("[BOURG] Registre : {}", Cadastre.describe());
        server.halt(false);
    }

    /** Rend vrai si cette structure a pu fournir un verdict. */
    private static boolean measure(MinecraftServer server, ServerLevel level, Structure structure,
            String id) {
        Lanterne.LOG.info("[BOURG] ── {} ──", id);

        // 1. La conformité, avant tout le reste. Une vitesse mesurée sur un module qui bâtit autre
        //    chose ne serait pas une bonne nouvelle : ce serait un piège.
        int faults = 0;
        int compared = 0;
        int biggest = 0;
        List<int[]> usable = new ArrayList<>();
        for (int[] spot : SPOTS) {
            ChunkPos at = new ChunkPos(spot[0], spot[1]);
            List<String> plain = layout(level, structure, at, false);
            List<String> filed = layout(level, structure, at, true);
            if (plain.isEmpty()) {
                Lanterne.LOG.info("[BOURG] chunk {},{} : aucune structure — ignoré.",
                        spot[0], spot[1]);
                continue;
            }
            usable.add(spot);
            biggest = Math.max(biggest, plain.size());
            compared += Math.max(plain.size(), filed.size());
            if (plain.size() != filed.size()) {
                faults += Math.abs(plain.size() - filed.size());
            }
            int common = Math.min(plain.size(), filed.size());
            for (int piece = 0; piece < common; piece++) {
                if (!plain.get(piece).equals(filed.get(piece))) {
                    faults++;
                }
            }
            Lanterne.LOG.info("[BOURG] chunk {},{} : {} pièce(s) sans le module, {} avec.",
                    spot[0], spot[1], plain.size(), filed.size());
        }

        if (usable.isEmpty()) {
            Lanterne.LOG.warn("[BOURG] REFUS DE CONCLURE pour « {} » : aucune des {} positions "
                    + "n'a produit de structure. Rien n'a été mesuré.", id, SPOTS.length);
            return false;
        }
        if (faults == 0) {
            Lanterne.LOG.info("[BOURG] CONFORMITÉ : {} pièce(s) comparée(s) sur {} position(s) — "
                    + "AUCUN écart. Le cadastre dispose exactement ce que vanilla dispose.",
                    compared, usable.size());
            if (Cadastre.broken()) {
                Lanterne.LOG.error("[BOURG] MAIS LANTERNE_BREAK_CADASTRE=1 ÉTAIT POSÉ. Cette "
                        + "épreuve devait échouer et ne l'a pas fait : elle ne prouve donc rien, "
                        + "et c'est elle qu'il faut réparer avant de croire le reste.");
            }
        } else {
            Lanterne.LOG.error("[BOURG] CONFORMITÉ : {} écart(s) sur {} pièce(s) comparée(s). "
                    + "LE CADASTRE NE DISPOSE PAS LA MÊME CHOSE QUE VANILLA — ne pas publier ce "
                    + "module.", faults, compared);
            if (Cadastre.broken()) {
                Lanterne.LOG.info("[BOURG] (attendu : l'interrupteur de rupture était posé.)");
            }
            return false;
        }

        if (biggest < ENOUGH_PIECES) {
            Lanterne.LOG.warn("[BOURG] REFUS DE CONCLURE sur la vitesse pour « {} » : la plus "
                    + "grosse disposition n'a que {} pièce(s), et le défaut visé croît comme la "
                    + "puissance quatrième de ce nombre. Il n'y a rien à voir à cette taille, et "
                    + "un ×1,00 ici ne dirait rien.", id, biggest);
            return false;
        }

        // 2. La vitesse, sur la position qui porte le plus de pièces — c'est là que l'exposant vit.
        int[] chosen = usable.get(0);
        int most = 0;
        for (int[] spot : usable) {
            int count = layout(level, structure, new ChunkPos(spot[0], spot[1]), false).size();
            if (count > most) {
                most = count;
                chosen = spot;
            }
        }
        ChunkPos where = new ChunkPos(chosen[0], chosen[1]);
        Lanterne.LOG.info("[BOURG] vitesse mesurée sur le chunk {},{} — {} pièce(s).",
                chosen[0], chosen[1], most);

        for (int pass = 0; pass < WARMUP; pass++) {
            place(level, structure, where, true);
            place(level, structure, where, false);
        }

        Cadastre.reset();
        List<Long> with = new ArrayList<>();
        List<Long> without = new ArrayList<>();
        for (int pass = 0; pass < PASSES; pass++) {
            // L'ordre s'inverse à chaque passe : si un effet de cache favorisait le premier appelé,
            // il favoriserait les deux également. C'est le protocole de l'épreuve de compression.
            if ((pass % 2) == 0) {
                with.add(place(level, structure, where, true));
                without.add(place(level, structure, where, false));
            } else {
                without.add(place(level, structure, where, false));
                with.add(place(level, structure, where, true));
            }
        }

        double withMedian = median(with);
        double withoutMedian = median(without);
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[BOURG] Sans cadastre : %.3f ms par disposition (médiane de %d)",
                withoutMedian / 1e6, without.size()));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[BOURG] Avec cadastre : %.3f ms par disposition (médiane de %d)",
                withMedian / 1e6, with.size()));
        Lanterne.LOG.info("[BOURG] Registre pendant la mesure : {}", Cadastre.describe());

        if (Cadastre.taken() == 0L) {
            Lanterne.LOG.warn("[BOURG] REFUS DE CONCLURE : le registre n'a pris AUCUNE région en "
                    + "charge. Le module était éteint, ou son point d'accroche ne s'applique pas — "
                    + "dans les deux cas le chiffre ci-dessus ne dit rien de lui.");
            return false;
        }
        if (withMedian <= 0d || withoutMedian <= 0d) {
            Lanterne.LOG.warn("[BOURG] REFUS DE CONCLURE : médiane nulle d'un côté.");
            return false;
        }

        double ratio = withoutMedian / withMedian;
        if (ratio > 1.08d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[BOURG] GAIN : ×%.2f sur la disposition de %d pièces.", ratio, most));
        } else if (ratio < 1d / 1.08d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[BOURG] PERTE : ×%.2f — le registre coûte plus qu'il n'épargne ici.", ratio));
        } else {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[BOURG] AUCUN EFFET MESURABLE : ×%.2f, sous la dérive de ×1,08 du "
                            + "laboratoire.", ratio));
        }
        return true;
    }

    /**
     * Une disposition, chronométrée, dans l'état demandé.
     *
     * <p>Le contexte est <b>neuf à chaque fois</b>, et ce n'est pas de la propreté : il porte un
     * {@code WorldgenRandom} que le placement fait avancer. Le réemployer ferait diverger la seconde
     * disposition de la première pour une raison qui n'a rien à voir avec le module, et la
     * conformité crierait au loup à chaque passe.
     */
    private static long place(ServerLevel level, Structure structure, ChunkPos at, boolean on) {
        Settings.setEnabled(on);
        try {
            Structure.GenerationContext context = context(level, at);
            Optional<Structure.GenerationStub> stub = structure.findValidGenerationPoint(context);
            if (stub.isEmpty()) {
                return 0L;
            }
            long started = System.nanoTime();
            StructurePiecesBuilder builder = stub.get().getPiecesBuilder();
            long elapsed = System.nanoTime() - started;
            // Le résultat est consommé pour que le compilateur à la volée ne puisse pas décider que
            // la disposition ne sert à rien et l'effacer. Ce laboratoire a déjà vu une allocation
            // disparaître par analyse d'échappement ; une boucle morte disparaîtrait aussi bien.
            if (builder.isEmpty()) {
                return elapsed;
            }
            return elapsed;
        } finally {
            Settings.setEnabled(true);
        }
    }

    /** La disposition, rendue sous une forme comparable caractère par caractère. */
    private static List<String> layout(ServerLevel level, Structure structure, ChunkPos at,
            boolean on) {
        Settings.setEnabled(on);
        try {
            Structure.GenerationContext context = context(level, at);
            Optional<Structure.GenerationStub> stub = structure.findValidGenerationPoint(context);
            if (stub.isEmpty()) {
                return List.of();
            }
            List<String> written = new ArrayList<>();
            for (StructurePiece piece : stub.get().getPiecesBuilder().build().pieces()) {
                if (piece instanceof PoolElementStructurePiece pool) {
                    written.add(pool.getElement() + "|" + pool.getRotation() + "|"
                            + pool.getPosition() + "|" + pool.getBoundingBox()
                            + "|" + pool.getGroundLevelDelta());
                } else {
                    written.add(piece.getClass().getSimpleName() + "|" + piece.getBoundingBox());
                }
            }
            return written;
        } finally {
            Settings.setEnabled(true);
        }
    }

    private static Structure.GenerationContext context(ServerLevel level, ChunkPos at) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        return new Structure.GenerationContext(
                level.registryAccess(),
                generator,
                generator.getBiomeSource(),
                level.getChunkSource().randomState(),
                level.getStructureManager(),
                level.getSeed(),
                at,
                level,
                // Tout biome accepté : l'épreuve veut disposer la structure, pas vérifier qu'elle a
                // le droit d'exister ici. Sans cela, il faudrait chercher un vrai village dans le
                // monde — et l'on mesurerait la recherche au lieu de la disposition.
                biome -> true);
    }

    private static double median(List<Long> values) {
        List<Long> kept = new ArrayList<>();
        for (Long value : values) {
            if (value != null && value > 0L) {
                kept.add(value);
            }
        }
        if (kept.isEmpty()) {
            return 0d;
        }
        kept.sort(null);
        int middle = kept.size() / 2;
        return kept.size() % 2 == 0
                ? (kept.get(middle - 1) + kept.get(middle)) / 2d
                : kept.get(middle);
    }
}
