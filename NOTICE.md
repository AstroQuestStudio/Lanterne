# Origines et attributions

Lanterne est publié sous **GPL-3.0-only** (voir `LICENSE`).

Ce choix n'est pas idéologique, il est mécanique. Le projet absorbe du travail
existant, et le copyleft remonte : pour incorporer du code LGPL-3.0 (Sodium,
Lithium, Krypton, ModernFix…) ou GPL-3.0 (MoreCulling, Exordium, RailOptimization…),
Lanterne doit être au moins aussi ouvert. Une licence permissive comme MIT ne
l'autoriserait pas — c'est un contresens fréquent : la licence « la plus ouverte »
est celle qui laisse *sortir* le code, pas celle qui laisse le plus *entrer*.

GPL-3.0-only laisse entrer MIT, Apache-2.0, CC0, Unlicense, MPL-2.0, LGPL-3.0,
LGPL-2.1-or-later et GPL-3.0. Cela couvre **50 des 59 cibles** retenues dans
`notes/MINE.md`.

## Ce qui n'est pas absorbable, et n'est donc pas absorbé

Ces cibles sont sous licence fermée, non commerciale, sans dérivée, ou maison.
Leur code n'entre pas dans Lanterne, quelle que soit la licence de Lanterne :

| Projet | Licence | Ce qu'on en retient |
|---|---|---|
| EntityCulling | tr7zw Protective | L'idée : un raycast asynchrone vers chaque entité. Réécrit. |
| Sodium | PolyForm Shield 1.0.0 | Rien n'est copié. Lanterne cohabite avec lui, et se greffe sur son écran d'options — voir plus bas. |
| TT20 | PolyForm Shield 1.0.0 | L'idée : dégrader le tick quand le TPS chute. Réécrit. |
| ai-improvements | All Rights Reserved | L'idée seule. |
| mobtimizations | All Rights Reserved | L'idée seule. |
| JEIOptimizer | All Rights Reserved | L'idée seule. |
| MemoryLeakFix | LGPL-2.1-only | Incompatible avec GPL-3. L'idée seule. |
| DSBG | CC-BY-ND-4.0 | Sans dérivée : l'idée seule. |
| server_heater | The Lambda License | L'idée seule. |
| WaterMedia | PolyForm Strict 1.0.0 | Redistribution et dérivés interdits. Son `vlcj` est shadé sous une licence commerciale de Caprica qui **ne se transmet pas aux forks**. Rien n'en est repris. |
| VideoPlayer (NGoedix) | All Rights Reserved | Le `LICENSE` fait 20 octets et dit exactement cela. L'idée seule. |
| WATERFrAMES | *aucun fichier `LICENSE`* | Donc tous droits réservés par défaut. L'idée seule. |
| Jade | CC-BY-NC-SA-4.0 | **Non commerciale et à partage identique**, donc incompatible avec la GPL-3.0. Et son API n'est pas publiée séparément : elle vit dans le mod. Rien n'en est repris, aucune dépendance n'est déclarée — voir plus bas. |
| JourneyMap | All rights reserved | `neoforge.mods.toml` le dit mot pour mot. Seul son **jar d'API** (`info.journeymap:journeymap-api-neoforge`) est un artefact distinct ; il n'est pas employé non plus — voir plus bas. |

Une licence protège l'**expression**, pas l'idée. Lire ces sources pour
comprendre un mécanisme est licite et a été fait ; en recopier les lignes ne
l'est pas et ne l'a pas été.

## Ce qui est repris, et d'où

Cette table est tenue à jour module par module au fur et à mesure de
l'absorption. Chaque entrée nomme le fichier de Lanterne, le projet d'origine et
sa licence. Tant qu'un module n'y figure pas, il est d'origine.

