# Le moteur de rendu du terrain de Lanterne — design (étape 0)

Objectif du patron : un **Iris sans Sodium**, qui s'appuie sur notre propre système de rendu, avec des
performances au moins égales à celles de Sodium, sous **OpenGL et Vulkan**, et une interface d'options
propre. Ce document est le design et le plan ; le squelette de code est dans
`src/main/java/fr/clubcitrouille/lanterne/render/terrain/` (interfaces et types, rien de branché au jeu).

## 0. Règle juridique (absolue)

Sodium est publié sous **PolyForm Shield 1.0.0** (`LICENSE.md` dans son jar ; confirmé par
`neoforge.mods.toml` : `license = "Polyform-Shield-1.0.0"`). Cette licence interdit d'utiliser son code pour
un produit concurrent. Le patron n'est pas le titulaire des droits. Donc :

- **Aucune ligne de Sodium n'est copiée, translittérée ni décompilée pour être recopiée.** Cette règle
  est plus importante que la vitesse.
- On travaille « salle blanche » : on observe le **comportement** (journaux, options, profil) et on
  applique des **techniques publiques** (sources en fin de document).
- Iris est sous LGPL-3.0 (voir `IRIS-ATTRIBUTION.md`, `LICENSE-IRIS`) : on peut le modifier librement.
- Le code de Minecraft lui-même est lu (jar de sources décompilées) pour connaître son API ; ce n'est
  pas du code Sodium.

## 1. La découverte qui change le plan : le moteur existe déjà dans vanilla 26.3

Je m'attendais à devoir écrire arènes de tampons, dessin indirect, graphe d'occlusion et abstraction GL/Vulkan.
La lecture du vrai jar client (`minecraft-client-patched-26.3.0.7-beta.jar`) montre que **Mojang les a
déjà intégrés**. Preuves (classes vérifiées dans le jar, pas seulement dans les sources) :

| Technique | Où dans vanilla 26.3 | Preuve |
|---|---|---|
| Tampons partagés en arène (allocation type TLSF) | `com.mojang.blaze3d.vertex.UberGpuBuffer`, `TlsfAllocator` ; `SectionRenderDispatcher` en crée 2 par couche : sommets 128 Mo (alignement = taille de sommet), index 32 Mo | `unzip -l` du jar ; `SectionRenderDispatcher.java:77-78` |
| Envoi asynchrone vers le GPU | `StagingBuffer`, `SectionRenderDispatcher.uploadTerrainBuffersToGpu()` | `SectionRenderDispatcher.java:145-151` |
| Dessin indirect multi-lots du terrain | `LevelRenderer.prepareChunkRendersIndirect(Matrix4fc, boolean)` + `isChunkRenderingUsingMultiDrawIndirect()` ; choisi si `deviceLimits.maxDrawIndirectDrawCount() > 0` | `javap` sur le jar ; `LevelRenderer.java:180,270-275` |
| Pipeline de terrain multi-dessin | `RenderPipelines.MULTIDRAW_TERRAIN_SNIPPET` avec un 2e binding `CHUNK_DATA_INSTANCED` (position et visibilité de section) | `RenderPipelines.java:86-92`, `DefaultVertexFormat.java:36` |
| Graphe d'occlusion de sections | `SectionOcclusionGraph`, `VisGraph`, `VisibilitySet`, `RotatingSectionStorage` | `LevelRenderer.java:135` |
| Maillage sur threads | `SectionRenderDispatcher.RenderSection.CompileTask`, `TracingExecutor` | déjà noté dans `notes/rendu-terrain-vs-sodium.md` |
| Culling de faces entre blocs | `Block.shouldRenderFace` | idem |
| Transparence sans tri | `LevelRenderer.executeOit` | `LevelRenderer.java:583` |
| Abstraction GPU commune GL et Vulkan | `com.mojang.renderpearl.api` (`RenderPass` interface, `DeviceFeatures`, `DeviceLimits`) ; backends `renderpearl.backend.opengl` (30 classes) et `.vulkan` (24) | `unzip -l` : 269 classes `renderpearl` |
| Capacités du périphérique | `DeviceFeatures(shaderDrawParameters, multiDrawDirectInterleaved, multiDrawDirectSeparate, multiDrawIndirect, drawIndirect, nonZeroFirstInstance, persistentMapping)` | `DeviceFeatures.java` |

