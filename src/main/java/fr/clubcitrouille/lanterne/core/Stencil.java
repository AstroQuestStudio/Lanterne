package fr.clubcitrouille.lanterne.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Le pochoir : on ne prépare que les blocs qui tombent dans la fenêtre.
 *
 * <h2>Le gaspillage, et il est structurel</h2>
 *
 * <p>Une structure de Minecraft ne se pose pas d'un coup. Elle se pose <b>chunk par chunk</b>, au
 * fur et à mesure que la génération atteint chacun d'eux : {@code ChunkGenerator.getWritableArea}
 * fabrique une boîte de seize blocs de côté, et {@code StructureStart.placeInChunk} rappelle
 * {@code placeInWorld} pour chaque pièce qui la touche.
 *
 * <p>Or {@code StructureTemplate.placeInWorld}, en 26.1.2, fait ceci :
 *
 * <pre>
 * List&lt;StructureBlockInfo&gt; processed = processBlockInfos(level, position, …, palette.blocks(), this);
 * for (StructureBlockInfo info : processed) {
 *     if (boundingBox == null || boundingBox.isInside(info.pos)) {   // ← le tri arrive ICI
 *         …
 *     }
 * }
 * </pre>
 *
 * <p>Le tri arrive <b>après</b> la préparation. Et la préparation n'est pas gratuite : pour
 * <em>chacun</em> des blocs du gabarit, {@code processBlockInfos} calcule la position transformée,
 * fabrique un {@code StructureBlockInfo} neuf, <b>recopie sa balise NBT</b> si elle en a une, et le
 * fait traverser toute la chaîne des {@code StructureProcessor}.
 *
 * <p>Une maison de village qui chevauche quatre chunks est donc préparée <b>quatre fois en
 * entier</b>, pour n'en écrire qu'un quart à chaque passage. Le reste est jeté à la ligne suivante.
 *
 * <h2>Ce que fait le module, et où il s'arrête</h2>
 *
 * <p>Il pose le pochoir avant la peinture : la liste de blocs remise à {@code processBlockInfos} est
 * réduite à ceux dont la position transformée tombe dans la fenêtre. Repris de
 * <b>StructureLayoutOptimizer</b> (TelepathicGrunt, MIT), qui vise précisément 26.1.
 *
 * <h2>Trois garde-fous, dont deux que le mod d'origine n'a pas</h2>
 *
 * <p><b>Un — le tri porte sur X et Z seulement.</b> C'est l'écart délibéré avec le mod d'origine, et
 * il vient d'une lecture de {@code GravityProcessor} : celui-ci <b>déplace les blocs en hauteur</b>,
 * puisqu'il rend {@code new BlockPos(x, level.getHeight(…) + offset + yLocal, z)}. Un bloc écarté
 * sur son Y d'avant pourrait donc atterrir dans la fenêtre après passage du processeur, et
 * disparaîtrait. En ne triant que sur X et Z, le module devient exact pour <em>tout</em> processeur
 * qui ne déplace pas horizontalement — ce qu'aucun processeur de vanilla ne fait. Et l'on ne perd
 * rien : la fenêtre d'un chunk couvre déjà toute la hauteur du monde, du plancher au plafond.
 *
 * <p><b>Deux — on renonce devant un processeur étranger.</b> Un processeur venu d'un mod pourrait,
 * lui, déplacer un bloc horizontalement. On ne peut pas le savoir sans le lire, donc on ne trie pas
 * quand un processeur n'est pas déclaré dans {@code net.minecraft}. La réponse est mémorisée par
 * instance : c'est une lecture de table, pas une réflexion, à chaque appel.
 *
 * <p><b>Trois — on renonce devant {@code finalizeProcessing}.</b> Un processeur qui redéfinit cette
 * méthode — {@code CappedProcessor} en est un, dans vanilla — reçoit la liste <em>entière</em> et
 * décide en la regardant dans son ensemble : lui en donner un quart changerait son verdict. Ce
 * garde-fou-là vient du mod d'origine, et il est juste.
 *
 * <h2>Et le quatrième, qui n'a rien d'une précaution</h2>
 *
 * <p>Si le tri ne laisse rien, on réinsère le premier bloc de la liste d'origine. Sans cela,
 * {@code placeInWorld} rendrait {@code false}, et l'appelant <b>retire alors la pièce du
 * {@code StructureStart}</b> — ce qui l'empêcherait de se poser dans les <em>autres</em> chunks
 * qu'elle devait encore traverser. Le mod d'origine porte ici un commentaire en majuscules ; il a
 * manifestement payé pour l'apprendre.
 *
 * <h2>Ce que ça vaut, mesuré</h2>
 *
 * <p>Épreuve du maçon ({@code LANTERNE_MASON=1}), sur {@code plains_big_house_1} — sept sur onze sur
 * onze, huit cent quarante-sept positions — posée à cheval sur une fenêtre de chunk, de sorte que
 * <b>68,8 % des blocs tombent dehors</b>. Médianes de quatre cents poses par côté, alternées, trois
 * exécutions :
 *
 * <pre>
 * gabarit nu                                        ×1,17  ×1,10  ×1,18
 * avec les deux processeurs de toute pièce à jigsaw ×1,54  ×1,32  ×1,30
 * </pre>
 *
 * <p>Le second régime est le seul qui décrive quelque chose de réel : {@code SinglePoolElement.place}
 * ajoute {@code BlockIgnoreProcessor.STRUCTURE_BLOCK} et {@code JigsawReplacementProcessor.INSTANCE}
 * à <em>chaque</em> pièce de village, d'avant-poste, de bastion ou de cité ancienne — et la liste du
 * <em>template pool</em> vient encore par-dessus. Le premier est une borne basse qui n'existe nulle
 * part dans le jeu.
 *
 * <p><b>Et ce gain ne va pas au temps de tick.</b> Ce laboratoire a déjà établi que la génération de
 * terrain s'exécute sur le pool de travail et ne coûte rien au fil du serveur : ce qu'on gagne ici
 * est de la <em>latence d'exploration</em>, c'est-à-dire le temps qu'un joueur qui avance attend son
 * chunk. C'est réel, c'est ressenti, et ce n'est pas des TPS. Le README ne doit pas les confondre.
 */
