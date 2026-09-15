# DLSS par Project Panama — enquête et verdict

> Rédigé le 15 septembre 2026. Machine d'enquête : Windows 11 26200, NVIDIA GeForce RTX 3080
> Laptop, JDK Temurin 25.0.4.1+1-LTS (`C:/Users/trufa/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2`),
> celui-là même que Gradle emploie pour ce dépôt.

Ce document distingue systématiquement **VÉRIFIÉ** (une commande a été lancée, une sortie a été
lue, elle est reproduite ici) de **SUPPOSÉ**.

---

## Verdict en une phrase

**La conclusion précédente du dépôt — « il faut un pont JNI compilé en C++ » — est fausse.**
Le chargeur NGX que le pilote NVIDIA installe déjà sur cette machine est une **DLL** qui exporte
les 75 fonctions `NVSDK_NGX_*` sous leurs **noms C non décorés**, et Panama les résout et en fait
des `MethodHandle` sans qu'une ligne de C++ soit écrite.

**Mais cela ne suffit pas à faire tourner DLSS**, et les trois obstacles qui restent sont d'une
autre nature que celui qu'on croyait :

1. le modèle `nvngx_dlss.dll` **n'est pas installé par le pilote** (vérifié) et doit venir
   d'ailleurs ;
2. l'objet `NVSDK_NGX_Parameter` n'est **pas** une API C : c'est une **classe C++ à table
   virtuelle**, et Panama doit faire la répartition à la main ;
3. le public reste plafonné à Vulkan.

---

## 1. Le SDK NGX est-il joignable par Panama ?

### 1.a — Ce que le pilote installe réellement sur cette machine

`_nvngx.dll` n'est **pas** dans `C:\Windows\System32` (vérifié : `os.listdir` sur les 4 944 entrées
du dossier, aucune ne contient `ngx`). Le pilote récent le garde dans le magasin de pilotes, sous
`nvaci.inf` — « NVIDIA Application Compute Interface » :

```
C:\Windows\System32\DriverStore\FileRepository\nvaci.inf_amd64_b9b853632e3b644f\
    1 426 152   _nvngx.dll          <- le chargeur NGX
      488 824   nvngx.dll
    9 299 624   nvngx_dlssg.dll     <- génération d'images, PAS la super-résolution
    1 045 736   nvngx_update.exe
    5 484 728   libnvidia-ngx.so.1
```

Un balayage complet de `C:\Windows\System32`, `C:\Program Files`, `C:\Program Files (x86)` et
`C:\ProgramData` (86 fichiers trouvés) donne **un seul** `nvngx_dlss.dll` sur toute la machine :

```
   54 779 504   C:\Program Files\Unity\Hub\Editor\6000.5.0f1\Editor\nvngx_dlss.dll
```

— c'est-à-dire une copie apportée par **Unity**, pas par le pilote. **VÉRIFIÉ.**

> **Conséquence directe :** le fichier que `Deep.libraryPresent()` cherche dans
> `config/lanterne/` est bien celui qui manque, et il manquera sur toutes les machines. Le mod ne
> peut pas compter dessus sans le faire venir.

### 1.b — Les exports de `_nvngx.dll`

Table d'export lue directement dans l'en-tête PE (script Python, aucun outil externe) : **75
symboles, x64, noms C non décorés**, dont les seize qui nous intéressent :

```
NVSDK_NGX_VULKAN_Init                    NVSDK_NGX_VULKAN_AllocateParameters
NVSDK_NGX_VULKAN_Init_Ext                NVSDK_NGX_VULKAN_DestroyParameters
NVSDK_NGX_VULKAN_Init_Ext2               NVSDK_NGX_VULKAN_GetCapabilityParameters
NVSDK_NGX_VULKAN_Init_ProjectID          NVSDK_NGX_VULKAN_GetParameters
NVSDK_NGX_VULKAN_Init_ProjectID_Ext      NVSDK_NGX_VULKAN_GetScratchBufferSize
NVSDK_NGX_VULKAN_CreateFeature           NVSDK_NGX_VULKAN_GetFeatureRequirements
NVSDK_NGX_VULKAN_CreateFeature1          NVSDK_NGX_VULKAN_RequiredExtensions
NVSDK_NGX_VULKAN_EvaluateFeature         NVSDK_NGX_VULKAN_ReleaseFeature
NVSDK_NGX_VULKAN_Shutdown                NVSDK_NGX_VULKAN_Shutdown1
```