Vérifié en prod : le module `client/terrain/Guet` de Lanterne (commit `98ec8b1`) a déjà établi par bytecode que
`prepareChunkRendersIndirect` est **actif** et appelle `drawIndexedIndirect` sur le backend Vulkan.

**Attention, piège documenté :** le jar de sources décompilées (`build/moddev/artifacts/*-sources.jar`) utilise
les anciens noms `com.mojang.blaze3d.systems.*` pour l'API GPU, alors que le vrai jar les a renommés en
`com.mojang.renderpearl.api.*` (ex. `com.mojang.renderpearl.api.commands.RenderPass`). Les classes
`blaze3d.vertex.UberGpuBuffer` et `TlsfAllocator` existent bien sous ce nom dans le vrai jar. Toute
lecture d'API doit être vérifiée par `javap` sur le vrai jar (ce que le code Lanterne fait déjà : voir
`client/terrain/TerrainVertexFormat`, qui importe `com.mojang.renderpearl.api.*`).

**Conséquence.** Ce qui donne à Sodium son avance historique (arènes, dessin indirect, culling par graphe,
maillage asynchrone) est **déjà dans le moteur**. Sodium reste utile dans notre pile pour une seule raison :
**Iris s'y accroche** pour injecter ses attributs de sommet et pour dessiner les passes d'ombre. Retirer Sodium
n'est donc pas « réécrire Sodium », c'est **écrire le pont Iris ↔ terrain vanilla**, plus les optimisations
propres aux shaders (ombres).

## 2. Inventaire de ce que Lanterne possède déjà

- **Format de sommet étendu du terrain** : `client/terrain/TerrainVertexFormat` réutilise `UV1`/`UV3` du
  binding 0 (pas de binding supplémentaire) ; `mixin/RenderPipelinesMixin` remplace le binding 0 des
  pipelines de terrain par `TerrainVertexFormat.EXTENDED` et ajoute le define de shader. Précédent solide :
  l'extension d'attributs par nom fonctionne (résolution des `location` par nom dans le SPIR-V).
- **Greedy meshing** : `client/terrain/Greedy` (faces UP/DOWN de la couche SOLID), désactivé ou limité par défaut
  selon les mesures (voir `notes/rendu-terrain-vs-sodium.md`, sections « Décision honnête »).
- **Guet** : mesure, sans toucher, la stabilité du regroupement de sections d'une image à l'autre ;
  `Radiographie` l'affiche. Base pour l'outillage de mesure.
- **Vulkan natif** : 9 mixins (`Vulkan*Mixin`, `RenderPipelinesMixin`, `DynamicGpuDataMixin`…), cache de
  pipelines `VkPipelineCache` via `client/Empreinte` (option `ClientConfig.EMPREINTE`, désactivée par défaut),
  diagnostics de présentation et de soumission.
- **Vulkan pour shaders** : `lab/ShadercProbe`, `lab/UniformReblocker` (prototype isolé, jamais branché) ;
  blocage documenté dans `RECHERCHE-VULKAN-SANS-SODIUM.md` (bug natif shaderc sur un fragment de 99 760 caractères).
  Iris a aussi un chemin `IrisVKOnly`.
