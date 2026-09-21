# Recherche : Vulkan et rendu sans Sodium pour notre portage Iris

Audit factuel du 20/09/2026, sur le clone de travail `iris-src` (branche 26.3 d'Iris,
LGPL-3.0 — voir `IRIS-ATTRIBUTION.md`). Chiffres obtenus par grep/javap sur le code et
bytecode réels, pas par supposition.

## Question 1 — Vulkan : l'ampleur précise, pas "impossible"

**Le moteur vanilla a déjà un vrai backend Vulkan parallèle à OpenGL.** Confirmé dans
`minecraft-patched-26.3.0.7-beta.jar` : `com/mojang/renderpearl/backend/{opengl,vulkan}/`
existent tous les deux sous une même API agnostique `com/mojang/renderpearl/api/`.
`VulkanRenderPipeline`, `VulkanDevice`, `VulkanCommandEncoder` etc. sont des classes
réelles et complètes côté vanilla.

**Répartition du code d'Iris (`common`, 696 fichiers .java) :**
- 119 fichiers couplés à OpenGL de façon dure (import direct `org.lwjgl.opengl.*` ou
  `com.mojang.renderpearl.backend.opengl.*`).
- 88 fichiers utilisent déjà l'API agnostique `com.mojang.renderpearl.api.*`.
- 37 fichiers utilisent **les deux** — déjà partiellement migrés, probablement parce
  que l'API agnostique ne couvre pas encore tout (compilation GLSL bas niveau,
  typiquement).
- **82 fichiers sont couplés dur SANS aucune trace de l'API agnostique** — c'est la
  vraie cible de réécriture, pas 696.

**Le vrai point dur n'est pas l'API de rendu, c'est la compilation des shaders.**
Iris charge des shaderpacks tiers écrits en GLSL, **au runtime**, choisis par le
joueur — ce n'est pas comparable au cas vanilla, où Mojang connaît ses propres
shaders à l'avance et peut les précompiler en SPIR-V (le format que Vulkan consomme,
PAS le GLSL) au moment du build. Vulkan ne sait pas lire du GLSL directement.

**Bonne nouvelle concrète** : `lwjgl-shaderc` (le compilateur GLSL→SPIR-V officiel de
Google/Khronos, avec bindings LWJGL) est **déjà présent dans le cache Gradle local**
(`org.lwjgl:lwjgl-shaderc:3.4.1`, natifs Windows inclus) — donc disponible sans
dépendance externe à ajouter. `com.mojang.renderpearl.util.ShaderCompileException`
existe déjà côté vanilla, ce qui confirme qu'un pipeline de compilation shader est un
concept déjà géré par le moteur, pas à inventer de zéro.

**Chemin concret (pas un refus)** :
1. Intégrer `lwjgl-shaderc` pour compiler les `.fsh`/`.vsh` GLSL des shaderpacks en
   SPIR-V au chargement du pack (pas à chaque frame — un coût ponctuel, comme la
   compilation OpenGL actuelle).
2. Réécrire les 82 fichiers durs pour passer par `renderpearl.api.*` au lieu des
   appels GL directs — chantier mécanique mais borné, pas infini.
3. Les 37 fichiers déjà hybrides sont la meilleure référence de comment Iris a déjà
   commencé cette transition ailleurs — s'en inspirer plutôt que réinventer.

**Ampleur réaliste** : plusieurs semaines de travail dédié (82 fichiers à auditer un
par un + le pipeline shaderc à écrire et tester), pas un simple flag à activer — mais
un chemin réel existe, avec les briques déjà là. Points d'ancrage les plus critiques :
`net.irisshaders.iris.pipeline.programs.ShaderCreator`/`ShaderMap` (compilation),
`net.irisshaders.iris.gl.*` (84 fichiers, le cœur de l'abstraction GL actuelle
d'Iris — à faire pointer vers `renderpearl.api` plutôt que LWJGL brut).

## Question 2 — Rendu sans Sodium : surface réelle, pas un simple retrait

**54 fichiers dans `common` référencent des types Sodium**, mais la moitié n'est pas
le vrai problème :
- **21 fichiers** sont dans `compat/sodium/mixin/` — ce sont des **mixins qui ciblent
  des classes Sodium**. Confirmé cette session (comportement Mixin standard) : si
  Sodium n'est pas chargé, ses classes ne sont jamais chargées non plus, donc ces
  mixins ne s'appliquent simplement jamais. Aucun risque, aucun travail nécessaire ici.
- **Les ~33 fichiers restants** sont le vrai couplage dur (imports Sodium utilisés en
  code Java normal, pas en cible de mixin) :
  - `vertices/sodium/` (5) + `vertices/sodium/terrain/` (3) : le format de vertex du
    terrain lui-même — la partie la plus critique et la plus difficile à remplacer,
    car c'est ce qui permet à Iris d'insérer des données par-vertex (normales,
    lumière) dans le pipeline de maillage de Sodium.
  - `mixin/` (5, dont le bootstrap `Iris.java` déjà patché cette session avec une
    garde `isModLoaded`) + `compat/sodium/config` (1) + `uniforms` (2) + `shadows/
    frustum/{fallback,advanced}` (4) + le reste (13 fichiers isolés).

**Aucun chemin "vanilla-only" mûr n'existe déjà dans Iris** : une seule garde
`isModLoaded("sodium")` trouvée dans tout `common/` (celle qu'on a ajoutée
nous-mêmes dans `Iris.java` hier soir). Vérifié : pas de branche alternative de
rendu terrain sans Sodium à moitié écrite qu'on pourrait terminer — il faudrait
réellement écrire un chemin de sérialisation de vertex directement contre le
pipeline de maillage vanilla (`SectionRenderDispatcher` côté Minecraft), depuis
zéro, pour ces ~33 fichiers.

**Conclusion honnête** : c'est un vrai sous-projet scopé (pas un retrait d'une
ligne), centré sur `vertices/sodium/terrain/` en premier — mais borné à ~33
fichiers, pas 980. Faisable, pas cette nuit dans son intégralité.

## Découverte complémentaire (2e passe) : mixins sur cibles VANILLA qui importent Sodium

Nouvelle catégorie trouvée, distincte des 21 mixins `compat/sodium/mixin/` (sans risque, cible
Sodium-only, jamais chargée si Sodium absent) : **4 fichiers dans `mixin/` (pas `compat/sodium/
mixin/`) ciblent des classes VANILLA/moteur, toujours chargées, tout en import­ant des types
Sodium dans leurs champs/paramètres** :

- `MixinLevelRenderer.java` — `@Mixin(LevelRenderer.class)` (vanilla), importe
  `net.caffeinemc.mods.sodium.client.util.GameRendererStorage`.
- `MixinWindow.java` — `@Mixin(GlBackend.class)` (`com.mojang.renderpearl.backend.opengl`,
  moteur vanilla), importe `SodiumClientMod`/`SodiumOptions`.
