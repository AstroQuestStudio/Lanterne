# La seconde mine — les mods que la reconnaissance 26.2 n'avait pas ouverts

> Date : **15 septembre 2026**. Lecture seule : aucun fichier source de Lanterne n'a été touché.
> Complément de `notes/reconnaissance-26-2.md`, qui traitait déjà `superresolution`,
> `vitrail-shaders`, `blockframe-dlss`, `upscaled`, `pureformance`, `notick`, `boosters`,
> `immersive-optimization`, `enttick`, `tt20`, `delta-chunk`, `smooth-chunk-save`,
> `fast-async-world-save`, `swiftgen`, `zfastnoise`, `fastmapcodec`, `village-architect`.
> Ceux-là ne sont pas repris ici.

## Convention

- **[V]** j'ai lu la source primaire : le fichier `LICENSE` du dépôt, le `*.mixins.json`, le code
  Java du mod, ou les sources décompilées de Minecraft 26.2 sous `C:/mc262src/`.
- **[S]** déduit, cohérent, mais non prouvé.
- **[NV]** non vérifiable : pas de source publique, description marketing seule.

**Méthode de licence.** La licence retenue est celle **lue dans le fichier `LICENSE` du dépôt**,
jamais l'étiquette CurseForge ni le détecteur automatique de GitHub. Les 44 dépôts ont été clonés
en `--depth 1 --filter=blob:none` dans `C:/tmp/lanterne-recon/r/` (hors du dépôt Lanterne) et leurs
fichiers de licence lus caractère par caractère. **Trois divergences réelles ont été trouvées, et
elles changent la décision dans les deux sens** (voir §6).

Rappel du cadre : Lanterne est **GPL-3.0-only**. Il peut absorber MIT, BSD, Apache-2.0, MPL-2.0,
CC0, LGPL-2.1, LGPL-3.0, GPL-3.0 — avec crédit dans `NOTICE.md`. Il ne peut pas absorber
GPL-2.0-only, AGPL, CC-BY-NC/ND, PolyForm, ni All Rights Reserved.

---

## 1. Ce que Mojang a corrigé lui-même en 26.2 — et qui périme des mods entiers

C'est la partie la plus rentable de cette reconnaissance. **Cinq mods au moins de la liste n'ont
plus d'objet en 26.2, non pas parce qu'ils sont mauvais, mais parce que le problème qu'ils
réparaient a disparu.** Tout est vérifié dans `C:/mc262src/`.

### 1.1 Le limiteur d'images a été réécrit par Mojang — et il est bon [V]

`C:/mc262src/net/minecraft/client/FramerateLimiter.java` — **[V]** la classe existe et contient
l'algorithme ci-dessous. `com.mojang.blaze3d.platform.Window#limitDisplayFPS()` **n'existe plus**
(vérifié par `grep` sur `Window.java` : aucune occurrence).

**[S]** La classe est apparue en 26.1 et non en 26.2 — `lite-fps` a une variante qui `@Overwrite`
`net.minecraft.client.FramerateLimiter` « pour les versions ≥ 26.1 » afin d'y remplacer
`glfwGetTime()` par `System.nanoTime()`. Je n'ai pas les sources 26.1 sous la main pour le vérifier.
**Ce qui est certain, parce que je l'ai lu**, c'est que **la version 26.2 n'utilise plus
`glfwGetTime()` du tout** : le correctif est donc déjà dedans.

Le corps réel de `FramerateLimiter.limitDisplayFPS(int)` :

```java
long remainingTimeNs;
while ((remainingTimeNs = targetTimeNs - System.nanoTime()) > 0L) {
    if (remainingTimeNs > averageOvershootNs + 500000L) {
        LockSupport.parkNanos(expectedSleepTimeNs);
        long currentOvershootNs = sleepDurationNs - expectedSleepTimeNs;
        if (currentOvershootNs > 0L && currentOvershootNs < 25000000L) {
            averageOvershootNs = (long)(0.1 * currentOvershootNs + 0.9 * averageOvershootNs);
            averageOvershootNs = Math.min(averageOvershootNs, 2000000L);
        }
    } else {
        Thread.onSpinWait();
    }
}
```

C'est exactement ce que vendent `stable-fps`, `lite-fps`, `framepacer`, `fps-tune` : `System.nanoTime()`
au lieu de `glfwGetTime()`, `LockSupport.parkNanos` plutôt qu'un `Thread.sleep` grossier, un
estimateur de dépassement de sommeil en moyenne glissante (0,1 / 0,9), une marge de sécurité de
500 µs avant de passer en attente active, et un plafond anti-dérive à 25 ms / 2 ms.

**Conséquence : toute la famille « frame pacing » est morte en 26.2.** Le code d'`@Overwrite` de
`stable-fps` que j'ai lu (`FramerateLimiterMixin.java`, `@Overwrite public void limitDisplayFPS()`)
cible une méthode qui n'existe plus.

### 1.2 Les particules sont déjà plafonnées et déjà cullées par le frustum [V]

`C:/mc262src/net/minecraft/client/particle/ParticleGroup.java` :

```java
private static final int MAX_PARTICLES = 16384;
private static final int RESERVOIR_SIZE = 4096;
private static final int RESERVOIR_START = 12288;
public boolean add(Particle particle) {
    int currentSize = this.particles.size();
    if (currentSize >= 16384) return false;
    if (currentSize >= 12288) {
        float freeSpace = (16384 - currentSize) / 4096.0F;
        if (this.engine.getRandom().nextFloat() >= freeSpace * freeSpace) return false;
    }
    ...
}
```

`C:/mc262src/net/minecraft/client/particle/QuadParticleGroup.java`, ligne 26 :

```java
if (frustum.pointInFrustum(particle.x, particle.y, particle.z)) { ... }
```

Et `ParticleEngine#extract(ParticlesRenderState, Frustum, Camera, float)` reçoit le frustum, avec
un `ParticleLimit` par type (`ParticleEngine#hasSpaceInParticleLimit`,
`net/minecraft/core/particles/ParticleLimit.java`).

Autrement dit vanilla 26.2 fait déjà, nativement : **plafond global par groupe de rendu, rejet
probabiliste progressif à l'approche du plafond, limite par type de particule, et culling par
frustum au moment de l'extraction.** C'est la liste de fonctionnalités de `particle-core` et de
`smart-particles`, mot pour mot.

### 1.3 Les chunks du spawn ne sont plus chargés en permanence [V]

`C:/mc262src/net/minecraft/server/MinecraftServer.java`, `prepareLevels()` ligne 548 : il ne charge
**plus** de carré de 441 chunks autour du spawn. Il ne fait que réactiver les *tickets sauvegardés*
(`TicketStorage#activateAllDeactivatedTickets`) et attendre que ceux-là atteignent `FULL`, via la
classe nouvelle `net/minecraft/server/level/ChunkLoadCounter.java`.

Corroboration : `net/minecraft/server/level/TicketType.java` ne contient **aucun** `TicketType.START`
(les types sont `PLAYER_SPAWN`, `SPAWN_SEARCH`, `DRAGON`, `PLAYER_LOADING`, `PLAYER_SIMULATION`,
`FORCED`, `PORTAL`, `ENDER_PEARL`, `UNKNOWN`) ; et la gamerule `spawnChunkRadius` est **supprimée
par un datafixer** (`net/minecraft/util/datafix/fixes/GameRuleRegistryFix.java` : `gameRules.remove("spawnChunkRadius")`).

**Conséquence : `ksyxis` n'a plus d'objet en 26.2.** Son idée entière — « supprimer le chargement
des chunks du spawn au démarrage » — décrit un comportement que Mojang a retiré. Il reste à
`ksyxis` l'écran « Preparing spawn area », c'est-à-dire du cosmétique.

> ⚠ `notes/MINE.md` classe `ksyxis` en **PRENDRE**. **Cette entrée est à corriger.**

### 1.4 Les entités et les block entities sont déjà cullées par le frustum, et le point d'accroche a changé [V]

`C:/mc262src/net/minecraft/client/renderer/extract/LevelExtractor.java` :

```java
public boolean isEntityVisible(Entity entity, Frustum frustum, double camX, double camY, double camZ)   // l.259
private void extractVisibleEntities(Camera camera, Frustum frustum, DeltaTracker, LevelRenderState)     // l.229
private void extractVisibleBlockEntities(Camera, float, LevelRenderState, @Nullable Frustum cullFrustum)// l.281
```

Deux choses en découlent.

1. **Le culling par frustum n'est plus une valeur ajoutée** : c'est vanilla, et ça se passe pendant
   la phase d'extraction, hors du rendu. C'est le socle que `chloride` et `pureformance` vendent.
2. **`LevelExtractor#isEntityVisible` est le point d'accroche propre, en 26.2, pour le culling par
   occlusion** — c'est-à-dire le seul culling qui reste à faire (ne pas dessiner ce qui est derrière
   un mur). C'est beaucoup plus net que l'ancien `LevelRenderer#renderEntity`. **Cette signature est
   à donner au Voile (`core/Shroud.java` + `mixin/EntityCullMixin.java`) lors de la migration.**

### 1.5 La redstone « non-locationnelle » est dans vanilla, derrière un drapeau [V]

`C:/mc262src/net/minecraft/world/level/redstone/` contient
`DefaultRedstoneWireEvaluator.java` **et** `ExperimentalRedstoneWireEvaluator.java`, et
`RedStoneWireBlock.java` ligne 354 :

```java
private static boolean useExperimentalEvaluator(Level level) {
    return level.enabledFeatures().contains(FeatureFlags.REDSTONE_EXPERIMENTS);
}
```

C'est l'idée d'`alternate-current` (évaluation par graphe, indépendante de la position), portée en
vanilla depuis 1.21.2 et toujours là en 26.2, mais **derrière le feature flag
`redstone_experiments`**, donc inactive par défaut.

Le primer 26.2 ajoute par ailleurs `BlockBehaviour#ownSignal` / `$BlockStateBase#getOwnSignal` —
confirmé dans le code : `RedStoneWireBlock#ownSignal(BlockState, BlockGetter, BlockPos)` ligne 381.

### 1.6 La fusion des items au sol est déjà ralentie par vanilla [V]

`C:/mc262src/net/minecraft/world/entity/item/ItemEntity.java`, `tick()` :

```java
int rate = moved ? 2 : 40;
if (this.tickCount % rate == 0 && !this.level().isClientSide() && this.isMergable()) {
    this.mergeWithNeighbours();
}
```

Vanilla 26.2 ne tente la fusion que **tous les 40 ticks** quand l'item n'a pas changé de bloc. Une
pile d'items immobile dans une ferme coûte donc vingt fois moins qu'avant. C'est l'essentiel du gain
que `get-it-together-drops` apportait historiquement — et Lanterne a déjà `core/Gather.java`
(mesuré ×1,27 à ×1,42 selon `MINE.md`).

### 1.7 Les shaders sont compilés par le jeu, et la « carte graphique » est devenue abstraite

Rappel de la reconnaissance précédente, confirmé ici par l'arborescence :
`C:/mc262src/com/mojang/blaze3d/opengl/` contient `GlHeuristics.java`, `GlDevice.java`,
`GlCommandEncoder.java`, `GlSurface.java`, `BufferStorage.java`, `FrameBufferCache.java`,
`GlTransientMemory.java`. La contrepartie Vulkan existe en parallèle. **Tout mixin ciblant
`com.mojang.blaze3d.opengl.*` est inactif quand le joueur passe en Vulkan** — c'est le cas de deux
des mixins d'`immediatelyfast` et de l'unique mixin de `framepace`.

---

## 2. Le tableau

Légende « déjà dans Lanterne » : le nom donné est celui du module existant dans
`src/main/java/fr/clubcitrouille/lanterne/`.

### 2.1 Client — rendu

