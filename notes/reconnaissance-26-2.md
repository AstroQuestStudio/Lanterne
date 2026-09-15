# Reconnaissance technique — Minecraft 26.2 (migration NeoForge 26.1.2 → 26.2)

> Date de la reconnaissance : **15 septembre 2026**. Lecture seule, aucune modif de code.
> Convention de ce document : **[V]** = vérifié (j'ai lu la source primaire : primer NeoForge, wiki, code du dépôt, API Modrinth, manifest Mojang). **[S]** = supposé / déduit. **[NV]** = non vérifiable (description marketing seule).

---

## AVERTISSEMENT LIMINAIRE — deux faits qui changent la décision

### A. Le « moteur de rendu Vulkan natif » n'est PAS par défaut en 26.2 [V]

L'hypothèse de départ (« 26.2 introduit un moteur de rendu Vulkan natif ») est **vraie sur le fond mais fausse sur l'usage réel** :

- 26.2 ajoute une option vidéo **Graphics API** à trois valeurs : `Default`, `Prefer Vulkan (Experimental)`, `Prefer OpenGL`.
- **`Default` utilise OpenGL en arrière-plan.** C'est écrit noir sur blanc dans la note de bas de page du wiki : *« Uses OpenGL behind the scenes. »* [V]
- Vulkan était le défaut dans la **snapshot 1** (7 avril 2026), puis Mojang l'a **rétrogradé en expérimental dès la snapshot 8**. [V]
- En Pre-Release 5, un garde-fou anti-crash a été ajouté : crash au démarrage avec `Prefer Vulkan` → bascule auto vers `Default` ; crash avec `Default` → bascule vers `Prefer OpenGL`. [V]
- Texte officiel : *« Currently an experimental rendering backend, and may not be as performant or stable »*. [V]

**Conséquence directe** : la quasi-totalité du parc joueur 26.2 tourne **en OpenGL**. Tout mod dont la valeur dépend de Vulkan (les cinq mods de la section 2) ne s'active que si le joueur va cocher manuellement une option marquée « Experimental ». C'est un plafond d'audience, pas un détail.

### B. 26.3 sort dans quelques jours [V]

Manifest officiel Mojang (`launchermeta.mojang.com/mc/game/version_manifest_v2.json`, lu le 15/09/2026) :

```
"latest": { "release": "26.2", "snapshot": "26.3-rc-3" }
26.3-rc-3  releaseTime 2026-09-14T12:58:38Z   ← hier
26.2       releaseTime 2026-06-16T12:03:33Z
```

26.3 est en **release candidate 3**. Une RC, chez Mojang, c'est la sortie à quelques jours. Et 26.3 **casse à nouveau tout le pipeline shader** (voir §1.4). Migrer vers 26.2 aujourd'hui, c'est viser une cible qui bouge sous les pieds.

---

## 1. Minecraft 26.2 : ce qui a changé techniquement

### 1.1 Repères de version

| Fait | Valeur | Source |
|---|---|---|
| Nom du drop | **Chaos Cubed** | wiki [V] |
| Date de sortie | **16 juin 2026** | manifest Mojang [V] |
| Contenu joueur | grottes de soufre, cube de soufre, blocs soufre/cinabre, liste d'amis, Vulkan expérimental | wiki [V] |
| Data pack format | **107.1** | wiki [V] |
| Resource pack format | **88.0** | wiki [V] |
| Java requis | **Java SE 25** (déjà le cas en 26.1) | wiki [V] |
| NeoForge | série `26.2.0.x`, suffixe `-beta` retiré → API stabilisée (≈ `26.2.0.88` mi-septembre) | projects.neoforged.net [V] |
| Versionnage | calver : `26` = année, `.2` = 2ᵉ drop de l'année. **26.2 n'est pas un patch de 26.1**, c'est une release complète. | minecraft.net [V] |

### 1.2 Le backend Vulkan : ce qu'il est vraiment

**Paquets Java** [V, primer NeoForge 26.2] :

> *« for all classes in `com.mojang.blaze3d.opengl`, there is generally a parallel found in `com.mojang.blaze3d.vulkan`. With this change, more of the Blaze3d components has been generalized further. »*

Classes principales de `com.mojang.blaze3d.vulkan.*` :
`VulkanBackend`, `VulkanDevice`, `VulkanCommandEncoder`, `VulkanRenderPass`, `VulkanRenderPipeline`, `VulkanGpuBuffer`, `VulkanGpuTexture`, `VulkanGpuTextureView`, `VulkanGpuSampler`, `VulkanInstance`, `VulkanPhysicalDevice`, `VulkanQueue`, `VulkanCommandPool`, `VulkanBindGroupLayout`, `VulkanQueryPool`, `VulkanTransientMemory`, `VulkanConst`, `VulkanDebug`.
Sous-paquet GLSL : `com.mojang.blaze3d.vulkan.glsl.{GlslCompiler, IntermediaryShaderModule, ShaderCompileException}`.
Extensions de checkpoint pour diagnostiquer les device-lost : `CheckpointExtension`, `AmdCheckpointExtension`, `NvidiaCheckpointExtension`, `NoopCheckpointExtension`.

**Coexistence** : OpenGL et Vulkan coexistent derrière une abstraction `GpuDevice` / `GpuDeviceBackend` commune. Mojang a annoncé le 18 février 2026 (*« Another step towards Vibrant Visuals for Java Edition »*) que **OpenGL sera supprimé** une fois Vulkan stable et performant, avec préavis et mise à jour de la config minimale. [V via reprises presse ; l'article minecraft.net lui-même a timeout sur deux tentatives de fetch — la substance est corroborée par le wiki : *« As previously announced, it is intended to switch the game from OpenGL to Vulkan »*]

**Exigences** : Vulkan 1.2 + `dynamic rendering` + `push descriptors`. macOS via MoltenVK → Metal. Sous Vulkan, le jeu **préfère le GPU dédié** à l'iGPU (inversion par rapport à OpenGL). Backend actif visible dans F3 → `system_specs`. [V]

**Multithread** : Mojang justifie le passage par le fait qu'OpenGL est mono-thread et sature un cœur CPU, là où Vulkan permet de répartir le travail de rendu. [V, communication Mojang] **Mais** : le primer 26.2 ne documente **aucune** nouvelle architecture de thread de rendu. Ce que je vois dans l'API, c'est une séparation **extract / render** :
- `LevelRenderer` est scindé en `LevelRenderer` + **`LevelExtractor`** ; `extractLevel()` → `LevelExtractor#extract()`.
- `collectPerFrameGizmos` → **`collectPerFrameRenderThreadGizmos`**, et ajout de **`addMainThreadGizmos`** — deux chemins distincts, main thread vs render thread.
- `MetricsRecorder#sampleDuringExtract` — *« marking the phase as during render state extraction »*, et `MetricSampler$SamplingPhase`.
- `CommandEncoder` gagne `MAX_SUBMITS_IN_FLIGHT`, `currentSubmitIndex`, `currentSubmitSlot`, `submit()`, `submitRenderPass()` — modèle multi-frames-in-flight.

→ **[V]** l'API 26.2 acte une séparation main-thread / render-thread avec phase d'extraction. **[S]** que ce soit déjà du vrai parallélisme de soumission de commandes. Je n'ai pas trouvé de déclaration Mojang chiffrant un gain multithread en 26.2.

**Point d'extension officiel pour DLSS / FSR / upscaling : IL N'Y EN A PAS.** [V]
- Aucune API publique d'upscaling, de render-scale, de motion vectors ou de jitter dans le primer 26.2.
- Preuve par l'usage : le mod `superresolution` (open source, lu) accède au `VkInstance` / `VkDevice` / `VkPhysicalDevice` **par réflexion** sur `com.mojang.blaze3d.vulkan.VulkanDevice` (`getClass().getMethod(...)`), avec des messages d'erreur internes du type *« Failed to read b3d VkInstance »*. C'est un hack, pas un hook.
- Les seuls points d'accroche de fait sont : `PostChain` (post-processing, mixinable), `com.mojang.blaze3d.platform.Window#getWidth/getHeight` (pour tromper la résolution interne), et `GpuSurface#blitFromTexture()`.

### 1.3 Autres changements de rendu à fort impact (liste courte des ruptures)

| Avant (26.1) | Après (26.2) |
|---|---|
| `TextureFormat`, formats de `VertexFormatElement` | **`GpuFormat`** (`R8_UNORM`, `RGBA32_SINT`, `D32_FLOAT_S8_UINT`…), pas de mapping 1:1 |
| `DestFactor` + `SourceFactor` | **`BlendFactor`** unique + `BlendOp` + `BlendEquation` |
| `VertexFormat$Mode` | **`PrimitiveTopology`** ; `VertexFormat$IndexType` → `IndexType` |
| liste statique de `VertexFormatElement` | construction dynamique via `$Builder#addAttribute(String, GpuFormat)`, max 16 attributs, max 16 vertex buffers |
| samplers/uniforms en vrac sur le pipeline | **`BindGroupLayout`** ; les `*_SNIPPET` deviennent `BindGroupLayouts#*` |
| **`MultiBufferSource`** | **supprimé**. Remplacé par le système `FeatureRenderer` / `FeatureRenderPhase` / `SubmitNode` / `PreparedRenderType` |
| depth test `LESS_THAN_OR_EQUAL` | **depth buffer inversé** → `GREATER_THAN_OR_EQUAL`, valeurs de `DepthStencilState` inversées |
| `RenderTarget#blitToScreen()` | `GpuSurface#blitFromTexture()` |
| `Font#drawInBatch()` | `Font#prepareText()` → `Font$PreparedText` → `Font$GlyphVisitor#acceptRenderable()` |
| `Minecraft#screen` | `Minecraft#gui.screen()` ; nouvelle classe **`Hud`** séparée de `Gui` |
| `ShapeRenderer` | `ShapeOutlineFeatureRenderer` + `submitShapeOutline()` |
| — | nouvelles classes : `GpuSurface`, `StagingBuffer`, `StagedVertexBuffer`, `TransientMemory`, `FrameBufferCache`, `TracyGpuProfiler`, `GpuDeviceLossException`, `BackendCreationException` |

`LevelRenderer` : constructeur entièrement changé (`EntityRenderDispatcher, BlockEntityRenderDispatcher, ModelManager, TextureManager, AtlasManager, ShaderManager, GameRenderer, int, int`), `renderLevel()` → `render()`, `allChanged()` → `invalidateCompiledGeometry()`, `setLevel()` → `resetLevelRenderData()`.
`SectionRenderDispatcher` : `rebuildSectionAsync()` → `compileAsync()`, `$RebuildTask` → `$CompileTask`.
`ViewArea` : ne prend plus `Level` ni `LevelRenderer`.

> **Verdict rendu** : ce n'est pas une migration, c'est une réécriture. Tout mixin sur le rendu 26.1 est à refaire, Vulkan ou pas.

### 1.4 26.3 casse encore le pipeline shader [V]

Confirmé sur wiki + changelogs officiels des snapshots 26.3 :
- **Les shaders sont désormais compilés par ShaderC même sous OpenGL**, à l'identique de Vulkan.
- `#moj_import` → **`#include`** (géré par ShaderC).
- Les entrées/sorties de shader **doivent déclarer une `location`** ; les sorties vertex sont appariées aux entrées fragment **uniquement par location**, le nom est ignoré.
- `B3D_IS_ZERO_TO_ONE` → `RENDERPEARL_IS_ZERO_TO_ONE`.
- Defines OIT renommés : `WAVELET_RANK` → `OIT_WAVELET_RANK`, `COEFF_COUNT` → `OIT_COEFF_COUNT`, `COEFF_ATTACHMENT_COUNT` → `OIT_COEFF_ATTACHMENT_COUNT`.
- Resource pack format → **93.0**.

**Impact** : tout investissement 26.2 dans la traduction/compilation de shaders (cf. Vitrail) sera à retoucher en 26.3.

### 1.5 Côté serveur : ce que Mojang a VRAIMENT optimisé en 26.2

**Réponse courte : presque rien.** [V]

Le changelog vanilla 26.2, section performance, tient en trois puces :
- *« Upgrading certain worlds from before 26.1 can now be faster »* (corroboré dans le primer : `ChunkNbt#updateChunk` retourne maintenant un `CompletableFuture` → conversion de monde asynchrone).
- *« Profiling the game with Tracy (launching with --tracy) now includes GPU timings »*.
- *« Rendering now uses a reversed depth buffer, which helps with Z-fighting on most hardware »* (client).
- Particules : au-delà de la limite, le jeu **choisit aléatoirement** quelles particules rendre au lieu de supprimer les particules existantes (client).

**Il n'y a AUCUNE optimisation vanilla 26.2 de threading serveur, de tick d'entités, d'IA, de redstone ou de sauvegarde de région.** Les articles SEO qui parlent d'une « réécriture majeure du pipeline de chargement de chunks » en 26.2 (minecraft.how, techtimes) **ne sont corroborés ni par le wiki, ni par le changelog officiel, ni par le primer NeoForge**. Je les considère comme du remplissage. **À ne pas croire.**

**Ce qui a bougé côté serveur, en revanche (primer 26.2, [V])** — c'est surtout une **passe d'ouverture d'API** très favorable à un mod d'optimisation :

*Chunks / génération* :
- `ChunkStatusTasks` : **toutes** les étapes passent package-private → `public` (`generateStructureStarts`, `loadStructureStarts`, `generateStructureReferences`, `generateBiomes`, `generateNoise`, `generateSurface`, `generateCarvers`, `generateFeatures`, `initializeLight`, `light`, `generateSpawn`, `full`, `passThrough`). → on peut enfin réordonner/instrumenter le pipeline de génération sans mixin sauvage.
- `RegionFileStorage` : constructeur package-private → `public`, `write` protected → `public`. → accroche propre sur l'IO de région.
- `ChunkMap#collectSpawningChunks`, `forEachBlockTickingChunk`, `anyPlayerCloseEnoughForSpawning`, `anyPlayerCloseEnoughTo` → `public`.
- `LoadingChunkTracker` → `public`. `ChunkMap#hasEntityWithId`, `ServerChunkCache#hasEntityWithId` ajoutés.
- `ChunkAccess#markPosForPostprocessing` → `markPosForPostProcessing` (typo corrigée, casse la compilation).
- `LevelChunkSection.SECTION_WIDTH/HEIGHT` → `SectionPos#SECTION_SIZE` ; `SECTION_SIZE` → `SectionPos#SECTION_BLOCK_COUNT`.
- `ChunkPos#MAX_COORDINATE_VALUE` → `ChunkPyramid#MAX_CHUNK_COORDINATE_VALUE`.
- `ChunkAccess#collectBiomesInPalette`, `PalettedContainerRO#forEachInPalette` ajoutés (itération palette sans matérialiser).

*Worldgen / bruit* :
- `DensityFunctions#weirdScaledSampler` / `$WeirdScaledSampler` **supprimés**, remplacés par `NoiseRouterData$QuantizedSpaghettiRarity#wrapRarity2d/3d` (**pas 1:1**).
- Ajout de `DensityFunctions#intervalSelect` / `$IntervalSelect`.
- `DensityFunction#mapAll` devient `default`, `mapChildren` ajouté.
- `DensityFunction.DIRECT_CODEC` et `HOLDER_HELPER_CODEC` **supprimés**.
- `CubicSpline` devient `sealed`, perd son générique `C`, `mapAll` → `mapCoordinates`.
- `SurfaceRules$Context#getNoiseSampler`, `getBiome` ajoutés.
- **26.3 (RC)** : *« Density function and noises are no longer evaluated with double-precision floating point values, but instead with single-precision »* — y compris les étapes intermédiaires. **C'est l'optimisation worldgen que Mojang a faite lui-même, et elle arrive en 26.3, pas en 26.2.**

*Redstone* :
- Ajout de `BlockBehaviour#ownSignal` et `$BlockStateBase#getOwnSignal` (*« The redstone signal this block is outputting »*), plus `SignalGetter#getBestOwnOrNeighbourSignal`.
- **[S]** C'est une mise en cache du signal propre au niveau du `BlockState` pour éviter de le recalculer ; cohérent avec les rapports selon lesquels *« redstone dust updates could behave subtly differently »* après les RC 26.2. Je n'ai trouvé aucune confirmation Mojang explicite — à vérifier par lecture du code décompilé avant de s'appuyer dessus.

*Entités / IA* :
- `MoveControl`, `FlyingMoveControl`, `SmoothSwimmingMoveControl` deviennent **génériques** sur `Mob` → casse tout override/mixin.
- `EntitySpawnReason` → **`EntitySpawnRequest`** dans `EntityType#create/spawn/loadEntityRecursive/loadPassengersRecursive` ; `Consumer` → **`PostSpawnProcessor`**.
- `AgeableMob#isBaby/setBaby` deviennent `final` (override `canBeABaby`).
- `FlyingAnimal` supprimé → `Entity#omnidirectionalAirMover`.
- `Slime` / `MagmaCube` déplacés dans `.monster.cubemob`, étendent `AbstractCubeMob`.
- `LivingEntity#knockback`, `causeExtraKnockback`, `blockUsingItem`, `blockedByItem` prennent maintenant `DamageSource` + `float`.
- `MobCategory` prend un `String` d'abréviation debug.
- Beaucoup de constructeurs de Goals passés `public` (Bee, Dolphin, Turtle, Frog, Fish…) — bon pour un mod d'IA.
- `Util#dumpThreadInfo` — *« Dumps the info for all live platform threads »*. Le terme « platform threads » est du vocabulaire Loom ; **[S]** indice que Mojang commence à manipuler des virtual threads (Java 25 requis). Non confirmé.

*Divers serveur* :
- Management Protocol → **3.0.0** ; le serveur de management démarre **avant** le serveur MC ; `Rpc.discover` accessible avant le spinup complet ; `allowPreServerInit` sur les méthodes RPC.
- `server.properties` : `chat-spam-threshold-seconds` et `command-spam-threshold-seconds` (10 s par défaut).
- `Util#timeSource` passe `public` → `private` (+ `setTimeSource`, `shutdownTimeSource`, `TimeSource#constant`). **Attention si Lanterne lit `Util.timeSource`.**
- `BlockableEventLoop#hasDelayedCrash` **supprimé**.
- `net.minecraft.util.Tuple` **supprimé**.
- `BlockPos#getCenter` / `getBottomCenter` **supprimés** (déléguer à `Vec3`).
- `CompoundTag#shallowCopy` protected → package-private.
- `Holder` devient `sealed` (`$Direct`, `$Reference`).

### 1.6 Ruptures d'API NeoForge / vanilla 26.1.2 → 26.2 — la liste qui fait mal

Il n'y a **pas d'article d'annonce NeoForge pour 26.2** (le dernier date de 26.1, 24 mars 2026). La référence de fait, y compris pour Fabric, c'est le primer vanilla :
**<https://docs.neoforged.net/primer/docs/26.2/>** / source brute : `https://raw.githubusercontent.com/neoforged/.github/main/primers/26.2/index.md` (182 Ko, 2638 lignes).

Les cinq ruptures structurantes hors rendu :

1. **Les registres d'objets sont éclatés.** `Block`, `Item`, `EntityType`, `BlockEntityType`, `Fluid`, `Potion` voient leurs `ResourceKey` déplacés dans des classes `*Ids` (`BlockIds`, `ItemIds`, `EntityTypeIds`, `BlockEntityTypeIds`, `FluidIds`, `PotionIds`). Les **entrées de registre** de `EntityType` et `BlockEntityType` déménagent dans **`EntityTypes`** et **`BlockEntityTypes`**. `EntityType#byString` supprimé. Nouveau concept `BlockItemId` (paire de `ResourceKey`) dans `BlockItemIds`, et `BlockItemTagId` dans `BlockItemTags`.
   → Tout `EntityType.ZOMBIE`, `BlockEntityType.CHEST` etc. dans le code Lanterne est à réécrire.

2. **Les `TagsProvider` sont refondus.** `HolderTagProvider`, `IntrinsicHolderTagsProvider`, `KeyTagProvider` **supprimés** ; tout hérite de `TagsProvider`. `TagAppender` perd son générique `E` et n'accepte plus que des `ResourceKey`. `TagAppender#map` supprimé.

3. **Object Collections.** Les variantes (16 couleurs, états du cuivre) sont regroupées : `Items#_*DYE` devient `Items#DYE`, via `ColorCollection` / `WeatheringCopperCollection` avec `registerBlocks` / `registerItems` / `forEach` / `pick`.

4. **Prédicats d'entité** : structure révisée, on passe d'« une structure à multiples champs optionnels » à un format de type composants. Les `EntitySubPredicate` changent de forme.

5. **Génériques sur les contrôleurs de mob** (`MoveControl<T extends Mob>` etc.) et passage `EntitySpawnReason` → `EntitySpawnRequest` : casse silencieuse au niveau des mixins (targets qui ne matchent plus).

**Renommages qui piègent** : `ColorArgument` → `TeamColorArgument` ; `DripstoneThickness` → `SpeleothemThickness` ; `BlockStateProperties#DRIPSTONE_THICKNESS` → `SPELEOTHEM_THICKNESS` ; `pointed_dripstone` → `speleothem`, `dripstone_cluster` → `speleothem_cluster` (features) ; `PointedDripstoneBlock extends SpeleothemBlock`.

**Rappel 26.1 (déjà fait chez toi, mais qui piège ceux qui sautent une version)** : JDK 21 → 25, déobfuscation complète (noms officiels Mojang), `ResourceLocation` → **`Identifier`** (le mot `ResourceLocation` n'apparaît **plus une seule fois** dans le primer 26.2 — [V] par comptage), `GuiGraphics` → `GuiGraphicsExtractor`, versionnage NeoForm en `<mcversion>-<build>`.

### 1.7 État de l'écosystème d'optimisation en 26.2 [V, API Modrinth au 15/09/2026]

Contrairement à ce qu'affirment plusieurs guides SEO (« Lithium ne supporte que jusqu'à 26.1.x »), **c'est faux** :

| Mod | `game_versions` contient 26.2 ? | Dernière MAJ | Téléchargements |
|---|---|---|---|
| Lithium | **oui** (26.1 → 26.2) | 2026-07-29 | 127 M |
| FerriteCore | **oui** (26.1 → 26.2) | 2026-03-24 | 150 M |
| Sodium | **oui** (jusqu'à 26.3-rc-2) | 2026-09-12 | 226 M |
| Iris Shaders | **oui** (26.1 → 26.2) | 2026-09-13 | 175 M |
| VMP (Fabric) | **oui** (26.2-rc-2, 26.2) | 2026-07-02 | 16 M |

Sodium 0.9.x annonce un « early support for Vulkan ». Iris prépare un successeur Vulkan-natif nommé **Aperture** (bêta privée, tourne sur 26.2 + Vulkan + Sodium), écrit en **Slang** (Khronos) et non en GLSL/OptiFine — **il abandonne le format de pack historique**. Repo public partiel : `https://github.com/IrisShaders/Aperture-Example-Pack` (branche `slang`). [V via relais publics ; le fil X d'origine n'a pas été lu en direct]

---

## 2. Les cinq mods client « Vulkan » — au banc d'essai

### Tableau de synthèse

| Mod | Existe ? | Plateforme | Source public | Licence réelle | Vérifié dans le code ? |
|---|---|---|---|---|---|
| `blockframe-dlss` | oui | **CurseForge seul** | **non trouvé** | « Custom License », texte non consultable | **NON** |
| `super-resolution` (slug réel `superresolution`) | oui | Modrinth + GitHub | **oui** | GPL-3.0-or-later (cohérent Modrinth/repo) | **OUI** |
| `upscaled` | oui | **CurseForge seul** | **non trouvé** | **All Rights Reserved** | **NON** |
| `pureformance` | oui | **CurseForge seul** | **non trouvé** | **All Rights Reserved** | **NON** |
| `vitrail-shaders` | oui | Modrinth + GitHub | **oui** | LGPL-3.0-only (cohérent) | **OUI** |

**Deux sur cinq sont auditables.** Les trois autres sont des boîtes noires propriétaires dont on ne peut littéralement rien affirmer.

---

### 2.1 `super-resolution` — le seul dossier technique solide

- Modrinth : <https://modrinth.com/mod/superresolution> (id `Hf3Qz2H3`) — **773 307 téléchargements**, 368 followers, 56 versions.
- Source **[V]** : <https://github.com/187J3X1-114514/superresolution> (branche par défaut `dev`), 279 ★, 38 forks, créé 27/08/2024, dernier push **13/09/2026**, 339 Mo (binaires natifs).
- Loaders : Fabric, Forge, **NeoForge**. Versions : 1.20.1 → 26.1.x → **26.2**. Dernière release `26.2-0.9.1-alpha.2` (13/09/2026).
- Licence **[V]** : GPL-3.0 (texte GPLv3 standard dans `LICENSE`), Modrinth déclare `GPL-3.0-or-later`. Cohérent. **Attention : GPL = contaminant.** Pas de reprise de code possible dans un mod non-GPL.

**Idée centrale, en trois phrases** : le mod rend le monde 3D à une résolution interne réduite en mentant à `Window#getWidth/getHeight`, puis réinjecte un upscaler temporel dans la chaîne de post-processing en substituant les render targets de `PostChain`. L'upscaling lui-même n'est pas du GLSL maison : il appelle de vraies bibliothèques natives C++ compilées en CI (NVIDIA NGX/DLSS, Intel XeSS, AMD FidelityFX/FSR 1/2/3, plus NIS et SGSR). Pour les brancher sur le backend Vulkan de Mojang, il **vole par réflexion** le `VkInstance`, le `VkDevice` et le `VkPhysicalDevice` dans `com.mojang.blaze3d.vulkan.VulkanDevice`.

**Classes Minecraft touchées [V]** :
- `super_resolution.mixins.json` — package `io.homo.superresolution.common.mixin`, **58 mixins client**, `MixinPlugin`, compat JAVA_8, refmap présent.
- `PostChainMixin` → `net.minecraft.client.renderer.PostChain` (injections dans `<init>`, `resize(int,int)`, `process(float)`).
- `WindowMixin` → `com.mojang.blaze3d.platform.Window` (`getWidth`, `getHeight`, `getGuiScaledWidth`, `getGuiScaledHeight`).
- `B3DVulkanBridge.java` → réflexion sur `VulkanDevice`, gestion des command buffers / fences ; messages d'erreur internes type *« Failed to read b3d VkInstance »*.
- `.gitmodules` **[V]** pointe sur les SDK officiels : `github.com/NVIDIA/DLSS`, `github.com/intel/xess`, fork `FidelityFX-SDK-UpscaleOnly`, plus FreeType et glslang.
- `common/src/main/resources/licenses/` contient 12 licences tierces réelles (`ngx.txt`, `xess.txt`, `ffxfsr.txt`, `fsr1.txt`, `fsr2v221.txt`, `fsr2v233.txt`, `nis.txt`, `sgsr.txt`…).
- CI `.github/workflows/build_native.yml` compile les natives Windows/Linux (`./gradlew buildNative`) → artefacts dans `common/src/main/resources/lib/**`.

**Non vérifié** : où et comment sont produits les motion vectors et le jitter de caméra (dossiers `upscale/`, `framegeneration/`, `lowlatency/` repérés mais non lus).

**Verdict** : projet crédible, actif, open source, techniquement réel. **C'est la référence à lire** si tu veux comprendre comment on branche un upscaler sur le Vulkan de Mojang aujourd'hui.

---

### 2.2 `vitrail-shaders` — le second dossier solide

- Modrinth : <https://modrinth.com/mod/vitrail-shaders> — 4 715 téléchargements, 17 followers, publié 14/08/2026, MAJ 11/09/2026. `game_versions: ["26.2"]` **uniquement**. Loaders : Fabric, NeoForge.
- Source **[V]** : <https://github.com/avpbynf/Vitrail-Shaders> — 1120 commits, 57 ★, 9 forks, **323 fichiers `.java` sur 377**.
- Licence **[V]** : **LGPL-3.0-only** (texte FSF dans `LICENSE`), cohérent avec Modrinth. `NOTICE` crédite du code adapté d'**Iris** (LGPL-3.0 : gestion program/target, samplers, shadow rendering, uniforms, UI d'options), de **stareval** de Kroppeb, de **jcpp / Anarres C Preprocessor** (Apache 2.0) et des kernels **AMD FSR 1.0** (MIT, portés de `ffx_fsr1.h`).
- Fork miroir sans valeur ajoutée apparente : `https://github.com/DebuNeko233/Vitrail-Shaders-Metal` (nom trompeur, cible Vulkan).

**Idée centrale, en trois phrases** : Vitrail ne réimplémente pas un compilateur GLSL→SPIR-V ; il **normalise** le GLSL format OptiFine vers le dialecte que Mojang comprend déjà, avec un lexer/traducteur maison (`dev.vitrail.glsl.{GlslLexer, TokenStream, GlslTranslator, ProgramTranslator, TranslatedUnit, TranslationCache, TranslatedProgramCodec}`). Il laisse ensuite le compilateur **déjà embarqué dans le jeu** (`com.mojang.blaze3d.vulkan.glsl.GlslCompiler`) produire le SPIR-V, et corrige en aval la réflexion des ressources. Il ne remplace pas Iris : sur OpenGL c'est Iris qui dessine, sur Vulkan c'est Vitrail, le switch se fait par l'option Graphics API.

**Vérifications [V]** : `build.gradle` ne déclare **aucune** dépendance shaderc/glslang/spirv-cross (lu ligne à ligne). `GlslCompilerMixin` ajoute le stage geometry, un cache de modules compilés, et neutralise des locales non initialisées avant la réflexion SPIRV-Cross. `IntermediaryShaderModuleMixin` élargit la liste de ressources SPIR-V demandées (storage images/buffers) pour laisser passer ce qu'utilisent des packs comme Complementary (`uimage3D voxel_img`, `blockDataBuffer`).

**Classes Minecraft touchées [V, `vitrail.mixins.json` lu intégralement]** : `VulkanBackendMixin`, `VulkanBindGroupLayoutMixin`, `VulkanCommandEncoderMixin` (+ `Sets`, `Transfer`), `VulkanConstMixin`, `VulkanDeviceMixin`, `VulkanGpuTextureViewMixin`, `VulkanQueueSubmitMixin`, `VulkanRenderPassMixin`, `VulkanRenderPipelineMixin`, `GlslCompilerMixin`, `IntermediaryShaderModuleMixin`.
**Dépendance dure à Sodium** : package `dev.vitrail.mixin.sodium` avec 10 classes (`ArenaAggregatorMixin`, `BlockRendererMixin`, `ChunkMeshFormatsMixin`, `ChunkVertexMixin`, `DefaultFluidRendererMixin`, `MixinDefaultChunkRenderer`, `MixinRenderRegion`, `MixinShaderChunkRenderer`, `MixinSodiumWorldRendererInit`, `VKDrawContextMixin`). Il faut la même version de Sodium qu'Iris exige.

**Maturité** : projet solo, actif quasi quotidiennement depuis juillet 2026, encore en bêta (`v0.11.0-beta`), géométries manquantes ou incorrectes assumées. Le projet déclare lui-même qu'une IA a fait « une vraie part de la frappe et du debug » sous revue humaine — déclaration à prendre pour ce qu'elle vaut.

**Risque 26.3** : Vitrail dépend directement de `GlslCompiler` et `IntermediaryShaderModule`. Or 26.3 unifie le pipeline sur ShaderC y compris en OpenGL et change `#moj_import` → `#include` + locations obligatoires. **Ce mod va prendre 26.3 de plein fouet.**

---

### 2.3 `blockframe-dlss` — **source introuvable, description seule**

- CurseForge : <https://www.curseforge.com/minecraft/mc-mods/blockframe-dlss>. **Absent de Modrinth** (API 404 + recherche vide).
- Auteur : `oliverwalter` — profil à 2 followers, 9 projets, ~2,7 K téléchargements cumulés.
- **26.2 uniquement, NeoForge uniquement.** ~1 184 téléchargements. Créé il y a ~2 mois, dernier fichier 13/08/2026.
- Licence affichée : « Custom License », texte non consultable.
- **Aucun lien source** (section Links vide). Recherches GitHub par nom exact, par auteur, par variantes : **rien**.

**Ce qu'on peut en dire** : rien de technique. La description annonce des modes Quality/Balanced/Performance, une détection RTX automatique, et prétend traiter *« motion vectors, temporal jitter, texture mip bias, foliage stability, shader compatibility »*. **[NV]** Aucune de ces affirmations n'est vérifiable. Compte tenu de §1.2 (aucun point d'extension officiel), n'importe quelle implémentation réelle devrait nécessairement passer par réflexion/mixin sur `VulkanDevice` comme le fait `superresolution` — **[S]** mais je n'ai aucune preuve que ce mod fasse quoi que ce soit.

**Recommandation** : traiter comme non-évaluable. Petit compte + zéro source + licence opaque + un domaine où le bluff est facile (personne ne peut distinguer « DLSS actif » d'un simple downscale/upscale bilinéaire à l'œil).

---

### 2.4 `upscaled` — **source introuvable, description seule**

- CurseForge : <https://www.curseforge.com/minecraft/mc-mods/upscaled>. **Absent de Modrinth** (404 confirmé API + web).
- Auteur : **KirboSoftware**. Contrairement au précédent, l'auteur est identifiable et a un historique réel : `github.com/KirboSoftware` et l'organisation `github.com/KirboSoftware-Mods` hébergent des dizaines de mods Minecraft légitimes. **Mais aucun dépôt « Upscaled » ni lié à DLSS/FSR/Vulkan** — cohérent avec la licence.
- Licence : **All Rights Reserved**. 821 téléchargements. Dernière version `v0.1.0-alpha-4` (13/07/2026). MC 26.2, Fabric + NeoForge.
- Prérequis annoncés : Windows x64, renderer Vulkan expérimental activé, Java 25+, RTX pour DLSS ou GPU Vulkan pour FSR3.

**Description technique (marketing, [NV])** : rendu du monde 3D en résolution interne réduite puis composition du HUD en résolution native ; **reconstruction des motion vectors caméra à partir du depth buffer** (donc pas de motion vectors par entité — le mod admet lui-même du ghosting sur particules et entités mobiles) ; fallback si l'upscaler ne s'initialise pas.

**Commentaire** : cette description est techniquement cohérente et *plausible* — reconstruire les motion vectors depuis la profondeur est exactement ce qu'on fait quand on n'a pas accès au pipeline de géométrie. Mais plausible ≠ vérifié. Auteur crédible, code fermé, projet alpha.

---

### 2.5 `pureformance` — **source introuvable, description seule**, et la plus suspecte

- CurseForge : <https://www.curseforge.com/minecraft/mc-mods/pureformance>. **Absent de Modrinth** : API 404, et `/v2/search?query=pureformance` renvoie `total_hits: 0`.
- Auteur : **TheWicked365** (solo, pas de profil GitHub identifiable ; présence Twitch/Bluesky).
- Créé ~23/08/2026, MAJ 13/09/2026. **1 372 téléchargements pour 37 fichiers publiés** — un rythme de publication anormalement élevé pour l'audience.
- Licence : **All Rights Reserved**. MC 26.2, 26.1.x, 1.21.11 + 11 autres. Loaders : NeoForge, Fabric, Quilt.
- **Aucun lien source.** Aucune dépendance déclarée.

**Description (marketing, [NV])** : *« camera-frustum rejection, occlusion testing, asynchronous visibility work, short-lived visibility caches, movement-aware invalidation, optimized ray ordering, bounded voxel traversal »*, un « Emergency Playability System » à 3 paliers de pression, une « Entity Catastrophe protection » avec plafonds de rendu d'entités à 4096/1024/256, et de la « memory canonicalization » de block states. Benchmark revendiqué : 19,13 → 29,18 FPS (+52,5 %) sur 600-799 entités entre 0.3.4 et 0.3.5.

**Analyse** [S] : le vocabulaire décrit exactement ce que font déjà Sodium (frustum + occlusion + culling de sections), MoreCulling / Entity Culling (culling d'entités), FerriteCore (canonicalisation mémoire de block states — *littéralement* la fonction principale de FerriteCore, 150 M de téléchargements) et Lithium. Un mod de 3 semaines, fermé, sans dépendance déclarée, qui prétend refaire tout ça en mieux sur 16 versions de Minecraft et 3 loaders simultanément, mérite un scepticisme élevé. Le benchmark auto-publié compare deux versions du mod lui-même, pas le mod contre le vanilla.

**Recommandation** : ne pas l'intégrer à un pack, et surtout ne pas s'en inspirer — il n'y a rien à lire.

---

## 3. Les douze mods serveur

Légende redondance : ⚠ = chevauchement réel à arbitrer.

| Mod | Idée centrale | Source | Déjà dans vanilla 26.2 ? |
|---|---|---|---|
| **notick** | Mixin `Level#guardEntityTick` (`@Inject HEAD cancellable`) : annule le tick d'une entité selon distance/whitelist ; whitelist via mixin sur `EntityType`. Vrai skip conditionnel. [V] | <https://github.com/Riqqqque/DoesItTickSC-main> — **divergence de licence** : repo MIT, Modrinth déclare LGPL-3.0-or-later. 4 743 dl, 0 ★, créé fév. 2026. | Non. ⚠ redondant avec `boosters` et `immersive-optimization`. |
| **boosters** | Mixins `Mob#serverAiStep` (throttle IA/sensing/nav des mobs distants, recalcul de distance tous les 10 ticks) et `LevelChunk$BoundTickingBlockEntity#tick` (throttle des BE distantes, hoppers/pistons exclus) + mixins client (culling entités/particules). [V] | <https://github.com/IsnotKrix/boosters> — MIT, créé 09/07/2026, **dernier push 16/07/2026 (stale ~2 mois)**, 0 ★. 5 662 dl. **26.2 uniquement**, Fabric/NeoForge. | Non. ⚠ Son README détecte Sodium/Lithium/C2ME/EntityCulling mais **pas** NoTick ni Immersive Optimization → double-throttle possible. |
| **immersive-optimization** | Mixin `ServerLevel#tick` (démarre un `TickScheduler`) + mixin `tickNonPassenger` (HEAD cancellable) qui skippe si `TickScheduler.shouldTick(entity)` est faux. **Répartit** les ticks dans le temps (dégradation progressive) au lieu du tout-ou-rien par distance. [V] | <https://github.com/Luke100000/ImmersiveOptimization> — **GPL-3.0**, branches par version dont `26.2`, actif depuis déc. 2024. **2,1 M dl Modrinth**, ~14,8 M CurseForge. | Non. **Le plus mature des trois.** ⚠ même famille que notick/boosters — n'en garder qu'un. |
| **enttick** | **Ce n'est pas un mod de perf.** Mixin `ServerChunkCache` annule l'appel vanilla à `ChunkMap#tick()`, puis mixin `ServerLevel#tick` (TAIL) le rappelle en fin de tick. Objectif : corriger MC-297196 (latence 1-3 ticks sur le knockback/sync d'entités liée à l'ordre de l'entity tracker). [V] | <https://github.com/cintlep/Enttick> — LGPL-3.0, branche `fabric`, créé 31/07/2026, push 02/09/2026, 0 ★. **472 dl.** MC 1.14.4 → 26.2. | Bug vanilla non corrigé en 26.2. Mais projet ultra-jeune et non éprouvé. Catégorie QoL, pas perf. |
| **tt20** | Compense **visuellement** le lag : mixins `AgeableMob#aiStep` (maturation accélérée via `missedTicks` calculés par `TT20.TPS_CALCULATOR`), `LivingEntityMixin`, `PortalProcessorMixin`, `ItemEntityMixin` — formule `ticks*tps/20` appliquée au temps de cassage, portails, couvée. Le mod **reconnaît traiter le symptôme, pas la cause**. [V] | <https://github.com/snackbag/tt20> — **licence `PolyForm-Shield-1.0.0` : source-available, NON-OSI, interdit d'exploiter un service concurrent basé dessus. À vérifier avant intégration dans un pack public.** 55 ★, push 11/09/2026. 548 K dl. | Non. Aucune redondance — mais aucun gain de TPS réel non plus. |
| **delta-chunk** | **Le slug `delta-chunk` n'existe pas** — le vrai est `deltachunk`. **Ce n'est pas un mod de tick** : compaction du stockage de chunks, ne persiste que les blocs modifiés par le joueur. Classes `RegionCompactor`, `WamStore`, `DeltaIndex`, `BlockDelta`. **Zéro mixin dans tout le repo** (arborescence complète vérifiée), format `.mca` conservé. [V] | <https://github.com/user19870/delta_chunk> — branche `multi-ver`, **divergence de licence** (GitHub « NOASSERTION » vs MIT sur Modrinth), 1 ★. **68 dl.** NeoForge uniquement. | Non. **Le mod lui-même déconseille le multijoueur** (*« Multiplayer servers are discouraged due to potential bugs and inefficient resource allocation »*) et exige une sauvegarde. À exclure. |
| **smooth-chunk-save** | Étale la sauvegarde des chunks dans le temps au lieu d'un pic global (bench auteur : 11 % → 0,3 % du tick, MC 1.18, 3 joueurs). | CurseForge seul (pas sur Modrinth). ~120,9 M dl. **`github.com/someaddons/smoothchunksave` ne contient QUE `.github/ISSUE_TEMPLATE`** — arbre git récursif vérifié, **aucun code**. **All Rights Reserved.** | Non — vanilla 26.2 n'a rien fait sur la sauvegarde. Mais **[NV]** sur la technique réelle. Issues fermées : « Exception in server tick loop », « server crashing », « server overloads » ; une issue ouverte de refmap Mixin cassé. ⚠ chevauche `fast-async-world-save` (même auteur). |
| **fast-async-world-save** | Sérialisation chunks/entités hors thread principal, sauvegarde async toutes les 5 min. Dépend de « Cupboard ». | CurseForge seul (slug `fast-async-world-save-forge-fabric`). ~64,2 M dl. **`github.com/someaddons/FastAsyncWorldSave` également vide de code.** All Rights Reserved. | Non. **Risque de corruption le plus concret des douze** : changelogs mentionnant des correctifs « data corruption prevention » (donc bugs antérieurs réels) ; issue tierce vérifiée (CreeperHost/FTB-Backups-2 #97) documentant une race condition créant des `tmp_*` dans `world/data` qui cassent les backups externes ; issues closes « Invalid null NBT value », « Could Not Save Data ». |
| **swiftgen** | **Accélère la génération sans la changer** — README : *« Faster chunk generation — same exact blocks (…) generated chunks come out bit-identical to what you'd get without it »*. Mixins réels : `MultiNoiseBiomeSourceCacheMixin`, `NoiseFillMixin`, `WorldGenRegionCacheMixin`, `ChunkDecorationTimingMixin` + pont optionnel ReTerraForged (`CellSamplerCacheMixin`, dormant sinon). [V] | <https://github.com/falling-colud/swiftgen> — **MIT vérifié en lisant le fichier** (le détecteur GitHub affiche à tort « NOASSERTION »). CurseForge seul, **6 024 dl**, MAJ 15/07/2026. *Homonymie : le « SwiftGen » iOS pollue toute recherche web.* | Non. Orthogonal à C2ME (cache vs parallélisme). Gain surtout avec ReTerraForged → **intérêt limité en 26.2 vanilla pur**. |
| **zfastnoise** | Remplace `populateNoise`/`populateBiomes` de `NoiseChunkGenerator` et `surfaceBuilder` de `SurfaceBuilder` : moins d'allocations, pas de redimensionnement de palette, comptage de blocs différé/compacté, cache de block states. Parité vanilla revendiquée. | <https://codeberg.org/ZenXArch/FastNoise> — **MPL-2.0**, actif, 43 issues. Modrinth **6,65 M dl**, 150+ versions (1.19 → 26.3). | Non en 26.2. **MAIS 26.3 fait la vraie optimisation en vanilla** : *« Density function and noises are no longer evaluated with double-precision (…) but instead with single-precision »*. ⚠⚠ **Conflits documentés et OUVERTS avec Lithium ET C2ME** (issue #27 : `ArrayIndexOutOfBoundsException` avec les deux ; #25 crash Lithium). Parité imparfaite : #39 ouverte « Vanilla parity issue », #34 fermée idem, #36 « packet corruption on heavily modded biome stacks ». Incompatible assumé : Moonrise, Noisium, Anti-Xray. |
| **fastmapcodec** | Corrige un **O(N²)** dans `com.mojang.serialization.codecs.BaseMapCodec.decode()` (DataFixerUpper) qui gèle le TPS quand un joueur avec un gros fichier d'avancements se connecte. Correctif soumis en amont : Mojang/DataFixerUpper PR #110. | <https://github.com/nekotxs/fastmapcodec> — MIT confirmé. **36 dl seulement.** MC 1.21 → 26.2, Fabric/NeoForge. | Pas encore. **Deviendra obsolète si la PR amont est mergée.** Sur NeoForge, techniques « cursed » via Mixin ; **incompatibilité avec Sodium Extra confirmée dans le texte officiel Modrinth**. Portée très chirurgicale, aucune redondance Lithium/C2ME/FerriteCore. |
| **village-architect** | **Hors sujet — ce n'est pas un mod d'optimisation.** Donne aux villageois une profession « Architecte » qui construit/répare des villages de façon autonome. | CurseForge seul, auteur « Encryptions », **194 dl**, 26.2 + NeoForge uniquement. **Source introuvable, description seule.** All Rights Reserved. *À ne pas confondre avec « Tax' Village Architect » (`taxvillagearchitect`, ~472 K dl), mod différent.* | Sans objet. |

### Arbitrages qui s'imposent

1. **`notick` / `boosters` / `immersive-optimization` font la même chose.** Trois mixins qui annulent le même tick d'entité = comportement imprévisible. **N'en garder qu'un — `immersive-optimization`** (2 M+ dl, actif depuis 2024, scheduler progressif plutôt que couperet par distance, GPL-3.0 donc auditable).
2. **`smooth-chunk-save` / `fast-async-world-save`** : même auteur, même domaine, tous deux **closed-source malgré un dépôt GitHub public qui ne contient rien**, et le second a un historique de corruption documenté. Sur un serveur en prod, c'est un pari sur la parole d'un tiers.
3. **`zfastnoise`** : conflits ouverts avec Lithium **et** C2ME. Si Lanterne vise un stack avec Lithium, c'est éliminatoire en l'état.
4. **`delta-chunk` et `village-architect`** n'ont rien à faire dans une liste d'optimisation serveur.

---

## 4. Sources primaires à garder sous la main

- Primer NeoForge 26.1.x → 26.2 : <https://docs.neoforged.net/primer/docs/26.2/> — brut : `https://raw.githubusercontent.com/neoforged/.github/main/primers/26.2/index.md` (182 Ko). **Pas encore de primer 26.3** (404 au 15/09/2026).
- Changelog vanilla diffé : <https://misode.github.io/versions/?id=26.2&tab=changelog>
- Wiki : <https://minecraft.wiki/w/Java_Edition_26.2>, <https://minecraft.wiki/w/Java_Edition_26.2-snapshot-1>, <https://minecraft.wiki/w/Java_Edition_26.3_Snapshot_7>
- Annonce Mojang du passage à Vulkan (18/02/2026) : <https://www.minecraft.net/en-us/article/another-step-towards-vibrant-visuals-for-java-edition>
- Fabric 26.2 : <https://fabricmc.net/2026/06/15/262.html> (Loom 1.17, Gradle 9.5.1)
- Manifest de versions (source de vérité pour l'ordre des releases) : <https://launchermeta.mojang.com/mc/game/version_manifest_v2.json>
- Builds NeoForge par PR : <https://projects.neoforged.net/neoforged/neoforge>
- Code de référence Vulkan+upscaling : <https://github.com/187J3X1-114514/superresolution>
- Code de référence shaders sur Vulkan : <https://github.com/avpbynf/Vitrail-Shaders>