public final class Stencil {
    /**
     * Sûreté d'un processeur, retenue par instance.
     *
     * <p>Les instances de processeurs sont partagées et peu nombreuses — une par entrée de
     * {@code StructureProcessorList} de datapack. La table reste donc minuscule. Elle est concurrente
     * parce que la génération de terrain tourne sur le <b>pool de travail</b>, et non sur le fil du
     * serveur : c'est le premier module de ce mod dans ce cas.
     */
    private static final Map<StructureProcessor, Boolean> SAFE = new ConcurrentHashMap<>();

    /** Blocs de gabarit examinés, tous appels confondus. */
    private static long seen;
    /** Blocs écartés avant préparation. */
    private static long trimmed;
    /** Appels où le tri n'a pas pu s'appliquer. */
    private static long declined;

    private Stencil() {}

    /**
     * Rend la liste réduite à ce qui tombe dans la fenêtre, ou la liste d'origine.
     *
     * <p>On ne fabrique une liste neuve que si l'on a effectivement écarté quelque chose : une
     * structure entièrement contenue dans un seul chunk — un puits de village, un iglou — ne doit pas
     * payer une copie pour n'écarter personne.
     */
    public static List<StructureTemplate.StructureBlockInfo> inWindow(
            StructureTemplate.Palette palette, BlockPos anchor, StructurePlaceSettings settings) {
        List<StructureTemplate.StructureBlockInfo> all = palette.blocks();
        if (!Settings.stencil()) {
            return all;
        }
        BoundingBox window = settings.getBoundingBox();
        if (window == null || all.isEmpty()) {
            return all;
        }
        for (StructureProcessor processor : settings.getProcessors()) {
            if (!SAFE.computeIfAbsent(processor, Stencil::judge)) {
                declined++;
                return all;
            }
        }

        int minX = window.minX() - anchor.getX();
        int maxX = window.maxX() - anchor.getX();
        int minZ = window.minZ() - anchor.getZ();
        int maxZ = window.maxZ() - anchor.getZ();

        List<StructureTemplate.StructureBlockInfo> kept = null;
        int examined = 0;
        for (int i = 0; i < all.size(); i++) {
            StructureTemplate.StructureBlockInfo info = all.get(i);
            examined++;
            // La position transformée, et non la position brute : le gabarit est posé avec une
            // rotation et un miroir, et c'est APRÈS eux que la fenêtre le découpe.
            BlockPos placed = StructureTemplate.calculateRelativePosition(settings, info.pos());
            boolean inside = placed.getX() >= minX && placed.getX() <= maxX
                    && placed.getZ() >= minZ && placed.getZ() <= maxZ;
            if (inside) {
                if (kept != null) {
                    kept.add(info);
                }
            } else if (kept == null) {
                // Premier écarté : on matérialise la liste, et l'on y remet ce qui précède.
                kept = new ArrayList<>(all.size());
                for (int done = 0; done < i; done++) {
                    kept.add(all.get(done));
                }
            }
        }

        seen += examined;
        if (kept == null) {
            return all;
        }
        trimmed += examined - kept.size();
        if (kept.isEmpty()) {
            // Voir la javadoc de la classe : une liste vide fait retirer la pièce du StructureStart.
            kept.add(all.get(0));
        }
        return kept;
    }

    /**
     * Un processeur est sûr s'il vient de vanilla et ne regarde pas la liste dans son ensemble.
     *
     * <p>La seconde condition se lit par réflexion, une fois par instance : {@code finalizeProcessing}
     * est déclarée sur {@code StructureProcessor} avec un corps qui rend son argument tel quel. Si la
     * classe qui la déclare n'est plus celle-là, le processeur la redéfinit.
     */
    private static boolean judge(StructureProcessor processor) {
        Class<?> type = processor.getClass();
        if (!type.getName().startsWith("net.minecraft.")) {
            return false;
        }
        try {
            return type.getMethod("finalizeProcessing",
                    net.minecraft.world.level.ServerLevelAccessor.class,
                    BlockPos.class, BlockPos.class, List.class, List.class,
                    StructurePlaceSettings.class).getDeclaringClass() == StructureProcessor.class;
        } catch (NoSuchMethodException absent) {
            // La signature a changé sous nos pieds : on ne trie pas plutôt que de trier à tort.
            return false;
        }
    }

    public static long seen() {
        return seen;
    }

    public static long trimmed() {
        return trimmed;
    }

    public static long declined() {
        return declined;
    }

    public static void reset() {
        seen = 0L;
        trimmed = 0L;
        declined = 0L;
    }
}
