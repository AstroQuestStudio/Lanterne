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

Neuf cibles sont sous licence fermée, non commerciale, sans dérivée, ou maison.
Leur code n'entre pas dans Lanterne, quelle que soit la licence de Lanterne :

| Projet | Licence | Ce qu'on en retient |
|---|---|---|
| EntityCulling | tr7zw Protective | L'idée : un raycast asynchrone vers chaque entité. Réécrit. |
| Sodium | PolyForm Shield 1.0.0 | Rien n'est copié. Lanterne cohabite avec lui. |
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
| `content/painting/*` — l'Atelier, les tableaux | **Rien.** Idée de [Immersive Paintings](https://github.com/Luke100000/ImmersivePaintings) (Luke100000), architecture entièrement différente. | GPL-3.0 (compatible) |
| `content/disc/*` — le Sillon, les disques | **Rien.** Écrit pour ce projet. | — |
| `content/screen/*` + `client/screen/*` — l'Écran, le Projecteur | **Rien n'est copié.** Aucun décodeur n'est embarqué à ce jour. Le relevé des projets étudiés et de leurs licences est dans `notes/projecteur.md` ; les deux moteurs prévus sont branchés derrière `client/screen/Engine.java` et appelés par réflexion, jamais par incorporation de code. | voir ci-dessous |
| `assets/lanterne/shaders/post/fsr_easu.fsh` | Algorithme **FidelityFX Super Resolution 1.0 (EASU)**, [AMD](https://github.com/GPUOpen-Effects/FidelityFX-FSR) — `ffx_fsr1.h` | MIT |
| `assets/lanterne/shaders/post/fsr_rcas.fsh` | Algorithme **FidelityFX Super Resolution 1.0 (RCAS)**, *idem* | MIT |
| `client/upscale/Jitter.java` — le décalage sous-pixellaire | **Rien n'est copié.** La suite de Halton est une construction mathématique publiée en 1960, sans titulaire. Le *choix* des bases 2 et 3 et la règle de période `8 × (échelle)²` sont la convention commune à DLSS, FSR 2, Unreal et Unity ; c'est une recommandation, pas du code. | — (domaine public) |
| `client/upscale/Pivot.java` — le basculement Vulkan | **Rien.** Écrit pour ce projet. N'appelle que des méthodes **publiques** de `net.minecraft.client.Options` (`preferredGraphicsBackend`, `isRestartRequiredToApplyVideoSettings`, `save`). | — |
| `client/upscale/Deep.loader()` + `tools/EssaiNgx.java` — le relevé NGX | **Rien.** Les noms de fonctions `NVSDK_NGX_*` sont lus dans la **table d'export** de la DLL que le pilote installe ; aucun en-tête, aucune ligne de source du SDK de NVIDIA n'a été copiée ni même consultée. Voir `notes/dlss-panama.md`. | — |
| `core/Digue.java` + `mixin/{BeeHive,BeeHoming,MapChunk,GameEventChunk,SleepBed,RemoveBlock}Mixin.java` — la digue | Idée et points d'accroche de [ServerCore](https://github.com/Wesley1808/ServerCore) (Wesley1808), paquet `optimizations/sync_loads` | MIT |
| `core/Sommaire.java` + `mixin/PackIndexMixin.java` — le sommaire des zip | Idée de [quick-pack](https://github.com/DrexHD/quick-pack) (DrexHD) | **GPL-3.0-only** |
| `client/Tampon.java` + `mixin/BufferStorageMixin.java` — le tampon mutable | Idée de [framepace](https://github.com/lap2ka/framepace) (Lap2ka), lui-même rétroportage du correctif **Mojang MC-307596** livré en 26.3 | MIT |

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
| **MCEF / Rinku** (Chromium embarqué) | **LGPL-2.1-or-later** — la clause « or later » est ce qui le rend compatible ; une LGPL-2.1-**only** ne le serait pas | oui, mais sans objet | mod compagnon **que le joueur installe lui-même**, appelé par réflexion. Jamais téléchargé ni redistribué par Lanterne |

[Dream Displays](https://github.com/arnodoelinger/dreamdisplays) (LGPL-3.0) a été
lu pour établir qu'un décodage FFmpeg avec accélération matérielle se pratique en
26.2 et sous quelle licence ; aucune de ses lignes n'a été reprise.

## Les modules d'origine

Tout ce qui est listé dans `README.md` à la date du passage en GPL-3.0 a été
écrit pour ce projet et mesuré sur ses propres bancs : les entonnoirs par lots,
la fusion au sol, l'enclos, la Vigie, le Balai, la compression réseau, le minage
à l'horloge réelle, les cadences d'entités, le Carnet et la Boussole d'Ancre.