| slug | idée technique réelle | dépôt source | licence lue dans `LICENSE` | déjà dans vanilla 26.2 ? | déjà dans Lanterne ? | verdict |
|---|---|---|---|---|---|---|
| **sodium** | Moteur de rendu de chunks de remplacement (~50 k lignes) : format de sommets compact, arènes de mémoire GPU, tri de translucidité, occlusion par graphe de sections. Support Vulkan précoce annoncé. [V] | `CaffeineMC/sodium` | **PolyForm Shield 1.0.0** [V] — non-OSI, interdit d'en faire un produit concurrent | non | non | **à côté** — rien à absorber, licence bloquante et réécriture insensée |
| **immediatelyfast** | En 26.2 ce n'est **plus** le remplacement de `MultiBufferSource` (classe supprimée en 26.2, vérifié absente de `C:/mc262src/`). Les 22 mixins actuels [V] portent sur : `enhanced_batching.MixinRenderTypeFeatureRenderer_Group` + `MixinScissorState` (regroupement dans le nouveau système `FeatureRenderer`), `map_atlas_generation.*` (5 mixins : atlas unique pour toutes les cartes au lieu d'une texture par carte), `font_atlas_resizing.MixinFontTexture`, `fast_text_lookup.*`, `sign_text_buffering.*`, `skip_text_translucency_sorting.MixinRenderTypes`, `avoid_redundant_framebuffer_switching.MixinGlCommandEncoder`/`MixinGlSurface` | `RaphiMC/ImmediatelyFast` | **LGPL-3.0** [V] | non | non | **PRENDRE, mais ciblé** — voir §3 |
| **moreculling** | Culling de faces de blocs : formes de culling personnalisées par bloc (torche, chaudron, tige de chorus…), cache d'opacité de sprite, cache de modèles, culling des cadres et des peintures, culling des feuilles par type, pluie. 139 fichiers Java, **entièrement porté sur le pipeline 26.2** (`LevelExtractor_frustumMixin`, `FeatureRenderDispatcher_cullMixin`, `BlockModelRenderState_cullMixin`, `SubmitNodeCollection_apiMixin`) [V] | `fxmorin/moreculling` | **GPL-3.0** [V] | partiellement (frustum oui, formes de culling non) | `client/Foliage.java` couvre les feuilles seulement | **PRENDRE — avec une réserve morale** : le README dit *« I don't allow clients or other mods to merge this mod without permission »*. La GPL-3.0 l'autorise, l'auteur ne le souhaite pas. **Demander.** |
| **particle-core** | 21 mixins client : cache de luminosité par particule, cache de position, distance de rendu configurable par type, culling par frustum, plafond par type, extraction asynchrone. [V] | `fzzyhmstrs/pc` | **MIT** [V] | **oui pour l'essentiel** (§1.2) | `mixin/ParticleVeilMixin.java` | **NE PAS PRENDRE en bloc.** Seul le **cache de luminosité** (`ParticleBrightnessCacheMixin`) reste hors vanilla. |
| **smart-particles** | Plafonne le nombre de particules et retire les *moins importantes* (proches/visibles d'abord) au lieu de refuser au hasard. En 26.2 le mod ne contient plus que `ParticleManagerMixin` + 3 accessors. [V] | `chedidandrew/Smart_Particles` (mono-dépôt à 897 fichiers, un dossier par version) | **MIT** [V] — un `LICENSE` par dossier de version, aucun à la racine | **oui** (§1.2) — vanilla rejette au hasard *avec pondération quadratique*, pas bêtement | non | **NON** — doublon direct de `particle-core` et de vanilla. Son propre README admet : *« NeoForge gameplay validation: a dedicated in-game smoke test is not yet recorded »* |
| **asyncparticles** | Fait ticker et extraire les particules sur d'autres fils. Machinerie lourde et inhabituelle : `ClassAdjusterMixinPlugin`, `ClassRenamer`, `MixinMemberCanceller`, `MixinTransformerExtension`, et des paires de mixins `*_SafeBlockEntityMap_On`/`_Off`, `*_SafeClassInstanceMultiMap_On`/`_Off` — le mod **réécrit des structures de données du jeu pour les rendre thread-safe**. 186 fichiers Java. [V] | `Harveykang/AsyncParticles` | **LGPL-3.0** [V] | non | non | **NON** — risque de course de données sur `LevelChunk`, `LevelChunkSection`, `ClassInstanceMultiMap`. Sur un serveur de référence c'est un pari. Actif (push 14/09/2026), déjà en 26.3. |
| **badoptimizations** | Trois idées réelles [V] : (1) **cache de lightmap** — ne pas recalculer/téléverser la texture de lightmap si rien n'a changé (gamma, effets, dimension) : `tick.MixinLightmapExtractor` ; (2) ne pas exécuter les quatre renderers de debug (abeilles, gameevents, gametest, IA villageois) quand il n'y a rien à afficher : `debug.MixinDebugRenderer` ; (3) cache de couleur du ciel — **l'auteur le marque « 1.21.10 and below »**, donc mort en 26.2. Plus des micro-choses sur les toasts et le tutoriel. | `ItsThosea/BadOpitmizations` (typo dans le nom du dépôt) | **MIT** [V] | (3) oui ; (1) et (2) non | non | **PRENDRE le cache de lightmap uniquement.** Le banc de l'auteur va de 1926 à 2008 FPS : **+4 %**, mesuré à 2000 FPS. Honnête et petit. |
| **better-block-entities** | Renderer hybride : convertit les block entities statiques (coffres, panneaux, bannières, pots, lutrins, étagères) en géométrie de section Sodium, et ne garde le renderer dynamique que pour celles qui bougent. 41 mixins dont 8 spécifiques à Sodium. [V] | `ceeden/betterblockentities` | **LGPL-3.0** [V] | non | `mixin/StaticChestRendererMixin.java`, `StaticChestShapeMixin.java`, `BlockEntityVeilMixin.java` (coffres) | **MESURER** — même famille que Faster Block Entities déjà absorbé. **Dépendance dure à Sodium** (`SodiumWorldRendererMixin`, `RenderSectionManagerMixin`, `TranslucentGeometryCollectorMixin`). |
| **enhanced-block-entities-reloaded** | Port non officiel d'**Enhanced Block Entities** (`FoundationGames/EnhancedBlockEntities`, **LGPL-3.0-only**, arrêté en Fabric/Quilt ≤ 1.21.4). Remplace les modèles d'entités par des modèles de blocs *baked*. 16 mixins dont `BuiltChunkMixin`/`RenderSectionMixin` Sodium ; multi-version par stonecutter (`versions/26.2-neoforge`, `26.3-fabric`). Le port **respecte la licence** : même LGPL-3.0, package `foundationgames.*` conservé. [V] | `Mat0u5/EnhancedBlockEntitiesReloaded` | **LGPL-3.0** [V] | non | idem | **NON** — 11 189 téléchargements, port non officiel, recouvre `better-block-entities` **et** Faster Block Entities déjà absorbé. **Trois mods pour le même travail : n'en garder qu'un.** Techniquement absorbable, mais ce serait le troisième. |
| **fast-items** | Rend les items au sol en 2D (un seul quad) au lieu du modèle 3D empilé. **2 mixins seulement** : `ItemEntityRendererMixin`, `ItemRendererMixin`. [V] | `Noryea/fast-items-fabric` | **CC0-1.0** [V] | non | `mixin/LooseItemMixin.java` + `core/Spill.java` | **déjà couvert** — `MINE.md` le notait déjà, `NOTICE.md` confirme « mécanisme différent ». **Dernier commit 04/03/2025, `game_versions` s'arrête avant 26.2.** Mort. |
| **gnetum** | Étale la mise à jour du HUD sur plusieurs images : deux framebuffers (avant/arrière), N passes par mise à jour complète, échange à la fin, plus un limiteur de FPS propre au HUD. 77 fichiers Java, 30 mixins, compat Jade/Xaero/JourneyMap/Sodium. [V] | `decce6/Gnetum` | **LGPL-3.0** [V] | non | non | **PRENDRE** — même famille qu'`exordium` (déjà PRENDRE dans `MINE.md`). **Choisir l'un des deux, pas les deux** : ils rendent tous les deux le HUD hors-cadence et se marcheraient dessus. |
| **framepacer** | Annonce un « hybrid sleep algorithm ». **Aucune preuve technique : il n'existe aucun dépôt.** Le compte GitHub de l'auteur existe et a **zéro dépôt public** [V]. | **source introuvable, description seule** | MIT **affiché sur Modrinth, mais adossé à aucun texte** — juridiquement nul | §1.1 : réglé en vanilla | non | **NON** — voir §1.1. Et il n'y a littéralement rien à lire. |
| **framepace** | Un seul mixin, 2 fichiers Java. Redirige la lecture de `GLCapabilities.GL_ARB_buffer_storage` dans `com.mojang.blaze3d.opengl.BufferStorage#create` pour **désactiver le buffer storage immuable sur NVIDIA et sur les Intel Gen7**. L'en-tête du fichier dit : *« Adapted from com.mojang.renderpearl.backend.opengl.GlHeuristics as of Minecraft 26.3 Snapshot 9 »*. [V] | `lap2ka/framepace` | **MIT** [V] | **pas en 26.2, oui en 26.3** — `C:/mc262src/com/mojang/blaze3d/opengl/GlHeuristics.java` n'a que `isGlOnDx12()` et `isAmd()`, pas `isNvidia()` ni `couldBeIntelGen7()` [V] | non | **PRENDRE — ~30 lignes.** C'est un rétroportage d'un correctif Mojang (MC-307596). Gain réel sur le *frame pacing* quand on est limité par le GPU, typiquement avec shaders. **N'agit que sur OpenGL.** |
| **stable-fps** | Deux choses distinctes. (a) `@Overwrite` de `Window#limitDisplayFPS` — **cible morte en 26.2**. (b) Dans sa variante `WindowMixin-26.2.java` : déplace `glfwCreateWindow` **et la boucle `glfwPollEvents`** sur un **fil d'entrée séparé**, avec `CountDownLatch` et file d'événements. [V] | `lunaticbonnie/stable-fps` | **MIT** [V] | (a) oui, §1.1 | non | **NON** en l'état. (b) est l'idée d'Ixeris, mais GLFW impose que la fenêtre soit créée et scrutée depuis le fil principal sur macOS — c'est une violation documentée. Le dépôt utilise un système de surcouche de fichiers `.csv`/`.remove`/`-26.2` très difficile à auditer. |
| **lite-fps** | `FramerateLimiterMixin` avec `@Overwrite` : sur `Window#limitDisplayFPS` (≤ 1.21.11) **et sur `net.minecraft.client.FramerateLimiter` (≥ 26.1)**, pour remplacer `GLFW.glfwGetTime()` par `System.nanoTime()`. [V] Même auteur et même système de gabarits (`mod_overrides/`, `.csv`) que `stable-fps`. | `lunaticbonnie/lite-fps` | **MIT** [V] | **oui en 26.2** — j'ai lu le corps de `FramerateLimiter#limitDisplayFPS(int)` dans `C:/mc262src/` : il utilise déjà `System.nanoTime()` + `LockSupport.parkNanos` + estimateur de dépassement. [V] | non | **NON** — la correction que ce mod porte a été faite par Mojang **entre 26.1 et 26.2**. C'est un rétroportage devenu inutile. |
| **interframe** | **De la vraie génération d'images intermédiaires, pas du marketing** [V] : `MixinWindow` sur `Window#updateDisplay` (HEAD) + `MixinLevelRenderer`, pilotant `FrameGenerator`, `BlendReprojectSynthesizer` (reprojection + mélange) **et `OnnxSynthesizer` (réseau de neurones ONNX)**, avec `CameraSnapshot` et des shaders GL. | `falling-colud/interframe` | **MIT** [V] | non | non | **INSTRUIRE — c'est le mod le plus intéressant de tout le lot pour ton objectif d'upscaling.** MIT, donc absorbable sans contrainte. 8 839 téléchargements, NeoForge 26.2, 15/07/2026. Voir §3.10. *(Même auteur que `swiftgen`, déjà instruit dans la reconnaissance précédente.)* |
| **fps-tune** | **2 mixins, et ils font exactement ce que la description dit** [V] : `@Mixin(ParticleEngine.class)` sur `tick`, et `@Mixin(WeatherEffectRenderer.class)` sur `render` en HEAD *cancellable*. Autrement dit : quand ça rame, on arrête de ticker les particules et de dessiner la pluie. | `imCinq/fps-tune` | **MIT** [V] | largement (§1.2 pour les particules) | `core/Shroud.java` + `mixin/ParticleVeilMixin.java` ; `moreculling` a `WeatherEffectRenderer_rainMixin` | **NON — mais c'est le dépôt le plus propre du lot FPS** (CI, tests, docs) pour seulement 1 023 téléchargements. L'idée « couper la pluie sous pression » vaut trois lignes dans `TickBudget`. |
| **fps-boost** | **Source introuvable, description seule.** Dépend d'un « TCTCore » externe, même auteur que `tct-clearlag`. | aucun | aucune licence publiée → **ARR** | — | — | **NON** — rien à lire, rien à affirmer. 1,41 M de téléchargements qui ne prouvent rien. |
| **fps-booster-triio** | **Source introuvable, description seule.** L'auteur `RealZeio` est introuvable sur GitHub et sur Modrinth. | aucun | aucune → **ARR** | — | — | **NON** |
| **reflex-antilag** | NVIDIA Reflex : un seul mixin (`MinecraftClientMixin`), 11 fichiers Java. | `Tythee/Minecraft-Reflex` | **`LICENSE` contient littéralement « All rights reserved »** [V] | non | non | **NON absorbable.** L'idée (marquer les points de simulation/rendu pour le pilote) n'est utile que sur NVIDIA et exige un binding natif. `MINE.md` le classait MESURER : la licence tranche. |
| **scalablelux** | Port du moteur de lumière **Starlight** (Spottedleaf) : files séparées montée/descente de lumière, propagation sans re-scan. Le package du mod est littéralement `ca.spottedleaf.starlight.mixin`. 13 mixins communs + 3 client. [V] | `RelativityMC/ScalableLux` | **LGPL-3.0** [V] | **oui, pour l'essentiel** | non | **NON** — `notes/recherche-client.md` (l.401-405) a déjà établi, sources à l'appui, que **vanilla a absorbé les idées centrales de Starlight depuis 1.20** et que Starlight est archivé. `MINE.md` le classait « le candidat sérieux » : **à déclasser.** |
| **chloride** | Addon Sodium : culling d'entités et de block entities par distance avec liste blanche, culling des feuilles, modèles solides pour coffres et lits (*« Bed (below mc26.2) »* — donc déjà mort en 26.2 pour les lits), masquage des overlays JEI/REI/EMI. [V] | `SrRapero720/chloride` — **le code est public mais il n'y a AUCUN fichier de licence dans le dépôt** [V] ; Modrinth déclare All Rights Reserved | **All Rights Reserved** (absence de licence = tous droits réservés) | frustum oui, distance non | `core/Shroud.java`, `client/Foliage.java`, `StaticChest*Mixin` | **NON absorbable**, et de toute façon **entièrement recouvert** par le Voile et le Feuillage. |
| **ferritecore** | Déduplication mémoire : `BlockStateCacheMixin` (cache partagé des états), `FastMapStateHolderMixin` (table de transition compacte au lieu d'une `ImmutableMap` par état), `PalettedContainerMixin`, `PatchedDataComponentMapMixin`, accesseurs de `VoxelShape`. [V] | `malte0811/FerriteCore` | **MIT** [V] | non | **oui** — `core/Moulds.java` + `mixin/StateCacheDedupMixin.java` (cible `BlockBehaviour$BlockStateBase$Cache`) | **partiellement absorbé.** Reste à prendre : le `FastMap` (la table de transition) et `PatchedDataComponentMapMixin`. Dernier push 24/03/2026. |
| **smooth-join** | « ajuste dynamiquement la distance de rendu » à la connexion. **Source introuvable, description seule** : l'auteur `ZhiMeow1120` est introuvable sur GitHub. [V] | aucun | **All Rights Reserved** [V, Modrinth, `source_url: null`] | non | `core/Cadence.java` / `TickBudget` font l'équivalent côté serveur | **NON** — pas de source, et l'idée est triviale (monter la distance de rendu par paliers pendant N secondes après connexion). **À refaire en 20 lignes si on la veut.** |
| **falling-blocks-fps-fix** | « supprime le gravier et le sable tombants ». **Le dépôt annoncé est une façade** : `SmartStreamLabs/Gravel-fps-fix` fait **1 Ko** et ne contient qu'un `README.md` et un fichier vide nommé `SOON`. Aucun code. [V] | dépôt-vitrine sans code | **aucun fichier `LICENSE`** (404 sur les cinq variantes) → **ARR** [V] | non | non | **NON** — et ce n'est de toute façon pas une optimisation mais la suppression d'une mécanique : ça casse toutes les fermes à gravier. 144 k téléchargements pour un dépôt vide. |

### 2.2 Serveur — monde, entités, réseau

| slug | idée technique réelle | dépôt source | licence lue dans `LICENSE` | déjà dans vanilla 26.2 ? | déjà dans Lanterne ? | verdict |
|---|---|---|---|---|---|---|
| **servercore** | **Le plus riche de la liste.** Quatre blocs distincts [V, `servercore.common.mixins.json` et arborescence lus] : (1) **Activation Range** — port de l'idée Spigot/Paper : chaque entité a un rayon d'activation par type, hors rayon on ne fait qu'un « tick inactif » réduit (18 mixins `inactive_ticks.*` : `MobMixin`, `GoalSelectorMixin`, `LivingEntityMixin`, `ArrowMixin`, `BeeMixin`, `VillagerMixin`, `ItemEntityMixin`…) ; (2) **DynamicManager** — quatre réglages asservis au temps de tick : `MOBCAP_PERCENTAGE`, `CHUNK_TICK_DISTANCE`, `SIMULATION_DISTANCE`, `VIEW_DISTANCE`, avec bornes min/max et pas de 10 ; pont optionnel vers **spark** (`SparkDynamicManager`) ; (3) **optimisations sans effet de jeu** : `optimizations/sync_loads/*` (7 mixins qui empêchent des chargements de chunk **synchrones** depuis `Bee`, `MapItem`, `SleepInBed`, `StructureCheck`, `DynamicGameEventListener`, `RemoveBlockGoal`), `optimizations/ticking/chunk/random/*`, `optimizations/biome_lookups/NaturalSpawnerMixin`, `optimizations/tickets/*`, `optimizations/misc/PathFinderMixin` ; (4) features de confort : plafond de reproduction, lobotomie des villageois 1×1, `/mobcaps`. | `Wesley1808/ServerCore` | **MIT**, avec une nuance lue dans le README : *« If a file uses the GPL-3.0 license it will be stated at the top. All other files are licensed under MIT. »* — le dépôt contient `licenses/MIT.md` **et** `licenses/GPL.md`. J'ai balayé les 134 `.java` : **aucun n'a d'en-tête GPL** (un seul faux positif sur un mot-clé). [V] | non | recouvrement partiel : `core/EntityThrottle.java`, `Cadence.java`, `TickBudget.java`, `Crowd.java`, `Census.java`, `Pasture.java` | **PRENDRE, en priorité — c'est la meilleure mine de la liste.** Voir §3. |
| **adaptive-performance-tweaks** | Mod serveur adaptatif : contrôle du spawn, adaptation des gamerules, nettoyage des items/XP/flèches, protection des nouveaux joueurs, distance de simulation adaptative « movement-aware », étranglement optionnel de l'IA et de la génération de chunks. 193 fichiers Java. Le README avertit lui-même : *« 12.x is still an early-release »*, *« There is no in-game config GUI yet »*. [V] | `MarkusBordihn/BOs-Adaptive-Performance-Tweaks` | **MIT** [V] (`LICENSE.md`, avec la réserve *« This license applies only to the code in this repository. Images, models and other assets are expl[icitly excluded] »*) | non | `core/Cadence.java`, `TickBudget.java`, `Crowd.java`, `Broom.java`, `Sweeper.java`, `Clump.java` | **NON, et c'est un piège de doublon.** Il fait la *même* boucle d'asservissement que `servercore` **et** que `core/Cadence.java`. Deux régulateurs qui modifient `simulationDistance` en même temps oscillent. `notes/recherche-serveur.md` §1.3 et §2.5 l'avait déjà jugé « radicalement différent et plus risqué ». |
| **lithium** | 100+ modules. Inventaire vérifié : `ai/*` (pathing, poi, sensor, task, raid), `alloc/*`, `block/{hopper,fluid,redstone_wire,flatten_states,moving_block_shapes}`, `chunk/{palette,serialization,no_locking,entity_class_groups}`, `collections/*`, `entity/{collisions,fast_retrieval,inactive_navigations,…}`, `math/{fast_blockpos,sine_lut,fast_util}`, `shapes/*`, `world/{tick_scheduler,raycast,explosions,block_entity_ticking,…}`. [V] | `caffeinemc/lithium-fabric` | **LGPL-3.0** [V] | non | déjà crédité dans `NOTICE.md` | **à côté, et à piocher module par module** — comme déjà décidé. `notes/recherche-serveur.md` a déjà instruit `block/fluid`, `world/tick_scheduler`, `entity/collisions`. |
| **krypton-fnp** (Krypton Reno) | Portage NeoForge de Krypton **plus** trois ajouts : (a) micro-optimisations de sérialisation réseau — `VarIntMixin`, `VarLongMixin`, `Utf8StringMixin`, `Varint21FrameDecoderMixin`, `PacketProcessorMixin` ; (b) **culling d'entités côté serveur** — `ServerCullingManager` branché sur `TrackedEntityMixin`/`ServerEntityMixin`/`EntityCullingMixin` : on n'envoie pas les paquets de suivi d'une entité que le joueur ne peut pas voir ; (c) RFC 8305 Happy Eyeballs, et une bibliothèque native « RecastLib » pour Windows x64/arm64. `compatibilityLevel: JAVA_25`. [V] | `404Setup/KryptonReno` | **LGPL-3.0** [V] | non | `core/NetworkThrottle.java`, `mixin/CompressionMixin.java`, `NetworkRegistryMixin.java` | **PRENDRE la partie (b) — c'est la meilleure idée serveur de la liste pour un VPS mono-cœur.** Voir §3. La partie (a) est du gain marginal ; la partie (c) tire une dépendance native. |
| **chunk-sending** | **7 mixins** [V] : `PlayerChunkSender`, `ChunkHolder`, `ChunkMap`, `LevelChunk`, `ChunkAccess` (mise en cache des paquets de chunk déjà sérialisés) + `ServerEntity`. | `someaddons/chunksending` — code sur les branches de version (`neo1.21`, `neo26.1`), **pas sur `main`** | **aucun fichier `LICENSE`** ; `neoforge.mods.toml` déclare `license="ARR"` **de la main de l'auteur** [V] | non | `core/Relay.java`, `NetworkThrottle.java`, `mixin/ChunkMapMixin.java` | **NON absorbable.** L'idée est excellente et parfaitement adaptée à un VPS mono-cœur (**ne pas re-sérialiser un chunk pour chaque joueur**), mais elle est à **réécrire**, pas à copier. Poussé le 15/09/2026, le plus vivant des six. |
| **connectivity** | **26 mixins sur la pile réseau** [V] : `FriendlyByteBuf`, `NbtAccounter`, `ClientboundLevelChunkPacketData`, `Connection$1` (le handler de timeout), `ServerLoginPacketListenerImpl`, `CompressionDecoder`/`Encoder`, `PacketDecoder`/`Encoder`, plus des sous-paquets `malformedtraffic` et `networkstats`. | `someaddons/connectivity` — idem, code sur les branches de version | **aucun fichier `LICENSE`** ; `mods.toml` : `license="ARR"` [V] | non | `mixin/CompressionMixin.java`, `NetworkRegistryMixin.java`, `core/NetworkThrottle.java` | **NON absorbable.** ⚠ **Et attention à la fraîcheur** : 161,2 M de téléchargements, mais **dernière mise à jour le 03/04/2026** et **pas de branche `neo26.2`** — le jar `26.1-7.6` couvre 26.2 par tolérance de plage, pas par portage. |
| **disconnect-packet-fix** | Contourne **MC-271325** : sur Fabric/NeoForge, le paquet `clientbound/minecraft:disconnect` échoue parce que le codec de composants n'est pas encore lié à la phase de configuration. **Un seul mixin sur `net.minecraft.network.Connection`, 4 fichiers Java au total.** [V] ; `Connection.java` existe bien en 26.2 [V] | `ascpixi/disconnect-packet-fix` | **MIT** [V] | non ([S] : le bug est toujours ouvert) | non | **PRENDRE — une trentaine de lignes.** Déjà PRENDRE dans `MINE.md`, confirmé. **Un serveur qui n'arrive pas à dire pourquoi il expulse un joueur est un serveur qu'on ne peut pas déboguer.** |
| **multicore-helper** | Vrai mod Forge à événements, **aucun mixin**, package laissé à `com.example.forcedmulticore` (vérifié par décompilation du jar). [V] Il existe **bel et bien** en 1.7.10, 1.12.2 et 1.16.5 (`forced-multicore-cpu-forge-1.16.5-1.0.0` → `1.0.7`), plus un jar Fabric 26.2. | **source introuvable** — le compte de l'auteur a zéro dépôt public [V] | aucune → **ARR** | — | `core/Threads.java`, `mixin/ThreadCountMixin.java` | **NON.** Un package `com.example.*` laissé tel quel en dit long sur le soin apporté. Le réglage des pools de fils est déjà chez Lanterne, et sur un VPS **mono-cœur** un « multicore helper » est un contresens. |
| **let-me-despawn** | Un seul mixin de fond (`MobMixin`, 10 fichiers Java au total) : `@Redirect` sur `Mob#discard()` dans `checkDespawn` pour **faire tomber l'équipement avant la disparition**, plus `@Inject` sur `setItemSlotAndDropWhenKilled` pour **ne pas marquer un mob persistant** quand il ramasse un objet. [V] ; `Mob#checkDespawn()` (l.715), `removeWhenFarAway(double)` (l.706), `setItemSlotAndDropWhenKilled` existent en 26.2 [V] | `frikinjay/let-me-despawn` | **LGPL-3.0** [V] | non | `core/Crowd.java`, `Census.java` (vigie de spawn), pas le despawn | **PRENDRE l'idée (2)** : *« un zombie qui a ramassé une pelle ne doit pas devenir immortel »* est **la** cause n°1 de dérive du nombre de mobs sur un serveur ancien. C'est ~40 lignes. `MINE.md` le classait « non, même cible que despawn-tweaks » : **les deux existent, mais celui-ci est vivant en 26.2 et `despawn-tweaks` s'arrête en 1.21.1.** |
| **easy-mob-spawn-control** | Cibles identifiées **par lecture du bytecode du jar** [V] : `NaturalSpawner`, `MobCategory`, `ServerChunkCache`, `Raid`, `EnderDragonFight`. | **source introuvable** — le compte GitHub `Catomon` est actif (7 dépôts) mais **aucun ne correspond**, alors que le package du jar est `io.github.catomon.easymobspawncontrol` [V] | **All Rights Reserved** [V, `source_url: null`] | non | `core/Census.java`, `Crowd.java`, `mixin/SpawnGuardMixin.java`, `NaturalSpawnerMixin.java` | **NON** — et c'est dommage : ses cibles de mixin sont **exactement** le terrain de la Vigie. Rien à copier, tout est déjà couvert. |
| **get-it-together-drops** | `@Inject(HEAD, cancellable)` sur `ItemEntity#mergeWithNeighbours`, remplacé par une boucle avec un **rayon configurable** (défaut vanilla : 0,5) et deux tags (`IGNORED`, `DO_NOT_COMBINE`). J'ai lu les 36 lignes du mixin. [V] | `bl4ckscor3/GetItTogetherDrops` | **MIT** [V] | **partiellement** (§1.6 : cadence 1/40 en vanilla) | **oui** — `core/Gather.java` + `mixin/ItemMergeMixin.java`, mesuré ×1,27 à ×1,42 | **NON** — déjà absorbé et déjà mesuré. La seule chose à en retenir est le **tag d'exclusion**, qui évite de casser les fermes à comptage. |
| **xp-stream** | `ExperienceOrbMixin` : absorption plus rapide des orbes sans les agglomérer. [V] | `Jed-Tech/XP_Stream` | **CC-BY-NC-ND-4.0** [V] (`LICENSE` de 893 octets, pointeur vers le texte CC) | non | **oui** — `core/Clump.java` + `mixin/ExperienceOrbMixin.java`, ×11,76 mesuré | **NON** — non commercial **et** sans dérivée : non absorbable, et Lanterne fait déjà mieux, chiffres à l'appui. |
| **leaky** | **5 mixins** [V] : deux sur `ItemEntity` (dont un à `priority=999`, donc volontairement prioritaire sur les autres mods), un sur `EntitySection`, plus `storage.ServerLevelMixin` et `storage.ClusterItemMixin` — il **stocke** les concentrations dangereuses d'items dans une structure à part au lieu de les laisser en entités. | `someaddons/Leaky`, branche `neo26.2` | **aucun fichier `LICENSE`** ; `mods.toml` : `license="ARR"` [V] | non | `core/Broom.java`, `Sweeper.java`, `Spill.java`, `Rubble.java` | **NON absorbable.** Mais **l'idée du regroupement en « cluster » plutôt que la suppression** est la bonne réponse au clearlag, et c'est déjà l'esprit du Balai. Vivant : NeoForge 26.2 le 08/09/2026. ⚠ `priority=999` sur `ItemEntity` **entrerait en conflit direct avec `mixin/ItemMergeMixin.java` et `LooseItemMixin.java`**. |
| **world-cleanup** | Deux mixins : `ItemEntityMixin`, `EntityDropMixin`. 11 fichiers Java. **Le code est publié mais le `LICENSE` dit : *« World Cleanup Copyright (c) 2026 notverycomfy. All Rights Reserved. This software and its source code are propr[ietary] »*** [V] | `notverycomfy/world-cleanup` | **All Rights Reserved** [V] | non | `core/Broom.java`, `Sweeper.java` | **NON** — 113 téléchargements, ARR, et Lanterne a déjà le Balai. |
| **tct-clearlag** | Vérifié **par lecture du bytecode** [V] : **aucun mixin**, package `net.clearlag.*` avec un dossier `procedures/`, `ClearlagModVariables`, `NetworkMessage` — **signature MCreator** (horodatages du zip figés au 01/01/1980). Dépend d'un « TCTCore ». | **source introuvable** | **All Rights Reserved** [V] | non | `core/Broom.java`, `Sweeper.java` | **NON** — généré par MCreator, ARR, zéro intérêt technique. Et un clearlag qui supprime les items au bout de N minutes est une punition, pas une optimisation. |
| **alternate-current** | Réécriture du moteur de poussière de redstone : au lieu de propager de proche en proche par `neighborChanged` (coût dépendant de l'ordre, donc « locationnel »), on construit le **réseau** de poussière et on l'évalue en une passe. 5 mixins, 21 fichiers Java, **dont `ExperimentalRedstoneUtilsMixin`** — le mod s'accroche donc au dispositif expérimental de vanilla. [V] | `SpaceWalkerRS/alternate-current` | **MIT** [V] | **oui, mais désactivé** : `ExperimentalRedstoneWireEvaluator` existe, derrière `FeatureFlags.REDSTONE_EXPERIMENTS` (§1.5) [V] | `core/Wire.java` + `mixin/RedstoneEvaluatorMixin.java` | **MESURER d'abord.** Avant d'écrire une ligne : mesurer `DefaultRedstoneWireEvaluator` contre `ExperimentalRedstoneWireEvaluator` en activant le feature flag. **Si l'écart est le même, le travail consiste à activer un drapeau, pas à réécrire un moteur.** |
| **structure-essentials** | **18 mixins** [V] : `ChunkGenerator` (accélération de la recherche de structure), `RandomSpreadStructurePlacement`, `LocateCommand`, `JigsawPlacement`, `StructureStart`, `ExplorationMapFunction`, `MappedRegistry`, `LegacyRandomSource`. | `someaddons/structureessentials`, code sur `neo26.1` / `fabric26.2` | **aucun fichier `LICENSE`** ; `mods.toml` : `license="ARR"` [V] | non | `core/Stencil.java`, `mixin/TemplateStencilMixin.java` | **NON absorbable — mais c'est la meilleure idée non-libre de la liste.** Le gel du serveur quand un joueur fait `/locate` ou ouvre une carte au trésor (`ExplorationMapFunction`) est un cas réel et mesurable. **À réécrire depuis l'idée**, en s'appuyant sur `ChunkStatusTasks` passé `public` en 26.2. |
| **structure-layout-optimizer** | 6 mixins sur la génération des structures Jigsaw : `JigsawPlacementMixin`, `JigsawPlacementPlacerMixin` (le placement des pièces est en O(n²) sur la liste des boîtes englobantes → indexation), `SinglePoolElementMixin`, `StructureTemplateMixin` + `StructureTemplatePaletteMixin` (ne pas re-matérialiser la palette de blocs à chaque pièce). [V] ; `JigsawPlacement.java` et `StructureTemplate.java` existent en 26.2 [V] | `TelepathicGrunt/StructureLayoutOptimizer` | **MIT** [V] | non | `core/Stencil.java` + `mixin/TemplateStencilMixin.java` — **recouvrement à vérifier** | **PRENDRE la partie Jigsaw** (`JigsawPlacementPlacerMixin`) si le Pochoir ne la couvre pas. C'est **le** pic de tick des villages et des cités anciennes. Dernier push 30/03/2026. |
| **ksyxis** | Supprime le chargement des chunks du spawn au démarrage. 6 mixins. [V] | `VidTu/Ksyxis` | **MIT** [V] | **OUI — le comportement visé n'existe plus** (§1.3) [V] | non | **NON — périmé par 26.2.** ⚠ **`MINE.md` le classe PRENDRE : entrée à corriger.** |
| **modernfix-mvus** | Fork de ModernFix qui ajoute le support des versions mineures. 229 fichiers Java contre 337 pour l'original. | `coredex-source/ModernFix---mVUS` | **LGPL-3.0** [V] (fichier de 43 482 octets = texte LGPL complet, identique à celui d'`embeddedt/ModernFix`) | non | non | **NON — prendre l'original.** `embeddedt/ModernFix` est en LGPL-3.0, plus complet, déjà **PRENDRE** dans `MINE.md`. Un fork qui retire 108 fichiers n'apporte rien à un projet qui absorbe le code plutôt que le jar. |
| **quick-pack** | Accélère la lecture des zip de packs. 4 mixins serveur (`FilePackResourcesMixin`, `FileResourcesSupplierMixin`, `FilePackResourcesAccessor`, `SharedZipFileAccessAccessor`) + 5 client (`SpriteLoaderMixin`, `FontSetMixin`, `FontManagerMixin`, `LoadingOverlayMixin`, `GuiMixin`). **Chiffre du README : un pack de 44,3 Mio à 60 369 entrées passe de 36 s à 2 s.** [V] ; en 26.2, `FilePackResources#listResources` fait toujours un `zipFile.entries()` linéaire (lignes 70 et 110 de `C:/mc262src/net/minecraft/server/packs/FilePackResources.java`), et `SharedZipFileAccess` existe toujours (classe interne, ligne 168) [V] | `DrexHD/quick-pack` | ⚠ **GPL-3.0** [V] — **Modrinth affiche MIT. C'est faux.** | non | non | **PRENDRE — le meilleur rapport gain/lignes de toute la liste.** Voir §3. |
| **memory-settings** | **Un seul mixin client** [V] : `@Mixin(Minecraft.class)`, `@Inject` cancellable dans `onGameLoadFinished`. C'est littéralement **un écran d'avertissement au démarrage**. | `someaddons/MemorySettings`, branche `neo26.2` | **aucun fichier `LICENSE`** ; `mods.toml` : `license="ARR"` [V] | non | `core/Ballast.java`, `Attic.java`, `MemoryPulseAccess.java` | **NON.** ⚠ **C'est l'exemple à retenir contre les chiffres de téléchargement** : **60,9 M de téléchargements pour ~40 fichiers et un mixin de 40 lignes qui affiche un message.** Ce chiffre mesure l'ancienneté (créé en 2021) et l'inclusion en modpack, rien d'autre. |
| **lomka** | Sac de micro-optimisations, 78 mixins lus dans l'arborescence : `MixinMth`, `MixinBlockPos`, `MixinVec3i`, `MixinSectionPos`, `MixinDirection`, `MixinAABB`, `MixinCursor3D`, `MixinARGB`, `MixinVoxelShape`, `MixinBitSetDiscreteVoxelShape`, `MixinFriendlyByteBuf`, `MixinCompressionEncoder`/`Decoder`, `MixinGlyphSource`, `MixinFontSet`, `MixinDataLayer`, `MixinLeveledPriorityQueue`, `MixinLayerLightSectionStorage`, `MixinDynamicUniformStorage`… [V] | `Starlevka/Lomka` | **LGPL-3.0** [V] | non | recouvrement partiel avec `efficient-hashing` et `faster-random` de `MINE.md` | **MESURER, prudemment.** Le README annonce lui-même *« up to 4.58% increase »*, déclare *« The project code was created with help of Artificial Intelligence »*, et **liste les versions qui marchent** (0.2.0, 0.2.1, 0.4.x, 0.5.x) — ce qui implique que les autres régressent. 27 357 téléchargements. **Bon gisement d'idées, mauvais candidat à l'absorption en bloc.** |
| **antifreeze** | ⚠ **Deux projets homonymes, et j'avais d'abord pris le mauvais.** Le mod CurseForge est un **vrai mod Java** : un unique `@Mixin(Biome.class)` sur `shouldFreeze(LevelReader, BlockPos)`, `@Inject` HEAD cancellable qui renvoie `FALSE`. Package `org.xFeneq.antifreeze.mixin`. Vérifié **par décompilation du jar** [V]. Le projet `antifreeze` de Modrinth est un **resourcepack** sans rapport (`project_type: resourcepack`, « stop falling in powder snow », auteur `aBrokenBed`) — **sa licence MIT ne couvre pas ce mod**. | **source introuvable** — le compte `xFeneq` est actif (3 dépôts), aucun ne correspond [V] | aucune → **ARR** | non | non | **NON** — ARR, et ce n'est pas une optimisation : c'est un changement de règle de jeu, que **trois lignes de datapack** (ou un `@Inject` maison de dix lignes) reproduisent exactement. |
| **chunky-pregenerator** | ⚠ **Homonymie à trois, tranchée.** Le mod CurseForge `chunky-pregenerator-forge` **est bien le Chunky de `pop4959`** : c'est la distribution NeoForge du même monorepo (bukkit/paper/folia/fabric/forge/neoforge/sponge), `pop4959` est membre du projet CurseForge. 4 mixins NeoForge : `ChunkMap`, `MinecraftServer`, `ServerChunkCache`, `MinecraftServerAccess` ; cœur commun `GenerationTask` / `TaskScheduler` / `TaskLoader`. [V] `uhneer/chunkypregen` (MIT, un seul mixin `MixinBossBarHud`) est **autre chose** : un simple pilote Fabric de Chunky. | `pop4959/Chunky` | **GPL-3.0** [V] | non | `lab/Pregen.java` | **à côté — mais absorbable** (GPL-3.0 → GPL-3.0-only) si le banc de prégénération de Lanterne devait devenir un outil de production. 39,3 M de téléchargements, NeoForge 26.2 (`Chunky-NeoForge-1.5.4`, 23/07/2026). |
| **simple-animal-pens** | **Datapack pur** [V] : `data/sap/` (mcfunction, loot_table, recipe, advancement) + un resourcepack, plus des enveloppes multi-loader. **Zéro fichier Java, zéro mixin.** | `dvirov/Simple-Animal-Pens` | ⚠ **aucun fichier `LICENSE` dans le dépôt**, malgré l'étiquette MIT de Modrinth [V] | non | `core/Pasture.java` | **NON en tant que code — il n'y a pas de code.** L'idée (sortir les animaux du monde d'entités) est bonne et **`core/Pasture.java` la couvre déjà**, en Java, donc mieux. |
| **chunk-loaders** | Blocs qui gardent des chunks chargés, par paliers. Pas de mixin (passe par la bibliothèque `SuperMartijn642CoreLib`). 32 fichiers Java. **Aucun fichier de licence dans le dépôt** [V] ; Modrinth déclare ARR. | `SuperMartijn642/ChunkLoaders` | **All Rights Reserved** [V] | non | `content/waypoint/*` (repères), pas de chunk loader | **NON** — c'est du contenu, et surtout **c'est l'exact contraire d'une optimisation sur un VPS mono-cœur** : chaque chunk forcé est un chunk qui ticke pour toujours. |
| **villagerconfig** | Personnalisation complète des échanges de villageois par datapack : `MerchantOfferMixin`, `VillagerMixin`, `NumberProvidersMixin`, `LootItemFunctionsMixin`… 15 mixins, 53 fichiers Java. [V] | `DrexHD/VillagerConfig` | **LGPL-3.0** [V] | non | `content/shop/*` (la boutique) | **à côté** — ce n'est pas une optimisation. Mais **c'est la référence à lire** si la boutique de Lanterne doit un jour exposer ses prix en datapack. |

### 2.3 Contenu, confort, outils

| slug | idée technique réelle | dépôt source | licence lue dans `LICENSE` | déjà dans vanilla 26.2 ? | déjà dans Lanterne ? | verdict |
|---|---|---|---|---|---|---|
| **lootr** | Coffres de butin instanciés par joueur : chaque joueur voit son propre contenu, le coffre est visuellement distinct. 316 fichiers Java. | `noobanidus/lootr` | **MIT** [V] | non | non | **à côté** — contenu, pas optimisation. Effet secondaire réel : **supprime la course au butin** sur un serveur, donc supprime des connexions simultanées sur une structure. À garder comme mod tiers. |
| **simple-voice-chat** | Serveur/client de voix : Opus via JNI, UDP séparé, groupes, distances. 659 fichiers Java, présent sur **12 plateformes** (Bukkit → Velocity). | `henkelmax/simple-voice-chat` — code entièrement public | **`license` contient : « Copyright (c) Max Henkel - All Rights Reserved »** [V] | non | non | **NON absorbable — et il ne faut surtout pas essayer.** La bonne décision est celle que tu as écrite : **le supporter**. Concrètement : ne pas étrangler ses paquets dans `core/NetworkThrottle.java` (il utilise son propre canal UDP, mais son canal de contrôle passe par les payloads custom), et ne pas culler côté serveur les joueurs qui parlent (§3, entrée Krypton Reno). |
| **bulk-merchant** | **Un seul mixin** : `@Mixin(MerchantMenu.class)`, package `net.emeraude.bulktrade.mixin`, structure multi-loader propre (common / fabric / neoforge). [V] | `BackupBackupFede/bulk-trade` | **MIT** [V] | non | **non** — `core/Bulk.java` porte le transfert par lots des **entonnoirs**, rien à voir avec les marchands (vérifié en lisant le fichier) | **PRENDRE — confort pur, quelques dizaines de lignes.** 150 téléchargements seulement, mais c'est l'exact inverse des `someaddons` : minuscule, propre, et réellement libre. Pertinent pour la boutique (`content/shop/*`), qui est un marchand. |
| **simple-server-utilities** | ~15 mixins réels, `compatibilityLevel: JAVA_25` [V] : côté serveur `FlowingFluid`, `BucketItem`, `LiquidBlock`, `HopperBlockEntity`, `FireBlock`, `BlockEntity` ; côté client `LevelRenderer`, `Screen`, `LivingEntityRenderer`, `PlayerSkinWidget`, `MultiLineEditBox`. | `winnetrie83/Simple-Server-Utilities` | **aucun fichier `LICENSE`** (404 sur les cinq variantes, `license: null`) → **ARR** [V] | non | `report/LanterneCommand.java`, `Guide.java`, `Ledger.java` | **NON absorbable.** Vrai code (11,5 Mo), vrai travail, **60 téléchargements**, et pas de licence. Rien à en tirer légalement. |
| **reap-mod** | Récolte au clic droit. 4 fichiers Java, aucun mixin. **Aucun fichier de licence dans le dépôt** [V] ; Modrinth déclare ARR. | `henkelmax/reap` | **All Rights Reserved** [V] | non | non | **à côté** — confort pur, ~200 lignes, trivial à refaire si on le veut. |
| **loot-integrations** | **3 mixins** [V] : `@Mixin(LootTable.class, priority=200)`, `@Mixin(LootContext.class)`, `@Mixin(ExplorationMapFunction.class)`. | `someaddons/LootIntegrations` (**oui, encore le même auteur**), branches `neo26.1` / `fabric26.1` | **aucun fichier `LICENSE`** ; `mods.toml` : `license="ARR"` [V] | non | non | **NON** — contenu, pas optimisation, et non absorbable. ⚠ Dernière mise à jour **11/04/2026**, pas de branche 26.2. |
| **jupiter** | Bibliothèque de configuration auto-synchronisée. 116 fichiers Java, **2 mixins seulement** (`AutoConfigClientMixin`, `AutoConfigAccessor`). Modrinth : **pas de 26.2** (dernière MAJ 05/05/2026). [V] | `IAFEnvoy/Jupiter` | **LGPL-3.0** [V] (43 465 octets = texte LGPL complet) | non | `core/Config.java`, `ClientConfig.java`, `Settings.java` | **NON** — Lanterne a sa configuration, et ce mod n'est pas en 26.2. `MINE.md` disait déjà « Lanterne a la sienne » pour `jauml`. |
| **spark** | Profileur : échantillonnage de la pile par `ThreadMXBean`, agrégation en arbre d'appels, sondes mémoire (`MemoryPool`, GC), `/spark profiler`, export web. 297 fichiers Java. | `lucko/spark` | **GPL-3.0** [V] | non | `report/Bench.java`, `Ledger.java`, `lab/Sampler.java`, `mixin/ProfilerMixin.java` | **à côté — et c'est l'outil à installer, pas à absorber.** Deux raisons concrètes : (1) **`servercore` sait déjà dialoguer avec lui** (`SparkDynamicManager`) ; (2) sur un VPS mono-cœur, `spark` est le seul moyen honnête de savoir si le GC vole le tick — ce que `notes/serveur-un-coeur.md` identifie comme le risque principal. GPL-3.0 : absorbable si un jour on en veut un morceau. |
| **worldedit** | Éditeur de carte : histoire d'annulation, masques, motifs, `//brush`, scripts. 1 070 fichiers Java. | `EngineHub/WorldEdit` | **GPL-3.0** pour l'ensemble, avec des parties MIT et BSD répertoriées dans le même fichier [V] | non | non | **à côté** — outil, énorme, très bien comme il est. Rien à absorber. |
| **expanded-weapon-enchantings** | Autorise Density/Breach/Impaling/Wind Burst sur d'autres armes. Modrinth : **ARR, aucune source**, datapack + mod. | *voir §7* | **ARR** | non | non | **NON** — contenu, et l'effet est reproductible **en datapack pur** (tags `minecraft:enchantable/*`), donc zéro ligne de Java. |
| **exploitation-timer** | Affiche l'heure d'explosion d'une TNT. **2 mixins seulement** : `TntEntityRenderMixin`, `MixinTitleScreen`. 6 fichiers Java. [V] | `anviaan/Exploitation-Timer` | **GPL-3.0** [V] | non | non | **à côté** — confort de joueur technique, absorbable (GPL-3.0), ~100 lignes. Aucun rapport avec les performances. |
| **ai-improvements** | ⚠ Voir §6 : **la licence lue contredit celle affichée.** 19 fichiers Java, **aucun mixin** (branchement par événements Forge). Dernier commit GitHub : **05/10/2022**, alors que la page Modrinth annonce 26.2 et une MAJ du 05/04/2026. | `BuiltBrokenModding/AI-Improvements` | ⚠ **MIT** [V] — le fichier dit *« MIT License, Applies to Code only »*. **`NOTICE.md` et `MINE.md` le classent All Rights Reserved / REFAIRE. C'est faux.** | non | `core/Mind.java`, `mixin/BrainMixin.java`, `GateBehaviorMixin.java`, `BehaviorMemoryMixin.java`, `MobMixin.java`, `PlayerSensorMixin.java` | **PRENDRE l'idée est désormais PRENDRE le code.** Voir §6. Mais attention : **le dépôt public est figé en 2022**, donc le code 26.2 distribué n'y est pas. Ce qu'on peut lire est l'implémentation 1.16/1.18. |

---

## 3. Ce qu'il faut prendre, dans l'ordre du gain attendu

Chaque entrée : la méthode de Minecraft touchée (**vérifiée présente en 26.2**), le mécanisme du
gain, l'ampleur du travail, le risque.

| # | Mod | Licence | Ampleur | Risque | Gain attendu |
|---|---|---|---|---|---|
| 3.1 | `servercore` — les 7 mixins « chargement synchrone » | MIT | dizaines | faible | **serveur : supprime des gels de 200 ms** |
| 3.2 | `quick-pack` — indexation des zip | **GPL-3.0** | centaines | faible | **démarrage : 36 s → 2 s, mesuré par l'auteur** |
| 3.3 | `krypton-fnp` — culling d'entités **côté serveur** | LGPL-3.0 | centaines | **élevé** | **le meilleur gain possible sur un cœur unique** |
| 3.4 | `structure-layout-optimizer` — placement Jigsaw | MIT | centaines | faible | supprime le pic de tick des villages |
| 3.5 | `framepace` — buffer storage mutable | MIT | **dizaines** | **quasi nul** | frame pacing GPU, NVIDIA + Intel Gen7 |
| 3.6 | `badoptimizations` — cache de lightmap | MIT | dizaines | modéré | ~4 % client, tous les ticks |
| 3.7 | `let-me-despawn` — équipement ramassé | LGPL-3.0 | dizaines | faible | **stoppe la fuite lente du mobcap** |
| 3.8 | `immediatelyfast` — atlas de cartes **seul** | LGPL-3.0 | centaines | faible | murs de cartes : 100 textures → 1 |
| 3.9 | `gnetum` **ou** `exordium` — HUD hors-cadence | LGPL-3.0 / GPL-3.0 | centaines | modéré | le HUD est le 1ᵉʳ poste d'interface |
| 3.10 | `interframe` — reprojection temporelle | **MIT** | milliers | élevé | **le chaînon manquant pour DLSS (§8)** |
| 3.11 | `bulk-merchant` — échange en lot | MIT | dizaines | nul | confort, utile à la boutique |

### 3.1 `servercore` — les sept mixins « chargement synchrone » — MIT

- **Méthodes touchées** : les points d'appel qui déclenchent un chargement de chunk *synchrone*
  depuis le fil serveur. Classes vérifiées présentes en 26.2 : `net.minecraft.world.entity.animal.bee.Bee`,
  `net.minecraft.world.item.MapItem`, `net.minecraft.world.entity.variant.StructureCheck`
  (`C:/mc262src/net/minecraft/world/entity/variant/StructureCheck.java`),
  `net.minecraft.world.level.entity.DynamicGameEventListener`,
  `net.minecraft.world.entity.ai.goal.RemoveBlockGoal`, `net.minecraft.world.level.Level`.
- **Pourquoi c'est un gain** : un chargement synchrone fait attendre **tout le tick** que le chunk
  soit généré ou lu depuis le disque. Sur un VPS mono-cœur, où le fil serveur, le fil de génération
  et le GC se partagent le *même* cœur (cf. `notes/serveur-un-coeur.md`), c'est exactement le
  mécanisme qui produit un gel de 200 ms. Le mod ne fait pas « plus vite » : il **refuse** le
  chargement et rend une valeur par défaut.
- **Ampleur** : sept mixins de 10 à 30 lignes. **Des dizaines de lignes.**
- **Risque** : **faible mais non nul et visible** — une abeille qui ne trouve pas sa ruche parce que
  le chunk n'est pas chargé ne rentre pas ; une carte ne se dessine pas au-delà du chargé. Chaque
  refus doit être configurable individuellement.

### 3.2 `quick-pack` — la lecture des zip — **GPL-3.0** (et non MIT)

- **Méthodes touchées, vérifiées en 26.2** :
  `net.minecraft.server.packs.FilePackResources#listResources` et `#getResource`
  (`C:/mc262src/net/minecraft/server/packs/FilePackResources.java`, lignes 54, 59, 65, 70, 108, 110)
  — ils appellent `ZipFile#entries()` et **balayent toutes les entrées du zip linéairement** ;
  `FilePackResources$SharedZipFileAccess` (ligne 168) ; `FilePackResources$FileResourcesSupplier`
  (ligne 132). Côté client : `SpriteLoader`, `FontSet`, `FontManager`.
- **Pourquoi c'est un gain** : le coût vanilla est en O(entrées × namespaces × appels). Sur un pack
  à 60 000 entrées, le mod mesure **36 s → 2 s**. Le correctif consiste à indexer le zip une fois
  (une `Map` préfixe → entrées) au lieu de rebalayer.
- **Ampleur** : 9 mixins, 16 fichiers Java. **Des centaines de lignes.**
- **Risque** : **faible.** L'index est un cache pur ; si le zip change pendant la session, il faut
  l'invalider — ce que le mod gère déjà via `SharedZipFileAccess`.
- **Ce que ça change pour Lanterne** : le pack de ressources du Club (mosaïques de tableaux,
  disques) est exactement un gros zip. Le gain est au démarrage **et à chaque reconnexion**.

### 3.3 `krypton-fnp` — le culling d'entités **côté serveur** — LGPL-3.0

- **Méthodes touchées, vérifiées en 26.2** : `net.minecraft.server.level.ChunkMap$TrackedEntity`
  (`ChunkMap.java` existe, et Lanterne y a déjà `ChunkMapMixin`),
  `net.minecraft.server.level.ServerEntity#sendChanges`
  (`C:/mc262src/net/minecraft/server/level/ServerEntity.java`, Lanterne a déjà `ServerEntityMixin`),
  `net.minecraft.world.entity.Entity#remove`.
- **Pourquoi c'est un gain, et pourquoi c'est *le* bon gain sur un cœur unique** : le Voile
  (`core/Shroud.java`) empêche le **client** de dessiner une entité cachée. Le culling serveur
  empêche le **serveur** de la sérialiser et de l'envoyer. Sur un tick à 50 ms partagé avec le GC,
  ne pas construire vingt paquets `ClientboundMoveEntityPacket` par tick est du temps rendu au tick,
  pas seulement de la bande passante. Et ça se combine avec `core/NetworkThrottle.java` qui existe.
- **Ampleur** : `ServerCullingManager` + 3 mixins. **Des centaines de lignes** si on veut une
  structure d'occlusion correcte côté serveur (le serveur n'a pas de frustum : il faut raisonner en
  ligne de vue par voxels, pas en caméra).