- **Interface** : `client/Dials` (1 699 lignes, écran « 🎃 Lanterne — rendu », cadran Shaderpack ajouté récemment),
  `client/Doorway` (porte d'entrée), `client/sodium/Graft` (greffe dans l'écran d'options de Sodium),
  `mixin/VideoOptionsMixin` (accroche à l'écran vidéo vanilla).
- **Iris côté Sodium** : 192 références à `net.caffeinemc` dans `net.irisshaders` ; depuis `bff38e7`, sans Sodium tous
  les mixins Iris sont coupés (Iris inerte, pas de shaders). Le point de couplage majeur du terrain est
  `shadows/ShadowRenderer.java` lignes 476-541 : `SodiumWorldRenderer.setupTerrain`, `SodiumChunkSection`
  (qui fabrique un `ChunkSectionsToRender` à la manière de Sodium), `prepareChunkRendering`.

## 3. Techniques : coût, gain, risque

Les gains « attendus » sont ceux décrits dans les sources publiques ; **chaque ligne sera prouvée par une mesure
avant/après sur notre banc avant d'être conservée**. Aucun gain n'est promis ici.

| # | Technique | Déjà dans vanilla ? | Coût pour nous | Gain attendu | Risque |
|---|---|---|---|---|---|
| T1 | Arène de tampons partagés + envoi par transfert | Oui (`UberGpuBuffer`) | Nul | Déjà acquis | Nul |
| T2 | Dessin indirect multi-lots | Oui | Nul pour caméra ; à écrire pour ombres | Moins d'appels CPU | Moyen (ombres) |
| T3 | Graphe d'occlusion de caméra | Oui | Nul | Déjà acquis | Nul |
| T4 | **Culling des ombres par volume de lumière** (ne pas réutiliser la liste de la caméra) | Non | Moyen : `ShadowDrawListBuilder` | **Élevé** : la passe d'ombre re-dessine le terrain | Moyen : une ombre manquante se voit |
| T5 | **Cache de carte d'ombres** (ne redessiner que si soleil, caméra ou géométrie proche ont changé ; couches lointaines moins souvent) | Non | Faible : `ShadowCachePolicy` | **Élevé** (technique publique, voir sources) | Faible si critère conservateur |
| T6 | Réutiliser le maillage entre passe d'ombre et passe principale | Oui de fait (mêmes tampons) | Nul | Moitié du travail d'ombre déjà acquise | Nul |
| T7 | Format de sommet compact avec attributs Iris décodés en shader | Partiel (`TerrainVertexFormat`) | Moyen | Bande passante mémoire | Moyen (compat shaderpacks) |
| T8 | Culling GPU par compute (frustum + Hi-Z) | Non | Élevé | Élevé sur très grandes distances | Élevé (sync, Vulkan/GL, coût de profondeur pyramidale) — **hors étapes A–C** |
| T9 | Ombres à distance adaptative selon budget d'image (« Dials ») | Non | Faible | Élevé si le GPU est limite | Faible |
| T10 | Mesh shaders (NVIDIA uniquement) | Non | Très élevé | Très élevé sur NVIDIA seulement | Très élevé — **hors périmètre** |
| T11 | Greedy meshing étendu | Non | Existant, partiel | Réduit les sommets | Élevé (artefacts UV, cf. notes) — reste désactivé tant que non prouvé |

Décision : **A–C reposent sur T1–T6, T7 (déjà là), T9**. T8, T10, T11 sont des pistes ultérieures sans engagement.

## 4. Architecture en couches

```
        Iris (pipelines, shaderpacks)
                 │  une seule interface
   ┌─────────────▼──────────────┐
   │ (e) IrisTerrainBridge       │  remplace tous les usages de net.caffeinemc.*
   └───┬──────────────────┬──────┘
       │ caméra           │ ombre
┌──────▼──────┐   ┌───────▼───────────┐   ┌───────────────────┐
│ vanilla:    │   │ (c) DrawListBuilder│   │ ShadowCachePolicy │
│ visible-    │   │  + LightFrustum    │   │  (T5)             │
│ Sections    │   │  (T4)              │   └───────────────────┘
└──────┬──────┘   └───────┬────────────┘
       └──────────┬───────┘
        (b) tampons partagés vanilla : UberGpuBuffer / TlsfAllocator  (T1)
                  │
        (a) mailleur vanilla SectionCompiler + attributs Iris via TerrainVertexFormat (T7)
                  │
        (d) com.mojang.renderpearl.api.RenderPass  →  backend OpenGL | backend Vulkan
```

- **(a) Mailleur** : `SectionCompiler.compile` appelle, par bloc, `region.getBlockState(pos)` puis émet chaque
  quad par `BufferBuilder.putBlockBakedQuad(x, y, z, quad, instance)`. Points d'accroche déjà éprouvés (voir
  `notes/rendu-terrain-vs-sodium.md`, lambdas `compile$0/1`). L'identifiant de bloc (`mc_Entity`) est l'état du bloc au
  moment de l'émission ; les autres attributs se dérivent du quad (`IrisVertexAttributes`).
- **(b) Arène** : ne rien réécrire. On lit les plages par `SectionRenderDispatcher.getRenderSectionSlice(mesh, layer)`,
  comme `LevelRenderer.extractSectionDrawGroups` (lignes 766-834 de `LevelRenderer`).
- **(c) Liste de dessin** : caméra = résultat de vanilla ; ombre = `ShadowDrawListBuilder` (à écrire, étape B) qui
  parcourt les sections chargées, retient celles que le `LightFrustum` touche, et produit un `ChunkSectionsToRender`
  avec les mêmes tampons — c'est ce que `SodiumChunkSection` fait aujourd'hui pour Sodium.
- **(d) Backends** : **aucune interface à inventer**. `RenderPass` de `renderpearl.api` est déjà l'interface mince ;
  `TerrainCaps` lit `DeviceFeatures` pour choisir (indirect ou non).
- **(e) Pont Iris** : `IrisTerrainBridge`. Iris passe de « je dessine avec Sodium » à « je demande la passe X ».

## 5. Le point dur : les passes d'ombre sans Sodium

`ShadowRenderer.renderShadows` (Iris) fait, avec Sodium : changer les matrices du rendu, `setupTerrain` avec le
frustum d'ombre, fabriquer un `ChunkSectionsToRender`, puis `renderGroup(OPAQUE)` avec les programmes d'ombre.
Sans Sodium :

1. Remplacer `setupTerrain` par `ShadowDrawListBuilder.build(pass, lightFrustum)` (T4).
2. Fabriquer le `ChunkSectionsToRender` de vanilla (record public : `textureView`, `drawGroupsPerLayer`, `maxIndicesRequired`,
   `chunkSectionInfos`) à partir de cette liste, comme `prepareChunkRenders` le fait pour la caméra.
3. Le dessiner avec les pipelines d'ombre d'Iris (les pipelines d'Iris remplacent déjà ceux de vanilla via ses mixins
   `MixinRenderPipeline`, `MixinGlRenderPass`, etc.).
4. Filtrer par `ShadowCachePolicy` (T5) avant tout.

Contrainte connue : la passe d'ombre utilise le **même format de sommet étendu** que la passe principale, donc
une seule variante de maillage à produire.

## 6. Interface (livrable de l'étape A)

Aujourd'hui Iris et Lanterne sont greffés dans l'écran d'options de Sodium. Sans Sodium, il faut un écran
d'options vidéo propre. On s'appuie sur l'existant : `client/Dials` (écran « Lanterne — rendu », déjà les cadrans
shaderpack / activation des shaders), `client/Doorway` (entrée) et `mixin/VideoOptionsMixin` (accroche vanilla).