Plus les familles `_CUDA_`, `_D3D11_`, `_D3D12_`, et — remarquable — six exports
`NVSDK_NGX_OTA_UPDATES_*` (`CheckForUpdate`, `GetPath`, `Install`, `Register`, `Unregister`,
`Update`). **VÉRIFIÉ.**

### 1.c — Panama les résout-il ? Oui.

Programme lancé sur le JDK du projet, sortie intégrale :

```
Java 25.0.4.1+1-LTS
Fichier : ...\nvaci.inf_amd64_b9b853632e3b644f\_nvngx.dll  existe=true
  NVSDK_NGX_VULKAN_Init                            OK  @0x7fff28c1c730
  NVSDK_NGX_VULKAN_Init_Ext2                       OK  @0x7fff28c1ca00
  NVSDK_NGX_VULKAN_Init_ProjectID_Ext              OK  @0x7fff28c1cd70
  NVSDK_NGX_VULKAN_GetCapabilityParameters         OK  @0x7fff28c1bce0
  NVSDK_NGX_VULKAN_AllocateParameters              OK  @0x7fff28c1bb40
  NVSDK_NGX_VULKAN_CreateFeature                   OK  @0x7fff28c1d230
  NVSDK_NGX_VULKAN_EvaluateFeature                 OK  @0x7fff28c1d4e0
  NVSDK_NGX_VULKAN_ReleaseFeature                  OK  @0x7fff28c1d3e0
  NVSDK_NGX_VULKAN_Shutdown1                       OK  @0x7fff28c1cfd0
  NVSDK_NGX_VULKAN_GetScratchBufferSize            OK  @0x7fff28c1d0e0
  NVSDK_NGX_VULKAN_RequiredExtensions              OK  @0x7fff28c1bb10
  NVSDK_NGX_VULKAN_GetFeatureRequirements          OK  @0x7fff28c1d600
  NVSDK_NGX_Parameter_SetI                         ABSENT
downcallHandle NVSDK_NGX_VULKAN_Shutdown1 : MethodHandle(MemorySegment)int
```

`SymbolLookup.libraryLookup(Path, Arena)` ouvre la DLL **par chemin absolu**, ce qui règle le
problème du magasin de pilotes : on n'a pas besoin que le dossier soit dans le chemin de
recherche du système, il suffit de le trouver. **VÉRIFIÉ.**

La bibliothèque statique `nvsdk_ngx_s.lib` du SDK n'est donc **pas** un obstacle. C'est bien une
bibliothèque **statique** et non une bibliothèque d'importation — elle a été téléchargée et
disséquée : archive `!<arch>` de 12 membres, 542 symboles, **zéro `__IMPORT_DESCRIPTOR`**, du vrai
code x64 en `.text$mn`, et jusqu'aux chemins de compilation de NVIDIA laissés dans les
informations de débogage. Mais son contenu est un enrobage qui fait `LoadLibrary` +
`GetProcAddress` sur `_nvngx.dll` : on y retrouve, en UTF-16, les chaînes `\_nvngx.dll`,
`\nvngx.dll`, `NGXCore\NGXPath`, et en ASCII les noms exacts passés à `GetProcAddress`. Panama
fait le même travail, en Java. Les suffixes `_s` / `_d` ne désignent d'ailleurs pas statique /
dynamique mais le choix de la bibliothèque d'exécution C de MSVC (`/MT` contre `/MD`).