- **Risque** : **élevé si mal fait.** Une entité cullée à tort disparaît de l'écran du joueur — c'est
  le bug le plus visible qui existe. **Garde-fous indispensables** : ne jamais culler un joueur, un
  boss, une entité qui émet un son, ni un joueur qui parle dans `simple-voice-chat`. Prévoir un
  délai d'hystérésis avant de recommencer à envoyer.

### 3.4 `structure-layout-optimizer` — le placement Jigsaw — MIT

- **Méthodes touchées, vérifiées en 26.2** :
  `net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement`
  (`C:/mc262src/net/minecraft/world/level/levelgen/structure/pools/JigsawPlacement.java`),
  `net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate` et sa palette.
- **Pourquoi c'est un gain** : le placeur Jigsaw teste chaque nouvelle pièce contre **toutes** les
  boîtes englobantes déjà placées — quadratique. Un village ou une cité ancienne, c'est des
  centaines de pièces. L'optimisation indexe les boîtes. La palette de `StructureTemplate` est
  re-matérialisée à chaque pièce : on la met en cache.
- **Ampleur** : 6 mixins, 20 fichiers Java. **Des centaines de lignes.**
- **Risque** : **faible** si l'indexation est exacte (même résultat, moins de tests). **À vérifier
  contre `core/Stencil.java`** qui touche déjà `StructureTemplate` — il y a peut-être recouvrement
  total sur la partie palette.

