# Recherche — génération et chargement des chunks (Minecraft 26.1.2 / NeoForge)

> Recherche seule, aucun fichier de `src/` touché. Toute classe citée a été lue directement dans
> `C:/Users/trufa/Documents/Lanterne/.mcsrc/` (sources décompilées officielles 26.1.2) sauf mention
> contraire explicite (« non vérifié en source, recherche web »). C2ME, Noisium et Moonrise ont été
> lus sur leur dépôt GitHub réel via `gh api`/`raw.githubusercontent.com`, avec la branche/commit la
> plus proche de notre version quand elle existe (`C2ME-fabric@dev/26.1.2`, `Moonrise@mc/26.1`).
> Contrainte non négociable rappelée par le brief : **même graine → même monde**, avec ou sans le
> mod. Chaque piste est jugée à cette aune.

---

## 1. Chaîne d'appels réelle — de `ChunkMap` à `NoiseBasedChunkGenerator.fillFromNoise`

### 1.1 La machine à états, vérifiée classe par classe

Les noms du brief (`ChunkMap`, `ChunkHolder`, `ChunkStatus`) sont corrects en 26.1.2, mais la
mécanique interne a été refactorée en un pipeline déclaratif : `ChunkStatus` (12 statuts, énumérés
dans `ChunkStatus.java`) → `ChunkStep` (un enregistrement immuable : statut cible + dépendances +
tâche) → `ChunkPyramid` (assemble tous les `ChunkStep` en deux pyramides : `GENERATION_PYRAMID` pour
générer, `LOADING_PYRAMID` pour charger depuis le disque) → `ChunkStatusTasks` (le corps réel de
chaque étape, méthodes statiques).

Statuts, dans l'ordre (`ChunkStatus.java`, vérifié) :
`EMPTY → STRUCTURE_STARTS → STRUCTURE_REFERENCES → BIOMES → NOISE → SURFACE → CARVERS → FEATURES →
INITIALIZE_LIGHT → LIGHT → SPAWN → FULL`.

Chaque statut est câblé à sa tâche réelle dans `ChunkPyramid.GENERATION_PYRAMID` (vérifié,
`ChunkPyramid.java`) :

| Statut | Méthode (`ChunkStatusTasks`) | Ce qu'elle fait |
|---|---|---|
| `STRUCTURE_STARTS` | `generateStructureStarts` | `ChunkGenerator.createStructures(...)` |
| `STRUCTURE_REFERENCES` | `generateStructureReferences` | `ChunkGenerator.createReferences(...)` |
| `BIOMES` | `generateBiomes` | `ChunkGenerator.createBiomes(...)` → `NoiseBasedChunkGenerator.doCreateBiomes` |
| `NOISE` | `generateNoise` | **`ChunkGenerator.fillFromNoise(...)`** → `NoiseBasedChunkGenerator.fillFromNoise` |
| `SURFACE` | `generateSurface` | `ChunkGenerator.buildSurface(...)` → `randomState.surfaceSystem().buildSurface(...)` |
| `CARVERS` | `generateCarvers` | `ChunkGenerator.applyCarvers(...)` (utilise déjà `noiseChunk.aquifer()`) |
| `FEATURES` | `generateFeatures` | `ChunkGenerator.applyBiomeDecoration(...)` |
| `INITIALIZE_LIGHT` | `initializeLight` | `ThreadedLevelLightEngine.initializeLight(...)` |
| `LIGHT` | `light` | `ThreadedLevelLightEngine.lightChunk(...)` |
| `SPAWN` | `generateSpawn` | `ChunkGenerator.spawnOriginalMobs(...)` |
| `FULL` | `full` | construit le `LevelChunk` final, **sur le thread principal** (`context.mainThreadExecutor()`) |

### 1.2 La descente réelle dans `fillFromNoise` (statut `NOISE`), méthode par méthode vérifiée

`NoiseBasedChunkGenerator.fillFromNoise(Blender, RandomState, StructureManager, ChunkAccess)` —
signature vérifiée, retourne un `CompletableFuture<ChunkAccess>`, exécuté via
`Util.backgroundExecutor().forName("wgen_fill_noise")` (donc déjà hors du thread principal). Corps :
acquiert (`LevelChunkSection.acquire()`) toutes les sections concernées, puis appelle
`doFill(...)`, qui :

1. crée (ou réutilise, via `ChunkAccess.getOrCreateNoiseChunk`) un `NoiseChunk` — l'objet qui porte
   tout l'état d'interpolation pour ce chunk ;
2. boucle `cellXIndex` (0..3 si `cellWidth=4`) → `advanceCellX` → `cellZIndex` → `cellYIndex` →
   `selectCellYZ` (remplit les caches `CacheAllInCell` pour la cellule courante) → boucle interne
   `yInCell`/`xInCell`/`zInCell` (les blocs réels) → `noiseChunk.getInterpolatedState()` →
   `MaterialRuleList.calculate()` (chaîne `aquifer.computeSubstance(...)` puis, si activé,
   `OreVeinifier`) → écrit dans la section via `LevelChunkSection.setBlockState(..., false)` (le
   `false` = pas de mise à jour de mesh/lumière immédiate, cohérent avec le fait que la lumière est
   un statut séparé, `INITIALIZE_LIGHT`/`LIGHT`, après `FEATURES`).

Chaque cellule (par défaut, taille dépendant du datapack — `getCellWidth()`/`getCellHeight()`
dérivés de `noiseSizeHorizontal`/`noiseSizeVertical` en quarts, typiquement 4×4×8 blocs pour
l'overworld vanilla, non codé en dur dans `NoiseSettings.java`) a ses 8 coins de densité calculés
**une seule fois** par le graphe `DensityFunction` (voir §4), puis les fonctions marquées
`Interpolated` sont trilinéairement interpolées à l'intérieur de la cellule ; la fonction de densité
**finale** (après `Aquifer`/`Beardifier`/`OreVeinifier`) est, elle, marquée `CacheAllInCell` : elle
est **réévaluée exactement pour chaque bloc** de la cellule (pas d'interpolation), parce
qu'interpoler ces contributions non linéaires donnerait un terrain visuellement faux (grottes/veines
qui ne suivent pas leurs propres règles). **C'est là que va le plus gros du temps CPU par bloc** :
tout le reste du graphe de bruit est amorti sur 8 échantillons par cellule (64 blocs en 4×4×4),
mais `Aquifer.computeSubstance` + l'addition du `Beardifier` sont exécutés à raison d'un appel par
bloc, soit jusqu'à 64× plus souvent que le bruit brut sous-jacent.

### 1.3 Où va le temps — ordre de grandeur

Pour un chunk 16×16, hauteur totale d'overworld (~384 blocs, ~24 cellules de 8 en Y si
`cellHeight=8`) : `cellCountX × cellCountZ × cellCountY = 4 × 4 × 24 = 384` cellules, chacune
remplissant un tableau de `cellWidth² × cellHeight` blocs (64 pour 4×4×8) via `CacheAllInCell` →
**~24 576 appels** à `aquifer.computeSubstance` + à la fonction de densité finale par chunk généré
(hors carvers/features/surface, qui sont des passes séparées). Ce nombre n'est pas gratuit à
réduire : c'est le prix pour que les grottes/aquifères aient une forme correcte, pas un oubli
d'optimisation.

