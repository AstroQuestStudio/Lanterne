package fr.clubcitrouille.lanterne.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.Settings;
import fr.clubcitrouille.lanterne.core.Stencil;

/**
 * Le maçon : combien coûte la pose d'un gabarit de structure qui déborde du chunk.
 *
 * <h2>Pourquoi ni le banc de vitesse ni celui de la carrière ne pouvaient trancher</h2>
 *
 * <p><b>Le banc de vitesse chronomètre le tick.</b> Le pochoir n'agit que pendant la génération de
 * terrain, qui s'exécute sur le pool de travail. Un monde déjà exploré ne pose plus une seule
 * structure : le banc mesurerait un module qui ne s'exécute jamais, et annoncerait « aucun effet »
 * avec le même aplomb que les autres.
 *
 * <p><b>Le banc de la carrière génère soixante-quatre chunks de chaque côté.</b> C'est trop peu : un
 * village apparaît environ une fois par trente-quatre chunks d'espacement, un portail en ruine une
 * fois par quarante. Une grille de huit sur huit a donc de bonnes chances de n'en contenir
 * <em>aucun</em> — et si elle en contient un, son coût se dilue dans deux secondes et demie de
 * génération de terrain. Un verdict « sous le bruit » ne dirait alors rien du module ; il dirait que
 * l'instrument est trop gros pour l'objet.
 *
 * <p>Cette épreuve est donc bâtie comme celle des explosions et celle de la compression : elle
 * reproduit <b>le geste exact</b> et le mesure seul.
 *
 * <h2>Le geste, et pourquoi il est représentatif</h2>
 *
 * <p>Un vrai gabarit du jeu — une maison de village, chargée depuis les ressources du serveur — est
 * posé avec une fenêtre de <b>seize blocs de côté</b>, c'est-à-dire exactement celle que
 * {@code ChunkGenerator.getWritableArea} fabrique pour un chunk. L'ancre est décalée de telle sorte
 * que le gabarit <b>chevauche</b> la fenêtre plutôt que d'y tenir : c'est le cas courant, et c'est le
 * seul où le pochoir a quelque chose à faire.
 *
 * <p>Les deux états alternent passe par passe, et l'ordre s'inverse à chaque fois — si un effet de
 * cache favorisait le premier appelé, il favoriserait les deux également. C'est le protocole de
 * l'épreuve de compression, qui a déjà servi.
 *
 * <h2>Ce que cette mesure est, et ce qu'elle n'est pas</h2>
 *
 * <p>Elle est une <b>borne basse</b>. Le gabarit est posé sans processeur, parce qu'en ajouter un
 * reviendrait à choisir soi-même l'ampleur du gain qu'on va annoncer : la chaîne des
 * {@code StructureProcessor} est précisément ce que le pochoir évite le plus. Un village réel en
 * porte plusieurs. Ce que l'on mesure ici, c'est donc uniquement ce que le tri épargne <em>sans</em>
 * eux : la position transformée, l'objet {@code StructureBlockInfo} neuf et la copie de la balise
 * NBT.
 *
 * <p>Elle n'est pas non plus une mesure de TPS. Ce module ne rend pas un serveur plus rapide à
 * charge égale : il rend l'<b>exploration</b> moins coûteuse, ce qui est une autre grandeur — celle
 * que la carrière appelle une latence, et dont ce laboratoire a déjà établi qu'elle ne pèse pas sur
 * le temps de tick.
 */
public final class Mason {
    /**
     * Les gabarits essayés, dans l'ordre, jusqu'à ce que l'un se charge.
     *
     * <p>On ne fabrique pas de gabarit de laboratoire : un gabarit inventé aurait la taille et la
     * densité qu'on lui donnerait, c'est-à-dire celles qui arrangent le résultat. Ceux-ci sont ceux
     * que le jeu pose réellement, chargés depuis ses propres ressources.
     */
    private static final String[] CANDIDATES = {
        "minecraft:village/plains/houses/plains_big_house_1",
        "minecraft:village/plains/houses/plains_medium_house_1",
        "minecraft:village/plains/houses/plains_small_house_1",
        "minecraft:village/plains/town_centers/plains_fountain_01",
        "minecraft:igloo/top",
    };

    /** Côté de la fenêtre, en blocs. Seize : celui d'un chunk, et donc celui de la vraie pose. */
    private static final int WINDOW = 16;

