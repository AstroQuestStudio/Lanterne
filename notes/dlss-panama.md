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

La bibliothèque statique `nvsdk_ngx_s.lib` du SDK n'est donc **pas** un obstacle : elle n'est
qu'un enrobage qui fait `LoadLibrary` + `GetProcAddress` sur cette même DLL. Panama fait le même
travail, en Java.

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

**VÉRIFIÉ** : l'absence des symboles. **SUPPOSÉ, avec une confiance élevée** : que c'est bien une
vtable C++ et non un autre encodage — l'absence totale de symbole d'accès à des paramètres que la
documentation de NVIDIA décrit comme obligatoires ne laisse pas d'autre explication.

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

| structure | rôle | difficulté Panama |
|---|---|---|
| `NVSDK_NGX_FeatureCommonInfo` | où chercher les DLL de fonctionnalité | contient un `NVSDK_NGX_PathListInfo` : tableau de `wchar_t*` + compte |
| `NVSDK_NGX_PathListInfo` | la liste de chemins elle-même | chaînes **UTF-16** sous Windows, pas UTF-8 |
| `NVSDK_NGX_Resource_VK` | une image Vulkan décrite à NGX | **union** de deux variantes + discriminant ; ~10 champs dont `VkImageView`, `VkImage`, `VkImageSubresourceRange` (5 champs), `VkFormat`, dimensions, `ReadWrite` |
| `NVSDK_NGX_VK_DLSS_Eval_Params` | les entrées d'une image | encapsule un `NVSDK_NGX_VK_Feature_Eval_Params` puis ~30 champs (couleur, profondeur, vecteurs, exposition, jitter, reset, matrices…) |
| `NVSDK_NGX_DLSS_Create_Params` | la création de la fonctionnalité | ~8 champs, dont un `NVSDK_NGX_DLSS_Feature_Flags` en bitmask |

`NVSDK_NGX_Resource_VK` doit être rempli **par image et par ressource** : au moins la couleur, la
profondeur, les vecteurs de mouvement et la sortie, soit quatre structures à union reconstruites
soixante fois par seconde, chacune avec son alignement exact. C'est là qu'est le travail, et c'est
là qu'est le risque : une erreur de remplissage d'un octet ne se voit pas à la compilation, ne se
voit pas au journal, et se voit à l'écran — ou fait tomber le pilote.

**SUPPOSÉ** pour le détail champ par champ : les en-têtes exacts n'ont pas été lus dans ce dépôt,
ils ne sont pas sur la machine. Aucune ligne de liaison ne doit être écrite avant de les avoir
sous les yeux.

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

## 4. Licence et redistribution

`nvngx_dlss.dll` n'est **pas** installé par le pilote (§1.a, vérifié). Il faut donc soit
l'embarquer, soit le télécharger.

- **L'embarquer est exclu deux fois.** La licence de NVIDIA encadre sa redistribution, et ce mod
  est en **GPL-3.0-only** : embarquer un binaire propriétaire sous conditions dans une œuvre GPL
  est une contradiction de licence, indépendamment de ce que NVIDIA autorise.
- **Le télécharger à la demande, sur consentement explicite** est la seule voie propre. Le dépôt
  est justement en train d'écrire ce mécanisme pour FFmpeg (`client/screen/Fetch.java`) : même
  problème, même forme — consentement, téléchargement, vérification d'empreinte avant chargement.
  **Il est réutilisable en l'état** et ne doit surtout pas être réécrit.

Reste une piste qui n'a pas été explorée et qui mérite de l'être : les six exports
`NVSDK_NGX_OTA_UPDATES_*` de `_nvngx.dll`, et l'arbre
`C:\ProgramData\NVIDIA\NGX\models\dlss_override\` qui contient déjà des versions de modèles sur
cette machine. `C:\ProgramData\NVIDIA\NGX\models\nvngx_config.txt` liste des versions DLSS par
identifiant d'application, dont `app_E658700 = 310.9.0`. Le chargeur sait donc aller **chercher
lui-même** le modèle. Si c'est le cas, la question de la redistribution disparaît : ce n'est plus
le mod qui distribue, c'est le pilote qui met à jour. **SUPPOSÉ, non vérifié** — ces exports ne
sont pas documentés publiquement.

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

**Ne pas écrire la liaison NGX maintenant.** Non parce qu'elle est impossible — elle ne l'est pas,
et c'est l'apport de cette enquête — mais parce que trois choses lui manquent qu'aucun code ne
remplace : le modèle `nvngx_dlss.dll`, les en-têtes du SDK pour les dispositions mémoire, et un
œil sur une image. Une liaison Panama vers une vtable C++, écrite sans les en-têtes et jamais
exécutée, n'est pas une fonctionnalité : c'est une hypothèse qui plante le pilote du joueur.

**Écrire d'abord ce qui sert dans les deux cas** — le décalage de projection et la copie de
profondeur — puis FSR 2, qui est un pur nuanceur, qui tourne sur OpenGL, et dont le public n'est
pas plafonné.

Et **d'abord avant tout** : rendre le basculement sur Vulkan atteignable en un clic. Un verrou
qu'on annonce sans donner la clé n'est que de la décoration. C'est fait, voir
`client/upscale/Pivot.java`.