### 3.5 `framepace` — trente lignes contre le bégaiement GPU — MIT

- **Méthode touchée, vérifiée en 26.2** : `com.mojang.blaze3d.opengl.BufferStorage#create`
  (`C:/mc262src/com/mojang/blaze3d/opengl/BufferStorage.java`, ligne 13 :
  `if (capabilities.GL_ARB_buffer_storage && GlDevice.USE_GL_ARB_buffer_storage)`).
- **Pourquoi c'est un gain** : sur pilote NVIDIA et sur Intel Gen7, le *buffer storage* immuable
  provoque des blocages de synchronisation quand on écrit dans un tampon encore utilisé par le GPU.
  Le forcer en mutable rend la main au pilote, qui fait de l'orphaning. Mojang a fait la même
  correction en 26.3 (`GlHeuristics` gagne `isNvidia` / `couldBeIntelGen7`) — **26.2 ne l'a pas**
  (vérifié : `GlHeuristics.java` de 26.2 n'a que `isGlOnDx12()` et `isAmd()`).
- **Ampleur** : **des dizaines de lignes** (2 fichiers Java, 1 `@Redirect`).
- **Risque** : **quasi nul**, et il existe même un interrupteur vanilla —
  `GlDevice.USE_GL_ARB_buffer_storage` est un `protected static boolean` qu'on peut simplement
  mettre à `false` par accesseur, sans `@Redirect`. **N'agit pas sous Vulkan.**

### 3.6 `badoptimizations` — le cache de lightmap — MIT

- **Méthode touchée, vérifiée en 26.2** :
  `net.minecraft.client.renderer.LightmapRenderStateExtractor`
  (`C:/mc262src/net/minecraft/client/renderer/LightmapRenderStateExtractor.java` — classe qui
  **existe bel et bien en 26.2**, et qui est l'équivalent 26.2 de l'ancien `LightTexture`).
- **Pourquoi c'est un gain** : à chaque tick client, le jeu recalcule 256 valeurs de lightmap
  (16×16) par interpolation vectorielle puis **téléverse la texture au GPU**. Ce calcul ne change
  que si le gamma, la dimension, un effet de potion, l'éclair ou le temps changent. Le reste du
  temps c'est du travail jeté.
- **Ampleur** : **des dizaines de lignes** — un `@Inject(HEAD, cancellable)` plus un état de
  validité à invalider sur cinq événements.
- **Risque** : **modéré** — si on rate un cas d'invalidation, le monde reste éclairé « comme avant »
  (éclair qui ne flashe pas, transition jour/nuit figée). L'auteur a d'ailleurs prévu une API
  d'invalidation pour les autres mods, preuve que le problème est réel.

### 3.7 `let-me-despawn` — l'équipement ramassé ne rend plus un mob immortel — LGPL-3.0

- **Méthodes touchées, vérifiées en 26.2** : `net.minecraft.world.entity.Mob#checkDespawn()`
  (`C:/mc262src/net/minecraft/world/entity/Mob.java`, ligne 715),
  `Mob#removeWhenFarAway(double)` (ligne 706),
  `Mob#setItemSlotAndDropWhenKilled(EquipmentSlot, ItemStack)` (2 occurrences).
- **Pourquoi c'est un gain** : vanilla marque un mob `persistenceRequired` dès qu'il ramasse un
  objet. Sur un monde qui tourne depuis un an, les mobs porteurs s'accumulent et **ne partent
  jamais** — ils occupent le mobcap, donc empêchent les spawns voulus tout en coûtant un tick
  chacun. C'est une fuite lente, invisible au banc de test, mortelle en production.
- **Ampleur** : **des dizaines de lignes.**
- **Risque** : **faible techniquement, visible en jeu** — les joueurs qui gardent des mobs nommés
  ou équipés exprès doivent être protégés (le nom suffit déjà en vanilla : `hasCustomName()`).

### 3.8 `immediatelyfast` — l'atlas de cartes, et lui seul — LGPL-3.0

- **Méthodes touchées, vérifiées en 26.2** : `net.minecraft.client.renderer.MapRenderer`
  (`C:/mc262src/net/minecraft/client/renderer/MapRenderer.java`), `MapRenderState`,
  `GuiGraphicsExtractor`.
- **Pourquoi c'est un gain** : vanilla alloue **une texture GPU par carte**. Un mur de cartes
  (l'usage réel : les *map arts*, très fréquents sur un serveur de construction) c'est 100 textures
  et 100 changements d'état. Le mod les met dans un atlas unique.
- **Ampleur** : 5 mixins du groupe `map_atlas_generation`. **Des centaines de lignes.**
- **Risque** : **faible** — c'est du pur rendu, le pire cas est une carte mal affichée.
- **Ce qu'il ne faut PAS prendre du même mod** : `avoid_redundant_framebuffer_switching.*` (cible
  `com.mojang.blaze3d.opengl.*`, donc **mort sous Vulkan** — or tu veux Vulkan pour DLSS) et
  `enhanced_batching.*` (le système `FeatureRenderer` de 26.2 fait déjà du regroupement ; à mesurer
  avant d'y toucher).

### 3.9 `gnetum` **ou** `exordium`, pas les deux — LGPL-3.0 / GPL-3.0

- **Méthodes touchées, vérifiées en 26.2** : `net.minecraft.client.gui.Hud`
  (`C:/mc262src/net/minecraft/client/gui/Hud.java` — **classe nouvelle en 26.2**, séparée de `Gui`),
  `net.minecraft.client.gui.Gui`, `net.minecraft.client.gui.render.GuiRenderer`
  (qui a déjà un `cachedGuiScale`, mais pas de cache de contenu).
- **Pourquoi c'est un gain** : le HUD est redessiné intégralement à chaque image, alors qu'il change
  au mieux 20 fois par seconde. Avec JEI/EMI ouvert, ou un minimap, c'est le premier poste de coût
  en interface. Gnetum le découpe en N passes réparties sur N images, avec double framebuffer.
- **Ampleur** : **des centaines de lignes**, plus la compat par mod tiers.
- **Risque** : **modéré** — latence visible sur les éléments qui doivent réagir vite (barre de vie
  en combat). Gnetum expose un réglage du nombre de passes, à laisser bas par défaut.
- ⚠ **Exclusivité stricte.** `MINE.md` classe `exordium` en PRENDRE. **Deux mods qui rendent le HUD
  hors-cadence en même temps produisent un HUD qui clignote.** Choisir, mesurer, et écrire le choix.

### 3.10 `interframe` — la seule génération d'images libre et réelle de la liste — MIT

Celui-ci n'était pas dans mes attentes et c'est la trouvaille de la reconnaissance.

- **Méthodes touchées, vérifiées en 26.2** : `com.mojang.blaze3d.platform.Window#updateDisplay`
  (mixin en HEAD) et `net.minecraft.client.renderer.LevelRenderer`.
- **Ce que le mod fait réellement** [V] — ce n'est pas du marketing : il contient un
  `FrameGenerator`, un `BlendReprojectSynthesizer` (reprojection + mélange, la méthode classique) et
  un **`OnnxSynthesizer`, c'est-à-dire un réseau de neurones ONNX** qui synthétise l'image
  intermédiaire, plus un `CameraSnapshot` et des shaders GL.
- **Pourquoi c'est important pour toi** : la reconnaissance précédente concluait qu'il n'existait
  aucun point d'extension officiel pour l'upscaling, et l'annexe §8 montre que `superresolution`
  **ne produit ni jitter ni vecteurs de mouvement** en mode vanilla. `interframe` est, à ma
  connaissance, **le seul dépôt public, libre (MIT) et ciblant NeoForge 26.2 qui implémente
  réellement une reprojection temporelle sans shader pack.** C'est le chaînon manquant : la
  reprojection depuis la caméra et la profondeur est exactement le mécanisme dont DLSS a besoin
  pour ses vecteurs de mouvement.
- **Ampleur** : non chiffrée — je n'ai pas lu son code ligne à ligne, seulement son inventaire de
  classes et son `mixins.json`. **[S]** de l'ordre du millier de lignes, plus un modèle ONNX.
- **Risque** : **élevé sur la qualité d'image** (le fantôme est inhérent à la reprojection), **nul
  sur la licence** (MIT), **inconnu sur la dépendance** (un runtime ONNX en Java, c'est une
  bibliothèque native de plusieurs dizaines de mégaoctets — à vérifier avant toute décision).
- **Ce qu'il faut faire avant d'y toucher** : lire `BlendReprojectSynthesizer` et `CameraSnapshot`,
  et **seulement ceux-là**. Si sa reprojection est correcte, elle se branche sur le point
  d'injection de jitter identifié en §8.3.

### 3.11 `bulk-merchant` — MIT

- **Méthode touchée, vérifiée en 26.2** : `net.minecraft.world.inventory.MerchantMenu`.
- **Ce que c'est** : un seul mixin, qui échange tout le stock en un shift-clic et recharge les slots
  de paiement depuis l'inventaire.
- **Pourquoi c'est ici** : ce n'est pas une optimisation, mais **la boutique de Lanterne
  (`content/shop/*`) est un marchand**, et vingt allers-retours de clic sont vingt paquets
  `ServerboundContainerClickPacket`. Le gain est marginal mais réel, et le confort est net.
- **Ampleur** : **des dizaines de lignes.** **Risque : nul.**
- 150 téléchargements — et c'est l'un des deux dépôts les plus propres de toute cette
  reconnaissance (§7.3).

---

## 4. Ce qu'il ne faut PAS prendre — et pourquoi

Cette liste vaut la précédente : elle économise des jours.

| Mod | Raison de ne pas le prendre |
|---|---|
| **ksyxis** | **Périmé par 26.2.** `prepareLevels()` ne charge plus les chunks du spawn, il n'y a plus de `TicketType.START`, et la gamerule `spawnChunkRadius` est supprimée par datafixer. [V] ⚠ corriger `MINE.md`. |
| **stable-fps**, **lite-fps**, **framepacer** | **Périmés par 26.2.** `net.minecraft.client.FramerateLimiter#limitDisplayFPS(int)` fait déjà `System.nanoTime()` + `LockSupport.parkNanos` + estimateur de dépassement en moyenne glissante + `Thread.onSpinWait()`. [V] `lite-fps` `@Overwrite` littéralement cette classe pour y remettre `System.nanoTime()` — un rétroportage 26.1 devenu inutile. `framepacer` n'a **aucun dépôt** : son étiquette MIT ne recouvre aucun texte. |
| **fps-tune** | MIT, dépôt propre, mais ses deux mixins (`ParticleEngine#tick`, `WeatherEffectRenderer#render`) tapent sur ce que vanilla plafonne déjà (§1.2) et sur ce que `moreculling` fait mieux. L'idée « couper la pluie sous pression » vaut trois lignes dans `core/TickBudget.java`. |
| **connectivity**, **structure-essentials**, **chunk-sending**, **leaky**, **memory-settings**, **loot-integrations** (*someaddons*) | **Non absorbables.** Le code est public et abondant (26, 18, 7, 5, 1, 3 mixins), mais **aucun des six dépôts n'a de fichier `LICENSE`** et chaque `neoforge.mods.toml` déclare `license="ARR"` de la main de l'auteur (§7.1). Deux d'entre eux — dont `connectivity`, 161 M de téléchargements — **n'ont pas de branche 26.2** et datent d'avril 2026. ⚠ `leaky` place son mixin `ItemEntity` à `priority=999` : conflit direct avec `ItemMergeMixin` et `LooseItemMixin`. |
| **fps-boost**, **fps-booster-triio**, **smooth-join**, **multicore-helper**, **easy-mob-spawn-control**, **tct-clearlag**, **antifreeze**, **simple-server-utilities**, **expanded-weapon-enchantings** | **Source introuvable, ou source publique sans licence.** Rien à lire, donc rien à affirmer techniquement, et rien à reprendre. `tct-clearlag` est généré par MCreator ; `expanded-weapon-enchantings` est un **datapack déguisé** dont le jar ne contient aucun `.class` (§7.4). |
| **smart-particles** | Vanilla plafonne déjà à 16 384 par groupe, rejette de façon probabiliste dès 12 288, applique une limite par type, et cull par frustum pendant l'extraction. [V] Le mod 26.2 ne contient plus qu'un mixin. Son README admet que le test en jeu NeoForge n'a pas été fait. |
| **particle-core** (en bloc) | Même raison. **Seul** `ParticleBrightnessCacheMixin` reste hors vanilla. Prendre 21 mixins pour en garder 1 est une dette de maintenance pure. |
| **scalablelux** | Vanilla a absorbé Starlight depuis 1.20, Starlight est archivé, et `notes/recherche-client.md` l'avait déjà établi. Le mod porte encore le package `ca.spottedleaf.starlight.mixin`. ⚠ déclasser l'entrée « le candidat sérieux » de `MINE.md`. |
| **adaptive-performance-tweaks** | **Doublon de régulateur.** Il asservit `simulationDistance` / `viewDistance` / mobcaps au temps de tick, exactement comme `servercore` **et** comme `core/Cadence.java`. Deux boucles d'asservissement sur la même grandeur oscillent. Son README dit lui-même « early-release ». |
| **enhanced-block-entities-reloaded** | Troisième mod sur la même cible que Faster Block Entities (déjà absorbé) et `better-block-entities`. Port non officiel, 2 524 téléchargements. |
| **asyncparticles** | Il réécrit `LevelChunk`, `LevelChunkSection` et `ClassInstanceMultiMap` pour les rendre thread-safe, via un transformateur de classes maison (`ClassAdjusterMixinPlugin`, `ClassRenamer`). C'est une machinerie que Lanterne devrait maintenir pour toujours, pour des particules que vanilla plafonne déjà. |
| **chloride**, **reflex-antilag**, **world-cleanup**, **easy-mob-spawn-control**, **tct-clearlag**, **chunk-loaders**, **reap-mod**, **expanded-weapon-enchantings**, **simple-voice-chat** | **Non absorbables** : All Rights Reserved (lu dans le fichier, ou absence totale de fichier de licence, ce qui revient au même). Pour `simple-voice-chat`, la bonne stratégie est le **support**, pas l'absorption. |
| **xp-stream** | CC-BY-NC-ND : non commercial **et** sans dérivée. Et `core/Clump.java` fait déjà ×11,76, mesuré. |
| **sodium** | PolyForm Shield : interdit d'en faire un produit concurrent. Et 50 000 lignes de moteur qu'il faudrait re-maintenir contre l'amont à chaque version. |
| **modernfix-mvus** | Fork appauvri (229 fichiers contre 337). Prendre `embeddedt/ModernFix` (LGPL-3.0). |
| **falling-blocks-fps-fix** | Supprime une mécanique de jeu, ne l'optimise pas. |
| **multicore-helper**, **memory-settings**, **antifreeze** | Hors sujet, hors version, ou non-optimisation. |
| **get-it-together-drops**, **fast-items**, **ferritecore** (partie cache d'états), **simple-animal-pens** | Déjà dans Lanterne, et pour trois d'entre eux **déjà mesurés**. |
| **moreculling** | **À prendre, mais pas sans demander.** La GPL-3.0 t'y autorise ; le README de l'auteur dit explicitement le contraire. Un mod qui met sa provenance dans `NOTICE.md` n'a pas intérêt à commencer par ignorer la volonté d'un auteur. Écrire à `fxmorin`. |

### 4.1 Les doublons qui s'annulent — à arbitrer avant d'écrire une ligne

Un serveur qui charge deux mods annulant le même tick devient imprévisible. Quatre couples ici :

1. **`servercore` ⟷ `adaptive-performance-tweaks` ⟷ `core/Cadence.java`** — trois régulateurs sur
   `simulationDistance` / `viewDistance` / mobcap. **N'en garder qu'un, et que ce soit celui de
   Lanterne.**
2. **`gnetum` ⟷ `exordium`** — deux rendus de HUD hors-cadence.
3. **`particle-core` ⟷ `smart-particles` ⟷ vanilla 26.2** — trois plafonds de particules.
4. **`better-block-entities` ⟷ `enhanced-block-entities-reloaded` ⟷ Faster Block Entities (déjà
   absorbé)** — trois renderers hybrides de block entities.

Et un rappel de la reconnaissance précédente, toujours valable :
**`notick` ⟷ `boosters` ⟷ `immersive-optimization` ⟷ `servercore` (activation range) ⟷
`core/EntityThrottle.java`** — cinq façons d'annuler le même `tickNonPassenger`.

---

## 5. Correctifs à apporter aux notes existantes

| Fichier | Entrée | Correction |
|---|---|---|
| `notes/MINE.md` | `ksyxis` — **PRENDRE** | → **non**. Le comportement visé n'existe plus en 26.2 (§1.3). |
| `notes/MINE.md` | `scalablelux` — « le candidat sérieux » | → **non**. Starlight est dans vanilla depuis 1.20 ; `recherche-client.md` le disait déjà. |
| `notes/MINE.md` | `lmd` (`let-me-despawn`) — **non** | → **PRENDRE**. `despawn-tweaks` s'arrête en 1.21.1 ; celui-ci est en 26.2, LGPL-3.0, et vivant. |
| `notes/MINE.md` | `notick` — LGPL-3.0-or-later | La reconnaissance précédente a établi que le **dépôt est MIT** et que Modrinth affiche LGPL. |
| `notes/MINE.md` + `NOTICE.md` | `ai-improvements` — All Rights Reserved / **REFAIRE** | → **MIT** (§6). Le code est absorbable. `NOTICE.md` le liste à tort dans « ce qui n'est pas absorbable ». |
| `notes/MINE.md` | `quick-pack` — MIT | → **GPL-3.0** (§6). Reste absorbable, mais le crédit dans `NOTICE.md` doit dire GPL-3.0. |
| `NOTICE.md` | table « ce qui n'est pas absorbable » | Retirer `ai-improvements`. Ajouter la famille `someaddons` (6 dépôts, aucun `LICENSE`, `license="ARR"` dans le `mods.toml`), `chloride`, `world-cleanup`, `simple-server-utilities`, `easy-mob-spawn-control`. |
| `notes/MINE.md` | `deltachunk` — MIT / MESURER | La reconnaissance précédente a montré une divergence GitHub « NOASSERTION » vs MIT, et que **le mod lui-même déconseille le multijoueur**. |
| — (nouvelles entrées) | `interframe`, `bulk-merchant`, `fps-tune`, `framepace`, `lomka`, `gnetum`, `stable-fps`, `lite-fps` | Absents de `MINE.md` (202 mods issus de l'API Modrinth). Les quatre premiers sont **MIT et absorbables** ; `interframe` est le plus intéressant. |
| — (homonymies à noter) | `chunky-pregenerator-forge`, `antifreeze`, `chunkypregen` | Trois pièges d'homonymie documentés en §2.2 : le premier **est** le Chunky de `pop4959` (GPL-3.0) ; le second est un mod Java ARR et non le resourcepack MIT de Modrinth ; le troisième est un simple pilote Fabric. |

---

## 6. Les divergences de licence trouvées — et ce qu'elles changent

Trois cas où l'étiquette publique et le fichier `LICENSE` ne disent pas la même chose. C'est autant
que dans la reconnaissance précédente : **le taux d'erreur des étiquettes est de l'ordre de 5 %.**

### 6.1 `quick-pack` : Modrinth dit MIT, le fichier dit GPL-3.0 [V]

```
$ git show HEAD:LICENSE   (DrexHD/quick-pack)
### GNU GENERAL PUBLIC LICENSE
Version 3, 29 June 2007
```

**Conséquence** : absorbable (GPL-3.0 → GPL-3.0-only), mais **le crédit dans `NOTICE.md` doit dire
GPL-3.0**, pas MIT. Se tromper ici, c'est sous-déclarer une obligation de copyleft.

### 6.2 `ai-improvements` : Modrinth dit All Rights Reserved, le fichier dit MIT [V]

```
$ git show HEAD:LICENSE   (BuiltBrokenModding/AI-Improvements)
MIT License, Applies to Code only
Copyright (c) 2022 Built Broken Modding
```

**Conséquence, et c'est la plus importante de cette note** : `NOTICE.md` range `ai-improvements`
dans « ce qui n'est pas absorbable », et `MINE.md` le classe **REFAIRE** — c'est-à-dire « lire
l'idée, réécrire le code ». **C'est faux : le code est sous MIT et peut être repris tel quel**, avec
le crédit d'usage. Cela transforme une réécriture en une reprise.

**Mais** — et c'est la partie honnête — **le dépôt GitHub est figé au 05/10/2022**, alors que le mod
distribué annonce 26.2. Ce qu'on peut lire est l'implémentation 1.16/1.18 : les idées (ne pas
exécuter les tâches d'IA hors de portée, sauter le recalcul de chemin quand la cible n'a pas bougé)
sont lisibles, le code 26.2 ne l'est pas. La reprise porte donc sur du code à re-porter.

### 6.3 `moreculling` : GPL-3.0 dans le fichier, interdiction dans le README [V]

Le `LICENSE` est le texte GPL-3.0 complet. Le README dit :
*« I don't allow clients or other mods to merge this mod without permission, if you would like to
use this mod in your client or another mod please contact me. »*

Juridiquement la GPL-3.0 prime et un README ne révoque pas une licence déjà accordée. **Humainement,
c'est un refus explicite.** Je le signale plutôt que de le taire : le crédit dans `NOTICE.md` ne
répare pas un désaccord avec un auteur vivant et actif. **Demander.**

### 6.4 `simple-animal-pens` : Modrinth dit MIT, le dépôt n'a aucun fichier de licence [V]

Le dépôt `dvirov/Simple-Animal-Pens` existe, contient bien le datapack, et **ne contient aucun
`LICENSE`**. Même cas que `framepacer`, qui affiche MIT sans avoir de dépôt du tout. **Une étiquette
de plateforme n'est pas une licence** : sans texte, il n'y a pas de concession de droits.

### 6.5 Les dépôts sans aucun fichier de licence — la liste complète

Absence de licence = **tous droits réservés**, quelle que soit la visibilité du code. **Neuf cas**
ici, tous avec du code public et lisible :

- les six dépôts `someaddons` : `connectivity`, `structureessentials`, `chunksending`, `Leaky`,
  `MemorySettings`, `LootIntegrations` — et chacun **écrit `license="ARR"` dans son propre
  `neoforge.mods.toml`**, ce qui lève toute ambiguïté
- `SrRapero720/chloride` (17,5 M de téléchargements)
- `SuperMartijn642/ChunkLoaders` (1,57 M)
- `henkelmax/reap`, `winnetrie83/Simple-Server-Utilities`, `dvirov/Simple-Animal-Pens`

Et trois cas où le fichier existe et dit explicitement l'interdiction :
`henkelmax/simple-voice-chat` (*« Copyright (c) Max Henkel - All Rights Reserved »*),
`Tythee/Minecraft-Reflex` (*« All rights reserved »*), et
`notverycomfy/world-cleanup` (*« proprietary. No permission is granted to copy, modify, distribute »*)
— ce dernier étant le cas d'école du **source-available qui n'est pas du libre**.

**Lire ce code pour comprendre un mécanisme reste licite. En recopier les lignes ne l'est pas.**

---

## 7. Mods sans source publique, ou dont le dépôt restait à trouver

Les 25 mods absents de Modrinth ou sans `source_url` ont fait l'objet d'une recherche dédiée. Les
verdicts sont déjà intégrés dans le tableau §2 ; voici ce qui n'y tenait pas.

### 7.1 La famille `someaddons` — le mythe du « dépôt vide » est à moitié faux, et c'est pire

La reconnaissance précédente notait que `github.com/someaddons/smoothchunksave` « ne contient QUE
`.github/ISSUE_TEMPLATE` ». **C'est exact mais trompeur, et la conclusion était trop clémente.**

**Ce qui est vrai** [V] : c'est la branche `main` qui ne contient qu'un `bug_report.yaml` et un
README. **Le code existe**, sur des branches nommées par version (`neo26.1`, `neo26.2`,
`fabric26.2`…), et il est abondant : 26 mixins pour `connectivity`, 18 pour `structure-essentials`,
7 pour `chunk-sending`, 5 pour `leaky`, 3 pour `loot-integrations`, 1 pour `memory-settings`.

**Ce qui bloque réellement** [V] : **aucun des six dépôts n'a de fichier `LICENSE`**, et chaque
`neoforge.mods.toml` déclare `license="ARR"` — écrit par l'auteur lui-même, pas déduit d'une
étiquette de plateforme. CurseForge affiche la même chose. C'est **regarder sans toucher** :

| dépôt | mixins | dernière MAJ | branche 26.2 ? |
|---|---|---|---|
| `someaddons/chunksending` | 7 | **15/09/2026** (aujourd'hui) | oui |
| `someaddons/Leaky` | 5 | 08-12/09/2026 | oui (`neo26.2`) |
| `someaddons/MemorySettings` | 1 | 21/07/2026 | oui |
| `someaddons/structureessentials` | 18 | 05/07/2026 | `fabric26.2` |
| `someaddons/LootIntegrations` | 3 | **11/04/2026** | **non** |
| `someaddons/connectivity` | 26 | **03/04/2026** | **non** |

Les deux dernières lignes méritent qu'on s'y arrête : `connectivity` affiche **161 millions de
téléchargements** et **n'a pas été mis à jour depuis cinq mois**. Le jar `26.1-7.6` « couvre » 26.2
par tolérance de plage de versions, pas par portage. Sur un serveur en production, c'est une
différence qui se paie.

### 7.2 Deux étiquettes « MIT » qui ne valent rien

- **`framepacer`** : MIT affiché sur Modrinth, **aucun dépôt** — le compte GitHub de l'auteur existe
  et a zéro dépôt public [V].
- **`simple-animal-pens`** : MIT affiché sur Modrinth, dépôt trouvé (`dvirov/Simple-Animal-Pens`),
  **aucun fichier `LICENSE` dedans** [V].

Une étiquette de plateforme n'est pas une licence. Sans texte à lire, **juridiquement on n'a rien** —
et il n'y a de toute façon aucun code à prendre dans ces deux cas.

### 7.3 Ce que les chiffres de téléchargement mesurent vraiment

Mis côte à côte, le classement est édifiant :

| mod | téléchargements | contenu réel |
|---|---|---|
| `connectivity` | 161,2 M | 26 mixins, non maintenu depuis 5 mois, ARR |
| `structure-essentials` | 99,0 M | 18 mixins, ARR |
| `loot-integrations` | 79,9 M | 3 mixins, pas de branche 26.2, ARR |
| `chunk-sending` | 70,9 M | 7 mixins, ARR |
| **`memory-settings`** | **60,9 M** | **un mixin qui affiche un avertissement** |
| `falling-blocks-fps-fix` | 144 k | **dépôt-vitrine de 1 Ko, zéro code** |
| `fps-tune` | **1 023** | 2 mixins, MIT, CI, tests, documentation |
| `bulk-merchant` | **150** | 1 mixin, MIT, structure multi-loader propre |

Les deux dépôts les plus propres et les plus librement réutilisables du lot sont les deux derniers.

### 7.4 Deux « mods » qui n'en sont pas

- **`expanded-weapon-enchantings`** : le jar de 255 Ko ne contient **aucun `.class`**. Il n'y a que
  `data/minecraft/enchantment/{breach,density,impaling}.json`, des tags, quelques `.mcfunction`, et
  `pack.mcmeta`. Les `fabric.mod.json` / `neoforge.mods.toml` sont des manifestes vides qui le font
  passer pour un mod sur quatre loaders. [V] **C'est un datapack déguisé** — reproductible en
  quelques fichiers JSON, zéro ligne de Java.
- **`tct-clearlag`** : généré par **MCreator** (horodatages du zip figés au 01/01/1980, dossier
  `procedures/`, aucun mixin). [V]

---

## 8. Annexe — l'upscaling, DLSS et FSR : ce que la lecture du code établit

> Cette annexe répond à une demande arrivée en cours de reconnaissance : **prioriser DLSS**, en
> acceptant d'exiger le backend Vulkan et un redémarrage. Elle reste **de la reconnaissance** : rien
> n'a été écrit dans `Lanterne/src/`. Voir l'avertissement en fin de section.
>
> Sources : dépôt `superresolution` cloné en partiel dans `C:/tmp/lanterne-recon/superresolution`
> (branche `dev`, commit `a7742a0`, GPL-3.0, aucun `.dll` téléchargé), et `C:/mc262src/`.

### 8.1 La mauvaise nouvelle, et il faut la dire en premier

**`superresolution` ne jitter PAS la matrice de projection de Minecraft, et ne produit AUCUN vecteur
de mouvement.** [V]

- La séquence de Halton existe bien —
  `thirdparty/fsr2/Fsr2Utils.java` : `halton(int index, int base)` (l. 96),
  `ffxFsr2GetJitterPhaseCount(float, float)` (l. 108) = `8 × (display/render)²`,
  `ffxFsr2GetJitterOffset(int, int)` (l. 114) = `halton(i+1, 2) − 0.5`, `halton(i+1, 3) − 0.5`.
- Mais **aucun mixin du dépôt ne touche `GameRenderer#getProjectionMatrix`, `Camera#setupPerspective`,
  `Projection#getMatrix` ni `RenderSystem#setProjectionMatrix`** (recherche faite sur les cinq
  *source sets* et les trois loaders). Le jitter est **délégué au shader pack** : le mod expose au
  pack Iris des uniformes `SRJitterOffset` / `SRPreviousJitterOffset` et des macros
  `SR_SHOULD_APPLY_JITTER`, et c'est le pack qui multiplie sa propre matrice.
- En mode « hack » (vanilla, sans shader pack), `MinecraftRenderHandler.java` l. 273-282 passe
  littéralement `new Vector2f(0)` comme jitter et `0` comme longueur de séquence.
- Les vecteurs de mouvement : `AlgorithmManager#getMotionVectorsFrameBuffer()` est **déprécié et
  retourne `null`** (l. 40-46), et le mode vanilla passe une **texture vide** nommée
  `"SRMainEmptyMotionVectorTexture"`. Le drapeau de configuration
  `SuperResolutionConfig.isGenerateMotionVectors()` existe mais **n'est appelé nulle part** — code
  mort.

**Conséquence directe** : le seul upscaling que `superresolution` fait réellement tourner **sans
shader pack** est **spatial** (FSR1, SGSR1, Anime4K). Ses algorithmes temporels (FSR2, DLSS, XeSS)
ne sont pleinement fonctionnels qu'avec Iris. **[S]** corroboré par le fait que
`AlgorithmManager.setMatrixVanilla(Matrix4f, Matrix4f)` (l. 84) **n'a aucun appelant**.

**Donc : la reconnaissance ne fournit pas de recette toute faite pour DLSS en vanilla.** Elle
fournit trois choses, et il faut les distinguer honnêtement de ce qu'elle ne fournit pas.

### 8.2 FSR 1.0 : entièrement faisable, et sans un octet de binaire natif [V]

- Implémentation Java : `common/src/main/java/io/homo/superresolution/common/upscale/algo/legacy/fsr1/FSR1.java`
- Shaders : `common/src/main/resources/shader/fsr1/fsr1_main.comp.glsl` (280 lignes) + les en-têtes
  officiels AMD `ffx_a.h` (3 739 l.) et `ffx_fsr1.h` (1 281 l.), sous **MIT** (texte dans
  `common/src/main/resources/licenses/fsr1.txt`).
- **Un seul fichier compute, compilé deux fois** avec `#define FSR_EASU=1` puis `#define FSR_RCAS=1`.
  UBO `std140` de sept flottants : `{vec2 renderViewportSize, vec2 containerTextureSize,
  vec2 upscaledViewportSize, float sharpness}`.
- Prérequis : **OpenGL 4.3 + DSA**. Aucune dépendance native. FP16 optionnel, détecté par
  `GL_EXT_shader_16bit_storage` / `GL_NV_gpu_shader5`, avec repli logiciel écrit à la main.
- **Marche donc sur OpenGL comme sur Vulkan**, et sur toutes les cartes.

MIT dans un projet GPL-3.0 : absorbable sans réserve, crédit dans `NOTICE.md`.

### 8.3 Le jitter en 26.2 : le point d'injection existe, et il est propre [V]

La chaîne complète, lue dans `C:/mc262src/` :

1. `net/minecraft/client/Camera.java` l. 110 → `setupPerspective(0.05F, depthFar, fov, w, h)` ;
   l. 135 → `this.projection.getMatrix(cameraState.projectionMatrix)`.
   ⚠ l. 183 : `createProjectionMatrixForCulling()` est **une matrice séparée** (FOV différent) —
   **ne pas y toucher**.
2. `net/minecraft/client/renderer/Projection.java` l. 63 :
   `public Matrix4f getMatrix(Matrix4f dest)` — c'est là que la matrice est fabriquée, en
   **reversed-Z** (`near = zFar; far = zNear`, l. 69-70) et avec `zZeroToOne` selon le backend.
   ⚠ l. 79 : `getMatrixVersion()` — un système de versionnage qui évite le réupload.
3. `net/minecraft/client/renderer/state/level/CameraRenderState.java` l. 29 :
   `public Matrix4f projectionMatrix` — **champ public et mutable**.
4. **`net/minecraft/client/renderer/GameRenderer.java#renderLevel(DeltaTracker)`, l. 529** :
   - l. 539 : `Matrix4f projectionMatrix = new Matrix4f(cameraState.projectionMatrix);` ← **copie locale**
   - l. 546 : `projectionMatrix.mul(bobStack.last().pose());` (view bobbing)
   - **l. 561 : `RenderSystem.setProjectionMatrix(this.levelProjectionMatrixBuffer.getBuffer(projectionMatrix), ProjectionType.PERSPECTIVE);`**

**Le point d'injection est la ligne 561**, sur la copie locale de la ligne 539. Trois raisons, toutes
vérifiées :

- c'est **une copie**, donc on ne pollue ni le culling (matrice indépendante), ni la HUD
  (`hudProjection` + `hud3dProjectionMatrixBuffer`, l. 575-577), ni les `PostChain` (projection
  orthographique) ;
- la surcharge empruntée est **`ProjectionMatrixBuffer#getBuffer(Matrix4f)`**, qui est le chemin
  **sans cache de version** : elle invalide `lastUploadedProjection` et réécrit systématiquement.
  Un jitter différent à chaque image passe donc sans avoir à contourner `getMatrixVersion()` ;
- la matrice transite par un UBO dédié d'une seule `mat4`
  (`RenderSystem.PROJECTION_MATRIX_UBO_SIZE`), consommé par tous les pipelines via
  `RenderSystem.getProjectionMatrixBuffer()` — donc le jitter s'applique à tout le monde d'un coup.

⚠ **Piège reversed-Z** : un jitter en X/Y n'y touche pas, mais **toute reconstruction de position
depuis la profondeur devra en tenir compte** (`near`/`far` échangés, `D32_FLOAT`).

### 8.4 L'accès à Vulkan en 26.2 : la réflexion est presque inutile [V]

C'est la bonne surprise, et elle contredit ce que la reconnaissance précédente déduisait du code de
`superresolution`.

Dans `C:/mc262src/com/mojang/blaze3d/vulkan/VulkanDevice.java`, **tous les accesseurs sont publics** :

| signature | ligne |
|---|---|
| `public VulkanInstance instance()` | 156 |
| `public VkDevice vkDevice()` | 160 |
| `public VulkanQueue graphicsQueue()` | 164 |
| `public VulkanCommandEncoder createCommandEncoder()` | 185 |

Et `VulkanQueue` est un **`record`** : `public record VulkanQueue(VkQueue vkQueue, int queueFamilyIndex)`
(l. 19) — donc `vkQueue()` et `queueFamilyIndex()` sont publics d'office.
`VulkanCommandEncoder` expose `execute(VkCommandBuffer)` (l. 164) et `createFence()` (l. 618).

**Le seul obstacle** est `com/mojang/blaze3d/systems/GpuDevice.java` l. 25 :
`private final GpuDeviceBackend backend;` — **sans accesseur public**. `RenderSystem.getDevice()`
rend le `GpuDevice`, pas le `VulkanDevice`.

Un **unique accesseur Mixin** suffit donc, là où `superresolution` réfléchit sur tout :

```
@Mixin(GpuDevice.class) @Accessor("backend") GpuDeviceBackend lanterne$backend();
```

puis, tout étant public : `vd.vkDevice()`, `vkDevice.getPhysicalDevice()` (LWJGL),
`.getInstance()` (LWJGL), `vd.graphicsQueue().queueFamilyIndex()`. **Mojang elle-même utilise
`vkDevice.getPhysicalDevice().getInstance()`** dans `VulkanBackend.java` l. 195-200 pour créer VMA —
le chemin est donc sûr.

⚠ `PreferredGraphicsApi.VULKAN` signifie *« essaye Vulkan, sinon retombe sur OpenGL »*
(`PreferredGraphicsApi#getBackendsToTry()`, l. 36). **Il faut tester le backend effectif
(`instanceof VulkanDevice`), jamais l'option.**

### 8.5 Le tampon de profondeur, et où insérer l'image finale [V]

- `com/mojang/blaze3d/pipeline/RenderTarget.java` l. 128/132 :
  `getDepthTexture()` et `getDepthTextureView()` sont **publics**.
- `createBuffers` l. 95-99 : le depth est créé en **`GpuFormat.D32_FLOAT`** avec l'usage **`15`** =
  `COPY_DST | COPY_SRC | TEXTURE_BINDING | RENDER_ATTACHMENT`. **Il est donc samplable et copiable
  nativement, sans le moindre patch.**
- Deux voies de lecture : déclarative (un post-effect JSON avec `"use_depth_buffer": true`, cf.
  `PostChainConfig$TargetInput` l. 94-102 et `PostPass$TargetInput` l. 171-193), ou programmatique
  (`bindTexture` / `copyTextureToTexture`).
- ⚠ **Le depth est effacé tôt** : `GameRenderer#render` l. 408-412 (début de frame) puis
  **l. 443 : `clearDepthTexture(mainRenderTarget.getDepthTexture(), 0.0)` — juste après le monde et
  avant la GUI.** `superresolution` neutralise ce clear par `@Redirect` et le rétablit après.
  **La fenêtre pour lire le depth est entre la fin de `renderLevel()` et la ligne 443** — ce qui
  tombe bien : c'est aussi la fenêtre où l'on veut upscaler, puisque la GUI doit rester en
  résolution native.
- **Présentation** : `Minecraft.java` l. 1334-1339 appelle
  `windowSurface.blitFromTexture(RenderSystem.getDevice().createCommandEncoder(), colorTexture)`.
  C'est le point d'insertion pour présenter la texture upscalée. La signature exacte est
  `GpuSurface#blitFromTexture(CommandEncoder, GpuTextureView)` (`GpuSurface.java` l. 72), et elle
  **exige que la texture porte `USAGE_COPY_SRC`**.
- Pour mentir sur la résolution interne : `Window#setWidth(int)` et `setHeight(int)` sont **publics**
  en 26.2 (`Window.java` l. 486 et 490). ⚠ `getGuiScaledWidth/Height` ne suivent pas : il faut
  rappeler `setGuiScale(int)`.

⚠ **`PostChain#resize(int,int)` n'existe plus en 26.2.** Le redimensionnement passe par
`addToFrame(FrameGraphBuilder, int screenWidth, int screenHeight, TargetBundle)` (l. 125). Toute
documentation ou tout mixin prévoyant `PostChain.resize` est périmé.

### 8.6 Les quatre obstacles, traités et non contournés

**1. Le binaire natif DLSS.** `nvngx_dlss.dll` **n'est pas redistribué** par `superresolution` — il
est **téléchargé à l'exécution** [V], via un `ExtraResource.builder("nvngx_dlss.dll")`
(`AlgorithmDescriptions.java` l. 286-318) avec quatre miroirs, dont
`raw.githubusercontent.com/NVIDIA/DLSS/refs/heads/main/lib/Windows_x86_64/rel/nvngx_dlss.dll`.
Le dossier `common/src/main/resources/lib/` **n'existe pas dans le dépôt** : il est listé au
`.gitignore` (ligne 149) et rempli au build par `native/build.gradle.kts`.
Le pont JNI est un unique fichier C++ de 667 lignes (`native/cpp/SRNativeNGX/src/ngx_jni.cpp`),
compilé en `libSuperResolutionNGX+<plateforme>+<type>.dll`, avec côté Java 29 fichiers
(`core/ngx/`, 2 116 lignes) dont `NgxNative.java` (déclarations `static native`) et `NgxVulkan.java`.

> **Réponse franche à la question posée : je ne peux pas produire ce binaire.** Compiler le pont JNI
> exige le SDK NVIDIA DLSS (sous-module `github.com/NVIDIA/DLSS`), CMake, un toolchain C++ et le SDK
> Vulkan. **Ce qui est à ma portée, c'est l'échafaudage Java + le point de branchement.**
> **Ce qui ne l'est pas, c'est le `.so`/`.dll` du pont.** Et c'est structurel, pas une paresse : un
> mod GPL-3.0 **ne peut pas** redistribuer `nvngx_dlss.dll`. La stratégie de téléchargement
> différé de `superresolution` n'est pas un hack, c'est **la** solution licence, et il faut la copier :
> téléchargement au premier usage, miroirs, vérification SHA-256.

**2. Les vecteurs de mouvement.** `superresolution` n'en fournit aucun en vanilla (§8.1). La
technique de `upscaled` — les reconstruire depuis le tampon de profondeur — est **correcte pour le
mouvement de la caméra et fausse pour tout le reste** : une entité qui marche, un chunk qui se
recharge, une particule, un bloc animé produiront du fantôme, parce que la reprojection suppose que
la scène est statique. Il faut l'écrire dans la documentation utilisateur, pas le découvrir en jeu.
**La seule piste libre pour faire mieux est `interframe` (§3.10), MIT.**

**3. Le jitter.** Résolu : §8.3 donne le fichier, la ligne et la raison. La suite de Halton (base 2 /
base 3, `8 × ratio²` phases) est à réécrire — c'est une vingtaine de lignes, et
`Fsr2Utils.java` en donne la formule exacte sous GPL-3.0, donc absorbable.

**4. Le repli.** `superresolution` a trois étages [V] :
(a) **pré-vol** — `NgxInitializer#initializeIfSupported(int)` retourne `false` sans jamais lever
d'exception dans **cinq** cas (DLL JNI non chargée, Vulkan absent, device nul, échec
d'initialisation, exigences matérielles non remplies), avec **mise en cache par handle de
`VkDevice`** pour ne pas réessayer à chaque image ;
(b) **sélection** — `SuperResolutionConfig#getUpscaleAlgorithm()` retombe sur
`getDefaultAlgorithm()` si l'algorithme est inconnu, non supporté, ou désactivé par le pack courant,
**et réécrit la configuration** ;
(c) **échec pendant `initialize()`** — et là il **relance** l'exception si c'est un `Error`.

> **Ne pas copier l'étage (c).** Pour Lanterne, la chaîne demandée — **NGX → FSR1 → rendu natif** —
> se construit avec (a) et (b) seulement : un `try/catch (Throwable)` autour de l'initialisation,
> repli silencieux pour le rendu, **une ligne claire dans le journal** nommant la raison exacte
> (pas de RTX / backend OpenGL / DLL absente / échec d'initialisation).

### 8.7 Ce que je n'ai pas pu voir, et qu'il ne faut pas me faire dire

- Je n'ai **pas** lu `ngx_jni.cpp` ligne à ligne (667 lignes), ni `VulkanSwapchain.java` (~2 000 l.),
  ni `GlVulkanInteropAlgorithm.java` (918 l.).
- Je n'ai **pas** exécuté le jeu. Aucune de ces conclusions n'a été vue à l'œil : elles viennent
  toutes de la lecture du code source.
- `C:/mc262src/` est un décompilé **patché NeoForge** (les classes contiennent des appels
  `net.neoforged.*`). Les numéros de ligne sont exacts pour ce décompilé et peuvent différer de
  quelques lignes d'un décompilé Mojang pur.
- Les `.java` de `superresolution` contiennent des **directives de préprocesseur littérales**
  (`#if MC_VER >= MC_26_2 … #endif`, traitées au build par JCPP). **Ce ne sont pas des fichiers
  compilables tels quels** — piège si on copie-colle.
- La génération d'images de `superresolution` est une **coquille vide** : `FrameGenerationDescriptions.java`
  l. 87 indique que les providers réels (Streamline / NVNGX) sont enregistrés par un **autre mod
  appelé Wisteria**, absent de ce dépôt.

### 8.8 Avertissement de périmètre

La consigne de cette reconnaissance est explicite : *« lecture seule sur le code — tu n'écris qu'un
rapport à la fin »*, *« ne modifie aucun fichier source du mod »*. **Aucune ligne n'a donc été
écrite dans `Lanterne/src/main/java/fr/clubcitrouille/lanterne/client/upscale/`, ni dans
`Settings.java`, ni dans `lanterne.mixins.json`.**

La demande d'écrire l'échafaudage FSR1 puis DLSS **est une tâche d'implémentation distincte**. Cette
annexe lui donne tout ce dont elle a besoin : le point d'injection du jitter (fichier + ligne + la
raison pour laquelle cette surcharge-là est la bonne), la façon d'atteindre le device Vulkan sans
réflexion, l'emplacement et le format du depth, le point de présentation, la stratégie de licence
pour la DLL, et les quatre pièges de 26.2 (`PostChain.resize` disparu, reversed-Z, clear du depth
en ligne 443, `PreferredGraphicsApi` qui n'est qu'une préférence).

---

## 9. Sources primaires

- Sources décompilées Minecraft 26.2 : `C:/mc262src/` (7 055 fichiers `.java`, noms officiels Mojang)
- Dépôts clonés pour lecture : `C:/tmp/lanterne-recon/r/` (44 dépôts, `--depth 1 --filter=blob:none`)
- API Modrinth : `https://api.modrinth.com/v2/project/<slug>` — métadonnées, **pas** source de vérité
  pour la licence
- Licences : `https://raw.githubusercontent.com/<owner>/<repo>/HEAD/{LICENSE,LICENSE.md,LICENSE.txt,COPYING}`
- Primer NeoForge 26.1.x → 26.2 : <https://docs.neoforged.net/primer/docs/26.2/>
- Notes internes recoupées : `notes/reconnaissance-26-2.md`, `notes/MINE.md`, `notes/recherche-serveur.md`,
  `notes/recherche-client.md`, `notes/serveur-un-coeur.md`, `NOTICE.md`
