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
 * <h2>⚠ CE RAISONNEMENT N'EST PLUS VRAI EN 26.2 SOUS NEOFORGE — et les chiffres ci-dessous non plus</h2>
 *
 * <p>Le pseudo-code ci-dessus est celui de la 26.1.2. La 26.2 patchée NeoForge écrit autre chose,
 * et il faut la lire en entier
 * ({@code StructureTemplate.java}, lignes 445-458) :
 *
 * <pre>
 * boolean processOnlyInCurrentChunk = true;
 * for (StructureProcessor processor : settings.getProcessors()) {
 *     if (processor.evaluatesEntirePieceState()) { processOnlyInCurrentChunk = false; break; }
 * }
 * BoundingBox chunkBb = settings.getBoundingBox();
 * for (StructureBlockInfo blockInfo : blockInfoList) {
 *     BlockPos blockPos = calculateRelativePosition(settings, blockInfo.pos).offset(position);
 *     if (!processOnlyInCurrentChunk || chunkBb == null || chunkBb.isInside(blockPos)) {   // ← ICI
 *         StructureBlockInfo processed = new StructureBlockInfo(blockPos, blockInfo.state,
 *                 blockInfo.nbt != null ? blockInfo.nbt.copy() : null);
 *         …toute la chaîne des processeurs…
 *     }
 * }
 * </pre>
 *
 * <p><b>L'objet neuf, la copie de balise et la chaîne de processeurs sont déjà derrière le test de
 * fenêtre.</b> Le pochoir n'épargne donc plus ce qu'il a été écrit pour épargner : il ne reste que
 * la transformation de position — et il l'ajoute une fois de plus, puisque {@code processBlockInfos}
 * la refait ligne 457 pour les blocs qui passent.
 *
 * <p>Le garde-fou {@code evaluatesEntirePieceState} de NeoForge déclenche d'ailleurs sur le même
 * ensemble que le garde-fou trois ci-dessous : {@code CappedProcessor} est le seul processeur de
 * vanilla à redéfinir {@code finalizeProcessing}, et le seul à rendre {@code true}. Le pochoir est
 * un sous-ensemble strict du comportement de NeoForge, en plus prudent.
 *
 * <h2>La mesure, refaite sur 26.2 / NeoForge 26.2.0.88</h2>
 *
 * <pre>
 * gabarit nu          : 0,210 ms sans · 0,195 ms avec   →  ×1,07
 * gabarit + 2 procs   : 0,156 ms sans · 0,148 ms avec   →  ×1,06
 * tri : 326 400 blocs examinés, 224 400 écartés (68,8 %), 0 renoncement
 * conformité : 15 360 positions sur quatre orientations, AUCUN écart
 * </pre>
 *
 * <p>Les deux régimes sont <b>sous la dérive de ×1,08 du laboratoire</b>. Le module fonctionne — le
 * taux d'écart est toujours de 68,8 % — et ce qu'il écarte ne coûte plus rien, parce que le jeu
 * l'écarte déjà.
 *
 * <p><b>Les ×1,17 et ×1,54 annoncés plus bas sont ceux de la 26.1.2 et ne doivent plus être
 * republiés.</b> Ils sont conservés ici parce qu'ils sont vrais de la version où ils ont été pris, et
 * parce que la comparaison est l'information : c'est le même banc, le même gabarit, la même fenêtre,
 * et Mojang-NeoForge a fait le travail entre les deux.
 *
 * <p>Le module n'est pas retiré, pour une seule raison : il ne <em>perd</em> rien — les deux mesures
 * penchent du bon côté, faiblement et dans le même sens. Il devient en revanche un candidat au
 * retrait, et la question à trancher n'est pas « rapporte-t-il ? » mais « justifie-t-il un point
 * d'accroche sur {@code placeInWorld} ? ».
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
 * <b>68,8 % des blocs tombent dehors</b>. Médianes de quatre cents poses par côté, alternées, cinq
 * exécutions :
 *
 * <pre>
 * gabarit nu                                        ×1,17  ×1,10  ×1,18  ×1,16  ×1,17
 * avec les deux processeurs de toute pièce à jigsaw ×1,54  ×1,32  ×1,30  ×1,52  ×1,52
 * </pre>
 *
 * <p>Et la conformité, qui comptait plus que la vitesse : <b>15 360 positions comparées sur quatre
 * orientations, aucun écart</b>. Le pochoir bâtit exactement ce que vanilla bâtit — y compris sous
 * rotation et miroir, qui sont les seuls cas où une erreur dans le calcul de la position transformée
 * se verrait.
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
     * Sûreté d'un processeur, retenue par CLASSE et non par instance.
     *
     * <p>Le mod d'origine mémorise par instance, et c'est ce qu'on avait écrit d'abord. Or les deux
     * questions posées — le processeur vient-il de vanilla, redéfinit-il {@code finalizeProcessing}
     * — ne dépendent que de sa <b>classe</b>. Retenir par instance n'apporte donc rien, et expose à
     * une table qui grossit sans fin si quelqu'un fabrique des processeurs à la volée : un
     * {@code BlockRotProcessor} neuf par appel suffirait.
     *
     * <p>Par classe, la table est bornée par le nombre de types chargés — une poignée — et ne peut
     * pas fuir. Elle est concurrente parce que la génération de terrain tourne sur le <b>pool de
     * travail</b>, et non sur le fil du serveur : c'est le premier module de ce mod dans ce cas.
     */
    private static final Map<Class<?>, Boolean> SAFE = new ConcurrentHashMap<>();

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
            if (!SAFE.computeIfAbsent(processor.getClass(), Stencil::judge)) {
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
    private static boolean judge(Class<?> type) {
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
