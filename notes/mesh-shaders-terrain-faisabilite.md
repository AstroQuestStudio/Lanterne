# Mesh shaders pour le rendu de terrain — faisabilité

> Enquête du 19-20 septembre 2026, même méthode que `notes/dlss-panama.md`, `notes/fsr2-faisabilite.md`
> et `notes/immersive-portals-faisabilite.md` : **VÉRIFIÉ** veut dire qu'une commande a été lancée,
> qu'une source primaire (`javap -p -c -constants` sur le VRAI jar patché,
> `build/moddev/artifacts/minecraft-patched-26.3.0.3-beta.jar`, jamais `.mcsrc/`) a été lue et citée,
> ou qu'un programme autonome a réellement tourné et que sa sortie est reproduite ; **SUPPOSÉ** est
> signalé comme tel.
>
> Point de départ obligatoire de cette enquête : une passe précédente, cette même nuit, a déjà
> confirmé par bytecode que `VK_EXT_mesh_shader`/`VK_NV_mesh_shader` sont **détectés et activés** à
> la création du device Vulkan (`mixin/VulkanFeatureSetsMixin.java`), mais que **rien dans ce dépôt
> ne les utilise** pour soumettre de la géométrie — zéro pipeline construit avec un stage mesh
> shader, zéro fichier `.msh`/`.tsh`, zéro référence ailleurs dans le code. Terrain vierge, pas
> l'extension d'un système existant. Cette enquête part de là pour établir précisément ce qu'un vrai
> pipeline exigerait, et où en est réellement la faisabilité.

---

## Verdict

**Pas de pipeline mesh shader pour le terrain cette passe — refonte de plusieurs semaines,
confirmée par lecture de bytecode, pas une intuition.** Deux points bloquent à eux seuls
l'idée d'un chantier d'une soirée :

1. L'abstraction `RenderPipeline`/`GlslCompiler`/`VulkanRenderPipeline` de ce moteur n'a
   **aucune** notion de stage mesh/task aujourd'hui — ni dans l'énumération des types de
   nuanceur, ni dans le compilateur GLSL→SPIR-V, ni dans la construction native du pipeline
   Vulkan. Les trois fichiers à modifier pour l'ajouter sont des fichiers **partagés, chauds,
   traversés par CHAQUE nuanceur et CHAQUE pipeline du jeu** — vanilla et Lanterne confondus, y
   compris le terrain lui-même. Les toucher sans pouvoir lancer de client ce soir pour vérifier
   l'absence de régression serait exactement le risque que la mission interdit.
2. Même une fois cette plomberie construite, il manquerait encore l'essentiel : un nouveau
   format de sortie de maillage en meshlets (`SectionCompiler`/`Greedy` produisent aujourd'hui
   un tampon de sommets/indices classique, pas des groupes de triangles avec bornes de culling
   propres), et le branchement dans le chemin de dessin de terrain réel
   (`LevelRenderer`/`ChunkSectionsToRender`, fichiers **vanilla**) — le système que le rapport
   Guet de ce soir (commit `98ec8b1`) qualifie déjà de « le plus central et le plus démontré
   fragile » du moteur.

**Ce qui a été livré à la place, vérifié réellement ce soir, hors du jeu :**

- `tools/EssaiMeshShader.java` — une épreuve autonome (même patron que `tools/EssaiNgx.java`),
  compilée et **exécutée deux fois** sur cette machine, qui répond avec des faits, pas des
  suppositions, aux deux questions qui décidaient si ce chantier valait même la peine d'être
  entamé un jour : l'outillage de ce dépôt sait-il compiler du mesh shader, et cette machine
  sait-elle en exécuter. Voir §2.
- Cette note, avec la liste précise et hiérarchisée par risque de ce qu'il resterait à
  construire — §4 — pour qu'une prochaine passe avec un client sous la main sache exactement où
  reprendre.

---

## 1. Comment le maillage de section de chunk est produit aujourd'hui

Repris du rapport Guet (`client/terrain/Guet.java`, commit `98ec8b1`, javadoc de classe) pour ne
pas redécouvrir ce qui l'était déjà cette même nuit :

- `SectionCompiler`/`CompiledSectionMesh` (vanilla) compilent chaque section de chunk en un
  tampon de sommets et d'indices classique — une « soupe de triangles », pas des meshlets.
- `LevelRenderer.extractSectionDrawGroups` regroupe les sections visibles partageant un même
  tampon GPU en `ChunkSectionsToRender.DrawIndirect` (`LevelRenderer.ChunkDrawGroup`).