Contenu cible d'un écran « Vidéo Lanterne » à onglets :

1. **Qualité** : distance de rendu, distance de simulation, nuages, particules, feuilles, éclairage lisse.
2. **Shaders** : liste des shaderpacks, activation, distance d'ombre, qualité (cadrans existants).
3. **Performances** : plafond de FPS, VSync, cache d'ombres, ombres adaptatives, histogramme FPS.
4. **Moteur** : backend **OpenGL / Vulkan** (avec message clair si Vulkan est indisponible ou si les shaders
   ne le supportent pas encore), affichage des capacités (`TerrainCaps`).

Règle de qualité : chaque cadran affiche sa valeur courante et l'effet sur les FPS mesurés quand c'est possible ;
aucune option n'est dupliquée avec vanilla sans raison. Suppression de la greffe `Graft` seulement quand l'écran
propre est prouvé en jeu (sinon deux interfaces cohabitent tant que Sodium est présent).

## 7. Plan par étapes (chacune livrable et testée avec un vrai client)

**Étape A — le client sans Sodium rend le terrain vanilla avec notre moteur ; interface propre.**
- Iris n'est plus « inerte » : remplacer les imports Sodium hors chemin d'ombre (`VanillaRenderingPipeline`,
  frustums, `HandRenderer`, `ExtendedShader`, `pbr/*`, debug) par des équivalents sans dépendance (~33 fichiers,
  liste dans `RECHERCHE-VULKAN-SANS-SODIUM.md`, « Question 2 »).
