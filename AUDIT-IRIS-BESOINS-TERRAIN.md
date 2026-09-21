# Audit Iris : ce que le terrain doit fournir pour se passer de Sodium

Date : 21/09/2026. Périmètre : `src/main/java/net/irisshaders` (Iris, LGPL-3.0, déjà dans le dépôt), dépôt `Lanterne`, branche `26.3`, Minecraft 26.3 / NeoForge 26.3.0.7-beta. Aucun code Java n'a été modifié. Toutes les affirmations ci-dessous sont sourcées par `fichier:ligne`, chemins relatifs à `net/irisshaders/iris/` sauf mention contraire.

## 0. Résumé (à lire d'abord)

1. **Sodium n'est pas une bibliothèque qu'Iris appelle : c'est le moteur de terrain dans lequel Iris s'injecte.** Sur les 192 références `net.caffeinemc.*` (57 fichiers), 21 fichiers sont des mixins qui ciblent les classes internes de Sodium (`compat/sodium/mixin/*`, dont `MixinChunkMeshBuildTask` 15 réf., `MixinRenderSectionManagerShadow` 14, `MixinDefaultFluidRenderer` 13). Retirer Sodium = fournir un moteur de terrain qui offre les mêmes points d'accroche.
2. **Bonne nouvelle : Minecraft 26.3 a déjà un rendu de chunks moderne** (verifié par `javap` sur `minecraft-client-patched-26.3.0.7-beta.jar`) : `SectionCompiler.compile` (mailleur), `SectionRenderDispatcher$SectionUberBuffers` (`UberGpuBuffer` sommets/indices), `ChunkSectionsToRender` avec trois implémentations (`DrawIndirect`, `DrawSeparate`, `GpuMultiDrawIndexedIndirect`), `SectionOcclusionGraph`, tri des translucides (`ResortTransparencyTask`, `TranslucencyPointOfView`) et OIT (`ChunkSectionsToRender.renderOit`). Le moteur maison peut donc se greffer sur le vanilla plutôt que tout réécrire.
3. **Un chemin « vanilla » existe déjà à moitié dans Iris** : `IrisVertexFormats.TERRAIN` (`vertices/IrisVertexFormats.java:35-44`), le remplacement de format `DefaultVertexFormat.BLOCK → TERRAIN` (`mixin/MixinRenderPipeline.java`, et `mixin/vertices/MixinBufferBuilder.java:108-110`), les clés de shader `TERRAIN_SOLID/CUTOUT/TRANSLUCENT` (`pipeline/programs/ShaderKey.java:44-48`) associées à `RenderPipelines.SOLID_TERRAIN/CUTOUT_TERRAIN/TRANSLUCENT_TERRAIN` (`pipeline/IrisPipelines.java:25-29`) et `VanillaTransformer`. **Ce qui manque** : (a) l'appel `beginBlock(...)` du mailleur vanilla (seul `mixin/forge/MixinGameClientEvents.java:22` l'appelle, et pour un mod tiers) donc `mc_Entity`, `at_midBlock` etc. ne sont jamais remplis pour le terrain vanilla ; (b) tout le passage d'ombres (`shadows/ShadowRenderer.java:444-645` est écrit contre `SodiumWorldRenderer`) ; (c) le tri « traversée du graphe pour la caméra de l'ombre ».
4. **Vulkan** : Iris est entièrement GL (125 fichiers touchent `org.lwjgl.opengl`/`GlStateManager`/`IrisRenderSystem`) et se désactive sous Vulkan (`mixin/IrisMixinPlugin.java:114-117`, écran `compat/sodium/config/ShaderPackScreenPlaceholder.java:30` « Iris cannot run when using Vulkan »). C'est le message que tu vois en lançant en Vulkan. Il y a deux blocages indépendants : la couche GL d'Iris et la compilation GLSL→SPIR-V (bug natif shaderc sur le fragment de 99760 caractères, `RECHERCHE-VULKAN-SANS-SODIUM.md` étape 13). Le nouveau moteur de terrain doit être écrit contre les abstractions `com.mojang.renderpearl` (ce que fait déjà le vanilla), pas contre GL, pour être gratuitement compatible Vulkan.
5. **Licence** : Sodium est sous **PolyForm Shield 1.0.0** (`META-INF/neoforge.mods.toml` du jar : `license = "Polyform-Shield-1.0.0"`, confirmé par la fiche Modrinth). Le moteur maison est un produit concurrent : **ne rien recopier de Sodium**, et ne s'appuyer que sur des techniques génériques (multi-draw, régions, graphe de visibilité) et sur ce qu'Iris (LGPL) contient déjà. Point d'attention : `pipeline/transform/transformer/SodiumTransformer.java:144` porte le commentaire « translated from sodium's chunk_vertex.glsl » (décodage position 20 bits, texcoords 15 bits, etc.). Ce code est dans Iris depuis l'époque LGPL de Sodium ; à garder tel quel mais à **ne pas étendre par de nouveaux emprunts** ; le format de sommets du moteur maison devra être défini par nous (voir §2 : Iris possède déjà l'encodeur, `XHFPTerrainVertex`).