- `ChunkSectionsToRender.DrawIndirect.render` appelle `RenderPass.drawIndexedIndirect`, dont
  l'implémentation Vulkan réelle (`VulkanRenderPass.drawIndexedIndirect`) appelle
  `VK12.vkCmdDrawIndexedIndirect` — **confirmé actif** dans l'arbre d'appel d'une session en jeu
  réelle (pas seulement câblé), voir Guet.

Un pipeline mesh shader pour le terrain remplacerait cette chaîne (soupe de triangles +
`vkCmdDrawIndexedIndirect`) par : un maillage en meshlets produit par le compilateur de section,
un `.msh` (et optionnellement un `.tsh`) qui les consomme, et `vkCmdDrawMeshTasksEXT` à la place
de `vkCmdDrawIndexedIndirect`. Rien de tout cela n'existe aujourd'hui.

---

## 2. Ce que l'outillage et le matériel de ce dépôt supportent réellement — VÉRIFIÉ ce soir

`tools/EssaiMeshShader.java` : un programme autonome, **hors Minecraft**, qui ne crée qu'une
`VkInstance` jetable (détruite en fin d'exécution) et n'utilise le compilateur `shaderc` que pour
du texte → SPIR-V — aucun `VkDevice` du jeu, aucune fenêtre, aucun pipeline, aucune commande
soumise. Compilé et exécuté deux fois ce soir (le premier essai a révélé un bug de la sonde
elle-même — pile trop petite pour la liste d'extensions d'un GPU discret — corrigé avant le
second essai, dont la sortie est reproduite ici en intégralité) :

```
=== Partie A : le compilateur (shaderc embarque par ce depot) sait-il produire du SPIR-V mesh/task ? ===
  [OK] fragment (controle negatif, deja supporte par GlslCompiler) -> 572 octets de SPIR-V.
  [OK] task (GL_EXT_mesh_shader) -> 412 octets de SPIR-V.
  [OK] mesh (GL_EXT_mesh_shader) -> 1684 octets de SPIR-V.

=== Partie B : CETTE carte / CE pilote exposent-ils reellement VK_EXT_mesh_shader ? ===
  AMD Radeon(TM) Graphics : VK_EXT_mesh_shader ABSENT de la liste d'extensions du pilote (148 extensions annoncees) — non interroge (meme discipline que Sonde.java, jamais de pNext sur une extension non annoncee).
  NVIDIA GeForce RTX 3080 Laptop GPU : VK_EXT_mesh_shader present dans la liste d'extensions du pilote.
    meshShader=true taskShader=true meshShaderQueries=true multiviewMeshShader=true
    maxMeshOutputVertices=256 maxMeshOutputPrimitives=256 maxMeshWorkGroupInvocations=128 maxPreferredMeshWorkGroupInvocations=32 maxMeshOutputMemorySize=32768 maxTaskWorkGroupInvocations=128 maxPreferredTaskWorkGroupInvocations=32

=== Verdict ===
Compilateur (toolchain shaderc de ce depot) : OK
Materiel (cette machine)                    : OK
=> Rien, au niveau outil/materiel, n'empeche un pipeline mesh shader sur cette machine.
```

**Ce que cela établit, VÉRIFIÉ :**

- Le `shaderc` embarqué par ce dépôt (`lwjgl-shaderc-3.4.3`, le MÊME jar que
  `GlslCompiler`/`PipelineBuilder` utilisent en jeu — pas une version différente) compile bien du
  GLSL utilisant `#extension GL_EXT_mesh_shader : require`, aussi bien pour le stage `task` que
  `mesh`, avec `shaderc_target_env_vulkan`/`shaderc_env_version_vulkan_1_2` et
  `shaderc_spirv_version_1_5`. Ce n'était pas garanti a priori : une version de `shaderc` liée à
  un `glslang` trop ancien aurait pu refuser l'extension.
- Le second `VkPhysicalDevice` de cette machine (le GPU discret, celui que le moteur choisit pour
  le rendu réel — le GPU intégré AMD n'annonce même pas l'extension) reporte
  `meshShader=true`/`taskShader=true`/`meshShaderQueries=true`/`multiviewMeshShader=true`, avec
  des limites concrètes (256 sommets et 256 primitives par meshlet au maximum, groupe de travail
  mesh préféré de 32 invocations, 32 Kio de mémoire de sortie par meshlet).
- Ceci **corrobore indépendamment**, par une instance Vulkan séparée et une sonde écrite cette
  nuit, la lecture de bytecode antérieure de `VulkanFeatureSetsMixin` (287 extensions relevées
  dans un journal de lancement réel, dont `VK_EXT_mesh_shader`) — deux méthodes différentes,
  même conclusion. Le matériel n'est donc plus une inconnue de cette enquête.

**Ce que cela n'établit PAS :** que le moteur, tel qu'il est aujourd'hui, peut exécuter ce
pipeline — voir §3, la nuance qui manquait encore.

---

## 3. Ce que l'abstraction `RenderPipeline` de ce moteur supporte aujourd'hui — vérifié par bytecode

Chaque ligne de ce tableau est une lecture `javap` directe sur le vrai jar patché, pas une
supposition :

| Composant | Constat vérifié | Portée du risque si modifié |
|---|---|---|
| `api.pipeline.ShaderType` (enum) | **Seulement** `VERTEX`/`FRAGMENT`, avec extensions de fichier `.vsh`/`.fsh` codées dans le constructeur de l'enum | Additif si on ajoute des valeurs — rien n'existe aujourd'hui qui itère sur `ShaderType.values()` en supposant exactement deux entrées (vérifié : `PipelineBuilder`/`VulkanConst` utilisent l'enum lui-même, pas sa taille) |
| `api.pipeline.RenderPipeline.Builder` | Seulement `withVertexShader(...)`/`withFragmentShader(...)` ; pas de méthode générique par `ShaderType` | Additif : ajouter `withMeshShader`/`withTaskShader` n'oblige à rien changer aux méthodes existantes |
| `frontend.shaders.GlslCompiler.compileToSpv` | Calcule `isFragment = (shaderType == ShaderType.FRAGMENT)` — un **ternaire binaire codé en dur**, pas un switch sur l'enum — pour choisir le `shaderc_shader_kind` | **Risque élevé** : cette méthode compile LITTÉRALEMENT chaque nuanceur du jeu, terrain compris. Un ternaire binaire ne peut pas simplement recevoir un troisième cas — il faut le réécrire en vraie logique de dispatch, dans un fichier chaud, sans client pour vérifier une régression sur tout le reste du jeu |
| `backend.vulkan.VulkanConst.toVk(ShaderType)` | Un vrai `switch` (table `$SwitchMap` générée par le compilateur), aujourd'hui à deux cas | Risque moyen : extension idiomatique d'un switch existant, mais fichier partagé par tous les pipelines |
| `backend.vulkan.VulkanRenderPipeline.compile(VulkanDevice, CreateInfo)` | Construit **trois** `VkGraphicsPipelineCreateInfo` (`withDepthStencilPipeline`, `withDepthPipeline`, `withoutDepthPipeline` — trois appels `vkCreateGraphicsPipelines` distincts, vérifiés aux trois sites d'appel), chacun avec `pVertexInputState`/`pInputAssemblyState` **toujours** renseignés depuis `CreateInfo.vertexBuffers()`/`primitiveTopology()` | **Risque le plus élevé de la plomberie** : la spec Vulkan ignore ces deux champs pour un pipeline mesh shading, mais ce code ne le sait pas — il faudrait une branche conditionnelle dans les TROIS constructions, dans le fichier qui compile CHAQUE pipeline du jeu, terrain compris |
| `mixin.VulkanFeatureSetsMixin` | Active `VK_EXT_mesh_shader`/`VK_NV_mesh_shader` dans `ppEnabledExtensionNames` à `vkCreateDevice` — **mais son propre Javadoc précise que le troisième argument de `FeatureSet` (les `VulkanFeature` à activer) reste un ensemble VIDE** : aucun bit `meshShader`/`taskShader` de `VkPhysicalDeviceMeshShaderFeaturesEXT` n'est demandé au device | Nuance importante trouvée cette passe : le nom de l'extension étant actif ne suffit pas — la spec Vulkan exige que les *feature bits* soient explicitement activés au `pNext` de `vkCreateDevice` pour qu'un pipeline utilisant ces stages soit valide. **Même avec ce mixin, le `VkDevice` du jeu aujourd'hui n'a PAS les bits activés.** Risque moyen : il existe déjà un patron à imiter (`VulkanFeatureSets.VK10_FEATURES_STRUCT` etc.), mais ça touche la création du device, exécutée à chaque lancement |

**Conclusion de ce tableau** : ce n'est pas une case à cocher, c'est cinq fichiers à modifier,
dont deux comptent parmi les plus chauds et les plus partagés de tout le moteur
(`GlslCompiler.compileToSpv`, `VulkanRenderPipeline.compile`) — exactement le genre de fichier
que ce dépôt a déjà vu casser des choses en cascade ce soir sur d'autres chantiers Vulkan, une
fois testé en jeu.

---

## 4. La liste précise de ce qu'il resterait à construire, par ordre d'attaque recommandé

Pensé pour qu'une prochaine passe avec un client sous la main puisse vérifier chaque étape
indépendamment avant de passer à la suivante — jamais tout d'un bloc :

1. **Plomberie additive sans risque** (`ShaderType` + `RenderPipeline.Builder`) — ajouter
   `MESH`/`TASK` à l'enum avec extensions `.msh`/`.tsh`, et `withMeshShader`/`withTaskShader` au
   `Builder`. Compile-vérifiable, zéro effet sur l'existant tant que rien ne les invoque.
2. **`VulkanConst.toVk`** — étendre le switch avec `EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT`/
   `VK_SHADER_STAGE_TASK_BIT_EXT` (confirmés présents dans les bindings LWJGL de ce dépôt, voir
   §2). Toujours sans effet tant que rien ne construit de pipeline avec ces stages.
3. **Activer réellement les *feature bits*** dans `VulkanFeatureSetsMixin` (ou un mixin sœur) —
   déclarer le `VulkanPNextStruct` pour `VkPhysicalDeviceMeshShaderFeaturesEXT`
   (`meshShader(true)`, `taskShader(true)`), suivant le patron déjà établi par
   `VulkanFeatureSets.VK10_FEATURES_STRUCT`. Vérifiable au lancement : le journal
   `Enabling optional FeatureSet […]` déjà présent (voir la Javadoc du mixin) suffit à confirmer
   l'activation sans instrumentation neuve.
4. **`GlslCompiler.compileToSpv`** — remplacer le ternaire binaire par un vrai dispatch sur
   `ShaderType` (switch ou table), pour permettre `shaderc_glsl_mesh_shader`/
   `shaderc_glsl_task_shader` (confirmés fonctionnels par `tools/EssaiMeshShader.java`, §2). **La
   première étape qui exige un client réel pour vérifier l'absence de régression** — compiler à
   nouveau chaque nuanceur vanilla existant après ce changement, sur un vrai lancement, avant de
   continuer.
5. **`VulkanRenderPipeline.compile`** — rendre `pVertexInputState`/`pInputAssemblyState`
   conditionnels à la présence d'un stage mesh, dans les trois constructions de
   `VkGraphicsPipelineCreateInfo`. Deuxième étape à haut risque, à vérifier isolément (un pipeline
   vertex/fragment ordinaire doit continuer à fonctionner à l'identique après ce changement).
6. **Un pipeline mesh shader isolé, jamais branché sur le terrain** — construit à la main
   (`GpuDevice.compilePipeline`, jamais le registre vanilla — même patron que
   `Gbuffer`/`Accumulate`/`PortailPrototype`, voir `notes/immersive-portals-faisabilite.md` §7.1),
   dessinant UN triangle dans une `GpuTextureView` séparée, hors du rendu de niveau réel. C'est
   l'équivalent en jeu de ce que `tools/EssaiMeshShader.java` a prouvé hors jeu ce soir — la
   première fois que ce moteur (pas juste le pilote) exécuterait réellement
   `vkCmdDrawMeshTasksEXT`. Sources GLSL de départ : celles de `tools/EssaiMeshShader.java`,
   déjà vérifiées comme compilant avec ce même `shaderc`.
7. **Le nouveau format de sortie en meshlets** — le vrai cœur algorithmique, sans précédent dans
   ce dépôt : regrouper les triangles produits par `SectionCompiler`/`Greedy` en meshlets (à
   dimensionner confortablement sous les limites mesurées §2 : 256 sommets/256 primitives max sur
   cette carte — des meshlets de 64 ou 128 seraient un choix prudent et classique), chacun avec sa
   propre borne de culling (boîte englobante, cône de normales). Aucun module de ce dépôt ne fait
   déjà quelque chose de comparable.
8. **Un `.tsh` de culling amont**, optionnel mais recommandé une fois 7 posé — teste chaque
   meshlet contre le frustum (et l'occlusion, en écho à `notes/hiz-occlusion-entites.md`) avant
   d'émettre son groupe de travail mesh.
9. **Le branchement dans le chemin réel** — une route alternative dans
   `LevelRenderer`/`ChunkSectionsToRender` (fichiers **vanilla**), derrière un réglage, avec repli
   garanti sur le chemin `vkCmdDrawIndexedIndirect` actuel. La dernière étape, et la plus
   dangereuse : c'est le système que Guet qualifie déjà, cette même nuit, de « le plus central et
   le plus démontré fragile » du moteur.

---

## 5. Estimation honnête

**Plusieurs semaines**, pas une soirée ni quelques passes — confirmé, pas supposé, par la lecture
de bytecode elle-même :

- Deux des fichiers à modifier (`GlslCompiler.compileToSpv`, `VulkanRenderPipeline.compile`) sont
  des points de passage partagés par CHAQUE nuanceur et CHAQUE pipeline du jeu — les modifier
  correctement exige, à chaque étape, un vrai lancement pour vérifier l'absence de régression sur
  tout le reste du rendu, pas seulement sur le terrain.
- Le format de sortie en meshlets (§4.7) est un algorithme neuf, sans le moindre précédent dans
  ce dépôt — comparable en ampleur à une phase de `notes/moteur-rendu-maison.md`, document qui
  qualifie déjà sa phase la plus proche en difficulté (« Occlusion par graphe de visibilité entre
  sections ») de phase la plus dure du document entier.
- L'étape finale (§4.9) touche directement le système que ce dépôt lui-même vient de qualifier,
  cette même nuit, de plus fragile aux tests réels en jeu — trois bogues Vulkan réels découverts
  en cascade sur d'AUTRES chantiers de rendu ce soir, chacun uniquement visible une fois lancé.

Rien de tout cela ne s'improvise sans un client à disposition pour vérifier chaque étape — exactement
la situation de cette passe.

---

## Ce qui a été fait cette passe

- Relecture du rapport Guet (`client/terrain/Guet.java`, commit `98ec8b1`) pour ne pas
  redécouvrir la chaîne de dessin de terrain actuelle.
- `javap -p -c -constants` sur le vrai jar patché
  (`build/moddev/artifacts/minecraft-patched-26.3.0.3-beta.jar`) pour : `ShaderType`,
  `RenderPipeline.Builder`, `PipelineBuilder`, `GlslCompiler.compileToSpv`, `VulkanConst.toVk`,
  `BackendRenderPipeline.CreateInfo`/`CreateInfo.Shader`, `VulkanRenderPipeline` (dont son unique
  méthode publique de compilation, `compile(VulkanDevice, CreateInfo)`, et ses trois sites
  `vkCreateGraphicsPipelines`).
- Relecture de `mixin/VulkanFeatureSetsMixin.java` pour préciser, au-delà de ce que la mission
  savait déjà, la nuance entre « extension activée » et « feature bits activés » (§3).
- Inspection des bindings LWJGL réellement en cache Gradle de ce dépôt (`lwjgl-3.4.3`,
  `lwjgl-vulkan-3.4.3`, `lwjgl-shaderc-3.4.3`) pour confirmer que `shaderc_glsl_mesh_shader`/
  `shaderc_glsl_task_shader` et `EXTMeshShader`/`vkCmdDrawMeshTasksEXT` existent bien dans les
  versions que ce projet utilise réellement — pas une version LWJGL différente.
- Écrit, compilé et **exécuté deux fois** `tools/EssaiMeshShader.java` (le second essai après
  correction d'un bug de dimensionnement de pile dans la sonde elle-même) — sortie intégrale en
  §2. Processus totalement séparé du client Minecraft, sa propre `VkInstance` jetable, détruite en
  fin d'exécution.
- Aucun fichier de `src/main/java` modifié. Aucun mixin écrit. Aucun pipeline construit dans le
  jeu. Aucun client Minecraft lancé.
- `git status` avant et après pour confirmer que seuls `tools/EssaiMeshShader.java` (nouveau) et
  ce fichier de note (nouveau) changent.

---

## Ce qu'il faudrait pour lever le blocage

Voir §4 pour l'ordre d'attaque précis. En résumé : trois passes additives sans risque (§4.1-3),
puis deux passes qui exigent chacune un vrai client pour vérifier l'absence de régression sur le
reste du rendu (§4.4-5), puis une preuve isolée hors du rendu de terrain (§4.6) avant même
d'envisager l'algorithme de meshlets (§4.7-8) et le branchement réel (§4.9) — la seule étape qui
engage vraiment le rendu de terrain, et seulement une fois tout le reste prouvé indépendamment.

Tant que ces conditions ne sont pas remplies, ce chantier **n'existe pas**, au sens où ce dépôt
l'entend — et c'est pour cela que rien n'a été branché ce soir : un module non mesuré, non vu en
jeu, n'existe pas.