- Écran d'options vidéo Lanterne (section 6).
- **Test** : client jetable **sans** Sodium, shaders coupés → menu, monde, terrain identique à vanilla ; puis
  **avec** Sodium (non-régression). Captures lues.

**Étape B — Iris + ComplementaryReimagined sans artefacts, sans Sodium.**
- `ShadowDrawListBuilder`, pont d'ombre, attributs Iris complets.
- **Test** : captures avant/après ; comparaison avec le rendu Sodium+Iris (mêmes coordonnées, mêmes shaders).

**Étape C — performances mesurées ≥ Sodium.**
- T4 + T5 + T9 ; banc reproductible (position fixe, FPS moyen et 1 % bas, `Radiographie`/`Guet` étendus avec
  `TerrainMetrics`) ; tableau avant/après dans `PERF-SHADERS-AUDIT.md`.
- **Critère** : FPS moyens et 1 % bas ≥ ceux de Sodium+Iris sur le même banc ; sinon les optimisations non prouvées
  sont retirées.

**Étape D — Vulkan.**
- Le terrain vanilla passe déjà par le backend Vulkan. Le blocage est côté **shaders Iris** (GLSL → SPIR-V) :
  bug natif shaderc, voir `RECHERCHE-VULKAN-SANS-SODIUM.md`. Piste : contournement par découpe/réduction du fragment
  ou autre compilateur ; test sur un vrai shaderpack en Vulkan, message d'erreur lisible dans l'écran d'options.

## 8. Risques et incertitudes (honnêtes)

- **Non vérifié visuellement** : ce document est fondé sur la lecture de sources et de bytecode ; aucun rendu n'a
  été lancé pour cette étape. Chaque étape ci-dessus impose un vrai lancement.
- **Noms d'API** : les sources décompilées disponibles sont périmées pour `blaze3d`/`renderpearl` ; se fier au jar.
- **Shaderpacks** : le format de sommet étendu doit rester compatible avec ce que les shaderpacks lisent
  (`mc_Entity`, `at_midBlock`…) ; ComplementaryReimagined est le banc, mais d'autres packs testeront d'autres cas.
- **Distant Horizons** : coupé sous shaders (correctif 26.3.108) ; son intégration est un chantier distinct.
- **Mises à jour de NeoForge/Minecraft** : l'API `renderpearl` est jeune ; l'isolement dans `render.terrain` limite les
  dégâts d'un changement.
- **T8 / T10** : non engagés.

## 9. Sources publiques (techniques génériques, aucun code de Sodium)

- Vulkan Guide — moteurs pilotés par le GPU : <https://vkguide.dev/docs/gpudriven/gpu_driven_engines/>
- Vulkan Guide — culling par compute : <https://vkguide.dev/docs/gpudriven/compute_culling/>
- Vulkan Guide — rendu haute performance de voxels et maillages : <https://vkguide.dev/docs/ascendant/ascendant_geometry/>
- Aokana, rendu de voxels piloté par le GPU (ACM) : <https://dl.acm.org/doi/10.1145/3728299>
- Khronos, `ARB_multi_draw_indirect` : <https://registry.khronos.org/OpenGL/extensions/ARB/ARB_multi_draw_indirect.txt>
- Khronos, `glMultiDrawElementsIndirect` : <https://registry.khronos.org/OpenGL-Refpages/gl4/html/glMultiDrawElementsIndirect.xhtml>
- Khronos OpenGL Wiki, rendu de sommets : <https://wikis.khronos.org/opengl/Vertex_Rendering>
- Tutoriel multi-draw indirect : <https://ktstephano.github.io/rendering/opengl/mdi>
- Cartes d'ombres virtuelles / mise en cache statique : <https://ktstephano.github.io/rendering/stratusgfx/svsm> et
  <https://dev.epicgames.com/documentation/unreal-engine/virtual-shadow-maps-in-unreal-engine>
- Mise à jour étalée des cartes d'ombres : <https://gamedev.net/forums/topic/631951-shadow-maps-update-optimization/>
- Nvidium (description publique du principe, pas de code) : <https://modrinth.com/mod/nvidium>

Cette étape n'a pas trouvé de descriptions publiques détaillées de Voxy ; aucune affirmation ne repose dessus.