**Et il y a mieux que balayer le magasin de pilotes.** L'en-tête `nvsdk_ngx_loader.h` publie
l'ordre de découverte officiel : à côté de l'exécutable, puis `D3DKMTQueryAdapterInfo`, puis la
base de registre — `HKLM\System\CurrentControlSet\Services\nvlddmkm\NGXCore` → `NGXPath`. Sur
cette machine, cette clé et la clé héritée `HKLM\SOFTWARE\NVIDIA Corporation\Global\NGXCore`
pointent vers **deux condensats différents** : la seconde est périmée. Le balayage retenu dans
`Deep.loader()` prend le fichier le plus récemment déposé, ce qui donne le même résultat sans
dépendre d'une clé de registre.

### 1.d — L'os : `NVSDK_NGX_Parameter` est du C++, pas du C

La dernière ligne du relevé est le contrôle négatif, et il est **positif au mauvais sens** :
`NVSDK_NGX_Parameter_SetI` est **ABSENT** de la table d'export, et aucun `Set`/`Get` ne s'y
trouve non plus. Ce n'est pas un oubli de NVIDIA.

`NVSDK_NGX_VULKAN_AllocateParameters` rend un `NVSDK_NGX_Parameter**`. Dans l'en-tête du SDK,
`NVSDK_NGX_Parameter` est une **classe abstraite C++** dont tous les `Set` et `Get` sont des
méthodes **virtuelles** ; les `NVSDK_NGX_Parameter_SetI(...)` qu'on lit dans les exemples de
NVIDIA sont des fonctions `inline` de l'en-tête qui font `p->Set(name, value)`. Il n'y a aucun
symbole à trouver : **il y a une table virtuelle à parcourir**.

C'est faisable en Panama — lire le pointeur de vtable en tête de l'objet, indexer, fabriquer un
`downcallHandle` avec `this` en premier argument (convention Microsoft x64 : `this` dans `RCX`) —
mais c'est **la seule partie du montage qui ne repose sur aucun contrat public**. L'ordre des
méthodes virtuelles est celui de leur déclaration dans un en-tête que NVIDIA peut réordonner,
et une erreur d'index n'échoue pas proprement : elle appelle la mauvaise fonction avec les
mauvais arguments, c'est-à-dire qu'elle plante le jeu du joueur.

**VÉRIFIÉ**, et pas seulement déduit : la bibliothèque statique `nvsdk_ngx_s.lib` du SDK a été
disséquée. Ses seize enrobages commencent tous par `48 8B 01` — `mov rax,[rcx]`, c'est-à-dire le
chargement du pointeur de table virtuelle depuis `this` — suivi de `48 8B 58 xx`, l'indexation.
Les emplacements relevés :

| indice | décalage | méthode |
|---:|---:|---|
| 0 | 0x00 | `Set(const char*, void*)` |
| 1 | 0x08 | `Set(const char*, ID3D12Resource*)` |
| 2 | 0x10 | `Set(const char*, ID3D11Resource*)` |
| **3** | **0x18** | **`Set(const char*, int)`** |
| **4** | **0x20** | **`Set(const char*, unsigned int)`** |
| 5 | 0x28 | `Set(const char*, double)` |
| **6** | **0x30** | **`Set(const char*, float)`** |
| 7 | 0x38 | `Set(const char*, unsigned long long)` |
| 8–15 | 0x40–0x78 | les huit `Get`, dans le même ordre inversé |
| 16 | 0x80 | `Reset()` — *supposé, non mesuré* |

> **Le détail qui piège.** L'ordre de déclaration dans l'en-tête de NVIDIA est
> `ULL, float, double, uint, int, D3D11, D3D12, void*`. **La table est exactement l'inverse** :
> MSVC retourne les surcharges d'un même nom. Lire l'en-tête et en déduire les indices donne la
> mauvaise réponse — pour chacun des seize.

**Et ce n'est pas le seul écart.** `nvsdk_ngx.h` prévient lui-même :

> *Functions under the same name and different function signatures exist between the NGX SDK,
> NGX Core (driver), and NGX Snippets.*

