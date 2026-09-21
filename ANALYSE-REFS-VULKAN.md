# Analyse de deux dépôts Vulkan de référence (21/09/2026)

Demande du patron : analyser `yiyuyan/VulkanModNeoForge` et `Player9753193/Vulkan-PostFX-Legacy` pour comprendre et voir ce qu'on peut en tirer pour Lanterne (Vulkan + shaders type Iris).
Méthode : clones `--depth 1` dans `C:\refs\` (lecture seule, rien copié dans Lanterne), lecture du code, `javap` sur le vrai jar client `minecraft-client-patched-26.3.0.7-beta`, API GitHub pour la maturité. **Aucun des deux projets n'a été compilé ni lancé** (voir « Non vérifié »).

## 1. Résumé et licences

| | VulkanModNeoForge | Vulkan-PostFX-Legacy |
|---|---|---|
| **Ce que c'est** | Fork non officiel (NeoForge) de **VulkanMod** (xCollateral) : un moteur de rendu **Vulkan complet qui remplace** le rendu OpenGL de Minecraft | Chargeur de « shader packs » externes + framework de post-traitement pour le chemin Vulkan **natif de Mojang** ; aucun moteur Vulkan propre |
| **Cible** | Minecraft **1.21.4**, NeoForge **21.4.157** (`gradle.properties`) — **pas 26.3** | Minecraft **26.2** (snapshots), **Fabric** (loader 0.19.3, `fabric_api 0.153.0+26.2`) |
| **Licence** | **LGPL-3.0** (`LICENSE` + `COPYING` GPL, `neoforge.mods.toml : license = "LGPL-3.0"`, API GitHub : LGPL-3.0). `NOTICE` : fork de xCollateral/VulkanMod, crédit à xCollateral, marque « VulkanMod » potentiellement soumise à d'autres droits | **« Vulkan PostFX License v1.2 — All Rights Reserved »** (IonHex975). API GitHub : NOASSERTION |
| **Ce qui est permis** | Réutilisation du code **avec crédit** et en respectant la LGPL-3.0 (remplacement de la bibliothèque possible, mention de la licence, sources des modifications) | **Étude/analyse/rétro-ingénierie pour usage personnel uniquement** (§7) ; **interdit** : redistribuer le mod, des forks, des ports, des œuvres dérivées, du code modifié (§4, §7) ; usage commercial interdit. → **IDÉES SEULEMENT, aucun code** |
| **Maturité** | 1 seul commit visible (« Updating 1.21.4 », 02/07/2026), 13 étoiles, 0 issue ; releases de juin 2026 (`1.21neo-0.4.8-dev+5`) ; README : « télécharger depuis Actions, PAS les releases » ; version `0.5.5-dev+6-unstable`. Le projet **amont** VulkanMod : 2 373 étoiles, LGPL-3.0, activité août 2026 | **Archivé**, 5 étoiles, 1 issue ouverte, dernier commit 10/07/2026 ; README : « EXPERIMENTAL — ALPHA », 223 fichiers Java / 33 305 lignes |

Compatibilité de licence pour nous : Lanterne contient déjà Iris (LGPL-3.0, `IRIS-ATTRIBUTION.md`, `LICENSE-IRIS`) ; reprendre du code LGPL-3.0 de VulkanMod, avec attribution, est donc cohérent. Le code de PostFX ne doit servir qu'à comprendre.

## 2. VulkanModNeoForge (= VulkanMod amont, portage NeoForge)

**Ce que le projet FAIT réellement** (vérifié dans le code, 289 fichiers Java) : il **remplace** la couche OpenGL de Minecraft 1.21 par un moteur Vulkan 1.2 écrit à la main (`net.vulkanmod.vulkan.*`, 1 515 lignes rien que pour `Renderer`+`Vulkan`+`Drawer`) et refait le rendu du terrain (`render/chunk/*`, maillage `build/*`, lumière lisse `SmoothLightPipeline`). C'est un **moteur parallèle**, pas un pont : il ne s'appuie pas sur une API de rendu fournie par le jeu (qui n'existait pas en 1.21).

**Architecture** (fichiers et constats) :
- **Mémoire** : VMA (`vulkan/memory/MemoryManager.java`), tampons `StorageBuffer`, `UploadManager` avec **sémaphore timeline** (`render/chunk/buffer/UploadManager.java:33`).
- **Rendu** : **dynamic rendering** `VK_KHR_dynamic_rendering` (`vulkan/device/DeviceManager.java:249`, `Framebuffer.java:130` `beginDynamicRendering`), pas de render pass classique. API cible **Vulkan 1.2** (`DeviceManager` : `VK_API_VERSION_1_2`).
- **Terrain** : dessin **indirect** (`render/chunk/buffer/DrawBuffers.java:280` `vkCmdDrawIndexedIndirect`), **push constants** (`DrawBuffers.java:156`).
- **Culling piloté par le GPU** : `render/chunk/cull/GpuCuller.java` (364 lignes) + `HiZPyramid.java` (132 lignes) + compute `shaders/compute/cull.comp` (127 lignes) et `hiz_downsample.comp` (24 lignes). Un thread par section : test des 6 plans du frustum (p-vertex), puis **test d'occlusion Hi-Z** contre la **pyramide de profondeur de l'image précédente** (mip choisi pour que le rectangle écran couvre ≈ 1 texel, 4 échantillons `textureLod`, biais de profondeur), puis `atomicAdd` d'une commande `VkDrawIndexedIndirectCommand` par (zone, type de rendu) ; dessin par `vkCmdDrawIndexedIndirectCount`. Sections proches de la caméra (±48 blocs) jamais éliminées. Translucides et tripwire non traités. Activé seulement si `supportsDrawIndirectCount`.
- **Cache de pipelines** : `Pipeline.java:46` `PIPELINE_CACHE = createPipelineCache()` (à comparer à notre `Empreinte`/`VkPipelineCache` persisté par `pipelineCacheUUID`).
- **Compat GL** : `net.vulkanmod.gl.*` (GlBuffer, GlFramebuffer, GlProgram, GlShader, GlTexture…) émule quelques appels GL ; `mixin/compatibility/gl`.
- **Aucune mention d'Iris, Sodium, Embeddium ou OptiFine** dans le code hors `SmoothLightPipeline`. L'amont le confirme : issue **#44 « Iris Support »** ouverte depuis 2022 et **#240** (« incompatibility with … iris shaders ») ouverte. **VulkanMod n'exécute donc pas de shaderpacks Iris.**

**Traitement des SHADERS** (le point qui nous concerne) :
- `vulkan/shader/SPIRVUtils.java` (226 lignes) : **shaderc en processus** via LWJGL ; options `shaderc_optimization_level_performance`, cible **`shaderc_env_version_vulkan_1_2`** ; résolveur d'`#include` par rappel ; compilation `shaderc_compile_into_spv` à chaque chargement (pas de cache SPIR-V sur disque : le `//TODO` du code le note). Erreur du compilateur = `RuntimeException` (aucune protection contre un crash natif).
- `vulkan/shader/converter/GlslConverter.java` (203 lignes) + `UniformParser`/`InputOutputParser`/`CodeParser` : **convertisseur GLSL 1.50 → 450 ligne par ligne** (jetons `uniform`, `in`/`out`, `#moj_import` → `#include`, retrait de `#version`, renommage `sample` → `sample1`). Tous les `uniform` non opaques d'un shader sont **regroupés dans un seul bloc UBO** (std140) placé en tête ; les samplers reçoivent un `binding` ; les entrées/sorties reçoivent des `location`. C'est **la même idée que notre `UniformReblocker`**, mais pour les **petits shaders vanilla** (`assets/vulkanmod/shaders/core/*`, ~198 fichiers shader/json), avec un parseur à base de séparation de lignes et de comptage de parenthèses : **il n'est pas fait pour des shaders de pack de 100 000 caractères**, ni pour les macros/`#define` complexes.
- Descripteurs : construits depuis ce que le convertisseur a lu (`UBO`, `ManualUBO`, `ImageDescriptor`, `PushConstants`), **pas par réflexion SPIR-V**. Pas de bindless (`descriptor_indexing` : 0 occurrence).
- Compute : `ComputePipeline`, `ImageComputePipeline` (utilisés par le culling et la pyramide Hi-Z).

**Ce que le README promet vs ce qui est** : le README tient en 6 lignes (« NeoForge version of VulkanMod… téléchargez depuis Actions ») ; le contenu réel est le moteur VulkanMod complet, sur **1.21.4/NeoForge 21.4**, donc **incompatible tel quel avec Minecraft 26.3**, dont le rendu a été réécrit (API `renderpearl`, voir § 4).

## 3. Vulkan-PostFX-Legacy

**Ce que le projet FAIT** : un chargeur de packs au **format maison « VPFX v1 alpha »** (`pack.json` + graphe JSON de passes + `shaders/<pack>/<chemin>.vsh/.fsh`) qui s'exécute via le **`PostChain` de Minecraft** (backend `minecraft_postchain`, `VpfxPostChainBackend`, « transitoire »). Un runtime natif « VPFX » n'existe qu'en **squelette v0** (`runtime/nativevpfx/*`, 25 fichiers + `framegraph/` 15 fichiers) : graphe IR, vérificateur de capacités, exécuteur de **passe plein écran unique** (`VpfxNativeFullscreenExecutor`), dry-runs. Le statut du README le dit lui-même : compute **non supporté**, passe d'ombres « expérimentale », runtime natif « v0 skeleton ».
- **Interaction avec le rendu de Minecraft 26.2** : uniquement par les **types vanilla** (imports les plus fréquents : `PostChain` ×8, `LevelRenderer` ×8, `BindGroupLayouts` ×5, `ProjectionMatrixBuffer`, `RenderPipelines`, `SectionRenderDispatcher`, `ChunkSectionsToRender`, `PostPass`, `PostChainConfig`). **Aucun appel LWJGL Vulkan, aucun VMA, aucun shaderc direct** : il utilise le compilateur de shaders et les pipelines **du jeu**.
- **Passe d'ombres** (`client/shadow/*`, 16 fichiers, « Lite ») : matrices d'ombre, cibles de rendu, pipelines d'ombre dédiés pour le terrain et les entités, suppression de l'ombre vanilla des entités ; exposé comme ressource `minecraft:shadow_depth`.
- **Feuille de route** (`docs/vpfx-v2-runtime-roadmap.md`) : « capacité type Iris » (graphe multi-passes, GBuffers, ombres en cascade, historique/TAA, SSAO/SSR, volumétrique, nuages) mais **explicitement sans compatibilité avec les packs Iris** : « les auteurs devront porter vers le format VPFX ».
- **Maturité** : archivé, alpha, Fabric seulement. Utile comme **carte du terrain** (quels types vanilla 26.2 accrocher), pas comme base.

## 4. Ce que vanilla 26.3 fait DÉJÀ (vérifié par `javap` sur le vrai jar)

Fait nouveau et important pour nous — Minecraft 26.3 possède un **compilateur de shaders frontal commun aux deux moteurs** :
- Classes `com.mojang.renderpearl.frontend.shaders.{GlslCompiler, SPIRVModule, SpvUtil, PipelineBuilder}` ; côté OpenGL `backend/opengl/{GlShaderModule, GlPipelineRecompiler}` ; côté Vulkan 62 classes dont `VulkanRenderPass`.
- `GlslCompiler.compileToSpv(String nom, String source, ShaderType, ShaderDefines, ShaderSource)` renvoie un `SpvModule` (avec **réflexion** dont `SpvModule$Reflection$PushConstant`). Options lues au bytecode (`createBaseShaderOptions`) : `shaderc_compile_options_set_target_env(options, 0, 4202496)` (= **Vulkan 1.2**, `0x402000`), **`auto_bind_uniforms = true`**, `preserve_bindings = false`, `generate_debug_info` activé, **`set_optimization_level(options, 0)` = niveau ZÉRO** (pas de spirv-opt), macros `RENDERPEARL_DEPTH_IS_ZERO_TO_ONE`, `RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE`, `RENDERPEARL_INSTANCE_INDEX_INCLUDES_BASE_INSTANCE`, `#include` par rappel, trace Tracy « Compile to SPV ». Donc **même en OpenGL, le jeu compile ses shaders GLSL→SPIR-V avec shaderc**.
- Mêmes briques que celles décrites dans `AUDIT-IRIS-BESOINS-TERRAIN.md` : dessin indirect (`ChunkSectionsToRender`), tampons partagés, occlusion.
- L'API publique expose `GpuQuery`/`GpuQueryPool` (requêtes GPU → **temps GPU par passe possible sans code natif**), `BindGroupLayout`, réflexion de push constants. **Je n'ai trouvé aucune classe « Compute » ni « Storage/SSBO/Indirect » dans `renderpearl/api`** (recherche par nom de classe seulement) : le culling GPU de VulkanMod n'a donc **pas d'équivalent dans l'API Mojang** ; il faudrait du GL 4.3 direct (chemin OpenGL) et/ou du LWJGL Vulkan direct (chemin Vulkan).

## 5. Comparaison avec Lanterne

| Sujet | VulkanModNeoForge | PostFX | Vanilla 26.3 | Lanterne aujourd'hui |
|---|---|---|---|---|
| Moteur Vulkan | complet, à lui | non (utilise celui de Mojang) | **natif** (`renderpearl/backend/vulkan`) | **s'appuie sur celui de Mojang** : 9 mixins (`VulkanDeviceMixin`, `VulkanRenderPipelineMixin`, `Empreinte` = `VkPipelineCache` persisté par `pipelineCacheUUID`) |
| Compilation shaders | shaderc en processus, opt. « performance » | celle du jeu | shaderc en processus, opt. **0** | `lab/ShadercProbe` : shaderc `options = 0L` (défauts : pas d'optimisation) |
| Uniformes | convertisseur ligne à ligne → 1 UBO | `BindGroupLayouts` | `auto_bind_uniforms` + `BindGroupLayout` | `UniformReblocker` (itératif, sans récursion) → 1 UBO ; `JcppProcessor` (prétraitement Java) |
| Culling GPU + Hi-Z | **oui** (`cull.comp`) | non | non | non |
| Ombres | non | « Lite » (expérimental) | non | branche `feat-shadow` en cours |
| Iris/packs | **non** (issues #44, #240 ouvertes) | non (format maison) | — | Iris natif, **inerte sous Vulkan** |
| Version MC | 1.21.4 | 26.2 | 26.3 | 26.3 |

**Redondant avec vanilla 26.3** : le moteur Vulkan de VulkanMod, sa mémoire VMA, ses tampons de terrain, son cache de pipelines, son convertisseur GLSL→Vulkan pour les shaders vanilla (le jeu a maintenant son propre frontal). **Incompatible** : tout ce qui touche `net.minecraft.client.renderer` en 1.21.4 (l'API de rendu a été refondue en 26.x). **Non couvert par aucun des deux** : exécuter un shaderpack Iris sous Vulkan, ce qui est notre objectif.

## 6. Blocage shaderc : ce que ces dépôts nous apprennent

Rappel (`RECHERCHE-VULKAN-SANS-SODIUM.md`, étape 13) : `shaderc_compile_into_spv` plante **dans l'appel natif** (« OutOfMemoryError: Out of stack space ») sur le fragment de `gbuffers_terrain` de Complementary (~99 760 caractères) ; un facteur 8 sur la pile (64 → 512 Mo) ne change rien, échec en ~0,45 s ; le vertex du même fichier compile.
1. **Ni VulkanMod ni PostFX ne rencontrent ce cas** : leurs shaders sont petits (vanilla ou shaders d'effet). Ils **n'apportent donc pas de solution** au bug, mais ils montrent qu'**il n'y a pas de meilleur compilateur en processus dans l'écosystème Minecraft** : tous utilisent le même `shaderc` LWJGL.
2. **Le niveau d'optimisation n'est pas en cause** : vanilla compile en niveau 0, comme notre sonde (`options = 0L`). Le plantage se produit donc **avant spirv-opt**, dans le **front-end glslang** (analyse/prétraitement/génération de l'arbre). Régler `set_optimization_level` (piste citée à l'étape 13) est **peu prometteur** ; la piste de VulkanMod (« performance ») serait plutôt pire.
3. **Conséquence utile** : ce que fait vanilla (`GlslCompiler`) est exactement ce que nous ferions pour un shader de pack. Si on passait les shaders d'Iris dans `GlslCompiler.compileToSpv` (source + `ShaderDefines` + `ShaderSource` pour les includes), on hériterait des macros `RENDERPEARL_*`, de la réflexion de push constants et du cache de pipelines du jeu — **mais on hérite aussi du même shaderc, donc du même plantage** pour les gros fragments.

## 7. Tests à tenter pour lever le blocage shaderc (ordre de rentabilité)

Tous **sans client Minecraft** (programme `java Fichier.java` avec les jars LWJGL `shaderc` du cache Gradle), sur le vrai `gbuffers_terrain` transformé par nos outils :
1. **T1 — mêmes options que vanilla** : `set_target_env(vulkan, 0x402000)`, `auto_bind_uniforms=true`, `preserve_bindings=false`, niveau 0, **avec puis sans `generate_debug_info`** (vanilla l'active ; l'info de debug alourdit l'arbre et peut changer la profondeur de récursion).
2. **T2 — `shaderc_compile_into_preprocessed_text`** puis compilation du texte prétraité : si le plantage est dans le préprocesseur de glslang (macros récursives), on le contourne ; on a déjà `JcppProcessor`, vérifier qu'aucune macro pathologique ne subsiste.
3. **T3 — dichotomie du fragment** (déjà prévue) : couper en moitiés compilables (fonctions déplacées dans des en-têtes) pour isoler le motif GLSL déclencheur (souvent : expression très longue/imbriquée, énorme initialiseur de tableau ou de structure constante, chaîne de `else if`/ternaires profonde). Un aplatissement **dans notre transformateur** (introduire des variables temporaires pour couper les expressions profondes) est faisable en AST.
4. **T4 — processus séparé** : lancer shaderc dans une JVM enfant (ou `glslc`/`glslangValidator` officiels de Khronos, licence permissive) pour (a) contenir le plantage (le jeu ne tombe plus : erreur propre et repli OpenGL), (b) tester une **autre version** du compilateur, éventuellement plus récente que celle de LWJGL 3.4.3.
5. **T5 — `shaderc_compile_options_set_limit`** : essayer de baisser les limites de ressources de glslang pour transformer un plantage en erreur de compilation contrôlée (utile pour le repli).
6. **T6 — compiler via `GlslCompiler.compileToSpv` du jeu** pour un shader de pack **plus petit** (composite/final) afin de valider toute la chaîne d'intégration (macros, includes, réflexion) sans le cas pathologique.

## 8. Ce qu'on peut faire, dans l'ordre (top 5 + suites)

1. **Contenir le plantage natif (T4)** : compiler dans un processus séparé avec délai et repli. Sans cela, le moindre shader pathologique fait tomber le client ; avec cela, Vulkan+Iris devient un « essai avec repli OpenGL », jamais un crash. *(gain : sécurité immédiate ; effort : faible)*
2. **T1/T2/T3 sur le vrai fichier** (options identiques à vanilla, texte prétraité, dichotomie) : ce sont les seuls leviers qui peuvent réellement **lever** le blocage. Documenter chaque résultat exact. *(effort : faible à moyen)*
3. **Reprendre l'idée du culling GPU + Hi-Z de VulkanMod** (code **LGPL-3.0, réutilisable avec crédit**) pour **notre chemin OpenGL 4.3** (compute + `glMultiDrawElementsIndirect`/`…IndirectCount` selon extension) : c'est l'idée n° 2 de `IDEES-OPTIMISATION.md`. Le compute `cull.comp` (127 lignes) se porte presque tel quel (SSBO `std430`, push constants → UBO/uniformes) ; réutiliser aussi la pyramide `hiz_downsample.comp` (24 lignes). **Attention** : (a) pas d'API compute dans `renderpearl` → GL direct ; (b) sous shaders, réutiliser le résultat pour la passe d'ombre (frustum de lumière) ; (c) translucides exclus ; (d) sections proches de la caméra jamais éliminées. À mesurer sous la règle de mesure n° 0 avant d'adopter.
4. **Utiliser les briques vanilla plutôt que réécrire** : `GpuQuery`/`GpuQueryPool` pour les **temps GPU par passe** dans le profileur/bilan ; `BindGroupLayout` et la réflexion `SpvModule` pour nos pipelines de post-traitement ; ne **pas** porter VulkanMod (redondant et incompatible 26.3).
5. **Post-traitement Iris sous Vulkan** : s'inspirer de la **structure** de PostFX (idées seulement) — graphe de passes en IR, vérificateur de capacités avec repli automatique, passes plein écran d'abord, cibles transitoires, ressources `scene_depth`/`shadow_depth` de première classe — mais viser l'exécution d'un pack **Iris** (multi-passes `composite*`/`final`, plus petits que `gbuffers_terrain`), en commençant par les composites via `GlslCompiler` (T6).
6. **Pièges à éviter** : (i) copier du code de PostFX (interdit) ; (ii) supposer que VulkanMod gère Iris (non) ; (iii) compter sur `optimization_level` pour éviter le plantage (non) ; (iv) forcer Vulkan sur AMD/Intel (la veille a mesuré −39 à −58 % sur AMD : garder OpenGL par défaut hors NVIDIA).
7. **Suites** : compilation SPIR-V **hors ligne mise en cache** (clé = SHA-256 du source transformé + options), ce que même VulkanMod ne fait pas (`SPIRVUtils` recompile à chaque chargement) ; avec notre cache de pipelines (`Empreinte`) cela supprimerait le coût de compilation aux lancements suivants.

## 9. Non vérifié (honnêteté)

- **Rien n'a été compilé ni exécuté** (ni VulkanModNeoForge, ni PostFX) : les constats viennent de la lecture du code et des métadonnées ; « maturité » = commits/étoiles/issues GitHub, pas un test d'usage.
- Clonage `--depth 1` : l'historique complet des deux dépôts n'a pas été relu (« 1 commit » de VulkanModNeoForge est l'effet du clone superficiel et du fork, pas une mesure de l'activité).
- **Absence de classe « Compute » dans l'API Mojang** : établie par recherche de noms de classes dans le jar (`renderpearl/api`, `backend/api`), pas par lecture de tout le bytecode ; à confirmer avant de conclure définitivement.
- Je n'ai pas relu tout `AUDIT-IRIS-BESOINS-TERRAIN.md` ni les étapes 6–12 de la recherche Vulkan ; j'ai utilisé l'étape 13 et les résumés du contexte.
- Les hypothèses sur la cause exacte du plantage de glslang (§ 6-7) sont des **pistes à tester**, pas des faits.
- Aucune vérification de compatibilité de VulkanModNeoForge avec NeoForge 26.3 par exécution : elle est déduite de sa version cible (1.21.4/21.4.157) et du fait que l'API de rendu de 26.x a changé.

## Sources
- https://github.com/yiyuyan/VulkanModNeoForge (LGPL-3.0, fork de https://github.com/xCollateral/VulkanMod)
- https://github.com/Player9753193/Vulkan-PostFX-Legacy (All Rights Reserved ; page officielle indiquée : https://modrinth.com/mod/vulkan-postfx)
- Issues amont : https://github.com/xCollateral/VulkanMod/issues/44 (« Iris Support »), /issues/240
- Jar : `minecraft-client-patched-26.3.0.7-beta.jar` (`com.mojang.renderpearl.frontend.shaders.GlslCompiler`, `javap -p -c`)