    /**
     * Poses de chauffe, jetées — et la première version en avait quatre fois trop peu.
     *
     * <h2>Le second régime passait pour deux fois plus rapide que le premier</h2>
     *
     * <p>Avec vingt-quatre poses de chauffe, l'épreuve a rendu {@code 0,866 ms} pour le gabarit nu et
     * {@code 0,408 ms} pour le même gabarit <em>avec</em> deux processeurs de plus. Ajouter du travail
     * ne rend pas une méthode deux fois plus rapide : c'est le premier régime qui payait la
     * compilation à la volée pour le second.
     *
     * <p>C'est le défaut que le banc d'images avait eu, puis le banc de vitesse, pour la même raison à
     * chaque fois — une chauffe dimensionnée à vue. Deux cents poses par côté et par régime la
     * corrigent ici.
     */
    private static final int WARMUP = 200;

    /**
     * Passes mesurées, alternées entre les deux états.
     *
     * <p>Quarante ne suffisaient pas : deux exécutions de suite ont rendu ×1,34 puis ×0,96 sur le
     * même code et la même charge. Une médiane de quarante relevés à un demi-millième de seconde est
     * à la merci de ce qui tourne à côté sur la machine.
     */
    private static final int PASSES = 400;

    private static boolean running;
    private static int waited;
    private static MinecraftServer host;

    private Mason() {}

    public static boolean running() {
        return running;
    }

    public static void begin(MinecraftServer server) {
        host = server;
        running = true;
        waited = 0;
    }

    /**
     * Laisse quelques ticks aux chunks demandés avant de poser quoi que ce soit.
     *
     * <p>Troisième fois que ce laboratoire se fait prendre par là — le tir, les fluides, la moisson.
     * {@code setBlock} n'écrit rien dans un chunk qui n'est pas chargé, et une épreuve qui pose ses
     * blocs dans le vide mesure deux fois la même absence.
     */
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

        StructureTemplate template = null;
        String chosen = null;
        for (String id : CANDIDATES) {
            Optional<StructureTemplate> found = server.getStructureManager()
                    .get(Identifier.parse(id));
            if (found.isPresent()) {
                template = found.get();
                chosen = id;
                break;
            }
        }
        if (template == null) {
            Lanterne.LOG.error("[MAÇON] aucun gabarit de structure n'a pu être chargé parmi les {} "
                    + "candidats — rien à mesurer.", CANDIDATES.length);
            server.halt(false);
            return;
        }

        // getSize() rend un Vec3i en 26.1, pas un BlockPos : seules les composantes servent ici.
        net.minecraft.core.Vec3i size = template.getSize();
        int ground = Scene.groundLevel(level) + 2;

        // L'ancre place le gabarit à cheval sur la fenêtre : décalé du quart de sa taille vers
        // l'extérieur en X et en Z. Une structure entièrement contenue n'écarterait rien, et la
        // mesure porterait alors sur un tri qui ne trie pas.
        BlockPos anchor = new BlockPos(-size.getX() / 2, ground, -size.getZ() / 2);
        BoundingBox window = new BoundingBox(0, level.getMinY() + 1, 0,
                WINDOW - 1, level.getMaxY(), WINDOW - 1);

        int total = template.getSize().getX() * template.getSize().getY() * template.getSize().getZ();
        Lanterne.LOG.info("[MAÇON] gabarit « {} » — {}x{}x{} blocs ({} positions de boîte), fenêtre de "
                + "{} blocs, ancre {}.", chosen, size.getX(), size.getY(), size.getZ(), total,
                WINDOW, anchor);

        RandomSource dice = RandomSource.create(1234L);

        // Deux régimes, et il fallait les deux.
        //
        // Le premier pose le gabarit NU. C'est la borne basse, et elle ne décrit aucune structure
        // réelle : elle ne mesure que la position transformée, l'objet neuf et la copie de balise.
        //
        // Le second lui donne les DEUX processeurs que SinglePoolElement.place ajoute à chaque
        // pièce de structure à jigsaw — village, avant-poste, bastion, cité ancienne — et rien de
        // plus. Ce n'est donc pas un régime choisi pour flatter le résultat : c'est le PLANCHER de
        // ce qu'une vraie pièce porte, la liste du template pool venant encore par-dessus.
        measure(server, level, template, anchor, window, dice, false, "gabarit nu");
        measure(server, level, template, anchor, window, dice, true,
                "gabarit avec les deux processeurs de toute pièce à jigsaw");

