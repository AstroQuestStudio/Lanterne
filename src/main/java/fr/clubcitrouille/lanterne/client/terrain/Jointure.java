package fr.clubcitrouille.lanterne.client.terrain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3fc;

import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ColorCollection;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

import fr.clubcitrouille.lanterne.Lanterne;
import fr.clubcitrouille.lanterne.core.ClientConfig;

/**
 * La texture connectee (CTM, façon Continuity/OptiFine) du verre plein.
 *
 * <h2>Le problème réel</h2>
 *
 * <p>{@code glass.png} et les seize {@code X_stained_glass.png} portent chacun un cadre d'UN
 * pixel de bord, plus opaque que le remplissage central — vérifié par dump ARGB brut (voir le
 * Javadoc de {@code tools/GenerateJointureTextures.java}). Un mur de blocs identiques affiche
 * donc une grille : chaque bloc garde son cadre complet même collé à un voisin du même type,
 * exactement ce que le joueur signale.
 *
 * <h2>La technique retenue, et pourquoi pas le blob à 47 tuiles</h2>
 *
 * <p>Variante SIMPLIFIÉE à 16 tuiles : 4 bits (Haut/Droite/Bas/Gauche), un par voisin immédiat de
 * la face examinée — pas les 8 voisins (4 arêtes + 4 coins) du blob à 47 tuiles complet. Un coin
 * partagé par deux bords connectés s'efface DÈS QUE L'UN DES DEUX bords l'est (voir
 * {@code tools/GenerateJointureTextures.java#clearBorder}), sans regarder le voisin diagonal —
 * la simplification assumée par « la plupart des packs CTM » citée dans la demande. Choisie pour
 * la sûreté et le temps disponible ce soir : 16 textures par couleur plutôt que 47, une seule
 * comparaison par bord plutôt qu'un arbre de cas par motif de coin.
 *
 * <h2>Le vrai point d'accroche, vérifié par bytecode (jamais supposé)</h2>
 *
 * <p>{@code javap -p -c} sur le vrai jar patché confirme que ce moteur a remplacé
 * {@code BakedModel}/{@code IDynamicBakedModel} par un système {@code BlockStateModel}
 * (paquet {@code net.minecraft.client.renderer.block.dispatch}) : {@code ModelBlockRenderer}
 * appelle {@code BlockStateModel.collectParts(BlockAndTintGetter, BlockPos, BlockState,
 * RandomSource, List<BlockStateModelPart>)} — la surcharge à CINQ arguments, avec le niveau et la
 * position du bloc — puis {@code BlockStateModelPart.getQuads(Direction)} pour chaque face.
 * Cette surcharge à cinq arguments est un DÉFAUT de {@code BlockStateModelExtension} (NeoForge,
 * {@code net.neoforged.neoforge.client.extensions}) qui délègue par défaut à la surcharge à deux
 * arguments (sans contexte) — {@code javap} sur cette interface confirme le corps exact :
 * {@code self().collectParts(random, output)}. Un modèle qui la surcharge reçoit donc le niveau
 * et la position à CHAQUE bloc maillé, sans mixin sur {@code SectionCompiler} ni sur quoi que ce
 * soit du pipeline de maillage déjà retravaillé ce soir (voir {@link Greedy},
 * {@link TerrainVertexFormat}) — ce module ne les touche ni ne les lit.
 *
 * <p>Les modèles bakés eux-mêmes sont substituables via {@code ModelEvent.ModifyBakingResult}
 * (NeoForge) : {@code event.getBakingResult().blockStateModels()} est la {@code Map<BlockState,
 * BlockStateModel>} que {@code ModelBakery} construit — vérifié par {@code javap -c} sur
 * {@code ModelBakery.lambda$bakeModels$4} que cette table provient de
 * {@code ParallelMapTransform.schedule(...)}, dont le cas général (plusieurs entrées — vérifié sur
 * {@code SingleTaskSplitter.lambda$scheduleFinalOperation$0}) construit un {@code new HashMap<>}
 * ordinaire rempli par {@code Container.copyOut}, jamais enveloppé dans
 * {@code Collections.unmodifiableMap}/{@code Map.copyOf} avant d'atteindre {@code BakingResult} —
 * une map MUTABLE, {@code .put()} dessus remplace donc bien le modèle utilisé au rendu.
 *
 * <h2>Pourquoi les UV ne peuvent pas rester ceux du quad vanilla</h2>
 *
 * <p>Vérifié en lisant {@link TerrainVertexFormat#putQuad} (même dépôt, écrit plus tôt ce soir
 * pour un autre chantier) : {@code BakedQuad.packedUV(i)} contient déjà des coordonnées EN ESPACE
 * ATLAS (celles que {@code VertexConsumer.setUv} reçoit telles quelles) — pas des coordonnées
 * relatives au sprite recalculées au dessin. Remplacer seulement {@code MaterialInfo.sprite()}
 * sans recalculer {@code packedUV} laisserait donc le quad échantillonner l'ANCIEN rectangle
 * d'atlas, visuellement inchangé. {@link #rebuildQuad} reconstruit les quatre UV à partir du
 * sprite d'ORIGINE ({@code quad.materialInfo().sprite()}, qui expose {@code getU0/getU1/getV0/
 * getV1}) et du nouveau, en faisant correspondre chaque sommet au coin le plus proche (bas coût
 * puisqu'un {@code cube_all} mappe déjà chaque face sur le rectangle ENTIER du sprite, jamais un
 * sous-rectangle) — vérifié, jamais deviné, par lecture de {@code assets/minecraft/models/block/
 * glass.json} (parent {@code block/cube_all}) dans le jar réel.
 *
 * <h2>Pourquoi le sens Haut/Droite/Bas/Gauche est calculé, pas supposé</h2>
 *
 * <p>{@link #deriveEdgeMap} lit la GÉOMÉTRIE RÉELLE du quad vanilla baké (position ET UV des 4
 * sommets) pour chaque face, plutôt que de faire confiance à une convention UV de cube supposée
 * de mémoire — un pari risqué vu les rotations spéciales que vanilla applique historiquement aux
 * faces UP/DOWN. Pour chaque direction perpendiculaire à la face, les deux sommets à l'extrémité
 * géométrique correspondante déterminent si c'est U ou V qui reste constant entre eux (donc si
 * c'est un bord colonne ou un bord rangée), et de quel côté (proche de {@code getU0()} ou
 * {@code getU1()}, etc.) — une dérivation qui s'auto-vérifie sur CE jar à CHAQUE chargement de
 * ressources, jamais périmée par un futur changement de convention vanilla. Repli sûr si la
 * géométrie ne correspond pas aux hypothèses (pas exactement deux sommets à l'extrémité, ni U ni
 * V constant) : cette face reste inchangée (bitmask 0, texture vanilla), jamais une supposition.
 *
 * <h2>Périmètre : le verre plein, pas les vitres</h2>
 *
 * <p>{@code glass_pane}/{@code X_stained_glass_pane} sont un modèle MULTIPART (poteau + jusqu'à
 * quatre bras N/E/S/O, cinq fichiers de modèle par couleur — vérifié en listant les assets réels
 * du jar) où chaque pièce échantillonne la MÊME texture {@code glass_pane_top.png} à des UV
 * différents. Leur connexion de FORME (les bras qui rejoignent un voisin) est DÉJÀ celle de
 * vanilla, gérée par les propriétés {@code north/east/south/west} du blockstate — un sujet
 * séparé de la connexion de TEXTURE ajoutée ici, non touché. Les connecter en texture demanderait
 * de remapper les UV de chaque pièce séparément (pas un simple remplacement de sprite par face
 * pleine comme pour {@code cube_all}) : un chantier à part, documenté ici plutôt que livré à la
 * hâte sur la géométrie la plus complexe du lot — voir la consigne de prudence de ce chantier.
 *
 * <h2>Zéro chevauchement avec {@link Greedy}</h2>
 *
 * <p>{@link Greedy#accept} ne retient que les quads {@code UP}/{@code DOWN} de la couche
 * {@link net.minecraft.client.renderer.chunk.ChunkSectionLayer#SOLID}. Le verre est
 * {@code force_translucent: true} dans son modèle vanilla (vérifié dans
 * {@code assets/minecraft/models/block/glass.json}) — couche {@code TRANSLUCENT}, jamais
 * {@code SOLID}. Ce module et la fusion de faces ne peuvent donc jamais se disputer le même quad.
 */