| Module Lanterne | Repris de | Licence d'origine |
|---|---|---|
| `core/{EntityThrottle,Cadence,Census}.java` — l'ordonnanceur de tick | **Rien.** Même idée que [Immersive Optimization](https://github.com/Luke100000/ImmersiveOptimization) (Luke100000), mécanique différente — voir ci-dessous. | GPL-3.0 (compatible) |
| `lab/Duel.java` — l'épreuve du duel | **Rien.** Écrite pour ce projet. | — |
| `core/Shroud.java` — le Voile | **Rien.** Écrit pour ce projet. | — |
| `mixin/StaticChestRendererMixin.java` | [Faster Block Entities](https://github.com/0x1bd/Faster-Block-Entities) (kvxd) | GPL-3.0-or-later |
| `mixin/StaticChestShapeMixin.java` | *idem* | GPL-3.0-or-later |
| `assets/minecraft/{blockstates,models,atlases}` *(coffres)* | [FastChest](https://github.com/FakeDomi/FastChest) (FakeDomi), via Faster Block Entities | MIT |
| `client/Foliage.java` + `mixin/LeafCullMixin.java` | **Rien.** Idée commune à [CullLeaves](https://github.com/TeamMidnightDust/CullLeaves) (MIT) et consorts. | — |
| `mixin/LeafDecayMixin.java` — chute rapide | **Rien.** Écrit pour ce projet. | — |
| `mixin/LooseItemMixin.java` — exemplaires au sol | **Rien.** Idée voisine de [fast-items](https://github.com/Noryea/fast-items-fabric) (CC0), mécanisme différent. | — |
| `mixin/ParticleVeilMixin.java` | **Rien.** Extension du Voile, écrite pour ce projet. | — |
| `mixin/BlockEntityVeilMixin.java` | **Rien.** Extension du Voile, écrite pour ce projet. | — |
| `core/Din.java` + `mixin/SoundLimitMixin.java` | **Rien.** Idée de [SoundCulling](https://github.com/Cukkoo12/SoundCulling) (MIT), écriture différente. | — |
| `core/Moulds.java` + `mixin/StateCacheDedupMixin.java` | Idée de [FerriteCore](https://github.com/malte0811/FerriteCore) (malte0811) | MIT |
| `core/Inventaire.java` — l'inventaire de la mémoire retenue | **Rien.** Écrit pour ce projet. La liste des postes à inspecter doit en revanche à [FerriteCore](https://github.com/malte0811/FerriteCore) d'avoir nommé le détecteur de concurrence, que ce dépôt n'avait pas vu. | MIT (rien n'en est copié) |
| `core/Creuset.java` — l'avis sur le ramasse-miettes | **Rien.** Écrit pour ce projet. Même intention que [memory-settings](https://modrinth.com/mod/memory-settings), qui avertit sur le dimensionnement du tas ; ici c'est le *ramasseur* qu'on regarde, et rien n'a été lu de son code. | — |
| `lab/Fonte.java` — l'épreuve du ramasse-miettes | **Rien.** Écrite pour ce projet. | — |
| `content/painting/*` — l'Atelier, les tableaux | **Rien.** Idée de [Immersive Paintings](https://github.com/Luke100000/ImmersivePaintings) (Luke100000), architecture entièrement différente. | GPL-3.0 (compatible) |
| `content/disc/*` — le Sillon, les disques | **Rien.** Écrit pour ce projet. | — |
| `content/screen/*` + `client/screen/*` — l'Écran, le Projecteur | **Rien n'est copié.** Aucun décodeur n'est embarqué à ce jour. Le relevé des projets étudiés et de leurs licences est dans `notes/projecteur.md` ; les deux moteurs prévus sont branchés derrière `client/screen/Engine.java` et appelés par réflexion, jamais par incorporation de code. | voir ci-dessous |
| `assets/lanterne/shaders/post/fsr_easu.fsh` | Algorithme **FidelityFX Super Resolution 1.0 (EASU)**, [AMD](https://github.com/GPUOpen-Effects/FidelityFX-FSR) — `ffx_fsr1.h` | MIT |
| `assets/lanterne/shaders/post/fsr_rcas.fsh` | Algorithme **FidelityFX Super Resolution 1.0 (RCAS)**, *idem* | MIT |
| `client/upscale/Jitter.java` — le décalage sous-pixellaire | **Rien n'est copié.** La suite de Halton est une construction mathématique publiée en 1960, sans titulaire. Le *choix* des bases 2 et 3 et la règle de période `8 × (échelle)²` sont la convention commune à DLSS, FSR 2, Unreal et Unity ; c'est une recommandation, pas du code. | — (domaine public) |
| `client/upscale/Scene.java` — la correction du ciel | **Rien n'est copié**, mais une *leçon* est reprise et elle valait cher : la javadoc de [Vitrail-Shaders](https://github.com/avpbynf/Vitrail-Shaders) (avpbynf, LGPL-3.0) explique pourquoi il échange les champs de texture et non l'objet — *« the sky renderer holds the object by reference across frames »*. Vérifié ensuite dans les sources de 26.2 : `SkyRenderer.java:64` garde bien un `private final RenderTarget`. C'est ce qui a révélé un défaut réel de ce dépôt. Le remède écrit ici est différent du sien. | LGPL-3.0 (compatible) |
| `assets/lanterne/shaders/post/aa_edge.fsh` — l'anticrénelage | **Rien n'est copié, et c'était obligatoire.** L'idée — détecter un contour par le contraste de luminance, en déduire une direction, fondre le long de cette direction — est celle de **FXAA**, publiée par **Timothy Lottes (NVIDIA)**. Mais `fxaa3_11.h` **ne porte aucune licence de redistribution** : seulement « COPYRIGHT (C) 2010, 2011 NVIDIA CORPORATION. ALL RIGHTS RESERVED. » suivi d'un avertissement de garantie. En reprendre une ligne serait donc une contrefaçon. Un algorithme ne se protège pas, une écriture si : celle-ci est la nôtre, et le raisonnement de chaque seuil est écrit dans le fichier. | *(aucune — d'où la réécriture)* |
| `client/upscale/Reproject.java` — la matrice de reprojection | **Rien n'est copié.** L'accumulation temporelle avec reprojection arrière est une technique publiée, antérieure à FSR 2 comme à DLSS. L'algèbre — et notamment la translation de caméra qu'impose la matrice de vue *rotation seule* de 26.2 — est démontrée dans le javadoc et éprouvée par `tools/EssaiReproject.java`. | — |
| `client/upscale/Pivot.java` + `client/upscale/Dawn.java` — le basculement Vulkan | **Rien.** Écrits pour ce projet. N'appellent que des méthodes **publiques** de `net.minecraft.client.Options` (`preferredGraphicsBackend`, `isRestartRequiredToApplyVideoSettings`, `save`), et substituent une ligne de `config/fml.toml`. Le défaut de NeoForge qu'ils contournent est décrit dans le javadoc de `Dawn`. | — |
| `client/upscale/Deep.loader()` + `tools/EssaiNgx.java` — le relevé NGX | **Rien.** Les noms de fonctions `NVSDK_NGX_*` sont lus dans la **table d'export** de la DLL que le pilote installe ; aucun en-tête, aucune ligne de source du SDK de NVIDIA n'a été copiée ni même consultée. Voir `notes/dlss-panama.md`. | — |
| `core/Digue.java` + `mixin/{BeeHive,BeeHoming,MapChunk,GameEventChunk,SleepBed,RemoveBlock}Mixin.java` — la digue | Idée et points d'accroche de [ServerCore](https://github.com/Wesley1808/ServerCore) (Wesley1808), paquet `optimizations/sync_loads` | MIT |
| `core/Sommaire.java` + `mixin/PackIndexMixin.java` — le sommaire des zip | Idée de [quick-pack](https://github.com/DrexHD/quick-pack) (DrexHD) | **GPL-3.0-only** |
| `core/Elastique.java` + `mixin/ElastiqueMixin.java` — l'élastique | **Rien n'est repris de TT20, et rien ne pouvait l'être** : voir ci-dessous. Écrit depuis les sources décompilées de 26.2 seules. | — |
| `core/network/MinecraftCipher{Decoder,Encoder}.java` — le chiffreur réseau | [Krypton](https://github.com/astei/krypton) (Andrew Steinborn) — repris quasi tel quel ; s'appuie sur `com.velocitypowered:velocity-native` (embarqué par jarJar, natif OpenSSL avec repli Java) | LGPL-3.0-only |
| `mixin/ConnectionMixin.java`, `mixin/ServerLoginPacketListenerImplMixin.java`, `core/network/ConnectionEncryption.java` — l'accroche | **Rien n'est repris.** Point d'accroche réécrit pour la 26.3 : `Connection.setEncryptionKey` y reçoit des `Cipher` déjà construits, plus la `SecretKey` brute — voir la javadoc de `ConnectionEncryption`. Ne couvre que le serveur pour l'instant, le client demande un relais entre deux méthodes non encore écrit. | — |

**L'élastique** demande une précision de licence, parce que le mod auquel on pense
d'abord est justement celui dont on ne peut rien prendre :

- **TT20 est sous `PolyForm Shield License 1.0.0`.** Vérifié dans son
  `gradle.properties` (`mod_license=PolyForm Shield License 1.0.0`), qui alimente
  les trois fichiers de métadonnées du mod. À noter : le dépôt ne contient
  **aucun fichier `LICENSE`**, alors que son `build.gradle` en référence un
  (`from(rootProject.file('LICENSE'))`). La seule déclaration de licence est donc
  cette ligne de propriétés.
- PolyForm Shield **n'est pas une licence libre** et n'est pas approuvée par l'OSI :
  sa clause de non-concurrence interdit de s'en servir pour bâtir un produit
  concurrent, ce qui est inconciliable avec la GPL-3.0. **Son code ne peut pas
  entrer ici**, et n'y est pas entré.
- Le dépôt de TT20 a donc été cloné pour lire son `LICENSE`, puis **supprimé sans
  qu'aucun de ses fichiers source n'ait été ouvert**. Ce qui a servi de point de
  départ tient dans son `README` public : la formule `ticks × tps / 20`, et l'aveu
  — le sien — qu'elle traite le symptôme et non la cause.
- Ce module ne reprend d'ailleurs pas cette formule. Il ne touche ni aux durées de
  jeu ni aux compteurs de ticks : il corrige trois seuils **anti-triche** de
  `ServerGamePacketListenerImpl.handleMovePlayer`, ce que TT20 ne fait pas.
- L'idée qu'il faille pouvoir desserrer ces seuils-là n'est pas neuve, et elle n'est
  de personne : **Spigot** expose depuis des années `moved-too-quickly-multiplier`
  et `moved-wrongly-threshold` dans son `spigot.yml`. Leur seule existence établit
  que le faux positif est réel et connu. Aucune ligne n'en est reprise — ce sont
  des réglages fixes, là où ce module indexe la tolérance sur le retard mesuré.
- Le temps de cassage d'un bloc, seul point où Lanterne rejoint vraiment le
  domaine de TT20, était déjà traité avant ce module par `mixin/MiningMixin.java`,
  et par une autre voie : l'horloge réelle plutôt qu'un facteur de TPS.

**La digue** mérite une précision, parce qu'elle s'écarte de sa source sur trois points et
qu'un lecteur pressé croirait à une copie :

- **Deux des sept mixins de `ServerCore` ont été écartés**, sur lecture des sources 26.2 et
  non par prudence. `LevelMixin` redirige `hasChunkAt` dans
  `updateNeighbourForOutputSignal` ; or `ServerChunkCache` redéfinit `hasChunk` en 26.2
  (ligne 266) et ne bloque plus. `StructureCheckMixin` n'est pas un refus de chargement mais
  un pré-test de biome pour le trésor enfoui, et il change la génération. Le détail est dans
  `Digue.REJECTED_UPDATE_NEIGHBOUR` et `Digue.REJECTED_STRUCTURE_BIOME`.
- **Un point d'accroche a été ajouté** que `ServerCore` n'a pas :
  `Bee$BeeGoToHiveGoal.canBeeUse`, qui charge le chunk de la ruche à chaque évaluation du but,
  c'est-à-dire bien plus souvent que le `getBeehiveBlockEntity` protégé par l'original.
- **Le refus a été rendu non destructif.** Chez `ServerCore`, une abeille dont le chunk de
  ruche est absent **oublie sa ruche pour de bon** : `Bee.aiStep` efface `hivePos` dès que
  `isHiveValid()` rend faux. `BeeHiveMixin` garde la mémoire tant que le seul motif d'échec est
  l'absence du chunk. L'épreuve `lab/Levee.java` le vérifie, et
  `LANTERNE_BREAK_DIGUE=1` rétablit le comportement d'origine pour qu'elle puisse échouer.

**Le sommaire** ne reprend aucune ligne de `quick-pack`, dont les neuf mixins ont pourtant été
lus. Deux différences de conception :

- `quick-pack` construit l'index à l'ouverture du paquet, en s'accrochant aux deux `RETURN` de
  `FilePackResources$FileResourcesSupplier.openFull` avec trois `@Local`. Ici l'index est bâti
  **paresseusement**, au premier `listResources` : un paquet ouvert et jamais interrogé ne paie
  rien, et il n'y a aucune dépendance au nom d'une variable locale.
- `quick-pack` réimplémente `getNamespaces` par `@WrapMethod`. Ici on se contente de **retenir**
  ce que vanilla a rendu : rejouer l'extraction d'espace de noms aurait créé une seconde vérité à
  maintenir, et sa validation avec.

**Ce qui a été lu et NON repris**, avec le motif — cette liste vaut autant que la précédente :

| Projet | Licence | Pourquoi non |
|---|---|---|
| [krypton-fnp](https://github.com/404Setup/KryptonReno) — `ServerCullingManager` | LGPL-3.0 (absorbable) | Son propre auteur le limite à `Display` et `HangingEntity` — *« Only entities whose purpose is visual decoration are safe to remove from a vanilla client »*. Les créatures, qui sont le poste de paquets, sont exclues par conception. Et le mécanisme **ajoute** jusqu'à seize `level.clip` par tick sur le fil du serveur pour économiser de la bande passante : sur un VPS à un cœur, c'est échanger la ressource rare contre l'abondante. `core/NetworkThrottle.java` espace déjà ces envois sans jamais faire disparaître l'entité. |
| [structure-layout-optimizer](https://github.com/TelepathicGrunt/StructureLayoutOptimizer) — son `TrojanVoxelShape` | MIT (absorbable) | **L'idée est reprise, le code ne l'est pas** — voir `core/Cadastre.java` et le paragraphe ci-dessous. |
| `ServerCore` — le régulateur de distances | MIT | Doublon de `core/Tide.java`. Deux boucles d'asservissement sur la même grandeur oscillent. |
| `client/ponder/*` — le guide illustré | **Rien.** Idée, vocabulaire et disposition d'écran de [Ponder](https://github.com/Creators-of-Create/Ponder) (Creators of Create), extrait de [Create](https://github.com/Creators-of-Create/Create) — voir ci-dessous. | MIT (compatible) |

Le placement des structures à jigsaw demande une précision de plus, parce que la distinction entre
« reprendre une idée » et « reprendre du code » y est tout le sujet.
**StructureLayoutOptimizer** (TelepathicGrunt, **MIT**) a trouvé le défaut et la bonne réponse : la
région libre du placeur Jigsaw est une région de départ moins un ensemble de boîtes, et la tenir dans
un index de boîtes remplace un coût en N⁴ par un coût quasi linéaire. **Cette idée est la sienne, et
elle est excellente.** `core/Cadastre.java` en découle.

**Ce qui n'a PAS été repris, et c'est délibéré** : sa classe `TrojanVoxelShape`. Elle range l'index
dans une sous-classe de `VoxelShape` qu'elle glisse à la place de celle de vanilla, rend `null` sur
`getCoords()` et porte une grille discrète `0×0×0` — si bien que `isEmpty()` y répond **vrai**.
N'importe quel autre mod qui lit cette forme reçoit soit un `NullPointerException` au fond de son
propre code, soit — bien pire — une réponse fausse et silencieuse : `Shapes.joinUnoptimized` y
rendrait `empty()` sans rien signaler. Lanterne **n'ajoute aucun type à la hiérarchie de
`VoxelShape`** : la case de vanilla continue de contenir une forme de vanilla, et le registre vit à
côté. Le prix de ce choix est nommé dans le javadoc de `Cadastre` — cette forme est *datée*, pas
fausse — et `Cadastre.Region.materialise()` la remet à jour à l'identique pour qui en aurait besoin.

Ne sont pas repris non plus ses trois autres mixins : `SinglePoolElementMixin`,
`StructureTemplateMixin` et `StructureTemplatePaletteMixin` visaient la **pose** des gabarits, que la
26.2 patchée NeoForge a rendue inutile — voir `notes/pochoir-retire.md`. Sa déduplication des listes
de pièces mélangées (`TrojanArrayList`) n'a pas été mesurée ici, et un module non mesuré n'existe pas.

L'ordonnanceur de tick mérite la même précision. **Immersive Optimization** est
sous GPL-3.0, donc absorbable sans réserve, et son dépôt a été lu en entier —
`TickScheduler`, `ServerLevelMixin`, `Config` — avant d'écrire cette ligne. Rien
n'en a été repris, pour quatre raisons qui se vérifient dans son code :

- **Il calcule la distance entité par entité**, en parcourant la liste des
  joueurs pour chacune, sur un **fil d'arrière-plan** réveillé toutes les
  500 ms. Lanterne recense des *chunks* : quatre cent quarante et un au lieu de
  cent mille entités. Et un fil d'arrière-plan est précisément ce qu'il ne faut
  pas sur la cible de ce projet — **un VPS à un seul cœur**, où ce fil vole le
  tick qu'il prétend soulager.
- **Son gradient est plus grossier** : un cran tous les 64 blocs, zone franche de
  6 blocs. Ici, un cran tous les 16 blocs et une zone franche de 24 — dégradation
  plus fine, garantie plus large.
- **Il n'a aucune compensation de production.** Une poule tickée une fois sur
  sept pond sept fois moins d'œufs, et rien ne le signale. Voir `core/Produce.java`,
  qui tient les horloges à la main pendant le sommeil.
- **Il ignore la densité.** Cinquante vaches dans un enclos à vingt blocs sont
  toutes « proches » chez lui, et ce sont elles qui coûtent le plus cher.

La comparaison a tout de même rendu quelque chose, et il faut le dire :
**Immersive Optimization exempte les chunks maintenus chargés de force**, ce que
Lanterne ne fait pas. Chez lui, `getPriority` commence par
`if (data.forcedChunks.contains(entity.chunkPosition().pack())) return 0;`. Ici,
un chunk sous `/forceload` sans joueur à proximité est classé « inconnu », donc
dégradé au plafond de son espèce — alors qu'un `/forceload` est le seul geste par
lequel un joueur dit explicitement « continue de simuler ceci pendant mon
absence ». `ServerLevel#getForceLoadedChunks()` est public en 26.2 et rend
directement le jeu de chunks concernés ; le correctif tient en quelques lignes
dans le recensement. **Constat ouvert, non corrigé à ce jour.**

Les tableaux méritent le même genre de précision que le Voile. **Immersive
Paintings** est sous GPL-3.0, donc parfaitement absorbable ; son code n'a pourtant
pas été repris, parce que ce qui coûte cher chez lui est justement ce qu'on
voulait éviter. Trois différences de fond, toutes mesurables :

- **Une texture par tableau et par palier de distance** chez lui — jusqu'à cinq
  par image, toutes résidentes — contre **une seule mosaïque** pour tout le monde
  ici. Vingt tableaux à l'écran : cent changements de texture chez lui, un chez
  nous. Voir `content/painting/Mosaic.java`.
- **Un niveau de détail calculé en Java à chaque image** — distance, champ de
  vision, tangente, par tableau visible — contre des **mipmaps matériels**, qui
  font le même travail gratuitement et mieux. Voir `content/painting/Mill.java`.
- **Une géométrie repoussée sommet par sommet depuis des fichiers `.obj`** pour
  les cadres, contre pas de cadre du tout et une géométrie de quelques quads.

Ce qui lui est repris, en revanche, est son **idée**, qui est bonne : nommer une
image par l'empreinte de son contenu, annoncer un catalogue, et ne transmettre
que ce qui manque. La reprise s'arrête là, et le chemin de migration depuis son
format est décrit dans `content/painting/Relic.java`.

La mise à l'échelle mérite une précision, parce qu'elle touche à du code de
constructeur. **FSR 1.0** est publié par AMD sous licence **MIT**, que la GPL-3.0
absorbe sans difficulté. Les deux nuanceurs de `shaders/post/` sont écrits
**d'après l'algorithme publié** — la disposition des douze échantillons, le noyau
de Lanczos étiré le long du contour, la limite de 0,1875 de RCAS — et non copiés
ligne à ligne depuis `ffx_fsr1.h`. Les constantes n'ont pas été confrontées au
fichier d'origine ; le garde-fou de voisinage placé en fin de passe EASU borne
l'effet d'une erreur éventuelle à un peu plus ou un peu moins de douceur.

[Vitrail Shaders](https://github.com/avpbynf/Vitrail-Shaders) (LGPL-3.0) a été
lu pour vérifier qu'un portage GLSL de ces noyaux se pratique et sous quelle
licence ; aucune de ses lignes n'a été reprise, et Lanterne ne dépend ni de lui
ni de Vulkan.

Quatre fichiers de nuanceur portant l'en-tête d'un mod tiers (« Salt's Anti
Aliasing ») avaient été déposés dans `assets/lanterne/shaders/post/` sans figurer
dans cette table, et sans que ce projet puisse établir leur origine ni leur
licence. Ils ont été **supprimés** et remplacés par les deux fichiers ci-dessus.

Les ressources de shulker fournies par Faster Block Entities n'ont pas été
reprises : leur couvercle qui s'ouvre est un retour d'information utile, et le
module ne concerne donc que les coffres.

L'atlas `assets/minecraft/atlases/blocks.json` **ajoute** une source sans
effacer celles de vanilla — `SpriteSourceList.load` (ligne 85) parcourt
`getResourceStack`, donc tous les paquets sont fusionnés. Vérifié dans le code
décompilé, pas supposé.

Le Voile mérite une précision, parce qu'il traite le même sujet qu'un mod connu.
**EntityCulling** (tr7zw Protective License) a été lu, et deux de ses garde-fous
ont inspiré les nôtres : ne pas voiler au-delà d'une distance, ne pas voiler une
entité à très grosse boîte. Aucune de ses lignes n'a été reprise, et les choix de
fond diffèrent — il lance ses rayons sur un fil séparé, nous les lançons sur le
fil de rendu, amortis par un cache et plafonnés par image, parce que lire
`ClientLevel` depuis un autre fil est une course de données. Le point d'accroche
diffère aussi : il annule `extractEntity`, nous enveloppons l'appel à
`shouldRender` qui le précède.

Le parcours de grille employé est l'algorithme d'Amanatides et Woo, publié en
1987 ; il n'appartient à aucun mod.

Les objets au sol méritent une précision inverse des précédentes. **fast-items**
(CC0, donc copiable sans réserve) remplace le modèle tridimensionnel par une
image plate face à la caméra. Son code n'a pas été repris — non par scrupule,
mais parce qu'il vise `BakedModel` et `ItemRenderer.render`, deux interfaces que
26.1 a supprimées au profit de l'extraction d'états et de `SubmitNodeCollector`.
Le mécanisme retenu ici est autre : plafonner le nombre d'exemplaires que vanilla
dessine par pile, qui va jusqu'à **cinq** pour une pile de plus de quarante-huit.

Le guide illustré mérite la précision la plus longue de cette page, parce que
c'est la dette d'idée la plus lourde du projet. **Ponder** — le système
d'animations explicatives de **Create**, extrait depuis en bibliothèque autonome
— est sous licence **MIT**, donc absorbable sans la moindre réserve, et son dépôt
a été lu en entier avant qu'une ligne ne soit écrite ici : `PonderStoryBoard`,
`SceneBuilder`, `PonderScene`, `PonderLevel`, `SceneTransform`, `Outliner`,
`TextWindowElement`, `PonderUI`. La lecture est consignée dans
`notes/ponder-create.md`.

**Ce qui lui est repris est son idée, et elle est excellente** : montrer une
machine au lieu de la décrire, en trois zones — chapitres, plateau, légende —,
avec des étiquettes accrochées à un point du monde dont l'ordonnée suit le décor
et l'abscisse reste figée. Cette dernière trouvaille est copiée sciemment : une
colonne de texte qui se déplacerait avec la caméra serait illisible pendant
qu'elle bouge, c'est-à-dire exactement quand on en a besoin.

**Aucune de ses lignes n'a été reprise**, et il faut dire pourquoi, parce que ce
n'est pas par scrupule :

- **Son étage de rendu ne compile pas en 26.2.** Ponder vise 1.21.1
  (`minecraft_version = 1.21.1` dans son `gradle.properties`) et repose sur
  `MultiBufferSource`, `BakedModel`, `ModelBlockRenderer.tesselateBlock`,
  `RenderSystem.setProjectionMatrix`, `Tesselator` — tous supprimés ou réécrits.
  Le porter, ce serait le réécrire en entier, à l'aveugle.
- **Son faux monde coûte cher.** Chaque section est tesselée bloc par bloc, mise
  en cache dans un `SuperByteBuffer` qui n'est pas un tampon graphique mais un
  gabarit de sommets en mémoire Java, **retransformé sommet par sommet sur le
  processeur à chaque image**. Ici, un bloc est un appel à
  `GuiGraphicsExtractor#item`, que 26.2 met en cache dans un atlas de textures :
  cent blocs coûtent cent quads d'une seule texture. Pour un mod de performance,
  ce n'était pas un compromis, c'était la bonne réponse.
- **Son modèle de temps interdit le retour arrière.** Ses instructions modifient
  un niveau tick après tick, et `PonderScene.seekToTime` commence par
  `throw new IllegalStateException("Cannot seek backwards. Rewind first.")` :
  reculer, chez lui, c'est restaurer un cliché NBT et rejouer toute la scène en
  accéléré. Sa barre de progression n'accepte donc le clic que sur des repères
  posés à la main. Ici une scène est une **fonction pure du temps** ; reculer
  coûte ce que coûte avancer, et la barre accepte n'importe quel instant.
- **Son branchement suppose JEI.** Chez Create, le geste « maintenir W sur un
  objet » passe par une greffe JEI, parce que les objets de la liste de JEI ne
  sont pas dans des cases d'inventaire. Ici il n'a coûté **aucune dépendance** :
  `ItemStack.getTooltipLines` déclenche `ItemTooltipEvent` (sources 26.2,
  ligne 926), et JEI construit ses infobulles par ce même appel — vérifié dans la
  table des constantes de `mezz.jei.library.render.ItemStackRenderer`, jar
  `jei-26.2-neoforge-30.29.0.201`. Écouter l'évènement suffit, et couvre du même
  coup l'inventaire, JEI, EMI et REI. Voir `client/ponder/Hint.java`.

Ce qui lui est perdu, en revanche, doit être dit aussi : le rendu d'objet impose
l'angle de l'inventaire, donc **notre caméra ne tourne pas**. Elle se déplace et
elle s'approche ; elle ne fait pas le tour de la scène. C'est le prix de ne pas
avoir réécrit un moteur de rendu.

Le masquage des feuilles mérite la même précision. **CullLeaves** (MIT, donc
copiable) a été lu. Son mixin *redéfinit* `skipRendering` en entier, ce qui
revient à décider seul du sort de toutes les faces de feuilles ; celui-ci
*injecte* un cas supplémentaire et laisse la méthode d'origine reprendre la main
partout ailleurs — de sorte qu'éteint, il ne change rigoureusement rien. Le
réglage à trois branches et l'accrochage à l'option `cutoutLeaves` de 26.1 n'ont
pas d'équivalent en amont.

### La projection — deux moteurs, aucun code repris

Aucun décodeur vidéo n'existe dans le jeu ni dans la machine virtuelle : tout mod
qui affiche de la vidéo appelle du natif venu d'ailleurs. Lanterne appelle **FFmpeg**,
dont il embarque les liaisons Java et télécharge les bibliothèques sur consentement.
`client/screen/Engine.java` déclare l'interface ; le second candidat, un Chromium
embarqué, n'est appelé que s'il est déjà installé par le joueur. Le dossier chiffré
est dans `notes/projecteur.md`.

| Candidat | Licence vérifiée | Compatible GPL-3.0-only ? | Comment il serait appelé |
|---|---|---|---|
| **FFmpeg via bytedeco** (`org.bytedeco:ffmpeg`) | greffon Apache-2.0 **ou** GPLv2+CE au choix ; binaires sans `--enable-gpl` en **LGPL v3** (`--enable-version3` est dans `cppbuild.sh`), avec `--enable-gpl` en **GPL v3** | **oui**, les deux variantes | **retenu et branché.** Liaisons Java (832 Kio) embarquées par jarJar ; binaires de la variante **LGPL v3** téléchargés une fois de Maven Central, empreintes SHA-256 pinées dans `client/screen/Fetch.java`. Aucune ligne de bytedeco n'est recopiée. |
| **Rinku** (ex-MCEF, Chromium embarqué) | **LGPL-2.1-or-later** — la clause « or later » est ce qui le rend compatible ; une LGPL-2.1-**only** ne le serait pas | oui, mais sans objet | **retenu et branché.** Mod compagnon **que le joueur installe lui-même**, appelé par **réflexion** (`client/screen/Chrome.java`) — aucune dépendance de compilation, aucune ligne recopiée, jamais téléchargé ni redistribué par Lanterne. Sert les lecteurs intégrables (YouTube, Vimeo, Dailymotion) ; voir `content/screen/Embed.java`. |

[Dream Displays](https://github.com/arnodoelinger/dreamdisplays) (LGPL-3.0) a été
lu pour établir qu'un décodage FFmpeg avec accélération matérielle se pratique en
26.2 et sous quelle licence ; aucune de ses lignes n'a été reprise.

Les sources de [Rinku](https://github.com/Keksuccino/Rinku) (branche `26.2.0`) ont
été lues pour en relever les **signatures publiques** — c'est ce qu'il faut pour
appeler un mod par réflexion, et cela ne constitue pas une reprise de code. Rien de
son implémentation n'entre dans Lanterne, qui fonctionne entièrement sans lui.

### La greffe chez Sodium — une API appelée, pas une ligne reprise

Sodium ne complète pas l'écran vidéo de vanilla : il le **remplace**. Son
`OptionsScreenMixin` se pose sur `OptionsScreen.lambda$init$3` — la fabrique qui
construit `VideoSettingsScreen` au clic sur « Graphismes » — en `HEAD` et
`cancellable`, et lui substitue son propre écran. Le bouton que pose
`mixin/VideoOptionsMixin.java` disparaît donc avec l'écran qui le portait, et
avec lui l'accès aux soixante-cinq réglages du mod.

Sodium publie une API pour exactement ce cas, et `client/sodium/Graft.java`
l'utilise : une **page externe** inscrite dans la colonne de gauche de son écran,
qui ouvre les cadrans au clic.

| Ce qui est employé | Licence vérifiée | Compatible GPL-3.0-only ? | Comment |
|---|---|---|---|
| `net.caffeinemc:sodium-neoforge-api:0.9.2+mc26.2` | **PolyForm Shield 1.0.0** — `LICENSE.md` lu dans l'artefact lui-même, pas sur l'étiquette du dépôt. Source ouverte à la lecture, **non libre** : clause de non-concurrence, non reconnue par l'OSI, absente de la liste des licences libres de la FSF. Le POM publié ne déclare aucun bloc `<licenses>`, l'artefact hérite donc de la licence du dépôt. | **Non, en tant que source.** Aucune ligne n'est donc reprise. | `compileOnly` dans `build.gradle`. Ni `jarJar`, ni ombrage, ni redistribution : `unzip -l` sur l'archive publiée ne trouve **aucune** entrée `caffeinemc`. Lanterne écrit sa propre implémentation de leurs interfaces ; Sodium la charge lui-même par `Class.forName` sur une chaîne de `neoforge.mods.toml`. |

Trois points à ne pas confondre, parce qu'ils ont trois réponses différentes :

1. **Copier du code Sodium** — exclu, définitivement. PolyForm Shield est
   incompatible avec la GPL-3.0, dans les deux sens. La ligne du tableau des
   non-absorbables, en tête de ce fichier, reste vraie mot pour mot.
2. **Redistribuer l'artefact** — exclu aussi, et c'est `compileOnly` qui le
   garantit mécaniquement plutôt que par promesse.
3. **Appeler l'API** — c'est l'usage que le concédant prévoit et documente. Son
   `USAGE.md` le dit sans ambiguïté : *« Historically, third-party mods have
   mixed into Sodium to add buttons to their own settings pages. With this API,
   these mods will not need to touch Sodium's internals anymore »*. La greffe
   par mixin dans ses internes — la pratique que cette API remplace — serait
   *plus* problématique, pas moins : elle couplerait Lanterne à du code
   PolyForm au lieu de s'en tenir à une frontière publiée.

Deux réserves qu'il vaut mieux écrire que découvrir :

- **La clause de non-concurrence** de PolyForm Shield interdit d'employer le
  logiciel pour fournir un produit qui lui fait concurrence, et son texte précise
  que deux choses se concurrencent « même lorsqu'elles offrent leurs fonctions
  par des interfaces de nature différente ». Lanterne ne remplace pas le moteur
  de rendu de chunks de Sodium et n'entend pas s'y substituer — il se greffe sur
  lui. L'appréciation reste néanmoins celle du concédant.
- **La liaison à l'exécution.** Une œuvre GPL-3.0 qui, chez l'utilisateur, se lie
  à une bibliothèque GPL-incompatible est le cas classique du *linking*. Ce qui
  est distribué ici, ce sont deux archives séparées, que l'utilisateur assemble
  lui-même ; Lanterne ne télécharge ni n'embarque Sodium. Si le doute devait être
  levé formellement, la voie prévue est une *permission additionnelle* au titre
  de l'**article 7 de la GPL-3.0**, autorisant explicitement la liaison avec
  Sodium. Ceci est un raisonnement, pas un avis juridique.

### Les compagnons du modpack — lus, jamais repris

Le modpack visé associe Lanterne à **Jade, JourneyMap, Sodium, Distant Horizons
et JEI**. Leurs sources ont été lues pour établir la cohabitation — ce que le
`README.md` décrit dans « Le modpack ». **Aucune ligne d'aucun des cinq n'entre
dans Lanterne**, et aucune dépendance nouvelle n'a été ajoutée à `build.gradle`.

Les licences ci-dessous ont été vérifiées **dans les fichiers**, pas sur
l'étiquette des dépôts — ce projet a déjà trouvé cinq divergences entre les deux.

| Projet | Étiquette annoncée | Texte réel | Verdict |
|---|---|---|---|
| **Distant Horizons** 3.2.1 (API 7.1.0) | LGPL-3.0-only | **Confirmée.** `LICENSE.LESSER.txt` porte le texte LGPLv3, et l'en-tête des 153 fichiers qui en ont un dit *« as published by the Free Software Foundation, version 3. »* — **sans** la clause « or (at your option) any later version », qui n'apparaît que dans le corps standard des licences FSF. C'est donc bien *only*. | Absorbable en droit ; **rien n'en est repris**. `notes/MINE.md` le classe « à côté » : un moteur de niveaux de détail ne s'absorbe pas, il se côtoie. |
| *idem* — les **logos** | — | `LICENSE-LOGOS.txt` : *« Distant Horizons logos © 2024 by Pankakes are licensed under CC BY-SA 4.0 »*. **Ce n'est pas la licence du code.** | Aucun logo n'est repris. |
| *idem* — un fichier | — | `DhChunkGenerator.java` porte en plus `Copyright (C) 2021 Tom Lee (TomTheFurry)`. Et **133 des 286 fichiers** des modules dépendants de Minecraft n'ont aucun en-tête de licence : incohérence formelle, pas une re-licence. | Signalé pour mémoire. |
| **Jade** 26.2.10 | CC-BY-NC-SA-4.0 | Confirmée dans `META-INF/neoforge.mods.toml`. **Non commerciale et à partage identique**, donc incompatible avec la GPL-3.0. Aggravant : Jade ne publie **pas** de jar d'API séparé — `snownee.jade.api` vit dans le mod lui-même. | Ni reprise, ni dépendance. La greffe possible est décrite dans le `README.md` et **non faite**, faute d'une coordonnée Maven reproductible. |
| **JourneyMap** 26.2-6.0.8 | All rights reserved | Confirmée dans `META-INF/neoforge.mods.toml`. Son API est en revanche un **artefact distinct**, `info.journeymap:journeymap-api-neoforge`, embarqué par jar-in-jar sous le modId `journeymap_api`. | Ni reprise, ni dépendance. La seule version publiée pour 26.2 sur `jm.gserv.me` est un **`-SNAPSHOT` daté du 18 juin 2026**, alors que l'API livrée dans le mod est datée du 9 septembre 2026 : un instantané mouvant et périmé n'est pas une dépendance acceptable ici. |
| **JEI** 30.29.0.201 | — | Aucune dépendance n'est déclarée et aucune n'est nécessaire : `client/ponder/Hint.java` passe par `ItemTooltipEvent`, un évènement de NeoForge. | Rien à créditer. |
| **Sodium** 0.9.2 | PolyForm Shield 1.0.0 | Voir la section précédente. | `compileOnly` sur son seul jar d'API. |

Deux constats de lecture qui ne sont pas des reprises mais qui ont servi à
trancher, et qu'il est honnête de nommer :

- Le calcul du plan de coupe de Distant Horizons
  (`core/util/RenderUtil.getNearClipPlaneDistanceInBlocks`) a été **lu** pour
  établir que la Marée de Lanterne ne creuse pas de trou. Le résultat est décrit
  et cité dans le `README.md` ; aucune ligne n'est recopiée dans le code.
- Le dimensionnement de ses fils
  (`core/config/eventHandlers/presets/ThreadPresetConfigEventHandler`) a été lu
  pour établir qu'un seul cœur y rend toujours un seul fil. Même chose : c'est un
  constat, pas un emprunt.

## Les modules d'origine

Tout ce qui est listé dans `README.md` à la date du passage en GPL-3.0 a été
écrit pour ce projet et mesuré sur ses propres bancs : les entonnoirs par lots,
la fusion au sol, l'enclos, la Vigie, le Balai, la compression réseau, le minage
à l'horloge réelle, les cadences d'entités, le Carnet et la Boussole d'Ancre.