La pierre de Rosette est `nvsdk_ngx_standalone_cuda.h` — l'implémentation par `GetProcAddress`
que NVIDIA publie elle-même — et elle montre que, entre le nom du SDK et le nom exporté par le
pilote, **l'argument de version et le pointeur `FeatureCommonInfo*` sont permutés**, et que
`Shutdown1` côté pilote prend un paramètre de sortie supplémentaire qui n'existe pas dans l'en-tête
du SDK. NVIDIA ne publie cette correspondance **que pour CUDA** : pour Vulkan, il faut la déduire
et la valider au premier appel réel.

C'est le **risque numéro un** de tout portage Panama, et il ne se lève pas en lisant : il se lève
en appelant, sur une machine, et en regardant le code de retour.

---

## 2. Séquence d'appel et structures

Ordre minimal, tel que la documentation de NVIDIA le décrit :

```
NVSDK_NGX_VULKAN_Init_ProjectID_Ext(projectId, engineType, engineVersion, appDataPath,
                                    vkInstance, vkPhysicalDevice, vkDevice,
                                    vkGIPA, vkGDPA, featureCommonInfo, sdkVersion)
NVSDK_NGX_VULKAN_GetCapabilityParameters(&params)
    params->Get(NVSDK_NGX_Parameter_SuperSampling_Available, &available)   <- vtable
NVSDK_NGX_VULKAN_AllocateParameters(&params)
    params->Set(...)                                                       <- vtable
NVSDK_NGX_VULKAN_CreateFeature(cmdBuffer, NVSDK_NGX_Feature_SuperSampling, params, &handle)
NVSDK_NGX_VULKAN_EvaluateFeature(cmdBuffer, handle, params, callback)      <- une fois par image
NVSDK_NGX_VULKAN_ReleaseFeature(handle)
NVSDK_NGX_VULKAN_DestroyParameters(params)
NVSDK_NGX_VULKAN_Shutdown1(vkDevice)
```

Les structures qui franchissent la frontière ABI, et qu'il faudrait décrire au `MemoryLayout`
près :

| structure | rôle | taille (x64) |
|---|---|---|
| `NVSDK_NGX_PathListInfo` | la liste des dossiers où chercher les modèles | **16** — chaînes **UTF-16**, pas UTF-8 |
| `NVSDK_NGX_LoggingInfo` | rappel de journal | **16** |
| `NVSDK_NGX_FeatureCommonInfo` | ce qu'on passe à `Init_Ext2` | **40** |
| `NVSDK_NGX_Resource_VK` | une image Vulkan décrite à NGX | **56** — union de 48 + discriminant + `bool` |
| `NVSDK_NGX_DLSS_Create_Params` | la création de la fonctionnalité | **28** |
| `NVSDK_NGX_VK_DLSS_Eval_Params` | les entrées d'une image | **368** |

Les sept premières tailles ne sont pas calculées mais **relevées dans les enregistrements
`LF_STRUCTURE` des informations de débogage CodeView** de `nvsdk_ngx_s.lib`, et elles concordent
avec le calcul d'alignement naturel — ce qui valide la méthode pour les structures Vulkan, dont
le débogage ne porte pas trace puisqu'elles sont construites côté application.

**Une bonne nouvelle, qui réduit beaucoup le travail :** `NVSDK_NGX_VK_DLSS_Eval_Params` et
`NVSDK_NGX_DLSS_Create_Params` **ne franchissent jamais la frontière ABI**. Les macros
`NGX_VULKAN_CREATE_DLSS_EXT` et `NGX_VULKAN_EVALUATE_DLSS_EXT` ne sont pas des macros mais des
fonctions `static inline` qui se contentent de décomposer ces structures en une cinquantaine
d'appels `Set` nommés (`"Width"`, `"Jitter.Offset.X"`, `"MV.Scale.X"`, `"DLSS.Pre.Exposure"`…). En
Java, on les réécrit : il n'y a rien à disposer en mémoire.