## 1. Cartographie des 192 références, par difficulté et ordre de migration

Comptage réel : `grep -rn caffeinemc net/irisshaders` = 192 lignes / 57 fichiers. Ordre de migration recommandé : palier 0 → 5.

### Palier 0 : utilitaires purs (remplaçables par du code local, 1 h)

| Fichier:ligne | Usage | Remplacement |
|---|---|---|
| `pbr/mipmap/ChannelMipmapGenerator.java:3`, `pbr/util/ImageManipulationUtil.java:4`, `targets/backed/NativeImageBackedSingleColorTexture.java:4-5`, `vertices/sodium/terrain/XHFPTerrainVertex.java:3-4` | `ColorABGR`, `ColorARGB` (opérations bit à bit sur des entiers couleur) | classe locale `fr.clubcitrouille.lanterne.render.util.ColorABGR/ColorARGB` (une vingtaine de fonctions statiques ; `NormI8` existe déjà dans `vertices/NormI8.java`) |
| `mixin/texture/pbr/MixinSpriteContents.java:3` | `SpriteUtil` (marquer un sprite « actif » pour l'animation) | mixin sur `SpriteContents` vanilla (l'API Sodium `SpriteUtil.markSpriteActive` a un équivalent vanilla : le sprite est mis à jour dans `SpriteContents.uploadFirstFrame/animation`) |

### Palier 1 : accesseurs d'état Sodium sur des objets vanilla (2-3 h, mixins simples)

| Fichier:ligne | Usage | Remplacement |
|---|---|---|
| `mixin/MixinLevelRenderer.java:18,115` | `GameRendererStorage.sodium$getProjectionMatrix()` pour `setGbufferProjection` | `@Accessor`/champ Lanterne sur `GameRenderer` qui capture la matrice de projection vanilla |
| `pathways/HandRenderer.java:9` | même accesseur (matrice de la main) | idem |
| `uniforms/FogUniforms.java:4`, `uniforms/IrisInternalUniforms.java:4-5`, `shadows/ShadowRenderer.java:17` | `FogStorage`, `FogParameters` (paramètres de brouillard capturés) | mixin sur `FogRenderer` vanilla qui expose les paramètres |
| `gui/debug/IrisDebugEntry.java:3`, `mixin/MixinDebugScreenEntriesList.java:3-4` | `SodiumWorldRenderer`, `SodiumClientMod`, `PlatformRuntimeInformation` pour les lignes F3 | remplacer par des lignes calculées depuis le moteur maison |
| `mixin/MixinWindow.java:10-11` | `SodiumClientMod`, `SodiumOptions` (options de fenêtre) | lire les options Lanterne |
| `mixin/IrisMixinPlugin.java:103` | détection `SodiumClientMod.class` (déjà en place, `sodiumPresent()`) | **conserver** : c'est la bascule « moteur Sodium / moteur maison » |
| `mixin/forge/MixinFluidRendererImpl.java:3-4` | cible `net.caffeinemc.mods.sodium.neoforge.render.FluidRendererImpl` | disparaît avec le mailleur Sodium (palier 5) |

### Palier 2 : frustums d'ombre (5 fichiers, 1 j)

`shadows/frustum/CullEverythingFrustum.java:3-4,11`, `shadows/frustum/fallback/NonCullingFrustum.java:3-4,13`, `shadows/frustum/fallback/BoxCullingFrustum.java:3-5,12`, `shadows/frustum/advanced/AdvancedShadowCullingFrustum.java:5-7,39`, `shadows/frustum/advanced/SafeZoneCullingFrustum.java:3` : chacun étend le `Frustum` vanilla **et** implémente l'interface `Frustum` de Sodium plus `ViewportProvider` (`sodium$createViewport()`), et `AdvancedShadowCullingFrustum`/`BoxCullingFrustum` importent `OcclusionCuller` (constantes de test de boîte). Remplacement : une interface Lanterne `TerrainFrustum` (test boîte-frustum sur une section 16³, voir §4) ; les cinq classes n'ont qu'à l'implémenter. La logique de culling elle-même (plans, boîte englobante orthographique) est du code Iris et se conserve.