---

## 2. Parallélisme réel — vérifié dans `Util.java`, `MinecraftServer.java`, `ChunkMap.java`

### 2.1 Un seul pool de workers pour tout le serveur, toutes dimensions confondues

`Util.backgroundExecutor()` (vérifié, `net/minecraft/util/Util.java:99-100,210`) renvoie un
**`ForkJoinPool` unique, statique, partagé par toute la JVM** (`BACKGROUND_EXECUTOR = makeExecutor
("Main")`), dimensionné par `maxAllowedExecutorThreads()` :

```java
public static int maxAllowedExecutorThreads() {
    return Mth.clamp(Runtime.getRuntime().availableProcessors() - 1, 1, getMaxThreads());
}
```

`getMaxThreads()` lit la propriété système `-Dmax.bg.threads=N` (sinon plafond 255). **Par défaut :
nombre de cœurs logiques moins un**, un seul pool, un seul pour l'overworld, le nether, l'end et
toute dimension modée. `MinecraftServer.java:354` : `this.executor = Util.backgroundExecutor();`,
réutilisé tel quel pour chaque `new ServerLevel(...)` (`MinecraftServer.java:439-479`, vérifié).
**Conséquence directe** : générer/charger l'overworld et le nether en parallèle (un joueur qui
traverse un portail pendant qu'un autre explore) fait compétition pour le même pool de threads —
il n'y a pas d'isolation par dimension.

### 2.2 Ce qui tourne où, vérifié