**La seule structure à construire réellement est `NVSDK_NGX_Resource_VK`**, et elle l'est quatre
fois par image — couleur, profondeur, vecteurs, sortie :

```
NVSDK_NGX_Resource_VK                       taille 56, alignement 8
   0..47  union Resource          (48 = max(ImageViewInfo 48, BufferInfo 16))
            0  ImageView   JAVA_LONG     (handle Vulkan non distribuable = uint64)
            8  Image       JAVA_LONG
           16  SubresourceRange : aspectMask, baseMipLevel, levelCount,
               baseArrayLayer, layerCount            (5 × JAVA_INT)
           36  Format      JAVA_INT
           40  Width       JAVA_INT
           44  Height      JAVA_INT
  48       Type        JAVA_INT        (0 = vue d'image, 1 = tampon)
  52       ReadWrite   JAVA_BOOLEAN    (1 octet)
  53..55   remplissage
```

Une erreur d'un octet ici ne se voit pas à la compilation, n'écrit rien au journal, et se voit à
l'écran — ou fait tomber le pilote.

Constantes utiles, relevées dans `nvsdk_ngx_defs.h` : `NVSDK_NGX_Version_API = 0x0000015`,
`Result_Success = 0x1`, `Result_Fail = 0xBAD00000`, `Feature_SuperSampling = 1`, et le drapeau
`DepthInverted = 1 << 3` — **dont la 26.2 a besoin**, puisque sa profondeur est inversée.

---

## 3. Restrictions d'accès natif en Java 25 — c'est un avertissement, et il ne se répète pas

Question réelle du dépôt : un mod ne peut pas ajouter `--enable-native-access` à la ligne de
commande du joueur. Est-ce bloquant ? Est-ce que le journal se remplit ?

**Non aux deux.** Preuve — cinq appels successifs à une méthode restreinte :

```
--- 5 appels successifs a une methode restreinte ---
WARNING: A restricted method in java.lang.foreign.SymbolLookup has been called
WARNING: java.lang.foreign.SymbolLookup::libraryLookup has been called by Repet in an unnamed module
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled

appel 0 -> true
appel 1 -> true
appel 2 -> true
appel 3 -> true
appel 4 -> true
--- 3 reinterpret (restreint aussi) ---
reinterpret 0 -> taille 8
reinterpret 1 -> taille 8
reinterpret 2 -> taille 8
```

L'avertissement est émis **une fois par module**, pas une fois par appel — et `reinterpret`, qui
serait appelé des dizaines de fois par image pour lire une vtable, n'en émet aucun une fois le
module signalé. Le journal du jeu ne se remplit donc pas. **VÉRIFIÉ.**

Le mode strict existe et il **refuse** :

```
$ java --illegal-native-access=deny ...
java.lang.IllegalCallerException: Illegal native access from an unnamed module
    at java.base/java.lang.Module.ensureNativeAccess(Module.java:307)
    at java.base/java.lang.foreign.SymbolLookup.libraryLookup(SymbolLookup.java:330)
```

Il n'est **pas** le défaut en Java 25 (le premier essai, sans aucune option, a fonctionné). Mais la
formule « will be blocked in a future release » est celle de la JEP : **le défaut basculera sur
`deny`**. Le jour venu, ce chemin s'éteindra tout seul chez les joueurs — d'où l'obligation que
l'échec retombe proprement sur FSR, ce que l'architecture de `Scene.give()` sait déjà faire.

**SUPPOSÉ** : la date de bascule. Elle n'est pas annoncée pour une version précise.

---

## 4. Licence — le vrai mur, et il n'est pas technique

`nvngx_dlss.dll` n'est **pas** installé par le pilote (§1.a, vérifié). Il faudrait donc soit
l'embarquer, soit le télécharger. **Les deux sont fermés**, et le texte est sans ambiguïté.

Le `LICENSE.txt` du dépôt `NVIDIA/DLSS` est la *NVIDIA RTX SDKs License*. Sa **clause 4.e** :