@EventBusSubscriber(modid = Lanterne.ID, value = Dist.CLIENT)
public final class Jointure {
    /** bit0=HAUT, bit1=DROITE, bit2=BAS, bit3=GAUCHE — DOIT rester identique, bit à bit, à
     * {@code tools/GenerateJointureTextures.TOP/RIGHT/BOTTOM/LEFT} : une divergence ferait choisir
     * la texture du mauvais bord. */
    static final int TOP = 1;
    static final int RIGHT = 2;
    static final int BOTTOM = 4;
    static final int LEFT = 8;

    private static final float EPS = 1.0e-4f;

    /** Bloc -> nom de fichier de texture vanilla (sans extension), qui est aussi le nom de
     * dossier sous {@code assets/lanterne/textures/block/jointure/} produit par
     * {@code tools/GenerateJointureTextures.java}. */
    private static final Map<Block, String> FAMILY_OF = new HashMap<>();

    static {
        FAMILY_OF.put(Blocks.GLASS, "glass");
        FAMILY_OF.put(Blocks.TINTED_GLASS, "tinted_glass");
        // Les seize couleurs ne sont PAS des champs individuels sur Blocks dans ce moteur — verifie
        // par javap (cannot find symbol sur Blocks.WHITE_STAINED_GLASS et consorts) : elles vivent
        // dans Blocks.STAINED_GLASS, un ColorCollection<Block>. ColorCollection.VALUES est la MEME
        // structure de donnees appliquee a DyeColor — ses seize champs sont donc dans le MEME ordre
        // que ceux de STAINED_GLASS (meme record, meme position par couleur), asList() les rend donc
        // valides a associer index a index.
        List<Block> stainedGlass = Blocks.STAINED_GLASS.asList();
        List<DyeColor> colors = ColorCollection.VALUES.asList();
        for (int i = 0; i < stainedGlass.size() && i < colors.size(); i++) {
            FAMILY_OF.put(stainedGlass.get(i), colors.get(i).getSerializedName() + "_stained_glass");
        }
    }