- `MixinRenderPipeline.java` — `@Mixin(RenderPipeline.class)` (`com.mojang.renderpearl.api.
  pipeline`, moteur vanilla), importe `ChunkMeshFormats` (Sodium).
- `MixinDebugScreenEntriesList.java` — `@Mixin(DebugScreenEntryList.class)` (l'écran de debug
  F3 vanilla, systématiquement chargé), importe `SodiumClientMod`/`PlatformRuntimeInformation`.

**Pourquoi c'est un vrai risque, contrairement aux mixins `compat/sodium/mixin/`** : ces 4
cibles sont vanilla et se chargent TOUJOURS (pas seulement si Sodium est présent), donc le
classe-mixin elle-même ELLE sera transformée/appliquée dans tous les cas — si un champ ou
paramètre référence un type Sodium absent, ça peut planter à l'application du mixin, pas juste
rester silencieusement inactif.

**Pourquoi PAS corrigé cette nuit (correctement, pas par flemme)** : le vrai correctif est de
faire filtrer CES 4 mixins précisément par `IrisMixinPlugin.shouldApplyMixin(...)` (déjà présent,
gère aujourd'hui uniquement le choix OpenGL/Vulkan via `usingVulkan`) en ajoutant une garde
Sodium. C'est un changement de LOGIQUE DE MIXIN, la même catégorie de modification qui a causé
2 des 5 crashs client de cette nuit (mixins mal appliqués) — donc à tester avec un vrai lancement
CLIENT (pas juste serveur) avant tout déploiement, ce qui n'était pas possible cette nuit sans
lanceur client jetable (voir TACHES-NUIT.md, tâche en cours ailleurs). Risque non-urgent
aujourd'hui : Sodium reste présent et fonctionnel dans tous les tests actuels, ce cas ne se
déclenche que dans l'hypothèse future "Sodium retiré".

**Piste concrète pour la suite** : étendre `IrisMixinPlugin.shouldApplyMixin` avec une liste des
mixins Sodium-dépendants hors `compat/sodium/`, refusés si `!Compagnons.present(SODIUM)` (accès
à `IrisPlatformHelpers.getInstance().isModLoaded("sodium")`, déjà utilisé ailleurs dans le code).

## Vulkan — étape 2 : la vraie chaîne testée sur un VRAI shader (21/09/2026 ~01h30)

Suite du prototype isolé `lab/ShadercProbe.java` (commit `9d56678`, GLSL minimal → SPIR-V
réussi). Cette passe teste la vraie chaîne sur du contenu réel : `shaders/program/composite3.glsl`
extrait de `ComplementaryReimagined_r5.9.zip` (vrai shaderpack installé), passé par
`JcppProcessor.glslPreprocessSource` (préprocesseur C d'Iris, déjà migré et fonctionnel) PUIS par
`shaderc_compile_into_spv`.

**Résultat réel, vérifié hors-jeu (compilation + exécution standalone, classpath reconstruit à la
main avec les vraies libs LWJGL/jcpp du cache Gradle)** :

- **Le préprocesseur fonctionne sur du contenu réel** : `Preprocessing OK, 1994 chars` — les
  macros/includes du vrai shaderpack passent sans erreur.
- **`shaderc` seul rejette le résultat** : 7 erreurs, dont `'colortex0' : undeclared identifier`,
  `'texelCoord' : undeclared identifier`, `#version: Desktop shaders for Vulkan SPIR-V require
  version 140 or higher`.

**Pourquoi, honnêtement** : les shaderpacks Iris écrivent du GLSL qui suppose que le MOTEUR
(Iris) va injecter des déclarations (uniforms de texture comme `colortex0`, variables comme
`texelCoord`, correction de la ligne `#version`) AVANT compilation — c'est le travail de
`glsl-transformer` (transformation d'AST GLSL), pas du préprocesseur C `jcpp`. Ces deux
bibliothèques sont toutes les deux déjà migrées et bundlées (`jarJar`, commit `115e33b`), mais
seule `jcpp` a été exercée dans ce test — `glsl-transformer` reste à mettre dans la boucle pour
que la vraie chaîne (preprocessing + transformation + compilation SPIR-V) fonctionne de bout en
bout sur du vrai contenu de shaderpack.

**Prochaine étape concrète** : trouver la classe d'Iris qui orchestre l'appel à
`glsl-transformer` pour l'étape de transformation (chercher dans
`net.irisshaders.iris.pipeline.transform` ou similaire dans le code déjà migré), l'appeler en
isolé dans `ShadercProbe` de la même façon que `JcppProcessor` ici, avant l'appel shaderc. Ne pas
brancher au pipeline de rendu réel avant d'avoir un test standalone qui produit du SPIR-V valide
sur un vrai shader.

## Vulkan — étape 3 : nouveau blocage précis, glsl-transformer couplé à un device GPU vivant (21/09/2026 ~02h)

Suite directe de l'étape 2 : cherché où Iris orchestre l'appel à `glsl-transformer` pour combler
exactement le manque identifié (`colortex0`/`texelCoord` non déclarés, `#version` manquant).
Trouvé : `net.irisshaders.iris.pipeline.transform.TransformPatcher`, méthode `patchComposite(...)`
(exactement celle utilisée pour les shaders `composite*.glsl` comme celui testé à l'étape 2).

**Nouveau blocage réel, vérifié en lisant le code (pas une supposition)** : `patchComposite` et
`patchDepth` commencent TOUS LES DEUX par `RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()`
(lignes 317 et 381) — un appel qui exige un device GPU/RenderSystem déjà initialisé par une vraie
session de jeu en cours. Un prototype standalone (sans client Minecraft lancé, comme `ShadercProbe`
l'a fait jusqu'ici) ne peut PAS appeler cette méthode : `RenderSystem.getDevice()` lèvera une
exception ou renverra null hors d'un contexte de rendu réel — non testé pour ne pas risquer un
appel qui bloque/plante le processus de test, la lecture du code suffit à confirmer le couplage.

**Conclusion honnête** : la chaîne complète jcpp → glsl-transformer → shaderc, telle qu'Iris
l'utilise réellement, N'EST PAS testable en standalone pur sans lancer le vrai jeu — c'est un
couplage architectural d'Iris lui-même (le transform dépend de l'état du device de rendu réel,
probablement pour des choix de compatibilité spécifiques au backend GPU actif), pas une limite
de notre prototype qu'on pourrait contourner proprement sans risque. Continuer cette piste
nécessite soit (a) un vrai lancement client (voir le chantier "lanceur client jetable" ailleurs
dans TACHES-NUIT.md), soit (b) appeler `glsl-transformer` directement via son API bas niveau
(sans passer par `TransformPatcher`, en réimplémentant à la main les déclarations qu'Iris injecte
normalement) — plus de travail, mais reste standalone. Aucune des deux tentée dans cette passe
(budget de fork insuffisant), à reprendre.

Aucun fichier de code modifié cette passe (recherche/lecture seule, `ShadercProbe.java` inchangé).

## Vulkan — étape 4 : chaîne complète PROUVÉE dans un vrai contexte client, SUCCÈS (21/09/2026 ~02h15)

Suite directe de l'étape 3, maintenant que le lanceur client jetable fonctionne (voir TACHES-NUIT.md,
TÂCHE C (suite)). Le blocage de l'étape 3 (`RenderSystem.getDevice()` exige un device réel) n'existe
plus dès lors que le code tourne DANS un vrai client lancé plutôt qu'en standalone.

**Ce qui a été ajouté** (aucun fichier `net.irisshaders`/`kroppeb`/`mixins.iris*` touché, comme
demandé) :
- `lab/ShadercProbe.testCompositeInClient(String)` — appelle réellement
  `TransformPatcher.patchComposite(...)` (vrai code Iris migré, jamais modifié) sur un fragment
  composite autonome (pas de `#include`), puis passe le résultat patché à shaderc via un nouveau
  helper partagé `compileSpirv`.
- `lab/ShadercProbeSelfCheck` (nouveau, même patron que `client.regard.RegardSelfCheck`) — gated
  par `LANTERNE_SHADERC_COMPOSITE_TEST=1` (jamais actif pour un joueur normal), se déclenche une
  seule fois au premier tick client une fois la fenêtre/le device GPU prêts (menu principal suffit,
  aucun monde requis).

**Premier essai, échec précis et honnête** : `TransformPatcher.patchComposite` a réellement
fonctionné dans le vrai contexte (`RenderSystem.getDevice()` a répondu, aucune exception) — le
blocage de l'étape 3 est donc bien résolu par le contexte client réel, pas contourné. Mais shaderc a
rejeté le GLSL patché en sortie :
```
probe-composite-patched.glsl:5: error: 'binding' : sampler/texture/image requires layout(binding=X)
probe-composite-patched.glsl:6: error: 'location' : SPIR-V requires location for user input/output
```
**Cause** : le GLSL produit par `TransformPatcher`/`glsl-transformer` est du GLSL "style OpenGL"
(`uniform sampler2D colortex0;` sans `layout(binding=...)`, exactement ce qu'Iris envoie réellement
au driver OpenGL en jeu aujourd'hui) — SPIR-V, contrairement à OpenGL, exige que CHAQUE
uniform/entrée-sortie porte une décoration de binding/location explicite ; ce n'est pas un défaut du
GLSL patché, juste une exigence propre à la cible SPIR-V qu'OpenGL n'a jamais eue.

**Correctif, deuxième essai, SUCCÈS RÉEL confirmé en jeu** : shaderc sait assigner ces
bindings/locations automatiquement (équivalent de `--auto-map-bindings` de `glslang`) —
`shaderc_compile_options_set_auto_bind_uniforms(options, true)` +
`shaderc_compile_options_set_auto_map_locations(options, true)`, passées via un handle d'options
construit par `shaderc_compile_options_initialize()` (jusque-là toujours 0/absent dans
`ShadercProbe`). Rebuild, relancé dans le même client jetable, log confirmé :
```
[SHADERC] TransformPatcher.patchComposite reussi (363 caracteres patches), tentative shaderc...
[SHADERC] Compilation GLSL->SPIR-V reussie : 968 octets SPIR-V produits.
[SHADERC-TEST] RESULTAT: SUCCES
```
`grep -ic fatal` → 0 sur stdout/stderr du lancement. **C'est la première preuve de bout en bout,
dans un vrai contexte de jeu, que la chaîne jcpp → glsl-transformer (via le vrai `TransformPatcher`
d'Iris, jamais modifié) → shaderc produit du SPIR-V valide à partir d'un vrai shader de
shaderpack.**

**Ce que ça NE prouve PAS encore** (pour ne pas survendre ce résultat) :
- Un seul fragment composite autonome a été testé, pas les vertex/geometry shaders, pas
  `patchVanilla`/`patchSodium`/`patchDHTerrain` (les autres points d'entrée de `TransformPatcher`,
  chacun avec ses propres paramètres), et surtout pas un shader avec de vraies directives
  `#include` (hors périmètre choisi dès l'étape 2, voir la Javadoc de `testReal`).
- Le SPIR-V produit n'a jamais été chargé dans un vrai pipeline Vulkan (`VkShaderModule`,
  pipeline graphique complet) — seule la compilation GLSL→SPIR-V est prouvée, pas l'exécution.
- `auto_bind_uniforms`/`auto_map_locations` assignent des bindings arbitraires (premier
  disponible) — un VRAI pipeline Vulkan aurait besoin d'un contrôle explicite de ces indices pour
  faire correspondre les uniformes/textures à ce que le reste du moteur attend (descriptor sets),
  pas juste "que ça compile".

**Prochaine étape concrète pour qui reprend ça** : essayer `patchVanilla` (le point d'entrée le
plus utilisé, pour les shaders "gbuffers") avec un shader vertex+fragment réel du même shaderpack,
toujours via ce même lanceur client jetable et le même patron `*SelfCheck` gated par variable
d'environnement — c'est le chemin le plus court vers "peut-on un jour se passer d'OpenGL", pas
encore répondu par cette étape.

Commits : ajout de `testCompositeInClient`/`compileSpirv`/`ShadercProbeSelfCheck`, puis l'ajout des
options auto-bind/auto-map, sur la branche `26.3`, jamais poussés au VPS ni à `test 1` (uniquement
testés sur le lanceur client jetable isolé `C:\lanterne-client-vulkan-test\`, copie indépendante de
`C:\lanterne-client-test\` pour ne pas interférer avec la vérification en cours dans ce dossier-là).

## Vulkan — étape 5 : `patchVanilla` (gbuffers, le point d'entrée le PLUS utilisé), nouveau blocage précis (21/09/2026 ~02h18)

Suite immédiate de l'étape 4 : `patchComposite` prouvé, mais c'est `patchVanilla` (shaders
"gbuffers", vertex+fragment, sur la quasi-totalité du rendu terrain/entités) qui est réellement le
chemin critique. Ajouté `ShadercProbe.testVanillaInClient(vertex, fragment)`, même patron, testé
dans le même client jetable avec une paire vertex/fragment "gbuffers" minimale mais réaliste
(`ftransform()`/`gl_Color`/`gl_MultiTexCoord0`, style legacy — exactement ce qu'un vrai shaderpack
Optifine/Iris envoie, à charge de `CompatibilityTransformer` de moderniser).

**Résultat** : `TransformPatcher.patchVanilla` a réussi (1732 + 1398 caractères patchés, vertex ET
fragment) — la transformation AST elle-même fonctionne sur ce point d'entrée aussi. shaderc a
rejeté les DEUX étages avec la même erreur :
```
error: 'non-opaque uniforms outside a block' : not allowed when using GLSL for Vulkan
```
**Cause, différente de l'étape 4** : le GLSL produit par `patchVanilla` déclare des uniforms
"classiques" (matrices de transformation, etc.) en dehors d'un bloc `uniform Nom { ... };` — valide
en GLSL "style OpenGL" (et pour la cible `shaderc_target_env_opengl`), mais explicitement interdit
par la spécification SPIR-V pour Vulkan (`shaderc_target_env_vulkan`, la cible par défaut de
`shaderc_compiler_initialize`) : Vulkan exige que tout uniform non-opaque (donc pas une texture/un
sampler) vive dans un UBO (`uniform buffer object`), jamais en variable globale libre.

**Ce n'est PAS résolu par `auto_bind_uniforms`/`auto_map_locations`** (qui assignent des indices,
pas une structure de bloc) — contrairement au blocage de l'étape 4. Deux vraies pistes, aucune
tentée cette passe (budget de fork) :
1. Compiler avec `shaderc_compile_options_set_target_env(options, shaderc_target_env_opengl, shaderc_env_version_opengl_4_5)` au lieu de la cible Vulkan par défaut — produit du SPIR-V valide pour
   `GL_ARB_gl_spirv` (uniforms libres autorisés), mais ce N'EST PAS le même SPIR-V qu'un vrai
   pipeline Vulkan natif peut charger tel quel — a mesurer si c'est utile comme étape intermédiaire
   ou si c'est un cul-de-sac pour l'objectif final (backend Vulkan natif, voir les logs
   `[LANTERNE][VK-QUEUES]` déjà produits par ce même client — Vulkan est déjà détecté et
   partiellement sondé côté Lanterne).
2. Réécrire les uniforms générés par `patchVanilla` pour les regrouper dans un unique bloc UBO
   avant shaderc — nécessiterait soit un nouveau pass `glsl-transformer` (à écrire, pas dans Iris
   aujourd'hui — Iris n'a jamais eu besoin de ça, il compile pour OpenGL, jamais SPIR-V/Vulkan en
   pratique malgré le nom du dossier `pipeline.transform`), soit une regex/AST-walk maison sur la
   sortie déjà patchée.

**Ce que cette étape confirme malgré tout** : la chaîne `TransformPatcher` (code Iris migré, jamais
modifié) fonctionne bien pour DEUX points d'entrée différents (`patchComposite` ET `patchVanilla`)
dans un vrai contexte client — le blocage n'est plus du tout "peut-on appeler ce code", mais
strictement "le GLSL qu'Iris produit aujourd'hui cible OpenGL, pas Vulkan, et cet écart est
structurel (uniforms hors bloc), pas un détail de compilation". C'est un résultat honnête à ne pas
présenter comme "Vulkan presque prêt" — c'est plutôt la preuve précise de LA prochaine vraie
tâche si ce chantier continue : un pass de re-blocage des uniforms, pas un simple réglage
shaderc.

`grep -ic fatal` → 0 sur ce run aussi. Rien déployé sur le VPS ni `test 1`.

## Vulkan — étape 6 : `UniformReblocker`, pass de re-blocage des uniforms — SUCCÈS PARTIEL réel, un étage sur deux compile (21/09/2026 ~02h28)

Suite directe de l'étape 5 (piste 2 choisie : un vrai pass de re-blocage plutôt que
`shaderc_target_env_opengl`, qui aurait produit un SPIR-V non chargeable tel quel par un vrai
pipeline Vulkan — jugé être un cul-de-sac pour l'objectif final).

**Ajouté** (aucun fichier `net.irisshaders`/`kroppeb`/`mixins.iris*` touché, uniquement du nouveau
code dans `lab/`) : `UniformReblocker.reblock(glsl, blockName, binding)` — post-traitement texte
(pas un pass `glsl-transformer`, Iris n'en a pas besoin pour OpenGL) qui retire chaque déclaration
`uniform <Type> <nom>;` de niveau ligne dont le type n'est pas opaque (sampler/image/atomic_uint),
et les regroupe dans un seul bloc `layout(binding=N, std140) uniform <nom> { ... };` inséré juste
après `#version`. `ShadercProbe.testVanillaReblockedInClient` applique ce pass sur la sortie de
`TransformPatcher.patchVanilla` (vertex ET fragment) avant shaderc, wired dans
`ShadercProbeSelfCheck` derrière `LANTERNE_SHADERC_COMPOSITE_TEST=1`.

**Premier essai, nouveau blocage précis et honnête** : le bloc UBO généré est bien accepté comme
regroupement (l'erreur `'non-opaque uniforms outside a block'` disparaît), mais
`layout(binding=N)` sur un bloc uniform exige GLSL 420+ ou une extension explicite sur un
`#version` plus ancien (le `#version 150` "style OpenGL" qu'Iris produit réellement) :
```
'binding' : not supported for this version or the enabled extensions
```
**Corrigé** : `UniformReblocker` insère désormais `#extension GL_ARB_shading_language_420pack :
enable` juste après `#version`, avant le bloc UBO. Commit `cfd737a`.

**Deuxième essai, dans le même client jetable (`C:\lanterne-client-vulkan-test-2\`, copie isolée
créée pour ce test précis afin de ne pas interférer avec d'autres forks en cours sur
`C:\lanterne-client-test\`/`C:\lanterne-client-vulkan-test\`), résultat réel mixte** :

```
[SHADERC] UniformReblocker applique (vertex 1732 -> 1840 caracteres, fragment 1398 -> 1508 caracteres), tentative shaderc...
[SHADERC] Compilation GLSL->SPIR-V echouee (statut=2, erreurs=6) : probe-vanilla-vertex-reblocked.glsl:60: error: 'ftransform' : no matching overloaded function found
...
[SHADERC] Compilation GLSL->SPIR-V reussie : 3532 octets SPIR-V produits.
[SHADERC-TEST] RESULTAT VANILLA REBLOCKED: ECHEC
```

- **L'étage FRAGMENT re-bloqué compile réellement en SPIR-V valide (3532 octets)** — la toute
  première preuve, dans un vrai contexte client, qu'un shader "gbuffers" issu de `patchVanilla`
  produit du SPIR-V syntaxiquement valide une fois ses uniforms regroupés en UBO. Le pass
  `UniformReblocker` répond donc réellement au blocage précis identifié à l'étape 5.
- **L'étage VERTEX échoue toujours**, mais pour une raison **différente et non liée au re-blocage** :
  la source de test fabriquée pour ce laboratoire (`ShadercProbeSelfCheck.VANILLA_VERTEX_SOURCE`)
  utilise volontairement des builtins GLSL legacy (`ftransform()`, `gl_TexCoord[0]`,
  `gl_FrontColor`) pour ressembler à un vrai shader Optifine/Iris d'ancien style — le log montre
  que `CompatibilityTransformer` a bien patché la correspondance `in`/`out` entre étages
  (`irs_texCoords`), mais PAS remplacé les appels `ftransform()`/`gl_TexCoord` eux-mêmes dans le
  corps du shader, qui restent donc absents du profil "core" que shaderc applique par défaut à
  `#version 150` sans suffixe `compatibility`. C'est un problème de builtins legacy non modernisés
  par notre shader de test, PAS le blocage UBO — hors périmètre de cette tâche précise (re-blocage
  des uniforms), à creuser séparément si ce chantier continue (soit fournir un shader de test sans
  builtins legacy pour isoler proprement le résultat du re-blocage, soit comprendre pourquoi
  `CompatibilityTransformer` ne les modernise pas ici).

`grep -ic fatal` → 0 sur `logs/latest.log` ET `logs/debug.log`. Process de test arrêté proprement
(`taskkill //PID 39672 //F`, un seul PID ciblé, confirmé disparu). Rien déployé sur le VPS ni
`test 1` — testé uniquement sur `C:\lanterne-client-vulkan-test-2\`, isolé du reste.

**Conclusion honnête** : le pass de re-blocage des uniforms fonctionne réellement pour au moins un
étage de shader complet, prouvé en SPIR-V valide dans un vrai contexte GPU — ce n'est plus une
piste théorique. **Prochaine étape concrète** : refaire ce même test avec une paire vertex/fragment
"gbuffers" moderne (sans builtins legacy comme `ftransform`/`gl_TexCoord`) pour confirmer que
l'étage vertex compile aussi une fois isolé de ce problème annexe ; puis, seulement après ça,
tenter de charger le SPIR-V produit dans un vrai `VkShaderModule` (jamais fait à ce stade — seule
la compilation GLSL→SPIR-V est prouvée, pas le chargement dans un pipeline Vulkan réel).

## Sodium retiré : premier VRAI test client (pas juste lecture de code), deux crashs réels trouvés, un corrigé (21/09/2026 ~02h30)

Jusqu'ici tout ce document reposait sur de la lecture de code/bytecode (javap, grep) — jamais un vrai
lancement client avec Sodium physiquement absent de `mods/`. Fait maintenant via une copie isolée du
lanceur jetable (`C:\lanterne-client-sodium-test\`, clone de `C:\lanterne-client-test\` avec
`launch7.bat` réécrit pour pointer sur ce nouveau dossier — jamais touché `C:\lanterne-client-test\`
ni les copies Vulkan occupées par d'autres tâches en parallèle cette nuit).

**Crash #1 (trouvé, corrigé, reteste OK) — le guard `isModLoaded` existant ne suffisait pas.**
`Iris.onRenderSystemInit` avait déjà un `if (IrisPlatformHelpers.getInstance().isModLoaded("sodium"))`
autour de `new EntityToTerrainVertexSerializer()` etc. (commit `0473178`, avant cette tâche) — un
premier lancement réel (build `26.3.59`, celui déployé sur `test 1`) a quand même planté :
```
A technical error occurred during mod loading:
java.lang.NoClassDefFoundError: net/caffeinemc/mods/sodium/api/vertex/serializer/VertexSerializer
```
(trouvé via un thread dump `jstack` + un heap dump `jmap` du process — il ne plantait pas franchement,
il restait bloqué indéfiniment sur une boîte de dialogue Swing FML jamais cliquable dans un lanceur
automatisé ; `-Djava.awt.headless=true` n'a rien changé au blocage ; le message d'erreur exact n'était
visible QUE dans le heap dump, jamais loggé sur stdout/stderr).

**Cause réelle, confirmée par le heap dump, pas supposée** : un `if` runtime ne protège pas contre la
résolution de classe faite par la JVM au chargement/vérification de la méthode qui contient
l'instruction `NEW XxxVertexSerializer` — cette résolution peut arriver même si la branche n'est
jamais exécutée à l'exécution. La seule protection fiable est l'isolation complète dans une classe
séparée, jamais chargée du tout tant que l'appelant ne l'appelle pas explicitement (même motif que
`CadenceBlueMap` pour `BlueMapAPI`, crash fix #2 de la nuit précédente).

**Corrigé** : nouvelle classe `net.irisshaders.iris.vertices.sodium.SodiumVertexSerializers`
(`registerAll()`), les 4 `registerSerializer(...)` déplacés hors de `Iris.java`, qui ne référence plus
aucun type Sodium directement. Commit `a9647c2`. Compilation propre revérifiée. Rebuild + retest sur
le même lanceur isolé (Sodium toujours absent) : **ce crash précis a disparu**, le jeu progresse
beaucoup plus loin (336 lignes de log contre 91 avant, jusqu'à "Initializing game").

**Crash #2 (trouvé, PAS corrigé — vrai couplage architectural, pas une isolation simple)** :
```
Description: Initializing game
java.lang.NoClassDefFoundError: net/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkMeshFormats
	at net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.<init>(WorldRenderingSettings.java:37)
	at net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.<clinit>(WorldRenderingSettings.java:14)
	at net.irisshaders.iris.pipeline.VanillaRenderingPipeline.<init>(VanillaRenderingPipeline.java:30)
	at net.irisshaders.iris.pipeline.PipelineManager.<init>(PipelineManager.java:20)
	at net.irisshaders.iris.Iris.getPipelineManager(Iris.java:690)
	at net.irisshaders.iris.gui.option.IrisVideoSettings.getOverriddenShadowDistance(IrisVideoSettings.java:62)
	at net.irisshaders.iris.gui.option.IrisVideoSettings.<clinit>(IrisVideoSettings.java:51)
	at net.irisshaders.iris.config.IrisConfig.load(IrisConfig.java:178)
	at net.irisshaders.iris.config.IrisConfig.initialize(IrisConfig.java:72)
	at net.irisshaders.iris.Iris.onEarlyInitialize(Iris.java:846)
	at net.minecraft.client.Minecraft.<init>(Minecraft.java:433)
```
**Cause, différente et plus profonde que le crash #1** : `WorldRenderingSettings` déclare un champ
`private ChunkVertexType chunkVertexFormat;` — le TYPE DÉCLARÉ du champ lui-même est une interface
Sodium (`net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType`), initialisé
sans garde dans le constructeur (`chunkVertexFormat = ChunkMeshFormats.COMPACT;`, ligne 37) et exposé
via `getVertexFormat()`/`setVertexFormat(ChunkVertexType)`. Contrairement au crash #1 (une instruction
`NEW` dans un corps de méthode, contournable en isolant l'appel dans une classe séparée), un
DESCRIPTEUR DE CHAMP fait partie du layout de la classe et est résolu au chargement/liaison de CETTE
classe elle-même — aucune astuce d'isolation d'appel ne peut éviter ça tant que `WorldRenderingSettings`
garde ce champ typé Sodium. Et `WorldRenderingSettings.INSTANCE` est un singleton statique construit
sans condition dès qu'Iris s'initialise (`Iris.onEarlyInitialize`, appelé inconditionnellement au
`Minecraft.<init>`), donc aucun garde runtime en amont ne peut retarder ce chargement.

**C'est exactement le couplage dur déjà scopé plus haut dans ce document** (`vertices/sodium/` +
`vertices/sodium/terrain/`, « la partie la plus critique et la plus difficile à remplacer ») —
`WorldRenderingSettings` en est un exemple concret et non trivial : pour corriger proprement, il
faudrait soit (a) remplacer le champ par un type interne à Lanterne (pas Sodium) avec conversion vers/
depuis `ChunkVertexType` uniquement quand Sodium est présent, soit (b) rendre `chunkVertexFormat` de
type `Object`/générique et faire les casts uniquement dans le code déjà gardé par
`Compagnons.present(SODIUM)`. Les deux demandent d'auditer TOUS les appelants de
`getVertexFormat()`/`setVertexFormat()` (pas fait cette passe, budget de fork insuffisant) pour
vérifier qu'aucun ne suppose un `ChunkVertexType` non-null en dehors d'un contexte Sodium déjà garanti
présent. **PAS tenté** — correctif risqué à improviser en fin de session sans pouvoir auditer tous les
appelants ; documenté plutôt que forcé, comme demandé par la directive de cette tâche.

**Sodium remis dans `mods/` de la copie isolée après ce test** (le retrait était pour le test
uniquement). **Rien déployé sur le VPS ni `test 1` pour ce sujet précis** (Sodium reste présent
partout ailleurs) — seul le commit `a9647c2` (fix du crash #1, réel progrès, sans danger que Sodium
soit présent ou pas) est sur la branche `26.3`, jamais poussé.

**Prochaine étape concrète pour qui reprend ça** : auditer les appelants de
`WorldRenderingSettings.getVertexFormat()`/`setVertexFormat()`
(`grep -rn "getVertexFormat\|setVertexFormat"`) pour dimensionner le vrai correctif (a) ou (b)
ci-dessus, PUIS reprendre ce même test (lanceur isolé, Sodium absent) pour voir si un 3e crash
apparaît plus loin dans la chaîne — probable vu que `vertices/sodium/terrain/` (`XHFPModelVertexType`
etc.) a la même nature de couplage par TYPE DE CHAMP, pas seulement par appel de méthode.

## AUDIT — appelants de `WorldRenderingSettings.getVertexFormat()`/`setVertexFormat()`, verdict : trop risqué pour cette nuit (fork, 21/09 ~02h50)

Reprise de la « prochaine étape concrète » ci-dessus. Audit complet fait (`grep -rn "getVertexFormat\|setVertexFormat"`
sur tout `src/main/java`, 30 occurrences, triées manuellement), **aucun code modifié** — verdict honnête : correctif (a)/(b) trop risqué à improviser cette nuit, documenté ici pour la suite.

**Constat qui aggrave le diagnostic précédent** : `VanillaRenderingPipeline.java:34` (le constructeur du
pipeline de secours, PAS le pipeline shader) fait lui aussi, sans aucune garde :
```java
WorldRenderingSettings.INSTANCE.setVertexFormat(ChunkMeshFormats.COMPACT);
```
`VanillaRenderingPipeline` est la classe utilisée par défaut dès qu'aucun shaderpack n'est actif OU en
repli après un `catch (Throwable)` dans `Iris.createPipeline` — donc même le chemin « rendu vanilla sans
shaders » touche directement une classe Sodium (`ChunkMeshFormats`). Ça veut dire qu'un simple garde sur
le SEUL constructeur de `WorldRenderingSettings` (piste envisagée initialement) ne suffirait pas : il
faudrait AUSSI garder ce site d'appel, et tous les autres listés ci-dessous.

**Inventaire des appelants réels (hors mixins ciblant des classes Sodium, ceux-là ne se déclenchent
jamais si Sodium est absent — le mixin ne s'applique pas si sa cible ne charge pas)** :

| Fichier | Ligne(s) | Usage réel |
|---|---|---|
| `shaderpack/materialmap/WorldRenderingSettings.java` | 27, 37, 138-148 | déclaration du champ + constructeur (`ChunkMeshFormats.COMPACT` non gardé) + accesseurs |
| `pipeline/VanillaRenderingPipeline.java` | 34 | `setVertexFormat(ChunkMeshFormats.COMPACT)` dans le constructeur, **chemin de repli par défaut**, non gardé |
| `pipeline/IrisRenderingPipeline.java` | 232 | `setVertexFormat(...)` au chargement d'un shaderpack réel |
| `gl/state/ShaderAttributeInputs.java` | 36 | `.getVertexFormat().getVertexFormat()` — appel CHAÎNÉ : récupère le `ChunkVertexType` Sodium PUIS appelle SA PROPRE méthode `getVertexFormat()` (qui renvoie le vrai `net.minecraft.client.renderer.VertexFormat` vanilla) pour construire les attributs d'un shader réel — usage d'ENCODAGE réel, pas juste transmis |
| `pipeline/programs/ShaderCreator.java` | 72, 254, 292, 327 | même appel chaîné `.getVertexFormat().getVertexFormat()`, 4 sites, pour construire chaque programme shader (`createShader`/variantes) — usage d'ENCODAGE réel |

**Conclusion honnête** : ce n'est PAS un champ « juste stocké/transmis » — `ShaderAttributeInputs` et
les 4 sites de `ShaderCreator` utilisent le résultat pour construire le VRAI format de vertex GL utilisé
par les shaders actifs (y compris quand Sodium est présent et qu'un shaderpack tourne, ce qui est
CONFIRMÉ STABLE cette nuit — Complementary Reimagined, 80-124 FPS). Un correctif (a)/(b) demanderait de
toucher au minimum 5 fichiers, dont 2 (`ShaderAttributeInputs`, `ShaderCreator`) sur le chemin chaud de
construction des shaders réellement utilisés en jeu avec Sodium présent — donc un risque de régression
directe sur le rendu shader déjà validé par le patron, si le correctif est mal fait ou mal testé. Pas un
travail à improviser en fin de nuit sans pouvoir retester ensuite avec Sodium présent ET un shaderpack
actif (pas seulement au menu principal du lanceur jetable, mais en jeu, plusieurs minutes, comme la
leçon de migration Iris l'exige déjà pour tout changement touchant ce chemin).

**Piste de reprise recommandée pour la suite** (à faire de jour, avec plus de marge pour retester) :
créer une interface neutre côté Lanterne (ex. `fr.clubcitrouille.lanterne.compat.VertexFormatHandle`,
avec une méthode `net.minecraft.client.renderer.VertexFormat resolve()`), avec deux implémentations :
une qui délègue à un vrai `ChunkVertexType` Sodium (utilisée quand `Compagnons.present(SODIUM)`), une
qui renvoie directement un `VertexFormat` vanilla codé en dur équivalent à `ChunkMeshFormats.COMPACT`
(pour le cas Sodium absent). Remplacer le champ de `WorldRenderingSettings` par ce type neutre, adapter
les 5 sites d'appel un par un, RETESTER dans les DEUX configurations (Sodium présent + shaderpack actif
en jeu ; Sodium absent) avant de considérer ça terminé — pas seulement compiler.

Rien modifié dans le dépôt pour cette tâche (audit seul). Rien à compiler, rien à tester.

## RE-CONFIRMATION — verdict inchangé, une précision utile pour la reprise (fork, 21/09 ~03h05)

Repris ce même audit avec un œil neuf, hypothèse testée : une interface neutre minimale serait-elle
suffisante ? Vérifié par `javap` sur le vrai jar Sodium embarqué (`test 1/mods/sodium-neoforge-0.9.2+mc26.3.jar`,
jar imbriqué `META-INF/jarjar/net.caffeinemc.sodium-neoforge-0.9.2+mc26.3-mod.jar`) : l'interface
`ChunkVertexType` n'a que DEUX méthodes :
```java
public interface ChunkVertexType {
    VertexFormat getVertexFormat();
    ChunkVertexEncoder getEncoder();
}
```
Confirmé par grep exhaustif (`getVertexFormat()\.getEncoder\|WorldRenderingSettings.INSTANCE.getVertexFormat()`) :
**`getEncoder()` n'est jamais appelé** sur le résultat de `WorldRenderingSettings.INSTANCE.getVertexFormat()`
— seule `.getVertexFormat()` (renvoyant le `VertexFormat` vanilla) l'est, aux 5 sites déjà listés. Ça confirme
que l'interface neutre proposée (une seule méthode `resolve(): VertexFormat`) est bien suffisamment large,
pas besoin d'exposer plus.

Ça ne change PAS le verdict : `VanillaRenderingPipeline.java:34` reste le vrai blocage de fond — sans Sodium,
il n'existe aucune valeur `VertexFormat` "équivalente" évidente à `ChunkMeshFormats.COMPACT` sans revalider
manuellement le format vanilla exact attendu par `ShaderAttributeInputs`/`ShaderCreator` (risque de mesh
corrompu si mal choisi, pas juste un crash — un bug plus sournois). Les 2 mixins `MixinChunkMeshFormats`/
`MixinRenderSectionManager` (retour direct de `ChunkVertexType`) sont sans risque car dans un package mixin
(`mixins.iris.compat.sodium.json`) qui ne s'applique de toute façon jamais sans Sodium (cibles Sodium
introuvables) — pas un obstacle supplémentaire, juste une confirmation.

**Verdict final de cette passe : ne pas implémenter cette nuit.** Le risque reste concentré sur `ShaderAttributeInputs`
et `ShaderCreator` (chemin chaud du rendu shader confirmé stable avec Sodium+shaderpack ce soir), pas sur une
simple substitution de type. Aucun fichier modifié, rien compilé, rien testé — audit seul, comme la passe précédente.

## ÉTAPE 7 — Vulkan : test sur un VRAI fichier de shaderpack (pas un shader de test écrit à la main) (fork, 21/09 ~03h50-04h07)

Toutes les preuves Vulkan précédentes (fragment 3532 octets, puis vertex+fragment 5576+3428 octets) portaient
sur un shader "gbuffers" minimal écrit à la main dans `ShadercProbeSelfCheck.java` — limite honnêtement
documentée. Cette passe teste pour de vrai `shaders/world0/gbuffers_terrain.{vsh,fsh}` du shaderpack
**ComplementaryReimagined_r5.9.zip** (`test 1/shaderpacks/`), le point d'entrée le plus utilisé réellement en
jeu.

**Nouveau code** : `ShadercProbe.testRealPackVanillaInClient(Path shadersRoot, String vertexPath, String fragmentPath)`
réutilise `net.irisshaders.iris.shaderpack.include.{IncludeGraph,IncludeProcessor,AbsolutePackPath}` — déjà
migrés cette nuit, jamais utilisés jusqu'ici dans ce laboratoire — pour résoudre les VRAIS `#include` du
shaderpack (contrairement à `testVanillaInClient`/`testReal` qui exigent un fichier autonome). Gate séparé
(`LANTERNE_SHADERC_REALPACK_TEST=<dossier shaders/ extrait>`), jamais actif sans variable d'environnement.

**Premier test empirique (commit `8b256d4`)** : `#include` résolus avec succès (10446 lignes produites pour
chaque étage — la richesse réelle de Complementary Shaders, pas un fichier jouet). Échec précis obtenu :
```
[SHADERC] Echec de patchVanilla+reblock (contexte client, device GPU requis) :
net.irisshaders.iris.gl.shader.ShaderCompileException: probe-vanilla-reblocked:
java.lang.IllegalArgumentException: Unparsed preprocessor directives such as '#define' may not be
present at this stage of shader processing!
```
Cause exacte : le flatten `#include` préserve les lignes `#define VERTEX_SHADER`/`#ifdef ...` littéralement
(comportement voulu de `IncludeProcessor`, qui ne fait QUE de la substitution de lignes) — mais
`TransformPatcher.patchVanilla` attend un GLSL DÉJÀ préprocessé (comme le fait réellement le pipeline Iris
avant d'appeler `patchVanilla` en jeu), pas les directives C brutes.

**Correctif (commit `3baf822`)** : ajout d'un passage par `JcppProcessor.glslPreprocessSource` (déjà prouvé
fonctionnel sur le composite via `testReal` plus tôt cette nuit) avant `patchVanilla`+`UniformReblocker`+shaderc
— exactement l'étape manquante. Compile sain.

**Re-test empirique : INCONCLUANT, pas par un défaut de code.** Une première instance de lanceur client
isolée (`C:\lanterne-client-vulkan-test-4\`) a bien reproduit l'échec ci-dessus (preuve du bug AVANT
correctif). Une seconde instance fraîche (`C:\lanterne-client-vulkan-test-5\`, jar rebuild avec le correctif
JcppProcessor) a été lancée pour confirmer le fix, mais s'est bloquée ~17 minutes sur le chargement des
providers de police unicode (`UnihexProvider`, étape normalement rapide) sans jamais progresser jusqu'au menu
principal ni exécuter le moindre log `[SHADERC-TEST]` — 8 process `java.exe` tournaient simultanément sur la
machine à ce moment (plusieurs autres forks actifs cette nuit), c'est la cause la plus probable (contention
CPU/IO), pas un bug du correctif — le même setup avait fonctionné sans accroc quelques minutes plus tôt sur
`test-4` avec un nombre de process similaire, donc **pas une certitude non plus**, juste l'hypothèse la plus
probable. Processus laissé actif (pas de kill, `tasklist` vérifié, jamais de kill global) pour qui veut
vérifier son état plus tard.

**État pour la reprise** : le correctif JcppProcessor est logiquement sain (même mécanisme que `testReal`,
déjà prouvé ailleurs) et compilé, mais SANS confirmation empirique fraîche sur le vrai fichier gbuffers_terrain
avec le fix appliqué — ne pas présenter comme résolu. Prochaine étape triviale pour qui reprend : relancer
`C:\lanterne-client-vulkan-test-5\launch7_v5.bat` (ou une nouvelle copie si celle-ci reste bloquée) à un moment
où moins de process concurrents tournent, puis `grep "SHADERC-TEST" logs/latest.log`. Si l'échec persiste même
isolé, ce sera un vrai nouveau signal à creuser (pas juste de la contention).

Commits `8b256d4` (nouvelle méthode de test) et `3baf822` (fix JcppProcessor) sur `26.3`, jamais poussés. Rien
déployé sur le VPS ni `test 1`.

### ÉTAPE 8 — reconfirmation empirique sur gbuffers_terrain réel, deux nouveaux blocages précis (21/09 ~04h10)

Reconfirmé sur machine désencombrée (le blocage précédent était bien de la contention, pas un bug :
menu atteint en ~40s au lieu de 17 min bloqué). Résultat réel, log `logs/latest.log` du lanceur
`C:\lanterne-client-vulkan-test-5\` :

- Le fix JcppProcessor (étape 7, commit `3baf822`) résout bien le problème `#define` non préprocédé.
- Sur le VRAI fichier `gbuffers_terrain` (10446 lignes après résolution des `#include`), deux
  problèmes NOUVEAUX et distincts apparaissent, jamais vus sur les shaders de test synthétiques :
  1. `UniformReblocker` laisse encore 3 uniforms non capturés (`'non-opaque uniforms outside a
     block'` persiste après reblock, lignes 143+ du fichier patché) — couverture incomplète du
     parseur sur un fichier de cette taille/complexité.
  2. `OutOfMemoryError: Out of stack space` sur le fragment (99921 caractères) — récursion non
     bornée quelque part dans la chaîne jcpp/UniformReblocker, jamais atteinte sur des shaders
     courts.

**Prochaine étape concrète pour qui reprend** : (a) auditer `UniformReblocker.java` pour trouver
pourquoi certains uniforms échappent à sa détection sur un gros fichier (regex trop simple ?
multi-déclaration `uniform vec3 a, b;` ? uniform dans un bloc conditionnel `#ifdef` déjà résolu par
jcpp mais mal replacé ?) ; (b) convertir toute récursion dans `JcppProcessor`/`UniformReblocker` en
itération, ou augmenter `-Xss` pour le thread concerné, pour éliminer le stack overflow sur les
gros fichiers réels.

Aucune modification de code cette passe (reconfirmation empirique uniquement). Commits inchangés
depuis l'étape 7.

### ÉTAPE 9 — recensement exhaustif des champs de classe typés Sodium (21/09 ~05h35)

Recherche systématique (pure documentation, aucune modification de code) sur tout le code Iris
migré (`net.irisshaders.iris.*` + `fr.clubcitrouille.lanterne.client.sodium.Graft`) pour
compléter le travail des forks précédents, qui n'avaient audité qu'un seul champ connu
(`WorldRenderingSettings.chunkVertexFormat`).

**Méthode** : 56 fichiers référencent `net.caffeinemc.mods.sodium.*` au total. 27 d'entre eux ne
sont pas des mixins ciblant directement une classe Sodium (ces 21 mixins-là restent hors sujet ici
— déjà documentés comme sans risque : la classe cible ne charge jamais si Sodium est absent, donc
le mixin ne s'applique simplement pas). Sur ces 27 fichiers, pour chaque import
`net.caffeinemc.mods.sodium.*`, recherche des déclarations de CHAMP de classe (pas paramètre de
méthode, pas variable locale) utilisant ce type — d'abord par un motif strict sur les modificateurs
(`private`/`public`/`protected`), puis par un motif large sur toute occurrence du nom de classe en
position de type suivie d'un identifiant et d'un `;`/`=`, pour ne rien manquer (multi-déclaration,
visibilité package-private, etc.).

**Résultat : UN SEUL champ de classe trouvé dans tout le code migré dont le TYPE référence
directement une classe Sodium :**

- `net/irisshaders/iris/shaderpack/materialmap/WorldRenderingSettings.java:27` —
  `private ChunkVertexType chunkVertexFormat;` (déjà documenté à l'étape précédente : couplage
  réel sur 5 fichiers/sites d'usage, correctif proposé mais jugé trop risqué cette nuit sans test
  humain en vol).

Les autres occurrences trouvées par le motif large sont toutes des VARIABLES LOCALES à l'intérieur
de méthodes (pas des champs) :
- `ShadowRenderer.java:482` — `SodiumWorldRenderer sodiumWorldRenderer = …` (locale, méthode de rendu des ombres)
- `ShadowRenderer.java:475` — `ChunkRenderMatrices playerMatrices = …` (locale, même méthode)
- `IrisInternalUniforms.java:34` — `FogParameters fog = …` (locale, méthode de calcul de fog)

Ces trois variables locales ne sont PAS un risque structurel au sens de la nuit (pas de
`NoClassDefFoundError` au chargement de classe) : une variable locale n'est résolue par la JVM
qu'au moment où le bytecode de la méthode s'exécute réellement, contrairement à un champ dont le
TYPE fait partie du layout de la classe. Ces trois méthodes appellent déjà, avant ces lignes, des
méthodes d'extension (`sodium$getWorldRenderer()`, `sodium$getMatrices()`,
`sodium$getFogParameters()`) qui supposent Sodium présent de toute façon — elles font donc partie
du même chemin déjà connu comme dépendant de Sodium, pas une découverte nouvelle.

**Conclusion pour la reprise de jour** : le couplage structurel dur (au niveau du TYPE d'un champ,
donc bloquant au chargement de classe même si Sodium absent) se limite à ce seul champ
`chunkVertexFormat` déjà scopé (5 fichiers/sites d'usage réels : `ShaderAttributeInputs.java:36`,
4 sites de `ShaderCreator.java`, plus `VanillaRenderingPipeline.java:34`). Il n'y a pas d'autre
« bombe à retardement » de ce type cachée ailleurs dans le code migré — la vraie prochaine étape
reste bien celle déjà documentée à l'étape précédente (RE-CONFIRMATION), pas une nouvelle
recherche exploratoire.

Aucun fichier de code modifié, rien compilé (recherche pure), rien commité côté code — seule cette
documentation a été ajoutée.