> **You may not use the SDK in any manner that would cause it to become subject to an open source
> software license.** As examples, licenses that require as a condition of use, modification,
> and/or distribution that the SDK be: (i) disclosed or distributed in source code form;
> (ii) licensed for the purpose of making derivative works; or (iii) redistributable at no charge.

C'est une **clause anticopyleft explicite**, et ses trois exemples décrivent mot pour mot les
articles 6, 5 et 4 de la GPL-3.0. **VÉRIFIÉ** (texte du dépôt).

Trois blocages indépendants, chacun suffisant à lui seul :

1. **GPL article 7.** La liste des restrictions supplémentaires tolérées y est *limitative*.
   Interdiction de rétroingénierie (4.a), interdiction de modification (4.b), interdiction de
   sous-licencier, restriction d'usage aux GPU NVIDIA, **notification écrite obligatoire à NVIDIA
   avant toute diffusion** (§ 4, y compris pour « a plug-in to a commercial application »),
   attribution soumise à **approbation écrite préalable** : rien de tout cela n'est autorisé.
2. **GPL article 6.** Obligation de fournir la source correspondante. Elle n'existe pas, et NVIDIA
   en interdit la divulgation. L'exception « System Libraries » ne s'applique pas : le guide de
   programmation demande de poser la DLL **à côté de l'exécutable**, ce n'est donc pas un
   composant du système.
3. **Clause 4.e + résiliation automatique (§ 12).** Le seul fait de placer le binaire dans une
   archive GPL viole la licence et met fin au droit de redistribution.

> L'agrégation simple (GPL article 5) ne sauve pas le montage : une DLL chargée dans le même
> processus, sans laquelle la fonctionnalité annoncée n'existe pas, ne remplit pas le critère
> « not combined with it such as to form a larger program ».

**Conséquence pratique, et elle change la conception :** le téléchargement à la demande **n'est
pas** la sortie qu'on croyait. Télécharger, c'est distribuer — cela replace le mod exactement dans
le rôle que 4.e interdit. `client/screen/Fetch.java` reste un excellent mécanisme, mais **le
problème de DLSS n'est pas celui de FFmpeg** : FFmpeg est sous une licence qui autorise la
redistribution, pas NVIDIA.

La seule posture défendable est donc : **ne rien redistribuer, ne rien télécharger, et charger le
fichier s'il est déjà là**, déposé par le joueur — ce que `Deep.libraryPresent()` fait déjà, et
qui suffit. C'est aussi la moins satisfaisante, puisqu'elle suppose un joueur qui sache d'où sortir
un fichier de 59 Mio.

> *Ceci est une lecture de textes de licence, pas un avis juridique.*