### Palier 3 : sérialiseurs de sommets non-terrain (gating, 0,5 j)

`vertices/sodium/{CloudVertex:3-6, EntityToTerrainVertexSerializer:3-6, GlyphExtVertexSerializer:5-6, IrisEntityToTerrainVertexSerializer:3, ModelToEntityVertexSerializer:3-6, SodiumVertexSerializers:4}.java` : ils s'enregistrent dans `VertexSerializerRegistry` (API vertex de Sodium) pour accélérer entités, glyphes et nuages. Sans Sodium, le chemin vanilla existe déjà : `mixin/vertices/MixinBufferBuilder.java:101-120` étend le format pour ENTITY/GLYPH/TERRAIN. Action : ne pas charger ces classes sans Sodium (déjà couvert par `IrisSodiumGate`) et laisser le chemin `BufferBuilder`. Perte possible : performance des entités sous shaders (à mesurer, voir §5).

### Palier 4 : interface de configuration (1 j)

`compat/sodium/config/IrisConfig.java:5-15` (11 réf. : `ConfigEntryPoint`, `ConfigState`, `OptionFlag`, `OptionImpact`, `Range`, `VideoSettingsScreen`, thèmes…), `compat/sodium/mixin/{MixinSodiumOptions, MixinSodiumGameOptions, EnumOptionBuilderImplAccessor, IntegerOptionBuilderImplAccessor}`. Ils greffent l'onglet Iris dans l'écran vidéo de Sodium. Sans Sodium : l'onglet passe par le cadran Lanterne (`client/Dials.java`, déjà en place) et l'écran vanilla de shaderpacks. Aucun moteur de terrain requis.

### Palier 5 : le cœur (le vrai travail, environ 33 fichiers)

C'est ce que le moteur maison doit remplacer. Détail par fonction :

**5a. Format et encodeur de sommets du terrain** (fichiers Iris qui héritent des types Sodium) :
`vertices/sodium/terrain/FormatAnalyzer.java:7-8,14` (`ChunkMeshFormats.COMPACT`, `ChunkVertexType`), `XHFPModelVertexType.java:4-5` (`ChunkVertexType`, `ChunkVertexEncoder`), `XHFPTerrainVertex.java:5` (`ChunkVertexEncoder`), `compat/vertexformat/SodiumVertexFormatSource.java:4` (déjà abstrait par `VertexFormatSource`), `pipeline/VanillaRenderingPipeline.java:7,35` (`ChunkMeshFormats.COMPACT` : **le crash du client sans Sodium**), `mixin/MixinRenderPipeline.java:7` (comparaison de format), `compat/sodium/mixin/{MixinChunkMeshFormats, MixinChunkVertex, MixinChunkVertexConsumer, MixinArenaAggregator}`. Remplacement : le moteur maison définit `TerrainVertexType`/`TerrainVertexEncoder` (mêmes signatures) et Iris les implémente (voir §2, §4).