    private Jointure() {}

    /**
     * Remplace, pour chaque état de bloc éligible, le {@code BlockStateModel} vanilla par une
     * enveloppe consciente du voisinage — voir le Javadoc de classe pour le point d'accroche.
     *
     * <p>Enveloppe entièrement dans un {@code try/catch} PAR ÉTAT : une géométrie inattendue sur
     * UNE couleur (mod tiers qui la retexture autrement, par exemple) ne doit jamais empêcher les
     * dix-sept autres de fonctionner, ni faire échouer le chargement des ressources tout entier.
     */
    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        if (!ClientConfig.JOINTURE.get()) {
            return;
        }
        ModelBakery.BakingResult result = event.getBakingResult();
        Map<BlockState, BlockStateModel> models = result.blockStateModels();
        java.util.function.Function<Identifier, TextureAtlasSprite> textureGetter = event.getTextureGetter();

        int wrapped = 0;
        for (Map.Entry<Block, String> fam : FAMILY_OF.entrySet()) {
            Block block = fam.getKey();
            String family = fam.getValue();
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                BlockStateModel original = models.get(state);
                if (original == null) {
                    continue;
                }
                try {
                    BlockStateModel wrappedModel = wrap(original, block, family, textureGetter);
                    if (wrappedModel != null) {
                        models.put(state, wrappedModel);
                        wrapped++;
                    }
                } catch (RuntimeException ex) {
                    Lanterne.LOG.warn("[JOINTURE] {} non connecte (geometrie/texture inattendue) : {}",
                            family, ex.toString());
                }
            }
        }
        if (wrapped > 0) {
            Lanterne.LOG.info("[JOINTURE] texture connectee armee sur {} etat(s) de bloc.", wrapped);
        }
    }

    /** @return le modèle enveloppé, ou {@code null} si aucune face n'a pu être dérivée (rien à gagner). */
    private static BlockStateModel wrap(BlockStateModel original, Block family, String familyName,
            java.util.function.Function<Identifier, TextureAtlasSprite> textureGetter) {
        List<BlockStateModelPart> baseParts = new ArrayList<>();
        original.collectParts(RandomSource.create(0L), baseParts);
        if (baseParts.size() != 1) {
            // cube_all vanilla n'a qu'une seule pièce ; un modèle retexturé par un autre mod en a
            // peut-être plusieurs — repli sûr, on ne touche pas à ce qu'on ne reconnaît pas.
            return null;
        }
        BlockStateModelPart basePart = baseParts.get(0);

        @SuppressWarnings("unchecked")
        List<BakedQuad>[][] quads = new List[Direction.values().length][16];
        Map<Direction, Map<Direction, Integer>> edgeMaps = new EnumMap<>(Direction.class);
        boolean anySupported = false;

        for (Direction face : Direction.values()) {
            List<BakedQuad> baseQuads = basePart.getQuads(face);
            for (int mask = 0; mask < 16; mask++) {
                quads[face.ordinal()][mask] = baseQuads; // repli par defaut : texture vanilla inchangee
            }
            if (baseQuads.size() != 1) {
                continue;
            }
            BakedQuad baseQuad = baseQuads.get(0);
            Map<Direction, Integer> edgeMap = deriveEdgeMap(face, baseQuad);
            if (edgeMap == null) {
                continue;
            }
            edgeMaps.put(face, edgeMap);
            for (int mask = 0; mask < 16; mask++) {
                Identifier spriteId = Identifier.fromNamespaceAndPath(Lanterne.ID,
                        "block/jointure/" + familyName + "/" + mask);
                TextureAtlasSprite sprite = textureGetter.apply(spriteId);
                if (sprite == null) {
                    continue; // repli deja pose : texture vanilla pour ce masque
                }
                quads[face.ordinal()][mask] = List.of(rebuildQuad(baseQuad, sprite));
            }
            anySupported = true;
        }

        if (!anySupported) {
            return null;
        }
        return new JointureModel(original, family, basePart, quads, edgeMaps);
    }

    /**
     * Détermine, pour la face {@code face} d'un cube plein, quel bit de bord (voir
     * {@link #TOP}/{@link #RIGHT}/{@link #BOTTOM}/{@link #LEFT}) correspond à chacune des quatre
     * directions perpendiculaires — en lisant la géométrie RÉELLE du quad vanilla baké, jamais une
     * convention supposée. Voir le Javadoc de classe pour le raisonnement complet.
     *
     * @return {@code null} si la géométrie ne correspond pas aux hypothèses d'un quad de face
     *         pleine non tournée (repli sûr : cette face reste alors texturée en vanilla).
     */
    private static Map<Direction, Integer> deriveEdgeMap(Direction face, BakedQuad quad) {
        TextureAtlasSprite sprite = quad.materialInfo().sprite();
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        float midU = (u0 + u1) / 2.0f;
        float midV = (v0 + v1) / 2.0f;

        Map<Direction, Integer> map = new EnumMap<>(Direction.class);
        for (Direction edgeDir : Direction.values()) {
            if (edgeDir.getAxis() == face.getAxis()) {
                continue; // c'est la face elle-même ou son opposée, pas un bord dans son plan
            }
            Direction.Axis axis = edgeDir.getAxis();
            boolean positive = edgeDir.getAxisDirection() == Direction.AxisDirection.POSITIVE;

            float[] comps = new float[4];
            float extreme = positive ? -Float.MAX_VALUE : Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                comps[i] = componentOf(quad.position(i), axis);
                extreme = positive ? Math.max(extreme, comps[i]) : Math.min(extreme, comps[i]);
            }
            List<Integer> idx = new ArrayList<>(2);
            for (int i = 0; i < 4; i++) {
                if (Math.abs(comps[i] - extreme) < EPS) {
                    idx.add(i);
                }
            }
            if (idx.size() != 2) {
                return null;
            }

            float ua = UVPair.unpackU(quad.packedUV(idx.get(0)));
            float ub = UVPair.unpackU(quad.packedUV(idx.get(1)));
            float va = UVPair.unpackV(quad.packedUV(idx.get(0)));
            float vb = UVPair.unpackV(quad.packedUV(idx.get(1)));
            boolean uConst = Math.abs(ua - ub) < EPS;
            boolean vConst = Math.abs(va - vb) < EPS;

            int bit;
            if (uConst && !vConst) {
                bit = ua < midU ? LEFT : RIGHT;
            } else if (vConst && !uConst) {
                bit = va < midV ? TOP : BOTTOM;
            } else {
                return null;
            }
            map.put(edgeDir, bit);
        }
        return map.size() == 4 ? map : null;
    }

    private static float componentOf(Vector3fc v, Direction.Axis axis) {
        return switch (axis) {
            case X -> v.x();
            case Y -> v.y();
            case Z -> v.z();
        };
    }

    /**
     * Reconstruit un quad identique en géométrie/couleur/lumière, mais dont les quatre UV pointent
     * vers {@code newSprite} plutôt que vers le sprite d'origine — voir le Javadoc de classe pour
     * pourquoi {@code packedUV} doit être recalculé et ne peut pas rester tel quel.
     */
    private static BakedQuad rebuildQuad(BakedQuad base, TextureAtlasSprite newSprite) {
        TextureAtlasSprite origSprite = base.materialInfo().sprite();
        float ou0 = origSprite.getU0();
        float ou1 = origSprite.getU1();
        float ov0 = origSprite.getV0();
        float ov1 = origSprite.getV1();
        float nu0 = newSprite.getU0();
        float nu1 = newSprite.getU1();
        float nv0 = newSprite.getV0();
        float nv1 = newSprite.getV1();

        long[] uv = new long[4];
        for (int i = 0; i < 4; i++) {
            float u = UVPair.unpackU(base.packedUV(i));
            float v = UVPair.unpackV(base.packedUV(i));
            boolean uHigh = Math.abs(u - ou1) < Math.abs(u - ou0);
            boolean vHigh = Math.abs(v - ov1) < Math.abs(v - ov0);
            uv[i] = UVPair.pack(uHigh ? nu1 : nu0, vHigh ? nv1 : nv0);
        }

        BakedQuad.MaterialInfo old = base.materialInfo();
        BakedQuad.MaterialInfo info = new BakedQuad.MaterialInfo(newSprite, old.layer(),
                old.itemRenderType(), old.itemGlintRenderType(), old.itemGlintSpecialRenderType(),
                old.tintIndex(), old.shadeDirectionOverride(), old.lightEmission(), old.ambientOcclusion());
        return new BakedQuad(base.position0(), base.position1(), base.position2(), base.position3(),
                uv[0], uv[1], uv[2], uv[3], base.direction(), info, base.bakedNormals(), base.bakedColors());
    }

    /**
     * L'enveloppe posée à la place du modèle vanilla — voir le Javadoc de classe pour le point
     * d'accroche exact.
     */
    private static final class JointureModel implements BlockStateModel {
        private final BlockStateModel original;
        private final Block family;
        private final BlockStateModelPart basePart;
        private final List<BakedQuad>[][] quads;
        private final Map<Direction, Map<Direction, Integer>> edgeMaps;

        JointureModel(BlockStateModel original, Block family, BlockStateModelPart basePart,
                List<BakedQuad>[][] quads, Map<Direction, Map<Direction, Integer>> edgeMaps) {
            this.original = original;
            this.family = family;
            this.basePart = basePart;
            this.quads = quads;
            this.edgeMaps = edgeMaps;
        }

        /** Chemin SANS contexte (objet tenu en main, rendu d'item...) : vanilla, inchangé. */
        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
            original.collectParts(random, output);
        }

        /**
         * Chemin AVEC contexte — celui que {@code ModelBlockRenderer} appelle réellement pour le
         * terrain (voir le Javadoc de classe). Calcule le bitmask de connexion par face à partir
         * des VRAIS voisins de CE bloc, choisit le quad pré-baké correspondant (aucun calcul de
         * texture ici, seulement une lecture de tableau), et pose une seule pièce.
         */
        @Override
        public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state,
                RandomSource random, List<BlockStateModelPart> output) {
            @SuppressWarnings("unchecked")
            List<BakedQuad>[] chosen = new List[Direction.values().length];
            for (Direction face : Direction.values()) {
                Map<Direction, Integer> edgeMap = edgeMaps.get(face);
                int mask = 0;
                if (edgeMap != null) {
                    for (Map.Entry<Direction, Integer> e : edgeMap.entrySet()) {
                        BlockState neighbor = level.getBlockState(pos.relative(e.getKey()));
                        if (neighbor.getBlock() == family) {
                            mask |= e.getValue();
                        }
                    }
                }
                chosen[face.ordinal()] = quads[face.ordinal()][mask];
            }
            output.add(new JointurePart(chosen, basePart));
        }

        @Override
        public Material.Baked particleMaterial() {
            return original.particleMaterial();
        }

        @Override
        public int materialFlags() {
            return original.materialFlags();
        }
    }

    /** Les quads choisis pour UN bloc réel, à SA position — tout le reste délégué à la pièce
     * vanilla d'origine (occlusion ambiante, matériau de particule, drapeaux). */
    private static final class JointurePart implements BlockStateModelPart {
        private final List<BakedQuad>[] byDirection;
        private final BlockStateModelPart original;

        JointurePart(List<BakedQuad>[] byDirection, BlockStateModelPart original) {
            this.byDirection = byDirection;
            this.original = original;
        }

        @Override
        public List<BakedQuad> getQuads(Direction direction) {
            if (direction == null) {
                return original.getQuads(null);
            }
            return byDirection[direction.ordinal()];
        }

        @Override
        public boolean useAmbientOcclusion() {
            return original.useAmbientOcclusion();
        }

        @Override
        public Material.Baked particleMaterial() {
            return original.particleMaterial();
        }

        @Override
        public int materialFlags() {
            return original.materialFlags();
        }
    }
}