- **Thread principal (tick serveur)** : seul le statut `FULL` (`ChunkStatusTasks.full`, vérifié
  ligne « `CompletableFuture.supplyAsync(() -> {...}, context.mainThreadExecutor())` ») construit
  le `LevelChunk` définitif sur le thread principal — parce que ça touche l'état vivant du niveau
  (enregistrement des ticks, des entités, du bus d'événements NeoForge `ChunkEvent.Load`).
- **Pool partagé (`Util.backgroundExecutor()`)** : tous les autres statuts de génération
  (`STRUCTURE_STARTS` → `SPAWN` inclus), plus `ChunkMap`'s deux `ConsecutiveExecutor` nommés
  `"worldgen"` et `"light"` (vérifié, `ChunkMap.java:190-194` : `new ConsecutiveExecutor(executor,
  "worldgen")` / `"light"`, tous deux construits à partir du **même** `executor` passé en
  paramètre, donc du même `Util.backgroundExecutor()`).
- **Le moteur de lumière est sérialisé, pas parallèle** : `ThreadedLevelLightEngine` encapsule un
  **unique** `ConsecutiveExecutor` (`"light"`). `ConsecutiveExecutor` garantit qu'une seule tâche de
  lumière est en cours d'exécution à un instant donné pour toute la dimension — la lumière de
  10 chunks générés simultanément par 10 workers est traitée **une par une**, en lots de 1000 tâches
  max (`DEFAULT_BATCH_SIZE = 1000`, `ThreadedLevelLightEngine.runUpdate()`, vérifié). C'est un vrai
  goulot d'étranglement structurel : la génération de terrain scale avec le nombre de cœurs, la
  lumière non.
- **`RegionFileStorage`** (I/O disque) tourne aussi sur `Util.backgroundExecutor()`
  (`ChunkMap.java:565,921` : `.forName("parseChunk")`, `.forName("upgradeChunk")`) — donc l'I/O
  disque, la génération de bruit et la lumière se disputent tous le même pool de N-1 threads.

### 2.3 Ce qui est déjà asynchrone, contrairement à une intuition naïve

`fillFromNoise` retourne déjà un `CompletableFuture` et tourne déjà hors thread principal (vérifié,
confirme et précise l'axe 7 du rapport `recherche-serveur.md` précédent). La question n'est donc
**pas** « faut-il paralléliser la génération » (déjà fait), mais « le partage d'un seul pool entre
génération/E-S/lumière est-il le bon compromis, et le moteur de lumière mono-thread est-il un
goulot réel ». Réponse du terrain (C2ME/Moonrise, cf. §3) : **oui**, c'est exactement ce que ces deux
mods attaquent en premier.

---

## 3. Ce que font C2ME, Noisium et Moonrise — lu sur code source réel

Sources lues : `RelativityMC/C2ME-fabric` branche `dev/26.1.2` (commit
`35c91e26827108c07b422afa9f3d3e1df45176eb`, via `gh api`), `Steveplays28/noisium` (dépôt maintenant
archivé, branche `main`/`1.20-1.20.1`), `Tuinity/Moonrise` branche `mc/26.1` (la plus proche de notre
version — Moonrise a même une branche `mc/26.1.2`-adjacente). Package tree complet obtenu via
`gh api repos/.../git/trees/<sha>?recursive=1`.

### 3.1 C2ME (`com.ishland.c2me`, ~20 modules Gradle indépendants)

C2ME n'est pas un mod monolithique : c'est une collection de modules activables séparément. Ceux
pertinents pour la génération de chunks :

- **`c2me-opts-worldgen-vanilla` / `mixin/aquifer/MixinAquiferSamplerImpl.java`** — **(d) réduction
  du travail, vérifié et confirmé applicable tel quel en 26.1.2** : élimine l'allocation
  `new MutableDouble(Double.NaN)` faite par `Aquifer.NoiseBasedAquifer.computeSubstance` (voir §4.3,
  j'ai vérifié que cette ligne existe **encore** dans notre `.mcsrc` actuel, ligne 248 de
  `Aquifer.java`) en remplaçant le paramètre de sortie mutable par un champ d'instance primitif.
  Rien d'algorithmique ne change : c'est un pur détail d'implémentation Java (boîte évitée).
- **`c2me-opts-worldgen-vanilla` / `mixin/structure_weight_sampler/MixinStructureWeightSampler.java`**
  — optimise l'ancien nom de `Beardifier` (mappings historiques : `StructureWeightSampler` =
  `Beardifier` aujourd'hui). **(a)/(d)**, non lu en détail (budget), mais cible confirmée pertinente.
- **`c2me-opts-worldgen-vanilla` / `mixin/the_end_biome_cache/MixinTheEndBiomeSource.java`** — met en
  cache le biome source de l'End, jugé assez coûteux pour être ciblé indépendamment par **deux**
  projets non affiliés (voir aussi Moonrise, `end_island`, §3.3). Signal fort de coût réel.
- **`c2me-opts-dfc`** (« Density Function Compiler ») — **(c) réécriture d'algorithme, la plus
  radicale du lot** : `common/gen/jvm/emitters/*BytecodeEmitter.java` — **compile le graphe
  `DensityFunction` en bytecode JVM à l'exécution**, au lieu de l'interpréter nœud par nœud (chaque
  `compute()` vanilla est un appel virtuel récursif sur l'arbre). Module séparé
  `mixin/equality/MixinDensityFunctionNoise.java` (lu intégralement) : ajoute `equals()`/`hashCode()`
  à `DensityFunctions.Noise` pour permettre la déduplication de sous-graphes structurellement
  identiques mais objets distincts (le `wrap()` de `NoiseChunk`, voir §4, dédoublonne par **identité
  d'objet**, pas par structure). **Nuance importante, vérifiée** : dans notre 26.1.2, `Noise` est
  déjà un `record` et son champ `NoiseHolder` aussi (`DensityFunction.java:81`, `record
  NoiseHolder(Holder<NoiseParameters> noiseData, @Nullable NormalNoise noise)`) — l'égalité
  structurelle est donc **déjà partiellement acquise nativement** par Mojang depuis la conversion en
  `record`. Le gain restant de ce mixin C2ME dépend de si les `NormalNoise` internes sont des
  instances partagées ou reconstruites (non vérifié en profondeur ici) — **gain probablement réduit
  aujourd'hui par rapport à la version où C2ME a écrit ce mixin**, à ne pas surestimer.
- **`c2me-opts-dfc` / `MixinChunkNoiseSamplerCache2D` / `CacheOnce` / `CellCache` / `FlatCache` /
  `DensityInterpolator`** — retouche **les cinq classes de cache internes de `NoiseChunk` elles-
  mêmes** (celles que j'ai lues intégralement en §4 : `Cache2D`, `CacheOnce`, `CacheAllInCell` (=
  `CellCache`), `FlatCache`, `NoiseInterpolator` (= `DensityInterpolator`)) — confirme que ces
  classes, bien que déjà présentes côté vanilla, ont un surcoût d'implémentation exploitable (very
  probablement des indirections `HashMap`/dispatch virtuel réduites).
- **`c2me-opts-natives-math`** — **(c)**, accélération via code natif (JNI/FFI) du bruit de
  Perlin/`DoublePerlinNoiseSampler`. Risque de portabilité élevé (bibliothèque native par OS/arch),
  écarté d'emblée pour Lanterne (philosophie mixins Java purs).
- **`c2me-opts-math`** — équivalent Java pur, sans dépendance native, pour les mêmes classes de
  bruit. Plus intéressant pour Lanterne mais non lu en détail.
- **`c2me-rewrites-chunk-system`** — **(b)+(c) le plus radical** : réimplémente entièrement l'état
  des chunks (`NewChunkStatus.java`, `TheChunkSystem.java`, granularité de statuts différente de
  vanilla, E-S entièrement async via `async_chunkio/*`). C2ME a **abandonné** l'idée de patcher le
  pipeline `ChunkStatus`/`ChunkPyramid` vanilla à la marge : il le remplace. Signal fort que les
  gains « faciles » dans le pipeline existant sont déjà épuisés une fois qu'on veut aller plus loin
  que les micro-corrections.
- **`c2me-opts-scheduling`** — plus proche de l'esprit « mixin chirurgical » : retouche la
  priorisation dans `ChunkTaskDispatcher`/`ChunkHolder` (`mixin/task_scheduling/`), exécute des
  tâches de chunk « mi-tick » (`mid_tick_chunk_tasks/`), et gère l'auto-save de façon plus fine
  (`idle_tasks/autosave/enhanced_autosave`, `disable_vanilla_mid_tick_autosave`) — **sans remplacer
  la machine à états**, contrairement à `c2me-rewrites-chunk-system`. **C'est la partie de C2ME la
  plus transposable à la philosophie de Lanterne.**
- **`c2me-fixes-worldgen-threading-issues` — la découverte la plus importante pour la question du
  déterminisme.** Un module entier de mixins rend **chaque générateur de structure vanilla**
  (`MineshaftGenerator`, `NetherFortressGenerator`, `StrongholdGenerator`,
  `WoodlandMansionGenerator`, `OceanMonumentGenerator`, `SwampHutGenerator`, `DesertTempleGenerator`,
  `JungleTempleGenerator`, plus `StructureStart`, `Structure`, `StructureChecker` = notre
  `StructureCheck`) thread-safe, via un **transformateur ASM dédié**
  (`asm/ASMTransformerMakeVolatile.java` + annotation marqueur `@MakeVolatile`, fichier lu
  intégralement : rend certains champs `volatile` au niveau bytecode) et une classe
  `CheckedThreadLocalRandom` qui détecte l'usage illégal d'un `Random` partagé entre threads. **Ceci
  prouve, à la source, que le code vanilla de génération de structures a été écrit en supposant une
  exécution mono-thread implicite** (des champs réutilisés/partagés qui ne posent pas de problème en
  série mais deviennent des data races dès que deux chunks sont générés en parallèle sur des threads
  différents — ce qui est **déjà le cas aujourd'hui**, sans aucun mod, puisque `STRUCTURE_STARTS`
  tourne sur le pool partagé). Le module frère **`c2me-fixes-worldgen-vanilla-bugs` /
  `mixin/ensure_chunk_status_before_callback/MixinChunkHolder.java`** corrige un cas où un callback
  de complétion de `ChunkHolder` peut être invoqué **avant** que le statut du chunk soit
  effectivement à jour — une vraie course interne à vanilla. **Ces deux modules ne sont pas des
  optimisations : ce sont des correctifs de sûreté qu'il faut avoir en tête avant de toucher au
  degré de parallélisme existant**, pas seulement avant d'en ajouter.
- **`c2me-rewrites-chunk-serializer`** — réécrit la sérialisation NBT du chunk (`NbtWriter`,
  `NbtWriterVisitor`, propres à C2ME, plus rapides que `NbtIo` standard) et cache des tableaux
  d'octets pré-calculés pour les nombres 0-31 (évite des `String.valueOf` répétés lors de l'écriture
  des clés de section). **(d) réduction du travail**, mais nécessite de réécrire tout le chemin de
  sérialisation — risque modéré à élevé (double implémentation à maintenir en cohérence avec le
  format vanilla).

### 3.2 Noisium (`io.github.steveplays28.noisium`, dépôt maintenant archivé)

D'après le README et les discussions du dépôt (le fichier `NoiseChunkGeneratorMixin.java` exact
n'a pas pu être récupéré — 404 sur le chemin attendu, le dépôt étant archivé et sa structure ayant
pu changer entre versions ; **je n'ai donc PAS pu vérifier son corps de méthode**, contrairement à
C2ME) : Noisium cible `NoiseChunkGenerator#populateNoise` (= notre `doFill`) pour poser les blocs
**directement dans le stockage en palette** plutôt que via les méthodes de haut niveau, évitant des
recalculs de mise à jour de voisinage inutiles en cours de génération (c'est exactement ce que fait
déjà `section.setBlockState(..., false)` dans notre `.mcsrc` actuel avec son paramètre booléen à
`false` — **suggère que Mojang a depuis intégré nativement ce que Noisium visait**, mais je ne peux
pas l'affirmer avec certitude sans lire le vrai diff de Noisium). Revendique par ailleurs une mise en
cache de `horizontalCellBlockCount`/`verticalCellBlockCount` (des constantes recalculées à chaque
appel dans les anciennes versions vanilla). **Verdict sur cet axe : possiblement obsolète en 26.1.2,
à vérifier avant toute tentative de portage — le projet est archivé et non maintenu depuis un
moment, signe indirect que ses optimisations ont pu être rattrapées par le moteur lui-même.**

### 3.3 Moonrise (`ca.spottedleaf.moonrise`, branche `mc/26.1`, par l'auteur de Starlight/Tuinity/Folia)

Arborescence complète obtenue (`gh api .../git/trees/mc/26.1?recursive=1`). Moonrise est, de très
loin, le plus ambitieux des trois : ce n'est pas un ensemble de micro-corrections, c'est le
remplacement de sous-systèmes entiers, avec des noms de package extrêmement parlants :

- **`mixin/chunk_system/*`** (~35 fichiers) — remplace intégralement `ChunkMap`, `ChunkHolder`,
  `GenerationChunkHolder`, `DistanceManager`, `TicketStorage`, **`ChunkStatus`, `ChunkStep`,
  `ChunkPyramid`** (les classes exactes que j'ai lues en §1 !), `NoiseBasedChunkGenerator`,
  `RegionFile`/`RegionFileStorage`, `SerializableChunkData`, `StructureCheck`. C'est la même
  conclusion que C2ME (`c2me-rewrites-chunk-system`) : **les deux projets, indépendamment,
  abandonnent l'idée de patcher le pipeline vanilla à la marge et le remplacent entièrement.**
- **`mixin/starlight/*`** — Moonrise **embarque Starlight**, le moteur de lumière du même auteur
  (`ThreadedLevelLightEngineMixin.java`, `LevelLightEngineMixin.java`, plus les classes de chunk
  liées au stockage de la lumière). Détails techniques du README de son successeur direct
  (`RelativityMC/ScalableLux`, branche `ver/26.1.0`, lu intégralement, voir §7).
- **`mixin/fast_palette/*`** — retouche `PalettedContainer`, `LinearPalette`, `HashMapPalette`,
  `SingleValuePalette` (exactement les classes de `net/minecraft/world/level/chunk/` que j'ai listées
  en tout début de recherche) — accès plus rapide à la palette de blocs, cœur de tout accès
  bloc-par-bloc pendant la génération.
- **`mixin/block_counting/*`** (`LevelChunkSectionMixin` + `BitStorageMixin`) — **exactement la
  technique de bucketing des random ticks déjà documentée pour Lithium dans
  `recherche-serveur.md` §5.2** : Moonrise implémente la même idée (compteur de blocs
  « tickables » par section) au niveau moteur plutôt qu'en mixin isolé.
- **`mixin/fluid/FlowingFluidMixin.java`** — même cible que Lithium (`recherche-serveur.md` §3.2).
- **`mixin/collisions/ServerExplosionMixin.java`** — **touche exactement la classe et la découverte
  n°1 du rapport précédent** (`ServerExplosion.calculateExplodedPositions`, le
  `getFluidState` redondant). Signal supplémentaire, indépendant, que cette piste est la bonne.
- **`mixin/end_island/DensityFunctions$EndIslandDensityFunctionMixin.java`** — cible spécifiquement
  la fonction de densité de génération des îles de l'End, jugée coûteuse par **deux projets non
  affiliés** (voir aussi C2ME §3.1, `the_end_biome_cache`). C'est la seule fonction de densité
  individuelle (par opposition aux classes de cache génériques) que les deux projets ciblent
  spécifiquement — signal fort qu'elle mériterait d'être lue en détail dans une prochaine passe.
- **`mixin/getblock/*`** — chemins rapides pour `ChunkAccess.getBlockState`/`LevelChunk.getBlockState`
  /`Level.getBlockState`, très sollicités pendant `applyBiomeDecoration` (placement de features qui
  lisent l'environnement).

**Compatibilité, vérifiée via le README** : Moonrise déclare explicitement C2ME **incompatible**
(« C2ME is based around modifications to the chunk system, which Moonrise replaces wholesale »), et
« désactive automatiquement » les parties concurrentes de Lithium et FerriteCore — mécanisme non
garanti à valider à chaque sortie. **Ces trois mods d'inspiration ne sont donc pas cumulables entre
eux dans un même modpack** ; toute inspiration à porter dans Lanterne doit choisir un camp
(pipeline vanilla retouché à la marge façon `c2me-opts-scheduling`, ou remplacement large façon
Moonrise/`c2me-rewrites-chunk-system` — **la seconde option est hors de portée d'un mixin
« chirurgical » et présente un risque de compatibilité avec le reste du pack largement supérieur**).

### 3.4 Classification demandée (a/b/c/d) — synthèse

| Technique | Exemples vérifiés | Portable sans risque de déterminisme ? |
|---|---|---|
| (a) Mise en cache | `the_end_biome_cache` (C2ME+Moonrise), les 5 wrappers `NoiseChunk` retouchés par `c2me-opts-dfc` | Oui si le cache est invalidé correctement — **aucun changement de valeur calculée** |
| (b) Parallélisation | `c2me-opts-scheduling` (mi-tick, priorité), pools séparés par tâche | Oui pour la *planification* ; **risque réel** si ça augmente le degré de concurrence sur du code non thread-safe (cf. §3.1, `fixes-worldgen-threading-issues`) |
| (c) Réécriture d'algorithme | `c2me-opts-dfc` (compilation bytecode), `c2me-opts-natives-math`, Starlight (push vs pull) | Seulement si le nouvel algorithme est prouvé équivalent bit-à-bit (Starlight le revendique explicitement, §7) ; risque modéré à élevé sinon |
| (d) Réduction du travail | `MixinAquiferSamplerImpl` (allocation), `ChunkDataSerializer` (cache de chaînes) | **La catégorie la plus sûre** — ne change aucune valeur, seulement le chemin pour y arriver |

---

## 4. Cache de `DensityFunction` — vérifié intégralement dans `NoiseChunk.java` et `DensityFunctions.java`

### 4.1 Les cinq types de marqueurs, vérifiés (`DensityFunctions.Marker.Type`, ligne 751)

`Interpolated`, `FlatCache`, `Cache2D`, `CacheOnce`, `CacheAllInCell` — noms exacts confirmés. Chacun
est concrétisé, à la construction du `NoiseChunk` (méthode `wrap()`, ligne 383), en une classe
interne dédiée :

- **`NoiseInterpolator`** (pour `Interpolated`) : calcule la valeur aux **8 coins** de chaque cellule
  (deux tranches `slice0`/`slice1` permutées à chaque avancée en X — **zéro recalcul en traversant le
  chunk en X**), puis interpole trilinéairement pour chaque bloc intérieur. C'est le mécanisme qui
  rend la génération de terrain praticable : sans lui, chaque fonction de bruit serait évaluée à
  chaque bloc.
- **`FlatCache`** : cache 2D (X,Z), rempli une fois pour tout le chunk à la construction (utilisé
  pour `BlendAlpha`/`BlendOffset`, alimenté par le `Blender`).
- **`Cache2D`** : mémoïsation de la **dernière** colonne (x,z) seulement — un seul slot, pas un
  tableau ; efficace parce que l'ordre de balayage de `doFill` reste sur la même colonne (x,z)
  pendant toute la descente en Y.
- **`CacheOnce`** : mémoïsation à la granularité d'un **appel d'interpolation** (compteurs internes
  `interpolationCounter`/`arrayInterpolationCounter`), pour des fonctions évaluées plusieurs fois
  pour le même point logique dans des contextes différents du pipeline.
- **`CacheAllInCell`** : remplit un tableau complet de `cellWidth² × cellHeight` valeurs **par bloc,
  sans interpolation** — utilisé uniquement pour la densité finale (post-`Aquifer`/`Beardifier`),
  parce qu'une interpolation y serait incorrecte (voir §1.2).

### 4.2 Dédoublonnage du graphe (DAG), déjà fait par vanilla

`NoiseChunk.wrap()` utilise une `Map<DensityFunction, DensityFunction> wrapped` (`HashMap`, clé =
**identité d'objet** puisque la plupart des implémentations de `DensityFunction` n'ont pas
d'`equals()` structurel autre que celui hérité de leur nature de `record` quand elles en sont un).
**Conséquence vérifiée** : si le graphe de bruit du `NoiseRouter` partage une sous-expression (par
exemple `erosion` utilisé à la fois par la densité finale et par le calcul du biome), **le même
objet `DensityFunction`** est référencé aux deux endroits (construit une fois à l'échelle du
`RandomState`, pas par chunk) → `wrap()` le enveloppe **une seule fois** → sa valeur est calculée une
seule fois par contexte et partagée. **Vanilla fait donc déjà le dédoublonnage structurel qu'on
pourrait croire manquant**, à condition que le graphe soit construit avec des références partagées
(ce qui est le cas pour `NoiseRouterData`, construit une fois par dimension). La marge restante
identifiée par C2ME (`equality/Mixin*`) ne concerne que les cas où deux instances **distinctes**
mais structurellement identiques existeraient (voir nuance §3.1 sur les records).

### 4.3 La vraie marge trouvée, vérifiée dans le code actuel : l'allocation `MutableDouble` de `Aquifer`

**Découverte propre à cette recherche, pas reprise d'un rapport précédent.** Dans
`Aquifer.NoiseBasedAquifer.computeSubstance` (ligne 149), la branche qui calcule la « pression de
barrière » entre deux poches de fluide alloue, **à chaque bloc concerné** :

```java
MutableDouble barrierNoiseValue = new MutableDouble(Double.NaN);
Aquifer.FluidStatus closestStatus2 = this.getAquiferStatus(closestIndex2);
double barrier12 = similarity12 * this.calculatePressure(context, barrierNoiseValue, closestStatus1, closestStatus2);
```

`calculatePressure` (ligne 306) utilise `barrierNoiseValue` comme **cache à un coup** : la première
des jusqu'à trois invocations (`barrier12`/`barrier13`/`barrier23`) calcule
`this.barrierNoise.compute(context)` (un échantillon de bruit coûteux) et le stocke dans la boîte ;
les invocations suivantes, dans le **même** appel à `computeSubstance`, réutilisent la valeur au lieu
de rééchantillonner. **Le mécanisme de mémoïsation est correct et utile — c'est son support qui est
cher** : un objet `MutableDouble` (commons-lang3) alloué sur le tas pour chaque bloc qui tombe dans
cette branche (zones proches d'une frontière d'aquifère, donc une bonne partie du sous-sol). C2ME
corrige exactement ceci (`MixinAquiferSamplerImpl`, vérifié en §3.1) en remplaçant la boîte par un
champ d'instance primitif — **safe ici** parce qu'un `Aquifer`/`NoiseChunk` est construit et utilisé
par une seule tâche de génération de chunk à la fois (pas partagé entre threads), donc un champ
mutable non synchronisé ne casse rien.

**Verdict** : gain réel, ordre de grandeur = une allocation de courte durée par bloc concerné par une
frontière d'aquifère (potentiellement plusieurs milliers par chunk généré en sous-sol accidenté) ;
risque de déterminisme **nul** (même valeur numérique, juste stockée différemment) ; **personne dans
Lanterne ne l'a fait**, et c'est la cible la plus proche, en esprit, de la découverte n°1 du rapport
`recherche-serveur.md` (`ServerExplosion.getFluidState` redondant) : un fix chirurgical, prouvable
identique, sur une classe déjà lue et comprise.

### 4.4 Verdict global sur l'axe « cache de densité »

Vanilla **cache déjà ce qu'il faut à l'échelle algorithmique** (les cinq marqueurs couvrent tous les
patrons d'accès utiles, le DAG est dédoublonné par référence partagée). La marge restante n'est
**pas** un manque de mise en cache, mais (1) le coût d'implémentation du support de cache
(allocations, cf. §4.3) et (2) l'irréductible réévaluation par bloc de la densité finale (`Aquifer`
+ `Beardifier`), qui est un choix correct de Mojang (l'alternative — interpoler — casserait le
rendu des grottes et des structures).

---

## 5. `StructureCheck` — coût vérifié, `featureChecks`, ce qui est recalculé

Classe lue intégralement (`StructureCheck.java`, 224 lignes). Deux caches internes, tous deux en
mémoire JVM (perdus à chaque redémarrage de serveur) :

- **`loadedChunks`** (`Long2ObjectMap<Object2IntMap<Structure>>`) : une fois qu'un chunk a été
  vérifié (chargé depuis le disque ou généré), **toutes** les structures de ce chunk sont connues
  d'un coup (`storeFullResults`) → tout `checkStart` ultérieur pour n'importe quelle structure à
  cette position est un lookup **O(1)**, jamais recalculé.
- **`featureChecks`** (`Map<Structure, Long2BooleanMap>`) : pour les positions **candidates** qui
  n'ont pas encore de statut connu (`tryLoadFromStorage` renvoie `null`), le résultat de
  `canCreateStructure` (appel à `Structure.findValidGenerationPoint`, la vraie recherche de
  placement) est mis en cache par `(Structure, position)` — **jamais recalculé** pour la même paire.

**Le seul coût non éliminable, vérifié** : `tryLoadFromStorage` (ligne 117) fait, pour la première
structure vérifiée à une position pas encore en cache, un **scan disque partiel** via
`ChunkScanAccess.scanChunk` (utilise `CollectFields`/`FieldSelector` — un visiteur NBT qui **ne
matérialise que les champs `DataVersion` et `structures.starts`**, sans parser le reste du chunk :
déjà une optimisation Mojang). Ce scan est amorti sur toutes les structures suivantes à la même
position (grâce à `loadedChunks`), donc le coût réel est **un scan disque par position candidate
par session serveur**, pas par structure. Pour une recherche de structure à grand espacement (les
monuments, les forteresses), le nombre de positions candidates scannées peut être important en
territoire non exploré — **c'est un coût d'E/S en périphérie de carte, pas un coût CPU répété**,
comme déjà noté dans `recherche-serveur.md` §7.1 — confirmé ici avec le détail exact de
`CollectFields`/`FieldSelector`.

**Rien à corriger ici sans risque** : les deux caches existants couvrent déjà tout ce qui est
recalculable ; le seul « gras » serait de **persister** `loadedChunks`/`featureChecks` sur disque
entre deux démarrages du serveur — un vrai gain (élimine le re-scan après un redémarrage en zone
déjà explorée) mais un vrai risque aussi : il faudrait invalider ce cache si le monde est édité par
un outil externe entre deux démarrages, ou si un datapack de structures change — **piste à
considérer séparément, hors scope génération pure, pas recommandée sans garde-fou explicite**.

---

## 6. Chargement depuis le disque — `RegionFileStorage`, `SerializableChunkData`, compression

### 6.1 La découverte la plus rentable de cette recherche : LZ4 existe déjà nativement dans vanilla

**Vérifié intégralement dans `RegionFileVersion.java`** : quatre formats enregistrés —
`VERSION_GZIP` (id 1, legacy), **`VERSION_DEFLATE`** (id 2, `DEFAULT`, nom d'option `"deflate"`),
`VERSION_NONE` (id 3, `"none"`, pas de compression), **`VERSION_LZ4`** (id 4, nom d'option `"lz4"`,
utilise `net.jpountz.lz4.LZ4Block{Input,Output}Stream` — LZ4 est **déjà une dépendance du jeu**, pas
une bibliothèque à ajouter). Le format est choisi par la propriété serveur
`region-file-compression` (défaut `"deflate"`, lu dans `DedicatedServerProperties.java:110`,
appliqué au démarrage via `RegionFileVersion.configure(...)` dans `Main.java:117`, vérifié).

**Et surtout, la question du brief (« vanilla permet-il de la choisir par chunk tout en restant
capable de relire l'ancien format ? ») est vérifiée positive** : chaque chunk stocké dans un fichier
`.mca` porte son **propre octet de version** (`RegionFile.java`, lignes 169/236/244 :
`RegionFileVersion.fromId(versionId)` lu **par entrée**, pas par fichier). Changer
`region-file-compression=lz4` en cours de vie d'un monde ne casse donc **rien** : les chunks déjà
écrits en Deflate restent lisibles indéfiniment (leur octet de version dit "deflate"), et les
nouveaux chunks écrits/réécrits utilisent LZ4. **C'est un changement de configuration serveur, zéro
ligne de code, zéro risque de déterminisme** (la compression n'affecte que la représentation sur
disque, jamais le contenu généré) — LZ4 est nettement moins gourmand en CPU que Deflate au prix d'un
fichier légèrement plus gros, exactement le compromis qu'un serveur moddé cherchant à économiser le
CPU souhaite. **Recommandation directe, sans même passer par un mixin** : documenter/fixer ce
réglage dans la configuration serveur de Club Citrouille.

### 6.2 Coût de reconstruction (`SerializableChunkData.parse`), vérifié en partie

Lu en tête de fichier (677 lignes, structure de l'enregistrement vérifiée). Le chargement
reconstruit, à partir du NBT déjà décompressé/parsé : une liste de `SectionData` (palettes +
tableaux de bits par section, reconstruction directe depuis les tableaux `long[]` stockés — pas de
recalcul, juste une désérialisation de palette), une `Map<Heightmap.Types, long[]>` (copie directe),
des listes de `CompoundTag` pour entités/blocs-entités **conservées telles quelles** (pas
instanciées en objets Java tout de suite — ça n'arrive que plus tard, dans
`postLoadProtoChunk`/`registerAllBlockEntitiesAfterLevelLoad`, vérifié dans
`ChunkStatusTasks.full`). **Le goulot dominant n'est donc ni la décompression (déjà traitée en
§6.1) ni la reconstruction des tableaux primitifs (copie directe), mais la construction de l'arbre
NBT lui-même** (`CompoundTag`/`ListTag`, beaucoup de petits objets) — cohérent avec ce que
`c2me-rewrites-chunk-serializer` cible (un `NbtWriter` maison, plus rapide que `NbtIo` standard, côté
écriture). Je n'ai pas mesuré le partage exact décompression/parsing NBT/reconstruction faute de
profil réel — **à confirmer par un banc à charge reconstituée** (déjà prévu dans la feuille de route
du projet, item 10).

---

## 7. Lumière — `ThreadedLevelLightEngine`, `LightEngine.runLightUpdates`, allocation vérifiée

### 7.1 Le vrai goulot structurel : sérialisation totale, pas juste « pas de parallélisme »

Déjà établi en §2.2 : un seul `ConsecutiveExecutor` par dimension nommé `"light"`, qui ne traite
qu'**une tâche à la fois** (`ThreadedLevelLightEngine.runUpdate()`, lots de 1000 tâches). C'est un
choix de conception : le graphe de propagation de lumière (`DynamicGraphMinFixedPoint`, lu
intégralement) n'est pas trivialement parallélisable nœud par nœud (les mises à jour voisines
s'enchaînent), donc vanilla préfère un seul thread cohérent plutôt qu'un verrouillage fin coûteux.

### 7.2 La source d'allocation trouvée et vérifiée dans le code — correspond au chiffre du profil (13 %)

`ThreadedLevelLightEngine` enveloppe **chaque** appel sensible à la lumière (`checkBlock`,
`queueSectionData`, `setLightEnabled`, `retainData`, `updateSectionStatus`,
`propagateLightSources`) dans `Util.name(Runnable, Supplier<String>)` (vérifié, motif répété à
chaque méthode, ex. ligne 61 : `Util.name(() -> super.checkBlock(immutable), () -> "checkBlock " +
immutable)`). Corps de `Util.name` (vérifié, `Util.java:669`) :

```java
public static Runnable name(Runnable task, Supplier<String> nameGetter) {
    if (SharedConstants.DEBUG_NAMED_RUNNABLES) {
        String name = nameGetter.get();
        return new Runnable() { ... }; // wrapper avec toString() = name
    } else {
        return task; // production : pas de wrapper
    }
}
```

`SharedConstants.DEBUG_NAMED_RUNNABLES` est **`false` en production** (`debugFlag("NAMED_RUNNABLES")`,
vérifié). Mais **le second argument (`() -> "checkBlock " + immutable`) est une lambda capturante,
évaluée par Java comme argument avant même l'entrée dans `Util.name`** — donc **allouée
systématiquement**, même quand elle ne sera jamais appelée (`nameGetter.get()` n'est jamais invoqué
côté `false`). `checkBlock` lui-même est appelé depuis `LevelChunk.setBlockState` (vérifié,
`LevelChunk.java:295-301`) **à chaque changement de bloc dont les propriétés de lumière diffèrent**
(`LightEngine.hasDifferentLightProperties`) — c'est-à-dire à peu près chaque pose/casse de torche,
porte, culture, feuille, etc., en jeu normal (pas seulement à la génération). Chaque appel alloue
donc, en pure perte en production : une lambda de nom de debug jamais invoquée, plus (dans
`addTask`) un `Pair.of(type, runnable)` ajouté à un `ObjectArrayList`, plus (dans
`ChunkTaskDispatcher.submit`) un `StrictQueue.RunnableWithPriority`. **C'est une explication
plausible et vérifiée en source du chiffre de 13 % d'allocations résiduelles** signalé par le profil
du projet : le chemin est emprunté en continu pendant le jeu normal, pas seulement pendant la
génération de chunks.

**Technique proposée** : sur les points d'entrée à haute fréquence de `ThreadedLevelLightEngine`
(en premier lieu `checkBlock`, le plus fréquent), remplacer l'appel à `Util.name(runnable, () ->
"...")` par un appel direct à `addTask(..., runnable)` quand `SharedConstants.DEBUG_NAMED_RUNNABLES`
est faux — ou, plus simplement, ne construire la lambda de nom que dans une branche `if
(SharedConstants.DEBUG_VERBOSE_SERVER_EVENTS)` explicite au lieu de la construire inconditionnellement
à chaque appel. **Risque nul sur le déterminisme** (aucune valeur de lumière calculée n'est
affectée, seule la façon d'empaqueter la tâche change) ; risque **non nul sur la maintenabilité** si
un mod tiers dépend du `toString()` de ces `Runnable` à des fins de debug (peu probable, mais pas
vérifié pour l'écosystème NeoForge du pack).

### 7.3 Ce que fait le remplacement complet (Starlight/Moonrise) — lu sur le successeur direct maintenu

`Steveplays28`/`Spottedleaf`'s **Starlight** est archivé, mais son successeur direct **maintenu à
notre version** (`RelativityMC/ScalableLux`, branche `ver/26.1.0`, `TECHNICAL_DETAILS.md` lu
intégralement) documente l'algorithme : vanilla **« tire »** (pull) — chaque position en file
recalcule sa valeur de lumière à partir de ses 6 voisins (lit les 6 états de bloc, à chaque
position, à chaque étape) ; Starlight **« pousse »** (push) — la valeur se propage vers les voisins,
qui ne sont mis à jour que si leur niveau potentiel augmente, avec sortie anticipée si un voisin est
déjà assez lumineux. Chiffres du document (mesures internes du projet, non reproduites ici) : environ
7× moins de lectures de niveau de lumière, 6× moins de lectures d'état de bloc, jusqu'à ~28× plus
rapide sur un scénario contrôlé (retrait de glowstone). **Le document affirme explicitement** :
« The end result of lighting with Starlight and Vanilla will be the same » — donc pas un changement
de règle du jeu, une restructuration de parcours à résultat identique revendiqué. **Mais** (vérifié,
même document) : **le format de stockage de la lumière sur disque change**, nécessitant un
« relight » (repropagation complète) lors de la conversion d'un monde existant — donc **pas un
remplacement neutre à chaud**, et incompatible avec tout mod qui accroche directement le moteur de
lumière vanilla (le document le dit explicitement : « breaks mods hooking the light engine
directly »). **Portage dans Lanterne** : hors de portée d'un mixin chirurgical (c'est un
remplacement d'algorithme entier, pas une correction ciblée) ; **la piste raisonnable à court terme
reste §7.2** (éliminer l'allocation de lambda de debug), qui est un sous-ensemble strictement plus
sûr et plus petit du même problème.

---

## Classement — gain estimé / risque / déterminisme

| # | Cible | Classe.méthode vérifiée | Gain estimé | Risque | Déterminisme préservé ? |
|---|---|---|---|---|---|
| 1 | Région disque : `region-file-compression=lz4` | `RegionFileVersion` (config serveur, zéro code) | **Très élevé** pour le CPU de sauvegarde/chargement, zéro risque | **Quasi nul** — format par-chunk, rétro-compatible en lecture | **Oui, absolu** — ne touche jamais le contenu généré |
| 2 | `Aquifer.NoiseBasedAquifer.computeSubstance` : éliminer `new MutableDouble(Double.NaN)` | `Aquifer.java:248` (vérifié, encore présent en 26.1.2) | Moyen-élevé en sous-sol/zones d'aquifère (allocation par bloc concerné) | **Quasi nul** — même valeur, juste stockée en champ au lieu d'une boîte | **Oui, absolu** |
| 3 | `ThreadedLevelLightEngine.checkBlock` (et consorts) : éviter la lambda `Util.name(...)` jamais invoquée en prod | `ThreadedLevelLightEngine.java` (vérifié), `Util.name` (vérifié) | Moyen — coûte à chaque pose/casse de bloc affectant la lumière, pas seulement à la génération | Faible — aucune valeur de lumière affectée | **Oui, absolu** |
| 4 | Retouche fine du `ChunkTaskDispatcher`/priorités façon `c2me-opts-scheduling` (mi-tick, auto-save asynchrone) | `ChunkTaskDispatcher.java`, `ChunkHolder` (vérifiés en structure) | Moyen — meilleure répartition du pool partagé sans le remplacer | Modéré — touche l'ordonnancement, à valider contre les cas de dépendances entre statuts | **Oui, si limité à l'ordonnancement** — ne change aucune valeur générée |
| 5 | Fonction de densité de l'End (`end_island`), ciblée indépendamment par C2ME et Moonrise | `DensityFunctions` (End, non lu en détail ici — piste identifiée, pas vérifiée en profondeur) | Élevé **si** la dimension End est utilisée intensivement, sinon nul (biome-dépendant) | Faible si mise en cache pure, à vérifier au cas par cas | **Oui, si mise en cache pure sans changer la formule** |
| 6 | Remplacement du moteur de lumière façon Starlight/Moonrise (push vs pull) | `LightEngine`/`ThreadedLevelLightEngine` (remplacement complet) | Très élevé (7-28× sur la propagation mesurée par le projet) | **Élevé** — change le format disque (relight requis à la conversion), casse les mods qui accrochent le moteur de lumière vanilla | **Revendiqué identique par le projet, non vérifié indépendamment ici** |

**Non retenu / hors de portée d'un mixin chirurgical** : remplacement complet du pipeline
`ChunkStatus`/`ChunkPyramid`/`ChunkMap` façon `c2me-rewrites-chunk-system` ou Moonrise
`chunk_system` — les deux projets de référence ont eux-mêmes renoncé à patcher le pipeline vanilla à
la marge pour ce niveau de gain ; le risque de compatibilité avec le reste du pack (les deux mods
sont mutuellement incompatibles, et incompatibles avec C2ME pour Moonrise) est disproportionné par
rapport à ce qu'un mod comme Lanterne peut absorber sans devenir lui-même un remplacement
d'infrastructure.

---

## Corrections à l'hypothèse implicite du brief

- **Noisium est probablement en partie obsolète en 26.1.2** : son README revendique des
  optimisations (pose de bloc directe en palette, cache de constantes de cellule) qui semblent déjà
  faites nativement dans le `.mcsrc` actuel (`section.setBlockState(..., false)`, `cellWidth()`/
  `cellHeight()` mis en cache comme champs finaux du `NoiseChunk`) — **non confirmé avec certitude**
  (fichier mixin exact introuvable, dépôt archivé), à vérifier avant tout portage.
- **`recherche-serveur.md` (rapport précédent) avait raison de ne pas trancher sur l'axe génération**
  faute d'avoir lu le code source des trois mods — cette recherche comble ce manque et confirme que
  la bonne cible n'est **pas** un remplacement de pipeline, mais des corrections ponctuelles
  (allocation `Aquifer`, allocation `ThreadedLevelLightEngine`, configuration LZ4) du même calibre
  que la découverte `ServerExplosion.getFluidState` de ce rapport précédent.
- **Le déterminisme est une préoccupation réelle et documentée par C2ME lui-même**, pas une prudence
  excessive du brief : le module `c2me-fixes-worldgen-threading-issues` existe précisément parce que
  le code vanilla de génération de structures (déjà exécuté sur un pool multi-thread **par défaut**,
  sans aucun mod) contient des suppositions mono-thread qui deviennent des data races sous
  concurrence. Toute modification qui **augmenterait** le degré de concurrence sur ce chemin (par
  exemple, un pool dédié plus grand pour `STRUCTURE_STARTS`) hériterait de ce risque et devrait être
  accompagnée des mêmes garde-fous (champs `volatile`, détection de `Random` partagé) que C2ME a dû
  construire lui-même.