**Une piste écartée après vérification.** Les six exports `NVSDK_NGX_OTA_UPDATES_*` et l'arbre
`C:\ProgramData\NVIDIA\NGX\models\` laissaient espérer que le pilote aille chercher le modèle
lui-même — auquel cas la question de la redistribution disparaissait. Le modèle DLSS y est bien
livré en blocs `.bin` indexés par `nvngx_config.txt` (`app_<identifiant> = <version>`), **mais
l'index est trié par identifiant d'application enregistré chez NVIDIA**. Sans identifiant — et un
mod n'en a pas — la voie OTA n'est pas fiable. **SUPPOSÉ, forte confiance** : ces exports ne sont
documentés nulle part.

---

## 4 bis. FSR 2 — licence, et un piège à ne pas manquer

**Le bon dépôt est `GPUOpen-Effects/FidelityFX-FSR2`**, sous **MIT** sans clause additionnelle
(`LICENSE.txt`, « Copyright (c) 2022-2023 Advanced Micro Devices, Inc. »). MIT entre sans
difficulté dans la GPL-3.0 : sa seule obligation, la préservation des notices, relève de
l'article **7(b)** de la GPL, qui l'autorise nommément. **VÉRIFIÉ.**

> **Piège.** Le dépôt successeur `GPUOpen-LibrariesAndSDKs/FidelityFX-SDK` **n'est plus MIT à
> partir de la version 2.1.0** : son `docs/license.md` dit « distribute copies of the Software,
> **in binary form only** » et « **No reverse engineering, decompilation, or disassembly** ». Seuls
> les dossiers d'exemples restent MIT, et le README entretient la confusion en annonçant que « AMD
> FSR Samples are open source ». **Ne jamais partir de `FidelityFX-SDK/main`** ; partir de
> `GPUOpen-Effects/FidelityFX-FSR2@master`, ou au pire des tags `v2.0.0` / `fsr3-v3.0.4`.

**Le portage en GLSL pur est établi, pas espéré.** Le dépôt d'AMD contient déjà un backend Vulkan
**en GLSL** (`src/ffx-fsr2-api/vk/`, compilé par `glslang` avec `-DFFX_GLSL=1`), et le cœur
`ffx_fsr2.cpp` ne touche jamais à une API graphique — tout passe par des rappels. Deux précédents
lisibles : **Godot** (`thirdparty/amd-fsr2`, qui inclut les `.glsl` d'AMD depuis ses propres
nuanceurs et documente scrupuleusement version, commit, licence et correctifs — modèle à copier)
et **JuanDiegoMontoya/FidelityFX-FSR2-OpenGL**, un backend **OpenGL** d'environ 1 200 lignes, avec
un compte rendu écrit du portage.

L'ordre réel d'exécution des passes, lu dans `fsr2Dispatch()` et **différent de l'ordre de
l'énumération** — c'est le genre de détail qui coûte une journée :

```
0. (option) TCR_AUTOGENERATE -> GENERATE_REACTIVE
1. COMPUTE_LUMINANCE_PYRAMID        (SPD)
2. RECONSTRUCT_PREVIOUS_DEPTH       (résolution de rendu)
3. DEPTH_CLIP                       (résolution de rendu)
4. LOCK                             (résolution de rendu)
5. ACCUMULATE  ou  ACCUMULATE_SHARPEN   (résolution d'affichage)
6. RCAS                             (résolution d'affichage, si raffermissement)
```

Frictions connues pour un portage vers OpenGL : les `layout(set = …)` n'ont pas d'équivalent,
textures et échantillonneurs séparés doivent être refondus en `sampler2D`, et
`GL_EXT_samplerless_texture_functions` n'existe pas. Le repli `FFX_HALF=0` évite la demi-précision.

---

## 5. Ce qui est vrai quel que soit le verdict

Ces trois points ont été revérifiés dans `C:/mc262src/` et sont acquis pour **DLSS comme pour
FSR 2** :

### Le point d'ancrage du jitter — confirmé, et meilleur que décrit

`net/minecraft/client/renderer/GameRenderer.java` :

- **ligne 539** : `Matrix4f projectionMatrix = new Matrix4f(cameraState.projectionMatrix);`
  — la copie locale ;
- **ligne 561** : `RenderSystem.setProjectionMatrix(this.levelProjectionMatrixBuffer.getBuffer(projectionMatrix), ProjectionType.PERSPECTIVE);`

et dans `ProjectionMatrixBuffer.java`, la surcharge visée fait exactement ce qu'on espérait :

```java
public GpuBufferSlice getBuffer(Matrix4f projectionMatrix) {
    this.lastUploadedProjection = null;
    this.projectionMatrixVersion = -1L;
    return this.writeBuffer(projectionMatrix);
}
```

Elle **invalide le cache de version** et réécrit l'UBO à chaque appel. Un décalage sous-pixellaire
différent par image s'y insère sans rien contourner. **VÉRIFIÉ.**

### La profondeur — confirmée inversée, et effacée plus tôt qu'annoncé

Le brief donnait la ligne 443. Il y a **deux** effacements, et le premier n'est pas celui-là :

- **ligne 578**, *à l'intérieur de* `renderLevel`, juste avant `renderItemInHand` : c'est **là** que
  la profondeur du monde meurt ;
- **ligne 443**, dans `render()`, avant l'interface : c'est le second.

Les deux s'écrivent `clearDepthTexture(this.mainRenderTarget.getDepthTexture(), 0.0)`. L'effacement
à **0,0** confirme la **profondeur inversée** : le proche vaut 1,0, le lointain 0,0. **VÉRIFIÉ.**

Et il y a mieux qu'un mixin : **ligne 572**, quatre lignes avant l'effacement, NeoForge émet
`RenderLevelStageEvent.AfterLevel`. C'est le moment exact où la profondeur du monde est complète
et encore intacte. La copie peut s'y prendre **sans toucher à un mixin**.

### Le `VkDevice`

Tous les accesseurs de `VulkanDevice` sont publics en 26.2 ; seul `GpuDevice.backend` demande un
accesseur de mixin. Repris du relevé de `Deep.java`, **non revérifié dans cette enquête**.

---

## 6. Recommandation

**Ne pas écrire la liaison NGX.** Pas « pas maintenant » : **pas**, tant que la licence est celle-là.

L'enquête renverse le diagnostic technique — NGX est joignable, aucun C++ n'est nécessaire — et le
remplace par un diagnostic **juridique** que le code ne peut pas contourner. La clause 4.e vise
nommément les licences copyleft. Un mod GPL-3.0-only ne peut ni embarquer `nvngx_dlss.dll`, ni le
télécharger pour le joueur. Reste le chargement d'un fichier déjà déposé — ce que `Deep` fait
déjà, et qui ne justifie pas, à soi seul, d'écrire une liaison Panama vers une table virtuelle
C++ dont l'ABI diverge de la documentation et qu'on ne peut pas exécuter ici.

Les cinq raisons, empilées, disent toutes la même chose :

1. la licence interdit la redistribution dans une œuvre GPL ;
2. le modèle n'est sur aucune machine par défaut ;
3. l'ABI réelle du pilote diffère de l'ABI publiée, et n'est documentée que pour CUDA ;
4. l'accès natif deviendra refusé par défaut dans un JDK futur, sans qu'un mod puisse y répondre ;
5. le public est plafonné à Vulkan, marqué expérimental.

**Ce qu'il faut écrire à la place, dans cet ordre :**

1. ~~Rendre le basculement sur Vulkan atteignable en un clic.~~ **Fait** — `client/upscale/Pivot.java`.
   Un verrou qu'on annonce sans donner la clé n'est que de la décoration.
2. ~~Le décalage de projection.~~ **Fait et éprouvé** — `client/upscale/Jitter.java`,
   `tools/EssaiJitter.java`. Non branché, et c'est volontaire : un décalage sans remonteur
   temporel dégrade l'image au lieu de l'améliorer.
3. **La copie de profondeur**, à prendre sur `RenderLevelStageEvent.AfterLevel` — voir §5, aucun
   mixin nécessaire.
4. ~~**FSR 2**, depuis `GPUOpen-Effects/FidelityFX-FSR2`.~~ **Écarté après enquête** : ses huit
   passes sont des nuanceurs de **calcul**, et Blaze3D 26.2 n'en exécute aucun — ni image de
   stockage, ni tampon de stockage, ni atomique. Le verdict et ses preuves sont dans
   `notes/fsr2-faisabilite.md`. La licence était bonne (MIT) ; c'est le moteur qui ne suit pas.
5. **Un remonteur temporel classique**, écrit en nuanceurs de fragment — la technique dont FSR 2
   et DLSS descendent tous deux. Vanilla la soutient de première main : les cibles internes de
   `PostChain` ont un champ `persistent`, c'est-à-dire un tampon d'historique. Tout s'y écrit
   en **rassemblement**, jamais en dispersion. Voir `notes/fsr2-faisabilite.md` §4.

Ce n'est pas un lot de consolation. C'est le seul des chemins qui aille jusqu'au joueur.