        server.halt(false);
    }

    private static void measure(MinecraftServer server, ServerLevel level, StructureTemplate template,
            BlockPos anchor, BoundingBox window, RandomSource dice, boolean processors, String label) {
        for (int pass = 0; pass < WARMUP; pass++) {
            place(level, template, anchor, window, dice, true, processors);
            place(level, template, anchor, window, dice, false, processors);
        }

        Stencil.reset();
        List<Long> withStencil = new ArrayList<>();
        List<Long> without = new ArrayList<>();

        for (int pass = 0; pass < PASSES; pass++) {
            boolean stencilFirst = (pass % 2) == 0;
            if (stencilFirst) {
                withStencil.add(place(level, template, anchor, window, dice, true, processors));
                without.add(place(level, template, anchor, window, dice, false, processors));
            } else {
                without.add(place(level, template, anchor, window, dice, false, processors));
                withStencil.add(place(level, template, anchor, window, dice, true, processors));
            }
        }

        double withMedian = median(withStencil);
        double withoutMedian = median(without);

        Lanterne.LOG.info("[MAÇON] ── {} ──", label);
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[MAÇON] Sans pochoir : %.3f ms par pose (médiane de %d)",
                withoutMedian / 1e6, without.size()));
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[MAÇON] Avec pochoir : %.3f ms par pose (médiane de %d)",
                withMedian / 1e6, withStencil.size()));

        long seen = Stencil.seen();
        long trimmed = Stencil.trimmed();
        Lanterne.LOG.info(String.format(Locale.ROOT,
                "[MAÇON] Tri : %d bloc(s) examiné(s), %d écarté(s) avant préparation (%.1f %%) · "
                        + "%d pose(s) où le tri a renoncé",
                seen, trimmed, seen == 0L ? 0d : 100d * trimmed / seen, Stencil.declined()));

        if (trimmed == 0L) {
            Lanterne.LOG.warn("[MAÇON] REFUS : le tri n'a écarté AUCUN bloc. Le gabarit tient "
                    + "entièrement dans la fenêtre, ou le module était éteint — dans les deux cas "
                    + "le chiffre ci-dessus ne dit rien de lui.");
            return;
        }
        if (withMedian <= 0d || withoutMedian <= 0d) {
            Lanterne.LOG.warn("[MAÇON] REFUS : médiane nulle d'un côté.");
            return;
        }
        double ratio = withoutMedian / withMedian;
        if (ratio > 1.08d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[MAÇON] GAIN : ×%.2f sur la pose d'un gabarit à cheval sur un chunk.", ratio));
        } else if (ratio < 1d / 1.08d) {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[MAÇON] PERTE : ×%.2f — le tri coûte plus qu'il n'épargne ici.", ratio));
        } else {
            Lanterne.LOG.info(String.format(Locale.ROOT,
                    "[MAÇON] AUCUN EFFET MESURABLE : ×%.2f, sous la dérive de ×1,08 du "
                            + "laboratoire. Le tri fonctionne — %.1f %% des blocs sont écartés — "
                            + "mais ce qu'il épargne ne se voit pas à cette échelle.",
                    ratio, 100d * trimmed / seen));
        }
    }

    /** Une pose, chronométrée, dans l'état demandé. */
    private static long place(ServerLevel level, StructureTemplate template, BlockPos anchor,
            BoundingBox window, RandomSource dice, boolean stencil, boolean processors) {
        Settings.setEnabled(stencil);
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setMirror(Mirror.NONE)
                .setRotation(Rotation.NONE)
                .setIgnoreEntities(true)
                .setBoundingBox(window);
        if (processors) {
            settings.addProcessor(net.minecraft.world.level.levelgen.structure.templatesystem
                    .BlockIgnoreProcessor.STRUCTURE_BLOCK);
            settings.addProcessor(net.minecraft.world.level.levelgen.structure.templatesystem
                    .JigsawReplacementProcessor.INSTANCE);
        }
        long started = System.nanoTime();
        template.placeInWorld(level, anchor, anchor, settings, dice, Block.UPDATE_CLIENTS);
        long elapsed = System.nanoTime() - started;
        Settings.setEnabled(true);
        return elapsed;
    }

    private static double median(List<Long> values) {
        if (values.isEmpty()) {
            return 0d;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2d
                : sorted.get(middle);
    }
}