**5b. Collecte du contexte de bloc pendant le maillage** (donne `mc_Entity`, `at_midBlock`) :
`compat/sodium/mixin/MixinChunkMeshBuildTask.java` (cible `ChunkBuilderMeshingTask.execute`, injections ligne 32, 59, 66 : `beginBlock` pour bloc et fluide, blocs « light »), `MixinBlockRenderer.java:110-113` (`iris$setData` au moment d'écrire chaque sommet), `MixinDefaultFluidRenderer.java`, `MixinAbstractBlockRenderContext.java`, `MixinBlockRenderer.java:61-94` (surcharge du type de rendu solide/cutout/translucide selon `block.properties` du pack). Remplacement : mixins équivalents sur le mailleur vanilla (`SectionCompiler.compile`, lambdas `compile$0/1` qui émettent les `BakedQuad` vers le `BufferBuilder` de la couche) ; l'API `BlockSensitiveBufferBuilder.beginBlock/endBlock` (`vertices/BlockSensitiveBufferBuilder.java`) existe déjà côté `BufferBuilder`.

**5c. Régions, tampons et dessin** :
`compat/sodium/mixin/{MixinRenderRegion (6 réf.), MixinRenderRegionManager, MixinDefaultChunkRenderer (8), MixinShaderChunkRenderer, MixinUniformData, MixinSodiumWorldRenderer (7), MixinRenderSectionManager, MixinRenderSectionManagerShadow (14)}`, `mixinterface/ShadowRenderRegion.java:3`, `mixin/MixinRenderSection.java:3`, `pipeline/programs/ExtendedShader.java:9,139`. Fonction : dessin à deux jeux de listes (caméra / ombre), UBO séparé pour l'ombre, culling de faces désactivé en ombre (`MixinDefaultChunkRenderer.java:36-40`), échantillonneur forcé NEAREST/clamp quand un pack est actif (`:42-50`), liste de rendu et lots de multi-draw en cache par région et par jeu (`MixinRenderRegion.java` : `swapToShadowRenderList/swapToRegularRenderList`, `cachedBatches`).

**5d. Passage d'ombres** : `shadows/ShadowRenderer.java:12-19` (8 réf.), toutes les opérations §3.

## 2. Contrat de sommets du terrain

### 2.1 Format actuel « Sodium compact » (ce qu'Iris écrit aujourd'hui)

Source : `vertices/sodium/terrain/FormatAnalyzer.java:17-92` (déclaration) et `XHFPTerrainVertex.java:106-176` (encodage). Le décalage de base est 20 octets (`FormatAnalyzer.java:37`), suivi d'extensions optionnelles de 4 octets chacune (blockId → normal → midUV → midBlock, ordre `:41-67`) : **20 à 36 octets par sommet**.

| Attribut GLSL | Format | Offset | Contenu (source) |
|---|---|---|---|
| `a_Position` | `RG32_UINT` | 0 | position quantifiée 20 bits ×3, entrelacée hi/lo (`XHFPTerrainVertex.java:49-63,149-150`), plage `[-8, +24)` blocs (origine 8, étendue 32, `:18-21`) |
| `a_Color` | `RGBA8_UNORM` | 8 | couleur × AO, ou couleur + AO en alpha si `shouldUseSeparateAo()` (`:151`) |
| `a_TexCoord` | `RG16_UINT` | 12 | UV 15 bits + bit de signe du biais vers le centre du quad (`:65-80,144-145,152`) |
| `a_LightAndData` | `RGBA8_UINT` | 16 | lumière bloc/ciel sur x/y (`encodeLight`, `:82-86`), bit 16 = signe de la tangente, octet w = index de section dans la région (`packLightAndData`, `:96-100`) |
| `mc_Entity` | `R32_UINT` (option) | 20+ | `((blockId+1)<<1) | renderType&1` (`packBlockId`, `:195-197`) : renderType=1 pour un fluide |
| `iris_Normal` | `R32_UINT` (option) | | normale octaédrique 24 bits + octet de tangente (`NormalHelper.encodeNormalTangent`, `:130-131`) ; décodée dans le shader par `decodeOct24` / `decode_diamond_tangent_with_sign` (`SodiumTransformer.java:150-190`) |
| `mc_midTexCoord` | `RG16_UINT` (option) | | centroïde des UV du quad ×32768 (`XHFPModelVertexType.encodeOld`, `XHFPModelVertexType.java:32-35`) |
| `at_midBlock` | `RGBA8_SNORM` (option) | | position locale relative au centre du bloc (`ExtendedDataHelper.computeMidBlock`) + émission de lumière dans l'octet 3 (`:159-162`) |

`at_tangent` n'existe pas comme attribut : il est reconstruit dans le shader depuis `iris_Normal` (`SodiumTransformer.java:239`).

Décodage côté GPU (contrat que le moteur doit respecter tel quel, sinon le patcheur casse) : `SodiumTransformer.java:144-256` : `VERTEX_SCALE = 32.0/2^20`, `VERTEX_OFFSET = -8.0`, position finale = `deinterleave_u20x3(a_Position)*scale + offset + iris_RegionOffset + _get_draw_translation(_draw_id)` (`:90`) ; `_draw_id = a_LightAndData[3]` (`:240`) avec `_get_relative_chunk_coord` : x 3 bits (bits 5-7), y 2 bits (bits 0-1), z 3 bits (bits 2-4) donc **256 sections maximum par région (8×4×8)** (`:247-253`).

Uniformes/tampons attendus par ce contrat (`SodiumTransformer.java:100-112,135-140`) : UBO `u_Globals` (projection, modèle-vue, brouillard, `u_TexelSize`, `u_TexCoordShrink`, `u_FadePeriodInv`, `u_UseRGSS`), UBO `iris_SodiumPushConstants` (`iris_RegionOffset`, `iris_CurrentTime`, `iris_RegionID`), `isamplerBuffer u_SectionTimeInfo` (fondu d'apparition par section, désactivé en ombre `:126`).

### 2.2 Format « vanilla étendu » (chemin sans moteur Sodium)

`vertices/IrisVertexFormats.java:35-44` : `Position RGB32_FLOAT, Color RGBA8_UNORM, UV0 RG32_FLOAT, UV2 RG16_SINT, Normal RGBA8_SNORM, mc_Entity RG16_SINT, mc_midTexCoord RG32_FLOAT, at_tangent RGBA8_SNORM, at_midBlock RGBA8_SNORM` = **52 octets par sommet** (12+4+8+4+4+4+8+4+4), soit +44 % de bande passante mémoire face au compact complet (36 octets) et +160 % face au compact de base (20 octets). Il est écrit par `MixinBufferBuilder` (activé par `ImmediateState.isRenderingLevel` et un pack actif, `:101-120`) et consommé par `VanillaTransformer` (position `iris_Position`, `ModelOffset`, `hasChunkOffset`).

**Recommandation** : le moteur maison réutilise l'encodeur compact d'Iris (`XHFPTerrainVertex`, déjà sa propriété, découplé de Sodium sauf l'interface `ChunkVertexEncoder`) et son décodage GLSL (`Patch.SODIUM`), donc 20-36 octets par sommet et **aucune réécriture des shaders patchés**. Le format 52 octets reste le repli « niveau A » (aucun code de mailleur à écrire, seulement `beginBlock`).

### 2.3 Comment les shaders des packs le consomment

Le patcheur choisit `Patch.SODIUM` ou `Patch.VANILLA` (`pipeline/programs/ShaderCreator.java`, `ShaderKey.java:155-161` pour les clés `SODIUM_TERRAIN_*` et `SHADOW_SODIUM_TERRAIN_*`, `pipeline/IrisPipelines.java:238-247` : la clé est choisie **par le nom d'espace du pipeline** (`namespace contains "sodium"`) et par `#define CUTOUT` / présence de fusion, donc ce sélecteur doit être adapté au nom du pipeline du moteur maison). Pour chaque programme du pack (`gbuffers_terrain`, `gbuffers_water`, `shadow`, `shadow_cutout`, `shadow_water`) : `gl_Vertex` → `getVertexPosition()`, `gl_Normal` → `irs_Normal`, `mc_Entity` → `iris_Entity` (`SodiumTransformer.java:263-306`), `mc_midTexCoord`, `at_tangent`, `at_midBlock`, `iris_NormalMat`.

## 3. Passes, états et matrices

### 3.1 Passe d'ombre (`shadows/ShadowRenderer.java`)

Ordre réel : matrices d'ombre (`:440`) → construction du frustum d'ombre (`createShadowFrustum`, `:452`, centré sur la caméra joueur `:464`) → `smartCull` désactivé (`:472-473` : « chunk occlusion culling is a bit complex to get working with shadow », **TODO d'origine**) → remplacement des matrices du moteur (`sodium$setMatrices(new ChunkRenderMatrices(shadowProjection, MODELVIEW))`, `:475-476`) → **`scheduleTerrainUpdate()` puis `setupTerrain(...)` à chaque image** (`:482-497`, commentaire `:478-481` : « Always schedule a terrain update ; TODO: only if the sun/moon moves or the shadow camera moved ») → `prepareChunkRendering` (`:520`) → cull GL désactivé (`:516`) → `TERRAIN_SOLID` groupe OPAQUE (`:524-531`) → `TERRAIN_CUTOUT` via `ShadowRenderCallbacks` (`:534-538`) → entités (`:551-594`) → blocs-entités (`:594`) → translucides `TERRAIN_TRANSLUCENT` (`:612-621`) → restauration (`:626`, `:643`).

Ce que le moteur doit donc fournir pour l'ombre : (i) une **seconde traversée de visibilité indépendante** (arbre, listes triées, lots de multi-draw et UBO propres, cf. `MixinRenderSectionManagerShadow.java:60-87,109`, `MixinRenderRegion.java`, `MixinUniformData.java`) ; (ii) dessin sans culling de faces (`MixinDefaultChunkRenderer.java:36-40`) ; (iii) frustum orthographique fourni par Iris.

### 3.2 Passe principale

`MixinLevelRenderer` capture `gbufferModelView`/`gbufferProjection` (`mixin/MixinLevelRenderer.java:113-115`, projection inversée « reverse-Z » : `MatrixUtils.undoRevZ`). Phases : `WorldRenderingPhase.TERRAIN_SOLID`, `TERRAIN_CUTOUT_MIPPED`, `TERRAIN_CUTOUT`, `TERRAIN_TRANSLUCENT` (`pipeline/WorldRenderingPhase.java:15-24`), déclenchées par le groupe de couches du terrain (`ChunkSectionLayerGroup.OPAQUE/TRANSLUCENT`, `:34-36`). Les programmes sont attribués aux pipelines vanilla `SOLID_TERRAIN/CUTOUT_TERRAIN/TRANSLUCENT_TERRAIN` (`IrisPipelines.java:25-29`, ombre `:87-91`). Chaque programme rend dans les FBO de gbuffers d'Iris (`gl/framebuffer/GlFramebuffer`), textures réservées `IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS` (`ExtendedShader.java:146`).

### 3.3 Tri des translucides

Vanilla : `SectionRenderDispatcher$RenderSection$ResortTransparencyTask`, `CompiledSectionMesh.isDifferentPointOfView/getTransparencyState`, `TranslucencyPointOfView`, et OIT (`ChunkSectionsToRender.renderOit`, `net.minecraft.client.renderer.oit`). Iris ne fait aucun tri lui-même ; il suppose seulement que la couche translucide est dessinée après l'opaque et à l'ordre arrière→avant correct par section. Le moteur doit garder l'ordre par section (arrière→avant) et le re-tri quand le point de vue change de cellule.

### 3.4 Exigences transversales

* Sections 16³ ; index de section dans la région sur 8 bits (256) ; région = boîte 8×4×8 (`SodiumTransformer.java:247-253`).
* Échantillonneur de l'atlas forcé NEAREST + clamp-to-edge quand un pack est en service (`MixinDefaultChunkRenderer.java:42-50`) : le moteur maison doit permettre à Iris de surcharger l'échantillonneur.
* `mc_chunkFade`/`u_SectionTimeInfo` : temps d'apparition par section (`SodiumTransformer.java:126-130`), à fournir ou à défaut valeur constante (fondu désactivé).
* Suppression du culling de faces en ombre, occlusion désactivée en ombre.
* Moteurs voisins à ne pas casser : Distant Horizons (`compat/dh/*`, correctif 26.3.108 : DH coupé pendant les shaders), `HandRenderer`.

## 4. Points de branchement et interface minimale proposée

### 4.1 Points de branchement actuels Iris → Sodium (à re-pointer)

| Point | Fichier:ligne | Rôle |
|---|---|---|
| Choix du format de sommets | `WorldRenderingSettings` (`VertexFormatSource`), `VanillaRenderingPipeline.java:34-35`, `MixinRenderPipeline` | quel format le terrain doit produire |
| Programmes de terrain | `MixinShaderChunkRenderer.java:13-16`, `ExtendedShader.java:139` (`ShaderChunkRenderer.BIND_GROUP.uniforms()`), `IrisPipelines.getPipeline:238-247` | Iris remplace le programme du terrain |
| Dessin et listes | `MixinDefaultChunkRenderer`, `MixinRenderRegion`, `MixinRenderSectionManager*`, `MixinSodiumWorldRenderer`, `MixinUniformData` | deux jeux de listes caméra/ombre |
| Ombres | `ShadowRenderer.java:475-531` | pilote la seconde traversée |
| Contexte de bloc | `MixinChunkMeshBuildTask`, `MixinBlockRenderer`, `MixinDefaultFluidRenderer` | `beginBlock` |
| Matrices/brouillard | `MixinLevelRenderer.java:115`, `FogUniforms` | capture d'état |

### 4.2 Interface minimale (Java) que le moteur `fr.clubcitrouille.lanterne.render.terrain` doit implémenter

```java
package fr.clubcitrouille.lanterne.render.terrain;

/** Le moteur de terrain : vanilla étendu, ou moteur maison. Iris ne parle qu'à cette interface. */
public interface TerrainEngine {

    /** Vrai si ce moteur sait fournir tous les attributs d'Iris. */
    boolean supportsShaderPacks();

    /** Format et encodeur de sommets à utiliser tant qu'un pack est actif. */
    TerrainVertexType vertexType(boolean blockId, boolean normal, boolean midUv, boolean midBlock);

    /** Hooks du mailleur : Iris y branche le contexte de bloc. */
    void setBlockContextSink(BlockContextSink sink);

    /** Prépare la visibilité pour une vue (caméra ou ombre). Deux vues indépendantes (arbres, listes, UBO séparés). */
    TerrainVisibility setup(TerrainView view, TerrainFrustum frustum, boolean shadow);

    /** Dessine un groupe de couches avec le programme fourni par Iris (gbuffers_terrain, shadow, water…). */
    void draw(TerrainVisibility set, TerrainLayerGroup group, RenderPass pass,
              GpuSampler samplerOverride, GpuTextureView atlas, TerrainProgram program);

    /** Statistiques pour F3. */
    String debugLine(boolean shadow);
}

public interface TerrainFrustum {           // remplace l'interface Frustum de Sodium + ViewportProvider
    boolean testSection(int sx, int sy, int sz);        // section 16³, coordonnées de section
    boolean testBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
}

public interface TerrainVertexEncoder {     // signature de XHFPTerrainVertex.write
    long write(long ptr, int material, TerrainVertex[] quad, int section);
}

public interface BlockContextSink {         // ce qu'Iris reçoit du mailleur
    void beginBlock(int blockId, byte isFluid, byte lightEmission, int x, int y, int z);
    void endBlock();
    void overrideBlock(int blockId);        // MixinBlockRenderer:97-108
    void restoreBlock();
}

public interface TerrainProgram {           // programme fourni par Iris (ExtendedShader)
    void bindGlobals(TerrainGlobals g);     // u_Globals (§2.1)
    void bindRegion(float rx, float ry, float rz, int currentTime, int regionId); // iris_SodiumPushConstants
    RenderPipeline pipeline();
}
```

Deux implémentations : `VanillaTerrainEngine` (niveau A, greffé sur `SectionCompiler` + `ChunkSectionsToRender`, format 52 octets) et `LanternTerrainEngine` (niveau B, compact + régions + multi-draw indirect maison). Tout est écrit contre `com.mojang.renderpearl` (`RenderPass`, `GpuBuffer`, `RenderPipeline`, `GpuSampler`) pour que Vulkan/OpenGL soient gratuits.

## 5. Étapes ordonnées

**Niveau A : « le client démarre sans Sodium, un pack se charge, le terrain est shadé »**

1. Palier 0 et 1 (utilitaires, accesseurs) → supprime les `NoClassDefFoundError` du démarrage (dont `VanillaRenderingPipeline.java:35`). Test : client sans Sodium démarre avec Iris **actif** (aujourd'hui : inerte, `IrisSodiumGate`). Risque : faible.
2. Abstraire `sodiumPresent()` en `TerrainEngine` (interface §4.2), implémentation `SodiumTerrainEngine` (l'existant) et `VanillaTerrainEngine`. Risque : moyen (ne pas casser le cas avec Sodium).
3. `VanillaTerrainEngine` pour le principal : mixin sur `SectionCompiler.compile` appelant `beginBlock/endBlock` (comblent 5b), format `IrisVertexFormats.TERRAIN` (52 octets), programmes `SOLID_TERRAIN/CUTOUT_TERRAIN/TRANSLUCENT_TERRAIN` déjà mappés. Risque : élevé — vérifier que `SectionUberBuffers` et le stride de `UberGpuBuffer` s'adaptent à un format plus large (à prouver par test), et que `beginBlock` est appelé aussi pour les fluides.
4. Passe d'ombre vanilla : réécrire `ShadowRenderer.java:475-531` contre `LevelRenderer`/`SectionOcclusionGraph` vanilla avec le frustum d'ombre. Risque : élevé (l'arbre vanilla est lié à la caméra ; il faut une seconde instance de graphe ou un parcours ad hoc).
5. Palier 2 (frustums) et 4 (config).
6. **Test réel obligatoire à chaque étape** : client sans Sodium, monde, `ComplementaryReimagined_r5.9.zip`, capture d'écran lue (procédure de l'agent artefacts : `C:\shaderbench`).

**Niveau B : « perfs au niveau de Sodium ou mieux »**

7. `LanternTerrainEngine` : format compact d'Iris (`XHFPTerrainVertex`) dans un `SectionCompiler` maison ou détourné ; régions de 256 sections ; multi-draw indirect (vanilla a déjà `GpuMultiDrawIndexedIndirect`) ; UBO d'ombre séparé ; listes triées.
8. Leviers de perf propres aux ombres, prouvés par le code : re-traversée complète à chaque image (`ShadowRenderer.java:478-487`), occlusion coupée (`:472`), deux jeux de lots/UBO. Pistes : mise à jour du graphe d'ombre seulement si l'angle du soleil ou la cellule caméra a changé (le TODO d'origine, `:479`), culling par boîte orthographique, distance d'ombre adaptative, réutilisation du cache de lots (`MixinRenderRegion.cachedBatches`).
9. Gating/perf des entités (palier 3) : mesurer avant/après.

Risques transversaux : dérive de version de MC (les noms `renderpearl` changent) ; ne pas ré-introduire de code Sodium (licence) ; `SodiumTransformer` traduit d'un shader Sodium (§0.5).

## 6. Vulkan

### 6.1 Ce qui bloque, par étage

1. **Iris désactivé sous Vulkan** : `IrisMixinPlugin.java:114-117` (`return !usingVulkan`) ; l'utilisateur voit `ShaderPackScreenPlaceholder.java:30` « Iris cannot run when using Vulkan… » ; `IrisConfig.java:123-134` avertit qu'un passage en Vulkan retire les shaders ; `IrisMixinPlugin.java:55` lit `preferredGraphicsBackend`.
2. **La couche GL d'Iris** : 125 fichiers utilisent GL/`GlStateManager`/`IrisRenderSystem` (87 importent `org.lwjgl.opengl`), dont tout `gl/` (framebuffers, programmes, textures, images, uniformes, échantillonneurs) et les mixins `MixinGlProgram`, `MixinGlRenderPass`, `MixinGlCommandEncoder`, `MixinGlStateManager*`. Sous Vulkan, rien de tout cela n'existe. Il faut une couche `IrisRenderSystem` alternative sur `renderpearl` (`GpuTexture`, `RenderPass`, `RenderPipeline`, groupes de liaison). `PBRAtlasTexture.java:16` importe déjà `VulkanGpuTexture` (une amorce). C'est le plus gros poste Vulkan ; à faire après le niveau B, mais le moteur de terrain doit déjà l'anticiper (§4.2).
3. **GLSL → SPIR-V** (prototype `lab/ShadercProbe`, `UniformReblocker`) : chaîne prouvée sur `patchComposite` et `patchVanilla` (vertex compile en 32784 octets sur un vrai pack), **échec natif** sur le fragment reblocké de 99760 caractères : `OutOfMemoryError: Out of stack space` dans `Shaderc.shaderc_compile_into_spv` (`RECHERCHE-VULKAN-SANS-SODIUM.md`, étapes 12-13), identique avec pile Java de 64 Mo, 256 Mo, 512 Mo → récursion non bornée dans glslang, pas un manque de pile.
4. **Étage pipeline** (après SPIR-V) : `Empreinte.java`/`VulkanRenderPipelineMixin` (cache `VkPipelineCache`) déjà en place, utile seulement quand 2 et 3 marcheront.

### 6.2 Pistes de contournement pour l'étage 3 (non tentées, ordre de coût croissant)

1. **Dichotomie du fichier** (le plus probable payant) : réduire le fragment par tailles croissantes pour trouver le motif GLSL déclencheur (la doc note explicitement « jamais fait ») ; puis aplatir ce motif dans `UniformReblocker`/`TransformPatcher`.
2. `shaderc_compile_options_set_optimization_level` à zéro / désactiver les passes d'optimisation, et essayer `shaderc_target_env_opengl 4.5` avec transformation SPIR-V ultérieure (déjà noté étape 3 comme valide).
3. **shaderc en processus séparé** (binaire `glslc`/`glslangValidator` d'un SDK Vulkan à jour) : n'aide que si la version native embarquée par LWJGL 3.4.3 est en cause ; le crash étant une récursion non bornée, il est probable qu'il se reproduise à l'identique, mais l'isolement évite au moins de tuer le jeu et permet une version plus récente de glslang. À tester sur le fichier réel avec un `glslangValidator` externe avant d'investir.
4. Découper le fragment (scinder les fonctions gigantesques du pack, ou compiler par unités) — exige un patcheur plus intrusif.
5. Ne pas utiliser SPIR-V d'un seul bloc : cross-compiler depuis un SPIR-V produit par `glslang` externe et lier avec `spirv-cross` uniquement pour la réflexion (LWJGL fournit `lwjgl-spvc`, présent dans le classpath du client de test).

### 6.3 Contrainte de conception qui en découle

Le moteur de terrain (§4.2) et le futur `IrisRenderSystem` alternatif doivent parler `renderpearl`. Un moteur maison écrit contre GL brut ferait de Vulkan un chantier séparé de plusieurs semaines ; écrit contre `renderpearl`, il est compatible dès que l'étage 2 et l'étage 3 sont levés.
